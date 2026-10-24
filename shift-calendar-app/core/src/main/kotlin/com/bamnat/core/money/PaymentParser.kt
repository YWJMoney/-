package com.bamnat.core.money

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import com.bamnat.core.time.Today

/**
 * [LEDG-02][LEDG-03] Reads card, bank and pay-app texts (문자·알림) on the device, ported from the
 * 살림왕 parser (src/parser.js 0.1.0) with its tests. The original text is never stored by callers
 * once it has been read; only the result is.
 */
object PaymentParser {
    const val VERSION = "0.1.0-kt"

    enum class Type { APPROVAL, CANCEL, DEPOSIT, WITHDRAW }

    data class Foreign(val currency: String, val amount: Double)

    data class Parsed(
        val type: Type,
        /** Won amount; null when only a foreign amount was found and the user has to give the won. */
        val amount: Long?,
        val foreign: Foreign?,
        val issuer: String?,
        val last4: String?,
        val date: LocalDate?,
        val time: LocalTime?,
        val installments: Int,
        val cumulative: Long?,
        val balance: Long?,
        val merchant: String,
        val category: String,
        val confidence: Double,
    ) {
        val isCard: Boolean get() = type == Type.APPROVAL || type == Type.CANCEL
        val needsWon: Boolean get() = amount == null && foreign != null
        val kind: MoneyKind get() = when (type) {
            Type.DEPOSIT -> MoneyKind.INCOME
            Type.WITHDRAW -> MoneyKind.TRANSFER
            else -> MoneyKind.EXPENSE
        }
        /** Ready to save without asking: a won amount and something to call it. */
        val confident: Boolean get() = amount != null && amount > 0 && (merchant.isNotBlank() || issuer != null)
    }

    sealed interface Result {
        data class Ok(val parsed: Parsed) : Result
        data class Fail(val reason: String) : Result
    }

    private val ISSUERS = listOf(
        "KB국민" to "KB국민", "국민" to "KB국민", "신한" to "신한", "현대" to "현대", "삼성" to "삼성",
        "롯데" to "롯데", "하나" to "하나", "우리" to "우리", "비씨" to "BC", "BC" to "BC",
        "NH농협" to "NH농협", "농협" to "NH농협", "IBK기업" to "IBK기업", "기업" to "IBK기업", "씨티" to "씨티",
        "카카오뱅크" to "카카오뱅크", "토스뱅크" to "토스뱅크", "케이뱅크" to "케이뱅크",
        "카카오페이" to "카카오페이", "네이버페이" to "네이버페이", "토스페이" to "토스페이", "토스" to "토스",
        "페이코" to "페이코", "우체국" to "우체국", "새마을" to "새마을금고", "수협" to "수협", "SC제일" to "SC제일",
        "iM" to "iM뱅크", "대구" to "iM뱅크", "부산" to "부산", "경남" to "경남", "광주" to "광주", "전북" to "전북",
    )
    private val ISSUER_RE = Regex("(${ISSUERS.joinToString("|") { it.first }})(카드|은행|뱅크|체크카드|체크)?")

    /** Plain words that are also bank names: "GS25 우리동네점" is not 우리카드. */
    private val PLAIN_WORDS = setOf("우리", "하나", "기업", "현대", "대구", "부산", "경남", "광주", "전북", "국민", "삼성", "롯데", "토스", "새마을", "농협", "신한", "수협", "iM")

    /** A bare bank word counts only with 카드/은행 after it, a card number after it, or at the start of the text. */
    private fun isIssuer(m: MatchResult, src: String): Boolean {
        if (m.groupValues[2].isNotEmpty() || m.groupValues[1] !in PLAIN_WORDS) return true
        val after = m.range.last + 1
        if (Regex("^\\s*\\(?\\s*[*xX]?\\d{4}").containsMatchIn(src.substring(after))) return true
        val before = src.substring(0, m.range.first)
        return Regex("^\\s*(\\[[^]]*]\\s*)*$").matches(before) || before.endsWith("\n")
    }

    private val STOPWORDS = setOf(
        "승인", "취소", "사용", "결제", "결제완료", "완료", "일시불", "할부", "체크", "신용", "해외", "국내",
        "카드", "님", "원", "누적", "잔액", "출금", "입금", "이체", "자동이체", "안내", "알림", "이용", "금액",
        "Web발신", "국외발신", "국제발신", "광고", "승인거절", "체크카드", "신용카드",
    )

