package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg
import com.alexisgordr.cellcase.core.i18n.plus

import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.model.ForensicCapability
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.ForensicCaseMeta
import com.alexisgordr.cellcase.core.model.ForensicCell
import com.alexisgordr.cellcase.core.model.ForensicTrustSite
import com.alexisgordr.cellcase.core.model.IssueExample
import com.alexisgordr.cellcase.core.model.ForensicHeuristic
import com.alexisgordr.cellcase.core.model.ForensicSample
import com.alexisgordr.cellcase.core.model.GeometryCell
import com.alexisgordr.cellcase.core.model.GeometryData
import com.alexisgordr.cellcase.core.model.GeometryEdge
import com.alexisgordr.cellcase.core.model.Issue
import com.alexisgordr.cellcase.core.model.IssueCollector
import com.alexisgordr.cellcase.core.model.MobilityTrip
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.ServiceStateEvent
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.model.StableSite
import com.alexisgordr.cellcase.core.model.StableSiteCell
import com.alexisgordr.cellcase.core.model.StableSiteData
import com.alexisgordr.cellcase.core.model.StableSiteEpisode
import com.alexisgordr.cellcase.core.model.StableSiteMotion
import com.alexisgordr.cellcase.core.model.TopologyData
import com.alexisgordr.cellcase.core.model.TopologyRoute
import com.alexisgordr.cellcase.core.time.CaptureTime
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.time.Instant
import java.time.LocalDate

/** Versión del conjunto de lectores. Se registra con cada resultado. */
const val READERS_VERSION = "readers/2.3.0"

/** Resultado de una importación, con categorías de error separadas (D-027). */
enum class ImportState { IMPORTED, UNRECOGNIZED, DAMAGED_ZIP, ZIP_SECURITY, INCOMPATIBLE, NOT_IMPLEMENTED, READ_ERROR, TOO_LARGE }

/**
 * Límites de importación. Los archivos se procesan en memoria, así que se rechazan ANTES de leerlos si superan
 * [MAX_FILE_BYTES]. Las exportaciones de ICdetector 2.10.5 ocupan normalmente de kilobytes a pocos megabytes.
 */
object ImportLimits {
    /**
     * Medido (docs/limits.md, `MemoryProbe`): con filas compactas un historial ocupa ~1,4 KiB por fila en memoria.
     * 8 MiB (~40.000–48.000 filas, unos 7–8 meses al ritmo de ICdetector) y el presupuesto por expediente caben en
     * un montón de 192 MiB con el análisis y los dos informes. Los 16 MiB anteriores NO cabían (D-046).
     */
    const val MAX_FILE_BYTES: Long = 6L * 1024 * 1024
    /** Filas por tabla o historial (por encima de lo que cabe en [MAX_FILE_BYTES] con filas reales). */
    const val MAX_TABLE_ROWS: Int = 60_000
    /**
     * Presupuesto del expediente: filas de historial analizadas juntas (D-054). Medido (docs/limits.md): ~1,1 KiB por
     * fila en memoria con 35 o con 54 columnas; 40.000 filas equivalen a lo que admitían los 6 MiB de 2.10.5 (36.000).
     */
    const val CASE_HISTORY_ROWS: Int = 40_000
    /** Un historial CSV puede ocupar más que otros archivos: las filas de 3.0 son más largas (D-054). */
    const val MAX_HISTORY_FILE_BYTES: Long = 16L * 1024 * 1024
    /** Presupuesto del expediente: bytes descomprimidos de todos los ZIP que se analizan juntos. */
    const val CASE_ZIP_BYTES: Long = 8L * 1024 * 1024
    /** Longitud máxima de un campo CSV (los campos de ICdetector miden decenas de caracteres). */
    const val MAX_FIELD_CHARS: Int = 8 * 1024
    fun tooLargeMessage(size: Long) = msg("import.tooLargeMessage.archivo_demasiado_grande_mib_el", size / (1024 * 1024), MAX_FILE_BYTES / (1024 * 1024))
    fun historyTooLargeMessage(size: Long) = msg("import.tooLargeMessage.archivo_demasiado_grande_mib_el", size / (1024 * 1024), MAX_HISTORY_FILE_BYTES / (1024 * 1024))
}

/** Papel de una entrada interna de un ZIP. */
enum class EntryRole { DATA, METADATA, INTEGRITY, CONSISTENCY, CONTEXT, UNKNOWN }

data class EntryInfo(val name: String, val bytes: Long, val sha256: String, val role: EntryRole, val rows: Int?)

enum class IntegrityStatus {
    /** Todas las entradas listadas coinciden con su SHA-256. */
    VERIFIED,
    /** Alguna entrada no coincide con el SHA-256 registrado. */
    MISMATCH,
    /** Falta alguna entrada listada o hay entradas no listadas. */
    INCOMPLETE,
    /** SHA256SUMS.txt no se puede interpretar. */
    MALFORMED,
    /** El formato no incluye SHA256SUMS.txt. */
    NOT_PROVIDED
}

data class IntegrityCheck(
    val status: IntegrityStatus,
    val checked: Int,
    val mismatched: List<String>,
    val missing: List<String>,
    val unlisted: List<String>
)

/** Base temporal de un periodo: no es lo mismo cubrir observaciones que resumir «última vez visto». */
enum class PeriodBasis { OBSERVATIONS_LOCAL, OBSERVATIONS_UTC, AGGREGATE_LAST_SEEN, AGGREGATE_FIRST_LAST, TRIPS, DAYS_LOCAL }

data class PeriodInfo(
    val basis: PeriodBasis,
    val start: Instant? = null,
    val end: Instant? = null,
    val startText: String? = null,
    val endText: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null
)

/** Partes de una copia de seguridad (vacío para cualquier otro archivo). */
val ImportResult.backupParts: List<ImportResult> get() = (data as? ImportedData.Backup)?.parts.orEmpty()

/** La importación y, si es una copia de seguridad, sus partes a continuación (tablas de archivos e incidencias). */
fun ImportResult.withParts(): List<ImportResult> = listOf(this) + backupParts

/** Lo que entra en el análisis: cada copia de seguridad se sustituye por sus partes. */
fun List<ImportResult>.leaves(): List<ImportResult> = flatMap { r -> if (r.kind == ExportKind.BACKUP && r.data is ImportedData.Backup) r.backupParts else listOf(r) }

/** Datos interpretados de una importación correcta. */
sealed interface ImportedData {
    data class History(val data: HistoryImport) : ImportedData
    data class ServiceState(val events: List<ServiceStateEvent>) : ImportedData
    data class Forensic(val data: ForensicCaseData) : ImportedData
    data class Topology(val data: TopologyData) : ImportedData
    data class Geometry(val data: GeometryData) : ImportedData
    data class StableSiteSet(val data: StableSiteData) : ImportedData
    /**
     * Copia de seguridad de ICdetector 3.0 (docs/backup-format.md): una parte por exportación reconstruida desde su
     * base. El análisis usa las partes como si fueran archivos exportados (CaseAnalyzer las despliega).
     */
    data class Backup(val info: com.alexisgordr.cellcase.core.backup.BackupInfo, val parts: List<ImportResult>) : ImportedData
}

