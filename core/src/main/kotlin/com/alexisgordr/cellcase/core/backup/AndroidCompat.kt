package com.alexisgordr.cellcase.core.backup

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Conversiones de valores como las hace `android.database.Cursor` (CursorWindow) al leer una columna con un tipo
 * distinto del guardado. ICdetector exporta lo que le devuelve el cursor, así que CellCase las reproduce para
 * reconstruir los mismos textos (docs/backup-format.md §4.1).
 */
object CursorValue {
    /** `getLong`: NULL → 0; real → truncado; texto → número inicial (strtoll, base 0). */
    fun long(v: Any?): Long = when (v) {
        null -> 0L
        is Long -> v
        is Double -> if (v.isNaN()) 0L else v.toLong()
        is String -> strtoll(v)
        is ByteArray -> throw SqliteFormatException("BLOB leído como número")
        else -> 0L
    }

    /** `getInt`: `(int) getLong`. */
    fun int(v: Any?): Int = long(v).toInt()

    /** `getDouble`: NULL → 0; entero → double; texto → número inicial (strtod). */
    fun double(v: Any?): Double = when (v) {
        null -> 0.0
        is Long -> v.toDouble()
        is Double -> v
        is String -> strtod(v)
        is ByteArray -> throw SqliteFormatException("BLOB leído como número")
        else -> 0.0
    }

    fun float(v: Any?): Float = double(v).toFloat()

    /** `getString`: NULL → null; entero → decimal; real → `%g` de C; texto tal cual. */
    fun string(v: Any?): String? = when (v) {
        null -> null
        is String -> v
        is Long -> v.toString()
        is Double -> cG(v)
        is ByteArray -> throw SqliteFormatException("BLOB leído como texto")
        else -> v.toString()
    }

    fun optString(v: Any?): String? = string(v)
    fun optInt(v: Any?): Int? = if (v == null) null else int(v)
    fun optLong(v: Any?): Long? = if (v == null) null else long(v)

    private fun strtoll(s: String): Long {
        val t = s.trimStart()
        var i = 0; var neg = false
        if (i < t.length && (t[i] == '+' || t[i] == '-')) { neg = t[i] == '-'; i++ }
        var base = 10
        if (t.startsWith("0x", i, true) && i + 2 < t.length && t[i + 2].isHexDigitAscii()) { base = 16; i += 2 }
        else if (i < t.length && t[i] == '0') base = 8
        var v = 0L
        while (i < t.length) {
            val d = Character.digit(t[i], base)
            if (d < 0) break
            v = v * base + d; i++
        }
        return if (neg) -v else v
    }

    private fun Char.isHexDigitAscii() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private val NUMBER_PREFIX = Regex("^\\s*[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")

    private fun strtod(s: String): Double = NUMBER_PREFIX.find(s)?.value?.trim()?.toDoubleOrNull() ?: 0.0

    /** `printf("%g")` de C: 6 cifras significativas, sin ceros finales; exponente si < 1e-4 o ≥ 1e6. */
    fun cG(x: Double): String {
        if (x.isNaN()) return "nan"
        if (x.isInfinite()) return if (x > 0) "inf" else "-inf"
        if (x == 0.0) return if (1.0 / x < 0) "-0" else "0"
        val bd = BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN))
        val exp = bd.precision() - bd.scale() - 1
        return if (exp < -4 || exp >= 6) {
            val mant = bd.movePointLeft(exp).stripTrailingZeros().toPlainString()
            mant + "e" + (if (exp < 0) "-" else "+") + Math.abs(exp).toString().padStart(2, '0')
        } else bd.stripTrailingZeros().toPlainString()
    }
}

/** `String.format(Locale.ROOT, "%.Nf", x)` de Java/Kotlin, el mismo que usa ICdetector. */
internal fun fixed(x: Double, places: Int): String = String.format(java.util.Locale.ROOT, "%.${places}f", x)
internal fun fixed(x: Float, places: Int): String = String.format(java.util.Locale.ROOT, "%.${places}f", x)

