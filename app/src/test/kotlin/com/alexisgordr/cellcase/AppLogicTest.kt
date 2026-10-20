package com.alexisgordr.cellcase

import com.alexisgordr.cellcase.core.backup.BackupLimits
import com.alexisgordr.cellcase.core.casefile.AnalysisConfig
import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportLimits
import com.alexisgordr.cellcase.logic.ConfigSummary
import com.alexisgordr.cellcase.logic.FileHash
import com.alexisgordr.cellcase.logic.ImportPolicy
import com.alexisgordr.cellcase.logic.ReportFileName
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

/** Lógica de la app sin Android (paquete `logic`): límites de copia, huellas, nombres de informe y resumen de configuración. */
class AppLogicTest {
    private val root = File(System.getProperty("cellcase.root") ?: "..").let { if (File(it, "samples").exists()) it else File("..") }
    private fun sample(name: String) = File(root, "samples/synthetic/$name")

    @Test fun zipFilesMayBeBackupsAndGetTheLargerCopyLimit() {
        val backup = sample("icd30/SYNTHETIC_icd30_fresh.icdbackup").readBytes()
        assertEquals(BackupLimits().maxBackupBytes, ImportPolicy.copyLimit(backup.copyOf(4)))
        assertEquals(BackupLimits().maxBackupBytes, ImportPolicy.copyLimit(sample("SYNTHETIC_ICD-topology.zip").readBytes().copyOf(4)))
        // texto: puede ser un historial de 3.0, que tiene su propio límite (D-054)
        assertEquals(ImportLimits.MAX_HISTORY_FILE_BYTES, ImportPolicy.copyLimit("Timestamp,NetType".toByteArray()))
        assertEquals(ImportLimits.MAX_HISTORY_FILE_BYTES, ImportPolicy.copyLimit(ByteArray(0)))
        assertEquals(ImportLimits.MAX_HISTORY_FILE_BYTES, ImportPolicy.copyLimit(byteArrayOf(0x50, 0x4B)))   // demasiado corto para ser un ZIP
        assertTrue(ImportLimits.MAX_HISTORY_FILE_BYTES > ImportLimits.MAX_FILE_BYTES)
    }

    @Test fun historyRowsAlreadyInTheCaseAreCountedFromTheOriginals() {
        val a = sample("SYNTHETIC_patrones_historial.csv"); val b = sample("icd30/SYNTHETIC_icd30_history.csv")
        val rows = ImportPolicy.historyRows(listOf(a, b))
        assertEquals(com.alexisgordr.cellcase.core.importing.CaseBudget.csvRows(a.readBytes()) + com.alexisgordr.cellcase.core.importing.CaseBudget.csvRows(b.readBytes()), rows)
        assertTrue(rows > 0)
        assertEquals(0, ImportPolicy.historyRows(emptyList()))
        assertEquals(0, ImportPolicy.historyRows(listOf(File("no-existe.csv"))))   // original que falta: no reserva nada
    }

    @Test fun peekDoesNotConsumeTheStream() {
        val data = sample("SYNTHETIC_patrones_radio.csv").readBytes()
        val input = BufferedInputStream(ByteArrayInputStream(data))
        assertArrayEquals(data.copyOf(4), ImportPolicy.peek(input))
        assertArrayEquals(data, input.readBytes())
        assertEquals(2, ImportPolicy.peek(BufferedInputStream(ByteArrayInputStream(byteArrayOf(1, 2)))).size)
        try { ImportPolicy.peek(ByteArrayInputStream(data).let { object : java.io.InputStream() { override fun read() = it.read(); override fun markSupported() = false } }); throw AssertionError() }
        catch (_: IllegalArgumentException) {}
    }

    @Test fun backupTempDirIsPrivateAndStartsEmpty() {
        val cache = Files.createTempDirectory("cache").toFile()
        val d1 = ImportPolicy.backupTempDir(cache)
        File(d1, "resto.db").writeText("de una ejecución interrumpida")
        val d2 = ImportPolicy.backupTempDir(cache)
        assertEquals(d1, d2); assertTrue(d2.isDirectory); assertTrue(d2.listFiles()!!.isEmpty())
        assertEquals(cache, d2.parentFile)
    }

    @Test fun fileHashStreamsAndMatchesTheCoreHash() {
        for (f in listOf(sample("icd30/SYNTHETIC_icd30_fresh.icdbackup"), sample("SYNTHETIC_patrones_historial.csv")))
            assertEquals(Hashing.sha256Hex(f.readBytes()), FileHash.sha256(f))
        val empty = File.createTempFile("vacio", ".bin"); empty.deleteOnExit()
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", FileHash.sha256(empty))
    }

    @Test fun reportFileNamesAreSafeAndDistinct() {
        assertEquals("CellCase-Caso_1__Pamplona_-explicado-es-12345678.pdf", ReportFileName.of("Caso 1 (Pamplona)", "EXPLAINED", "es", "123456789abc"))
        assertEquals("CellCase-x-plain-en-abcdefgh.pdf", ReportFileName.of("x", "EXPLAINED", "en", "abcdefghij"))
        assertEquals("CellCase-x-technical-en-abc.pdf", ReportFileName.of("x", "FULL", "en", "abc"))
        assertEquals("CellCase-x-tecnico-es-abc.pdf", ReportFileName.of("x", "FULL", "es", "abc"))
        assertEquals("CellCase-x-resumido-es-abc.pdf", ReportFileName.of("x", "SUMMARY", "es", "abc"))
        val n = ReportFileName.of("../../etc/ñandú:*?", "FULL", "es", "id")
        assertFalse(n.contains('/') || n.contains(':') || n.contains('*') || n.contains('ñ'))
        assertTrue(n.endsWith(".pdf"))
    }

    @Test fun configSummaryHasNoWordsOfAnyLanguage() {
        val cfg = AnalysisConfig("Europe/Madrid", true, null, fileHashes = listOf("a", "b"))
        assertEquals("Europe/Madrid · * · 2 × SHA-256 · ${cfg.rules}", ConfigSummary.of(cfg))
        val p = PeriodOfInterest(java.time.Instant.parse("2026-10-01T00:00:00Z"), java.time.Instant.parse("2026-10-02T00:00:00Z"), "viaje")
        assertEquals("UTC (?) · ${p.start}–${p.end} · 0 × SHA-256 · ${cfg.rules}", ConfigSummary.of(AnalysisConfig("UTC", false, p)))
    }
}
