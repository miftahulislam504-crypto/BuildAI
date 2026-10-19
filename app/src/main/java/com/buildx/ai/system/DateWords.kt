package com.buildx.ai.system

/** "আজ বুধবার, ১৮ অক্টোবর ২০২৬" */
object DateWords {
    private val weekdaysBn = listOf("রবিবার", "সোমবার", "মঙ্গলবার", "বুধবার", "বৃহস্পতিবার", "শুক্রবার", "শনিবার") // Calendar.SUNDAY = 1
    private val monthsBn = listOf("জানুয়ারি", "ফেব্রুয়ারি", "মার্চ", "এপ্রিল", "মে", "জুন", "জুলাই", "আগস্ট", "সেপ্টেম্বর", "অক্টোবর", "নভেম্বর", "ডিসেম্বর")
    private val weekdaysEn = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    private val monthsEn = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    private fun d(n: Int) = n.toString().map { '০' + (it - '0') }.joinToString("")

    /** [month0]: 0 = January; [weekday]: 1 = Sunday (java.util.Calendar) */
    fun say(year: Int, month0: Int, day: Int, weekday: Int, bn: Boolean): String =
        if (bn) "আজ ${weekdaysBn[(weekday - 1).mod(7)]}, ${d(day)} ${monthsBn[month0.mod(12)]} ${d(year)}"
        else "Today is ${weekdaysEn[(weekday - 1).mod(7)]}, ${monthsEn[month0.mod(12)]} $day, $year"
}
