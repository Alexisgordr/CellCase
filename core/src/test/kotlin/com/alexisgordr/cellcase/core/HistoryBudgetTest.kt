package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.importing.CaseBudget
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportLimits
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Presupuesto de historial por filas y límite propio de los historiales CSV (D-054). */
class HistoryBudgetTest {
    private val v3 = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv")
    private val legacy = TestSupport.sample("SYNTHETIC_patrones_historial.csv")

    @Test fun csvRowsCountsRecordsNotPhysicalLines() {
        assertEquals(0, CaseBudget.csvRows(ByteArray(0)))
        assertEquals(0, CaseBudget.csvRows("A,B\n".toByteArray()))
        assertEquals(2, CaseBudget.csvRows("A,B\n1,2\n3,4".toByteArray()))           // sin salto final
        assertEquals(2, CaseBudget.csvRows("A,B\r\n1,2\r\n3,4\r\n".toByteArray()))   // CRLF
        assertEquals(1, CaseBudget.csvRows("A,B\n\"x\ny\",2\n\n".toByteArray()))     // salto dentro de comillas y línea vacía
        assertEquals(1, CaseBudget.csvRows("A,B\n\"di \"\"hola\"\"\",2\n".toByteArray()))
    }

    /** Un `\r` solo es fin de registro para el lector CSV: el presupuesto debe contar igual (revisión 22/10, A2). */
    @Test fun csvRowsCountsLoneCarriageReturnsLikeTheReader() {
        assertEquals(2, CaseBudget.csvRows("A,B\r1,2\r3,4\r".toByteArray()))
        assertEquals(2, CaseBudget.csvRows("A,B\r1,2\r\r3,4".toByteArray()))          // línea vacía con \r
        assertEquals(1, CaseBudget.csvRows("A,B\r\"x\ry\",2\r".toByteArray()))       // \r dentro de comillas no cuenta
        val lf = legacy.readText()
        val rows = CaseBudget.csvRows(lf.toByteArray())
        for (eol in listOf("\r\n", "\r")) {
            val bytes = lf.replace("\n", eol).toByteArray()
            assertEquals(eol, rows, CaseBudget.csvRows(bytes))
            val r = ImportPipeline.process(bytes, "h.csv", "h", TestSupport.MADRID, budget = CaseBudget(maxHistoryRows = 10))
            assertEquals(eol, ImportState.TOO_LARGE, r.state)
        }
    }

    @Test fun csvRowsMatchesTheRowsTheImporterReads() {
        for (f in listOf(v3, legacy)) {
            val r = ImportPipeline.process(f.readBytes(), f.name, f.name, TestSupport.MADRID)
            assertEquals(f.name, (r.data as com.alexisgordr.cellcase.core.importing.ImportedData.History).data.dataRows, CaseBudget.csvRows(f.readBytes()))
        }
    }

    @Test fun budgetIsReservedInOrderAndReportsRows() {
        val b = CaseBudget(maxHistoryRows = 10, initialHistoryRows = 4)
        assertTrue(b.reserveHistoryRows(6)); assertEquals(10, b.historyRows)
        assertFalse(b.reserveHistoryRows(1)); assertEquals(10, b.historyRows)
        assertTrue(b.reserveHistoryRows(0))
        for (lang in Language.values()) {
            val t = Messages.format(lang, b.historyMessage(3).key, b.historyMessage(3).args)
            assertTrue(t, t.contains("3") && t.contains("10"))
            assertFalse(t, t.contains("MiB"))
        }
    }

    @Test fun defaultBudgetAdmitsTheRowsThat210AdmittedIn6MiB() {
        // 2.10.5: ~36.000 filas en 6 MiB (docs/limits.md); 3.0 escribe ~2,3 veces más bytes por fila.
        assertTrue(ImportLimits.CASE_HISTORY_ROWS >= 36_000)
        val bytesPerRowV3 = v3.length().toDouble() / CaseBudget.csvRows(v3.readBytes())
        assertTrue("una historia de 3.0 con el presupuesto completo cabe en el límite del archivo",
            bytesPerRowV3 * ImportLimits.CASE_HISTORY_ROWS < ImportLimits.MAX_HISTORY_FILE_BYTES)
    }

    /** Historial de 3.0 por encima de 6 MiB: se repiten las filas del ejemplo cambiando la hora. */
    private fun bigHistory(targetBytes: Long): File {
        val lines = v3.readLines(); val header = lines[0]; val body = lines.drop(1).filter { it.isNotEmpty() && !it.contains('"') }
        val f = Files.createTempFile("hist", ".csv").toFile(); f.deleteOnExit()
        f.bufferedWriter().use { w ->
            w.write(header); w.write("\n")
            var n = 0L; var i = 0
            while (n < targetBytes) { val l = body[i % body.size]; w.write(l); w.write("\n"); n += l.length + 1; i++ }
        }
        return f
    }

    @Test fun historyCsvMayExceedTheGeneralFileLimit() {
        val f = bigHistory(ImportLimits.MAX_FILE_BYTES + 512 * 1024)
        assertTrue(f.length() > ImportLimits.MAX_FILE_BYTES)
        val r = ImportPipeline.processFile(f, "grande.csv", "g", TestSupport.MADRID)
        assertEquals(ExportKind.HISTORY, r.kind)
        assertTrue(r.state.toString(), r.state != ImportState.TOO_LARGE)
    }

    @Test fun historyAboveItsOwnLimitIsRecognisedAndNotRead() {
        val f = bigHistory(ImportLimits.MAX_HISTORY_FILE_BYTES + 1024)
        val r = ImportPipeline.processFile(f, "enorme.csv", "e", TestSupport.MADRID)
        assertEquals(ImportState.TOO_LARGE, r.state); assertEquals(ExportKind.HISTORY, r.kind)
        assertTrue(r.message!!, r.message!!.contains("${ImportLimits.MAX_HISTORY_FILE_BYTES / 1048576} MiB"))
        assertEquals(com.alexisgordr.cellcase.core.importing.Hashing.sha256Hex(f.readBytes()), r.sha256)
    }

    @Test fun otherFilesKeepTheirLimitAndHistoryHasItsOwn() {
        val other = Files.createTempFile("otro", ".csv").toFile(); other.deleteOnExit()
        other.writeText("Columna,Otra\n" + "x,y\n".repeat(((ImportLimits.MAX_FILE_BYTES + 10) / 4).toInt()))
        assertEquals(ImportState.TOO_LARGE, ImportPipeline.processFile(other, "otro.csv", "o", TestSupport.MADRID).state)
        // en memoria: un CSV que no es historial y pasa de 6 MiB tampoco se interpreta
        val svc = (TestSupport.sample("icd30/SYNTHETIC_icd30_service_state.csv").readText().lines()[0] + "\n").toByteArray() +
            ByteArray((ImportLimits.MAX_FILE_BYTES + 1).toInt()) { 'x'.code.toByte() }
        assertEquals(ImportState.TOO_LARGE, ImportPipeline.process(svc, "s.csv", "s", TestSupport.MADRID).state)
        // un historial por encima de su propio límite tampoco
        val head = v3.readLines()[0] + "\n"
        val huge = head.toByteArray() + ByteArray((ImportLimits.MAX_HISTORY_FILE_BYTES + 1).toInt()) { 'x'.code.toByte() }
        assertEquals(ImportState.TOO_LARGE, ImportPipeline.process(huge, "h.csv", "h", TestSupport.MADRID).state)
    }
}
