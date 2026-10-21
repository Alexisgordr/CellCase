package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CHURN_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.ChurnAnalyzer
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.importing.HistoryColumns
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.report.reportTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Cambio de celdas de H17 (contexto en sombra, punto 6) y caso forense de ICdetector 3.1.0 (punto 7). */
class Icd31ChurnTest {
    private val csv31 = TestSupport.sample("icd31/SYNTHETIC_icd31_history.csv")
    private fun import(bytes: ByteArray, name: String = "h.csv"): ImportResult = ImportPipeline.process(bytes, name, name, TestSupport.MADRID)
    private fun analyze(vararg r: ImportResult) = CaseAnalyzer.analyze(r.toList(), TestSupport.MADRID, null)
    private val a31 by lazy { analyze(import(csv31.readBytes(), csv31.name)) }

    private fun doc(lang: Language, a: CaseAnalysis, mode: ReportMode = ReportMode.FULL) =
        ReportBuilder(reportTexts(lang)).build(TextContext(CaseInfo("x", "H17", Instant.EPOCH, "Sintético", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null), a.results,
            ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = mode)), a)
    private fun text(lang: Language, a: CaseAnalysis, mode: ReportMode = ReportMode.FULL) = doc(lang, a, mode).blocks.joinToString("\n") { b ->
        when (b) { is Block.Heading -> b.text; is Block.Paragraph -> b.text; is Block.KeyValues -> b.rows.joinToString { "${it.first}: ${it.second}" }
            is Block.Labeled -> b.label + ": " + b.items.joinToString(); is Block.Bullets -> b.items.joinToString("\n"); is Block.Callout -> b.title + ": " + b.text
            is Block.Table -> (b.caption ?: "") + "\n" + b.rows.joinToString("\n") { it.joinToString(" | ") }; else -> "" } }
    private fun m(lang: Language, k: String, vararg args: Any) = Messages.format(lang, k, args.toList())

    @Test fun percentilesAreNearestRank() {
        assertEquals(1, ChurnAnalyzer.percentile(listOf(1), 0.9))
        assertEquals(3, ChurnAnalyzer.percentile(listOf(1, 2, 3, 4, 5), 0.5))
        assertEquals(9, ChurnAnalyzer.percentile((1..10).toList(), 0.9))
        assertEquals(10, ChurnAnalyzer.percentile((1..10).toList(), 1.0))
    }

    @Test fun summaryDescribesEveryRowWithChurnByMotion() {
        val rows = a31.history!!.import.observations
        val c = a31.churn!!
        val with = rows.filter { it.churnCells15m != null }
        assertEquals(with.size, c.rowsWithChurn)
        assertEquals(c.rowsWithChurn, c.byMotion.sumOf { it.rows })
        assertEquals(ChurnAnalyzer.MOTIONS.filter { m -> with.any { it.churnMotion == m } }, c.byMotion.map { it.motion })
        for (s in c.byMotion) {
            val l = with.filter { it.churnMotion == s.motion }
            assertEquals(l.maxOf { it.churnChanges15m!! }, s.changesMax); assertEquals(l.maxOf { it.churnCells15m!! }, s.cellsMax)
            assertTrue(s.cellsMedian <= s.cellsP90 && s.cellsP90 <= s.cellsMax && s.changesMedian <= s.changesP90 && s.changesP90 <= s.changesMax)
        }
        // La franja del trayecto del 28/10 (muchos cambios, en movimiento) es la primera
        val top = c.bands.first()
        assertTrue(top.peakChanges >= c.changesP90 && top.rows >= 3 && "MOVING" in top.motions)
        assertTrue(Duration.between(top.start, top.end) <= Duration.ofHours(1))
        assertTrue(c.bands.size <= ChurnAnalyzer.MAX_BANDS)
        assertTrue(c.bands.zipWithNext().all { (a, b) -> a.peakChanges >= b.peakChanges })
        val peakRow = rows.single { it.provenance == top.peak }
        assertEquals(top.peakChanges, peakRow.churnChanges15m)
    }

    @Test fun historiesBefore31HaveNoChurnSection() {
        val a30 = analyze(import(TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv").readBytes()))
        assertNull(a30.churn)
        assertFalse(text(Language.ES, a30).contains(m(Language.ES, "churn.title")))
    }

    @Test fun technicalReportHasTheSectionInBothLanguagesAndThePlainOneDoesNot() {
        for (lang in Language.values()) {
            val t = text(lang, a31)
            for (k in listOf("churn.title", "rule.H17.what", "rule.H17.meaning", "churn.limits", "rule.H12.retired")) assertTrue("$lang $k", t.contains(m(lang, k)))
            assertTrue(t.contains(m(lang, "churn.caption_stats", CHURN_RULES_VERSION)))
            assertTrue(t.contains(m(lang, "churn.caption_bands", a31.churn!!.changesP90)))
            assertTrue(t.contains(m(lang, "report.methodology.churn_version", CHURN_RULES_VERSION)))
            val plain = text(lang, a31, ReportMode.EXPLAINED)
            assertFalse(plain.contains(m(lang, "churn.title"))); assertFalse(plain.contains("ChurnCells15m"))
        }
        assertEquals("Cambio de celdas (H17, modo sombra, sin calibrar)", m(Language.ES, "churn.title"))
        assertEquals("Cell churn (H17, shadow mode, not calibrated)", m(Language.EN, "churn.title"))
    }

    @Test fun churnNeverChangesEpisodesPrioritiesRelevanceOrAssessment() {
        // El mismo historial sin las columnas del churn (vacías)
        val lines = csv31.readLines(); val head = lines[0].split(",")
        val idx = HistoryColumns.V31.map { head.indexOf(it) }.toSet()
        val blanked = lines.filter { '"' !in it }.mapIndexed { i, l -> if (i == 0) l else l.split(",").mapIndexed { j, v -> if (j in idx) "" else v }.joinToString(",") }.joinToString("\n") + "\n"
        val kept = lines.filter { '"' !in it }.joinToString("\n") + "\n"
        val with = analyze(import(kept.toByteArray())); val without = analyze(import(blanked.toByteArray()))
        assertTrue(with.churn != null); assertNull(without.churn)
        fun facts(a: CaseAnalysis) = a.episodes.episodes.map { listOf(it.number, it.start, it.end, it.mainPattern, it.priority, it.relevance, it.criteria, it.supports, it.contradicts) }
        assertEquals(facts(without), facts(with))
        assertEquals(without.assessment, with.assessment)
        assertEquals(without.cross?.findings?.size, with.cross?.findings?.size)
        assertEquals("episodes/6", com.alexisgordr.cellcase.core.analysis.EPISODE_RULES_VERSION)
    }

    // ── 7. Caso forense de ICdetector 3.1.0 (sin latencia) ──

    @Test fun forensicCaseFrom31WithoutLatencyIsImportedTheSame() {
        val zip = TestSupport.sample("icd31/SYNTHETIC_icd31_ICD-2026-10-28-0004.zip")
        val r = import(zip.readBytes(), zip.name)
        assertEquals(ImportState.IMPORTED, r.state)
        val d = (r.data as ImportedData.Forensic).data
        assertEquals(4, d.samples.size)
        assertTrue(d.samples.all { it.latencyState.isNullOrEmpty() })
        assertTrue(d.samples.all { it.verified == "PENDING" })
        assertTrue(r.issues.none { it.column?.contains("latency") == true })
        assertEquals("3.1.0+90627b7", d.meta.appVersion)
        val a = analyze(r)
        for (lang in Language.values()) {
            val t = text(lang, a).lowercase()
            assertFalse("$lang", t.contains("latenc"))
        }
    }
}