data class ImportResult(
    val importId: String,
    val fileName: String,
    val sha256: String,
    val sizeBytes: Long,
    val kind: ExportKind,
    val state: ImportState,
    /** Explicación para el usuario cuando no se ha importado (D-044: clave y argumentos, sin idioma fijo). */
    val messageText: Msg?,
    val readerVersion: String,
    val schemaText: Msg?,
    val entries: List<EntryInfo>,
    val integrity: IntegrityCheck?,
    /** Recuentos neutros de idioma (`rows`, `routes`, `samples`…). */
    val counts: Map<String, Int>,
    val period: PeriodInfo?,
    val issues: List<Issue>,
    val data: ImportedData?,
    /** Indicios automáticos de datos de prueba (no deciden el origen, D-028). */
    val syntheticIndicators: List<String>
) {
    val imported: Boolean get() = state == ImportState.IMPORTED
    /** Explicación en español (idioma de referencia). */
    val message: String? get() = messageText?.toString()
    val schemaLabel: String? get() = schemaText?.toString()
}

/**
 * Inspecciona el contenido real de un archivo (no su nombre, extensión ni MIME) y lo importa con
 * el lector que corresponda. Cada archivo se procesa por separado: un fallo nunca afecta a otro.
 */
object ImportPipeline {

    /**
     * Procesa un archivo en disco comprobando su tamaño ANTES de cargarlo: un archivo grande se rechaza sin
     * reservar memoria para su contenido. Su SHA-256 se calcula por bloques.
     */
    fun processFile(
        file: java.io.File,
        fileName: String,
        importId: String,
        zone: DeclaredZone?,
        checkpoint: Checkpoint = Checkpoint.NONE,
        maxBytes: Long = ImportLimits.MAX_FILE_BYTES,
        budget: CaseBudget? = null,
        /** Carpeta para la base temporal de una copia de seguridad (en la app, la caché privada). */
        tempDir: java.io.File? = null
    ): ImportResult {
        val size = file.length()
        // Copia de seguridad de ICdetector 3.0: se lee desde el disco (la base no cabe en memoria) y con sus propios límites.
        if (com.alexisgordr.cellcase.core.backup.IcdBackupReader.isBackup(file))
            return com.alexisgordr.cellcase.core.backup.IcdBackupReader.read(file, fileName, importId, zone, checkpoint, budget, tempDir ?: java.io.File(System.getProperty("java.io.tmpdir")))
        // Un historial CSV puede llegar a MAX_HISTORY_FILE_BYTES; se reconoce por su cabecera antes de cargarlo.
        val history = maxBytes == ImportLimits.MAX_FILE_BYTES && size > maxBytes && looksLikeHistory(file)
        val limit = if (history) ImportLimits.MAX_HISTORY_FILE_BYTES else maxBytes
        if (size > limit) {
            val sha = file.inputStream().use { Hashing.copyHashing(it, { _, _ -> }, Long.MAX_VALUE, checkpoint).first }
            return ImportResult(importId, fileName, sha, size, if (history) ExportKind.HISTORY else ExportKind.UNKNOWN, ImportState.TOO_LARGE,
                if (history) ImportLimits.historyTooLargeMessage(size) else ImportLimits.tooLargeMessage(size), READERS_VERSION,
                null, emptyList(), null, emptyMap(), null, emptyList(), null, nameIndicators(fileName))
        }
        return process(file.readBytes(), fileName, importId, zone, checkpoint, budget)
    }

    private fun looksLikeHistory(file: java.io.File): Boolean = try {
        val head = file.inputStream().use { i -> ByteArray(4096).let { b -> b.copyOf(maxOf(0, i.read(b))) } }
        FormatDetector.detectCsv(String(head, Charsets.UTF_8).lineSequence().firstOrNull() ?: "").kind == ExportKind.HISTORY
    } catch (_: Exception) { false }

