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

    /** ZIP con un EOCD que declara un directorio central de [cd] bytes en [offset] (archivo disperso: no ocupa disco). */
    private fun fakeEocdZip(cd: Long, offset: Long = 4, fileBytes: Long = 4 + cd): File {
        val f = File.createTempFile("eocd", ".zip").also { it.deleteOnExit() }
        java.io.RandomAccessFile(f, "rw").use { r ->
            r.write(byteArrayOf(0x50, 0x4b, 0x03, 0x04)); r.setLength(fileBytes); r.seek(fileBytes)
            val e = ByteArray(22); e[0] = 0x50; e[1] = 0x4b; e[2] = 0x05; e[3] = 0x06; e[10] = 1
            for (k in 0..3) { e[12 + k] = (cd shr (8 * k)).toByte(); e[16 + k] = (offset shr (8 * k)).toByte() }
            r.write(e)
        }
        return f
    }

    /** Revisión 22/10 (A1): leer el directorio central de un archivo no carga el archivo ni reserva lo que declara. */
    @Test fun centralDirectoryFromDiskMatchesTheInMemoryReaderAndIsBounded() {
        for (f in File(TestSupport.root, "samples/synthetic").walk().filter { it.isFile && (it.name.endsWith(".zip") || it.name.endsWith(".icdbackup")) }) {
            val mem = try { SafeZip.centralDirectoryNames(f.readBytes()) } catch (e: ImportRejected) { null }
            val disk = try { SafeZip.centralDirectoryNames(f) } catch (e: ImportRejected) { null }
            assertEquals(f.name, mem, disk)
        }
        // Directorio central declarado de 100 MiB: rechazado por tamaño sin leerlo
        val big = fakeEocdZip(100L * 1024 * 1024)
        try { SafeZip.centralDirectoryNames(big); fail("debía rechazarlo") } catch (e: ImportRejected) {
            assertEquals(com.alexisgordr.cellcase.core.importing.RejectKind.ZIP_SECURITY, e.kind)
        }
        // Directorio central que apunta más allá del EOCD: dañado
        val out = fakeEocdZip(cd = 1000, offset = 4, fileBytes = 500)
        try { SafeZip.centralDirectoryNames(out); fail("debía rechazarlo") } catch (e: ImportRejected) {
            assertEquals(com.alexisgordr.cellcase.core.importing.RejectKind.DAMAGED_ZIP, e.kind)
        }
        // La importación por archivo y la lectura directa de una copia no lanzan: dan un estado
        val r = com.alexisgordr.cellcase.core.importing.ImportPipeline.processFile(big, "x.zip", "x", null)
        assertTrue(r.state.name, r.state in setOf(com.alexisgordr.cellcase.core.importing.ImportState.TOO_LARGE,
            com.alexisgordr.cellcase.core.importing.ImportState.ZIP_SECURITY, com.alexisgordr.cellcase.core.importing.ImportState.DAMAGED_ZIP))
        assertEquals(com.alexisgordr.cellcase.core.importing.ImportState.ZIP_SECURITY,
            com.alexisgordr.cellcase.core.backup.IcdBackupReader.read(big, "x.icdbackup", "x", null).state)
        assertTrue(!com.alexisgordr.cellcase.core.backup.IcdBackupReader.isBackup(big))
        listOf(big, out).forEach { it.delete() }
    }
}