/** `Instant.ofEpochMilli(ms).toString()`. */
internal fun instantText(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()

/** `ObservationTime.utcIso`: instante UTC siempre con milisegundos. */
internal fun utcIsoMillis(ms: Long): String =
    java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.ROOT).withZone(java.time.ZoneOffset.UTC).format(java.time.Instant.ofEpochMilli(ms))

/**
 * JSON con la semántica de `org.json` de Android, que es la que usa ICdetector para leer y escribir: los números
 * enteros se leen como Int (o Long si no caben) y los que llevan punto o exponente como Double; el orden de las
 * claves se conserva; `toString(2)` sangra con dos espacios y escapa `/`.
 */
object AndroidJson {
    /** `JSONObject.NULL`. */
    object Null { override fun toString() = "null" }

    class ParseException(message: String) : Exception(message)

    fun parse(text: String): Any? = Parser(text).run { val v = value(); skipWs(); if (pos != s.length) throw ParseException("texto tras el JSON"); v }

    fun obj(text: String): Map<String, Any?> {
        val v = parse(text) as? Map<*, *> ?: throw ParseException("no es un objeto JSON")
        // El analizador solo crea mapas con claves String (ver Parser.obj): la comprobación evita el cast sin verificar.
        return v.entries.associate { (k, x) -> (k as? String ?: throw ParseException("clave no textual en el objeto JSON")) to x }
    }

