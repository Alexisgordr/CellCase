package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg
import com.alexisgordr.cellcase.core.model.HeaderIndex
import com.alexisgordr.cellcase.core.model.RowValues
import com.alexisgordr.cellcase.core.model.StringPool

import com.alexisgordr.cellcase.core.csv.CsvFormatException
import com.alexisgordr.cellcase.core.csv.CsvReader
import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.model.IssueCollector
import com.alexisgordr.cellcase.core.model.IssueExample
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.time.CaptureTime
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.LocalDate

/** Fila de una tabla CSV leída por nombre de columna, con su procedencia y lectores tipados. */
class CsvRow(val fields: Map<String, String>, val prov: Provenance, private val issues: IssueCollector, private val table: String) {
    fun raw(col: String): String? = fields[col]
    fun str(col: String): String? = fields[col]?.takeIf { it.isNotEmpty() }

    private fun bad(col: String, text: Msg) =
        issues.add("VALUE_INVALID", Severity.WARNING, "${table}:${col}", text, IssueExample(prov, col, fields[col]))

    fun int(col: String): Int? = str(col)?.let { v -> v.trim().toIntOrNull() ?: run { bad(col, msg("import.int.valor_no_entero_se_trata")); null } }
    fun long(col: String): Long? = str(col)?.let { v -> v.trim().toLongOrNull() ?: run { bad(col, msg("import.int.valor_no_entero_se_trata")); null } }
    fun double(col: String): Double? = str(col)?.let { v ->
        v.trim().toDoubleOrNull()?.takeIf { !it.isNaN() && !it.isInfinite() } ?: run { if (v.trim() != "NaN") bad(col, msg("import.double.valor_no_numerico_se_trata")); null }
    }
    fun bool(col: String): Boolean? = when (str(col)?.trim()?.lowercase()) {
        null -> null
        "1", "true" -> true
        "0", "false" -> false
        else -> { bad(col, msg("import.bool.se_esperaba_1_0_o")); null }
    }
    fun instant(col: String): Instant? = str(col)?.let { v -> CaptureTime.parseInstant(v) ?: run { bad(col, msg("import.instant.instante_iso_8601_no_valido")); null } }
    fun epochMs(col: String): Instant? = long(col)?.let { Instant.ofEpochMilli(it) }
    fun date(col: String): LocalDate? = str(col)?.let { v -> CaptureTime.parseDate(v) ?: run { bad(col, msg("import.date.fecha_no_valida")); null } }
    fun identity(col: String): CellIdentity? = str(col)?.let { Identities.parse(it) }
}

class CsvTable(val entry: String, val header: List<String>, val rows: List<CsvRow>, val extraColumns: List<String>)

object Identities {
    /** `MCC-MNC-TAC-CID-RADIO`; componentes `N/A` o vacíos quedan ausentes (identidad incompleta). */
    fun parse(text: String): CellIdentity {
        val p = text.trim().split('-')
        fun part(i: Int, re: Regex) = p.getOrNull(i)?.takeIf { it != "N/A" && re.matches(it) }
        val radio = p.getOrNull(4)?.let { r -> RadioTech.values().firstOrNull { it.name == r } } ?: RadioTech.UNKNOWN
        return if (p.size != 5) CellIdentity(null, null, null, null, RadioTech.UNKNOWN)
        else CellIdentity(part(0, MCC), part(1, MNC), part(2, TAC), part(3, CID), radio)
    }
    private val MCC = Regex("\\d{3}"); private val MNC = Regex("\\d{2,3}"); private val TAC = Regex("\\d{1,10}"); private val CID = Regex("\\d{1,15}")
}

/** Lectura de entradas CSV de un ZIP (o de un CSV suelto) por nombre de columna. */
object CsvTables {
    fun decode(bytes: ByteArray): String {
        val dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return try { dec.decode(java.nio.ByteBuffer.wrap(bytes)).toString() } catch (e: CharacterCodingException) {
            throw ImportRejected(msg("import.decode.no_es_utf_8_valido"), RejectKind.READ_ERROR)
        }
    }

