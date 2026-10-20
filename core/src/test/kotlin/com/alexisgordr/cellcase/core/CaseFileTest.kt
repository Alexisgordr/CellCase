package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.casefile.AnalysisConfig
import com.alexisgordr.cellcase.core.casefile.CaseStorage
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import com.alexisgordr.cellcase.core.casefile.DeviceCrossCheck
import com.alexisgordr.cellcase.core.casefile.DeviceDeclaration
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.casefile.DiscrepancyCode
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

class CaseFileTest {
    private val pixel = DetectedEnvironment("Google", "Pixel 9a", "16", 36, "Europe/Madrid", Instant.parse("2026-10-02T08:00:00Z"))
    private fun forensic() = ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes(), "caso.zip", "i1", TestSupport.MADRID)

    @Test fun detectedLabelDoesNotRepeatManufacturer() {
        assertEquals("Google Pixel 9a · Android 16", pixel.deviceLabel)
        assertEquals("Samsung SM-S921B · Android 15", pixel.copy(manufacturer = "Samsung", model = "SM-S921B", androidRelease = "15").deviceLabel)
    }

    @Test fun declaringAnotherDeviceThanTheCurrentOneIsKeptAndWarnedOnlyOnConflict() {
        val f = forensic()
        val fileDevice = DeviceCrossCheck.fileDevices(listOf(f)).single().device!!
        // Declarado «otro dispositivo» que coincide con el del archivo: sin aviso de dispositivo
        val okOther = DeviceCrossCheck.check(pixel, DeviceDeclaration(DeviceSource.OTHER_DEVICE, fileDevice), "Europe/Madrid", listOf(f))
        assertTrue(okOther.none { it.code == DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DECLARED })
        // Declarado «este móvil» (Pixel 9a) pero el archivo nombra otro modelo: aviso, sin corregir nada
        val thisDev = DeviceCrossCheck.check(pixel, DeviceDeclaration(DeviceSource.THIS_DEVICE, pixel.deviceLabel), "Europe/Madrid", listOf(f))
        assertTrue(thisDev.any { it.code == DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DETECTED })
        // Declarado otro distinto del archivo
        val wrongOther = DeviceCrossCheck.check(pixel, DeviceDeclaration(DeviceSource.OTHER_DEVICE, "Nokia 3310"), "Europe/Madrid", listOf(f))
        assertTrue(wrongOther.any { it.code == DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DECLARED })
    }

    @Test fun zoneDifferenceIsOnlyAWarning() {
        val d = DeviceCrossCheck.check(pixel, DeviceDeclaration(DeviceSource.NOT_DECLARED, ""), "Atlantic/Canary", emptyList())
        assertEquals(listOf(DiscrepancyCode.ZONE_DIFFERS_FROM_CURRENT), d.map { it.code })
    }

    @Test fun configFingerprintChangesWithZonePeriodAndFiles() {
        val base = AnalysisConfig("Europe/Madrid", true, null, fileHashes = listOf("a", "b"))
        assertEquals(base.fingerprint(), base.copy(fileHashes = listOf("b", "a")).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(zoneId = "Atlantic/Canary").fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(zoneConfirmed = false).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(period = PeriodOfInterest(Instant.EPOCH, Instant.EPOCH.plusSeconds(60), "x")).fingerprint())
        assertNotEquals(base.fingerprint(), base.copy(fileHashes = listOf("a")).fingerprint())
    }

    @Test fun twoPhaseDeletionOnlyTouchesPrivateStorageAndResumes() {
        val root = Files.createTempDirectory("cc").toFile()
        val outside = File(root.parentFile, "seleccionado-${System.nanoTime()}.csv").apply { writeText("original del usuario") }
        try {
            val st = CaseStorage(root)
            File(st.originals("case-1"), "abc").apply { writeText("copia"); setReadOnly() }
            File(st.reports("case-1"), "r.pdf").writeText("pdf")
            File(st.originals("case-2"), "def").writeText("otro")
            assertTrue(st.sizeOf("case-1") > 0)
            // Interrupción tras mover a la papelera: queda pendiente y se completa después
            assertTrue(st.moveToTrash("case-1"))
            assertFalse(st.caseDir("case-1").exists())
            assertEquals(listOf("case-1"), st.pendingTrash())
            assertTrue(st.purge("case-1"))
            assertTrue(st.purge("case-1")) // idempotente
            assertTrue(st.pendingTrash().isEmpty())
            assertTrue(File(st.originals("case-2"), "def").exists())
            assertTrue(outside.exists())
        } finally { root.deleteRecursively(); outside.delete() }
    }

    @Test(expected = IllegalArgumentException::class) fun pathTraversalIdsAreRejected() {
        CaseStorage(Files.createTempDirectory("cc").toFile()).caseDir("../x")
    }
}
