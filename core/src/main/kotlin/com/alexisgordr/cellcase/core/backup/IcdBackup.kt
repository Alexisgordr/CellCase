package com.alexisgordr.cellcase.core.backup

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg
import com.alexisgordr.cellcase.core.importing.CaseBudget
import com.alexisgordr.cellcase.core.importing.Checkpoint
import com.alexisgordr.cellcase.core.importing.EntryInfo
import com.alexisgordr.cellcase.core.importing.EntryRole
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportLimits
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportRejected
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.IntegrityCheck
import com.alexisgordr.cellcase.core.importing.IntegrityStatus
import com.alexisgordr.cellcase.core.importing.PeriodInfo
import com.alexisgordr.cellcase.core.importing.READERS_VERSION
import com.alexisgordr.cellcase.core.importing.RejectKind
import com.alexisgordr.cellcase.core.importing.SafeZip
import com.alexisgordr.cellcase.core.importing.ZipLimits
import com.alexisgordr.cellcase.core.model.IssueCollector
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.time.CaptureTime
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.io.File
import java.time.Instant
import java.util.zip.ZipException
import java.util.zip.ZipFile

/** Formato `.icdbackup` de ICdetector 3.0.0 (`BackupFormat`, `BackupManifest`, `BackupArchive`; docs/backup-format.md §2). */
object IcdBackupFormat {
    const val FORMAT = 1
    const val APP_ID = "com.alexisgordr.icdetector"
    const val MANIFEST = "manifest.json"
    const val DATABASE = "icdetector_history.db"
    const val SETTINGS = "settings.json"
    /** Esquema más reciente que CellCase conoce: el de ICdetector 3.1.0 (3.0.0 escribe el 25). */
    const val SCHEMA = 26
    /** Esquemas que se leen: 25 (3.0.0) y 26 (3.1.0, añade el churn de H17 al historial). */
    val SUPPORTED_SCHEMAS: Set<Int> = setOf(25, 26)
    /** Las betas de 3.0 escribían del 20 al 24: no se leen. */
    const val FIRST_SCHEMA = 20
    /** Versión del lector de copias. Se guarda con la importación, como la de los demás lectores. */
    const val READER_VERSION = "backup-icd/1.0.0"
}

/** Límites de la copia (docs/limits.md). La base no se carga en memoria: se copia a un archivo temporal. */
data class BackupLimits(
    /** Archivo `.icdbackup` (comprimido). */
    val maxBackupBytes: Long = 512L * 1024 * 1024,
    /** Base de datos descomprimida. ICdetector admite hasta 2 GiB; en el teléfono se limita a 1 GiB. */
    val maxDatabaseBytes: Long = 1024L * 1024 * 1024,
    val maxTextBytes: Long = 1024L * 1024,
    val maxEntries: Int = 16,
    /** Relación descomprimido/comprimido de la base (defensa ante bombas; una base real comprime ~5–15×). */
    val maxRatio: Long = 200,
    /** Filas por tabla que se reconstruyen: el mismo límite que un archivo exportado. */
    val maxRows: Int = ImportLimits.MAX_TABLE_ROWS,
    /**
     * Bytes de `payload_json` de un caso forense por encima de los cuales no se reconstruye (no se carga): el
     * descomprimido máximo de un ZIP exportado (`ZipLimits.maxTotalBytes`). Un caso real ocupa ~150 KiB.
     */
    val maxForensicPayloadBytes: Long = ZipLimits().maxTotalBytes
)

/** Lo que dice el manifiesto y lo comprobado sobre la base. */
data class BackupInfo(
    val format: Int?,
    val appId: String?,
    val appVersion: String?,
    val schemaVersion: Int?,
    val createdAt: Instant?,
    val createdAtText: String?,
    val declaredSha256: String?,
    val declaredBytes: Long?,
    val databaseSha256: String?,
    val databaseBytes: Long?,
    /** `PRAGMA user_version` leído de la base. */
    val userVersion: Int?,
    val pageSize: Int?,
    val freelistPages: Long?,
    /** Claves de `settings.json` (los valores no se usan). */
    val settingsKeys: List<String>,
    /** Filas por tabla de la base. */
    val tables: Map<String, Long>,
    /** Referencias del perfil local (`local_profile`) por madurez: LEARNING, MATURE, STALE. */
    val profileReferences: Map<String, Int>,
    /** Cambios del modo del perfil local (`profile_mode_events`): instante, modo y versión. */
    val profileModes: List<Triple<Instant?, String?, String?>>
) {
    val integrityVerified: Boolean get() = declaredSha256 != null && declaredSha256.equals(databaseSha256, ignoreCase = true) && declaredBytes == databaseBytes
}