    fun process(
        bytes: ByteArray,
        fileName: String,
        importId: String,
        zone: DeclaredZone?,
        checkpoint: Checkpoint = Checkpoint.NONE,
        budget: CaseBudget? = null
    ): ImportResult {
        val sha = Hashing.sha256Hex(bytes)
        fun fail(kind: ExportKind, state: ImportState, text: Msg, entries: List<EntryInfo> = emptyList()) =
            ImportResult(importId, fileName, sha, bytes.size.toLong(), kind, state, text, READERS_VERSION, null, entries, null,
                emptyMap(), null, emptyList(), null, nameIndicators(fileName))

        if (bytes.isEmpty()) return fail(ExportKind.UNKNOWN, ImportState.READ_ERROR, msg("import.fail.el_archivo_esta_vacio"))
        val isZip = FormatDetector.isZip(bytes.copyOf(4))
        if (!isZip) {
            val firstLine = try { CsvTables.decode(bytes.copyOf(minOf(bytes.size, 8192)).let { trimToUtf8(it) }).lineSequence().firstOrNull() ?: "" }
                catch (e: ImportRejected) { return fail(ExportKind.UNKNOWN, ImportState.READ_ERROR, msg("import.fail.no_es_texto_utf_8")) }
            val det = FormatDetector.detectCsv(firstLine)
            if (det.kind == ExportKind.UNKNOWN) return fail(ExportKind.UNKNOWN, ImportState.UNRECOGNIZED,
                msg("import.fail.archivo_no_reconocido_no_es"))
            // Presupuesto del expediente ANTES de interpretar el historial (D-046)
            if (det.kind != ExportKind.HISTORY && bytes.size > ImportLimits.MAX_FILE_BYTES)
                return fail(det.kind, ImportState.TOO_LARGE, ImportLimits.tooLargeMessage(bytes.size.toLong()))
            if (det.kind == ExportKind.HISTORY && bytes.size > ImportLimits.MAX_HISTORY_FILE_BYTES)
                return fail(ExportKind.HISTORY, ImportState.TOO_LARGE, ImportLimits.historyTooLargeMessage(bytes.size.toLong()))
            if (det.kind == ExportKind.HISTORY && budget != null) {
                val rows = CaseBudget.csvRows(bytes)
                if (!budget.reserveHistoryRows(rows)) return fail(ExportKind.HISTORY, ImportState.TOO_LARGE, budget.historyMessage(rows))
            }
            return guarded(det.kind, ::fail) {
                when (det.kind) {
                    ExportKind.HISTORY -> history(bytes, fileName, importId, sha, zone, checkpoint)
                    ExportKind.SERVICE_STATE -> serviceState(bytes, fileName, importId, sha, checkpoint)
                    else -> fail(det.kind, ImportState.NOT_IMPLEMENTED, msg("import.fail.importador_no_implementado_para", det.kind.labelText))
                }
            }
        }
        val backupNames = try { SafeZip.centralDirectoryNames(bytes) } catch (_: ImportRejected) { emptyList() }
        if (com.alexisgordr.cellcase.core.backup.IcdBackupReader.isBackup(backupNames))
            return com.alexisgordr.cellcase.core.backup.IcdBackupReader.read(bytes, fileName, importId, zone, checkpoint, budget)
        val entriesMap = try { SafeZip.readAll(bytes, checkpoint = checkpoint) } catch (e: ImportRejected) {
            val state = when (e.kind) {
                RejectKind.DAMAGED_ZIP -> ImportState.DAMAGED_ZIP
                RejectKind.ZIP_SECURITY -> ImportState.ZIP_SECURITY
                RejectKind.READ_ERROR -> ImportState.READ_ERROR
                else -> ImportState.INCOMPATIBLE
            }
            return fail(ExportKind.UNKNOWN, state, e.text)
        }
        val unzipped = entriesMap.values.sumOf { it.size.toLong() }
        if (budget != null && !budget.reserveZip(unzipped))
            return fail(ExportKind.UNKNOWN, ImportState.TOO_LARGE, budget.zipMessage(unzipped))
        val feature = entriesMap["metadata.json"]?.let { b -> runCatching { MiniJson.obj(CsvTables.decode(b))["feature"] as? String }.getOrNull() }
        val det = FormatDetector.detectZip(entriesMap.keys, feature)
        if (det.kind == ExportKind.UNKNOWN) {
            return fail(ExportKind.UNKNOWN, ImportState.UNRECOGNIZED,
                msg("import.fail.zip_no_reconocido_sus_entradas", entriesMap.keys.take(8).joinToString(), if (entriesMap.size > 8) "…" else ""),
                entriesMap.map { (n, b) -> EntryInfo(n, b.size.toLong(), Hashing.sha256Hex(b), EntryRole.UNKNOWN, null) })
        }
        return guarded(det.kind, ::fail) {
            when (det.kind) {
                ExportKind.FORENSIC_CASE -> forensic(entriesMap, bytes.size.toLong(), fileName, importId, sha, zone, checkpoint)
                ExportKind.TOPOLOGY -> topology(entriesMap, bytes.size.toLong(), fileName, importId, sha, checkpoint)
                ExportKind.GEOMETRY -> geometry(entriesMap, bytes.size.toLong(), fileName, importId, sha, checkpoint)
                ExportKind.STABLE_SITE -> stableSite(entriesMap, bytes.size.toLong(), fileName, importId, sha, checkpoint)
                else -> fail(det.kind, ImportState.NOT_IMPLEMENTED, msg("import.fail.importador_no_implementado_para", det.kind.labelText))
            }
        }
    }

    private fun trimToUtf8(b: ByteArray): ByteArray {
        // Recorta un posible carácter multibyte partido al final del fragmento leído.
        var n = b.size
        while (n > 0 && n > b.size - 4 && (b[n - 1].toInt() and 0xC0) == 0x80) n--
        if (n > 0 && (b[n - 1].toInt() and 0x80) != 0) n--
        return if (n == b.size) b else b.copyOf(n)
    }

