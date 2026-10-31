package uk.newbroman.dune

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Time and date in Polish words, for the home-screen clock. */
object PolishTime {
    val PL: Locale = Locale.forLanguageTag("pl-PL")

    private val ones = listOf(
        "zero", "jeden", "dwa", "trzy", "cztery", "pięć", "sześć", "siedem", "osiem", "dziewięć",
        "dziesięć", "jedenaście", "dwanaście", "trzynaście", "czternaście", "piętnaście",
        "szesnaście", "siedemnaście", "osiemnaście", "dziewiętnaście",
    )
    private val tens = mapOf(2 to "dwadzieścia", 3 to "trzydzieści", 4 to "czterdzieści", 5 to "pięćdziesiąt")

    /** Cardinal 0–59, as used for minutes. */
    fun cardinal(n: Int): String = when {
        n < 20 -> ones[n]
        n % 10 == 0 -> tens.getValue(n / 10)
        else -> tens.getValue(n / 10) + " " + ones[n % 10]
    }

    // Feminine ordinals (godzina): "pierwsza", "druga" … used for hours.
    private val femOrd = listOf(
        "zero", "pierwsza", "druga", "trzecia", "czwarta", "piąta", "szósta", "siódma", "ósma",
        "dziewiąta", "dziesiąta", "jedenasta", "dwunasta", "trzynasta", "czternasta", "piętnasta",
        "szesnasta", "siedemnasta", "osiemnasta", "dziewiętnasta", "dwudziesta",
    )

    fun hour(h: Int): String = when {
        h <= 20 -> femOrd[h]
        else -> "dwudziesta " + femOrd[h - 20]
    }

    /** 18:41 → "osiemnasta czterdzieści jeden"; 7:05 → "siódma zero pięć"; 0:00 → "północ". */
    fun timeWords(h: Int, m: Int): String = when {
        h == 0 && m == 0 -> "północ"
        h == 12 && m == 0 -> "dwunasta (południe)"
        m == 0 -> hour(h)
        m < 10 -> hour(h) + " zero " + ones[m]
        else -> hour(h) + " " + cardinal(m)
    }

    // Masculine ordinals (dzień) for the day of the month.
    private val mascOrd = listOf(
        "", "pierwszy", "drugi", "trzeci", "czwarty", "piąty", "szósty", "siódmy", "ósmy", "dziewiąty",
        "dziesiąty", "jedenasty", "dwunasty", "trzynasty", "czternasty", "piętnasty", "szesnasty",
        "siedemnasty", "osiemnasty", "dziewiętnasty", "dwudziesty",
    )

    fun dayOrdinal(d: Int): String = when {
        d <= 20 -> mascOrd[d]
        d < 30 -> "dwudziesty " + mascOrd[d - 20]
        d == 30 -> "trzydziesty"
        else -> "trzydziesty " + mascOrd[d - 30]
    }

    /** "wtorek, 27 października" (month in the genitive, as Polish dates are written). */
    fun dateLine(d: LocalDate): String = d.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", PL))

    /** "wtorek, dwudziesty siódmy października" – as you would say it. */
    fun dateWords(d: LocalDate): String =
        d.format(DateTimeFormatter.ofPattern("EEEE", PL)) + ", " + dayOrdinal(d.dayOfMonth) + " " + genitiveMonth(d.monthValue)

    private val genitive = listOf(
        "stycznia", "lutego", "marca", "kwietnia", "maja", "czerwca",
        "lipca", "sierpnia", "września", "października", "listopada", "grudnia",
    )

    fun genitiveMonth(m: Int) = genitive[m - 1]

    fun spoken(t: LocalDateTime) = "Jest " + timeWords(t.hour, t.minute) + ". " + dateWords(t.toLocalDate()) + "."
}

data class Phrase(val pl: String, val en: String, val note: String, val emoji: String, val level: String)

/**
 * Phrases from the Polish Phrase Master app (newbroman/Polish_survival_phrases, CC BY 4.0).
 * A copy ships with Dune; a fresher copy is fetched from GitHub at most once a week.
 */
object Phrases {
    private const val BASE = "https://raw.githubusercontent.com/newbroman/Polish_survival_phrases/main/"
    const val APP_URL = "https://newbroman.github.io/Polish_survival_phrases/"
    @Volatile private var cache: List<Phrase>? = null

    fun parse(json: String): List<Phrase> {
        val a = JSONArray(json)
        return (0 until a.length()).map { a.getJSONObject(it) }.map {
            Phrase(it.getString("pl"), it.getString("en"), it.optString("note"), it.optString("emoji"), it.optString("lvl"))
        }
    }

    suspend fun all(ctx: Context): List<Phrase> = cache ?: withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "phrases.json")
        val list = runCatching { if (f.exists()) parse(f.readText()) else null }.getOrNull()
            ?: parse(ctx.assets.open("phrases.json").bufferedReader().use { it.readText() })
        cache = list
        list
    }

    /** Same phrase all day; [offset] steps through others when you ask for another. */
    fun forDay(list: List<Phrase>, day: LocalDate, offset: Int = 0): Phrase? {
        if (list.isEmpty()) return null
        val i = Math.floorMod(day.toEpochDay() * 7919L + offset * 104729L, list.size.toLong()).toInt()
        return list[i]
    }

    /** Refresh from GitHub weekly so new phrases appear without a Dune update. */
    suspend fun refresh(ctx: Context) = withContext(Dispatchers.IO) {
        val f = File(ctx.filesDir, "phrases.json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86_400_000L) return@withContext
        runCatching {
            val levels = org.json.JSONObject(get(BASE + "levels.json")).getJSONArray("levels")
            val out = JSONArray()
            for (i in 0 until levels.length()) {
                val l = levels.getJSONObject(i)
                if (l.getString("id") in setOf("0", "1", "1.1")) continue   // alphabet drills, not phrases
                val title = l.optString("title").substringBefore(" - ")
                val ps = org.json.JSONObject(get(BASE + l.getString("file"))).optJSONArray("phrases") ?: continue
                for (j in 0 until ps.length()) {
                    val p = ps.getJSONObject(j)
                    val pl = p.optString("pl"); val en = p.optString("en")
                    if (pl.isBlank() || en.isBlank()) continue
                    out.put(org.json.JSONObject().put("pl", pl).put("en", en)
                        .put("note", p.optString("note").take(240)).put("emoji", p.optString("emoji")).put("lvl", title))
                }
            }
            if (out.length() > 100) {
                f.writeText(out.toString()); cache = parse(out.toString())
            }
        }
    }
}
