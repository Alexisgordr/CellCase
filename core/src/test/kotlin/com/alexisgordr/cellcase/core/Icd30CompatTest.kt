package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.ForensicVersions
import com.alexisgordr.cellcase.core.analysis.HistoryMerge
import com.alexisgordr.cellcase.core.analysis.RowMatch
import com.alexisgordr.cellcase.core.analysis.VersionRelation
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.HistoryCsvImporter
import com.alexisgordr.cellcase.core.importing.HistoryImport
import com.alexisgordr.cellcase.core.importing.HistorySchema
import com.alexisgordr.cellcase.core.importing.IcdVersion
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.SafeZip
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.time.DeclaredZone
import com.alexisgordr.cellcase.core.time.TimeResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exportaciones de ICdetector 3.0.0 leídas con los lectores de CellCase (readers/2.3.0, history-csv/1.1.0, merge/4). */
class Icd30CompatTest {
    private val history = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv").readText()

    private fun imp(text: String, zone: DeclaredZone? = TestSupport.MADRID, name: String = "h.csv", id: String = "h") =
        HistoryCsvImporter().import(text.byteInputStream(), name, id, zone)

    /** CSV con las columnas indicadas quitadas (simula una exportación anterior o recortada). */
    private fun dropColumns(text: String, cols: Collection<String>, unguard: Boolean = false): String {
        val lines = text.lines().filter { it.isNotEmpty() }
        val h = lines[0].split(",")
        val keep = h.indices.filter { h[it] !in cols }
        // Las filas con comillas se resuelven con el lector de CSV para no partir campos
        val reader = com.alexisgordr.cellcase.core.csv.CsvReader(text.reader().buffered())
        val out = StringBuilder()
        while (true) {
            val r = reader.next() ?: break
            out.append(keep.joinToString(",") { i -> r.fields[i].let { if (unguard) com.alexisgordr.cellcase.core.csv.CsvGuard.unguard(it) else it }.let { v -> if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v } }).append('\n')
        }
        return out.toString()
    }

    @Test fun readsThe54ColumnsOfIcdetector30() {
        val h = imp(history)
        assertEquals(HistorySchema.V3_0, h.schema)
        assertEquals(0, h.rejectedRows)
        assertTrue("no quedan columnas sin interpretar", h.uninterpretedColumns.isEmpty())
        assertEquals("SYNTHETIC Phone 3", h.exportDevice); assertEquals("16 (API 36)", h.exportAndroid)
        val modern = h.observations.filter { it.observedAtUtc != null }
        val legacy = h.observations.filter { it.observedAtUtc == null }
        assertTrue(modern.isNotEmpty() && legacy.isNotEmpty())
        assertTrue(modern.all { it.appVersion == "3.0.0+f5173a8" && it.locationMode == "INTELLIGENT" && it.observationId != null })
        assertTrue(legacy.all { it.appVersion == null && it.profile == null && it.observationId == null })
        assertTrue(h.observations.all { it.rowId != null })
        assertTrue(modern.any { it.notEvaluatedHeuristics == "NONE" } && modern.any { it.notEvaluatedHeuristics == "H1;H9" })
        assertTrue(modern.any { it.gpsAccuracyM != null })
        val evaluated = h.observations.mapNotNull { it.profile }
        assertTrue(evaluated.any { it.level == "ABSTAIN" && it.abstainReason in setOf("NO_PROFILE", "PROFILE_IMMATURE") && it.score == null })
        assertTrue(evaluated.any { it.level == "HIGH" && (it.score ?: 0.0) >= 4.0 && it.evidence!!.startsWith("SIGNAL_DBM") })
        assertTrue(evaluated.all { it.mode == "SHADOW" && it.algorithm == 2 && it.metricsTotal == 2 })
        assertTrue(evaluated.any { it.holdout == true && it.learned == false })
        assertFalse(h.issues.any { it.severity == Severity.ERROR })
    }

