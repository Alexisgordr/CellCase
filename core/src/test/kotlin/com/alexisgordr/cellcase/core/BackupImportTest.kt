package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.backup.BackupLimits
import com.alexisgordr.cellcase.core.backup.IcdBackupReader
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.CaseBudget
import com.alexisgordr.cellcase.core.importing.Checkpoint
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.IntegrityStatus
import com.alexisgordr.cellcase.core.importing.SelectedFile
import com.alexisgordr.cellcase.core.importing.backupParts
import com.alexisgordr.cellcase.core.importing.leaves
import com.alexisgordr.cellcase.core.model.Severity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Copia de seguridad de ICdetector 3.0.0 importada en CellCase (backup/IcdBackup.kt, docs/backup-format.md). */
class BackupImportTest {
    private val base = TestSupport.sample("icd30/SYNTHETIC_icd30_fresh.icdbackup").parentFile

    private fun run(files: List<File>): List<ImportResult> {
        var n = 0
        return BatchImport.run(files.map { SelectedFile(it.name, it.readBytes()) }, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
            .mapNotNull { (it as? BatchOutcome.Processed)?.result }
    }
    private fun analyze(r: List<ImportResult>) = CaseAnalyzer.analyze(r, TestSupport.MADRID, null)
    private val exports get() = base.listFiles()!!.filter { it.name.startsWith("SYNTHETIC_icd30_") && !it.name.endsWith(".icdbackup") }.sortedBy { it.name }
    private fun backup(v: String = "fresh") = File(base, "SYNTHETIC_icd30_$v.icdbackup")

    /** Lo que debe coincidir entre dos análisis del mismo teléfono. */
    private fun facts(a: CaseAnalysis): List<Any?> = listOf<Any?>(
        a.history?.import?.observations?.size, a.history?.import?.observations?.map { it.time.instantOrNull to it.identity.joinKey }?.toSet(),
        a.episodes.episodes.map { Triple(it.number, it.start, it.priority) }, a.assessment.overall, a.assessment.perEpisode,
        a.serviceEvents.size, a.forensicCases.map { it.second.meta.caseCode ?: "" }.sorted(), a.topology?.primary?.routes?.size,
        a.geometry?.primary?.cells?.map { it.raw to it.familiarity }, a.stableSite?.primary?.sites?.map { it.key to it.featureState },
        a.cross?.findings?.groupingBy { it.check to it.outcome }?.eachCount(), a.localProfile?.byLevel)

    @Test fun backupImportsAsOneFileWithOnePartPerExport() {
        val r = run(listOf(backup())).single()
        assertEquals(ExportKind.BACKUP, r.kind); assertEquals(ImportState.IMPORTED, r.state)
        assertEquals(IntegrityStatus.VERIFIED, r.integrity?.status)
        val info = (r.data as ImportedData.Backup).info
        assertEquals("3.0.0+f5173a8", info.appVersion); assertEquals(25, info.schemaVersion); assertEquals(25, info.userVersion); assertTrue(info.integrityVerified)
        assertEquals(listOf("app_language", "location_mode", "proxy_enabled", "latency_detection_enabled", "legacy_network_alert_mode", "stable_site_silent_notices"), info.settingsKeys)
        assertEquals(mapOf("MATURE" to 6), info.profileReferences)
        assertEquals("SHADOW", info.profileModes.single().second)
        val kinds = r.backupParts.map { it.kind }
        assertEquals(listOf(ExportKind.HISTORY, ExportKind.SERVICE_STATE, ExportKind.TOPOLOGY, ExportKind.GEOMETRY, ExportKind.STABLE_SITE), kinds.take(5))
        assertEquals(3, kinds.count { it == ExportKind.FORENSIC_CASE })
        assertTrue(r.backupParts.all { it.imported })
        assertTrue(r.backupParts.all { it.fileName.startsWith("SYNTHETIC_icd30_fresh.icdbackup › ") })
        assertEquals(r.backupParts.map { it.importId }.toSet().size, r.backupParts.size)
        // las referencias citan la exportación reconstruida
        val h = (r.backupParts.first().data as ImportedData.History).data
        assertEquals("SYNTHETIC_icd30_fresh.icdbackup › history.csv:2", h.observations.first().provenance.shortRef())
        assertTrue(r.issues.any { it.code == "BACKUP_RECONSTRUCTED" })
        assertEquals(listOf(r) , listOf(r).filter { it.kind == ExportKind.BACKUP })
        assertEquals(r.backupParts, listOf(r).leaves())
    }

    @Test fun backupGivesTheSameAnalysisAsTheExportsOfTheSamePhone() {
        val fromExports = analyze(run(exports))
        val fromBackup = analyze(run(listOf(backup())))
        assertEquals(facts(fromExports), facts(fromBackup))
    }

    @Test fun importingBackupAndExportsTogetherCountsEverythingOnceInAnyOrder() {
        val single = facts(analyze(run(listOf(backup()))))
        val a = analyze(run(listOf(backup()) + exports))
        val b = analyze(run(exports.reversed() + backup()))
        assertEquals(single, facts(a))
        assertEquals(facts(a), facts(b))
        assertTrue(a.history!!.matches.all { it.kind == com.alexisgordr.cellcase.core.analysis.RowMatch.EXACT })
    }

    @Test fun upgradedPhoneGivesTheSameEpisodesAndAssessment() {
        val f = analyze(run(listOf(backup("fresh")))); val u = analyze(run(listOf(backup("upgraded"))))
        assertEquals(f.episodes.episodes.map { it.start to it.mainPattern }, u.episodes.episodes.map { it.start to it.mainPattern })
        assertEquals(f.assessment.overall, u.assessment.overall)
        assertEquals(f.history!!.import.observations.size, u.history!!.import.observations.size)
    }

    @Test fun emptyBackupIsImportedWithoutPartsAndSaysSo() {
        val r = run(listOf(backup("empty"))).single()
        assertEquals(ImportState.IMPORTED, r.state)
        assertTrue(r.backupParts.isEmpty())
        assertTrue(r.issues.any { it.code == "BACKUP_EMPTY" && it.severity == Severity.WARNING })
        assertEquals(null, analyze(listOf(r)).history)
    }

    // ── copias manipuladas o dañadas ──

    private fun entries(v: String = "fresh"): LinkedHashMap<String, ByteArray> = ZipFile(backup(v)).use { z ->
        LinkedHashMap<String, ByteArray>().apply { z.entries().asSequence().forEach { put(it.name, z.getInputStream(it).readBytes()) } }
    }
    private fun zip(e: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bo ->
        ZipOutputStream(bo).use { z -> e.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }.toByteArray()
    private fun withManifest(edit: (String) -> String) = entries().apply { put("manifest.json", edit(String(getValue("manifest.json"))).toByteArray()) }
    private fun import(bytes: ByteArray, name: String = "copia.icdbackup", budget: CaseBudget? = null) = ImportPipeline.process(bytes, name, "imp", TestSupport.MADRID, budget = budget)

    @Test fun shaMismatchIsImportedButFlagged() {
        val r = import(zip(withManifest { it.replace(Regex("\"databaseSha256\": \"[0-9a-f]+\""), "\"databaseSha256\": \"${"0".repeat(64)}\"") }))
        assertEquals(ImportState.IMPORTED, r.state)
        assertEquals(IntegrityStatus.MISMATCH, r.integrity?.status)
        assertTrue(r.issues.any { it.code == "BACKUP_SHA256_MISMATCH" && it.severity == Severity.ERROR })
        assertFalse((r.data as ImportedData.Backup).info.integrityVerified)
    }

    @Test fun incompatibleManifestsAreRejectedWithTheirReason() {
        fun state(edit: (String) -> String) = import(zip(withManifest(edit)))
        assertEquals(ImportState.INCOMPATIBLE, state { it.replace("\"format\": 1", "\"format\": 2") }.state)
        val beta = state { it.replace("\"schemaVersion\": 25", "\"schemaVersion\": 24") }
        assertEquals(ImportState.INCOMPATIBLE, beta.state); assertTrue(beta.messageText.toString().contains("24"))
        // 26 (ICdetector 3.1.0) se lee; aquí el manifiesto no cuadra con la base (esquema 25) y se avisa
        val s26 = state { it.replace("\"schemaVersion\": 25", "\"schemaVersion\": 26") }
        assertEquals(ImportState.IMPORTED, s26.state); assertTrue(s26.issues.any { it.code == "BACKUP_USER_VERSION" })
        val newer = state { it.replace("\"schemaVersion\": 25", "\"schemaVersion\": 27") }
        assertEquals(ImportState.INCOMPATIBLE, newer.state); assertTrue(newer.messageText.toString().contains("27"))
        assertEquals(ImportState.INCOMPATIBLE, state { it.replace("\"schemaVersion\": 25", "\"schemaVersion\": 19") }.state)
        assertEquals(ImportState.UNRECOGNIZED, state { it.replace("com.alexisgordr.icdetector", "com.otra.app") }.state)
        assertEquals(ImportState.INCOMPATIBLE, state { "no es json" }.state)
    }

    @Test fun zipWithoutDatabaseIsNotABackup() {
        val r = import(zip(entries().apply { remove("icdetector_history.db") }))
        assertEquals(ExportKind.UNKNOWN, r.kind); assertEquals(ImportState.UNRECOGNIZED, r.state)
    }

    @Test fun unknownEntriesAreReportedAndNotRead() {
        val r = import(zip(entries().apply { put("extra.txt", "hola".toByteArray()) }))
        assertEquals(ImportState.IMPORTED, r.state)
        assertTrue(r.issues.any { it.code == "BACKUP_UNKNOWN_ENTRIES" })
    }

    @Test fun duplicateAndUnsafeEntryNamesAreRejected() {
        // «settings.jsoX» tiene la misma longitud que «manifest.json»: se renombra en los bytes para duplicar la entrada
        val e = entries(); val bytes = zip(LinkedHashMap<String, ByteArray>().apply { putAll(e.filterKeys { it != "settings.json" }); put("settings.jsoX", e.getValue("settings.json")) })
        val s = String(bytes, Charsets.ISO_8859_1).replace("settings.jsoX", "manifest.json").toByteArray(Charsets.ISO_8859_1)
        val f = File.createTempFile("dup-", ".icdbackup").also { it.writeBytes(s); it.deleteOnExit() }
        val dup = IcdBackupReader.read(f, "dup.icdbackup", "d", TestSupport.MADRID)
        assertEquals(ImportState.ZIP_SECURITY, dup.state)
        val traversal = import(zip(entries().apply { put("../fuera.txt", ByteArray(1)) }))
        assertEquals(ImportState.ZIP_SECURITY, traversal.state)
    }

    @Test fun truncatedBackupIsDamaged() {
        val b = backup().readBytes()
        val f = File.createTempFile("trunc-", ".icdbackup").also { it.writeBytes(b.copyOf(b.size / 2)); it.deleteOnExit() }
        val r = ImportPipeline.processFile(f, "trunc.icdbackup", "t", TestSupport.MADRID)
        assertTrue(r.state == ImportState.DAMAGED_ZIP || r.state == ImportState.UNRECOGNIZED)
    }

    @Test fun corruptDatabaseIsAReadErrorNotACrash() {
        val e = entries(); val garbage = ByteArray(8192) { (it * 31).toByte() }
        e["icdetector_history.db"] = garbage
        val sha = com.alexisgordr.cellcase.core.importing.Hashing.sha256Hex(garbage)
        e["manifest.json"] = String(e.getValue("manifest.json")).replace(Regex("\"databaseSha256\": \"[0-9a-f]+\""), "\"databaseSha256\": \"$sha\"")
            .replace(Regex("\"databaseBytes\": \\d+"), "\"databaseBytes\": 8192").toByteArray()
        val r = import(zip(e))
        assertEquals(ImportState.READ_ERROR, r.state)
    }

    @Test fun limitsOnSizeRatioRowsAndCaseBudget() {
        val f = backup()
        val tmp = Files.createTempDirectory("lim").toFile()
        assertEquals(ImportState.TOO_LARGE, IcdBackupReader.read(f, "c", "c", TestSupport.MADRID, tempDir = tmp, limits = BackupLimits(maxBackupBytes = 1000)).state)
        assertEquals(ImportState.ZIP_SECURITY, IcdBackupReader.read(f, "c", "c", TestSupport.MADRID, tempDir = tmp, limits = BackupLimits(maxRatio = 1)).state)
        assertEquals(ImportState.ZIP_SECURITY, IcdBackupReader.read(f, "c", "c", TestSupport.MADRID, tempDir = tmp, limits = BackupLimits(maxDatabaseBytes = 4096)).state)
        // más filas que el límite: esa parte no se reconstruye; las demás sí
        val rows = IcdBackupReader.read(f, "c", "c", TestSupport.MADRID, tempDir = tmp, limits = BackupLimits(maxRows = 100))
        assertEquals(ImportState.IMPORTED, rows.state)
        assertEquals(ImportState.TOO_LARGE, rows.backupParts.single { it.kind == ExportKind.HISTORY }.state)
        assertTrue(rows.backupParts.single { it.kind == ExportKind.SERVICE_STATE }.imported)
        // presupuesto de historial del expediente: el mismo que con un CSV
        val budget = IcdBackupReader.read(f, "c", "c", TestSupport.MADRID, budget = CaseBudget(maxHistoryRows = 100), tempDir = tmp)
        assertEquals(ImportState.TOO_LARGE, budget.backupParts.single { it.kind == ExportKind.HISTORY }.state)
        assertTrue(tmp.listFiles()!!.isEmpty())
    }

    /** Revisión, fallo 6: un caso forense con más payload del que cabe en un ZIP no se carga; el resto de la copia sí. */
    @Test fun oversizedForensicCaseIsSkippedAndTheRestImports() {
        val tmp = Files.createTempDirectory("big").toFile()
        val dbFile = File(tmp, "base.db").also { f -> ZipFile(backup()).use { z -> z.getInputStream(z.getEntry("icdetector_history.db")).use { i -> f.outputStream().use { i.copyTo(it) } } } }
        val sizes = com.alexisgordr.cellcase.core.backup.SqliteFile(dbFile).use { db ->
            com.alexisgordr.cellcase.core.backup.Icd30Exports(db, "3.0.0", 0L).forensicCases()!!.associate { it.caseCode to it.payloadBytes }
        }
        dbFile.delete()
        assertTrue(sizes.toString(), sizes.size >= 2)
        val sorted = sizes.values.sortedDescending()
        assertTrue("el caso más grande es único", sorted[0] > sorted[1])
        val big = sizes.entries.single { it.value == sorted[0] }.key
        val r = IcdBackupReader.read(backup(), "c", "c", TestSupport.MADRID, tempDir = tmp, limits = BackupLimits(maxForensicPayloadBytes = sorted[1]))
        assertEquals(ImportState.IMPORTED, r.state)
        val cases = r.backupParts.filter { it.kind == ExportKind.FORENSIC_CASE }
        assertEquals(sizes.size, cases.size)
        assertEquals(ImportState.TOO_LARGE, cases.single { it.fileName.endsWith("$big.zip") }.state)
        assertTrue(cases.filter { !it.fileName.endsWith("$big.zip") }.all { it.imported })
        assertTrue(r.backupParts.single { it.kind == ExportKind.HISTORY }.imported)
        assertTrue(r.issues.any { it.code == "BACKUP_PART_NOT_IMPORTED" })
        // Con el límite por defecto (el de un ZIP exportado) se importan todos
        assertTrue(IcdBackupReader.read(backup(), "c", "c", TestSupport.MADRID, tempDir = tmp).backupParts.filter { it.kind == ExportKind.FORENSIC_CASE }.all { it.imported })
        assertTrue(tmp.listFiles()!!.isEmpty())
    }

    @Test fun forensicCasesAreBuiltOnlyWhenAsked() {
        val tmp = Files.createTempDirectory("lazy").toFile()
        val dbFile = File(tmp, "base.db").also { f -> ZipFile(backup()).use { z -> z.getInputStream(z.getEntry("icdetector_history.db")).use { i -> f.outputStream().use { i.copyTo(it) } } } }
        com.alexisgordr.cellcase.core.backup.SqliteFile(dbFile).use { db ->
            val cases = com.alexisgordr.cellcase.core.backup.Icd30Exports(db, "3.0.0", 0L, maxRows = 3).forensicCases()!!
            // listar no lee las muestras (el límite de filas solo salta al construir un caso con más de 3)
            val many = cases.first { it.sampleRows > 3 }
            try { many.files; throw AssertionError("debía superar el límite de filas") } catch (_: com.alexisgordr.cellcase.core.backup.Icd30Exports.TooManyRows) {}
        }
        tmp.deleteRecursively()
    }

    @Test fun temporaryDatabaseIsDeletedAlsoWhenCancelled() {
        val tmp = Files.createTempDirectory("cancel").toFile()
        var calls = 0
        val cp = Checkpoint { if (++calls > 40) throw CancellationException("cancelado") }
        try { IcdBackupReader.read(backup(), "c", "c", TestSupport.MADRID, checkpoint = cp, tempDir = tmp); throw AssertionError("debía cancelarse") }
        catch (_: CancellationException) {}
        assertTrue(tmp.listFiles()!!.isEmpty())
        IcdBackupReader.read(backup(), "c", "c", TestSupport.MADRID, tempDir = tmp)
        assertTrue(tmp.listFiles()!!.isEmpty())
    }

    @Test fun fileOnDiskAndBytesInMemoryGiveTheSameResult() {
        val a = ImportPipeline.processFile(backup(), "c.icdbackup", "x", TestSupport.MADRID)
        val b = ImportPipeline.process(backup().readBytes(), "c.icdbackup", "x", TestSupport.MADRID)
        assertEquals(a.sha256, b.sha256); assertEquals(a.counts, b.counts)
        assertEquals(a.backupParts.map { it.sha256 }, b.backupParts.map { it.sha256 })
        // una copia de más de 6 MiB se admite (los demás archivos siguen limitados a 6 MiB)
        assertTrue(com.alexisgordr.cellcase.core.importing.ImportLimits.MAX_FILE_BYTES < BackupLimits().maxBackupBytes)
    }

    @Test fun reportsListTheBackupAndItsPartsInBothLanguages() {
        val results = run(listOf(backup()))
        val a = analyze(results)
        for (lang in com.alexisgordr.cellcase.core.i18n.Language.values()) for (mode in com.alexisgordr.cellcase.core.report.ReportMode.values()) {
            val ctx = com.alexisgordr.cellcase.core.report.TextContext(com.alexisgordr.cellcase.core.report.CaseInfo("x", "Copia", java.time.Instant.EPOCH, "Sintético",
                TestSupport.MADRID, com.alexisgordr.cellcase.core.report.DataOrigin.SYNTHETIC, null, null), results,
                com.alexisgordr.cellcase.core.report.ReportOptions(generatedAt = java.time.Instant.EPOCH, appVersion = "t", mode = mode))
            val doc = com.alexisgordr.cellcase.core.report.ReportBuilder(com.alexisgordr.cellcase.core.report.reportTexts(lang)).build(ctx, a)
            val text = doc.blocks.joinToString("\n") { it.toString() }
            if (mode == com.alexisgordr.cellcase.core.report.ReportMode.FULL) {
                assertTrue("$lang: bloque de la copia", text.contains(com.alexisgordr.cellcase.core.i18n.Messages.format(lang, "backup.report.title", emptyList())))
                assertTrue("$lang: partes en la tabla de archivos", text.contains("SYNTHETIC_icd30_fresh.icdbackup › history.csv"))
                assertTrue("$lang: versión del lector", text.contains("backup-icd/1.0.0"))
            }
            assertFalse("$lang $mode: texto sin traducir", Regex("\\b(import\\.backup|backup\\.report|profile\\.(level|lay|k|v|col|abstain|mode|maturity|title|intro))\\.?\\w").containsMatchIn(text))
            com.alexisgordr.cellcase.core.report.ReportBuilder(com.alexisgordr.cellcase.core.report.reportTexts(lang)).writePdf(ctx, a, ByteArrayOutputStream())
        }
    }
}
