package com.bamnat.core.pay

import com.bamnat.core.leave.LeaveAccrual

/**
 * [LEAVE-07] What is left of the 1.21 연차 counter: the day format only. Every number (생긴 날, 쓴 날, 남은 날, 소멸)
 * comes from the 연차 원장 ([com.bamnat.core.leave.LeaveLedger] through [com.bamnat.core.leave.LeaveSources]).
 */
object LeaveBook {
    /** "3.75일", "2일". */
    fun days(value: Double): String = LeaveAccrual.days(value)
}
