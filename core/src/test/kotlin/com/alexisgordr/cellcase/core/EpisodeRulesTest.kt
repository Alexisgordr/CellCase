package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.CheckCode
import com.alexisgordr.cellcase.core.analysis.CriterionCode
import com.alexisgordr.cellcase.core.analysis.Level
import com.alexisgordr.cellcase.core.analysis.LteBands
import com.alexisgordr.cellcase.core.analysis.MeasureStatus
import com.alexisgordr.cellcase.core.analysis.PatternKind
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.ReasonParser
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reglas de episodes/2 (docs/episode-rules.md). Datos en memoria, con PLMN de pruebas 001-01. */
class EpisodeRulesTest {
    private val SUB_B3_B20 = "[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)"

    /** Fila LTE de la celda [cid] con EARFCN [earfcn]. */
    private fun r(ts: String, cid: String, earfcn: Int, reason: String = "OK", extra: Map<String, String> = emptyMap()) =
        row(ts, mapOf("CID" to cid, "ARFCN" to earfcn.toString(), "Bands" to "", "FailedHeuristics" to reason, "TAUnit" to "STUB_ZERO", "TA" to "0", "TAMeters" to "") + extra)

    private fun analyze(vararg rows: String, radio: String? = null): CaseAnalysis {
        val res = mutableListOf(ImportPipeline.process(csv(*rows).toByteArray(), "h.csv", "h", TestSupport.MADRID))
        if (radio != null) res += ImportPipeline.process(radio.toByteArray(), "radio.csv", "r", TestSupport.MADRID)
        return CaseAnalyzer.analyze(res, TestSupport.MADRID, null)
    }

    private val RADIO_HEADER = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source\n"
    private fun radio(vararg ev: Pair<String, String>) = RADIO_HEADER + ev.joinToString("\n") { (t, s) -> "$t,$s,1,1,0,0,00101,Pruebas,00101,0,1300,20000,LTE,LTE,CALLBACK" } + "\n"

    @Test fun reasonsAreParsedWithBandsAndArrowVariants() {
        val r1 = ReasonParser.parse(SUB_B3_B20).single()
        assertEquals(PatternKind.BAND_CHANGE, r1.kind); assertEquals(3, r1.bandFrom); assertEquals(20, r1.bandTo); assertEquals(791, r1.freqToMhz)
        assertEquals(8, ReasonParser.parse("Downgrade de banda forzado (2620MHz->925MHz, B7->B8)").single().bandTo)
        assertEquals(listOf(PatternKind.TAC_DEVIATION, PatternKind.POWER_DEVIATION), ReasonParser.parse("Desviación TAC | Salto potencia (>35dB)").map { it.kind })
        assertEquals(19, ReasonParser.parse("[sub-umbral] Potencia anómala vs historial (+19dB)").single().db)
        assertEquals(20, LteBands.bandOfEarfcn(6300)); assertEquals(3, LteBands.bandOfEarfcn(1300)); assertEquals(8, LteBands.bandOfEarfcn(3600))
    }