    @Test fun utcInstantResolvesTheRepeatedHourOfTheClockChange() {
        val h = imp(history)
        val repeated = h.observations.filter { it.time.raw == "2026-10-25 02:30:00" }
        assertEquals(2, repeated.size)
        val instants = repeated.map { (it.time.resolution as TimeResolution.Exact).instant }.toSet()
        assertEquals(setOf(Instant.parse("2026-10-25T00:30:00Z"), Instant.parse("2026-10-25T01:30:00Z")), instants)
        val offsets = repeated.map { (it.time.resolution as TimeResolution.Exact).offset.totalSeconds }.toSet()
        assertEquals(setOf(7200, 3600), offsets)
        assertTrue(h.issues.any { it.code == "TIME_AMBIGUOUS_RESOLVED" })
        assertFalse(h.issues.any { it.code == "TIME_AMBIGUOUS" })
        // dos instantes distintos: no son «misma hora e identidad»
        assertFalse(h.issues.any { it.code == "DUP_SAME_TIME_IDENTITY" })
    }

    @Test fun utcInstantDoesNotDependOnTheDeclaredZone() {
        val confirmed = imp(history)
        val unconfirmed = imp(history, DeclaredZone(ZoneId.of("Europe/Madrid"), false))
        val modern = { h: HistoryImport -> h.observations.filter { it.observedAtUtc != null }.map { it.time.instantOrNull } }
        assertEquals(modern(confirmed), modern(unconfirmed))
        assertTrue(modern(unconfirmed).all { it != null })
        // las filas anteriores a 3.0 siguen necesitando la zona confirmada
        assertTrue(unconfirmed.observations.filter { it.observedAtUtc == null }.all { it.time.instantOrNull == null })
    }

