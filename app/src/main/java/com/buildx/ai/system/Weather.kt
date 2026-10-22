package com.buildx.ai.system

import org.json.JSONException
import org.json.JSONObject
import java.net.URLEncoder
import kotlin.math.roundToInt

/** Open-Meteo (free, no key): place name -> coordinates -> forecast -> one spoken sentence. No Android classes. */
object Weather {

    data class Geo(val name: String, val lat: Double, val lon: Double)

    fun geoUrl(name: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(name, "UTF-8") + "&count=1&language=en&format=json"

    fun parseGeo(body: String): Geo? {
        val root = try { JSONObject(body) } catch (e: JSONException) { return null }
        val r = root.optJSONArray("results")?.optJSONObject(0) ?: return null
        if (!r.has("latitude") || !r.has("longitude")) return null
        return Geo(r.optString("name", ""), r.getDouble("latitude"), r.getDouble("longitude"))
    }

    fun forecastUrl(g: Geo): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${g.lat}&longitude=${g.lon}" +
            "&current=temperature_2m,apparent_temperature,weather_code" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=2"

    private fun bnDigits(s: String) = s.map { if (it in '0'..'9') '০' + (it - '0') else it }.joinToString("")

    private fun n(v: Double, bn: Boolean): String = v.roundToInt().toString().let { if (bn) bnDigits(it) else it }

    /** WMO weather code -> words */
    fun describe(code: Int, bn: Boolean): String = when (code) {
        0 -> if (bn) "পরিষ্কার আকাশ" else "clear sky"
        1 -> if (bn) "প্রায় পরিষ্কার" else "mostly clear"
        2 -> if (bn) "আংশিক মেঘলা" else "partly cloudy"
        3 -> if (bn) "মেঘলা" else "overcast"
        45, 48 -> if (bn) "কুয়াশা" else "foggy"
        in 51..57 -> if (bn) "গুঁড়িগুঁড়ি বৃষ্টি" else "light drizzle"
        in 61..67 -> if (bn) "বৃষ্টি" else "rain"
        in 71..77 -> if (bn) "তুষারপাত" else "snow"
        in 80..82 -> if (bn) "দমকা বৃষ্টি" else "rain showers"
        85, 86 -> if (bn) "তুষারের ঝাপটা" else "snow showers"
        95 -> if (bn) "বজ্রসহ বৃষ্টি" else "thunderstorm"
        96, 99 -> if (bn) "শিলাবৃষ্টিসহ বজ্রঝড়" else "thunderstorm with hail"
        else -> if (bn) "আবহাওয়া অজানা" else "unknown conditions"
    }

    /** [place] is how to call the place aloud; null = the forecast was not usable */
    fun say(body: String, place: String, tomorrow: Boolean, bn: Boolean): String? {
        val root = try { JSONObject(body) } catch (e: JSONException) { return null }
        val daily = root.optJSONObject("daily") ?: return null
        val day = if (tomorrow) 1 else 0
        val max = daily.optJSONArray("temperature_2m_max")?.optDouble(day, Double.NaN) ?: return null
        val min = daily.optJSONArray("temperature_2m_min")?.optDouble(day, Double.NaN) ?: return null
        if (max.isNaN() || min.isNaN()) return null
        val dayCode = daily.optJSONArray("weather_code")?.optInt(day, -1) ?: -1
        val rain = daily.optJSONArray("precipitation_probability_max")?.optDouble(day, Double.NaN) ?: Double.NaN

        val rainTail = if (rain.isNaN()) "" else if (bn) " বৃষ্টির সম্ভাবনা ${n(rain, true)} শতাংশ।" else " Chance of rain ${n(rain, false)} percent."
        val range = if (bn) "সর্বোচ্চ ${n(max, true)}, সর্বনিম্ন ${n(min, true)} ডিগ্রি।" else "High ${n(max, false)}, low ${n(min, false)} degrees."
        val where = if (bn) "$place-এ" else "In $place"

        if (tomorrow) {
            val d = describe(dayCode, bn)
            return if (bn) "$where আগামীকাল $d। $range$rainTail" else "$where tomorrow: $d. $range$rainTail"
        }
        val cur = root.optJSONObject("current")
        val t = cur?.optDouble("temperature_2m", Double.NaN) ?: Double.NaN
        val code = cur?.optInt("weather_code", dayCode) ?: dayCode
        val d = describe(code, bn)
        val now = if (t.isNaN()) "" else if (bn) "এখন ${n(t, true)} ডিগ্রি, $d। " else "right now ${n(t, false)} degrees, $d. "
        return if (bn) "$where ${now.ifEmpty { "$d। " }}আজ $range$rainTail"
        else "$where ${now.ifEmpty { "$d. " }}Today: $range$rainTail"
    }
}
