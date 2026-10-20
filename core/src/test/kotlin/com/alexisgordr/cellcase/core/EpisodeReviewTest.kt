package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CheckCode
import com.alexisgordr.cellcase.core.analysis.EpisodeEngine
import com.alexisgordr.cellcase.core.analysis.EpisodeSet
import com.alexisgordr.cellcase.core.analysis.PatternKind
import com.alexisgordr.cellcase.core.analysis.Quality
import com.alexisgordr.cellcase.core.analysis.SpoofingRelevance
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.HistoryObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Motor de episodios y relevancia (revisión del 20/10/2026, fallos 7 a 10). */
class EpisodeReviewTest {
    private val zone = TestSupport.MADRID.zoneId

    private fun rows(vararg r: String): List<HistoryObservation> =
        (ImportPipeline.process(TestSupport.csv(*r).toByteArray(), "h.csv", "h", TestSupport.MADRID).data as ImportedData.History).data.observations

    private fun build(rows: List<HistoryObservation>, forensic: List<Pair<String, ForensicCaseData>> = emptyList()): EpisodeSet =
        EpisodeEngine.build(rows, emptyList(), forensic, null, null, null, zone)

    private val band = "[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)"

    // ── 7. Recurrencia entre días en la zona del expediente ──

    @Test fun recurrenceAcrossMidnightUsesTheCaseZone() {
        // Madrid en verano (UTC+2): 23:30 del día 1 y 00:30 del día 2 en hora local son el mismo día en UTC.
        val s = build(rows(
            TestSupport.row("2026-07-01 23:30:00", mapOf("FailedHeuristics" to band)),
            TestSupport.row("2026-07-02 00:30:00", mapOf("FailedHeuristics" to band))))
        assertEquals(2, s.episodes.size)
        val (a, b) = s.episodes
        assertEquals(2, a.recurrenceDays); assertEquals(2, b.recurrenceDays)
        assertEquals(listOf(b.number), a.recurrenceEpisodes)
        assertEquals(listOf(a.number), b.recurrenceEpisodes)
    }

    @Test fun sameLocalDayIsNotRecurrence() {
        val s = build(rows(
            TestSupport.row("2026-07-01 08:00:00", mapOf("FailedHeuristics" to band)),
            TestSupport.row("2026-07-01 20:00:00", mapOf("FailedHeuristics" to band))))
        assertTrue(s.episodes.all { it.recurrenceEpisodes.isEmpty() && it.recurrenceDays == 1 })
    }

    // ── 8. Rendimiento: 40.000 filas, mismos episodios que antes de optimizar ──

    @Test fun fortyThousandRowsInUnderTenSecondsWithTheSameEpisodes() {
        val csv = EpisodeScaleFixture.csv()
        val t0 = System.nanoTime()
        val s = EpisodeScaleFixture.episodes(csv)
        val seconds = (System.nanoTime() - t0) / 1e9
        assertTrue("tardó $seconds s", seconds < 10.0)
        assertEquals(5406, s.episodes.size)
        // Huella del resultado ANTES del cambio (38 s con el código anterior; guardada el 20/10/2026).
        assertEquals("c968a37b2a3dd9bfcda815e13ff745234715dbb7cd804cedbfa59ee7f62a6a97",
            Hashing.sha256Hex(EpisodeScaleFixture.fingerprint(s).toByteArray()))
    }

    // ── 9. Relevancia: cualquier patrón de identidad del episodio, no solo el principal ──

