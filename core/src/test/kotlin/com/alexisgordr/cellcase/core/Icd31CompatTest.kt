package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.backup.Icd30Exports
import com.alexisgordr.cellcase.core.backup.IcdBackupFormat
import com.alexisgordr.cellcase.core.backup.IcdBackupReader
import com.alexisgordr.cellcase.core.backup.SqliteFile
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.HistoryColumns
import com.alexisgordr.cellcase.core.importing.HistorySchema
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.backupParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile

/**
 * ICdetector 3.1.0 (CellCase 0.2.0): copia de esquema 26 y historial con el churn de H17. Las muestras salen de
 * tools/synth/generate_icd31.py (base de 3.0.0 actualizada a 3.1.0, con filas de 2.10.x, 3.0.0 y 3.1.0).
 */
class Icd31CompatTest {
    private val at = Instant.parse("2026-10-29T18:40:11.500Z").toEpochMilli()
    private val backup = TestSupport.sample("icd31/SYNTHETIC_icd31.icdbackup")
    private val csv = TestSupport.sample("icd31/SYNTHETIC_icd31_history.csv")

    private fun db(f: (SqliteFile) -> Unit) {
        val tmp = File.createTempFile("icd31-", ".db"); tmp.deleteOnExit()
        ZipFile(backup).use { z -> z.getInputStream(z.getEntry("icdetector_history.db")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } }
        SqliteFile(tmp).use(f)
    }

    private fun same(path: String, actual: ByteArray?) {
        val e = TestSupport.sample("icd31/expected/$path").readBytes()
        if (actual == null || !e.contentEquals(actual)) {
            val a = String(actual ?: ByteArray(0)).lines(); val x = String(e).lines()
            val i = x.indices.firstOrNull { it >= a.size || a[it] != x[it] } ?: a.size
            throw AssertionError("$path difiere en la línea ${i + 1}:\n esperado: ${x.getOrNull(i)}\n obtenido: ${a.getOrNull(i)}")
        }
    }

    // ── 1. Copia de esquema 26 ──

    @Test fun schema26IsSupportedAndTheBetasAndNewerAreNot() {
        assertEquals(26, IcdBackupFormat.SCHEMA)
        assertEquals(setOf(25, 26), IcdBackupFormat.SUPPORTED_SCHEMAS)
        val r = IcdBackupReader.read(backup, "c.icdbackup", "c", TestSupport.MADRID, tempDir = Files.createTempDirectory("b").toFile())
        assertEquals(ImportState.IMPORTED, r.state)
        val info = (r.data as ImportedData.Backup).info
        assertEquals(26, info.schemaVersion); assertEquals(26, info.userVersion)
        assertTrue(r.issues.none { it.code == "BACKUP_USER_VERSION" })
    }

    @Test fun everyExportOfTheSchema26DatabaseIsByteIdenticalToTheReference() {
        db { d ->
            val ex = Icd30Exports(d, "3.1.0+90627b7", at)
            same("history.csv", ex.historyCsv())
            same("service_state.csv", ex.serviceStateCsv())
            ex.topology()!!.forEach { (n, b) -> same("topology/$n", b) }
            ex.geometry()!!.forEach { (n, b) -> same("geometry/$n", b) }
            ex.stableSite()!!.forEach { (n, b) -> same("stable_site/$n", b) }
            val cases = ex.forensicCases()!!
            assertEquals(File(TestSupport.sample("icd31/expected"), "forensic").listFiles()!!.size, cases.size)
            cases.forEach { c -> c.files.forEach { (n, b) -> same("forensic/${c.caseCode}/$n", b) } }
        }
    }

    @Test fun churnColumnsAreDecidedByTheTableNotTheSchemaNumber() {
        db { d -> assertTrue(String(Icd30Exports(d, "3.1.0", at).historyCsv()!!).lineSequence().first().endsWith(",ChurnCells15m,ChurnChanges15m,ChurnMotion")) }
        // Base de 3.0.0 (esquema 25): sin las columnas, 54 columnas
        val tmp = File.createTempFile("icd30-", ".db"); tmp.deleteOnExit()
        ZipFile(TestSupport.sample("icd30/SYNTHETIC_icd30_fresh.icdbackup")).use { z -> z.getInputStream(z.getEntry("icdetector_history.db")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } }
        SqliteFile(tmp).use { d ->
            val head = String(Icd30Exports(d, "3.0.0", at).historyCsv()!!).lineSequence().first()
            assertEquals(Icd30Exports.HISTORY_HEADER, head); assertEquals(54, head.split(",").size)
        }
        assertEquals(57, Icd30Exports.HISTORY_HEADER_31.split(",").size)
        assertEquals(40L, Icd30Exports.knownVersionCode("3.1.0+90627b7"))
    }

