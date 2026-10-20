package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.msg

/**
 * Lector JSON mínimo y estricto (RFC 8259) para `case.json`, `metadata.json` y `capabilities.json`.
 * Devuelve Map/List/String/Double/Long/Boolean/null. Sin dependencias: `:core` no usa librerías externas.
 */
object MiniJson {
    class JsonException(val text: com.alexisgordr.cellcase.core.i18n.Msg) : Exception(text.toString())

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value(0)
        p.ws()
        if (p.i != text.length) throw JsonException(msg("import.parse.contenido_sobrante_en_la_posicion", p.i))
        return v
    }

    @Suppress("UNCHECKED_CAST")
    fun obj(text: String): Map<String, Any?> = parse(text) as? Map<String, Any?> ?: throw JsonException(msg("import.obj.se_esperaba_un_objeto_json"))

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i] in " \t\r\n") i++ }
        fun value(depth: Int): Any? {
            if (depth > 64) throw JsonException(msg("import.value.anidamiento_excesivo"))
            if (i >= s.length) throw JsonException(msg("import.value.fin_inesperado"))
            return when (val c = s[i]) {
                '{' -> obj(depth)
                '[' -> arr(depth)
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonException(msg("import.value.caracter_inesperado_en", c, i))
            }
        }
        fun lit(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw JsonException(msg("import.lit.literal_no_valido_en", i))
            i += w.length; return v
        }
        fun obj(d: Int): Map<String, Any?> {
            i++; ws()
            val m = LinkedHashMap<String, Any?>()
            if (i < s.length && s[i] == '}') { i++; return m }
            while (true) {
                ws(); if (i >= s.length || s[i] != '"') throw JsonException(msg("import.obj.se_esperaba_clave_en", i))
                val k = str(); ws()
                if (i >= s.length || s[i] != ':') throw JsonException(msg("import.obj.se_esperaba_en", i))
                i++; ws(); m[k] = value(d + 1); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == '}') { i++; return m }
                throw JsonException(msg("import.obj.se_esperaba_o_en", i))
            }
        }
        fun arr(d: Int): List<Any?> {
            i++; ws()
            val l = ArrayList<Any?>()
            if (i < s.length && s[i] == ']') { i++; return l }
            while (true) {
                ws(); l += value(d + 1); ws()
                if (i < s.length && s[i] == ',') { i++; continue }
                if (i < s.length && s[i] == ']') { i++; return l }
                throw JsonException(msg("import.arr.se_esperaba_o_en", i))
            }
        }
        fun str(): String {
            i++
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonException(msg("import.str.cadena_sin_cerrar"))
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonException(msg("import.str.escape_incompleto"))
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000C'); 'n' -> sb.append('\n')
                            'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonException(msg("import.str.escape_u_incompleto"))
                                val hex = s.substring(i, i + 4)
                                // Solo cuatro cifras hexadecimales («\uZZZZ» o «\u+1F6» no son JSON).
                                if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
                                    throw JsonException(msg("import.str.escape_no_valido", "u$hex"))
                                sb.append(hex.toInt(16).toChar()); i += 4
                            }
                            else -> throw JsonException(msg("import.str.escape_no_valido", e))
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }
        fun num(): Any {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            val t = s.substring(start, i)
            val v: Any = if (t.any { it in ".eE" }) t.toDoubleOrNull() ?: throw JsonException(msg("import.num.numero_no_valido", t))
            else t.toLongOrNull() ?: t.toDoubleOrNull() ?: throw JsonException(msg("import.num.numero_no_valido", t))
            // JSON no tiene infinitos ni NaN: «1e999» se desbordaría a Infinity y no se podría representar después.
            if (v is Double && !v.isFinite()) throw JsonException(msg("import.num.numero_no_valido", t))
            return v
        }
    }
}