    @Test fun identityPatternThatIsNotMainStillNeedsMoreData() {
        // Tres señales de banda y una de banda + TAC: patrón principal BAND_CHANGE, con un patrón de identidad (TAC).
        val s = build(rows(
            TestSupport.row("2026-07-01 10:00:00", mapOf("FailedHeuristics" to band)),
            TestSupport.row("2026-07-01 10:05:00", mapOf("FailedHeuristics" to band)),
            TestSupport.row("2026-07-01 10:10:00", mapOf("FailedHeuristics" to band)),
            TestSupport.row("2026-07-01 10:12:00", mapOf("FailedHeuristics" to "$band | Desviación TAC"))))
        val e = s.episodes.single()
        assertEquals(PatternKind.BAND_CHANGE, e.mainPattern)
        assertTrue(PatternKind.TAC_DEVIATION in e.patterns)
        assertEquals(SpoofingRelevance.NEEDS_MORE_DATA, e.relevance.relevance)
        // El escenario de la revisión tal cual (banda | TAC tres veces y TAC sola): también NEEDS_MORE_DATA.
        val lit = build(rows(
            TestSupport.row("2026-07-01 10:00:00", mapOf("FailedHeuristics" to "$band | Desviación TAC")),
            TestSupport.row("2026-07-01 10:05:00", mapOf("FailedHeuristics" to "$band | Desviación TAC")),
            TestSupport.row("2026-07-01 10:10:00", mapOf("FailedHeuristics" to "$band | Desviación TAC")),
            TestSupport.row("2026-07-01 10:12:00", mapOf("FailedHeuristics" to "[sub-umbral] Desviación TAC"))))
        assertEquals(SpoofingRelevance.NEEDS_MORE_DATA, lit.episodes.single().relevance.relevance)
    }

    @Test fun bandOnlyEpisodeIsStillCommonBehaviour() {
        val e = build(rows(TestSupport.row("2026-07-01 10:00:00", mapOf("FailedHeuristics" to band)))).episodes.single()
        assertEquals(SpoofingRelevance.NO_INDICATION, e.relevance.relevance)
    }

    // ── 10. Casos forenses de otra celda ──

    private fun forensicCase(edit: (String) -> String = { it }): Pair<String, ForensicCaseData> {
        val zip = TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes()
        val bytes = if (edit === IDENTITY) zip else ReviewZip.withCaseJson(zip, edit)
        val r = ImportPipeline.process(bytes, "caso.zip", "f", TestSupport.MADRID)
        return "f" to (r.data as ImportedData.Forensic).data
    }
    private val IDENTITY: (String) -> String = { it }

    /** Celda A con una alarma 25 min después del final del caso forense (21:17:26 en Madrid), que es de la celda B. */
    private val cellA = rows(TestSupport.row("2026-09-27 21:42:30", mapOf("CID" to "5555555", "FailedHeuristics" to band.removePrefix("[sub-umbral] "))))

    @Test fun forensicCaseOfAnotherCellIsNotLinked() {
        val e = build(cellA, listOf(forensicCase(IDENTITY))).episodes.single { it.signals.any { s -> s.row != null } }
        assertTrue(e.forensicCaseIds.isEmpty())
        assertTrue(e.notEvaluable.any { it.code == CheckCode.NO_FORENSIC })
    }

    @Test fun forensicCaseOfTheSameCellIsLinked() {
        val same = rows(TestSupport.row("2026-09-27 21:42:30", mapOf("CID" to "9999001", "TAC" to "1099", "FailedHeuristics" to band.removePrefix("[sub-umbral] "))))
        val e = build(same, listOf(forensicCase(IDENTITY))).episodes.single { it.signals.any { s -> s.row != null } }
        assertEquals(listOf("f"), e.forensicCaseIds)
        assertFalse(e.notEvaluable.any { it.code == CheckCode.NO_FORENSIC })
    }

    @Test fun forensicCaseWithoutIdentityIsLinkedByWindowButLimitsQuality() {
        val noId = forensicCase { it.replace("\"cellIdentity\": \"001-01-1099-9999001-LTE\"", "\"cellIdentity\": null") }
        assertEquals(null, noId.second.meta.cellIdentity?.joinKey)
        val e = build(cellA, listOf(noId)).episodes.first { it.signals.any { s -> s.row != null } }
        assertEquals(listOf("f"), e.forensicCaseIds)
        assertEquals(Quality.LIMITED, e.quality)
        // sin caso, la misma alarma tiene calidad buena
        assertEquals(Quality.GOOD, build(cellA).episodes.single().quality)
    }
}