    @Test fun singleObservationHasUnknownDurationAndIsContext() {
        val a = analyze(r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:05:00", "5002", 6300, SUB_B3_B20), r("2026-09-25 10:10:00", "5002", 6300))
        val e = a.episodes.episodes.single()
        assertFalse(e.durationKnown); assertEquals(1, e.moments); assertEquals(Level.LOW, e.persistence)
        assertEquals(MeasureStatus.CONSISTENT, e.measurement.status)   // B3→B20 visible en la fila anterior
        assertEquals(Priority.CONTEXT, e.priority)                      // sub-umbral, una vez, sin recurrencia
        assertTrue(e.criteria.any { it.code == CriterionCode.SINGLE_OBSERVATION })
    }

    @Test fun recurrenceAcrossDaysIsNotPersistence() {
        val a = analyze(
            r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:05:00", "5002", 6300, SUB_B3_B20),
            r("2026-09-29 18:00:00", "5001", 1300), r("2026-09-29 18:05:00", "5002", 6300, SUB_B3_B20))
        val eps = a.episodes.episodes
        assertEquals(2, eps.size)                                        // días distintos: episodios distintos
        for (e in eps) {
            assertEquals(2, e.recurrenceDays); assertEquals(Level.LOW, e.persistence); assertFalse(e.durationKnown)
            assertEquals(Priority.REVIEW, e.priority)                    // visible en mediciones + recurrente
        }
        assertEquals(listOf(2), eps[0].recurrenceEpisodes); assertEquals(listOf(1), eps[1].recurrenceEpisodes)
    }

    @Test fun overlappingEpisodesOfDifferentCellsAreGroupedButNotMerged() {
        val a = analyze(
            r("2026-09-29 13:53:00", "6001", 1300), r("2026-09-29 13:53:44", "6001", 1300, "[sub-umbral] Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)", mapOf("PCI" to "12")),
            r("2026-09-29 13:55:00", "6001", 1300, extra = mapOf("PCI" to "13")),
            r("2026-09-29 14:00:00", "6002", 3100), r("2026-09-29 14:00:42", "6003", 6300, SUB_B3_B20))
        val eps = a.episodes.episodes
        assertEquals(2, eps.size)
        assertEquals(setOf(PatternKind.RF_IDENTITY, PatternKind.BAND_CHANGE), eps.map { it.mainPattern }.toSet())
        assertEquals(1, eps.map { it.group }.distinct().size)            // mismo grupo
        assertEquals(1, a.episodes.groups.single().number)
        val rf = eps.first { it.mainPattern == PatternKind.RF_IDENTITY }
        assertEquals(MeasureStatus.PARTIAL, rf.measurement.status)      // PCI 12→13: cambio único, no alternancia
        assertTrue(rf.identities.none { it.key.contains("6003") })
    }

    @Test fun verificationStatusNeverChangesPriority() {
        fun prio(verified: String) = analyze(
            r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:05:00", "5002", 6300, SUB_B3_B20, mapOf("Verified" to verified)),
            r("2026-09-29 18:00:00", "5001", 1300), r("2026-09-29 18:05:00", "5002", 6300, SUB_B3_B20, mapOf("Verified" to verified))
        ).episodes.episodes.map { it.priority }
        assertEquals(prio("VERIFIED"), prio("NOT_FOUND"))
        assertEquals(prio("VERIFIED"), prio("PENDING"))
        val e = analyze(r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:05:00", "5002", 6300, SUB_B3_B20, mapOf("Verified" to "NOT_FOUND"))).episodes.episodes.single()
        assertTrue(e.context.any { it.code == CheckCode.VERIFICATION_STATUS })
        assertTrue(e.supports.none { it.code == CheckCode.VERIFICATION_STATUS })
    }

    @Test fun repeatedRepresentationsOfOneEventDoNotMakePersistence() {
        // tres filas del colector en el mismo minuto: un solo momento
        val a = analyze(r("2026-09-25 09:59:00", "5001", 1300),
            r("2026-09-25 10:00:00", "5002", 6300, SUB_B3_B20), r("2026-09-25 10:00:20", "5002", 6300, SUB_B3_B20), r("2026-09-25 10:00:40", "5002", 6300, SUB_B3_B20))
        val e = a.episodes.episodes.single()
        assertEquals(1, e.moments); assertEquals(Level.LOW, e.persistence); assertFalse(e.durationKnown)
    }

    @Test fun momentsAreAnchoredNotChained() {
        val a = analyze(r("2026-09-25 09:59:00", "5001", 1300),
            r("2026-09-25 10:00:00", "5002", 6300, SUB_B3_B20), r("2026-09-25 10:00:50", "5002", 6300, SUB_B3_B20), r("2026-09-25 10:01:40", "5002", 6300, SUB_B3_B20))
        assertEquals(2, a.episodes.episodes.single().moments)
    }

    @Test fun dependentSourcesAndRadioDoNotInflateCorroboration() {
        val rows = arrayOf(r("2026-09-25 09:55:00", "5001", 1300), r("2026-09-25 10:00:00", "5002", 6300, SUB_B3_B20))
        val noRadio = analyze(*rows).episodes.episodes.single()
        assertTrue(noRadio.criteria.none { it.code == CriterionCode.INDEPENDENT_CORROBORATION })
        assertTrue(noRadio.notEvaluable.any { it.code == CheckCode.NO_RADIO_DATA })
        // Radio con datos pero sin degradación: no se afirma continuidad, solo que no se registraron eventos degradados
        val calm = analyze(*rows, radio = radio("2026-09-25T07:00:00Z" to "IN_SERVICE")).episodes.episodes.single()
        assertTrue(calm.contradicts.any { it.code == CheckCode.RADIO_NO_DEGRADED_RECORDED })
        // Radio degradado a ≤ 5 min: es contexto adicional (episodes/4), no evidencia independiente ni eleva la prioridad
        val degraded = analyze(*rows, radio = radio("2026-09-25T07:00:00Z" to "IN_SERVICE", "2026-09-25T08:02:00Z" to "OUT_OF_SERVICE", "2026-09-25T08:03:00Z" to "IN_SERVICE")).episodes.episodes.single()
        assertTrue(degraded.criteria.any { it.code == CriterionCode.RADIO_COINCIDENCE_CONTEXT })
        assertTrue(degraded.criteria.none { it.code == CriterionCode.INDEPENDENT_CORROBORATION })
        assertEquals(Priority.CONTEXT, degraded.priority)
        assertEquals(com.alexisgordr.cellcase.core.analysis.SpoofingRelevance.NO_INDICATION, degraded.relevance.relevance)
    }

    @Test fun alarmWithMeasuredRecurrenceCanBePriorityButSubthresholdCannot() {
        val alarm = "Downgrade de banda forzado (1805MHz→791MHz, B3→B20)"
        val a = analyze(r("2026-09-25 09:55:00", "5001", 1300), r("2026-09-25 10:00:00", "5002", 6300, alarm),
            r("2026-09-26 09:55:00", "5001", 1300), r("2026-09-26 10:00:00", "5002", 6300, alarm))
        assertTrue(a.episodes.episodes.all { it.priority == Priority.PRIORITY })
        val s = analyze(r("2026-09-25 09:55:00", "5001", 1300), r("2026-09-25 10:00:00", "5002", 6300, SUB_B3_B20),
            r("2026-09-26 09:55:00", "5001", 1300), r("2026-09-26 10:00:00", "5002", 6300, SUB_B3_B20))
        assertTrue(s.episodes.episodes.none { it.priority == Priority.PRIORITY })
    }

    @Test fun technologyChangeIsDistinguishedFromBandChange() {
        val gsm = row("2026-09-25 09:58:00", mapOf("Radio" to "GSM", "NetType" to "2G", "CID" to "777", "PCI" to "", "ARFCN" to "60", "TA" to "", "TAUnit" to "", "TAMeters" to ""))
        val e = analyze(gsm, r("2026-09-25 10:00:00", "5002", 6300, SUB_B3_B20)).episodes.episodes.single()
        assertTrue(e.context.any { it.code == CheckCode.TECH_CHANGE_OBSERVED })
        assertEquals("Cambio de tecnología", com.alexisgordr.cellcase.core.report.ReportTextsEs.patternLabel(e.mainPattern, e))
    }

    @Test fun powerDeviationUsesTheSameCellBaseline() {
        val base = (0 until 8).map { r("2026-09-24 0$it:00:00", "5009", 1300, extra = mapOf("DBM" to "-100")) }
        val e = analyze(*(base + r("2026-09-27 16:48:53", "5009", 1300, "[sub-umbral] Potencia anómala vs historial (+19dB)", mapOf("DBM" to "-81"))).toTypedArray()).episodes.episodes.single()
        assertEquals(PatternKind.POWER_DEVIATION, e.mainPattern)
        assertEquals(MeasureStatus.CONSISTENT, e.measurement.status)
        assertEquals(Priority.CONTEXT, e.priority)                       // una sola vez y sin recurrencia
    }
}