    /**
     * Lee una tabla. Las columnas [required] deben existir (si no: contenido incompatible).
     * Las columnas desconocidas se conservan en [CsvRow.fields] y se informan.
     */
    fun read(
        bytes: ByteArray, importId: String, fileName: String, entry: String?, required: List<String>,
        issues: IssueCollector, checkpoint: Checkpoint = Checkpoint.NONE, maxRows: Int = ImportLimits.MAX_TABLE_ROWS,
        /** Escrito por ICdetector 3.0 o posterior: se retira el apóstrofo protector (`CsvGuard`) y se informa. */
        guarded: Boolean = false,
        /** Columnas conocidas que no son obligatorias (añadidas en versiones posteriores): no se avisan como no interpretadas. */
        optional: List<String> = emptyList()
    ): CsvTable {
        val label = entry ?: fileName
        // Decodificación en flujo (sin una copia completa del texto); UTF-8 inválido se rechaza.
        val dec = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val stream = java.io.InputStreamReader(java.io.ByteArrayInputStream(bytes), dec).buffered()
        stream.mark(1); if (stream.read() != 0xFEFF) stream.reset()
        val reader = CsvReader(stream, maxFieldChars = ImportLimits.MAX_FIELD_CHARS)
        try {
            val header = reader.next()?.fields ?: throw ImportRejected(msg("import.read.esta_vacio", label), RejectKind.INCOMPATIBLE)
            val dup = header.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (dup.isNotEmpty()) throw ImportRejected(msg("import.read.columnas_repetidas", label, dup.joinToString()), RejectKind.INCOMPATIBLE)
            val missing = required.filter { it !in header }
            if (missing.isNotEmpty()) throw ImportRejected(msg("import.read.faltan_columnas_obligatorias", label, missing.joinToString()), RejectKind.INCOMPATIBLE)
            val extra = header.filter { it !in required && it !in optional }
            if (extra.isNotEmpty()) issues.add("EXTRA_COLUMNS", Severity.INFO, label, msg("import.checkHeader.columnas_no_interpretadas_conservadas_tal", extra.joinToString()))
            val rows = ArrayList<CsvRow>()
            val headerIndex = HeaderIndex(header)
            val pool = StringPool()
            var n = 0
            while (true) {
                if (n % 512 == 0) checkpoint.check()
                val rec = reader.next() ?: break
                n++
                if (n > maxRows) throw ImportRejected(msg("import.read.supera_filas", label, maxRows), RejectKind.INCOMPATIBLE)
                val prov = Provenance(importId, fileName, entry, rec.startLine, rec.endLine, rec.index - 1)
                if (rec.fields.size != header.size) {
                    issues.add("ROW_FIELD_COUNT", Severity.ERROR, label, msg("import.read.fila_con_un_numero_de"), IssueExample(prov, null, "${rec.fields.size} campos"))
                    continue
                }
                // Fila compacta (cabecera compartida, valores repetidos compartidos): D-041, memoria.
                val values = Array(rec.fields.size) { i ->
                    val v0 = rec.fields[i]
                    val v = if (guarded) com.alexisgordr.cellcase.core.csv.CsvGuard.unguard(v0) else v0
                    if (v !== v0) issues.add("TEXT_GUARD_REMOVED", Severity.INFO, "$label:${header[i]}", msg("import.guard.removed"), IssueExample(prov, header[i], v0))
                    pool.of(v)
                }
                rows += CsvRow(RowValues(headerIndex, values), prov, issues, entry ?: "csv")
            }
            return CsvTable(label, header, rows, extra)
        } catch (e: CharacterCodingException) {
            throw ImportRejected(msg("import.read.no_es_utf_8_valido", label), RejectKind.READ_ERROR)
        } catch (e: CsvFormatException) {
            throw ImportRejected(msg("import.read.csv_mal_formado_en_la", label, e.line, e.message), RejectKind.READ_ERROR)
        }
    }

    fun inputStream(bytes: ByteArray) = InputStreamReader(ByteArrayInputStream(bytes), StandardCharsets.UTF_8)
}
