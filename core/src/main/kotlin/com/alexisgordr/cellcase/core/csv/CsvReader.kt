package com.alexisgordr.cellcase.core.csv

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg

import java.io.Reader

/** Un registro CSV con su posición física en el archivo (líneas 1-based, inclusive). */
data class CsvRecord(
    val fields: List<String>,
    /** Índice del registro empezando en 0 (la cabecera es el registro 0). */
    val index: Int,
    val startLine: Int,
    val endLine: Int,
    /** Se encontró una comilla dentro de un campo sin entrecomillar (se conserva tal cual). */
    val strayQuote: Boolean = false
)

class CsvFormatException(val text: com.alexisgordr.cellcase.core.i18n.Msg, val line: Int) : Exception(text.toString())

/**
 * Lector CSV RFC 4180 en streaming.
 *
 * - Campos entre comillas con comillas dobladas y saltos de línea internos.
 * - Acepta LF y CRLF. Una línea vacía al final del archivo no es un registro.
 * - Límites de tamaño por campo y por registro para no agotar memoria con archivos hostiles.
 * - Una comilla sin cerrar al final del archivo es un error de formato (no se adivina el cierre).
 */
class CsvReader(
    private val reader: Reader,
    private val maxFieldChars: Int = 64 * 1024,
    private val maxFieldsPerRecord: Int = 1024
) {
    private var line = 1
    private var recordIndex = 0
    private var pushback = -2

    private fun read(): Int {
        if (pushback != -2) {
            val c = pushback; pushback = -2; return c
        }
        return reader.read()
    }

    private fun unread(c: Int) { pushback = c }

    /** Siguiente registro o null al final del archivo. */
    fun next(): CsvRecord? {
        var c = read()
        // Las líneas completamente vacías (p. ej. al final) no producen registro. Se saltan en un bucle, no con
        // recursión: miles de líneas en blanco seguidas agotarían la pila (StackOverflowError).
        while (c == '\n'.code || c == '\r'.code) {
            if (c == '\r'.code) { val n = read(); if (n != '\n'.code) unread(n) }
            line++
            c = read()
        }
        if (c == -1) return null
        val start = line
        val fields = ArrayList<String>()
        val sb = StringBuilder()
        var stray = false
        while (true) {
            if (c == '"'.code && sb.isEmpty()) {
                // Campo entrecomillado
                val quoteLine = line
                while (true) {
                    val q = read()
                    if (q == -1) throw CsvFormatException(msg("import.next.comillas_sin_cerrar_abiertas_en", quoteLine), quoteLine)
                    if (q == '"'.code) {
                        val n = read()
                        if (n == '"'.code) { append(sb, '"') } else { c = n; break }
                    } else {
                        if (q == '\n'.code) line++
                        append(sb, q.toChar())
                    }
                }
                // Tras la comilla de cierre solo puede venir separador o fin de línea
                if (c != ','.code && c != '\n'.code && c != '\r'.code && c != -1) {
                    stray = true
                    while (c != ','.code && c != '\n'.code && c != '\r'.code && c != -1) {
                        append(sb, c.toChar()); c = read()
                    }
                }
            } else {
                while (c != ','.code && c != '\n'.code && c != '\r'.code && c != -1) {
                    if (c == '"'.code) stray = true
                    append(sb, c.toChar()); c = read()
                }
            }
            fields += sb.toString(); sb.setLength(0)
            if (fields.size > maxFieldsPerRecord) {
                throw CsvFormatException(msg("import.next.mas_de_campos_en_un", maxFieldsPerRecord), start)
            }
            when (c) {
                ','.code -> { c = read(); if (c == -1 || c == '\n'.code || c == '\r'.code) { fields += ""; if (c == -1) break else { endLine(c); break } } }
                -1 -> break
                else -> { endLine(c); break }
            }
        }
        val rec = CsvRecord(fields, recordIndex++, start, line - (if (lastWasNewline) 1 else 0), stray)
        lastWasNewline = false
        return rec
    }

    private var lastWasNewline = false

    private fun endLine(c: Int) {
        if (c == '\r'.code) { val n = read(); if (n != '\n'.code) unread(n) }
        line++
        lastWasNewline = true
    }

    private fun append(sb: StringBuilder, ch: Char) {
        if (sb.length >= maxFieldChars) throw CsvFormatException(msg("import.append.campo_de_mas_de_caracteres", maxFieldChars), line)
        sb.append(ch)
    }
}
