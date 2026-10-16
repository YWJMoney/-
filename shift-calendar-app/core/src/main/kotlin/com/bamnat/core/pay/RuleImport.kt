package com.bamnat.core.pay

import com.bamnat.core.model.ExtraWork

/** What a line of a wage rule seems to say [PAYX-04]. */
enum class CandidateKind { BASE_SALARY, HOURLY_WAGE, ALLOWANCE, MULTIPLIER, RAISE, EXTRA_RULE }

/** Which premium a [CandidateKind.MULTIPLIER] line sets. */
enum class PremiumTarget(val label: String) { OVERTIME("연장"), NIGHT("야간"), HOLIDAY("휴일") }

/**
 * A suggestion read from one line of text. Nothing is applied until the user confirms it on
 * the review screen, which shows [sourceLine] next to the candidate.
 */
data class RuleCandidate(
    val sourceLine: String,
    val kind: CandidateKind,
    val name: String,
    val amount: Long? = null,
    val percent: Double? = null,
    val cadence: Cadence? = null,
    /** 0..1: how sure the reader is; low ones are shown unticked. */
    val confidence: Double,
    /** For [CandidateKind.MULTIPLIER]: the premium on top of 100% it sets. */
    val premiumTarget: PremiumTarget? = null,
    val premium: Double? = null,
    /** For [CandidateKind.EXTRA_RULE]: which marked work and how it is paid [PAYX-07]. */
    val extraWork: ExtraWork? = null,
    val extraRule: ExtraRule? = null,
)

/**
 * Reads wage rules from text: pasted sentences, or what on-device text recognition got from a
 * photo of a pay slip or company rule [PAYX-04]. Plain pattern matching, no network.
 */
object RuleImport {
    private val amountRe = Regex("""(\d{1,3}(?:,\d{3})+|\d+(?:\.\d+)?)\s*(만\s*(?:\d{1,4}\s*천)?|천)?\s*원""")
    private val manCheonRe = Regex("""(?<![\d.])(\d+)\s*만\s*(?:(\d+)\s*천)?\s*원""")
    private val percentRe = Regex("""(\d+(?:\.\d+)?)\s*%""")
    private val timesRe = Regex("""(\d+(?:\.\d+)?)\s*배""")

