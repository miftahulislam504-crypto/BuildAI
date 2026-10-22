package com.buildx.ai.system

import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/** Unit and currency conversion. No Android classes. Unit keys are the ones the parser puts in the "from" / "to" slots. */
object Units {

    private enum class Dim { LENGTH, MASS, VOLUME, TEMP, DATA, MONEY }

    private class U(val dim: Dim, val factor: Double, val bn: String, val en: String, val enPlural: String = en + "s")

    private val table: Map<String, U> = mapOf(
        "km" to U(Dim.LENGTH, 1000.0, "কিলোমিটার", "kilometer"),
        "m" to U(Dim.LENGTH, 1.0, "মিটার", "meter"),
        "cm" to U(Dim.LENGTH, 0.01, "সেন্টিমিটার", "centimeter"),
        "mm" to U(Dim.LENGTH, 0.001, "মিলিমিটার", "millimeter"),
        "mi" to U(Dim.LENGTH, 1609.344, "মাইল", "mile"),
        "ft" to U(Dim.LENGTH, 0.3048, "ফুট", "foot", "feet"),
        "inch" to U(Dim.LENGTH, 0.0254, "ইঞ্চি", "inch", "inches"),
        "kg" to U(Dim.MASS, 1.0, "কেজি", "kilogram"),
        "g" to U(Dim.MASS, 0.001, "গ্রাম", "gram"),
        "lb" to U(Dim.MASS, 0.45359237, "পাউন্ড", "pound"),
        "oz" to U(Dim.MASS, 0.028349523125, "আউন্স", "ounce"),
        "maund" to U(Dim.MASS, 37.3242, "মণ", "maund"),
        "ser" to U(Dim.MASS, 0.933105, "সের", "ser", "ser"),
        "bhori" to U(Dim.MASS, 0.011664, "ভরি", "bhori", "bhori"),
        "l" to U(Dim.VOLUME, 1.0, "লিটার", "liter"),
        "ml" to U(Dim.VOLUME, 0.001, "মিলিলিটার", "milliliter"),
        "gal" to U(Dim.VOLUME, 3.785411784, "গ্যালন", "gallon"),
        "c" to U(Dim.TEMP, 1.0, "ডিগ্রি সেলসিয়াস", "degree Celsius", "degrees Celsius"),
        "f" to U(Dim.TEMP, 1.0, "ডিগ্রি ফারেনহাইট", "degree Fahrenheit", "degrees Fahrenheit"),
        "kb" to U(Dim.DATA, 1.0, "কিলোবাইট", "kilobyte"),
        "mb" to U(Dim.DATA, 1024.0, "মেগাবাইট", "megabyte"),
        "gb" to U(Dim.DATA, 1048576.0, "গিগাবাইট", "gigabyte"),
        "usd" to U(Dim.MONEY, 1.0, "ডলার", "US dollar"),
        "bdt" to U(Dim.MONEY, 1.0, "টাকা", "taka", "taka"),
        "eur" to U(Dim.MONEY, 1.0, "ইউরো", "euro", "euros"),
        "gbp" to U(Dim.MONEY, 1.0, "পাউন্ড স্টার্লিং", "British pound"),
        "inr" to U(Dim.MONEY, 1.0, "ভারতীয় রুপি", "Indian rupee"),
        "sar" to U(Dim.MONEY, 1.0, "সৌদি রিয়াল", "Saudi riyal"),
        "aed" to U(Dim.MONEY, 1.0, "দিরহাম", "dirham"),
        "jpy" to U(Dim.MONEY, 1.0, "ইয়েন", "yen", "yen"),
        "cny" to U(Dim.MONEY, 1.0, "ইউয়ান", "yuan", "yuan"),
        "myr" to U(Dim.MONEY, 1.0, "রিঙ্গিত", "ringgit", "ringgit"),
        "kwd" to U(Dim.MONEY, 1.0, "কুয়েতি দিনার", "Kuwaiti dinar")
    )

