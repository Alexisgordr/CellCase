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

    /**
     * Revisión 22/10 (B1): un solo episodio que cruza la medianoche no es «recurrente entre días». Dos señales de cambio
     * de banda visibles en las mediciones a las 23:57 y a las 00:05 (8 min, un episodio) daban `recurrenceDays = 2`,
     * criterio RECURRENT sin ningún episodio de otro día que citar, y la prioridad subía de contexto a revisar.
     */
    @Test fun oneEpisodeAcrossMidnightIsNotRecurrent() {
        fun at(d1: String, d2: String, h1: String, h2: String) = build(rows(
            TestSupport.row("$d1 ${h1.dropLast(2)}50:00"),
            TestSupport.row("$d1 $h1:00", mapOf("FailedHeuristics" to band, "Bands" to "20", "ARFCN" to "6300")),
            TestSupport.row("$d2 $h2:00", mapOf("FailedHeuristics" to band, "Bands" to "20", "ARFCN" to "6300"))))
        val night = at("2026-07-01", "2026-07-02", "23:57", "00:05")
        val evening = at("2026-07-01", "2026-07-01", "20:57", "21:05")
        for (s in listOf(night, evening)) {
            assertEquals(1, s.episodes.size)
            val e = s.episodes.single()
            assertEquals(1, e.recurrenceDays)
            assertTrue(e.recurrenceEpisodes.isEmpty())
            assertTrue(e.criteria.none { it.code == com.alexisgordr.cellcase.core.analysis.CriterionCode.RECURRENT })
        }
        assertEquals(evening.episodes.single().priority, night.episodes.single().priority)
        // Con otro episodio de la misma celda y patrón otro día, sí es recurrente y lo cita
        val two = build(rows(
            TestSupport.row("2026-07-01 23:50:00"),
            TestSupport.row("2026-07-01 23:57:00", mapOf("FailedHeuristics" to band, "Bands" to "20", "ARFCN" to "6300")),
            TestSupport.row("2026-07-02 00:05:00", mapOf("FailedHeuristics" to band, "Bands" to "20", "ARFCN" to "6300")),
            TestSupport.row("2026-07-02 18:00:00", mapOf("FailedHeuristics" to band))))
        assertEquals(2, two.episodes.size)
        for (e in two.episodes) {
            assertEquals(2, e.recurrenceDays)
            assertEquals(two.episodes.map { it.number }.filter { it != e.number }, e.recurrenceEpisodes)
        }
    }

    /**
     * Revisión 22/10 (B2): con la zona sin confirmar, un historial de 3.0 se sitúa por `ObservedAtUtc` y el expediente se
     * presenta en UTC. La recurrencia contaba la fecha local del teléfono y los «otros días» la fecha UTC: dos episodios
     * a las 23:30 y a las 00:30 de Madrid salían recurrentes (dos fechas locales) sin citarse (mismo día UTC).
     */
    @Test fun recurrenceAndCitedEpisodesUseTheSameDayWithoutConfirmedZone() {
        val v3 = ",ObservedAtUtc,NotEvaluatedHeuristics,GpsAccuracyM,AppVersion,ExportDevice,ExportAndroid,LocationMode,ProfileMode,ProfileLevel," +
            "ProfileScore,ProfileCoverage,ProfileAbstainReason,ProfileAlgorithm,ProfileRevision,ProfileEvidence,ProfileLearned,ProfileHoldout,RowId,ObservationId"
        fun r(local: String, utc: String, id: Int) = TestSupport.row(local, mapOf("FailedHeuristics" to band)) +
            ",$utc,NONE,,3.0.0+f5173a8,,,,,,,,,,,,,,$id,obs-$id"
        val csv = TestSupport.HEADER + v3 + "\n" + r("2026-09-26 23:30:00", "2026-09-26T21:30:00.000Z", 1) + "\n" + r("2026-09-27 00:30:00", "2026-09-26T22:30:00.000Z", 2) + "\n"
        val unconfirmed = com.alexisgordr.cellcase.core.time.DeclaredZone(java.time.ZoneId.of("Europe/Madrid"), confirmed = false)
        val obs = (ImportPipeline.process(csv.toByteArray(), "h.csv", "h", unconfirmed).data as ImportedData.History).data.observations
        assertTrue(obs.all { it.time.instantOrNull != null })
        val s = EpisodeEngine.build(obs, emptyList(), emptyList(), null, null, null, java.time.ZoneOffset.UTC)
        assertEquals(2, s.episodes.size)
        for (e in s.episodes) {
            // Recurrente si y solo si cita otro episodio de otro día; aquí los dos son del 26 en UTC
            assertEquals(e.recurrenceDays >= 2, e.recurrenceEpisodes.isNotEmpty())
            assertEquals(1, e.recurrenceDays)
        }
        assertTrue(s.observationGroups.all { it.days == listOf(java.time.LocalDate.parse("2026-09-26")) })
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
        // Huella del resultado (la optimización del 20/10/2026 no la cambió: 38 s con el código anterior).
        // episodes/7 (22/10/2026, B1/B2): este caso importa con zona de Madrid y construye los episodios en UTC. Antes la
        // recurrencia contaba fechas de Madrid (14 días) y los episodios citados fechas UTC; ahora todo va en UTC y 924
        // episodios pasan de 14 a 15 días (30/06 + 1–14/07). Ninguna prioridad, relevancia ni cita cambia. Antes:
        // c968a37b2a3dd9bfcda815e13ff745234715dbb7cd804cedbfa59ee7f62a6a97.
        assertEquals("c545dfc031772dc738cd2ca6cd84856043faf3c1019ab6c643441c84488f3bec",
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

    /**
     * Un caso sin identidad solo se enlaza por R3 (se solapa con la señal a ±2 min) y entonces limita la calidad.
     * Revisión 22/10 (B6, episodes/7): antes bastaba con caer en la ventana de contexto (±30 min), así que un caso a
     * 25 min formaba su propio episodio Y además figuraba en este, le bajaba la calidad y le quitaba NO_FORENSIC.
     */
    @Test fun forensicCaseWithoutIdentityIsLinkedOnlyByR3AndThenLimitsQuality() {
        val noId = forensicCase { it.replace("\"cellIdentity\": \"001-01-1099-9999001-LTE\"", "\"cellIdentity\": null") }
        assertEquals(null, noId.second.meta.cellIdentity?.joinKey)
        // A 25 min del final del caso: no se enlaza; el caso es otro episodio
        val far = build(cellA, listOf(noId))
        val e = far.episodes.first { it.signals.any { s -> s.row != null } }
        assertTrue(e.forensicCaseIds.isEmpty())
        assertEquals(Quality.GOOD, e.quality)
        assertTrue(e.notEvaluable.any { it.code == CheckCode.NO_FORENSIC })
        assertTrue(far.episodes.any { it.forensicCaseIds == listOf("f") && it.signals.none { s -> s.row != null } })
        // A 64 s del final del caso: R3 lo enlaza, el caso es de este episodio y la calidad queda limitada
        val near = build(rows(TestSupport.row("2026-09-27 21:18:30", mapOf("CID" to "5555555", "FailedHeuristics" to band.removePrefix("[sub-umbral] ")))), listOf(noId))
        val n = near.episodes.single()
        assertEquals(listOf("f"), n.forensicCaseIds)
        assertEquals(Quality.LIMITED, n.quality)
        // sin caso, la misma alarma tiene calidad buena
        assertEquals(Quality.GOOD, build(cellA).episodes.single().quality)
    }
}