    /**
     * Default dictionary; the user's own rules (가맹점 → 분류) win over it [LEDG-04]. [LEDG-19] Card
     * texts often carry the company's name, not the shop's (코리아세븐 is 세븐일레븐), and the 1.15
     * categories have their own words. The first group that matches wins, so narrower ones come first.
     */
    private val CATEGORY_HINTS = listOf(
        Regex("동물병원|(?<!카)펫|애견|애완|강아지|고양이") to "반려동물",
        Regex("스타벅스|커피|카페|이디야|투썸|메가MGC|메가엠지씨|메가커피|빽다방|컴포즈|폴바셋|할리스|블루보틀|파스쿠찌|엔제리너스|탐앤탐스|더벤티|매머드|공차|던킨|배스킨|파리바게|뚜레쥬르|베이커리|제과") to "카페",
        Regex("GS25|(?<![A-Za-z])CU(?![A-Za-z])|씨유|세븐일레븐|이마트24|미니스톱|편의점|코리아세븐|비지에프리테일|BGF리테일|지에스리테일|위드미") to "편의점",
        Regex("배달의민족|우아한형제들|배민|요기요|위대한상상|쿠팡이츠|땡겨요") to "배달",
        Regex("넷플릭스|NETFLIX|유튜브|YouTube|YOUTUBE|멜론|스포티파이|Spotify|디즈니|티빙|웨이브|왓챠|쿠팡와우|네이버플러스|밀리의서재|리디북스|RIDI|애플|Apple|APPLE|구글|Google|GOOGLE|ChatGPT|OpenAI|Claude|Anthropic") to "구독",
        Regex("CGV|메가박스|롯데시네마|영화|노래방|코인노래|PC방|피씨방|볼링|당구|골프|헬스|필라테스|요가|수영장|캠핑|야놀자|여기어때|에어비앤비|Airbnb|AIRBNB|호텔|모텔|펜션|리조트|아고다|트립닷컴|에버랜드|롯데월드|티켓링크|인터파크|놀이공원") to "여가",
        Regex("미용실|헤어|네일|피부관리|왁싱|바버|유니클로|UNIQLO|ZARA|H&M|무신사|에이블리|지그재그|29CM|스파오|탑텐|나이키|NIKE|아디다스|뉴발란스|ABC마트|슈마커|아울렛|백화점|갤러리아") to "의류·미용",
        Regex("이마트|홈플러스|롯데마트|롯데슈퍼|코스트코|쿠팡|컬리|마켓|농협하나로|하나로마트|트레이더스|노브랜드|GS더프레시|다이소|아성다이소|올리브영|11번가|G마켓|지마켓|옥션|SSG|위메프|티몬|알리익스프레스|ALIEXPRESS|테무|TEMU|스마트스토어") to "장보기·쇼핑",
        Regex("택시|카카오T|카카오모빌리티|티머니|캐시비|코레일|KTX|지하철|버스|SRT|고속|교통|대한항공|아시아나|제주항공|진에어|티웨이|에어부산") to "교통",
        Regex("주유|칼텍스|SK에너지|S-OIL|에쓰오일|오일뱅크|주차|하이패스|세차|정비|타이어|카센터") to "자동차",
        Regex("약국|병원|의원|치과|한의원|안과|내과|외과|피부과|이비인후과|소아과|산부인과|클리닉|메디컬|보건소") to "의료",
        Regex("통신|SKT|SK텔레콤|KT(?![A-Za-z&])|케이티|LG유플러스|LGU|U\\+|알뜰폰|헬로모바일") to "통신",
        Regex("관리비|관리사무소|전기|가스|수도|한전|한국전력|도시가스|지방세|국세|과태료") to "공과금",
        Regex("보험|화재|생명|손해|라이나|메리츠|현대해상") to "보험",
        Regex("학원|교습소|어학원|교보문고|영풍문고|알라딘|예스24|YES24|서점|클래스101|패스트캠퍼스|등록금") to "교육",
        Regex("키즈|유아|아기|베이비|육아|어린이집|유치원|토이저러스|장난감") to "육아",
        Regex("월세|부동산|이사|인테리어|오늘의집|한샘|이케아|IKEA|철물") to "주거",
        Regex("축의|부의|조의|화환|꽃집|플라워") to "경조사",
        Regex("세탁|빨래방|크린토피아|수선|열쇠|택배|대한통운|생활용품|모던하우스|무인양품|MUJI") to "생활",
        Regex("식당|김밥|치킨|피자|버거|맥도날드|롯데리아|맘스터치|분식|국밥|고기|횟집|교촌|굽네|BBQ|BHC|도미노|서브웨이|KFC|본죽|한솥|아웃백|빕스|애슐리|샤브|돈까스|돈가스|초밥|스시|라멘|냉면|족발|보쌈|곱창|삼겹살|포차|주점|호프|이자카야|한식|중식|일식|양식|반점|레스토랑") to "식비",
    )

