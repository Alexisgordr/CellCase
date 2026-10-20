package com.alexisgordr.cellcase.core.i18n

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Idioma de presentación de un informe o de los textos del núcleo (D-044). Solo cambia palabras y formato
 * regional: nunca la interpretación de fechas, la zona de captura, los cálculos ni la valoración.
 */
enum class Language(val tag: String, val locale: Locale) {
    ES("es", Locale.forLanguageTag("es-ES")),
    EN("en", Locale.forLanguageTag("en-GB"));

    companion object {
        fun fromTag(tag: String?): Language = values().firstOrNull { it.tag == tag?.substringBefore('-')?.lowercase() } ?: ES
    }
}

/** Atajo para crear un [Msg] con argumentos. */
fun msg(key: String, vararg args: Any?): Msg = Msg(key, args.toList())

/** Concatenación de textos pendientes: cada parte se presenta en el idioma final. */
operator fun Msg.plus(other: Msg): Msg = Msg("core.concat", listOf(this, other))
operator fun Msg.plus(other: String): Msg = Msg("core.concat", listOf(this, Msg.raw(other)))

/** Texto pendiente de presentar: clave del catálogo y argumentos. Así un aviso se guarda sin idioma fijo. */
data class Msg(val key: String, val args: List<Any?> = emptyList()) {
    constructor(key: String, vararg a: Any?) : this(key, a.toList())
    fun render(lang: Language): String = Messages.format(lang, key, args)
    /** Español: idioma de referencia de los textos guardados por versiones anteriores. */
    override fun toString(): String = render(Language.ES)

    companion object {
        /** Texto libre ya formado (mensaje de una excepción ajena, valor del archivo): se muestra tal cual. */
        fun raw(text: String?) = Msg("core.raw", listOf(text ?: ""))
    }
}

/**
 * Catálogos `clave = plantilla` en `resources/cellcase/i18n/messages_<idioma>.txt` (UTF-8).
 * Plantillas: `{0}` argumento; `{0,plural,one{# fila}other{# filas}}` plural (`#` = el argumento ya formateado).
 * Español e inglés usan las mismas categorías (one = 1; other = el resto).
 */
object Messages {
    private val cache = HashMap<Language, Map<String, String>>()

    fun catalog(lang: Language): Map<String, String> = synchronized(cache) {
        cache.getOrPut(lang) { load(lang) }
    }