/**
 * Lector de la copia de seguridad de ICdetector 3.0.0. Comprueba el manifiesto y el SHA-256 de la base, lee la base en
 * solo lectura ([SqliteFile]) y reconstruye las exportaciones que ICdetector habría hecho en ese momento
 * ([Icd30Exports]). Cada exportación reconstruida se lee con el lector de su formato: el resultado es una importación
 * de tipo [ExportKind.BACKUP] con una parte por exportación (historial, Radio, topología, geometría, Stable-Site y un
 * caso forense por caso). La copia original no se modifica; la base se copia a un archivo temporal que se borra.
 */
object IcdBackupReader {

    /** ¿Es una copia de ICdetector? Solo por las entradas del ZIP (como el resto de la detección, D-003). */
    fun isBackup(entryNames: Collection<String>): Boolean =
        IcdBackupFormat.MANIFEST in entryNames && IcdBackupFormat.DATABASE in entryNames

    fun isBackup(file: File): Boolean = try {
        ZipFile(file).use { z -> isBackup(z.entries().asSequence().map { it.name }.toList()) }
    } catch (_: Exception) { false }

    fun read(
        file: File, fileName: String, importId: String, zone: DeclaredZone?, checkpoint: Checkpoint = Checkpoint.NONE,
        budget: CaseBudget? = null, tempDir: File = File(System.getProperty("java.io.tmpdir")), limits: BackupLimits = BackupLimits()
    ): ImportResult {
        val size = file.length()
        val sha = file.inputStream().use { Hashing.copyHashing(it, { _, _ -> }, Long.MAX_VALUE, checkpoint).first }
        val issues = IssueCollector()
        fun fail(state: ImportState, text: Msg, entries: List<EntryInfo> = emptyList(), info: BackupInfo? = null) = ImportResult(importId, fileName, sha, size,
            ExportKind.BACKUP, state, text, READERS_VERSION, info?.let { schemaText(it) }, entries, null, emptyMap(), null, issues.result(),
            info?.let { ImportedData.Backup(it, emptyList()) }, emptyList())
        if (size > limits.maxBackupBytes) return fail(ImportState.TOO_LARGE, msg("import.backup.too_large", size / 1048576, limits.maxBackupBytes / 1048576))

        val zip = try { ZipFile(file) } catch (e: ZipException) { return fail(ImportState.DAMAGED_ZIP, msg("import.backup.damaged", e.message ?: "")) }
        zip.use { z ->
            val all = z.entries().asSequence().toList()
            if (all.size > limits.maxEntries) return fail(ImportState.ZIP_SECURITY, msg("import.backup.too_many_entries", all.size))
            all.forEach { e -> SafeZip.validateEntryName(e.name)?.let { why -> return fail(ImportState.ZIP_SECURITY, msg("import.backup.bad_entry", e.name, why)) } }
            all.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let { return fail(ImportState.ZIP_SECURITY, msg("import.backup.duplicate_entry", it)) }
            val known = setOf(IcdBackupFormat.MANIFEST, IcdBackupFormat.DATABASE, IcdBackupFormat.SETTINGS)
            val unknown = all.map { it.name }.filter { it !in known }
            if (unknown.isNotEmpty()) issues.add("BACKUP_UNKNOWN_ENTRIES", Severity.WARNING, null, msg("import.backup.unknown_entries", unknown.joinToString()))

            fun text(name: String): ByteArray? {
                val e = z.getEntry(name) ?: return null
                val out = java.io.ByteArrayOutputStream()
                z.getInputStream(e).use { Hashing.copyHashing(it, { b, n -> out.write(b, 0, n) }, limits.maxTextBytes, checkpoint) }
                return out.toByteArray()
            }
            val manifestBytes = try { text(IcdBackupFormat.MANIFEST) } catch (e: ImportRejected) { return fail(ImportState.ZIP_SECURITY, msg("import.backup.manifest_unreadable", e.text)) }
            val m = try { AndroidJson.obj(String(manifestBytes ?: ByteArray(0), Charsets.UTF_8)) } catch (e: Exception) {
                return fail(ImportState.INCOMPATIBLE, msg("import.backup.manifest_unreadable", e.message ?: ""))
            }
            fun str(k: String) = (m[k] as? String)
            fun num(k: String) = (m[k] as? Number)?.toLong()
            val created = str("createdAtUtc")?.let { CaptureTime.parseInstant(it) }
            var info = BackupInfo(num("format")?.toInt(), str("appId"), str("appVersion"), num("schemaVersion")?.toInt(), created, str("createdAtUtc"),
                str("databaseSha256"), num("databaseBytes"), null, null, null, null, null, emptyList(), emptyMap(), emptyMap(), emptyList())
            if (info.appId != IcdBackupFormat.APP_ID) return fail(ImportState.UNRECOGNIZED, msg("import.backup.not_icdetector", info.appId ?: "?"), info = info)
            if (info.format != IcdBackupFormat.FORMAT) return fail(ImportState.INCOMPATIBLE, msg("import.backup.format_unsupported", info.format ?: "?"), info = info)
            val schema = info.schemaVersion ?: return fail(ImportState.INCOMPATIBLE, msg("import.backup.manifest_unreadable", "schemaVersion"), info = info)
            when {
                schema > IcdBackupFormat.SCHEMA -> return fail(ImportState.INCOMPATIBLE, msg("import.backup.schema_newer", schema), info = info)
                schema !in IcdBackupFormat.SUPPORTED_SCHEMAS && schema >= IcdBackupFormat.FIRST_SCHEMA -> return fail(ImportState.INCOMPATIBLE, msg("import.backup.schema_beta", schema), info = info)
                schema < IcdBackupFormat.FIRST_SCHEMA -> return fail(ImportState.INCOMPATIBLE, msg("import.backup.schema_older", schema), info = info)
            }
            val settingsKeys = try { text(IcdBackupFormat.SETTINGS)?.let { AndroidJson.obj(String(it, Charsets.UTF_8)).keys.toList() } } catch (_: Exception) { null }
            if (settingsKeys == null) issues.add("BACKUP_SETTINGS", Severity.INFO, IcdBackupFormat.SETTINGS, msg("import.backup.settings_unreadable"))

            // Base: a un archivo temporal, con límite de tamaño y de compresión, calculando su SHA-256.
            val dbEntry = z.getEntry(IcdBackupFormat.DATABASE) ?: return fail(ImportState.INCOMPATIBLE, msg("import.backup.database_missing"), info = info)
            tempDir.mkdirs()
            val tmp = File.createTempFile("icdbackup-", ".db", tempDir)
            try {
                val maxDb = if (dbEntry.compressedSize > 0) minOf(limits.maxDatabaseBytes, dbEntry.compressedSize * limits.maxRatio) else limits.maxDatabaseBytes
                val (dbSha, dbBytes) = try {
                    z.getInputStream(dbEntry).use { input -> tmp.outputStream().buffered().use { out -> Hashing.copyHashing(input, { b, n -> out.write(b, 0, n) }, maxDb, checkpoint) } }
                } catch (e: ImportRejected) {
                    return fail(ImportState.ZIP_SECURITY, msg("import.backup.database_too_large", maxDb / 1048576), info = info)
                }
                info = info.copy(databaseSha256 = dbSha, databaseBytes = dbBytes, settingsKeys = settingsKeys.orEmpty())
                val entries = listOfNotNull(
                    EntryInfo(IcdBackupFormat.MANIFEST, manifestBytes!!.size.toLong(), Hashing.sha256Hex(manifestBytes), EntryRole.METADATA, null),
                    EntryInfo(IcdBackupFormat.DATABASE, dbBytes, dbSha, EntryRole.DATA, null),
                    z.getEntry(IcdBackupFormat.SETTINGS)?.let { EntryInfo(IcdBackupFormat.SETTINGS, it.size, "", EntryRole.CONTEXT, null) }
                ) + unknown.map { EntryInfo(it, z.getEntry(it).size, "", EntryRole.UNKNOWN, null) }
                val integrity = IntegrityCheck(if (info.integrityVerified) IntegrityStatus.VERIFIED else IntegrityStatus.MISMATCH, 1,
                    if (info.integrityVerified) emptyList() else listOf(IcdBackupFormat.DATABASE), emptyList(), emptyList())
                if (!info.declaredSha256.equals(dbSha, ignoreCase = true)) issues.add("BACKUP_SHA256_MISMATCH", Severity.ERROR, IcdBackupFormat.DATABASE, msg("import.backup.sha_mismatch"))
                if (info.declaredBytes != dbBytes) issues.add("BACKUP_SIZE_MISMATCH", Severity.ERROR, IcdBackupFormat.DATABASE, msg("import.backup.size_mismatch", dbBytes, info.declaredBytes ?: "?"))

                val db = try { SqliteFile(tmp) } catch (e: SqliteFormatException) {
                    return fail(ImportState.READ_ERROR, msg("import.backup.sqlite_invalid", e.message ?: ""), entries, info)
                }
                db.use {
                    try {
                        info = info.copy(userVersion = db.userVersion, pageSize = db.pageSize, freelistPages = db.freelistPages)
                        if (db.userVersion != schema) issues.add("BACKUP_USER_VERSION", Severity.WARNING, IcdBackupFormat.DATABASE, msg("import.backup.user_version_mismatch", db.userVersion, schema))
                        if (db.readVersion == 2) issues.add("BACKUP_WAL", Severity.WARNING, IcdBackupFormat.DATABASE, msg("import.backup.wal"))
                        if (db.table("history") == null) return fail(ImportState.INCOMPATIBLE, msg("import.backup.history_missing"), entries, info)
                        info = info.copy(tables = db.tables.values.associate { it.name to db.count(it) }.toSortedMap(), profileReferences = profileReferences(db),
                            profileModes = profileModes(db))
                        val exportAt = info.createdAt ?: Instant.ofEpochMilli(file.lastModified())
                        issues.add("BACKUP_RECONSTRUCTED", Severity.INFO, null, msg("import.backup.reconstructed", exportAt.toString()))
                        val parts = parts(db, info, exportAt, fileName, importId, zone, checkpoint, budget, issues, limits)
                        if (parts.isEmpty()) issues.add("BACKUP_EMPTY", Severity.WARNING, null, msg("import.backup.empty"))
                        val counts = linkedMapOf("parts" to parts.size, "rows" to (info.tables["history"] ?: 0L).toInt(),
                            "cases" to parts.count { it.kind == ExportKind.FORENSIC_CASE })
                        val period = parts.firstOrNull { it.kind == ExportKind.HISTORY }?.period ?: parts.mapNotNull { it.period }.firstOrNull()
                        return ImportResult(importId, fileName, sha, size, ExportKind.BACKUP, ImportState.IMPORTED, null, READERS_VERSION, schemaText(info),
                            entries, integrity, counts, period, issues.result(), ImportedData.Backup(info, parts), parts.flatMap { it.syntheticIndicators }.distinct())
                    } catch (e: SqliteFormatException) {
                        return fail(ImportState.READ_ERROR, msg("import.backup.sqlite_invalid", e.message ?: ""), entries, info)
                    } catch (e: IndexOutOfBoundsException) {
                        // Recuentos, perfil o partes sobre una base dañada: formato corrupto, no un cierre de la app.
                        return fail(ImportState.READ_ERROR, msg("import.backup.sqlite_invalid", e.javaClass.simpleName), entries, info)
                    } catch (e: IllegalArgumentException) {
                        return fail(ImportState.READ_ERROR, msg("import.backup.sqlite_invalid", e.javaClass.simpleName), entries, info)
                    }
                }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun schemaText(i: BackupInfo): Msg = msg("import.backup.schema_text", i.appVersion ?: "?", i.schemaVersion ?: "?", i.format ?: "?")

    private fun profileReferences(db: SqliteFile): Map<String, Int> {
        val t = db.table("local_profile") ?: return emptyMap()
        val c = t.indexOf("maturity")
        return db.rows(t).groupingBy { CursorValue.string(it[c]) ?: "?" }.eachCount().toSortedMap()
    }

    private fun profileModes(db: SqliteFile): List<Triple<Instant?, String?, String?>> {
        val t = db.table("profile_mode_events") ?: return emptyList()
        return db.rows(t).map { r -> Triple(r[t.indexOf("at_ms")]?.let { Instant.ofEpochMilli(CursorValue.long(it)) }, CursorValue.string(r[t.indexOf("mode")]),
            CursorValue.string(r[t.indexOf("app_version")])) }.toList()
    }

    /**
     * Una parte por exportación reconstruida, con los MISMOS límites que tendría el archivo exportado (tamaño,
     * filas, presupuesto del expediente): lo que no cabría como archivo tampoco se interpreta desde la copia.
     * Una exportación sin datos (tabla vacía) no se añade.
     */
    private fun parts(db: SqliteFile, info: BackupInfo, at: Instant, fileName: String, importId: String, zone: DeclaredZone?,
                      cp: Checkpoint, budget: CaseBudget?, issues: IssueCollector, limits: BackupLimits): List<ImportResult> {
        val ex = Icd30Exports(db, info.appVersion ?: "unknown", at.toEpochMilli(), maxRows = limits.maxRows)
        val out = ArrayList<ImportResult>()
        fun rows(t: String) = info.tables[t] ?: 0L
        fun name(n: String) = "$fileName › $n"
        fun failPart(kind: ExportKind, n: String, id: String, state: ImportState, text: Msg) =
            ImportResult(id, name(n), "", 0, kind, state, text, READERS_VERSION, null, emptyList(), null, emptyMap(), null, emptyList(), null, emptyList())
        fun <T> attempt(kind: ExportKind, n: String, id: String, build: () -> T?, use: (T) -> ImportResult) {
            cp.check()
            val r = try {
                build()?.let(use)
            } catch (e: java.util.concurrent.CancellationException) { throw e
            } catch (e: Icd30Exports.TooManyRows) { failPart(kind, n, id, ImportState.TOO_LARGE, msg("import.backup.too_many_rows", e.table, limits.maxRows))
            } catch (e: ImportRejected) { failPart(kind, n, id, ImportState.INCOMPATIBLE, e.text)
            } catch (e: SqliteFormatException) { throw e
            } catch (e: IndexOutOfBoundsException) { throw SqliteFormatException("lectura fuera de los datos en $n")
            } catch (e: Exception) { failPart(kind, n, id, ImportState.READ_ERROR, msg("import.backup.part_error", n, e.message ?: e.javaClass.simpleName)) }
            if (r != null) {
                out += r
                if (!r.imported) issues.add("BACKUP_PART_NOT_IMPORTED", Severity.WARNING, null, msg("import.backup.part_error", n, r.messageText ?: msg("import.backup.not_imported")))
            }
        }
        fun csvPart(bytes: ByteArray, kind: ExportKind, n: String, id: String, read: () -> ImportResult): ImportResult {
            val max = if (kind == ExportKind.HISTORY) ImportLimits.MAX_HISTORY_FILE_BYTES else ImportLimits.MAX_FILE_BYTES
            if (bytes.size > max) return failPart(kind, n, id, ImportState.TOO_LARGE,
                if (kind == ExportKind.HISTORY) ImportLimits.historyTooLargeMessage(bytes.size.toLong()) else ImportLimits.tooLargeMessage(bytes.size.toLong()))
            if (kind == ExportKind.HISTORY && budget != null) {
                val rows = CaseBudget.csvRows(bytes)
                if (!budget.reserveHistoryRows(rows)) return failPart(kind, n, id, ImportState.TOO_LARGE, budget.historyMessage(rows))
            }
            return read()
        }
        fun zipPart(files: Map<String, ByteArray>, kind: ExportKind, n: String, id: String, read: (Long, String) -> ImportResult): ImportResult {
            val zl = ZipLimits()
            val total = files.values.sumOf { it.size.toLong() }
            val sha = Hashing.sha256Hex(files.entries.joinToString("\u0000") { (k, v) -> k + "\u0000" + Hashing.sha256Hex(v) }.toByteArray())
            return when {
                files.values.any { it.size > zl.maxEntryBytes } || total > zl.maxTotalBytes -> failPart(kind, n, id, ImportState.TOO_LARGE, ImportLimits.tooLargeMessage(total))
                budget != null && !budget.reserveZip(total) -> failPart(kind, n, id, ImportState.TOO_LARGE, budget.zipMessage(total))
                else -> read(total, sha)
            }
        }
        if (rows("history") > 0) attempt(ExportKind.HISTORY, "history.csv", "$importId/history", { ex.historyCsv() }) { b ->
            csvPart(b, ExportKind.HISTORY, "history.csv", "$importId/history") { ImportPipeline.historyPart(b, name("history.csv"), "$importId/history", Hashing.sha256Hex(b), zone, cp) }
        }
        if (rows("service_state_events") > 0) attempt(ExportKind.SERVICE_STATE, "service_state.csv", "$importId/radio", { ex.serviceStateCsv() }) { b ->
            csvPart(b, ExportKind.SERVICE_STATE, "service_state.csv", "$importId/radio") { ImportPipeline.serviceStatePart(b, name("service_state.csv"), "$importId/radio", Hashing.sha256Hex(b), cp) }
        }
        if (rows("cell_transitions") > 0) attempt(ExportKind.TOPOLOGY, "topology.zip", "$importId/topology", { ex.topology() }) { f ->
            zipPart(f, ExportKind.TOPOLOGY, "topology.zip", "$importId/topology") { total, sha -> ImportPipeline.topologyPart(f, total, name("topology.zip"), "$importId/topology", sha, cp) }
        }
        if (rows("cell_transitions") > 0 || rows("mobility_trips") > 0) attempt(ExportKind.GEOMETRY, "geometry.zip", "$importId/geometry", { ex.geometry() }) { f ->
            zipPart(f, ExportKind.GEOMETRY, "geometry.zip", "$importId/geometry") { total, sha -> ImportPipeline.geometryPart(f, total, name("geometry.zip"), "$importId/geometry", sha, cp) }
        }
        if (rows("site_cell_evidence") > 0) attempt(ExportKind.STABLE_SITE, "stable_site.zip", "$importId/stable-site", { ex.stableSite() }) { f ->
            zipPart(f, ExportKind.STABLE_SITE, "stable_site.zip", "$importId/stable-site") { total, sha -> ImportPipeline.stableSitePart(f, total, name("stable_site.zip"), "$importId/stable-site", sha, cp) }
        }
        if (rows("forensic_cases") > 0) {
            // Listar los casos ya lee la base: los mismos errores que una parte (no se pierde el resto de la copia).
            var cases: List<Icd30Exports.ForensicExport> = emptyList()
            attempt<ImportResult>(ExportKind.FORENSIC_CASE, "forensic", "$importId/forensic", { cases = ex.forensicCases().orEmpty(); null }) { it }
            cases.forEachIndexed { i, c ->
                val n = "${c.caseCode.ifEmpty { "case-$i" }}.zip"; val id = "$importId/forensic/${c.caseCode.ifEmpty { "$i" }}"
                if (c.payloadBytes > limits.maxForensicPayloadBytes) {
                    // Un caso con más muestras de las que caben en un ZIP exportado no se construye (no se carga en memoria).
                    val r = failPart(ExportKind.FORENSIC_CASE, n, id, ImportState.TOO_LARGE, ImportLimits.tooLargeMessage(c.payloadBytes))
                    out += r
                    issues.add("BACKUP_PART_NOT_IMPORTED", Severity.WARNING, null, msg("import.backup.part_error", n, r.messageText ?: msg("import.backup.not_imported")))
                    return@forEachIndexed
                }
                attempt(ExportKind.FORENSIC_CASE, n, id, { c.files }) { f ->
                    zipPart(f, ExportKind.FORENSIC_CASE, n, id) { total, sha -> ImportPipeline.forensicPart(f, total, name(n), id, sha, zone, cp) }
                }
            }
        }
        return out
    }

    /** Copia de seguridad en memoria (pruebas y lote): se escribe a un temporal y se lee como archivo. */
    fun read(bytes: ByteArray, fileName: String, importId: String, zone: DeclaredZone?, checkpoint: Checkpoint = Checkpoint.NONE,
             budget: CaseBudget? = null, tempDir: File = File(System.getProperty("java.io.tmpdir"))): ImportResult {
        tempDir.mkdirs()
        val f = File.createTempFile("icdbackup-", ".zip", tempDir)
        try { f.writeBytes(bytes); return read(f, fileName, importId, zone, checkpoint, budget, tempDir) } finally { f.delete() }
    }

    internal fun periodOf(parts: List<ImportResult>): PeriodInfo? = parts.firstOrNull { it.kind == ExportKind.HISTORY }?.period
}
