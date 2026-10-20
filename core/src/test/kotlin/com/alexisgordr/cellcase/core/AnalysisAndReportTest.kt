package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.importHistory
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.HistoryAnalyzer
import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.SignalSource
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.SelectedFile
import com.alexisgordr.cellcase.core.report.DataOrigin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import com.alexisgordr.cellcase.core.model.CollectorVerdict
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.pdf.PdfFont
import com.alexisgordr.cellcase.core.pdf.ReportRenderer
import com.alexisgordr.cellcase.core.pdf.WinAnsi
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.IdentifierMode
import com.alexisgordr.cellcase.core.report.Privacy
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.ReportTextsEs
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.time.DeclaredZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.zip.Inflater

class AnalysisAndReportTest {

    @Test fun syntheticPeriodAndCountsMatchTheFile() {
        val a = HistoryAnalyzer.analyze(TestSupport.importSample("SYNTHETIC_icdetector_history.csv"))
        assertEquals("2026-09-26 00:00:00", a.period.first!!.time.raw)
        assertEquals("2026-09-28 23:45:00", a.period.last!!.time.raw)
        assertEquals(a.period.validTimeRows, a.period.rowsPerDay.sumOf { it.second })
        assertEquals(3, a.period.daysWithData)
        assertEquals(651, a.time.exact)
        // Escenario sintético: 1 fila con motivo de alarma y 1 sub-umbral (MANIFEST.json)
        assertEquals(1, a.collector.verdicts[CollectorVerdict.REASON_WITHOUT_PREFIX])
        assertEquals(1, a.collector.verdicts[CollectorVerdict.SUBTHRESHOLD])
        assertEquals(78.0, a.collector.maxAnomalyConfidence!!, 1e-9)
        // Muestreo continuo cada 5/15 min: sin huecos de 60 min
        assertTrue(a.sampling.evaluable); assertEquals(0, a.sampling.gaps.size)
        assertEquals(651, a.testNet.rows)
    }

    @Test fun gapsAreMeasuredInUtcAcrossTheDstChange() {
        // 01:50 y 02:10 locales del 25/10/2026: el reloj retrocede a las 03:00; con la zona confirmada,
        // 01:50 (+02:00) → 23:50Z y 03:10 (+01:00) → 02:10Z = 2 h 20 min.
        val imp = importHistory(csv(row("2026-10-25 01:50:00"), row("2026-10-25 03:10:00")))
        val s = HistoryAnalyzer.analyze(imp).sampling
        assertEquals(1, s.gaps.size)
        assertEquals(Duration.ofMinutes(140), s.gaps.single().duration)
    }

    @Test fun ambiguousRowsAreExcludedFromGapsNotGuessed() {
        val imp = importHistory(csv(row("2026-10-25 01:59:00"), row("2026-10-25 02:30:00"), row("2026-10-25 03:01:00")))
        val a = HistoryAnalyzer.analyze(imp)
        assertEquals(1, a.time.ambiguous.size)
        assertEquals(2, a.sampling.rowsUsed)
    }

    @Test fun samplingIsNotEvaluableWithoutConfirmedZone() {
        val imp = importHistory(csv(row("2026-09-27 10:00:00"), row("2026-09-27 15:00:00")), zone = DeclaredZone(ZoneId.of("Europe/Madrid"), false))
        val a = HistoryAnalyzer.analyze(imp)
        assertFalse(a.sampling.evaluable)
        assertEquals("NO_ZONE", a.sampling.notEvaluableReason)
    }

    @Test fun powerIsNeverMixedAcrossTechnologies() {
        val imp = importHistory(csv(
            row("2026-09-27 10:00:00", mapOf("DBM" to "-80")),
            row("2026-09-27 10:05:00", mapOf("DBM" to "-100", "Radio" to "NR", "CID" to "5000000001", "PCI" to "501", "ARFCN" to "632448", "TA" to "", "TAUnit" to "", "TAMeters" to ""))
        ))
        val p = HistoryAnalyzer.analyze(imp).power
        assertEquals(listOf(RadioTech.LTE, RadioTech.NR), p.stats.map { it.radio })
        assertEquals(-80, p.stats[0].median); assertEquals(-100, p.stats[1].median)
    }

    @Test fun periodOfInterestCountsOnlyUnambiguousRows() {
        val imp = importHistory(csv(row("2026-09-27 21:00:00"), row("2026-09-27 21:20:00"), row("2026-09-27 23:00:00")))
        val poi = PeriodOfInterest(Instant.parse("2026-09-27T19:00:00Z"), Instant.parse("2026-09-27T19:30:00Z"), "Incidente")
        assertEquals(2, HistoryAnalyzer.analyze(imp, poi).period.rowsInPeriod)
    }

