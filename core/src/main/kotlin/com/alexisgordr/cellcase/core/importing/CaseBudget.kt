package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg

/**
 * Presupuesto de memoria de un expediente (D-046). Los límites por archivo no bastan: varios archivos grandes
 * analizados juntos pueden agotar la memoria. Se reserva en el orden de lectura (orden de importación), así que el
 * resultado es reproducible: los primeros archivos tienen prioridad y el que no cabe se marca «demasiado grande»
 * sin interpretarlo. Los valores están medidos con `MemoryProbe` (docs/limits.md).
 */
class CaseBudget(
    /** Filas de historial que se analizan juntas (D-054: la memoria depende de las filas, no de los bytes). */
    val maxHistoryRows: Int = ImportLimits.CASE_HISTORY_ROWS,
    val maxZipBytes: Long = ImportLimits.CASE_ZIP_BYTES,
    initialHistoryRows: Int = 0
) {
    var historyRows: Int = initialHistoryRows; private set
    var zipBytes: Long = 0; private set

    fun reserveHistoryRows(n: Int): Boolean = (historyRows + n <= maxHistoryRows).also { if (it) historyRows += n }
    fun reserveZip(n: Long): Boolean = (zipBytes + n <= maxZipBytes).also { if (it) zipBytes += n }

    private fun mib(n: Long) = (n + 1048575) / 1048576
    fun historyMessage(rows: Int): Msg = msg("import.budget.history_rows", rows, historyRows, maxHistoryRows)
    fun zipMessage(n: Long): Msg = msg("import.budget.zip", mib(n), mib(zipBytes), mib(maxZipBytes))

    companion object {
        /**
         * Filas de datos de un CSV (registros tras la cabecera; los saltos de línea dentro de comillas no cuentan).
         * Con las mismas reglas que [com.alexisgordr.cellcase.core.csv.CsvReader]: una comilla abre un campo
         * entrecomillado solo al principio del campo; en medio (`foo"bar`) es una comilla suelta y no abre nada.
         */
        fun csvRows(bytes: ByteArray): Int {
            var rows = 0; var content = false
            var quoted = false          // dentro de un campo entrecomillado
            var fieldStart = true       // al principio de un campo
            var i = 0
            while (i < bytes.size) {
                val c = bytes[i].toInt()
                if (quoted) {
                    if (c == '"'.code) {
                        if (i + 1 < bytes.size && bytes[i + 1].toInt() == '"'.code) i++   // comilla doblada
                        else quoted = false
                    }
                } else when (c) {
                    '"'.code -> { if (fieldStart) quoted = true; content = true; fieldStart = false }
                    ','.code -> { content = true; fieldStart = true }
                    '\n'.code -> { if (content) rows++; content = false; fieldStart = true }
                    '\r'.code -> {}
                    else -> { content = true; fieldStart = false }
                }
                i++
            }
            if (content) rows++
            return maxOf(0, rows - 1)
        }

        fun csvRows(file: java.io.File): Int = if (file.exists()) csvRows(file.readBytes()) else 0
    }
}
