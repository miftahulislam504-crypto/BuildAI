package com.buildx.ai.system

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Tiny calculator for "৫ গুণ ৭ কত". The expression is the parser's token list: digits, "point", and
 * plus / minus / times / divided. Multiplication and division bind tighter than addition and subtraction.
 */
object Calc {

    private val ops = setOf("plus", "minus", "times", "divided")

    /** null if the expression is incomplete or divides by zero */
    fun eval(expr: String): Double? {
        val raw = expr.split(' ').filter { it.isNotEmpty() }
        // "5 point 25" -> 5.25
        val t = ArrayList<String>()
        var i = 0
        while (i < raw.size) {
            if (i + 2 < raw.size + 0 && raw[i].all { it.isDigit() } && raw.getOrNull(i + 1) == "point" && raw.getOrNull(i + 2)?.all { it.isDigit() } == true) {
                t.add(raw[i] + "." + raw[i + 2])
                i += 3
            } else {
                t.add(raw[i])
                i++
            }
        }
        if (t.isEmpty() || t.size % 2 == 0) return null
        // numbers at even places, operators at odd places
        val nums = ArrayList<Double>()
        val opsList = ArrayList<String>()
        for ((k, tok) in t.withIndex()) {
            if (k % 2 == 0) nums.add(tok.toDoubleOrNull() ?: return null)
            else if (tok in ops) opsList.add(tok) else return null
        }
        // first pass: times / divided
        var j = 0
        while (j < opsList.size) {
            if (opsList[j] == "times" || opsList[j] == "divided") {
                val r = if (opsList[j] == "times") nums[j] * nums[j + 1] else if (nums[j + 1] == 0.0) return null else nums[j] / nums[j + 1]
                nums[j] = r
                nums.removeAt(j + 1)
                opsList.removeAt(j)
            } else {
                j++
            }
        }
        var acc = nums[0]
        for (k in opsList.indices) acc = if (opsList[k] == "plus") acc + nums[k + 1] else acc - nums[k + 1]
        return acc
    }

    /** 35.0 -> "35", 2.5 -> "2.5", 0.3333 -> "0.3333"; Bengali digits if [bn] */
    fun format(v: Double, bn: Boolean): String {
        val bd = BigDecimal(v).setScale(4, RoundingMode.HALF_UP).stripTrailingZeros()
        val s = bd.toPlainString()
        return if (bn) s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("") else s
    }
}