    private val defaults = mapOf(
        "km" to "mi", "mi" to "km", "m" to "ft", "ft" to "m", "cm" to "inch", "inch" to "cm", "mm" to "inch",
        "kg" to "lb", "lb" to "kg", "g" to "oz", "oz" to "g", "maund" to "kg", "ser" to "kg", "bhori" to "g",
        "l" to "gal", "gal" to "l", "ml" to "l", "c" to "f", "f" to "c", "kb" to "mb", "mb" to "gb", "gb" to "mb",
        "bdt" to "usd"
    )

    fun known(key: String) = key in table

    fun isMoney(key: String) = table[key]?.dim == Dim.MONEY

    /** what to convert to when the person named only one side; money goes to taka */
    fun defaultTarget(from: String): String? = defaults[from] ?: if (isMoney(from)) "bdt" else null

    fun sameKind(a: String, b: String): Boolean {
        val x = table[a] ?: return false
        val y = table[b] ?: return false
        return x.dim == y.dim
    }

    /** null if the two cannot be converted into each other, or money (that needs [Rates]) */
    fun convert(value: Double, from: String, to: String): Double? {
        val a = table[from] ?: return null
        val b = table[to] ?: return null
        if (a.dim != b.dim || a.dim == Dim.MONEY) return null
        if (a.dim == Dim.TEMP) {
            val celsius = if (from == "f") (value - 32.0) * 5.0 / 9.0 else value
            return if (to == "f") celsius * 9.0 / 5.0 + 32.0 else celsius
        }
        return value * a.factor / b.factor
    }

    private fun digits(s: String, bn: Boolean) = if (!bn) s else s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

    /** at most 2 decimals (more only for tiny results), no trailing zeros, no exponent; Bengali digits if [bn] */
    fun format(v: Double, bn: Boolean): String {
        if (v.isNaN() || v.isInfinite()) return "?"
        var d = BigDecimal(v).setScale(2, RoundingMode.HALF_UP)
        if (d.signum() == 0 && v != 0.0) d = BigDecimal(v).setScale(4, RoundingMode.HALF_UP)
        var s = d.toPlainString()
        if (s.contains('.')) s = s.trimEnd('0').trimEnd('.')
        if (s == "-0") s = "0"
        return digits(s, bn)
    }

    private fun name(key: String, v: Double, bn: Boolean): String {
        val u = table[key] ?: return key
        return if (bn) u.bn else if (v == 1.0) u.en else u.enPlural
    }

    /** "১০ কিলোমিটার = ৬.২১ মাইল" */
    fun say(value: Double, from: String, result: Double, to: String, bn: Boolean): String =
        "${format(value, bn)} ${name(from, value, bn)} = ${format(result, bn)} ${name(to, result, bn)}"

    fun parseValue(s: String?): Double? = s?.toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() }
}

/** Exchange rates against the US dollar (open.er-api.com, free, no key). */
object Rates {
    const val URL = "https://open.er-api.com/v6/latest/USD"

    /** code (lower case) -> units of that currency per 1 US dollar; null if the answer is not usable */
    fun parse(body: String): Map<String, Double>? {
        val root = try { JSONObject(body) } catch (e: JSONException) { return null }
        if (root.optString("result") != "success") return null
        val r = root.optJSONObject("rates") ?: return null
        val out = HashMap<String, Double>()
        val names = r.names() ?: return null
        for (i in 0 until names.length()) {
            val k = names.getString(i)
            val v = r.optDouble(k, Double.NaN)
            if (!v.isNaN() && v > 0) out[k.lowercase()] = v
        }
        return if (out.containsKey("usd")) out else null
    }

    fun convert(rates: Map<String, Double>, value: Double, from: String, to: String): Double? {
        val a = rates[from] ?: return null
        val b = rates[to] ?: return null
        return value / a * b
    }
}
