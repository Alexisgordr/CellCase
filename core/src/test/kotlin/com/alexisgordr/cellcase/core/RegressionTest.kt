package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.CriterionCode
import com.alexisgordr.cellcase.core.analysis.EpisodeEngine
import com.alexisgordr.cellcase.core.analysis.ForensicVersions
import com.alexisgordr.cellcase.core.analysis.HistoryMerge
import com.alexisgordr.cellcase.core.analysis.MeasureStatus
import com.alexisgordr.cellcase.core.analysis.MeasurementAvailability
import com.alexisgordr.cellcase.core.analysis.PciPattern
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.Quality
import com.alexisgordr.cellcase.core.analysis.RowMatch
import com.alexisgordr.cellcase.core.analysis.SpoofingRelevance
import com.alexisgordr.cellcase.core.analysis.VersionRelation
import com.alexisgordr.cellcase.core.casefile.AtomicFiles
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.Checkpoint
import com.alexisgordr.cellcase.core.importing.CsvTables
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.HistoryImport
import com.alexisgordr.cellcase.core.importing.ImportLimits
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportRejected
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.SafeZip
import com.alexisgordr.cellcase.core.importing.SelectedFile
import com.alexisgordr.cellcase.core.importing.ZipLimits
import com.alexisgordr.cellcase.core.model.IssueCollector
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.ReportTextsEs
import com.alexisgordr.cellcase.core.report.TextContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pruebas de regresión de la revisión del 2026-10-02 (docs/review-2026-10-02.md). Cada prueba nombra el hallazgo.
 * Todos los datos son sintéticos (PLMN de pruebas 001-01).
 */
class RegressionTest {
    private val RF = "[sub-umbral] Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)"

    /** Fila LTE con CID, TAC, EARFCN y PCI dados. */
    private fun r(ts: String, pci: Int, reason: String = "OK", cid: String = "6001", tac: String = "1001", earfcn: Int = 1300, radio: String = "LTE") =
        row(ts, mapOf("CID" to cid, "TAC" to tac, "ARFCN" to earfcn.toString(), "PCI" to pci.toString(), "Bands" to "", "FailedHeuristics" to reason,
            "Radio" to radio, "NetType" to if (radio == "NR") "5G" else "4G"))

    private fun analyze(vararg rows: String): CaseAnalysis =
        CaseAnalyzer.analyze(listOf(ImportPipeline.process(csv(*rows).toByteArray(), "h.csv", "h", TestSupport.MADRID)), TestSupport.MADRID, null)

    private fun rfEpisode(vararg rows: String) = analyze(*rows).episodes.episodes.single { it.mainPattern.name == "RF_IDENTITY" }

    // ── 1. Cambio de PCI frente a alternancia ───────────────────────────

