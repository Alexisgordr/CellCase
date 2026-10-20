package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.EpisodeEngine
import com.alexisgordr.cellcase.core.analysis.ForensicVersions
import com.alexisgordr.cellcase.core.analysis.MeasureStatus
import com.alexisgordr.cellcase.core.analysis.MeasurementAvailability
import com.alexisgordr.cellcase.core.analysis.PciPattern
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.VersionRelation
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.model.ForensicCapability
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Correcciones de análisis del 2026-10-02 (segunda revisión): PCI con horas coincidentes (D-042) y versiones
 * forenses por contenido (D-043). Datos sintéticos (PLMN de pruebas 001-01).
 */
class AnalysisFixesTest {
    private val RF = "[sub-umbral] Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)"

    private fun r(ts: String, pci: Int, reason: String = "OK") =
        row(ts, mapOf("CID" to "6001", "TAC" to "1001", "ARFCN" to "1300", "PCI" to pci.toString(), "Bands" to "", "FailedHeuristics" to reason, "Radio" to "LTE", "NetType" to "4G"))

    private fun imp(name: String, id: String, vararg rows: String) = ImportPipeline.process(csv(*rows).toByteArray(), name, id, TestSupport.MADRID)

    private fun analyze(vararg files: Pair<String, Array<String>>, ids: List<String> = files.indices.map { "imp-$it" }): CaseAnalysis =
        CaseAnalyzer.analyze(files.mapIndexed { i, (n, rows) -> imp(n, ids[i], *rows) }, TestSupport.MADRID, null)

    private fun rf(a: CaseAnalysis) = a.episodes.episodes.single { it.mainPattern.name == "RF_IDENTITY" }

    @Suppress("UNCHECKED_CAST")
    private fun series(a: CaseAnalysis) = rf(a).measurement.values["series"] as List<EpisodeEngine.PciSeries>

    /** Procedencia sin identificadores internos: archivo, entrada y registro. */
    private fun p(x: Provenance) = Triple(x.fileName, x.zipEntry, x.recordIndex)

    /** Hechos comparables entre importaciones: sin importId. */
    private fun facts(a: CaseAnalysis): List<Any?> = a.episodes.episodes.map { e ->
        @Suppress("UNCHECKED_CAST")
        val ser = (e.measurement.values["series"] as? List<EpisodeEngine.PciSeries>).orEmpty().sortedBy { it.key + it.arfcn }
        listOf(e.start, e.end, e.mainPattern, e.priority, e.relevance.relevance, e.measurement.status, e.measurement.availability, e.moments,
            e.measurement.values["pattern"], e.measurement.values["conflicts"], e.measurement.values["conflictBlocks"],
            ser.map { s -> listOf(s.key, s.arfcn, s.pattern, s.coincident, s.blocked,
                s.runs.map { listOf(it.pci, it.from, it.to, it.rows, it.refs.map(::p)) },
                s.conflicts.map { listOf(it.time, it.pcis, it.refs.map(::p)) }) })
    }

    // ── PCI con horas coincidentes ──────────────────────────────────────