    private val PAY_GATE = Regex(
        "^(?:NICE|KCP|KICC|KSNET|KIS|INICIS|KG이니시스|이니시스|나이스페이|나이스|다날|토스페이먼츠|NHN|PAYCO|페이코|스마트로|JTNET|헥토|KPN|카카오페이|네이버페이)\\s*[_*\\-./]\\s*",
        RegexOption.IGNORE_CASE,
    )
    private val CORP = Regex("\\(주\\)|㈜|주식회사|\\(유\\)|유한회사")
    private val BRANCH = Regex("(점|지점|본점|직영점)$")

    /**
     * [LEDG-17] The part of a place's name that stays the same across its branches and however a
     * card writes it: no 결제대행 prefix (NICE_, KCP_), no (주), no last word naming the branch
     * ("김밥천국 역삼점" → 김밥천국), no spaces, lower case.
     */
    fun placeKey(merchant: String): String {
        val s = merchant.trim().replace(PAY_GATE, "").replace(CORP, " ").trim()
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val kept = if (words.size >= 2 && BRANCH.containsMatchIn(words.last())) words.dropLast(1) else words
        return kept.joinToString("").lowercase()
    }

    /** [LEDG-17] Two names for the same place: one key starts the other (a shop and its branches). */
    fun samePlace(a: String, b: String): Boolean {
        val ka = placeKey(a)
        val kb = placeKey(b)
        return ka.length >= 2 && kb.length >= 2 && (ka.startsWith(kb) || kb.startsWith(ka))
    }

    /**
     * [LEDG-21] A rule the user wrote as "이 말이 들어가면": its word is kept with this mark in front.
     * No place read from a text has it, since the parser drops every piece with a '*'.
     */
    const val CONTAINS = "*"

    fun isContainsRule(key: String): Boolean = key.startsWith(CONTAINS)

    /** [LEDG-21] The word of a "이 말이 들어가면" rule as it is compared: no spaces, lower case. */
    private fun containsWord(key: String): String = key.removePrefix(CONTAINS).replace(Regex("\\s+"), "").lowercase()

    /**
     * [LEDG-17] The user's rule for [merchant]: the same name first, else the rule whose place is the
     * longest start of this one, so a rule learned at one branch covers the others. [LEDG-21] A
     * "이 말이 들어가면" rule counts when its word is anywhere in the name; the longest match wins.
     */
    fun ruleFor(merchant: String, rules: Map<String, String>): String? {
        if (merchant.isBlank() || rules.isEmpty()) return null
        rules[merchant]?.let { return it }
        val key = placeKey(merchant)
        val flat = merchant.replace(Regex("\\s+"), "").lowercase()
        return rules.entries.mapNotNull { (m, c) ->
            if (isContainsRule(m)) containsWord(m).takeIf { it.isNotEmpty() && flat.contains(it) }?.let { it to c }
            else placeKey(m).takeIf { it.length >= 2 && key.length >= 2 && key.startsWith(it) }?.let { it to c }
        }.maxByOrNull { it.first.length }?.second
    }

    fun guessCategory(merchant: String, rules: Map<String, String> = emptyMap()): String {
        if (merchant.isBlank()) return "기타"
        ruleFor(merchant, rules)?.let { return it }
        for ((re, cat) in CATEGORY_HINTS) if (re.containsMatchIn(merchant)) return cat
        return "기타"
    }

