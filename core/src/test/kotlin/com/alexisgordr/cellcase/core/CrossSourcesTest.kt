package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CrossSummary
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.report.*
import java.time.Instant

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.CrossCheck
import com.alexisgordr.cellcase.core.analysis.CrossField
import com.alexisgordr.cellcase.core.analysis.CrossFinding
import com.alexisgordr.cellcase.core.analysis.CrossOutcome
import com.alexisgordr.cellcase.core.analysis.CrossReason
import com.alexisgordr.cellcase.core.analysis.Derivation
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.SpoofingRelevance
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.SelectedFile
import com.alexisgordr.cellcase.core.time.DeclaredZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Coincidencias y diferencias entre fuentes (`cross/1`, docs/cross-sources.md). Datos SINTÉTICOS (PLMN de pruebas 001-01).
 */
class CrossSourcesTest {

    // ── utilidades ───────────────────────────────────────────────────────

    private val RADIO_HEADER = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source"
    private fun ev(utc: String, state: String) = "$utc,$state,${if (state == "IN_SERVICE") 1 else 0},${if (state == "IN_SERVICE") 1 else 0},0,0,00101,Red de Pruebas,00101,0,1300,20000,LTE,LTE,CALLBACK"
    private fun radio(vararg lines: String) = (RADIO_HEADER + "\n" + lines.joinToString("\n") + "\n").toByteArray()

    private fun imp(bytes: ByteArray, name: String, id: String, zone: DeclaredZone? = TestSupport.MADRID): ImportResult =
        ImportPipeline.process(bytes, name, id, zone)

    private fun analyze(vararg r: ImportResult, zone: DeclaredZone? = TestSupport.MADRID): CaseAnalysis = CaseAnalyzer.analyze(r.toList(), zone, null)

    private fun CaseAnalysis.f(check: CrossCheck, outcome: CrossOutcome? = null, reason: CrossReason? = null): List<CrossFinding> =
        cross!!.findings.filter { it.check == check && (outcome == null || it.outcome == outcome) && (reason == null || it.reason == reason) }