    private inline fun guarded(
        kind: ExportKind,
        fail: (ExportKind, ImportState, Msg, List<EntryInfo>) -> ImportResult,
        block: () -> ImportResult
    ): ImportResult = try { block() } catch (e: ImportRejected) {
        val state = when (e.kind) {
            RejectKind.READ_ERROR -> ImportState.READ_ERROR
            RejectKind.DAMAGED_ZIP -> ImportState.DAMAGED_ZIP
            RejectKind.ZIP_SECURITY -> ImportState.ZIP_SECURITY
            RejectKind.UNRECOGNIZED -> ImportState.UNRECOGNIZED
            RejectKind.INCOMPATIBLE -> ImportState.INCOMPATIBLE
        }
        fail(kind, state, e.text, emptyList())
    } catch (e: MiniJson.JsonException) {
        fail(kind, ImportState.READ_ERROR, msg("import.guarded.json_no_valido", e.text), emptyList())
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (e: RuntimeException) {
        // Último recurso: un dato que ningún lector previó (número, texto o estructura imposible) no debe cerrar la
        // app. El archivo queda como «error de lectura» con el tipo de fallo; el original se conserva.
        fail(kind, ImportState.READ_ERROR, msg("import.guarded.error_inesperado", e.javaClass.simpleName), emptyList())
    }

    // ───────────────────────── partes de una copia de seguridad ─────────────────────────
    // Exportaciones reconstruidas desde la base de una copia (backup/IcdBackupReader): mismos lectores que los archivos.

    private fun partFail(fileName: String, id: String, sha: String, size: Long) = { kind: ExportKind, state: ImportState, text: Msg, entries: List<EntryInfo> ->
        ImportResult(id, fileName, sha, size, kind, state, text, READERS_VERSION, null, entries, null, emptyMap(), null, emptyList(), null, emptyList())
    }
    internal fun historyPart(b: ByteArray, fileName: String, id: String, sha: String, zone: DeclaredZone?, cp: Checkpoint) =
        guarded(ExportKind.HISTORY, partFail(fileName, id, sha, b.size.toLong())) { history(b, fileName, id, sha, zone, cp) }
    internal fun serviceStatePart(b: ByteArray, fileName: String, id: String, sha: String, cp: Checkpoint) =
        guarded(ExportKind.SERVICE_STATE, partFail(fileName, id, sha, b.size.toLong())) { serviceState(b, fileName, id, sha, cp) }
    internal fun topologyPart(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, cp: Checkpoint) =
        guarded(ExportKind.TOPOLOGY, partFail(fileName, id, sha, size)) { topology(e, size, fileName, id, sha, cp) }
    internal fun geometryPart(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, cp: Checkpoint) =
        guarded(ExportKind.GEOMETRY, partFail(fileName, id, sha, size)) { geometry(e, size, fileName, id, sha, cp) }
    internal fun stableSitePart(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, cp: Checkpoint) =
        guarded(ExportKind.STABLE_SITE, partFail(fileName, id, sha, size)) { stableSite(e, size, fileName, id, sha, cp) }
    internal fun forensicPart(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, zone: DeclaredZone?, cp: Checkpoint) =
        guarded(ExportKind.FORENSIC_CASE, partFail(fileName, id, sha, size)) { forensic(e, size, fileName, id, sha, zone, cp) }

    // ───────────────────────── indicios de datos de prueba ─────────────────────────

    private fun nameIndicators(fileName: String) =
        if (fileName.contains("SYNTHETIC", ignoreCase = true) || fileName.contains("sintetic", ignoreCase = true)) listOf("FILENAME_SYNTHETIC") else emptyList()

    private fun indicators(fileName: String, ids: Sequence<CellIdentity?>, extra: List<String> = emptyList()): List<String> {
        val test = ids.count { it?.mcc == "001" }
        return nameIndicators(fileName) + (if (test > 0) listOf("TEST_MCC_001:${test}") else emptyList()) + extra
    }

    // ───────────────────────── integridad ─────────────────────────

    fun checkSums(entries: Map<String, ByteArray>): IntegrityCheck {
        val sums = entries["SHA256SUMS.txt"] ?: return IntegrityCheck(IntegrityStatus.NOT_PROVIDED, 0, emptyList(), emptyList(), emptyList())
        val listed = LinkedHashMap<String, String>()
        for (line in CsvTables.decode(sums).lines().map { it.trimEnd('\r') }.filter { it.isNotBlank() }) {
            val m = Regex("^([0-9a-fA-F]{64}) [ *](.+)\$").find(line) ?: return IntegrityCheck(IntegrityStatus.MALFORMED, 0, emptyList(), emptyList(), emptyList())
            listed[m.groupValues[2]] = m.groupValues[1].lowercase()
        }
        val mismatched = ArrayList<String>(); val missing = ArrayList<String>()
        for ((name, hex) in listed) {
            val b = entries[name]
            if (b == null) missing += name else if (Hashing.sha256Hex(b) != hex) mismatched += name
        }
        val unlisted = entries.keys.filter { it != "SHA256SUMS.txt" && it !in listed }
        val status = when {
            mismatched.isNotEmpty() -> IntegrityStatus.MISMATCH
            missing.isNotEmpty() || unlisted.isNotEmpty() -> IntegrityStatus.INCOMPLETE
            else -> IntegrityStatus.VERIFIED
        }
        return IntegrityCheck(status, listed.size - missing.size, mismatched, missing, unlisted)
    }

    private fun entryInfos(entries: Map<String, ByteArray>, roles: Map<String, EntryRole>, rows: Map<String, Int>) =
        entries.map { (n, b) -> EntryInfo(n, b.size.toLong(), Hashing.sha256Hex(b), roles[n] ?: EntryRole.UNKNOWN, rows[n]) }

    private fun unknownEntriesIssue(entries: Map<String, ByteArray>, roles: Map<String, EntryRole>, issues: IssueCollector) {
        val unknown = entries.keys.filter { it !in roles }
        if (unknown.isNotEmpty()) issues.add("ZIP_UNKNOWN_ENTRIES", Severity.INFO, null,
            msg("import.unknownEntriesIssue.entradas_no_reconocidas_conservadas_sin", unknown.joinToString()))
    }

    private fun integrityIssue(check: IntegrityCheck, issues: IssueCollector) {
        when (check.status) {
            IntegrityStatus.MISMATCH -> issues.add("SHA256_MISMATCH", Severity.ERROR, "SHA256SUMS.txt",
                msg("import.integrityIssue.el_sha_256_de_no", check.mismatched.joinToString()))
            IntegrityStatus.INCOMPLETE -> issues.add("SHA256_INCOMPLETE", Severity.WARNING, "SHA256SUMS.txt",
                msg("import.integrityIssue.sha256sums_txt_no_cubre_exactamente") +
                    (if (check.missing.isNotEmpty()) msg("import.integrityIssue.faltan", check.missing.joinToString()) else Msg.raw("")) +
                    (if (check.unlisted.isNotEmpty()) msg("import.integrityIssue.no_listadas", check.unlisted.joinToString()) else Msg.raw("")) + ".")
            IntegrityStatus.MALFORMED -> issues.add("SHA256_MALFORMED", Severity.WARNING, "SHA256SUMS.txt", msg("import.integrityIssue.sha256sums_txt_no_tiene_el"))
            else -> {}
        }
    }

    private fun need(entries: Map<String, ByteArray>, name: String): ByteArray =
        entries[name] ?: throw ImportRejected(msg("import.need.falta_la_entrada_obligatoria", name), RejectKind.INCOMPATIBLE)

    /** Texto canónico de un valor JSON: números sin ceros sobrantes, objetos con claves ordenadas. */
    private fun canonicalJson(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Number -> java.math.BigDecimal(v.toString()).stripTrailingZeros().toPlainString()
        is String -> "\"" + v + "\""
        is Map<*, *> -> v.entries.sortedBy { it.key.toString() }.joinToString(",", "{", "}") { "\"${it.key}\":" + canonicalJson(it.value) }
        is List<*> -> v.joinToString(",", "[", "]") { canonicalJson(it) }
        else -> v.toString()
    }

    private fun json(entries: Map<String, ByteArray>, name: String): Map<String, Any?>? =
        entries[name]?.let { MiniJson.obj(CsvTables.decode(it)) }

    private fun Map<String, Any?>.long(k: String) = (this[k] as? Number)?.toLong()
    private fun Map<String, Any?>.str(k: String) = this[k] as? String

    // ───────────────────────── historial ─────────────────────────

    private fun history(bytes: ByteArray, fileName: String, id: String, sha: String, zone: DeclaredZone?, cp: Checkpoint): ImportResult {
        val imp = HistoryCsvImporter().import(bytes.inputStream(), fileName, id, zone, cp)
        val sorted = imp.observations.filter { it.time.local != null }.sortedBy { it.time.local }
        val period = sorted.firstOrNull()?.let { f ->
            val l = sorted.last()
            PeriodInfo(PeriodBasis.OBSERVATIONS_LOCAL, f.time.instantOrNull, l.time.instantOrNull, f.time.raw, l.time.raw)
        }
        return ImportResult(id, fileName, sha, bytes.size.toLong(), ExportKind.HISTORY, ImportState.IMPORTED, null, imp.readerVersion,
            imp.schema.label, emptyList(), null, mapOf("rows" to imp.dataRows, "rejectedRows" to imp.rejectedRows), period, imp.issues,
            ImportedData.History(imp), indicators(fileName, imp.observations.asSequence().map { it.identity }))
    }

    // ───────────────────────── Radio ─────────────────────────

    val SERVICE_COLUMNS = listOf("TimestampUtc", "State", "DataRegistered", "VoiceRegistered", "Searching", "Roaming", "NetworkOperator",
        "NetworkName", "SimOperator", "ManualSelection", "ChannelNumber", "CellBandwidthsKHz", "DataNetwork", "VoiceNetwork", "Source")

    private fun serviceState(bytes: ByteArray, fileName: String, id: String, sha: String, cp: Checkpoint): ImportResult {
        val issues = IssueCollector()
        val t = CsvTables.read(bytes, id, fileName, null, SERVICE_COLUMNS, issues, cp, guarded = true)
        val states = setOf("IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF", "UNKNOWN")
        val events = t.rows.map { r ->
            val st = r.str("State")
            if (st != null && st !in states) issues.add("ENUM_UNKNOWN", Severity.WARNING, "State", msg("import.serviceState.estado_no_reconocido_se_conserva"), com.alexisgordr.cellcase.core.model.IssueExample(r.prov, "State", st))
            ServiceStateEvent(r.prov, r.instant("TimestampUtc"), st, r.bool("DataRegistered"), r.bool("VoiceRegistered"), r.bool("Searching"),
                r.bool("Roaming"), r.str("NetworkOperator"), r.str("NetworkName"), r.str("SimOperator"), r.bool("ManualSelection"), r.int("ChannelNumber"),
                r.str("CellBandwidthsKHz")?.split(';')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty(), r.str("DataNetwork"), r.str("VoiceNetwork"), r.str("Source"))
        }
        if (events.isEmpty()) issues.add("NO_DATA_ROWS", Severity.WARNING, null,
            msg("import.serviceState.solo_contiene_la_cabecera_en"))
        val times = events.mapNotNull { it.time }
        if (times.zipWithNext().any { (a, b) -> b.isBefore(a) }) issues.add("ORDER", Severity.INFO, null, msg("import.serviceState.los_eventos_no_estan_en"))
        if (events.size >= 5000) issues.add("RETENTION_CAP", Severity.WARNING, null, msg("import.serviceState.hay_5_000_eventos_es"))
        val period = if (times.isEmpty()) null else PeriodInfo(PeriodBasis.OBSERVATIONS_UTC, times.min(), times.max())
        val extra = if (events.any { it.networkOperator == "00101" || it.simOperator == "00101" }) listOf("TEST_PLMN_00101") else emptyList()
        return ImportResult(id, fileName, sha, bytes.size.toLong(), ExportKind.SERVICE_STATE, ImportState.IMPORTED, null, READERS_VERSION,
            msg("import.serviceState.estado_de_servicio_radio_de"), emptyList(), null, mapOf("events" to events.size), period, issues.result(),
            ImportedData.ServiceState(events), nameIndicators(fileName) + extra)
    }

    // ───────────────────────── caso forense ─────────────────────────

    private val TIMELINE = listOf("timestamp_utc", "elapsed_ms", "event", "phase", "score", "anomaly_confidence", "cell_identity", "verified", "latency_state", "gps_lat", "gps_lon", "gps_accuracy_m", "reason")
    private val FCELLS = listOf("timestamp_utc", "role", "identity", "mcc", "mnc", "tac", "cid", "radio", "network_type", "dbm", "pci", "arfcn", "band", "rsrq", "sinr", "ta", "ta_unit")
    private val HEUR = listOf("timestamp_utc", "heuristic_id", "name", "status", "explanation")

    /** Columnas de `timeline.csv` añadidas en 3.0 (al final; vacías si la muestra no las guardaba). */
    private val TIMELINE_V3 = listOf("required_phases", "data_quality_reason", "heuristics")
    /** Columnas de `cells.csv` añadidas en 3.0 que CellCase lee. `connection_state` y `bandwidth_khz` (2.10.4) siguen sin interpretarse. */
    private val FCELLS_LATER = listOf("registered", "bands", "csg_indicator", "secondary_carriers")
    /** Columnas de `trust_site.csv` (3.0) que CellCase lee; las demás se conservan como no interpretadas. */
    private val TRUST_SITE = listOf("local_trust_state", "local_trust_confidence_percent", "local_trust_contradictions", "site_key", "site_feature_state",
        "site_would_trigger", "site_reason")

    /** Papeles de `cells.csv`: `SECONDARY` desde 3.0 (portadora secundaria, antes salía como `NEIGHBOR`). */
    private val FORENSIC_ROLES = setOf("SERVING", "NEIGHBOR", "SECONDARY")

    private fun forensic(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, zone: DeclaredZone?, cp: Checkpoint): ImportResult {
        val guardedZip = "trust_site.csv" in e || IcdVersion.atLeast(json(e, "case.json")?.str("appVersion"), 3, 0, 0)
        val issues = IssueCollector()
        val integrity = checkSums(e); integrityIssue(integrity, issues)
        val case = MiniJson.obj(CsvTables.decode(need(e, "case.json")))
        val schema = case.long("schemaVersion")
        if (schema != 1L) issues.add("SCHEMA_VERSION", Severity.WARNING, "case.json", msg("import.forensic.schemaversion_distinto_del_de_icdetector", schema))
        fun local(k: String) = case.str(k)?.let { CaptureTime.parseLocalSeconds(it, zone) }
        val meta = ForensicCaseMeta(schema, case.str("caseId"), case.str("state"), case.str("captureOrigin"), local("createdAt"), local("updatedAt"), local("closedAt"),
            case.str("cellIdentity")?.let { Identities.parse(it) }, case.long("highestPhase"), case["confirmed"] as? Boolean, case.long("sampleCount"),
            case.str("appVersion"), case.str("device"), case.str("android"), case, case.long("sampleSchemaVersion"))
        if (meta.state == "CAPTURING" || meta.state == "POST_CAPTURE") issues.add("CASE_INCOMPLETE", Severity.WARNING, "case.json",
            msg("import.local.caso_exportado_en_estado_la", meta.state))
        if (meta.caseCode?.startsWith("ICD-OBS-") == true && meta.captureOrigin == "ALARM") issues.add("CASE_PROMOTED", Severity.INFO, "case.json",
            msg("import.local.codigo_icd_obs_con_origen"))
        val tl = CsvTables.read(need(e, "timeline.csv"), id, fileName, "timeline.csv", TIMELINE, issues, cp, guarded = guardedZip, optional = TIMELINE_V3)
        val samples = tl.rows.map { r ->
            ForensicSample(r.prov, r.instant("timestamp_utc"), r.long("elapsed_ms"), r.str("event"), r.int("phase"), r.int("score"), r.double("anomaly_confidence"),
                r.identity("cell_identity"), r.str("verified"), r.str("latency_state"), r.double("gps_lat"), r.double("gps_lon"), r.double("gps_accuracy_m"), r.str("reason"),
                r.int("required_phases"), r.str("data_quality_reason"), r.str("heuristics"))
        }
        val cl = e["cells.csv"]?.let { CsvTables.read(it, id, fileName, "cells.csv", FCELLS, issues, cp, guarded = guardedZip, optional = FCELLS_LATER) }
        val cells = cl?.rows.orEmpty().map { r ->
            fun sent(v: Int?) = v?.takeIf { it != Int.MAX_VALUE }
            ForensicCell(r.prov, r.instant("timestamp_utc"), r.str("role"), r.identity("identity"),
                r.str("radio")?.let { n -> RadioTech.values().firstOrNull { it.name == n } } ?: RadioTech.UNKNOWN,
                sent(r.int("dbm")), sent(r.int("pci")), sent(r.int("arfcn")), r.int("band"), sent(r.int("rsrq")), sent(r.int("sinr")), r.int("ta"), r.str("ta_unit"),
                r.bool("registered"), r.str("bands"), r.bool("csg_indicator"), r.str("secondary_carriers"))
        }
        cells.filter { it.role != null && it.role !in FORENSIC_ROLES }.takeIf { it.isNotEmpty() }?.let { odd ->
            issues.add("ROLE_UNKNOWN", Severity.WARNING, "cells.csv:role", msg("import.forensic.role_unknown"), IssueExample(odd[0].provenance, "role", odd[0].role))
        }
        val ts = e["trust_site.csv"]?.let { CsvTables.read(it, id, fileName, "trust_site.csv", listOf("timestamp_utc"), issues, cp, guarded = guardedZip, optional = TRUST_SITE) }
        val trustSite = ts?.rows.orEmpty().map { r ->
            ForensicTrustSite(r.prov, r.instant("timestamp_utc"), r.str("local_trust_state"), r.int("local_trust_confidence_percent"), r.str("local_trust_contradictions"),
                r.str("site_key"), r.str("site_feature_state"), r.bool("site_would_trigger"), r.str("site_reason"))
        }
        val hl = e["heuristics.csv"]?.let { CsvTables.read(it, id, fileName, "heuristics.csv", HEUR, issues, cp, guarded = guardedZip) }
        val heur = hl?.rows.orEmpty().map { r -> ForensicHeuristic(r.prov, r.instant("timestamp_utc"), r.int("heuristic_id"), r.str("name"), r.str("status"), r.str("explanation")) }
        val caps = json(e, "capabilities.json")?.let { j -> (j["observations"] as? List<*>).orEmpty().mapNotNull { o ->
            val m = o as? Map<*, *> ?: return@mapNotNull null
            val raw = m["timestamp"] as? String
            val t = raw?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val vals = (m["values"] as? Map<*, *>).orEmpty().entries.associate { (k, v) -> k.toString() to canonicalJson(v) }.toSortedMap()
            ForensicCapability(raw, t, vals)
        } }.orEmpty()
        val log = e["terminal.log"]?.let { CsvTables.decode(it).lines().map { l -> l.trimEnd('\r') }.filter { l -> l.isNotBlank() } }.orEmpty()
        if (meta.sampleCount != null && meta.sampleCount.toInt() != samples.size) issues.add("SAMPLE_COUNT", Severity.WARNING, "case.json",
            msg("import.sent.samplecount_no_coincide_con_las", meta.sampleCount, samples.size))
        val data = ForensicCaseData(meta, samples, cells, heur, caps, log, trustSite)
        val roles = mapOf("case.json" to EntryRole.METADATA, "timeline.csv" to EntryRole.DATA, "cells.csv" to EntryRole.DATA, "heuristics.csv" to EntryRole.DATA,
            "trust_site.csv" to EntryRole.CONTEXT, "capabilities.json" to EntryRole.CONTEXT, "terminal.log" to EntryRole.CONTEXT, "SHA256SUMS.txt" to EntryRole.INTEGRITY)
        unknownEntriesIssue(e, roles, issues)
        val period = if (data.start != null) PeriodInfo(PeriodBasis.OBSERVATIONS_UTC, data.start, data.end) else null
        val ind = indicators(fileName, (samples.asSequence().map { it.identity } + cells.asSequence().map { it.identity }),
            if (meta.device?.contains("SYNTHETIC", true) == true) listOf("DEVICE_SYNTHETIC") else emptyList())
        return ImportResult(id, fileName, sha, size, ExportKind.FORENSIC_CASE, ImportState.IMPORTED, null, READERS_VERSION,
            msg("import.sent.caso_forense_schemaversion", schema ?: "?", meta.appVersion ?: msg("import.sent.version_desconocida")),
            entryInfos(e, roles, mapOf("timeline.csv" to samples.size, "cells.csv" to cells.size, "heuristics.csv" to heur.size, "trust_site.csv" to trustSite.size)),
            integrity, mapOf("samples" to samples.size, "cells" to cells.size, "heuristics" to heur.size), period, issues.result(), ImportedData.Forensic(data), ind)
    }

    // ───────────────────────── topología ─────────────────────────

    private fun topology(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, cp: Checkpoint): ImportResult {
        val guardedZip = IcdVersion.atLeast(json(e, "metadata.json")?.str("appVersion"), 3, 0, 0)
        val issues = IssueCollector()
        val integrity = checkSums(e); integrityIssue(integrity, issues)
        val t = CsvTables.read(need(e, "transitions.csv"), id, fileName, "transitions.csv",
            listOf("from_identity", "to_identity", "observations", "trusted_observations", "trust_ratio", "last_status", "last_seen_utc"), issues, cp, guarded = guardedZip)
        val routes = t.rows.map { r ->
            TopologyRoute(r.prov, Identities.parse(r.raw("from_identity").orEmpty()), Identities.parse(r.raw("to_identity").orEmpty()),
                r.raw("from_identity").orEmpty(), r.raw("to_identity").orEmpty(), r.int("observations"), r.int("trusted_observations"),
                r.double("trust_ratio"), r.str("last_status"), r.instant("last_seen_utc"))
        }
        val cells = e["cells.csv"]?.let { CsvTables.read(it, id, fileName, "cells.csv",
            listOf("identity", "incoming_routes", "outgoing_routes", "incoming_handovers", "outgoing_handovers", "trusted_incoming", "trusted_outgoing"), issues, cp, guarded = guardedZip) }
        val meta = json(e, "metadata.json")
        val graphEdges = e["topology.graphml"]?.let { Regex("<edge\\b").findAll(CsvTables.decode(it)).count() }
        val data = TopologyData(routes, cells?.rows?.size ?: 0, meta?.str("generatedAtUtc")?.let { CaptureTime.parseInstant(it) }, meta?.long("schemaVersion"),
            meta?.str("appVersion"), meta?.long("routeCount"), meta?.long("cellCount"), meta?.long("handoverCount"), graphEdges)
        if (data.declaredRouteCount != null && data.declaredRouteCount.toInt() != routes.size) issues.add("ROUTE_COUNT", Severity.WARNING, "metadata.json",
            msg("import.topology.routecount_no_coincide_con_transitions", data.declaredRouteCount, routes.size))
        if (graphEdges != null && graphEdges != routes.size) issues.add("GRAPHML_COUNT", Severity.WARNING, "topology.graphml",
            msg("import.topology.el_graphml_tiene_aristas_y", graphEdges, routes.size))
        // Desde 2.10.6 la exportación no tiene tope de rutas (TopologyExporter): el aviso solo vale para versiones anteriores.
        if (data.possibleTruncation && !IcdVersion.atLeast(data.appVersion, 2, 10, 6)) issues.add("POSSIBLE_TRUNCATION", Severity.WARNING, null,
            msg("import.topology.rutas_icdetector_2_10_5", routes.size, TopologyData.LIMIT))
        issues.add("SCOPE_AGGREGATE", Severity.INFO, null,
            msg("import.topology.agregado_acumulado_totales_por_ruta"))
        val roles = mapOf("transitions.csv" to EntryRole.DATA, "cells.csv" to EntryRole.CONSISTENCY, "topology.graphml" to EntryRole.CONSISTENCY,
            "metadata.json" to EntryRole.METADATA, "SHA256SUMS.txt" to EntryRole.INTEGRITY)
        unknownEntriesIssue(e, roles, issues)
        val seen = routes.mapNotNull { it.lastSeen }
        val period = if (seen.isEmpty()) null else PeriodInfo(PeriodBasis.AGGREGATE_LAST_SEEN, seen.min(), seen.max())
        return ImportResult(id, fileName, sha, size, ExportKind.TOPOLOGY, ImportState.IMPORTED, null, READERS_VERSION,
            msg("import.topology.topologia_schemaversion", data.schemaVersion ?: "?", data.appVersion ?: msg("import.sent.version_desconocida")),
            entryInfos(e, roles, mapOf("transitions.csv" to routes.size, "cells.csv" to (cells?.rows?.size ?: 0))), integrity,
            mapOf("routes" to routes.size, "handovers" to routes.sumOf { it.observations ?: 0 }, "cells" to data.cellRows), period, issues.result(),
            ImportedData.Topology(data), indicators(fileName, routes.asSequence().flatMap { sequenceOf(it.from, it.to) }))
    }

    // ───────────────────────── geometría ─────────────────────────

    private fun geometry(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, cp: Checkpoint): ImportResult {
        val guardedZip = IcdVersion.atLeast(json(e, "metadata.json")?.str("appVersion"), 3, 0, 0)
        val issues = IssueCollector()
        // ICdetector no escribe SHA256SUMS.txt en Geometría; si hay uno y no coincide, se avisa como en los demás ZIP.
        val integrity = checkSums(e); integrityIssue(integrity, issues)
        val gc = CsvTables.read(need(e, "geometry_cells.csv"), id, fileName, "geometry_cells.csv",
            listOf("identity", "radio_tech", "local_trust_state", "mobility_familiarity", "mobility_good_edges", "mobility_relevant_trip_count",
                "incoming_routes", "outgoing_routes", "incoming_transitions", "outgoing_transitions", "trusted_incoming", "trusted_outgoing",
                "first_mobility_seen_utc", "last_mobility_seen_utc"), issues, cp, guarded = guardedZip)
        val cells = gc.rows.map { r ->
            GeometryCell(r.prov, Identities.parse(r.raw("identity").orEmpty()), r.raw("identity").orEmpty(), r.str("mobility_familiarity"),
                r.int("mobility_good_edges"), r.int("mobility_relevant_trip_count"), r.int("incoming_transitions"), r.int("outgoing_transitions"))
        }
        val ge = CsvTables.read(need(e, "geometry_edges.csv"), id, fileName, "geometry_edges.csv",
            listOf("from_identity", "to_identity", "transition_count", "trusted_transition_count", "trip_count", "pending_current_trip",
                "projected_trip_count_after_valid_close", "mobility_first_seen_utc", "mobility_last_seen_utc", "last_trip_id"), issues, cp, guarded = guardedZip)
        val edges = ge.rows.map { r ->
            GeometryEdge(r.prov, Identities.parse(r.raw("from_identity").orEmpty()), Identities.parse(r.raw("to_identity").orEmpty()),
                r.raw("from_identity").orEmpty(), r.raw("to_identity").orEmpty(), r.int("transition_count"), r.int("trusted_transition_count"),
                r.int("trip_count"), r.bool("pending_current_trip"), r.int("projected_trip_count_after_valid_close"),
                r.instant("mobility_first_seen_utc"), r.instant("mobility_last_seen_utc"))
        }
        val mt = e["mobility_trips.csv"]?.let { CsvTables.read(it, id, fileName, "mobility_trips.csv",
            listOf("trip_id", "started_at_utc", "ended_at_utc", "state", "close_reason", "had_moving", "distinct_cells", "edge_count"), issues, cp, guarded = guardedZip) }
        val trips = mt?.rows.orEmpty().map { r ->
            MobilityTrip(r.prov, r.str("trip_id"), r.instant("started_at_utc"), r.instant("ended_at_utc"), r.str("state"), r.str("close_reason"),
                r.bool("had_moving"), r.int("distinct_cells"), r.int("edge_count"))
        }
        val meta = json(e, "metadata.json")
        if (meta?.str("feature") != "MOBILITY_FAMILIARITY") issues.add("METADATA_FEATURE", Severity.WARNING, "metadata.json", msg("import.geometry.metadata_json_no_declara_feature"))
        val graphEdges = e["geometry.graphml"]?.let { Regex("<edge\\b").findAll(CsvTables.decode(it)).count() }
        val data = GeometryData(cells, edges, trips, meta?.str("exportTimeUtc")?.let { CaptureTime.parseInstant(it) }, meta?.long("schemaVersion"), meta?.str("appVersion"), graphEdges)
        if (graphEdges != null && graphEdges != edges.size) issues.add("GRAPHML_COUNT", Severity.WARNING, "geometry.graphml", msg("import.geometry.el_graphml_tiene_aristas_y", graphEdges, edges.size))
        if (data.possibleTruncation && !IcdVersion.atLeast(data.appVersion, 2, 10, 6)) issues.add("POSSIBLE_TRUNCATION", Severity.WARNING, null, msg("import.geometry.se_alcanza_el_limite_de", GeometryData.LIMIT))
        val invalidClosed = trips.count { it.state == "CLOSED" && it.meetsValidityRule == false }
        if (invalidClosed > 0) issues.add("TRIPS_DISCARDED", Severity.INFO, "mobility_trips.csv",
            msg("import.geometry.viaje_s_closed_no_cumplen", invalidClosed))
        issues.add("SCOPE_AGGREGATE", Severity.INFO, null, msg("import.geometry.agregado_de_rutas_y_viajes"))
        val roles = mapOf("geometry_cells.csv" to EntryRole.DATA, "geometry_edges.csv" to EntryRole.DATA, "mobility_trips.csv" to EntryRole.DATA,
            "geometry.graphml" to EntryRole.CONSISTENCY, "metadata.json" to EntryRole.METADATA)
        unknownEntriesIssue(e, roles, issues)
        val starts = trips.mapNotNull { it.start }; val ends = trips.mapNotNull { it.end ?: it.start }
        val period = if (starts.isEmpty()) null else PeriodInfo(PeriodBasis.TRIPS, starts.min(), ends.maxOrNull())
        return ImportResult(id, fileName, sha, size, ExportKind.GEOMETRY, ImportState.IMPORTED, null, READERS_VERSION,
            msg("import.geometry.geometria_schemaversion", data.schemaVersion ?: "?", data.appVersion ?: msg("import.sent.version_desconocida")),
            entryInfos(e, roles, mapOf("geometry_cells.csv" to cells.size, "geometry_edges.csv" to edges.size, "mobility_trips.csv" to trips.size)), integrity,
            mapOf("cells" to cells.size, "edges" to edges.size, "trips" to trips.size), period, issues.result(), ImportedData.Geometry(data),
            indicators(fileName, cells.asSequence().map { it.identity }))
    }

    // ───────────────────────── Stable-Site ─────────────────────────

    private fun stableSite(e: Map<String, ByteArray>, size: Long, fileName: String, id: String, sha: String, cp: Checkpoint): ImportResult {
        val guardedZip = StableSiteFormat.PRIVACY_NOTE in e
        val issues = IssueCollector()
        // Igual en Stable-Site: un SHA256SUMS.txt erróneo genera SHA256_MISMATCH.
        val integrity = checkSums(e); integrityIssue(integrity, issues)
        val st = CsvTables.read(need(e, "sites.csv"), id, fileName, "sites.csv",
            listOf("site_key", "feature_state", "neighbour_capability", "maturity_reason", "serving_observations", "serving_distinct_days", "static_distinct_days",
                "full_neighbour_observations", "full_neighbour_distinct_days", "rf_neighbour_observations", "rf_neighbour_distinct_days", "first_seen_ms", "last_seen_ms"), issues, cp, guarded = guardedZip)
        val sites = st.rows.map { r ->
            StableSite(r.prov, r.raw("site_key").orEmpty(), r.str("feature_state"), r.str("neighbour_capability"), r.str("maturity_reason"),
                r.long("serving_observations"), r.int("serving_distinct_days"), r.int("static_distinct_days"), r.epochMs("first_seen_ms"), r.epochMs("last_seen_ms"))
        }
        val sc = CsvTables.read(need(e, "site_cells.csv"), id, fileName, "site_cells.csv",
            listOf("site_key", "cell_identity", "serving_observations", "serving_distinct_days", "neighbour_observations", "neighbour_distinct_days",
                "first_serving_ms", "last_serving_ms", "first_neighbour_ms", "last_neighbour_ms"), issues, cp, guarded = guardedZip)
        val cells = sc.rows.map { r ->
            StableSiteCell(r.prov, r.raw("site_key").orEmpty(), Identities.parse(r.raw("cell_identity").orEmpty()), r.raw("cell_identity").orEmpty(),
                r.long("serving_observations"), r.int("serving_distinct_days"), r.long("neighbour_observations"), r.int("neighbour_distinct_days"),
                r.epochMs("first_serving_ms"), r.epochMs("last_serving_ms"))
        }
        val rf = e["site_rf_neighbours.csv"]?.let { CsvTables.read(it, id, fileName, "site_rf_neighbours.csv", listOf("site_key", "rf_fingerprint"), issues, cp, guarded = guardedZip).rows.size } ?: 0
        val mo = CsvTables.read(need(e, "motion.csv"), id, fileName, "motion.csv", listOf("site_key", "day", "state", "accuracy_band_m", "duration_s", "displacement_band_m"), issues, cp, guarded = guardedZip)
        val motion = mo.rows.map { r -> StableSiteMotion(r.prov, r.raw("site_key").orEmpty(), r.date("day"), r.str("state"), r.int("accuracy_band_m"), r.long("duration_s")) }
        val ep = CsvTables.read(need(e, "shadow_episodes.csv"), id, fileName, "shadow_episodes.csv",
            listOf("episode_id", "site_key", "previous_identity", "candidate_identity", "first_seen_ms", "last_seen_ms", "duration_ms", "evaluated_ms", "closed_ms",
                "shadow_recorded", "reason", "motion_state", "feature_state", "globally_known", "known_at_site", "seen_as_neighbour", "serving_days", "neighbour_days", "corroborated"), issues, cp, guarded = guardedZip)
        val episodes = ep.rows.map { r ->
            StableSiteEpisode(r.prov, r.str("episode_id"), r.str("site_key"), r.str("previous_identity")?.let { Identities.parse(it) },
                r.str("candidate_identity")?.let { Identities.parse(it) }, r.epochMs("first_seen_ms"), r.epochMs("last_seen_ms"), r.epochMs("closed_ms"),
                r.bool("shadow_recorded"), r.str("reason"), r.str("motion_state"), r.str("feature_state"), r.bool("globally_known"), r.bool("known_at_site"),
                r.bool("seen_as_neighbour"), r.int("serving_days"), r.int("neighbour_days"), r.bool("corroborated"))
        }
        issues.add("SCOPE_OVERLAP", Severity.INFO, null,
            msg("import.stableSite.cada_lugar_se_registra_en"))
        val roles = mapOf("sites.csv" to EntryRole.DATA, "site_cells.csv" to EntryRole.DATA, "site_rf_neighbours.csv" to EntryRole.CONTEXT,
            "motion.csv" to EntryRole.DATA, "shadow_episodes.csv" to EntryRole.DATA, StableSiteFormat.PRIVACY_NOTE to EntryRole.CONTEXT)
        unknownEntriesIssue(e, roles, issues)
        val firsts = sites.mapNotNull { it.firstSeen }; val lasts = sites.mapNotNull { it.lastSeen }
        val days = motion.mapNotNull { it.day }
        val period = if (firsts.isEmpty()) null else PeriodInfo(PeriodBasis.AGGREGATE_FIRST_LAST, firsts.min(), lasts.maxOrNull(),
            startDate = days.minOrNull(), endDate = days.maxOrNull())
        return ImportResult(id, fileName, sha, size, ExportKind.STABLE_SITE, ImportState.IMPORTED, null, READERS_VERSION,
            if (guardedZip) msg("import.stableSite.v3_privacy") else msg("import.stableSite.stable_site_sin_version_declarada"),
            entryInfos(e, roles, mapOf("sites.csv" to sites.size, "site_cells.csv" to cells.size, "site_rf_neighbours.csv" to rf, "motion.csv" to motion.size, "shadow_episodes.csv" to episodes.size)),
            integrity, mapOf("sites" to sites.size, "siteCells" to cells.size, "rfNeighbours" to rf, "motionDays" to motion.size, "episodes" to episodes.size),
            period, issues.result(), ImportedData.StableSiteSet(StableSiteData(sites, cells, rf, motion, episodes)),
            indicators(fileName, cells.asSequence().map { it.identity } + episodes.asSequence().map { it.candidate }))
    }
}