    fun read(text: String): List<RuleCandidate> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }.flatMap(::readLine)

    private fun readLine(line: String): List<RuleCandidate> {
        val name = nameOf(line)
        val amount = parseAmount(line)
        val percent = percentRe.find(line)?.groupValues?.get(1)?.toDouble()
            ?: timesRe.find(line)?.groupValues?.get(1)?.toDouble()?.let { it * 100 }
        val compact = line.replace(" ", "")
        extraRule(line, compact, amount, percent)?.let { return listOf(it) }
        return when {
            ("인상" in compact || "상승" in compact) && percent != null ->
                listOf(RuleCandidate(line, CandidateKind.RAISE, name.ifEmpty { "인상률" }, percent = percent, confidence = 0.8))
            "시급" in compact && amount != null ->
                listOf(RuleCandidate(line, CandidateKind.HOURLY_WAGE, "시급", amount = amount, confidence = 0.85))
            ("기본급" in compact || "기본월급" in compact) && amount != null ->
                listOf(RuleCandidate(line, CandidateKind.BASE_SALARY, "기본급", amount = amount, cadence = Cadence.MONTHLY, confidence = 0.9))
            amount != null && ("수당" in compact || "가산금" in compact || "지원금" in compact || "비" in compact.takeLast(8)) ->
                listOf(RuleCandidate(line, CandidateKind.ALLOWANCE, name.ifEmpty { "수당" }, amount = amount,
                    cadence = cadenceOf(compact), confidence = if (name.isEmpty()) 0.5 else 0.8))
            percent != null && ("연장" in compact || "야간" in compact || "휴일" in compact) -> {
                val target = when {
                    "야간" in compact -> PremiumTarget.NIGHT
                    "휴일" in compact -> PremiumTarget.HOLIDAY
                    else -> PremiumTarget.OVERTIME
                }
                // "연장 150%" is the whole rate, "연장 가산 50%" only the part on top; night is always the part on top.
                val premium = if ("가산" in compact || target == PremiumTarget.NIGHT || percent < 100) percent / 100 else percent / 100 - 1
                listOf(RuleCandidate(line, CandidateKind.MULTIPLIER, name.ifEmpty { "가산율" }, percent = percent, confidence = 0.7,
                    premiumTarget = target, premium = premium))
            }
            amount != null && name.isNotEmpty() ->
                listOf(RuleCandidate(line, CandidateKind.ALLOWANCE, name, amount = amount, cadence = cadenceOf(compact), confidence = 0.4))
            else -> emptyList()
        }
    }

    /** "대근 1회 80,000원", "특근 시간당 2만원", "대근은 통상시급의 200%, 최소 8시간 인정". */
    private fun extraRule(line: String, compact: String, amount: Long?, percent: Double?): RuleCandidate? {
        val kind = when {
            "대근" in compact || "대체근무" in compact -> ExtraWork.COVER
            "특근" in compact || "특별근무" in compact -> ExtraWork.SPECIAL
            "잔업" in compact -> ExtraWork.OVERTIME
            else -> return null
        }
        val minHours = Regex("""최소\s*(\d{1,2})\s*시간""").find(line)?.groupValues?.get(1)?.toInt()
        val noNight = Regex("야간가산(없|제외|미)").containsMatchIn(compact)
        // Amounts in a "최소 8시간" phrase are hours, not won; amountRe only matches 원 so it is safe.
        val rule = when {
            percent != null -> ExtraRule(ExtraBasis.HOURLY_RATE, multiplier = if (percent >= 100) percent / 100 else 1 + percent / 100)
            amount != null && Regex("시간당|1시간|매시간").containsMatchIn(compact) -> ExtraRule(ExtraBasis.FIXED_PER_HOUR, amount = amount)
            amount != null -> ExtraRule(ExtraBasis.FIXED_PER_TIME, amount = amount)
            else -> return null
        }.copy(minMinutes = (minHours ?: 0) * 60, nightPremium = !noNight)
        return RuleCandidate(line, CandidateKind.EXTRA_RULE, kind.label, amount = amount, percent = percent,
            confidence = 0.8, extraWork = kind, extraRule = rule)
    }

    /** "30,000원", "3만원", "3만 5천원", "1.5만원" → won. */
    fun parseAmount(line: String): Long? {
        manCheonRe.find(line)?.let { m ->
            val man = m.groupValues[1].toLong()
            val cheon = m.groupValues[2].toLongOrNull() ?: 0
            return man * 10_000 + cheon * 1_000
        }
        val m = amountRe.find(line) ?: return null
        val number = m.groupValues[1].replace(",", "").toDouble()
        val unit = m.groupValues[2].replace(" ", "")
        return when {
            unit.startsWith("만") -> (number * 10_000).toLong()
            unit == "천" -> (number * 1_000).toLong()
            else -> number.toLong()
        }
    }

    private fun cadenceOf(compact: String): Cadence = when {
        Regex("나이트|야간근무|밤근무").containsMatchIn(compact) &&
            Regex("1회|회당|근무당|1일|일당|매회").containsMatchIn(compact) -> Cadence.PER_NIGHT_SHIFT
        Regex("1회|회당|근무당|1일|일당|매회|근무일").containsMatchIn(compact) -> Cadence.PER_WORK_DAY
        else -> Cadence.MONTHLY
    }

    /** The words before the first number, without bullets and colons. */
    private fun nameOf(line: String): String =
        line.split(Regex("""[\d:：]"""), limit = 2).first()
            .replace(Regex("""^[\s\-•·*○●▶\[\(]+"""), "")
            .replace(Regex("""(월|1회|회당|근무당|매달|매월)\s*$"""), "")
            .trim()
}