    private fun caseOf(origin: DataOrigin = DataOrigin.SYNTHETIC, ids: IdentifierMode = IdentifierMode.FULL, names: List<String> = ALL,
                       mode: com.alexisgordr.cellcase.core.report.ReportMode = com.alexisgordr.cellcase.core.report.ReportMode.FULL): Pair<TextContext, CaseAnalysis> {
        var n = 0
        val results = BatchImport.run(names.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
            .mapNotNull { (it as? BatchOutcome.Processed)?.result }
        val a = CaseAnalyzer.analyze(results, TestSupport.MADRID, null)
        return TextContext(
            CaseInfo("case-1", "Prueba", Instant.parse("2026-10-01T10:00:00Z"), "Teléfono de prueba", TestSupport.MADRID, origin, "Nota del analista.", null),
            results,
            ReportOptions(identifiers = ids, generatedAt = Instant.parse("2026-10-02T08:00:00Z"), appVersion = "0.2.0-test", mode = mode)
        ) to a
    }

    private val ALL = listOf("SYNTHETIC_icdetector_history.csv", "SYNTHETIC_icdetector_service_state.csv", "SYNTHETIC_ICD-topology.zip",
        "SYNTHETIC_ICD-stable-site.zip", "SYNTHETIC_ICD-geometry.zip", "SYNTHETIC_ICD-2026-09-27-0007.zip")

    @Test fun everyImporterFeedsTheModelAndTheReport() {
        val (ctx, a) = caseOf()
        assertNotNull(a.history); assertTrue(a.serviceEvents.isNotEmpty()); assertNotNull(a.topology); assertNotNull(a.geometry)
        assertNotNull(a.stableSite); assertEquals(1, a.forensicCases.size)
        val all = allText(ReportBuilder(ReportTextsEs).build(ctx, a).blocks)
        for (h in listOf("Transiciones entre celdas (Topología)", "Estado de servicio (Radio)", "Casos forenses", "Lugares habituales (Stable-Site)", "Movilidad (Geometría)"))
            assertTrue(h, all.contains(h))
        assertTrue(all.contains("Todos los análisis previstos tienen datos"))
    }

    @Test fun syntheticIncidentFormsAPriorityEpisodeWithSeveralSources() {
        val (_, a) = caseOf()
        val eps = a.episodes.episodes
        assertTrue(eps.isNotEmpty())
        val incident = eps.first { e -> e.identities.any { it.key == "001-01-1099-9999001-LTE" } }
        assertTrue(incident.sources.size >= 2)
        assertTrue(incident.sources.contains(SignalSource.FORENSIC_CASE))
        assertTrue(incident.priority != Priority.CONTEXT)
        assertTrue(incident.durationKnown)          // la ventana del caso forense es un intervalo
        assertTrue(incident.moments >= 2)
    }

    @Test fun missingFormatsAppearOnceInTheAvailabilityTable() {
        val (ctx, a) = caseOf(names = listOf("SYNTHETIC_icdetector_history.csv", "SYNTHETIC_hostile_truncated.zip"))
        val doc = ReportBuilder(ReportTextsEs).build(ctx, a)
        val table = doc.blocks.filterIsInstance<Block.Table>().single { it.caption == "Análisis no disponibles y motivo" }
        val statuses = table.rows.map { it[1] }.toSet()
        assertTrue(statuses.contains("No aportado"))
        assertEquals(table.rows.size, table.rows.map { it[0] }.distinct().size)
        val files = doc.blocks.filterIsInstance<Block.Table>().single { it.caption == "Archivos aportados" }
        assertTrue(files.rows.any { it[2] == "ZIP dañado" })
    }

    @Test fun reportNeverClaimsDetection() {
        val (ctx, a) = caseOf()
        val all = allText(ReportBuilder(ReportTextsEs).build(ctx, a).blocks).lowercase()
        for (forbidden in listOf("se ha detectado un imsi", "detección confirmada", "probabilidad de", "hay un imsi catcher", "existe un imsi catcher", "amenaza confirmada", "posible imsi catcher"))
            assertFalse(forbidden, all.contains(forbidden))
        assertTrue(all.contains("no se puede establecer una atribución"))
        assertTrue(all.contains("tampoco demuestra que no haya habido ningún problema"))
    }

    @Test fun maskedIdentifiersDoNotLeakCid() {
        assertEquals("001-01-**01-*****01-LTE", Privacy.identityKey("001-01-1001-1000001-LTE", IdentifierMode.MASKED))
        val (ctx, a) = caseOf(ids = IdentifierMode.MASKED)
        val all = allText(ReportBuilder(ReportTextsEs).build(ctx, a).blocks)
        assertFalse(all.contains("1000001-LTE")); assertFalse(all.contains("9999001"))
    }

    @Test fun bannerFollowsTheDeclaredOrigin() {
        assertEquals("DATOS SINTÉTICOS — NO REALES", ReportTextsEs.banner(DataOrigin.SYNTHETIC))
        assertNull(ReportTextsEs.banner(DataOrigin.DECLARED_REAL))
        assertEquals("ORIGEN DE LOS DATOS NO DETERMINADO", ReportTextsEs.banner(DataOrigin.UNDETERMINED))
        // Real declarado con indicios sintéticos: no hay banda, pero sí aviso de conflicto
        val (ctx, a) = caseOf(origin = DataOrigin.DECLARED_REAL, names = listOf("SYNTHETIC_icdetector_history.csv"))
        val doc = ReportBuilder(ReportTextsEs).build(ctx, a)
        assertNull(doc.banner)
        assertTrue(doc.blocks.filterIsInstance<Block.Callout>().any { it.title == "Origen en conflicto" })
        assertTrue(allText(doc.blocks).contains("no implica autenticidad verificada"))
    }

    @Test fun pdfIsWellFormedAndBannerIsOnEveryPage() {
        val (ctx, a) = caseOf()
        val out = ByteArrayOutputStream()
        val pages = ReportBuilder(ReportTextsEs).writePdf(ctx, a, out)
        val bytes = out.toByteArray()
        assertTrue(String(bytes, 0, 8, Charsets.ISO_8859_1).startsWith("%PDF-1.4"))
        assertTrue(String(bytes, bytes.size - 6, 6, Charsets.ISO_8859_1).contains("%%EOF"))
        assertTrue(pages >= 5)
        val streams = inflateStreams(bytes)
        assertEquals(pages, streams.size)
        val banner = String(WinAnsi.encode(ReportTextsEs.banner(DataOrigin.SYNTHETIC)!!), Charsets.ISO_8859_1)
        assertTrue(streams.all { it.contains(banner) })
        assertTrue(streams.last().contains("Página $pages de $pages"))
    }

    @Test fun textWrappingNeverExceedsWidth() {
        val long = "Comprobación realizada: SHA-256 " + "a".repeat(200) + " de los bytes del archivo en el momento de importarlo."
        for (l in ReportRenderer.wrap(long, PdfFont.REGULAR, 9f, 200f)) assertTrue(l, PdfFont.REGULAR.width(l, 9f) <= 200f + 0.01f)
    }

    @Test fun winAnsiCoversSpanishAndSubstitutesTheRest() {
        assertEquals("áéíóúñÑüÜ¿¡«»ºª€", WinAnsi.normalize("áéíóúñÑüÜ¿¡«»ºª€"))
        assertEquals("a -> b >= c", WinAnsi.normalize("a → b ≥ c"))
    }

    private fun allText(blocks: List<Block>): String = blocks.joinToString("\n") { b ->
        when (b) {
            is Block.Paragraph -> b.text
            is Block.Bullets -> b.items.joinToString("\n")
            is Block.Labeled -> b.label + "\n" + b.items.joinToString("\n")
            is Block.Heading -> b.text
            is Block.Callout -> b.title + "\n" + b.text
            is Block.Table -> (b.caption ?: "") + "\n" + b.rows.joinToString("\n") { it.joinToString(" | ") }
            is Block.KeyValues -> b.rows.joinToString("\n") { "${it.first}: ${it.second}" }
            is Block.Cover -> b.title + b.fields.joinToString { it.second } + (b.notice ?: "")
            else -> ""
        }
    }

    private fun inflateStreams(pdf: ByteArray): List<String> {
        val s = String(pdf, Charsets.ISO_8859_1)
        val out = ArrayList<String>()
        var i = 0
        while (true) {
            val start = s.indexOf("stream\n", i).takeIf { it >= 0 } ?: break
            val end = s.indexOf("\nendstream", start)
            val data = pdf.copyOfRange(start + 7, end)
            val inf = Inflater(); inf.setInput(data)
            val buf = ByteArray(1 shl 20); val n = inf.inflate(buf); inf.end()
            out += String(buf, 0, n, Charsets.ISO_8859_1)
            i = end + 10
        }
        return out
    }
}

class PrivacyRoundingTest {
    @org.junit.Test fun noNegativeZero() {
        org.junit.Assert.assertEquals("0.001, 0.000", com.alexisgordr.cellcase.core.report.Privacy.coordinate(5.0e-4, -2.5e-4, com.alexisgordr.cellcase.core.report.CoordinateMode.ROUND_3))
        org.junit.Assert.assertEquals("-0.01", com.alexisgordr.cellcase.core.report.Privacy.round(-0.012, 2))
    }
}