    /**
     * [LEDG-20] Texts with an amount that are not a payment: ads, bills to come, limits and points.
     * Null for anything else; the reason otherwise, and the notification listener drops these.
     */
    fun notice(text: String): String? = when {
        Regex("\\[\\s*광고\\s*]|\\(\\s*광고\\s*\\)|수신\\s*거부|무료\\s*거부|(?<!\\d)080-?\\d{3,4}-?\\d{4}(?!\\d)").containsMatchIn(text) -> "광고 문자예요"
        Regex("결제\\s*예정|청구\\s*(금액|예정|서)|이용\\s*대금\\s*명세|명세서|납부\\s*예정|출금\\s*예정|미납|연체").containsMatchIn(text) -> "카드값·청구 안내예요"
        // A pay-app receipt may mention a coupon it used; only texts that do not say a payment went through.
        !Regex("승인|결제\\s*완료|사용").containsMatchIn(text) &&
            Regex("한도\\s*(상향|변경|안내|조정)|포인트\\s*(소멸|안내)|소멸\\s*예정|이벤트|당첨|쿠폰").containsMatchIn(text) -> "안내 문자예요"
        else -> null
    }

    /** [LEDG-20] Money leaving the account for a card company is the card bill, not a transfer of one's own. */
    private val CARD_BILL = Regex("카드\\s*(대금|값|결제)|(신한|KB|국민|삼성|현대|롯데|하나|우리|비씨|BC|농협|NH)\\s*카드")

    // Absurdly long digit runs are not amounts; toLong would throw on them.
    private fun num(s: String): Long? = s.replace(",", "").toLongOrNull()
    private fun dec(s: String): Double = s.replace(",", "").toDouble()

    /** Quick filter for the notification listener: a won/foreign amount next to a payment word. */
    fun looksFinancial(text: String): Boolean =
        Regex("[\\d,]+\\s*원|(출금|입금)\\s*:?\\s*[\\d,]{3,}|(USD|US\\$|EUR|JPY|\\$)\\s*[\\d,.]+").containsMatchIn(text) &&
            Regex("승인|결제|취소|출금|입금|이체|사용").containsMatchIn(text)

    /** Several messages pasted at once, split one per message [LEDG-03]. */
    fun splitMessages(text: String): List<String> {
        val norm = text.replace(Regex("\r\n?"), "\n").trim()
        if (norm.isEmpty()) return emptyList()
        val chunks = norm.split(Regex("(?=\\[(?:Web발신|국외발신|국제발신)])|\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = ArrayList<String>()
        for (c in chunks) {
            val lines = c.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val each = lines.size > 1 && lines.all { Regex("[\\d,]+\\s*원").containsMatchIn(it) && Regex("(승인|취소|결제|출금|입금|사용)").containsMatchIn(it) }
            if (each) out += lines else out += c
        }
        return out
    }

    private data class Found(val value: Long, val raw: String)

    private fun findAmount(text: String): Found? {
        // '누적', '잔액', '한도' 바로 뒤 금액은 쓴 돈이 아니다.
        for (m in Regex("(\\d{1,3}(?:,\\d{3})+|\\d+)\\s*원").findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 6), m.range.first)
            if (Regex("(누적|잔액|한도|잔여|포인트|적립|가능)\\s*:?\\s*$").containsMatchIn(before)) continue
            return Found(num(m.groupValues[1]) ?: continue, m.value)
        }
        // 은행 문자는 '출금 50,000'처럼 '원'이 없는 때가 많다.
        val bank = Regex("(출금|입금|이체)\\s*:?\\s*(\\d{1,3}(?:,\\d{3})+|\\d{3,})").find(text) ?: return null
        return Found(num(bank.groupValues[2]) ?: return null, bank.groupValues[2])
    }

    private fun findForeign(text: String): Pair<Foreign, String>? {
        Regex("(USD|US\\$|EUR|JPY|CNY|GBP|HKD|SGD|AUD|CAD|VND|THB)\\s*([\\d,]+(?:\\.\\d+)?)", RegexOption.IGNORE_CASE).find(text)?.let {
            return Foreign(it.groupValues[1].uppercase().replace("US$", "USD"), dec(it.groupValues[2])) to it.value
        }
        Regex("([\\d,]+(?:\\.\\d+)?)\\s*(USD|달러|EUR|유로|JPY|엔|위안|파운드)").find(text)?.let {
            val map = mapOf("달러" to "USD", "유로" to "EUR", "엔" to "JPY", "위안" to "CNY", "파운드" to "GBP")
            return Foreign(map[it.groupValues[2]] ?: it.groupValues[2].uppercase(), dec(it.groupValues[1])) to it.value
        }
        Regex("\\$\\s*([\\d,]+(?:\\.\\d+)?)").find(text)?.let { return Foreign("USD", dec(it.groupValues[1])) to it.value }
        return null
    }

