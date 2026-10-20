package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.IntegrityStatus
import com.alexisgordr.cellcase.core.importing.SelectedFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ImportPipelineTest {
    private fun run(name: String): ImportResult =
        ImportPipeline.process(TestSupport.sample(name).readBytes(), name, "id-$name", TestSupport.MADRID)

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return bos.toByteArray()
    }

    @Test fun theFiveUsualFormatsPlusForensicImport() {
        val expected = mapOf(
            "SYNTHETIC_icdetector_history.csv" to ExportKind.HISTORY,
            "SYNTHETIC_icdetector_service_state.csv" to ExportKind.SERVICE_STATE,
            "SYNTHETIC_ICD-topology.zip" to ExportKind.TOPOLOGY,
            "SYNTHETIC_ICD-stable-site.zip" to ExportKind.STABLE_SITE,
            "SYNTHETIC_ICD-geometry.zip" to ExportKind.GEOMETRY,
            "SYNTHETIC_ICD-2026-09-27-0007.zip" to ExportKind.FORENSIC_CASE
        )
        for ((name, kind) in expected) {
            val r = run(name)
            assertEquals(name, ImportState.IMPORTED, r.state)
            assertEquals(name, kind, r.kind)
            assertNotNull(name, r.data)
            assertNotNull(name, r.period)
        }
    }

    @Test fun countsMatchTheSyntheticManifest() {
        assertEquals(7, run("SYNTHETIC_icdetector_service_state.csv").counts["events"])
        val topo = run("SYNTHETIC_ICD-topology.zip")
        assertEquals(8, topo.counts["routes"])
        assertEquals(192, topo.counts["handovers"])
        assertEquals(192L, (topo.data as ImportedData.Topology).data.declaredHandovers)
        val geo = run("SYNTHETIC_ICD-geometry.zip")
        assertEquals(8, geo.counts["edges"]); assertEquals(4, geo.counts["trips"])
        val ss = run("SYNTHETIC_ICD-stable-site.zip")
        assertEquals(8, ss.counts["sites"]); assertEquals(2, ss.counts["episodes"])
        val f = run("SYNTHETIC_ICD-2026-09-27-0007.zip")
        val data = (f.data as ImportedData.Forensic).data
        assertEquals(data.meta.sampleCount!!.toInt(), data.samples.size)
        assertEquals("ICD-2026-09-27-0007", data.meta.caseCode)
    }

    @Test fun sha256SumsAreVerifiedAndReportedExactly() {
        assertEquals(IntegrityStatus.VERIFIED, run("SYNTHETIC_ICD-2026-09-27-0007.zip").integrity!!.status)
        assertEquals(IntegrityStatus.VERIFIED, run("SYNTHETIC_ICD-topology.zip").integrity!!.status)
        assertEquals(IntegrityStatus.NOT_PROVIDED, run("SYNTHETIC_ICD-geometry.zip").integrity!!.status)
        val t = run("SYNTHETIC_ICD-2026-09-27-0007_tampered.zip")
        assertEquals(ImportState.IMPORTED, t.state)                 // se importa, pero con aviso
        assertEquals(IntegrityStatus.MISMATCH, t.integrity!!.status)
        assertEquals(listOf("timeline.csv"), t.integrity!!.mismatched)
        assertTrue(t.issues.any { it.code == "SHA256_MISMATCH" })
    }

    @Test fun zipEntriesAreListedWithRoles() {
        val r = run("SYNTHETIC_ICD-2026-09-27-0007.zip")
        assertEquals(listOf("case.json", "timeline.csv", "cells.csv", "heuristics.csv", "capabilities.json", "terminal.log", "SHA256SUMS.txt"), r.entries.map { it.name })
        assertTrue(r.entries.all { it.sha256.length == 64 })
    }

    @Test fun errorCategoriesAreDistinct() {
        assertEquals(ImportState.ZIP_SECURITY, run("SYNTHETIC_hostile_path_traversal.zip").state)
        assertEquals(ImportState.ZIP_SECURITY, run("SYNTHETIC_hostile_zip_bomb_64MiB.zip").state)
        assertEquals(ImportState.ZIP_SECURITY, run("SYNTHETIC_hostile_many_entries.zip").state)
        assertEquals(ImportState.DAMAGED_ZIP, run("SYNTHETIC_hostile_truncated.zip").state)
        assertEquals(ImportState.READ_ERROR, run("SYNTHETIC_icdetector_history_malformed.csv").state)
        val unknownText = ImportPipeline.process("hola,mundo\n1,2\n".toByteArray(), "notas.csv", "x", TestSupport.MADRID)
        assertEquals(ImportState.UNRECOGNIZED, unknownText.state)
        val unknownZip = ImportPipeline.process(zip(mapOf("a.txt" to "x".toByteArray())), "otro.zip", "x", TestSupport.MADRID)
        assertEquals(ImportState.UNRECOGNIZED, unknownZip.state)
        assertEquals(1, unknownZip.entries.size)
        // Tipo reconocido pero sin columna obligatoria → incompatible
        val badRadio = ImportPipeline.process((ImportPipeline.SERVICE_COLUMNS.dropLast(1).joinToString(",").replace("NetworkName", "Nombre") + "\n")
            .let { "TimestampUtc,State,X\n" }.toByteArray(), "r.csv", "x", TestSupport.MADRID)
        assertEquals(ImportState.INCOMPATIBLE, badRadio.state)
        // ZIP de topología sin transitions.csv pero con su GraphML: reconocido por estructura incompleta → no reconocido
        val noTransitions = ImportPipeline.process(zip(mapOf("topology.graphml" to "<graphml/>".toByteArray())), "t.zip", "x", TestSupport.MADRID)
        assertEquals(ImportState.UNRECOGNIZED, noTransitions.state)
        // Binario que no es ni ZIP ni UTF-8
        assertEquals(ImportState.READ_ERROR, ImportPipeline.process(byteArrayOf(0xC3.toByte(), 0x28, 0x00), "b.bin", "x", TestSupport.MADRID).state)
    }

    @Test fun unknownZipEntriesArePreservedNotInterpreted() {
        val original = TestSupport.sample("SYNTHETIC_ICD-topology.zip").readBytes()
        val entries = java.util.zip.ZipInputStream(original.inputStream()).use { z ->
            generateSequence { z.nextEntry }.associate { it.name to z.readBytes() }
        }.toMutableMap()
        entries["nota_usuario.txt"] = "añadido a mano".toByteArray()
        val r = ImportPipeline.process(zip(entries), "topo+extra.zip", "x", TestSupport.MADRID)
        assertEquals(ImportState.IMPORTED, r.state)
        assertTrue(r.entries.any { it.name == "nota_usuario.txt" && it.role.name == "UNKNOWN" })
        assertTrue(r.issues.any { it.code == "ZIP_UNKNOWN_ENTRIES" })
        assertEquals(IntegrityStatus.INCOMPLETE, r.integrity!!.status)   // entrada no listada en SHA256SUMS
    }

    @Test fun emptyRadioIsImportedWithAmbiguityWarning() {
        val r = run("SYNTHETIC_icdetector_service_state_empty.csv")
        assertEquals(ImportState.IMPORTED, r.state)
        assertEquals(0, r.counts["events"])
        assertNull(r.period)
        assertTrue(r.issues.any { it.code == "NO_DATA_ROWS" })
    }

    @Test fun syntheticIndicatorsAreDetectedButDoNotDecide() {
        val r = run("SYNTHETIC_icdetector_history.csv")
        assertTrue(r.syntheticIndicators.contains("FILENAME_SYNTHETIC"))
        assertTrue(r.syntheticIndicators.any { it.startsWith("TEST_MCC_001") })
        val renamed = ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes(), "caso.zip", "x", TestSupport.MADRID)
        assertTrue(renamed.syntheticIndicators.contains("DEVICE_SYNTHETIC"))
        assertFalse(renamed.syntheticIndicators.contains("FILENAME_SYNTHETIC"))
    }

    @Test fun batchOfFiveUsualFilesPlusOneInvalidImportsTheRest() {
        val names = listOf("SYNTHETIC_icdetector_history.csv", "SYNTHETIC_icdetector_service_state.csv", "SYNTHETIC_ICD-topology.zip",
            "SYNTHETIC_ICD-stable-site.zip", "SYNTHETIC_ICD-geometry.zip", "SYNTHETIC_hostile_truncated.zip")
        var n = 0
        val out = BatchImport.run(names.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }, emptyMap(), TestSupport.MADRID, { "id${n++}" })
        assertEquals(6, out.size)
        val processed = out.filterIsInstance<BatchOutcome.Processed>().map { it.result }
        assertEquals(5, processed.count { it.imported })
        assertEquals(ImportState.DAMAGED_ZIP, processed.single { !it.imported }.state)
    }

    @Test fun identicalFilesAreDetectedByHashInBatchAndAgainstExisting() {
        val h = TestSupport.sample("SYNTHETIC_icdetector_history.csv").readBytes()
        val out = BatchImport.run(listOf(SelectedFile("a.csv", h), SelectedFile("copia.csv", h)), emptyMap(), TestSupport.MADRID, { "x" })
        assertTrue(out[0] is BatchOutcome.Processed)
        assertEquals("a.csv", (out[1] as BatchOutcome.AlreadyPresent).existingName)
        val sha = (out[0] as BatchOutcome.Processed).result.sha256
        val again = BatchImport.run(listOf(SelectedFile("b.csv", h)), mapOf(sha to "a.csv"), TestSupport.MADRID, { "y" })
        assertTrue(again.single() is BatchOutcome.AlreadyPresent)
        // Reexportación del mismo caso: ZIP distinto (no es duplicado por hash) → se procesa; la revisión es posterior
        val r1 = TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes()
        val r2 = TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007_reexport.zip").readBytes()
        val both = BatchImport.run(listOf(SelectedFile("c1.zip", r1), SelectedFile("c2.zip", r2)), emptyMap(), TestSupport.MADRID, { "z" })
        assertTrue(both.all { it is BatchOutcome.Processed })
    }

    @Test fun importSummaryUsesTheReportWordsAndExplainsFailures() {
        val zone = java.time.ZoneId.of("Europe/Madrid")
        val ok = com.alexisgordr.cellcase.core.report.ReportTextsEs.importSummary(run("SYNTHETIC_ICD-topology.zip"), zone)
        assertTrue(ok.first(), ok.first().endsWith("Importado"))
        assertTrue(ok.any { it.contains("192 handovers") })
        assertTrue(ok.any { it.contains("SHA256SUMS verificado") })
        val bad = com.alexisgordr.cellcase.core.report.ReportTextsEs.importSummary(run("SYNTHETIC_hostile_truncated.zip"), zone)
        assertTrue(bad.first(), bad.first().endsWith("ZIP dañado"))
        assertTrue(bad.size >= 2)
    }

    @Test fun repeatedImportOfTheSameFileIsIdempotent() {
        val a = run("SYNTHETIC_ICD-stable-site.zip"); val b = run("SYNTHETIC_ICD-stable-site.zip")
        assertEquals(a.sha256, b.sha256); assertEquals(a.counts, b.counts); assertEquals(a.state, b.state)
    }
}
