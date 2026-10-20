package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.FormatDetector
import com.alexisgordr.cellcase.core.importing.ImportRejected
import com.alexisgordr.cellcase.core.importing.SafeZip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

class DetectionAndZipTest {

    private fun detect(f: File): ExportKind {
        val bytes = f.readBytes()
        return if (FormatDetector.isZip(bytes.copyOf(4))) {
            val entries = SafeZip.readAll(bytes)
            FormatDetector.detectZip(entries.keys).kind
        } else FormatDetector.detectCsv(bytes.decodeToString().lineSequence().first()).kind
    }

    @Test fun everySyntheticSampleIsDetectedByContent() {
        val expected = mapOf(
            "SYNTHETIC_icdetector_history.csv" to ExportKind.HISTORY,
            "SYNTHETIC_icdetector_history_edge_cases.csv" to ExportKind.HISTORY,
            "SYNTHETIC_icdetector_history_v21_23cols.csv" to ExportKind.HISTORY,
            "SYNTHETIC_icdetector_service_state.csv" to ExportKind.SERVICE_STATE,
            "SYNTHETIC_icdetector_service_state_empty.csv" to ExportKind.SERVICE_STATE,
            "SYNTHETIC_ICD-2026-09-27-0007.zip" to ExportKind.FORENSIC_CASE,
            "SYNTHETIC_ICD-2026-09-27-0007_reexport.zip" to ExportKind.FORENSIC_CASE,
            "SYNTHETIC_ICD-topology.zip" to ExportKind.TOPOLOGY,
            "SYNTHETIC_ICD-geometry.zip" to ExportKind.GEOMETRY,
            "SYNTHETIC_ICD-stable-site.zip" to ExportKind.STABLE_SITE
        )
        for ((name, kind) in expected) assertEquals(name, kind, detect(TestSupport.sample(name)))
    }

    @Test fun renamedFilesAreStillDetected() {
        val tmp = File.createTempFile("renombrado", ".txt")
        TestSupport.sample("SYNTHETIC_ICD-topology.zip").copyTo(tmp, overwrite = true)
        assertEquals(ExportKind.TOPOLOGY, detect(tmp))
        tmp.delete()
    }

    @Test fun cellsCsvAloneDoesNotDecideTheFormat() {
        assertEquals(ExportKind.UNKNOWN, FormatDetector.detectZip(setOf("cells.csv", "metadata.json")).kind)
    }

    private fun rejected(name: String): String {
        val bytes = TestSupport.sample(name).readBytes()
        try { SafeZip.readAll(bytes); fail("$name no se rechazó") } catch (e: ImportRejected) { return e.message!! }
        error("inalcanzable")
    }

    @Test fun pathTraversalIsRejected() { assertTrue(rejected("SYNTHETIC_hostile_path_traversal.zip").contains("..")) }
    @Test fun zipBombIsRejected() { rejected("SYNTHETIC_hostile_zip_bomb_64MiB.zip").let { assertTrue(it, it.contains("bomba") || it.contains("declara")) } }
    @Test fun tooManyEntriesIsRejected() { assertTrue(rejected("SYNTHETIC_hostile_many_entries.zip").contains("entradas")) }
    @Test fun truncatedZipIsRejected() { rejected("SYNTHETIC_hostile_truncated.zip") }

    @Test fun entryNameRules() {
        assertNull(SafeZip.validateEntryName("timeline.csv"))
        for (bad in listOf("../a", "/etc/passwd", "a\\b", "C:x", "dir/", "sub/a.csv", "")) {
            assertTrue(bad, SafeZip.validateEntryName(bad) != null)
        }
    }
}