    /** A December text read in January belongs to last year. */
    private fun inferYear(month: Int, now: LocalDateTime): Int = if (month > now.monthValue + 1) now.year - 1 else now.year

    fun parse(text: String, now: LocalDateTime = Today.dateTime(), rules: Map<String, String> = emptyMap()): Result {
        val src = text.trim()
        if (src.isEmpty()) return Result.Fail("빈 글")
        val fx = findForeign(src)
        val amt = findAmount(src)
        if (amt == null && fx == null) return Result.Fail("금액을 찾지 못했어요")
        if (Regex("승인\\s*거절|거절").containsMatchIn(src)) return Result.Fail("승인 거절 문자예요")
        notice(src)?.let { return Result.Fail(it) }
        val type = when {
            src.contains("취소") -> Type.CANCEL
            src.contains("입금") -> Type.DEPOSIT
            Regex("출금|이체").containsMatchIn(src) -> Type.WITHDRAW
            else -> Type.APPROVAL
        }

        val iss = ISSUER_RE.findAll(src).firstOrNull { isIssuer(it, src) }
        val issuer = iss?.let { m -> ISSUERS.first { it.first == m.groupValues[1] }.second }

        // 카드 끝 4자리: 신한카드(1234), KB국민카드1234승인, *1234
        val l4 = Regex("(?:카드|체크)\\s*\\(?\\s*[*xX]?(\\d{4})\\s*\\)?").find(src)
            ?: Regex("\\(\\s*[*xX]?(\\d{4})\\s*\\)").find(src)
            ?: Regex("[*xX](\\d{4})(?!\\d)").find(src)
            ?: iss?.let { Regex("^\\s*\\(?\\s*(\\d{4})(?!\\d)").find(src.substring(it.range.last + 1)) }
        val last4 = l4?.groupValues?.get(1)

        var date: LocalDate? = null
        var dateRaw: String? = null
        // A foreign amount such as "USD 12.30" is not the 30th of December.
        val dateSrc = fx?.second?.let { src.replace(it, " ") } ?: src
        for (m in Regex("(?<![\\d,.])(\\d{1,2})\\s*[/.\\-월]\\s*(\\d{1,2})\\s*일?(?![\\d,.])").findAll(dateSrc)) {
            val mo = m.groupValues[1].toInt()
            val da = m.groupValues[2].toInt()
            if (mo in 1..12 && da in 1..31) {
                date = runCatching { LocalDate.of(inferYear(mo, now), mo, da) }.getOrNull() ?: continue
                dateRaw = m.value
                break
            }
        }
        val tm = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?!\\d)").find(src)
        val time = tm?.let { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

        val inst = Regex("(\\d{1,2})\\s*개월").find(src) ?: Regex("할부\\s*\\(?\\s*(\\d{1,2})").find(src)
        val installments = inst?.groupValues?.get(1)?.toInt()?.takeIf { it > 1 } ?: 1

        val cum = Regex("누적\\s*:?\\s*([\\d,]+)").find(src)?.let { num(it.groupValues[1]) }
        val bal = Regex("잔액\\s*:?\\s*([\\d,]+)").find(src)?.let { num(it.groupValues[1]) }

        // 가맹점: 알려진 조각을 지우고 남은 조각 가운데 마지막 것.
        var rest = src.replace(Regex("\\[[^]]*]"), "|")
        fun cut(s: String?) { if (!s.isNullOrEmpty()) rest = rest.split(s).joinToString("|") }
        cut(iss?.value); cut(amt?.raw); cut(fx?.second); cut(dateRaw); cut(tm?.value)
        rest = rest
            .replace(Regex("(누적|잔액|한도|잔여)\\s*:?\\s*[\\d,]+\\s*원?"), "|")
            .replace(Regex("\\(?\\s*[*xX]?\\d{4}\\s*\\)?"), "|")
            .replace(Regex("\\d{1,2}\\s*개월|일시불|할부"), "|")
            .replace(Regex("\\(주\\)|㈜"), "")
            .replace(Regex("(?<![A-Za-z가-힣\\d])\\d[\\d,.\\-]*"), "|")
        val pieces = rest.split(Regex("[|\\n]"))
            .flatMap { it.split(Regex("\\s{2,}")) }
            .map { it.replace(Regex("^[\\s:()·,.\\-]+|[\\s:()·,.\\-]+$"), "").trim() }
            .filter { p -> p.isNotEmpty() && !p.contains('*') && !p.endsWith("님") && p !in STOPWORDS && !Regex("^(승인|취소|결제|출금|입금)+$").matches(p) }
        val cleaned = pieces.map { p -> p.split(Regex("\\s+")).filter { w -> w !in STOPWORDS && !w.endsWith("님") }.joinToString(" ") }
            .filter { it.isNotEmpty() }
        val merchant = cleaned.lastOrNull()?.take(40).orEmpty()

        var confidence = 0.4
        if (amt != null) confidence += 0.2
        if (date != null) confidence += 0.15
        if (merchant.isNotEmpty()) confidence += 0.15
        if (issuer != null) confidence += 0.1

        val category = when (type) {
            Type.DEPOSIT -> if (src.contains("급여")) MoneyCategories.SALARY else "부수입"
            Type.WITHDRAW -> if (CARD_BILL.containsMatchIn(src)) "카드값" else "내 계좌"
            else -> guessCategory(merchant, rules)
        }
        return Result.Ok(
            Parsed(type, amt?.value, fx?.first, issuer, last4, date, time, installments, cum, bal, merchant, category,
                minOf(1.0, Math.round(confidence * 100) / 100.0)),
        )
    }

