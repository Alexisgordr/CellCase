package com.alexisgordr.cellcase

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.importing.Checkpoint
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.backupParts
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.data.CaseRepository
import com.alexisgordr.cellcase.data.FileOutcome
import com.alexisgordr.cellcase.logic.ImportPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId

/**
 * Importación de una copia de seguridad de ICdetector 3.0.0 en el teléfono (D-054): copia privada, base temporal
 * en la caché, partes reconstruidas, perfil local y los cuatro informes. Datos SINTÉTICOS (samples/synthetic/icd30,
 * empaquetados como recursos de la prueba). Se ejecuta en un dispositivo o emulador: `./gradlew :app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class BackupImportInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var repo: CaseRepository
    private val created = ArrayList<String>()

    @Before fun setUp() { repo = CaseRepository(context) }

    @After fun tearDown() { created.forEach { repo.deleteCase(it) } }

    /** Copia un recurso de la prueba a la caché y devuelve su Uri (como lo daría el selector de archivos). */
    private fun asset(name: String): Uri {
        val test = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().context
        val f = File(context.cacheDir, "prueba-$name")
        test.assets.open(name).use { i -> f.outputStream().use { i.copyTo(it) } }
        return Uri.fromFile(f)
    }

    private fun newCase() = repo.createCase("Copia 3.0", null, DeviceSource.NOT_DECLARED, "SYNTHETIC", ZoneId.of("Europe/Madrid"), true,
        DataOrigin.SYNTHETIC, null).also { created += it.id }

    @Test fun backupIsImportedAsOneOriginalWithItsParts() {
        val case = newCase()
        val report = repo.importFiles(case.id, listOf(asset("SYNTHETIC_icd30_fresh.icdbackup")), Checkpoint.NONE) {}
        val outcome = report.outcomes.single() as FileOutcome.Processed
        assertEquals(ExportKind.BACKUP, outcome.result.kind)
        assertEquals(ImportState.IMPORTED, outcome.result.state)
        val kinds = outcome.result.backupParts.map { it.kind }.toSet()
        assertTrue(kinds.toString(), kinds.containsAll(setOf(ExportKind.HISTORY, ExportKind.SERVICE_STATE, ExportKind.FORENSIC_CASE)))
        assertTrue(repo.verifyOriginal(outcome.record))
        // la base temporal no queda en la caché
        assertTrue(ImportPolicy.backupTempDir(context.cacheDir).listFiles().orEmpty().isEmpty())
    }

    @Test fun theSameBackupTwiceIsNotCountedTwice() {
        val case = newCase()
        val uri = asset("SYNTHETIC_icd30_fresh.icdbackup")
        repo.importFiles(case.id, listOf(uri), Checkpoint.NONE) {}
        val again = repo.importFiles(case.id, listOf(uri), Checkpoint.NONE) {}
        assertTrue(again.outcomes.single() is FileOutcome.AlreadyPresent)
        assertEquals(1, repo.store.listImports(case.id).size)
    }

    @Test fun snapshotHasTheLocalProfileAndTheFourReports() {
        val case = newCase()
        repo.importFiles(case.id, listOf(asset("SYNTHETIC_icd30_fresh.icdbackup")), Checkpoint.NONE) {}
        val snap = repo.snapshot(case.id, Checkpoint.NONE)
        val profile = snap.analysis.localProfile
        assertNotNull(profile)
        assertTrue(profile!!.evaluated > 0)
        assertEquals(mapOf("MATURE" to 6), profile.references)
        for (lang in Language.values()) for (mode in listOf(ReportMode.FULL, ReportMode.EXPLAINED)) {
            val rec = repo.generateReport(snap, mode, lang)
            assertTrue("$lang $mode", rec.pages > 0)
        }
        assertEquals(4, repo.store.listReports(case.id).size)
        assertTrue(ImportPolicy.backupTempDir(context.cacheDir).listFiles().orEmpty().isEmpty())
    }

    @Test fun backupAndExportsGiveTheSameAnalysis() {
        val a = newCase(); val b = newCase()
        repo.importFiles(a.id, listOf(asset("SYNTHETIC_icd30_fresh.icdbackup")), Checkpoint.NONE) {}
        val exports = listOf("SYNTHETIC_icd30_history.csv", "SYNTHETIC_icd30_service_state.csv", "SYNTHETIC_icd30_topology.zip",
            "SYNTHETIC_icd30_geometry.zip", "SYNTHETIC_icd30_stable_site.zip", "SYNTHETIC_icd30_ICD-2026-10-22-0001.zip",
            "SYNTHETIC_icd30_ICD-OBS-2026-10-23-0002.zip", "SYNTHETIC_icd30_ICD-2026-10-26-0003.zip")
        repo.importFiles(b.id, exports.map { asset(it) }, Checkpoint.NONE) {}
        val sa = repo.snapshot(a.id, Checkpoint.NONE).analysis; val sb = repo.snapshot(b.id, Checkpoint.NONE).analysis
        assertEquals(sb.history!!.import.observations.size, sa.history!!.import.observations.size)
        assertEquals(sb.episodes.episodes.size, sa.episodes.episodes.size)
        assertEquals(sb.assessment, sa.assessment)
    }

    @Test fun anEmptyBackupIsKeptAndExplained() {
        val case = newCase()
        val outcome = repo.importFiles(case.id, listOf(asset("SYNTHETIC_icd30_empty.icdbackup")), Checkpoint.NONE) {}.outcomes.single() as FileOutcome.Processed
        assertEquals(ExportKind.BACKUP, outcome.result.kind)
        assertTrue(outcome.result.backupParts.isEmpty())
        assertTrue(outcome.result.issues.any { it.code == "BACKUP_EMPTY" })
    }
}