    @Test fun aWrongDeclaredZoneIsReportedAndTheRecordedInstantIsKept() {
        val ny = imp(history, DeclaredZone(ZoneId.of("America/New_York"), true))
        assertTrue(ny.issues.any { it.code == "TIME_ZONE_MISMATCH" && it.severity == Severity.WARNING })
        val ok = imp(history)
        assertFalse(ok.issues.any { it.code == "TIME_ZONE_MISMATCH" })
        val m = ny.observations.first { it.observedAtUtc != null }
        assertEquals(m.observedAtUtc!!.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), m.time.instantOrNull)
    }

    @Test fun protectiveApostropheIsRemovedAndReported() {
        val h = imp(history)
        val names = h.observations.mapNotNull { it.csgName }.toSet()
        assertTrue(names.containsAll(setOf("=Femto,\"casa\"", "-Femto", "'ya protegido")))
        val issue = h.issues.single { it.code == "TEXT_GUARD_REMOVED" }
        assertEquals(3, issue.count)
        assertTrue(issue.examples.any { it.rawValue == "''ya protegido" })
        // un historial 2.10.x no se toca: allí un apóstrofo inicial es parte del dato
        val old = TestSupport.HEADER + "\n" + TestSupport.row("2026-09-27 10:00:00", mapOf("CsgName" to "'=antiguo"))
        val o = imp(old)
        assertEquals(HistorySchema.V2_10_4_PLUS, o.schema)
        assertEquals("'=antiguo", o.observations.single().csgName)
    }

    @Test fun sameReadingSavedTwiceIsReportedOnceAndKept() {
        val h = imp(history)
        val dup = h.issues.single { it.code == "DUP_SAME_OBSERVATION" }
        assertEquals(1, dup.count)
        val ids = h.observations.mapNotNull { it.observationId }
        assertEquals(1, ids.size - ids.toSet().size)
        assertEquals(TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv").readLines().size - 1, h.observations.size)
    }

    @Test fun partialV3HeaderIsReadWithAWarning() {
        val partial = dropColumns(history, listOf("ProfileHoldout", "RowId"))
        val h = imp(partial)
        assertEquals(HistorySchema.PARTIAL_V3, h.schema)
        assertTrue(h.issues.any { it.code == "SCHEMA_PARTIAL_V3" })
        assertTrue(h.observations.all { it.rowId == null })
    }

    @Test fun mergeIgnoresExportDeviceAndCountsThe30RowsOnce() {
        val a = imp(history, name = "a.csv", id = "a")
        val other = history.replace("SYNTHETIC Phone 3", "SYNTHETIC Phone 4")
        val b = imp(other, name = "b.csv", id = "b")
        val m = HistoryMerge.merge(listOf(a, b), TestSupport.MADRID)!!
        assertEquals(a.observations.size, m.import.observations.size)
        assertTrue(m.matches.all { it.kind == RowMatch.EXACT && !it.schemaDiffers })
    }

    @Test fun mergeWithA2105StyleExportComparesTheCommonColumns() {
        val v3 = imp(history, name = "v3.csv", id = "v3")
        // una exportación 2.10.x escribe los textos tal cual, sin el apóstrofo protector de 3.0
        val v2 = imp(dropColumns(history, com.alexisgordr.cellcase.core.importing.HistoryColumns.V3, unguard = true), name = "v2.csv", id = "v2")
        assertEquals(HistorySchema.V2_10_4_PLUS, v2.schema)
        // sin ObservedAtUtc, el 2.10.x deja ambiguas las dos filas de la hora repetida; el resto coincide
        val m = HistoryMerge.merge(listOf(v2, v3), TestSupport.MADRID)!!
        assertEquals(v3.observations.size, m.import.observations.size)
        assertTrue(m.matches.all { it.schemaDiffers })
    }

    @Test fun profileEvaluatedLaterIsKeptWhenMerging() {
        val lines = history.lines().filter { it.isNotEmpty() }
        val h = lines[0].split(","); val iLevel = h.indexOf("ProfileLevel")
        val target = lines.indexOfFirst { l -> !l.contains('"') && l.split(",").let { it.size == h.size && it[iLevel] == "HIGH" } }
        assertTrue(target > 0)
        // la exportación anterior se hizo antes de evaluar esa fila: columnas del perfil vacías
        val f = lines[target].split(",").toMutableList()
        for ((i, c) in h.withIndex()) if (c.startsWith("Profile")) f[i] = ""
        val earlier = (lines.take(target) + f.joinToString(",") + lines.drop(target + 1)).joinToString("\n") + "\n"
        val a = imp(earlier, name = "antes.csv", id = "antes"); val b = imp(history, name = "despues.csv", id = "despues")
        val m = HistoryMerge.merge(listOf(a, b), TestSupport.MADRID)!!
        val revised = m.matches.filter { it.kind == RowMatch.REVISION }
        assertEquals(1, revised.size)
        assertTrue(revised.single().fields.keys.all { it.startsWith("Profile") })
        val kept = m.import.observations.single { it.rowId == f[h.indexOf("RowId")].toLong() }
        assertEquals("HIGH", kept.profile?.level)
    }

    // ── ZIP de 3.0 ──

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bo.toByteArray()
    }

    private fun entries(name: String) = SafeZip.readAll(TestSupport.sample("icd30/$name").readBytes())
    private fun process(name: String, bytes: ByteArray = TestSupport.sample("icd30/$name").readBytes()): ImportResult =
        ImportPipeline.process(bytes, name, "id-$name", TestSupport.MADRID)

    @Test fun forensicCaseOf30ReadsNewColumnsSecondaryCellsAndTrustSite() {
        val r = process("SYNTHETIC_icd30_ICD-2026-10-22-0001.zip")
        assertEquals(ExportKind.FORENSIC_CASE, r.kind); assertTrue(r.imported)
        val d = (r.data as ImportedData.Forensic).data
        assertEquals(1L, d.meta.sampleSchemaVersion)
        assertTrue(d.cells.any { it.role == "SECONDARY" && !it.isNeighbour })
        assertTrue(d.cells.any { it.role == "NEIGHBOR" && it.isNeighbour })
        assertTrue(d.cells.filter { it.role == "SERVING" }.all { it.registered == true })
        assertEquals(d.samples.size, d.trustSite.size)
        assertTrue(d.trustSite.any { it.trustState == "CONTRADICTED" && it.contradictions == "SIGNAL_JUMP;PCI_FLAP" && it.trustConfidencePercent == 62 })
        assertTrue(d.samples.all { it.requiredPhases == 3 })
        assertTrue(d.samples.any { it.dataQualityReason != null })
        // la explicación «+19 dB…» llega con apóstrofo protector y se lee sin él
        assertTrue(r.issues.any { it.code == "TEXT_GUARD_REMOVED" })
        // las columnas de 3.0 que CellCase lee no se avisan como no interpretadas
        val extra = r.issues.filter { it.code == "EXTRA_COLUMNS" }.joinToString { it.text.toString() }
        assertFalse(extra.contains("required_phases")); assertFalse(extra.contains("registered")); assertFalse(extra.contains("local_trust_state"))
        assertTrue(extra.contains("connection_state"))
        assertFalse(r.issues.any { it.code == "ZIP_UNKNOWN_ENTRIES" })
        assertEquals(com.alexisgordr.cellcase.core.importing.IntegrityStatus.VERIFIED, r.integrity?.status)
    }

    @Test fun secondaryRoleOf30AndNeighborOf2105AreTheSameCaseVersion() {
        val e = entries("SYNTHETIC_icd30_ICD-2026-10-22-0001.zip")
        // La misma exportación tal como la habría escrito 2.10.x: portadoras secundarias como NEIGHBOR
        val cells = String(e.getValue("cells.csv")).replace("\"SECONDARY\"", "\"NEIGHBOR\"").toByteArray()
        val old = LinkedHashMap(e).apply { put("cells.csv", cells); remove("SHA256SUMS.txt") }
        val sums = old.entries.joinToString("\n", postfix = "\n") { (n, b) -> com.alexisgordr.cellcase.core.importing.Hashing.sha256Hex(b) + "  " + n }.toByteArray()
        old["SHA256SUMS.txt"] = sums
        val a = process("nuevo.zip", zip(e)); val b = process("viejo.zip", zip(old))
        val g = ForensicVersions.group(listOf(a, b).map { r -> ForensicVersions.Version(r.importId, r.fileName, r.sha256, (r.data as ImportedData.Forensic).data) })
        assertEquals(VersionRelation.EQUIVALENT, g.single().relation)
    }

    @Test fun stableSiteOf30RecognisesThePrivacyNote() {
        val r = process("SYNTHETIC_icd30_stable_site.zip")
        assertTrue(r.imported)
        assertFalse(r.issues.any { it.code == "ZIP_UNKNOWN_ENTRIES" })
        assertEquals(com.alexisgordr.cellcase.core.importing.EntryRole.CONTEXT, r.entries.single { it.name == "PRIVACY.txt" }.role)
        assertTrue(r.schemaText.toString().contains("3.0"))
    }

    @Test fun truncationWarningOnlyForVersionsThatHadACap() {
        fun topology(appVersion: String, routes: Int): ImportResult {
            val t = StringBuilder("from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc\n")
            repeat(routes) { i -> t.append("\"001-01-1-$i-LTE\",\"001-01-1-${i + 1}-LTE\",\"1\",\"1\",\"1.0000\",\"PASSED\",\"2026-10-01T00:00:00Z\"\n") }
            val g = "<graphml>" + "<edge id=\"e\"/>".repeat(routes) + "</graphml>"
            val meta = "{\"schemaVersion\":1,\"appVersion\":\"$appVersion\",\"routeCount\":$routes}"
            return process("t.zip", zip(linkedMapOf("transitions.csv" to t.toString().toByteArray(), "topology.graphml" to g.toByteArray(), "metadata.json" to meta.toByteArray())))
        }
        assertTrue(topology("2.10.5", 250).issues.any { it.code == "POSSIBLE_TRUNCATION" })
        assertFalse(topology("3.0.0+f5173a8", 250).issues.any { it.code == "POSSIBLE_TRUNCATION" })
        assertFalse(topology("2.10.6", 250).issues.any { it.code == "POSSIBLE_TRUNCATION" })
    }

    @Test fun serviceStateOf30() {
        val r = process("SYNTHETIC_icd30_service_state.csv")
        assertTrue(r.imported)
        val events = (r.data as ImportedData.ServiceState).events
        assertEquals(11, events.size)
        assertTrue(r.issues.any { it.code == "TEXT_GUARD_REMOVED" })
    }

    @Test fun icdetectorVersionsAreComparedNumerically() {
        assertTrue(IcdVersion.atLeast("3.0.0+f5173a8", 3, 0, 0)); assertTrue(IcdVersion.atLeast("2.10.10", 2, 10, 6))
        assertFalse(IcdVersion.atLeast("2.10.5", 2, 10, 6)); assertFalse(IcdVersion.atLeast("2.9", 2, 10, 0))
        assertTrue(IcdVersion.atLeast("3.0.0-beta4+6bb5ed3", 3, 0, 0)); assertFalse(IcdVersion.atLeast("unknown", 1, 0, 0)); assertFalse(IcdVersion.atLeast(null, 1, 0, 0))
        assertEquals(Triple(2, 9, 0), IcdVersion.parse("2.9")); assertNull(IcdVersion.parse(""))
        assertNotNull(IcdVersion.parse(" 3.0.0"))
    }
}