    /**
     * The same payment often arrives twice (card text and pay-app push): same kind, amount and day,
     * within ten minutes, and the same card or place.
     */
    fun findDuplicate(p: Parsed, date: LocalDate, existing: List<MoneyEntry>): MoneyEntry? {
        val amount = signedAmount(p) ?: return null
        return existing.firstOrNull { e ->
            if (e.amount != amount || e.date != date || e.kind != p.kind) return@firstOrNull false
            val t = p.time
            if (e.time != null && t != null && kotlin.math.abs(ChronoUnit.MINUTES.between(e.time, t)) > 10) return@firstOrNull false
            (e.cardLast4.isNotEmpty() && e.cardLast4 == p.last4) || (e.merchant.isNotEmpty() && e.merchant == p.merchant) || (e.time == null && t == null)
        }
    }

    /** A cancellation points back at the payment it undoes: same amount, place or card, within 30 days before. */
    fun findCancelTarget(p: Parsed, date: LocalDate, existing: List<MoneyEntry>): MoneyEntry? {
        val amount = p.amount ?: return null
        return existing.filter { e ->
            e.kind == MoneyKind.EXPENSE && e.amount == amount && !e.date.isAfter(date) &&
                ChronoUnit.DAYS.between(e.date, date) <= 30 &&
                ((p.merchant.isNotEmpty() && e.merchant == p.merchant) || (p.last4 != null && e.cardLast4 == p.last4))
        }.maxByOrNull { it.date }
    }

    /** A cancellation is a negative expense, so the month's spending drops by what came back. */
    fun signedAmount(p: Parsed): Long? = p.amount?.let { if (p.type == Type.CANCEL) -it else it }

    /** The ledger line a parsed text becomes; [date] falls back to the day it arrived. */
    fun toEntry(p: Parsed, arrived: LocalDate, source: MoneySource, category: String = p.category): MoneyEntry? {
        val amount = signedAmount(p) ?: return null
        val notes = buildList {
            if (p.type == Type.CANCEL) add("결제 취소")
            if (p.installments > 1) add("${p.installments}개월 할부")
            if (p.foreign != null) add("${p.foreign.currency} ${p.foreign.amount}")
            p.issuer?.let { add(it + (p.last4?.let { l -> " $l" } ?: "")) }
        }
        return MoneyEntry(
            date = p.date ?: arrived, kind = p.kind, amount = amount, category = category, note = notes.joinToString(" · "),
            source = source, merchant = p.merchant, time = p.time, cardLast4 = p.last4.orEmpty(),
            reviewed = source != MoneySource.NOTIFICATION,
            // [LEDG-08] Spread over that many card bills; a cancellation comes back at once.
            installments = if (p.type == Type.APPROVAL) p.installments.coerceIn(1, 60) else 1,
        )
    }
}