    /** ZIP de muestra con algunas entradas sustituidas; recalcula SHA256SUMS si existe. */
    private fun zipWith(sample: String, replace: Map<String, String>): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(TestSupport.sample(sample).inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        replace.forEach { (k, v) -> entries[k] = v.toByteArray() }
        if ("SHA256SUMS.txt" in entries) entries["SHA256SUMS.txt"] = entries.filterKeys { it != "SHA256SUMS.txt" }
            .entries.joinToString("") { (k, v) -> Hashing.sha256Hex(v) + "  " + k + "\n" }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        return out.toByteArray()
    }
    private fun zipText(sample: String, entry: String): String {
        ZipInputStream(TestSupport.sample(sample).inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; if (e.name == entry) return String(z.readBytes()) } }
        error(entry)
    }

    private fun h(ts: String, cid: String = "1000001", state: String = "IN_SERVICE", over: Map<String, String> = emptyMap()) =
        row(ts, mapOf("CID" to cid, "ServiceState" to state) + over)

    // ── 1. datos coincidentes ───────────────────────────────────────────

    @Test fun matchingServiceStateAndAggregatesAreCoincidences() {
        val hist = imp(csv(h("2026-09-26 10:00:00"), h("2026-09-26 10:10:00"), h("2026-09-26 10:20:00")).toByteArray(), "h.csv", "imp-1")
        val rad = imp(radio(ev("2026-09-26T07:00:00Z", "IN_SERVICE"), ev("2026-09-26T09:00:00Z", "OUT_OF_SERVICE")), "r.csv", "imp-2")
        val a = analyze(hist, rad)
        val m = a.f(CrossCheck.SERVICE_STATE, CrossOutcome.MATCH).single()
        assertEquals(3, m.count)
        assertEquals(Derivation.SAME_PHONE_STATE, m.derivation)
        assertTrue(a.f(CrossCheck.SERVICE_STATE, CrossOutcome.DIFFERENCE).isEmpty())
        // Topología y Geometría de muestra: mismas rutas y contadores compatibles.
        val b = analyze(imp(TestSupport.sample("SYNTHETIC_ICD-topology.zip").readBytes(), "t.zip", "imp-3"), imp(TestSupport.sample("SYNTHETIC_ICD-geometry.zip").readBytes(), "g.zip", "imp-4"))
        assertTrue(b.f(CrossCheck.AGGREGATE_COUNTERS, CrossOutcome.MATCH).sumOf { it.count } >= 8)
        assertTrue(b.f(CrossCheck.AGGREGATE_COUNTERS, CrossOutcome.DIFFERENCE).isEmpty())
    }

    // ── 2. diferencias reales en datos comparables ──────────────────────

    @Test fun realDifferenceIsReportedButBoundaryIsNotComparable() {
        // Radio: OUT_OF_SERVICE a las 08:00 UTC (10:00 en Madrid) y nada más hasta las 12:00 UTC.
        val rad = imp(radio(ev("2026-09-26T07:00:00Z", "IN_SERVICE"), ev("2026-09-26T08:00:00Z", "OUT_OF_SERVICE"), ev("2026-09-26T12:00:00Z", "IN_SERVICE")), "r.csv", "imp-2")
        val hist = imp(csv(
            h("2026-09-26 10:00:30"),   // a 30 s del cambio: frontera de transición
            h("2026-09-26 10:20:00")    // 20 min después: diferencia comprobable
        ).toByteArray(), "h.csv", "imp-1")
        val a = analyze(hist, rad)
        val d = a.f(CrossCheck.SERVICE_STATE, CrossOutcome.DIFFERENCE).single()
        assertEquals(1, d.count)
        assertEquals(listOf("IN_SERVICE" to "OUT_OF_SERVICE"), d.compared.map { it.a to it.b })
        assertTrue("referencias a las dos fuentes", d.refs.any { it.fileName == "h.csv" } && d.refs.any { it.fileName == "r.csv" })
        assertEquals(1, a.f(CrossCheck.SERVICE_STATE, CrossOutcome.NOT_COMPARABLE, CrossReason.TRANSITION_BOUNDARY).single().count)
        // Una diferencia es contexto: no crea episodios ni cambia la valoración.
        assertTrue(a.episodes.episodes.isEmpty())
        assertEquals(SpoofingRelevance.NO_INDICATION, a.assessment.overall)
    }

    @Test fun forensicCaseAgainstHistoryFindsSameMeasurementAndRealDifference() {
        val a = sampleCase(GenerateSampleReport.ALL)
        val m = a.f(CrossCheck.FORENSIC_HISTORY, CrossOutcome.MATCH).single()
        assertEquals(Derivation.SAME_MEASUREMENT, m.derivation)
        // La fila de las 21:15:00 (19:15:00 UTC) dice C1; la muestra del caso a 0,4 s dice la celda nueva.
        val d = a.f(CrossCheck.FORENSIC_HISTORY, CrossOutcome.DIFFERENCE).single()
        assertEquals(CrossField.SERVING_CELL, d.compared.single().field)
        assertEquals("001-01-1099-9999001-LTE" to "001-01-1001-1000001-LTE", d.compared.single().let { it.a to it.b })
    }

    // ── 3. periodos sin solapamiento ────────────────────────────────────

    @Test fun periodsWithoutOverlapAreNotComparable() {
        val hist = imp(csv(h("2026-09-20 10:00:00"), h("2026-09-20 11:00:00")).toByteArray(), "h.csv", "imp-1")
        val rad = imp(radio(ev("2026-09-26T07:00:00Z", "OUT_OF_SERVICE"), ev("2026-09-26T08:00:00Z", "IN_SERVICE")), "r.csv", "imp-2")
        val a = analyze(hist, rad)
        val all = a.f(CrossCheck.SERVICE_STATE)
        assertEquals(1, all.size)
        assertEquals(CrossReason.NO_OVERLAP, all.single().reason)
        assertEquals(CrossOutcome.NOT_COMPARABLE, all.single().outcome)
        assertFalse(a.cross!!.anyComparable)
    }

    // ── 4. zona horaria ambigua o sin confirmar ─────────────────────────

    @Test fun ambiguousAndUnconfirmedTimesAreNeverCompared() {
        // 25/10/2026 02:30 existe dos veces en Madrid (fin del horario de verano).
        val rad = imp(radio(ev("2026-10-24T20:00:00Z", "OUT_OF_SERVICE"), ev("2026-10-25T06:00:00Z", "IN_SERVICE")), "r.csv", "imp-2")
        val hist = imp(csv(h("2026-10-25 02:30:00"), h("2026-10-25 04:00:00")).toByteArray(), "h.csv", "imp-1")
        val a = analyze(hist, rad)
        assertEquals(1, a.f(CrossCheck.SERVICE_STATE, CrossOutcome.NOT_COMPARABLE, CrossReason.AMBIGUOUS_TIME).single().count)
        assertEquals(1, a.f(CrossCheck.SERVICE_STATE, CrossOutcome.DIFFERENCE).single().count)
        // Sin zona confirmada no se compara ninguna hora local.
        val unconfirmed = DeclaredZone(ZoneId.of("Europe/Madrid"), confirmed = false)
        val b = analyze(imp(csv(h("2026-09-26 10:00:00")).toByteArray(), "h.csv", "imp-1", unconfirmed),
            imp(radio(ev("2026-09-26T07:00:00Z", "IN_SERVICE"), ev("2026-09-26T09:00:00Z", "IN_SERVICE")), "r.csv", "imp-2"), zone = unconfirmed)
        assertTrue(b.f(CrossCheck.SERVICE_STATE).all { it.outcome == CrossOutcome.NOT_COMPARABLE })
    }

    // ── 5. exportaciones incompletas ────────────────────────────────────

    @Test fun truncatedTopologyMakesAbsentRoutesNotComparable() {
        val routes = (0 until 250).joinToString("\n") { i -> "\"001-01-7000-${100 + i}-LTE\",\"001-01-7000-${101 + i}-LTE\",\"1\",\"1\",\"1.0000\",\"PASSED\",\"2026-09-28T06:00:00Z\"" }
        val topo = zipWith("SYNTHETIC_ICD-topology.zip", mapOf("transitions.csv" to
            "from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc\n$routes\n" +
            "\"001-01-1001-1000001-LTE\",\"001-01-1001-1000002-LTE\",\"1\",\"1\",\"1.0000\",\"PASSED\",\"2026-09-28T06:00:00Z\"\n"))
        // El historial cambia de 1000001 a 1000003 (ruta que no está en la exportación truncada).
        val hist = imp(csv(h("2026-09-26 10:00:00"), h("2026-09-26 10:05:00", cid = "1000003")).toByteArray(), "h.csv", "imp-1")
        val a = analyze(hist, imp(topo, "t.zip", "imp-2"))
        assertTrue(a.topology!!.primary.possibleTruncation)
        val r = a.f(CrossCheck.ROUTES).single()
        assertEquals(CrossOutcome.NOT_COMPARABLE, r.outcome)
        assertEquals(CrossReason.TRUNCATED_EXPORT, r.reason)
        assertTrue("una ausencia nunca es una diferencia", a.cross!!.findings.none { it.outcome == CrossOutcome.DIFFERENCE })
    }

    // ── 6. valores desconocidos ─────────────────────────────────────────

    @Test fun unknownIdentitiesAndSentinelsAreTreatedAsMissing() {
        val hist = imp(csv(
            h("2026-09-26 10:00:00", over = mapOf("Radio" to "", "NetType" to "")),       // tecnología desconocida
            h("2026-09-26 10:05:00", cid = "N/A"),                                          // CID centinela
            h("2026-09-26 10:10:00", state = "")                                            // estado de servicio vacío
        ).toByteArray(), "h.csv", "imp-1")
        val a = analyze(hist, imp(TestSupport.sample("SYNTHETIC_ICD-topology.zip").readBytes(), "t.zip", "imp-2"),
            imp(radio(ev("2026-09-26T07:00:00Z", "IN_SERVICE"), ev("2026-09-26T09:00:00Z", "IN_SERVICE")), "r.csv", "imp-3"))
        assertEquals(2, a.f(CrossCheck.CELLS, CrossOutcome.NOT_COMPARABLE, CrossReason.UNKNOWN_IDENTITY).single().compared.single().a!!.toInt())
        assertTrue("PCI u otros campos no sustituyen a la identidad", a.f(CrossCheck.CELLS, CrossOutcome.MATCH).none { m -> m.entities.any { "N/A" in it } })
        assertEquals(1, a.f(CrossCheck.SERVICE_STATE, CrossOutcome.NOT_COMPARABLE, CrossReason.MISSING_VALUE).single().count)
    }

    // ── 7. campos actualizados entre exportaciones ──────────────────────

    @Test fun countersUpdatedBetweenExportsAndMutableFields() {
        val topo = TestSupport.sample("SYNTHETIC_ICD-topology.zip").readBytes()
        // Geometría POSTERIOR con un contador mayor: actualizado entre exportaciones (coincidencia).
        val geoGrew = TestSupport.sample("SYNTHETIC_ICD-geometry.zip").readBytes()
        val a = analyze(imp(topo, "t.zip", "imp-1"), imp(geoGrew, "g.zip", "imp-2"))
        val grew = a.f(CrossCheck.AGGREGATE_COUNTERS, CrossOutcome.MATCH, CrossReason.COUNTER_GREW).single()
        assertEquals("41" to "42", grew.compared.first { it.field == CrossField.HANDOVERS }.let { it.a to it.b })
        // Geometría posterior con un contador MENOR: diferencia comprobable.
        val edges = zipText("SYNTHETIC_ICD-geometry.zip", "geometry_edges.csv").replace("\"42\",\"41\"", "\"30\",\"29\"")
        val b = analyze(imp(topo, "t.zip", "imp-1"), imp(zipWith("SYNTHETIC_ICD-geometry.zip", mapOf("geometry_edges.csv" to edges)), "g.zip", "imp-2"))
        assertEquals(CrossReason.COUNTER_DECREASED, b.f(CrossCheck.AGGREGATE_COUNTERS, CrossOutcome.DIFFERENCE).single().reason)
        // Verified del historial reescrito después de la captura: no comparable, nunca diferencia.
        val c = sampleCase(GenerateSampleReport.ALL)
        val mut = c.f(CrossCheck.FORENSIC_HISTORY, CrossOutcome.NOT_COMPARABLE, CrossReason.MUTABLE_FIELD).single()
        assertTrue(mut.compared.all { it.field == CrossField.VERIFIED })
        assertTrue(c.f(CrossCheck.FORENSIC_HISTORY, CrossOutcome.DIFFERENCE).flatMap { it.compared }.none { it.field == CrossField.VERIFIED })
    }

    // ── 8. claves superpuestas de Stable-Site ───────────────────────────

    @Test fun overlappingStableSiteKeysAreNotSummed() {
        val cells = "site_key,cell_identity,serving_observations,serving_distinct_days,neighbour_observations,neighbour_distinct_days,first_serving_ms,last_serving_ms,first_neighbour_ms,last_neighbour_ms\n" +
            listOf("a1", "a2", "a3", "a4").joinToString("") { k -> "${k}aaaaaaaaaaaaaaaaaa,001-01-1001-1000001-LTE,100,10,0,0,1785780000000,1790632500000,,\n" }
        val ss = zipWith("SYNTHETIC_ICD-stable-site.zip", mapOf("site_cells.csv" to cells))
        val hist = imp(csv(h("2026-09-26 10:00:00")).toByteArray(), "h.csv", "imp-1")
        val a = analyze(hist, imp(ss, "s.zip", "imp-2"))
        val m = a.f(CrossCheck.CELLS, CrossOutcome.MATCH).single { CrossCheck.CELLS == it.check }
        assertEquals("máximo por clave, no 40", "10", m.compared.single { it.field == CrossField.MAX_SERVING_DAYS }.b)
    }

    // ── 9. fuentes derivadas de la misma medición u observación ─────────

    @Test fun derivedSourcesAreNotIndependentConfirmation() {
        val a = sampleCase(GenerateSampleReport.PATTERNS)
        // El episodio de Stable-Site y la ruta de Topología son el mismo cambio de celda (misma hora al milisegundo).
        val same = a.f(CrossCheck.STABLE_SITE_EPISODE, CrossOutcome.MATCH, CrossReason.TRANSITION_IN_WINDOW)
        assertEquals(2, same.size)
        assertTrue(same.all { it.derivation == Derivation.SAME_OBSERVATION })
        assertEquals(listOf(5), same.flatMap { it.episodes }.distinct())
        // Ninguna coincidencia entre fuentes de ICdetector se presenta como independiente.
        assertTrue(a.cross!!.findings.filter { it.outcome == CrossOutcome.MATCH }.none { it.derivation == Derivation.INDEPENDENT_FAMILIES })
    }

    // ── 10. independencia del orden de importación ──────────────────────

    @Test fun resultsDoNotDependOnImportOrder() {
        fun facts(names: List<String>, prefix: String): List<Any?> {
            var n = 0
            val outcomes = BatchImport.run(names.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }, emptyMap(), TestSupport.MADRID, { "$prefix-${++n}" })
            val a = CaseAnalyzer.analyze(outcomes.mapNotNull { (it as? BatchOutcome.Processed)?.result }, TestSupport.MADRID, null)
            return a.cross!!.findings.map { f -> listOf(f.check, f.outcome, f.reason, f.sourceA, f.sourcesB, f.files, f.entities, f.start, f.end, f.count,
                f.compared, f.refs.map { Triple(it.fileName, it.zipEntry, it.recordIndex) }, f.derivation, f.episodes, f.next) }
        }
        val names = GenerateSampleReport.ALL
        assertEquals(facts(names, "a"), facts(names.reversed(), "zz"))
    }

    // ── valoración de referencia ─────────────────────────────────────────

    @Test fun referenceAssessmentIsUnchanged() {
        val a = sampleCase(GenerateSampleReport.PATTERNS)
        assertEquals(SpoofingRelevance.NEEDS_MORE_DATA, a.assessment.overall)
        assertEquals(listOf(2, 3, 8, 9), a.episodes.episodes.filter { it.priority == Priority.REVIEW }.map { it.number })
        assertTrue(a.episodes.episodes.none { it.priority == Priority.PRIORITY })
        assertNotNull(a.cross)
        assertNull(a.cross!!.why)
    }

    @Test fun singleSourceExplainsWhyNothingIsCompared() {
        val a = analyze(imp(csv(h("2026-09-26 10:00:00")).toByteArray(), "h.csv", "imp-1"))
        assertTrue(a.cross!!.findings.isEmpty())
        assertEquals(CrossReason.NO_DATA, a.cross!!.why)
    }

    // ── cross/2: correcciones de la revisión ────────────────────────────

    /** Hechos de los cruces sin identificadores internos, para comparar órdenes de importación. */
    private fun crossFacts(a: CaseAnalysis): List<Any?> = a.cross!!.findings.map { f -> listOf(f.check, f.outcome, f.reason, f.sourceA, f.sourcesB, f.files, f.entities,
        f.start, f.end, f.window, f.count, f.compared, f.refs.map { Triple(it.fileName, it.zipEntry, it.recordIndex) }, f.derivation, f.episodes, f.next) } +
        listOf(a.aggregateConflicts.map { c -> c.kind to c.versions.map { listOf(it.fileName, it.time, it.summary) } })

    @Test fun radioStateMissingOrUninterpretableIsNeverADifference() {
        val rad = imp(radio(ev("2026-09-26T07:00:00Z", "IN_SERVICE"), ev("2026-09-26T08:00:00Z", ""), ev("2026-09-26T09:00:00Z", "ESTADO_RARO"),
            ev("2026-09-26T10:00:00Z", "UNKNOWN"), ev("2026-09-26T11:00:00Z", "IN_SERVICE"), ev("2026-09-26T13:00:00Z", "IN_SERVICE")), "r.csv", "imp-2")
        val hist = imp(csv(
            h("2026-09-26 10:20:00"),                     // tras un estado vacío
            h("2026-09-26 11:20:00"),                     // tras un texto no reconocido
            h("2026-09-26 12:20:00"),                     // tras UNKNOWN
            h("2026-09-26 13:20:00", state = "UNKNOWN"),  // el historial no sabe su estado
            h("2026-09-26 13:30:00")                      // comparable: coincide
        ).toByteArray(), "h.csv", "imp-1")
        val a = analyze(hist, rad)
        assertTrue("nunca una diferencia", a.f(CrossCheck.SERVICE_STATE, CrossOutcome.DIFFERENCE).isEmpty())
        assertEquals(4, a.f(CrossCheck.SERVICE_STATE, CrossOutcome.NOT_COMPARABLE, CrossReason.STATE_NOT_INTERPRETABLE).single().count)
        assertEquals(1, a.f(CrossCheck.SERVICE_STATE, CrossOutcome.MATCH).single().count)
        assertEquals(crossFacts(a), crossFacts(analyze(rad, hist)))
    }

    @Test fun aggregatesWithSameOrMissingDateAreNotChosenByImportOrder() {
        val topoA = TestSupport.sample("SYNTHETIC_ICD-topology.zip").readBytes()
        val transitionsB = zipText("SYNTHETIC_ICD-topology.zip", "transitions.csv").replace("\"41\",\"40\"", "\"55\",\"54\"")
        val topoB = zipWith("SYNTHETIC_ICD-topology.zip", mapOf("transitions.csv" to transitionsB))
        val hist = csv(h("2026-09-26 10:00:00"), h("2026-09-26 10:05:00", cid = "1000002")).toByteArray()
        fun case(order: List<Pair<String, ByteArray>>): CaseAnalysis =
            analyze(*(listOf("h.csv" to hist) + order).mapIndexed { n, (name, b) -> imp(b, name, "imp-" + (n * 7) % 5) }.toTypedArray())
        // misma fecha, contenido distinto: no se elige ninguna, en ningún orden
        val ab = case(listOf("topo-a.zip" to topoA, "topo-b.zip" to topoB))
        val ba = case(listOf("topo-b.zip" to topoB, "topo-a.zip" to topoA))
        assertNull(ab.topology); assertNull(ba.topology)
        val conflict = ab.aggregateConflicts.single()
        assertEquals(listOf("topo-a.zip", "topo-b.zip"), conflict.versions.map { it.fileName })
        val ver = ab.f(CrossCheck.AGGREGATE_VERSIONS).single()
        assertEquals(CrossOutcome.NOT_COMPARABLE to CrossReason.AMBIGUOUS_EXPORTS, ver.outcome to ver.reason)
        assertTrue("sin cruces con un agregado no elegido", ab.cross!!.findings.none { it.check != CrossCheck.AGGREGATE_VERSIONS && ExportKind.TOPOLOGY in it.sourcesB })
        assertEquals(crossFacts(ab), crossFacts(ba))
        assertEquals(ab.episodes.episodes.map { it.priority to it.relevance.relevance }, ba.episodes.episodes.map { it.priority to it.relevance.relevance })
        // sin fecha en ninguna: igual
        val meta = zipText("SYNTHETIC_ICD-topology.zip", "metadata.json").lines().filterNot { "generatedAtUtc" in it }.joinToString("\n")
        val noDateA = zipWith("SYNTHETIC_ICD-topology.zip", mapOf("metadata.json" to meta))
        val noDateB = zipWith("SYNTHETIC_ICD-topology.zip", mapOf("metadata.json" to meta, "transitions.csv" to transitionsB))
        val n1 = case(listOf("a.zip" to noDateA, "b.zip" to noDateB)); val n2 = case(listOf("b.zip" to noDateB, "a.zip" to noDateA))
        assertNull(n1.topology)
        assertTrue(n1.aggregateConflicts.single().versions.all { it.time == null })
        assertEquals(crossFacts(n1), crossFacts(n2))
        // misma fecha y mismo contenido: cualquiera sirve, sin conflicto y sin depender del orden
        val s1 = case(listOf("copia-1.zip" to topoA, "copia-2.zip" to topoA)); val s2 = case(listOf("copia-2.zip" to topoA, "copia-1.zip" to topoA))
        assertNotNull(s1.topology); assertTrue(s1.aggregateConflicts.isEmpty())
        assertEquals(crossFacts(s1), crossFacts(s2))
    }

    @Test fun simultaneousIdentitiesDoNotCreateTransitions() {
        // X (1000001) a las 10:00; A (1000002) y B (1000003) a las 10:05:00 exactas; Y (1000004) a las 10:10.
        // Topología contiene X→A: con el orden de filas se habría «comprobado» un cambio que no se puede justificar.
        val f1 = csv(h("2026-09-26 10:00:00"), h("2026-09-26 10:05:00", cid = "1000002")).toByteArray()
        val f2 = csv(h("2026-09-26 10:05:00", cid = "1000003"), h("2026-09-26 10:10:00", cid = "1000004")).toByteArray()
        val topo = TestSupport.sample("SYNTHETIC_ICD-topology.zip").readBytes()
        val a = analyze(imp(f1, "h1.csv", "imp-1"), imp(f2, "h2.csv", "imp-2"), imp(topo, "t.zip", "imp-3"))
        val b = analyze(imp(topo, "t.zip", "imp-9"), imp(f2, "h2.csv", "imp-8"), imp(f1, "h1.csv", "imp-7"))
        val routes = a.f(CrossCheck.ROUTES)
        assertTrue("ningún cambio hacia, desde ni a través del instante en conflicto",
            routes.filter { it.reason != CrossReason.SIMULTANEOUS_IDENTITIES }.all { it.reason == CrossReason.NO_DATA && it.entities.isEmpty() })
        val sim = routes.single { it.reason == CrossReason.SIMULTANEOUS_IDENTITIES }
        assertEquals(CrossOutcome.NOT_COMPARABLE, sim.outcome)
        assertEquals(1, sim.count)
        assertEquals(listOf("001-01-1001-1000002-LTE", "001-01-1001-1000003-LTE"), sim.entities)
        assertEquals(crossFacts(a), crossFacts(b))
    }

    @Test fun shadowTextsStateTheMarginAndDoNotClaimTheSameEvent() {
        val a = sampleCase(GenerateSampleReport.PATTERNS)
        val f = a.f(CrossCheck.STABLE_SITE_EPISODE, CrossOutcome.MATCH, CrossReason.TRANSITION_IN_WINDOW).first()
        assertEquals(java.time.Duration.ofSeconds(60), f.window)
        val es = com.alexisgordr.cellcase.core.report.reportTexts(com.alexisgordr.cellcase.core.i18n.Language.ES)
        val en = com.alexisgordr.cellcase.core.report.reportTexts(com.alexisgordr.cellcase.core.i18n.Language.EN)
        val tEs = es.crossExplanation(f); val tEn = en.crossExplanation(f)
        assertTrue(tEs, "±1 min" in tEs && "no demuestra que sea exactamente el mismo evento" in tEs)
        assertTrue(tEn, "±1 min" in tEn && "does not prove that it is exactly the same event" in tEn)
        assertFalse(tEs, "es el mismo cambio de celda." in tEs)
        val lay = es.crossLay(f)!!
        assertTrue(lay, "margen de 1 min" in lay && "Puede ser el mismo cambio" in lay)
        // el orden de importación no cambia el resultado
        assertEquals(crossFacts(a), crossFacts(sampleCase(GenerateSampleReport.PATTERNS.reversed())))
    }

    // ── presentación resumida (§6) ───────────────────────────────────────

    private fun scaleImports(): List<ImportResult> {
        var n = 0
        val outcomes = BatchImport.run(ScaleFixture.files().map { (nm, b) -> SelectedFile(nm, b) }, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
        return outcomes.mapNotNull { (it as? BatchOutcome.Processed)?.result }
    }
    private fun scaleCase(): CaseAnalysis = CaseAnalyzer.analyze(scaleImports(), TestSupport.MADRID, null)

    @Test fun summaryAndFullListKeepTheSameTotals() {
        for (a in listOf(scaleCase(), sampleCase(GenerateSampleReport.ALL), sampleCase(GenerateSampleReport.PATTERNS))) {
            val r = a.cross!!
            val v = CrossSummary.of(r)
            // cada resultado está en exactamente un grupo del resumen
            assertEquals(r.findings.size, v.totalResults)
            assertEquals(r.findings.size, v.summary.flatMap { it.findings }.size)
            assertEquals(r.findings.toSet(), v.summary.flatMap { it.findings }.toSet())
            for (o in CrossOutcome.values()) assertEquals(r.count(o), v.total(o))
            // el detalle: todas las diferencias y los resultados vinculados a episodios, sin pérdidas ni duplicados
            val expectedDetail = r.findings.filter { it.outcome == CrossOutcome.DIFFERENCE || it.episodes.isNotEmpty() }
            assertEquals(expectedDetail.size, v.detail.sumOf { it.results })
            assertEquals(expectedDetail.toSet(), v.detail.flatMap { it.findings }.toSet())
        }
        // el expediente a escala reproduce el volumen del real: más de mil resultados rutinarios de Stable-Site
        assertTrue(scaleCase().cross!!.findings.count { it.check == CrossCheck.STABLE_SITE_EPISODE } >= 1000)
    }

    @Test fun reportShowsSummaryByDefaultAndFullListOnlyInOptionalAnnex() {
        val imports = scaleImports()
        val a = CaseAnalyzer.analyze(imports, TestSupport.MADRID, null)
        val r = a.cross!!
        for (lang in Language.values()) {
            fun tables(annex: Boolean) = ReportBuilder(reportTexts(lang)).build(
                TextContext(CaseInfo("x", "Escala", Instant.EPOCH, "Sintético", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null), imports,
                    ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = ReportMode.FULL, crossAnnex = annex)), a)
                .blocks.filterIsInstance<Block.Table>()
            val summaryPrefix = Messages.format(lang, "cross.caption_summary", listOf(r.version, 0, 0)).substringBefore(':')
            val fullPrefix = Messages.format(lang, "cross.caption_full", listOf(r.version, 0)).substringBefore(':')
            val off = tables(false); val on = tables(true)
            assertTrue(off.none { it.caption?.startsWith(fullPrefix) == true })
            assertEquals(r.findings.size, on.single { it.caption?.startsWith(fullPrefix) == true }.rows.size)
            val sumOff = off.single { it.caption?.startsWith(summaryPrefix) == true }
            assertEquals(sumOff.rows, on.single { it.caption?.startsWith(summaryPrefix) == true }.rows)
            assertEquals(CrossSummary.of(r).summary.size, sumOff.rows.size)
            assertTrue(sumOff.rows.size * 20 < r.findings.size)
        }
    }

    private fun sampleCase(names: List<String>): CaseAnalysis {
        var n = 0
        val outcomes = BatchImport.run(names.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
        return CaseAnalyzer.analyze(outcomes.mapNotNull { (it as? BatchOutcome.Processed)?.result }, TestSupport.MADRID, null)
    }
}