    @Test fun singlePciChangeIsNotAlternation() {
        val e = rfEpisode(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 13, RF), r("2026-09-29 13:56:00", 13))
        assertEquals(MeasureStatus.PARTIAL, e.measurement.status)
        assertEquals(PciPattern.SINGLE_CHANGE, e.measurement.values["pattern"])
        val text = ReportBuilder(ReportTextsEs).build(ctx(listOf(), ReportMode.FULL), analyze(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 13, RF), r("2026-09-29 13:56:00", 13)))
            .blocks.joinToString("\n") { it.toString() }
        assertTrue(text.contains("único cambio de PCI, no una alternancia demostrada"))
    }

    @Test fun returnAndRepeatedAlternationAreDistinguished() {
        val ret = rfEpisode(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 13, RF), r("2026-09-29 13:56:00", 12))
        assertEquals(PciPattern.RETURN, ret.measurement.values["pattern"]); assertEquals(MeasureStatus.CONSISTENT, ret.measurement.status)
        val rep = rfEpisode(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:51:00", 13, RF), r("2026-09-29 13:52:00", 12), r("2026-09-29 13:53:00", 13))
        assertEquals(PciPattern.REPEATED_ALTERNATION, rep.measurement.values["pattern"])
        // la secuencia conserva tramos con horas y referencias
        @Suppress("UNCHECKED_CAST")
        val series = (ret.measurement.values["series"] as List<EpisodeEngine.PciSeries>).single()
        assertEquals(listOf(12, 13, 12), series.runs.map { it.pci })
        assertTrue(series.runs.all { it.refs.isNotEmpty() })
    }

    @Test fun repeatedRowsAreNotNewChanges() {
        // misma hora y mismo PCI repetidos: una sola lectura
        val e = rfEpisode(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 13, RF), r("2026-09-29 13:53:44", 13))
        @Suppress("UNCHECKED_CAST")
        val s = (e.measurement.values["series"] as List<EpisodeEngine.PciSeries>).single()
        assertEquals(PciPattern.SINGLE_CHANGE, s.pattern)
        assertTrue(s.coincident >= 1)
    }

    // ── Mismo CID con distinta área, tecnología o portadora ─────────────

    @Test fun sameCidUnderOtherAreaTechnologyOrCarrierIsAnotherSeries() {
        for ((other, label) in listOf(
            mapOf("tac" to "2002") to "área",
            mapOf("radio" to "NR") to "tecnología",
            mapOf("earfcn" to "6300") to "portadora")) {
            val o = r("2026-09-29 13:52:00", 13, cid = "6001", tac = other["tac"] ?: "1001", earfcn = other["earfcn"]?.toInt() ?: 1300, radio = other["radio"] ?: "LTE")
            val e = rfEpisode(r("2026-09-29 13:50:00", 12), o, r("2026-09-29 13:53:44", 12, RF), r("2026-09-29 13:56:00", 12))
            assertEquals(label, MeasureStatus.NOT_VISIBLE, e.measurement.status)   // la serie de la celda solo tiene PCI 12
        }
    }

    // ── 2. Reexportaciones forenses ─────────────────────────────────────

    private fun forensicSample(): Pair<ImportResult, com.alexisgordr.cellcase.core.model.ForensicCaseData> {
        val res = ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes(), "caso.zip", "f1", TestSupport.MADRID)
        return res to (res.data as ImportedData.Forensic).data
    }
    private fun v(id: String, d: com.alexisgordr.cellcase.core.model.ForensicCaseData) = ForensicVersions.Version(id, "$id.zip", Hashing.sha256Hex(id.toByteArray()), d)

    @Test fun sameTimesDifferentContentIsContradictoryAndBothKept() {
        val (_, d) = forensicSample()
        val changed = d.copy(samples = d.samples.mapIndexed { i, s -> if (i == 3) s.copy(score = (s.score ?: 0) + 7) else s })
        val g = ForensicVersions.group(listOf(v("a", d), v("b", changed))).single()
        assertEquals(VersionRelation.CONTRADICTORY, g.relation)
        assertEquals(setOf("a", "b"), g.used.toSet())
    }

    @Test fun extendedReexportIsRecognisedInAnyOrder() {
        val (_, d) = forensicSample()
        // versión anterior realista: captura aún en curso, sin cierre y con 5 muestras menos
        val shorter = d.copy(samples = d.samples.dropLast(5), meta = d.meta.copy(state = "POST_CAPTURE", closedAt = null, sampleCount = d.samples.size - 5L))
        val g1 = ForensicVersions.group(listOf(v("corto", shorter), v("largo", d))).single()
        val g2 = ForensicVersions.group(listOf(v("largo", d), v("corto", shorter))).single()
        assertEquals(VersionRelation.EXTENDED, g1.relation); assertEquals(listOf("largo"), g1.used)
        assertEquals(g1, g2)
    }

    @Test fun repeatedSamplesKeepEquivalence() {
        val (_, d) = forensicSample()
        val withDup = d.copy(samples = d.samples + d.samples.first())
        val g = ForensicVersions.group(listOf(v("a", d), v("b", withDup))).single()
        assertEquals(VersionRelation.EQUIVALENT, g.relation); assertEquals(1, g.used.size)
    }

    // ── 3. Historiales con revisiones y orden inverso ───────────────────

    private fun hist(name: String, text: String): HistoryImport =
        (ImportPipeline.process(text.toByteArray(), name, "id-$name-${text.hashCode()}", TestSupport.MADRID).data as ImportedData.History).data

    @Test fun historiesWithRevisionsGiveSameFactsInReverseOrder() {
        val sub = "[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)"
        val a = hist("a.csv", csv(
            row("2026-09-25 09:55:00", mapOf("CID" to "5001", "ARFCN" to "1300", "Bands" to "")),
            row("2026-09-25 10:00:00", mapOf("CID" to "5002", "ARFCN" to "6300", "Bands" to "", "FailedHeuristics" to sub, "Verified" to "PENDING")),
            row("2026-09-25 10:05:00", mapOf("CID" to "5002", "ARFCN" to "6300", "Bands" to ""))))
        val b = hist("b.csv", csv(
            row("2026-09-25 10:00:00", mapOf("CID" to "5002", "ARFCN" to "6300", "Bands" to "", "FailedHeuristics" to sub, "Verified" to "NOT_FOUND")),
            row("2026-09-25 10:05:00", mapOf("CID" to "5002", "ARFCN" to "6300", "Bands" to "")),
            row("2026-09-25 10:10:00", mapOf("CID" to "5003", "ARFCN" to "6300", "Bands" to ""))))
        val ab = HistoryMerge.merge(listOf(a, b), TestSupport.MADRID)!!
        val ba = HistoryMerge.merge(listOf(b, a), TestSupport.MADRID)!!
        assertEquals(1, ab.revisedMatches); assertEquals(1, ab.exactMatches)
        assertEquals(ab.revisedMatches, ba.revisedMatches); assertEquals(ab.exactMatches, ba.exactMatches)
        assertEquals(ab.import.observations.map { it.raw }, ba.import.observations.map { it.raw })
        // la revisión no se resuelve eligiendo un archivo: el campo en conflicto queda sin valor y se conservan ambos
        val revised = ab.import.observations.single { it.raw["Timestamp"] == "2026-09-25 10:00:00" }
        assertNull(revised.verified)
        assertEquals(setOf("PENDING", "NOT_FOUND"), ab.matches.single { it.kind == RowMatch.REVISION }.fields.getValue("Verified").toList().toSet())
        // mismos hechos y conclusiones con los resultados en orden inverso
        val ra = ImportPipeline.process(csv(row("2026-09-25 09:55:00", mapOf("CID" to "5001", "ARFCN" to "1300", "Bands" to "")),
            row("2026-09-25 10:00:00", mapOf("CID" to "5002", "ARFCN" to "6300", "Bands" to "", "FailedHeuristics" to sub, "Verified" to "PENDING"))).toByteArray(), "a.csv", "x1", TestSupport.MADRID)
        val rb = ImportPipeline.process(csv(row("2026-09-25 10:00:00", mapOf("CID" to "5002", "ARFCN" to "6300", "Bands" to "", "FailedHeuristics" to sub, "Verified" to "NOT_FOUND")),
            row("2026-09-25 10:10:00", mapOf("CID" to "5003", "ARFCN" to "6300", "Bands" to ""))).toByteArray(), "b.csv", "x2", TestSupport.MADRID)
        val c1 = CaseAnalyzer.analyze(listOf(ra, rb), TestSupport.MADRID, null)
        val c2 = CaseAnalyzer.analyze(listOf(rb, ra), TestSupport.MADRID, null)
        fun facts(c: CaseAnalysis) = c.episodes.episodes.map { listOf(it.start, it.end, it.identities.map { f -> f.key }, it.priority, it.relevance.relevance, it.measurement.status, it.moments) }
        assertEquals(facts(c1), facts(c2))
        assertEquals(ReportTextsEs.overallStatement(c1), ReportTextsEs.overallStatement(c2))
    }

    @Test fun schemaDifferencesAreComparedOnCommonColumns() {
        val base = TestSupport.HEADER.split(",").take(23)
        val old = base.joinToString(",") + "\n" + row("2026-09-25 10:00:00").split(",").take(23).joinToString(",") + "\n"
        val a = hist("v21.csv", old)
        val b = hist("v2105.csv", csv(row("2026-09-25 10:00:00")))
        val m = HistoryMerge.merge(listOf(a, b), TestSupport.MADRID)!!
        assertEquals(1, m.matches.size); assertTrue(m.matches.single().schemaDiffers)
        assertEquals(1, m.import.observations.size)
    }

    // ── 5. Cancelación tras importar parte de un lote ───────────────────

    @Test fun cancellationAfterPartOfABatchKeepsCompletedFilesOnly() {
        val files = listOf("SYNTHETIC_icdetector_history.csv", "SYNTHETIC_icdetector_service_state.csv", "SYNTHETIC_ICD-topology.zip", "SYNTHETIC_ICD-geometry.zip")
            .map { SelectedFile(it, TestSupport.sample(it).readBytes()) }
        val committed = ArrayList<BatchOutcome>()
        var checks = 0
        var cancelAt = Int.MAX_VALUE
        val cp = Checkpoint { checks++; if (checks >= cancelAt) throw CancellationException("cancelado") }
        var n = 0
        try {
            BatchImport.run(files, emptyMap(), TestSupport.MADRID, { "i${++n}" }, cp, commit = { o -> committed += o; if (committed.size == 2) cancelAt = checks + 1 })
            fail("debía cancelarse")
        } catch (e: CancellationException) { /* esperado */ }
        assertEquals(2, committed.size)
        assertEquals(listOf(files[0].name, files[1].name), committed.map { (it as BatchOutcome.Processed).result.fileName })
    }

    @Test fun cancellationInsideAFileIsNotTurnedIntoAReadError() {
        var checks = 0
        try {
            ImportPipeline.process(TestSupport.sample("SYNTHETIC_icdetector_history.csv").readBytes(), "h.csv", "i", TestSupport.MADRID, Checkpoint { if (++checks > 1) throw CancellationException("x") })
            fail("debía propagarse la cancelación")
        } catch (e: CancellationException) { /* esperado */ }
    }

    // ── Fallos de escritura ──────────────────────────────────────────────

    @Test fun failedWriteLeavesNoPartialFileAndKeepsThePreviousOne() {
        val dir = Files.createTempDirectory("aw").toFile()
        try {
            val target = File(dir, "informe.pdf")
            try { AtomicFiles.write(target) { it.write(ByteArray(1000)); throw IOException("disco lleno") }; fail() } catch (e: IOException) { /* esperado */ }
            assertFalse(target.exists()); assertTrue(AtomicFiles.leftovers(dir).isEmpty())
            AtomicFiles.write(target) { it.write("v1".toByteArray()) }
            try { AtomicFiles.write(target) { it.write("v2-parcial".toByteArray()); throw CancellationException("cancelado") }; fail() } catch (e: CancellationException) { /* esperado */ }
            assertEquals("v1", target.readText())
            // un PDF que falla a mitad de generación tampoco deja archivo
            val (c, a) = ctx(listOf(), ReportMode.FULL) to analyze(r("2026-09-29 13:50:00", 12))
            val pdf = File(dir, "x.pdf")
            try { AtomicFiles.write(pdf) { out -> ReportBuilder(ReportTextsEs).writePdf(c, a, FailingStream(out, 2000)) }; fail() } catch (e: IOException) { /* esperado */ }
            assertFalse(pdf.exists())
        } finally { dir.deleteRecursively() }
    }

    private class FailingStream(private val inner: java.io.OutputStream, private val limit: Int) : java.io.OutputStream() {
        private var n = 0
        override fun write(b: Int) { if (++n > limit) throw IOException("fallo de escritura simulado"); inner.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { if (n + len > limit) throw IOException("fallo de escritura simulado"); n += len; inner.write(b, off, len) }
    }

    // ── 4. Límites de memoria y descompresión ───────────────────────────

    @Test fun largeFileIsRejectedBeforeLoading() {
        val f = File.createTempFile("grande", ".csv")
        try {
            RandomAccessFile(f, "rw").use { it.setLength(ImportLimits.MAX_FILE_BYTES + 1) }  // disperso: no ocupa memoria
            val r = ImportPipeline.processFile(f, "grande.csv", "g", TestSupport.MADRID)
            assertEquals(ImportState.TOO_LARGE, r.state); assertNull(r.data)
            assertEquals(ImportLimits.MAX_FILE_BYTES + 1, r.sizeBytes)
        } finally { f.delete() }
    }

    @Test fun streamCopyStopsAtTheLimitWithoutBuffering() {
        val endless = object : InputStream() { override fun read() = 0; override fun read(b: ByteArray, off: Int, len: Int): Int { java.util.Arrays.fill(b, off, off + len, 0); return len } }
        var received = 0L
        try { Hashing.copyHashing(endless, { _, n -> received += n }, 1L shl 20); fail() } catch (e: ImportRejected) { /* esperado */ }
        assertTrue("recibidos $received", received <= 1L shl 20)
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) ->
            val e = ZipEntry(n)
            if (stored) { e.method = ZipEntry.STORED; e.size = b.size.toLong(); e.compressedSize = b.size.toLong(); e.crc = java.util.zip.CRC32().apply { update(b) }.value }
            z.putNextEntry(e); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun zipEntryLimitsCountRealBytesAndDeclaredSizes() {
        val small = ZipLimits(maxEntryBytes = 64 * 1024, maxTotalBytes = 96 * 1024, maxRatio = 10_000)
        // tamaño declarado (STORED): se rechaza antes de leer
        val declared = runCatching { SafeZip.readAll(zip("a.csv" to ByteArray(100 * 1024) { 'x'.code.toByte() }, stored = true), small) }.exceptionOrNull()
        assertTrue(declared is ImportRejected && declared.message!!.contains("declara"))
        // sin tamaño declarado (DEFLATED con descriptor): se cuentan los bytes reales descomprimidos
        val real = runCatching { SafeZip.readAll(zip("a.csv" to ByteArray(100 * 1024) { 'x'.code.toByte() }), small) }.exceptionOrNull()
        assertTrue(real is ImportRejected && real.message!!.contains("supera"))
        // total de varias entradas
        val total = runCatching { SafeZip.readAll(zip("a.csv" to ByteArray(60 * 1024), "b.csv" to ByteArray(60 * 1024)), small) }.exceptionOrNull()
        assertTrue(total is ImportRejected)
    }

    @Test fun rowAndFieldLimits() {
        val rows = "a,b\n" + (1..11).joinToString("\n") { "$it,x" } + "\n"
        val tooMany = runCatching { CsvTables.read(rows.toByteArray(), "i", "t.csv", null, listOf("a", "b"), IssueCollector(), maxRows = 10) }.exceptionOrNull()
        assertTrue(tooMany is ImportRejected)
        val longField = "a,b\n1," + "x".repeat(ImportLimits.MAX_FIELD_CHARS + 1) + "\n"
        val r = runCatching { CsvTables.read(longField.toByteArray(), "i", "t.csv", null, listOf("a", "b"), IssueCollector()) }
        assertTrue(r.isFailure)
    }

    // ── 6. Patrones no evaluables sin calidad ni prioridad injustificadas ─

    @Test fun nonEvaluablePatternGetsNoAutomaticQualityOrPriority() {
        // Alarma (no sub-umbral) de un patrón sin comprobación propia, repetida en dos días
        val tac = "Desviación TAC"
        val a = analyze(r("2026-09-25 10:00:00", 12, tac), r("2026-09-26 10:00:00", 12, tac))
        for (e in a.episodes.episodes) {
            assertEquals(Quality.GOOD, e.quality)                                         // datos completos
            assertEquals(MeasurementAvailability.NO_CHECK_IMPLEMENTED, e.measurement.availability)
            assertEquals(MeasureStatus.NOT_EVALUABLE, e.measurement.status)
            assertTrue(e.criteria.any { it.code == CriterionCode.NO_CHECK_IMPLEMENTED })
            assertEquals(Priority.REVIEW, e.priority)                                     // alarma: revisar, nunca prioritaria sin comprobación
        }
        assertTrue(ReportTextsEs.priorityReason(a.episodes.episodes.first()).contains("no evaluable no equivale a no contradictorio"))
    }

    @Test fun observationForensicCaseIsNotAnAlarm() {
        val (res, d) = forensicSample()
        val obs = res.copy(data = ImportedData.Forensic(d.copy(meta = d.meta.copy(captureOrigin = "TRUST_CONTRADICTION", caseCode = "ICD-OBS-0001"))))
        val a = CaseAnalyzer.analyze(listOf(obs), TestSupport.MADRID, null)
        val e = a.episodes.episodes.single()
        assertFalse(e.hasAlarm)
        assertTrue(e.criteria.any { it.code == CriterionCode.FORENSIC_OBSERVATION_CASE })
        assertTrue(e.priority != Priority.PRIORITY)
        assertTrue(e.relevance.reasons.none { it.name == "FORENSIC_CASE" })
        // con origen ALARM sí cuenta como alarma, pero sin comprobación de mediciones no llega a prioritaria
        val alarm = CaseAnalyzer.analyze(listOf(res), TestSupport.MADRID, null).episodes.episodes.single()
        assertTrue(alarm.hasAlarm); assertTrue(alarm.priority != Priority.PRIORITY)
        assertEquals(SpoofingRelevance.NEEDS_MORE_DATA, alarm.relevance.relevance)
    }

    private fun ctx(results: List<ImportResult>, mode: ReportMode) = TextContext(
        CaseInfo("c", "Prueba", java.time.Instant.EPOCH, "Teléfono", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null), results,
        ReportOptions(generatedAt = java.time.Instant.EPOCH, appVersion = "t", mode = mode))
}