    @Test fun differentPciAtSameTimeIsATemporalConflictNotAChange() {
        val a = analyze("h.csv" to arrayOf(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 12, RF), r("2026-09-29 13:53:44", 13)))
        val e = rf(a); val s = series(a).single()
        assertEquals(1, s.conflicts.size)
        assertEquals(listOf(12, 13), s.conflicts.single().pcis)
        assertEquals(2, s.conflicts.single().refs.size)
        assertEquals(PciPattern.NO_CHANGE, s.pattern)                   // ni A→B ni alternancia
        assertTrue(s.blocked)
        assertEquals(MeasureStatus.NOT_EVALUABLE, e.measurement.status)
        assertEquals(MeasurementAvailability.CONFLICTING, e.measurement.availability)
        assertEquals(Priority.CONTEXT, e.priority)
    }

    @Test fun sameTimeSamePciIsOneCoincidentObservationWithAllProvenances() {
        val a = analyze("a.csv" to arrayOf(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 13, RF)),
            "b.csv" to arrayOf(r("2026-09-29 13:53:44", 13), r("2026-09-29 13:56:00", 13)))
        val s = series(a).single()
        assertTrue(s.conflicts.isEmpty())
        assertEquals(PciPattern.SINGLE_CHANGE, s.pattern)
        val second = s.runs[1]
        assertTrue("procedencias de los dos archivos", second.refs.map { it.fileName }.toSet().containsAll(listOf("a.csv", "b.csv")))
    }

    @Test fun conflictDoesNotBlockAReturnShownByUnambiguousMoments() {
        val a = analyze("h.csv" to arrayOf(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:51:00", 13, RF), r("2026-09-29 13:52:00", 12),
            r("2026-09-29 13:53:00", 12), r("2026-09-29 13:53:00", 13)))
        val e = rf(a); val s = series(a).single()
        assertEquals(PciPattern.RETURN, s.pattern); assertFalse(s.blocked); assertEquals(1, s.conflicts.size)
        assertEquals(MeasureStatus.CONSISTENT, e.measurement.status)
    }

    @Test fun reverseOrderAndOtherInternalIdsGiveTheSameFactsConflictsAndConclusions() {
        val a1 = arrayOf(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:53:44", 12, RF), r("2026-09-29 13:55:00", 13))
        val b1 = arrayOf(r("2026-09-29 13:53:44", 13), r("2026-09-29 13:55:00", 13), r("2026-09-29 13:57:00", 12))
        val x = analyze("a.csv" to a1, "b.csv" to b1, ids = listOf("x-1", "x-2"))
        val y = analyze("b.csv" to b1, "a.csv" to a1, ids = listOf("zz-9", "aa-0"))
        assertEquals(facts(x), facts(y))
        assertEquals(x.episodes.overall, y.episodes.overall)
        // las líneas dentro de cada archivo invertidas tampoco cambian nada
        val z = analyze("b.csv" to b1.reversedArray(), "a.csv" to a1.reversedArray(), ids = listOf("q", "p"))
        assertEquals(facts(x).map { (it as List<*>).take(11) }, facts(z).map { (it as List<*>).take(11) })
        assertTrue(series(x).single().conflicts.isNotEmpty())
    }

    // ── Versiones forenses ──────────────────────────────────────────────

    private fun sample(): ForensicCaseData =
        (ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes(), "caso.zip", "f1", TestSupport.MADRID).data as ImportedData.Forensic).data

    private fun v(id: String, d: ForensicCaseData) = ForensicVersions.Version(id, "$id.zip", Hashing.sha256Hex(id.toByteArray()), d)

    @Test fun capabilitiesAndTerminalContentAreKept() {
        val d = sample()
        assertTrue(d.capabilities.isNotEmpty() && d.capabilities.all { it.values.isNotEmpty() && it.time != null })
        assertTrue(d.terminal.first().startsWith("["))
    }

    @Test fun sameNumberOfTerminalLinesWithDifferentContentIsNotEquivalent() {
        val d = sample()
        val other = d.copy(terminal = d.terminal.dropLast(1) + "[21:17:26.000] [CICLO] línea distinta")
        assertEquals(d.terminalLines, other.terminalLines)
        val g = ForensicVersions.group(listOf(v("a", d), v("b", other))).single()
        assertNotEquals(VersionRelation.EQUIVALENT, g.relation)
        assertEquals(VersionRelation.DIVERGENT, g.relation)
        assertEquals(setOf("a", "b"), g.used.toSet())
    }

    @Test fun differentCapabilitiesWithSameCountAreContradictory() {
        val d = sample()
        val c0 = d.capabilities.first()
        val other = d.copy(capabilities = listOf(ForensicCapability(c0.timeRaw, c0.time, c0.values + ("hardwareCiphering" to "true"))) + d.capabilities.drop(1))
        assertEquals(d.capabilityObservations, other.capabilityObservations)
        val g = ForensicVersions.group(listOf(v("a", d), v("b", other))).single()
        assertEquals(VersionRelation.CONTRADICTORY, g.relation)
        assertTrue(g.conflicts.any { it.field == "capabilities" })
    }

    @Test fun incompatibleMetadataIsNotResolvedByHash() {
        val d = sample()
        // mismo contenido, pero una dice confirmado y cerrado como READY y la otra INTERRUPTED: no hay orden posible
        val other = d.copy(meta = d.meta.copy(state = "INTERRUPTED"))
        for (ids in listOf("a" to "b", "b" to "a", "zzz" to "000")) {
            val g = ForensicVersions.group(listOf(v(ids.first, d), v(ids.second, other))).single()
            assertEquals(VersionRelation.CONTRADICTORY, g.relation)
            assertEquals(2, g.used.size)
            assertTrue(g.conflicts.any { it.field == "state" })
        }
        // confirmado que vuelve a «no confirmado» con más muestras: incompatible
        val fewer = d.copy(samples = d.samples.dropLast(3), meta = d.meta.copy(state = "POST_CAPTURE", closedAt = null, sampleCount = d.samples.size - 3L))
        val regress = d.copy(meta = d.meta.copy(confirmed = false))
        val g2 = ForensicVersions.group(listOf(v("a", fewer.copy(meta = fewer.meta.copy(confirmed = true))), v("b", regress))).single()
        assertEquals(VersionRelation.CONTRADICTORY, g2.relation)
        assertTrue(g2.conflicts.any { it.field == "confirmed" })
    }

    @Test fun contentAddedAfterClosureIsIncompatible() {
        val d = sample()
        val closedEarly = d.copy(samples = d.samples.dropLast(4), meta = d.meta.copy(sampleCount = d.samples.size - 4L))
        val g = ForensicVersions.group(listOf(v("a", closedEarly), v("b", d))).single()
        assertEquals(VersionRelation.CONTRADICTORY, g.relation)
        assertTrue(g.conflicts.any { it.field == "contentAfterClosure" })
    }

    @Test fun validExtensionIsAcceptedWithExplicitProgression() {
        val d = sample()
        val early = d.copy(
            samples = d.samples.dropLast(6), capabilities = d.capabilities.dropLast(6), terminal = d.terminal.dropLast(3),
            meta = d.meta.copy(state = "CAPTURING", confirmed = false, highestPhase = 1, sampleCount = d.samples.size - 6L, closedAt = null,
                caseCode = d.meta.caseCode!!.replace("ICD-", "ICD-OBS-")))
        for (order in listOf(listOf(v("p", early), v("q", d)), listOf(v("q", d), v("p", early)))) {
            val g = ForensicVersions.group(order).single()
            assertEquals(VersionRelation.EXTENDED, g.relation)
            assertEquals(listOf("q"), g.used)
            assertTrue(g.conflicts.isEmpty())
        }
    }

    @Test fun internalSampleCountMismatchIsReported() {
        val d = sample()
        val bad = d.copy(meta = d.meta.copy(sampleCount = 999))
        val g = ForensicVersions.group(listOf(v("a", bad))).single()
        assertTrue(g.internal.getValue("a").any { it.field == "sampleCount" && it.a == "999" })
    }

    // ── Presupuesto de memoria del expediente (D-046) ───────────────────

    @Test fun caseBudgetRejectsTheFileThatDoesNotFitAndKeepsOrder() {
        val a = csv(r("2026-09-29 13:50:00", 12), r("2026-09-29 13:51:00", 12)).toByteArray()
        val b = csv(r("2026-09-29 14:50:00", 12)).toByteArray()
        val budget = com.alexisgordr.cellcase.core.importing.CaseBudget(maxHistoryRows = 2)
        val ra = ImportPipeline.process(a, "a.csv", "a", TestSupport.MADRID, budget = budget)
        val rb = ImportPipeline.process(b, "b.csv", "b", TestSupport.MADRID, budget = budget)
        assertEquals(com.alexisgordr.cellcase.core.importing.ImportState.IMPORTED, ra.state)
        assertEquals(com.alexisgordr.cellcase.core.importing.ImportState.TOO_LARGE, rb.state)
        assertTrue(rb.data == null)
        assertTrue(rb.message!!.contains("presupuesto"))
        assertTrue(com.alexisgordr.cellcase.core.report.ReportTextsEn.tr(rb.messageText!!).contains("budget"))
    }
}