    private fun load(lang: Language): Map<String, String> {
        val path = "/cellcase/i18n/messages_${lang.tag}.txt"
        val stream = Messages::class.java.getResourceAsStream(path) ?: error("Falta el catálogo $path")
        val out = LinkedHashMap<String, String>()
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val i = line.indexOf(" = ")
                require(i > 0) { "Línea de catálogo sin « = »: $line" }
                out[line.substring(0, i)] = unescape(line.substring(i + 3))
            }
        }
        return out
    }

    private fun unescape(v: String): String {
        val sb = StringBuilder(v.length); var i = 0
        while (i < v.length) {
            val c = v[i]
            if (c == '\\' && i + 1 < v.length) {
                when (v[i + 1]) { 'n' -> sb.append('\n'); '\\' -> sb.append('\\'); else -> sb.append(c).append(v[i + 1]) }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    /** Plantilla de [key]; si falta en el idioma pedido se usa el español (y una prueba exige que no falte ninguna). */
    fun template(lang: Language, key: String): String =
        catalog(lang)[key] ?: catalog(Language.ES)[key] ?: key

    fun has(lang: Language, key: String) = key in catalog(lang)

    fun format(lang: Language, key: String, args: List<Any?>): String =
        apply(template(lang, key), args.map { if (it is Msg) it.render(lang) else it })

    /** Sustituye `{n}` y `{n,plural,…}`. Las llaves que no siguen ese formato se copian tal cual. */
    fun apply(t: String, args: List<Any?>): String {
        val sb = StringBuilder(t.length + 16)
        var i = 0
        while (i < t.length) {
            val c = t[i]
            if (c == '{') {
                val m = Regex("""\{(\d+)(,plural,)?""").matchAt(t, i)
                if (m != null) {
                    val n = m.groupValues[1].toInt()
                    val arg = args.getOrNull(n)
                    val text = arg?.toString() ?: "null"
                    if (m.groupValues[2].isEmpty()) {
                        if (t.getOrNull(m.range.last + 1) == '}') { sb.append(text); i = m.range.last + 2; continue }
                    } else {
                        val (forms, end) = pluralForms(t, m.range.last + 1)
                        val form = forms[category(arg)] ?: forms["other"] ?: ""
                        sb.append(apply(form.replace("#", text), args))
                        i = end; continue
                    }
                }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private fun pluralForms(t: String, start: Int): Pair<Map<String, String>, Int> {
        val forms = LinkedHashMap<String, String>(); var i = start
        while (i < t.length && t[i] != '}') {
            val open = t.indexOf('{', i)
            val name = t.substring(i, open).trim()
            var depth = 0; var j = open
            while (true) { if (t[j] == '{') depth++ else if (t[j] == '}') { depth--; if (depth == 0) break }; j++ }
            forms[name] = t.substring(open + 1, j)
            i = j + 1
        }
        return forms to i + 1
    }

    /** Categoría plural (es/en): «one» solo para 1. Admite números ya formateados («1.234», «1,234»). */
    fun category(arg: Any?): String {
        val v: Long? = when (arg) {
            is Number -> if (arg.toDouble() == Math.floor(arg.toDouble())) arg.toLong() else null
            is String -> arg.replace(Regex("[.,   ]"), "").toLongOrNull()
            else -> null
        }
        return if (v == 1L) "one" else "other"
    }
}

/**
 * Formato regional por idioma (D-044). Fechas inequívocas: en español día/mes/año (uso de España); en inglés
 * año-mes-día (ISO 8601) o el mes con letras, para que nadie lea 02/10 como 10 de febrero.
 * Los valores no cambian: solo su escritura. Identidades, PCI, EARFCN, dBm y huellas no pasan por aquí.
 */
class RegionalFormat(val lang: Language) {
    private val loc = lang.locale
    private val p = when (lang) {
        Language.ES -> Patterns("dd/MM/yyyy HH:mm:ss", "dd/MM HH:mm", "dd/MM/yyyy", "dd/MM", "d 'de' MMMM", "d 'de' MMMM 'de' yyyy")
        Language.EN -> Patterns("yyyy-MM-dd HH:mm:ss", "d MMM HH:mm", "yyyy-MM-dd", "d MMM", "d MMMM", "d MMMM yyyy")
    }
    private class Patterns(val local: String, val short: String, val date: String, val day: String, val lay: String, val layYear: String)

    val LOCAL: DateTimeFormatter = DateTimeFormatter.ofPattern(p.local, loc)
    val SHORT: DateTimeFormatter = DateTimeFormatter.ofPattern(p.short, loc)
    val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern(p.date, loc)
    val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern(p.day, loc)
    val LAY: DateTimeFormatter = DateTimeFormatter.ofPattern(p.lay, loc)
    val LAY_YEAR: DateTimeFormatter = DateTimeFormatter.ofPattern(p.layYear, loc)
    val UTC: DateTimeFormatter = DateTimeFormatter.ofPattern(p.local + " 'UTC'", loc).withZone(ZoneOffset.UTC)
    val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", loc)
    val HMS: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", loc)

    /** Separador de millares: en español solo desde 5 cifras con punto (RAE); en inglés coma desde 4 cifras. */
    fun int(v: Long): String {
        val s = kotlin.math.abs(v).toString()
        val (sep, min) = if (lang == Language.ES) "." to 5 else "," to 4
        val grouped = if (s.length < min) s else s.reversed().chunked(3).joinToString(sep).reversed()
        return if (v < 0) "-$grouped" else grouped
    }

    /** Número con [digits] decimales y la coma o el punto decimal del idioma. */
    fun dec(v: Double, digits: Int = 1): String = String.format(loc, "%.${digits}f", v)

    fun local(t: LocalDateTime): String = LOCAL.format(t)
    fun utc(i: Instant): String = UTC.format(i)
    fun at(i: Instant, z: ZoneId): String = LOCAL.format(i.atZone(z))
    fun short(i: Instant, z: ZoneId): String = SHORT.format(i.atZone(z))
    fun date(d: LocalDate): String = DATE.format(d)

    companion object {
        private val all = Language.values().associateWith { RegionalFormat(it) }
        fun of(lang: Language) = all.getValue(lang)
    }
}
