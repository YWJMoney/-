package com.bamnat.core.pay

/**
 * Whether a newly added allowance is 통상임금, guessed from its name, so the switch starts where the law
 * usually puts it (순번 285, research/shift-wage-practices.md 5장). The user can always flip it.
 *
 * - Paid every month at a set amount for the work itself: 교대·근속·자격·면허·직책·직무·위험 수당, 정액 식대·교통비
 *   (대법원 2013.12.18. 2012다89399 전원합의체; 2024.12.19. 2020다247190 전원합의체로 고정성 요건 폐기).
 *   A flat 교대수당 paid for night rotations is 통상임금 too (대법원 2021.9.30. 2019다288898).
 * - Not 통상임금: 성과급·인센티브, 실비 (출장비 등), allowances that prepay or pay by the hour of 연장·야간·휴일
 *   work, and 가족수당 that follows the number of dependants.
 * - Anything else, or a 가족수당 that may be paid alike to everyone, is left to the user (null).
 */
object OrdinaryGuess {
    private val never = Regex("성과|인센티브|실비|출장|부양|고정.*(연장|시간외|OT)|연장|시간외|특근|휴일", RegexOption.IGNORE_CASE)
    private val night = Regex("야간|나이트|심야|night", RegexOption.IGNORE_CASE)
    private val usual = Regex("교대|근속|자격|면허|직책|직무|직급|위험|기술|식대|식비|급식|교통|차량|통근")

    fun of(name: String, cadence: Cadence): Boolean? {
        val n = name.filterNot { it.isWhitespace() }
        if (n.isEmpty() || cadence == Cadence.BONUS) return null
        if (never.containsMatchIn(n)) return false
        // A night allowance paid per night or per hour stands in for the night premium; a flat monthly one
        // for being on the rotation is 통상임금 (2019다288898).
        if (night.containsMatchIn(n)) return if (cadence == Cadence.MONTHLY && "교대" in n) true else false
        if (cadence == Cadence.MONTHLY && usual.containsMatchIn(n)) return true
        return null
    }
}