    @Test fun backupAndItsExportsGiveTheSameAnalysis() {
        val fromBackup = IcdBackupReader.read(backup, "c.icdbackup", "c", TestSupport.MADRID, tempDir = Files.createTempDirectory("b").toFile())
        assertTrue(fromBackup.backupParts.all { it.imported })
        val parts = File(TestSupport.sample("icd31"), ".").listFiles()!!.filter { it.isFile && !it.name.endsWith(".icdbackup") }.sortedBy { it.name }
            .map { ImportPipeline.processFile(it, it.name, it.name, TestSupport.MADRID) }
        assertTrue(parts.all { it.imported })
        val a = CaseAnalyzer.analyze(listOf(fromBackup), TestSupport.MADRID, null)
        val b = CaseAnalyzer.analyze(parts, TestSupport.MADRID, null)
        assertEquals(b.history!!.import.observations.size, a.history!!.import.observations.size)
        assertEquals(b.episodes.episodes.map { listOf(it.start, it.end, it.mainPattern, it.priority) }, a.episodes.episodes.map { listOf(it.start, it.end, it.mainPattern, it.priority) })
        assertEquals(b.assessment, a.assessment)
        assertEquals(4, parts.count { it.kind == ExportKind.FORENSIC_CASE })
    }

    // ── 2. Historial CSV con churn ──

    private fun history(r: ImportResult) = (r.data as ImportedData.History).data

    @Test fun historyCsvOf31IsReadWithTheChurnColumns() {
        val r = ImportPipeline.processFile(csv, csv.name, "h", TestSupport.MADRID)
        val h = history(r)
        assertEquals(HistorySchema.V3_1, h.schema)
        assertTrue(h.uninterpretedColumns.none { it in HistoryColumns.V31 })
        assertTrue(r.issues.none { it.code == "EXTRA_COLUMNS" })
        val v31 = h.observations.filter { it.appVersion?.startsWith("3.1.0") == true }
        val older = h.observations.filter { it.appVersion?.startsWith("3.1.0") != true }
        assertTrue(v31.isNotEmpty() && older.isNotEmpty())
        assertTrue(v31.all { it.churnCells15m != null && it.churnChanges15m != null && it.churnMotion in HistoryColumns.CHURN_MOTIONS })
        assertTrue(older.all { it.churnCells15m == null && it.churnChanges15m == null && it.churnMotion == null })   // anteriores a 3.1.0: sin dato
        assertTrue(v31.all { it.churnCells15m!! >= 1 && it.churnChanges15m!! >= 0 })
    }

    @Test fun invalidChurnValuesAreWarningsWithProvenanceNotRejections() {
        val lines = csv.readLines().toMutableList()
        val head = lines[0].split(",")
        fun setCol(i: Int, col: String, v: String) {
            val f = lines[i].split(",").toMutableList()   // las filas 3.1.0 no llevan comas entre comillas
            f[head.indexOf(col)] = v; lines[i] = f.joinToString(",")
        }
        val rows31 = lines.indices.filter { it > 0 && "3.1.0+" in lines[it] && '"' !in lines[it] }
        setCol(rows31[0], "ChurnCells15m", "-2")
        setCol(rows31[1], "ChurnChanges15m", "muchos")
        setCol(rows31[2], "ChurnMotion", "RUNNING")
        val r = ImportPipeline.process((lines.joinToString("\n") + "\n").toByteArray(), "h.csv", "h", TestSupport.MADRID)
        assertEquals(ImportState.IMPORTED, r.state)
        val h = history(r)
        assertEquals(lines.size - 1, h.observations.size)
        fun issue(col: String) = r.issues.single { it.column == col }
        assertEquals("OUT_OF_RANGE", issue("ChurnCells15m").code)
        assertEquals("VALUE_INVALID", issue("ChurnChanges15m").code)
        assertEquals("ENUM_UNKNOWN", issue("ChurnMotion").code)
        for (col in listOf("ChurnCells15m", "ChurnChanges15m", "ChurnMotion")) {
            val ex = issue(col).examples.single()
            assertEquals("h.csv", ex.provenance!!.fileName); assertTrue(ex.provenance!!.startLine > 1)
        }
        val bad = h.observations.single { it.provenance.startLine == rows31[0] + 1 }
        assertNull(bad.churnCells15m); assertTrue(bad.churnChanges15m != null)
    }

    @Test fun partialChurnColumnsArePartialV3() {
        val lines = csv.readLines()
        val head = lines[0].split(","); val drop = head.indexOf("ChurnMotion")
        val cut = lines.filter { '"' !in it }.joinToString("\n") { l -> l.split(",").filterIndexed { i, _ -> i != drop }.joinToString(",") } + "\n"
        val r = ImportPipeline.process(cut.toByteArray(), "h.csv", "h", TestSupport.MADRID)
        assertEquals(HistorySchema.PARTIAL_V3, history(r).schema)
        assertTrue(r.issues.any { it.code == "SCHEMA_PARTIAL_V3" && it.text.toString().contains("ChurnMotion") })
        assertFalse(r.issues.any { it.code == "EXTRA_COLUMNS" })
    }
}