    private class Parser(val s: String) {
        var pos = 0
        var depth = 0
        fun skipWs() { while (pos < s.length && s[pos].isWhitespace()) pos++ }
        fun value(): Any? {
            skipWs()
            if (pos >= s.length) throw ParseException("JSON incompleto")
            if (++depth > 64) throw ParseException("JSON demasiado anidado")
            try {
                return when (s[pos]) {
                    '{' -> objectValue()
                    '[' -> arrayValue()
                    '"' -> string()
                    else -> literal()
                }
            } finally { depth-- }
        }
        fun objectValue(): Map<String, Any?> {
            pos++
            val m = LinkedHashMap<String, Any?>()
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                if (pos >= s.length || s[pos] != '"') throw ParseException("se esperaba una clave")
                val k = string()
                skipWs()
                if (pos >= s.length || s[pos] != ':') throw ParseException("se esperaba ':'")
                pos++
                m[k] = value()
                skipWs()
                if (pos >= s.length) throw ParseException("objeto sin cerrar")
                when (s[pos++]) { ',' -> continue; '}' -> return m; else -> throw ParseException("se esperaba ',' o '}'") }
            }
        }
        fun arrayValue(): List<Any?> {
            pos++
            val l = ArrayList<Any?>()
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                l += value()
                skipWs()
                if (pos >= s.length) throw ParseException("lista sin cerrar")
                when (s[pos++]) { ',' -> continue; ']' -> return l; else -> throw ParseException("se esperaba ',' o ']'") }
            }
        }
        fun string(): String {
            pos++
            val b = StringBuilder()
            while (true) {
                if (pos >= s.length) throw ParseException("texto sin cerrar")
                val c = s[pos++]
                when (c) {
                    '"' -> return b.toString()
                    '\\' -> {
                        if (pos >= s.length) throw ParseException("escape incompleto")
                        when (val e = s[pos++]) {
                            'u' -> {
                                if (pos + 4 > s.length) throw ParseException("escape incompleto")
                                val hex = s.substring(pos, pos + 4)
                                // Como org.json: un \u sin cuatro cifras hexadecimales es un error de formato.
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw ParseException("escape \\u no válido")
                                b.append(hex.toInt(16).toChar()); pos += 4
                            }
                            't' -> b.append('\t'); 'b' -> b.append('\b'); 'n' -> b.append('\n'); 'r' -> b.append('\r'); 'f' -> b.append('\u000C')
                            else -> b.append(e)
                        }
                    }
                    else -> b.append(c)
                }
            }
        }
        fun literal(): Any? {
            val start = pos
            while (pos < s.length && s[pos] !in ",]} \t\r\n") pos++
            val t = s.substring(start, pos)
            return when {
                t == "null" -> Null
                t == "true" -> true
                t == "false" -> false
                t.isEmpty() -> throw ParseException("valor vacío")
                t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0 && t.toLongOrNull() != null ->
                    t.toLong().let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
                else -> t.toDoubleOrNull() ?: t   // org.json devuelve el texto si no es un número
            }
        }
    }

    // ── lectura con la semántica de optString / optInt / optDouble ──

    /** `optString(key)`: "" si falta; el texto de cualquier otro valor (`String.valueOf`). */
    fun optString(o: Map<String, Any?>?, key: String): String = if (o == null || key !in o) "" else valueOf(o[key])

    /** `optInt(key)`: 0 si falta o no es numérico. */
    fun optInt(o: Map<String, Any?>?, key: String, fallback: Int = 0): Int = when (val v = o?.get(key)) {
        is Int -> v
        is Long -> v.toInt()
        is Double -> v.toInt()
        is String -> v.trim().toDoubleOrNull()?.toInt() ?: fallback
        else -> fallback
    }

    /** `optDouble(key)`: NaN si falta o no es numérico. */
    fun optDouble(o: Map<String, Any?>?, key: String): Double = when (val v = o?.get(key)) {
        is Int -> v.toDouble()
        is Long -> v.toDouble()
        is Double -> v
        is String -> v.trim().toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }

    @Suppress("UNCHECKED_CAST")
    fun optObject(o: Map<String, Any?>?, key: String): Map<String, Any?>? = o?.get(key) as? Map<String, Any?>
    fun optArray(o: Map<String, Any?>?, key: String): List<Any?>? = o?.get(key) as? List<Any?>
    fun isNullOrMissing(o: Map<String, Any?>?, key: String) = o == null || key !in o || o[key] == null || o[key] === Null

    /** `String.valueOf(value)` de un valor de org.json (objetos y listas en forma compacta). */
    fun valueOf(v: Any?): String = when (v) {
        null, Null -> "null"
        is String -> v
        is Double -> java.lang.Double.toString(v)
        is Map<*, *>, is List<*> -> compact(v)
        else -> v.toString()
    }

    // ── escritura (toString() y toString(2)) ──

    fun compact(v: Any?): String = StringBuilder().also { write(it, v, 0, -1) }.toString()
    fun pretty(v: Any?): String = StringBuilder().also { write(it, v, 2, 0) }.toString()

    private fun write(b: StringBuilder, v: Any?, indent: Int, level: Int) {
        fun nl(l: Int) { if (indent > 0) { b.append('\n'); repeat(indent * l) { b.append(' ') } } }
        when (v) {
            is Map<*, *> -> {
                if (v.isEmpty()) { b.append("{}"); return }
                b.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) b.append(','); first = false
                    nl(level + 1)
                    string(b, k.toString()); b.append(if (indent > 0) ": " else ":")
                    write(b, x, indent, level + 1)
                }
                nl(level); b.append('}')
            }
            is List<*> -> {
                if (v.isEmpty()) { b.append("[]"); return }
                b.append('[')
                var first = true
                for (x in v) { if (!first) b.append(','); first = false; nl(level + 1); write(b, x, indent, level + 1) }
                nl(level); b.append(']')
            }
            null, Null -> b.append("null")
            is String -> string(b, v)
            is Boolean -> b.append(v.toString())
            is Double -> b.append(numberToString(v))
            is Float -> b.append(numberToString(v.toDouble()))
            else -> b.append(v.toString())
        }
    }

    /** `JSONObject.numberToString`: un double entero se escribe sin decimales. */
    fun numberToString(d: Double): String {
        if (d == 0.0 && 1.0 / d < 0) return "-0"
        val l = d.toLong()
        return if (d == l.toDouble()) l.toString() else java.lang.Double.toString(d)
    }

    private fun string(b: StringBuilder, s: String) {
        b.append('"')
        for (c in s) when (c) {
            '"' -> b.append("\\\""); '\\' -> b.append("\\\\"); '/' -> b.append("\\/")
            '\t' -> b.append("\\t"); '\b' -> b.append("\\b"); '\n' -> b.append("\\n"); '\r' -> b.append("\\r"); '\u000C' -> b.append("\\f")
            else -> if (c.code <= 0x1F) b.append(String.format(java.util.Locale.ROOT, "\\u%04x", c.code)) else b.append(c)
        }
        b.append('"')
    }
}
