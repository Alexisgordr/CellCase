package com.alexisgordr.cellcase.data

import com.alexisgordr.cellcase.logic.ConfigSummary
import com.alexisgordr.cellcase.logic.FileHash
import com.alexisgordr.cellcase.logic.ImportPolicy
import com.alexisgordr.cellcase.logic.ReportExport
import com.alexisgordr.cellcase.logic.StartupWork

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.alexisgordr.cellcase.BuildConfig
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.EPISODE_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.RULESET_VERSION
import com.alexisgordr.cellcase.core.casefile.AnalysisConfig
import com.alexisgordr.cellcase.core.casefile.AtomicFiles
import com.alexisgordr.cellcase.core.importing.ImportLimits
import com.alexisgordr.cellcase.core.casefile.CaseStorage
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import com.alexisgordr.cellcase.core.casefile.DeviceCrossCheck
import com.alexisgordr.cellcase.core.casefile.DeviceDeclaration
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.casefile.Discrepancy
import com.alexisgordr.cellcase.core.importing.Checkpoint
import com.alexisgordr.cellcase.core.importing.CaseBudget
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportRejected
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.READERS_VERSION
import com.alexisgordr.cellcase.core.report.AnalysisAvailability
import com.alexisgordr.cellcase.core.report.AvailabilityTable
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.CoordinateMode
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.IdentifierMode
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.reportTexts
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg
import com.alexisgordr.cellcase.R
import com.alexisgordr.cellcase.ui.UiText
import com.alexisgordr.cellcase.ui.UserFacingException
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.CancellationException

/** Resultado de un archivo dentro de una selección múltiple. Cada archivo se procesa por separado. */
/** Falta la copia privada de un original registrado. */
class MissingCopy : Exception()

sealed interface FileOutcome {
    val fileName: String
    /** Archivo nuevo: se conserva el original y se registra su estado (importado o no). */
    data class Processed(val record: ImportRecord, val result: ImportResult) : FileOutcome { override val fileName get() = record.fileName }
    /** Mismo SHA-256 que un archivo ya presente (en el expediente o antes en la misma selección): no se cuenta dos veces. */
    data class AlreadyPresent(override val fileName: String, val existingName: String) : FileOutcome
    /** No se pudo ni siquiera copiar el archivo (permiso, tamaño, error de lectura): no queda registro. */
    data class Failed(override val fileName: String, val text: UiText) : FileOutcome
}

/** Resultado de una selección múltiple, también si se canceló a mitad (D-038). */
data class ImportBatchReport(
    val outcomes: List<FileOutcome>,
    val cancelled: Boolean,
    /** Archivos de la selección que no llegaron a procesarse (incluido el que estaba en curso al cancelar). */
    val pending: List<String>
)

/** Vista de un expediente con todo recalculado desde los originales conservados. */
data class CaseSnapshot(
    val case: CaseRecord,
    val imports: List<ImportRecord>,
    val reports: List<ReportRecord>,
    /** Resultado de la relectura de cada original, por id de importación. */
    val results: Map<String, ImportResult>,
    val analysis: CaseAnalysis,
    val availability: List<AnalysisAvailability>,
    /** Configuración actual del análisis; los informes con otra huella se generaron con una configuración previa. */
    val config: AnalysisConfig,
    /** Discrepancias entre lo detectado, lo declarado y los metadatos de los archivos (solo avisos). */
    val discrepancies: List<Discrepancy>
) {
    fun isOutdated(r: ReportRecord) = r.configFingerprint != config.fingerprint()

    val orderedResults: List<ImportResult> get() = imports.mapNotNull { results[it.id] }
    val indicatorFiles: List<ImportResult> get() = orderedResults.filter { it.syntheticIndicators.isNotEmpty() }
    /** Declarado real pero con indicios automáticos de datos de prueba. */
    val originConflict: Boolean get() = case.origin == DataOrigin.DECLARED_REAL && indicatorFiles.isNotEmpty()
}

/**
 * Almacenamiento de expedientes:
 * `files/cases/<id>/originals/<sha256>` — copias privadas de solo lectura de los archivos importados;
 * `files/cases/<id>/reports/<id>.pdf` — informes generados.
 * Todo lo pesado se llama desde corrutinas fuera del hilo de interfaz.
 */
class CaseRepository(private val context: Context, val store: CaseStore = CaseStore(context)) {

    private val resolver: ContentResolver get() = context.contentResolver
    private val storage = CaseStorage(context.filesDir)
    private fun originals(id: String) = storage.originals(id)
    private fun reportsDir(id: String) = storage.reports(id)

    fun createCase(
        name: String, description: String?, deviceSource: DeviceSource, deviceLabel: String,
        zone: ZoneId, zoneConfirmed: Boolean, origin: DataOrigin, detected: DetectedEnvironment?
    ): CaseRecord {
        ready()
        val c = CaseRecord(UUID.randomUUID().toString(), name.trim(), Instant.now(), deviceLabel.trim(), zone.id, zoneConfirmed,
            origin, false, null, null, CoordinateMode.OMIT, IdentifierMode.FULL,
            description = description?.trim()?.ifEmpty { null }, deviceSource = deviceSource, detected = detected)
        store.insertCase(c)
        originals(c.id); reportsDir(c.id)
        return c
    }

    fun listCases() = ready().let { store.listCases() }
    fun getCase(id: String) = ready().let { store.getCase(id) }
    fun issues(importId: String) = ready().let { store.listIssues(importId) }

    /**
     * Guarda la edición de un expediente. Si cambia la zona horaria, se aplica a sus importaciones (D-010); el
     * análisis se recalcula al recargar y los informes anteriores quedan marcados por su huella (D-033).
     * Devuelve true si el cambio afecta al análisis.
     */
    fun updateCase(updated: CaseRecord): Boolean {
        ready()
        val old = store.getCase(updated.id) ?: throw UserFacingException(UiText.res(R.string.error_case_missing))
        store.updateCase(updated)
        val zoneChanged = old.zoneId != updated.zoneId || old.zoneConfirmed != updated.zoneConfirmed
        if (zoneChanged) store.updateImportsZone(updated.id, updated.zoneId, updated.zoneConfirmed)
        return zoneChanged || old.periodOfInterest != updated.periodOfInterest
    }

    /** Lo que se borrará, para explicarlo antes de confirmar. */
    data class DeletionPreview(val imports: Int, val reports: Int, val bytes: Long)

    fun deletionPreview(id: String): DeletionPreview {
        ready()
        val (i, r) = store.countFor(id)
        return DeletionPreview(i, r, storage.sizeOf(id))
    }

    /**
     * Borrado en dos fases (D-033): marca → carpeta privada a la papelera → filas en una transacción → papelera.
     * Si algo falla antes de mover la carpeta, se deshace la marca y no se pierde nada. Si la app se cierra
     * después, [resumePendingDeletions] termina el borrado al volver a abrir.
     * No toca los archivos elegidos con el selector ni los PDF guardados fuera de CellCase.
     */
    fun deleteCase(id: String) {
        ready()
        store.markDeleting(id, true)
        if (!storage.moveToTrash(id)) {
            store.markDeleting(id, false)
            throw UserFacingException(UiText.res(R.string.error_delete_move))
        }
        store.deleteCaseRows(id)
        storage.purge(id)
    }

    /** Termina borrados interrumpidos (marca «borrando» o carpeta en la papelera). Idempotente. */
    fun resumePendingDeletions() {
        for (id in store.listDeleting()) {
            if (storage.moveToTrash(id)) { store.deleteCaseRows(id); storage.purge(id) }
        }
        for (id in storage.pendingTrash()) {
            if (store.getCase(id) == null) storage.purge(id)
            else { store.deleteCaseRows(id); storage.purge(id) } // la carpeta ya estaba en la papelera: el borrado se había decidido
        }
    }

    /**
     * Borrados interrumpidos y temporales: en segundo plano (E/S fuera del hilo principal al arrancar la app).
     * Toda lectura de expedientes espera a que termine ([ready]).
     */
    private val startup = StartupWork(listOf({ resumePendingDeletions() }, { cleanupLeftovers() }))

    private fun ready() = startup.await()

    fun configOf(c: CaseRecord, imports: List<ImportRecord>) =
        AnalysisConfig(c.zoneId, c.zoneConfirmed, c.periodOfInterest, fileHashes = imports.map { it.sha256 })

    /** Resumen de la configuración sin palabras de un idioma (se guarda y se muestra en cualquier idioma). */
    fun configSummary(cfg: AnalysisConfig): String = ConfigSummary.of(cfg)

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        } ?: uri.lastPathSegment ?: context.getString(R.string.file_unnamed)

    private fun zoneOf(case: CaseRecord) = DeclaredZone(ZoneId.of(case.zoneId), case.zoneConfirmed)

    /**
     * Importa varios archivos elegidos en una sola operación. Cada uno se procesa por separado:
     * copia privada con SHA-256 → ¿ya presente? → inspección del contenido → lectura → registro.
     * Un fallo en un archivo no impide importar los demás. La cancelación detiene la selección completa.
     */
    fun importFiles(caseId: String, uris: List<Uri>, checkpoint: Checkpoint, progress: (UiText) -> Unit): ImportBatchReport {
        ready()
        val case = store.getCase(caseId) ?: throw UserFacingException(UiText.res(R.string.error_case_missing))
        val known = HashMap<String, String>()
        store.listImports(caseId).forEach { known[it.sha256] = it.fileName }
        val names = uris.mapIndexed { i, uri -> runCatching { displayName(uri) }.getOrDefault("#${i + 1}") }
        val outcomes = ArrayList<FileOutcome>()
        for ((i, uri) in uris.withIndex()) {
            val name = names[i]
            try {
                checkpoint.check()
                progress(UiText.Raw("(${i + 1}/${uris.size}) $name"))
                outcomes += importOne(case, uri, name, known, checkpoint)
            } catch (e: CancellationException) {
                // Cada archivo terminado ya está guardado; el interrumpido se ha limpiado en importOne.
                return ImportBatchReport(outcomes, true, names.drop(i))
            } catch (e: ImportRejected) {
                outcomes += FileOutcome.Failed(name, UiText.Core(e.text))
            } catch (e: UserFacingException) {
                outcomes += FileOutcome.Failed(name, e.text)
            } catch (e: Exception) {
                outcomes += FileOutcome.Failed(name, UiText.Core(msg("import.run.error_al_leer", Msg.raw(e.message ?: e.javaClass.simpleName))))
            }
        }
        return ImportBatchReport(outcomes, false, emptyList())
    }

    /**
     * Importa un archivo de forma atómica: o queda su original con su registro, o no queda nada.
     * Un original que ya existía (mismo SHA-256) nunca se borra.
     */
    private fun importOne(case: CaseRecord, uri: Uri, name: String, known: MutableMap<String, String>, checkpoint: Checkpoint): FileOutcome {
        val tmp = File(originals(case.id), "tmp-${UUID.randomUUID()}")
        var storedNew: File? = null
        var recorded = false
        try {
            // Copia por bloques con límite: un archivo grande se corta al superar el máximo, sin cargarlo en memoria.
            val (sha, size) = (resolver.openInputStream(uri) ?: throw UserFacingException(UiText.res(R.string.error_open_file))).buffered().use { input ->
                // Una copia de seguridad de ICdetector (ZIP) puede ocupar cientos de MiB: su límite es otro (ImportPolicy).
                val limit = ImportPolicy.copyLimit(ImportPolicy.peek(input))
                FileOutputStream(tmp).use { out -> Hashing.copyHashing(input, { b, n -> out.write(b, 0, n) }, limit, checkpoint) }
            }
            known[sha]?.let { tmp.delete(); return FileOutcome.AlreadyPresent(name, it) }
            val stored = File(originals(case.id), sha)
            if (stored.exists()) tmp.delete()
            else {
                if (!tmp.renameTo(stored)) { tmp.copyTo(stored, overwrite = true); tmp.delete() }
                storedNew = stored
            }
            stored.setReadOnly()

            val id = UUID.randomUUID().toString()
            val result = try {
                ImportPipeline.processFile(stored, name, id, zoneOf(case), checkpoint, tempDir = ImportPolicy.backupTempDir(context.cacheDir),
                    budget = CaseBudget(initialHistoryRows = ImportPolicy.historyRows(store.listImports(case.id)
                        .filter { it.kind == ExportKind.HISTORY && it.state == ImportState.IMPORTED }.map { File(originals(case.id), it.storedName) })))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readError(id, name, sha, size, e)
            }
            checkpoint.check()
            val rec = ImportRecord(id, case.id, name, stored.name, sha, size, Instant.now(), result.kind, result.state, result.readerVersion,
                result.schemaLabel, result.issues.sumOf { it.count }, result.message, case.zoneId, case.zoneConfirmed)
            store.insertImport(rec, result.issues)
            recorded = true
            known[sha] = name
            return FileOutcome.Processed(rec, result)
        } catch (e: Throwable) {
            tmp.delete()
            if (!recorded) storedNew?.let { it.setWritable(true); it.delete() }
            throw e
        }
    }

    /**
     * Limpia restos de ejecuciones interrumpidas: temporales de importación, `.part` de informes y copias de
     * originales sin registro. No toca originales registrados ni informes completos.
     */
    fun cleanupLeftovers() {
        for (c in store.listCases()) {
            val dir = storage.caseDir(c.id)
            if (!dir.exists()) continue
            AtomicFiles.leftovers(dir).forEach { it.delete() }
            val referenced = store.listImports(c.id).map { it.storedName }.toSet()
            storage.originals(c.id).listFiles()?.filter { it.isFile && (it.name.startsWith("tmp-") || it.name !in referenced) }?.forEach { it.setWritable(true); it.delete() }
            val reports = store.listReports(c.id).map { it.storedName }.toSet()
            storage.reports(c.id).listFiles()?.filter { it.isFile && it.name !in reports }?.forEach { it.delete() }
        }
    }

    private fun readError(id: String, name: String, sha: String, size: Long, e: Exception) = ImportResult(
        id, name, sha, size, ExportKind.UNKNOWN, ImportState.READ_ERROR,
        msg("import.run.error_al_leer", (e as? ImportRejected)?.text ?: (e as? MissingCopy)?.let { msg("import.missingCopy") } ?: Msg.raw(e.message ?: e.javaClass.simpleName)),
        READERS_VERSION, null, emptyList(), null, emptyMap(), null, emptyList(), null, emptyList()
    )

    /**
     * Carga el expediente y relee TODOS los originales con los lectores actuales; después hace el
     * análisis conjunto (historiales combinados, Radio, forense, agregados y episodios).
     * Si la relectura cambia el estado guardado (p. ej. un formato que antes no tenía importador), se actualiza.
     */
    fun snapshot(caseId: String, checkpoint: Checkpoint, progress: (UiText) -> Unit = {}): CaseSnapshot {
        ready()
        val case = store.getCase(caseId) ?: throw UserFacingException(UiText.res(R.string.error_case_missing))
        var imports = store.listImports(caseId)
        val results = LinkedHashMap<String, ImportResult>()
        // Presupuesto del expediente (D-046): se reserva en orden de importación; lo que no cabe no se interpreta.
        val budget = CaseBudget()
        for ((i, rec) in imports.withIndex()) {
            checkpoint.check()
            progress(UiText.res(R.string.busy_reading, i + 1, imports.size, rec.fileName))
            val file = File(originals(caseId), rec.storedName)
            val zone = DeclaredZone(ZoneId.of(rec.zoneId ?: case.zoneId), rec.zoneConfirmed)
            val r = try {
                if (!file.exists()) throw MissingCopy()
                ImportPipeline.processFile(file, rec.fileName, rec.id, zone, checkpoint, budget = budget, tempDir = ImportPolicy.backupTempDir(context.cacheDir))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                readError(rec.id, rec.fileName, rec.sha256, rec.sizeBytes, e)
            }
            results[rec.id] = r
            if (r.state != rec.state || r.kind != rec.kind || r.readerVersion != rec.readerVersion) {
                store.updateImportState(rec.copy(kind = r.kind, state = r.state, readerVersion = r.readerVersion, schemaLabel = r.schemaLabel,
                    issueCount = r.issues.sumOf { it.count }, message = r.message), r.issues)
            }
        }
        imports = store.listImports(caseId)
        checkpoint.check()
        progress(UiText.res(R.string.busy_analysing))
        val analysis = CaseAnalyzer.analyze(imports.mapNotNull { results[it.id] }, zoneOf(case), case.periodOfInterest)
        val availability = AvailabilityTable.compute(analysis.results, analysis, case.zoneConfirmed)
        val discrepancies = DeviceCrossCheck.check(case.detected, DeviceDeclaration(case.deviceSource, case.deviceLabel), case.zoneId, analysis.results)
        return CaseSnapshot(case, imports, store.listReports(caseId), results, analysis, availability, configOf(case, imports), discrepancies)
    }

    /** Comprueba que la copia privada sigue teniendo el SHA-256 registrado al importar. */
    fun verifyOriginal(rec: ImportRecord): Boolean {
        val f = File(originals(rec.caseId), rec.storedName)
        return f.exists() && FileHash.sha256(f) == rec.sha256
    }

    /**
     * Ambas modalidades y ambos idiomas salen del MISMO análisis (snapshot.analysis); solo cambian la selección de
     * bloques y la presentación (D-044). El idioma queda registrado en el PDF y en el registro del informe.
     */
    fun generateReport(snapshot: CaseSnapshot, mode: ReportMode, language: Language, crossAnnex: Boolean = false): ReportRecord {
        val c = snapshot.case
        val ctx = TextContext(
            CaseInfo(c.id, c.name, c.createdAt, c.deviceLabel, zoneOf(c), c.origin, c.analystNotes, c.periodOfInterest,
                description = c.description, deviceSource = c.deviceSource, detected = c.detected, discrepancies = snapshot.discrepancies),
            snapshot.analysis.results,
            ReportOptions(c.coordinateMode, c.identifierMode, Instant.now(), BuildConfig.VERSION_NAME, mode = mode, crossAnnex = crossAnnex)
        )
        val id = UUID.randomUUID().toString()
        val file = File(reportsDir(c.id), "$id.pdf")
        // Escritura atómica: si falla o se cancela, no queda un PDF parcial ni un registro de informe.
        val pages = AtomicFiles.write(file) { ReportBuilder(reportTexts(language)).writePdf(ctx, snapshot.analysis, it) }
        val rec = ReportRecord(id, c.id, Instant.now(), file.name, Hashing.sha256Hex(file.readBytes()), pages, "$RULESET_VERSION · $EPISODE_RULES_VERSION",
            snapshot.config.fingerprint(), configSummary(snapshot.config), mode.name, language.tag)
        try { store.insertReport(rec) } catch (e: Exception) { file.delete(); throw e }
        return rec
    }

    /** Copia un informe guardado al destino elegido por el usuario. */
    fun exportReport(rec: ReportRecord, target: Uri) {
        // «wt»: si el destino ya existía y era más largo, no deben quedar bytes del archivo anterior.
        val out: OutputStream = resolver.openOutputStream(target, ReportExport.WRITE_MODE) ?: throw UserFacingException(UiText.res(R.string.error_open_target))
        out.use { File(reportsDir(rec.caseId), rec.storedName).inputStream().use { input -> input.copyTo(it) } }
    }

    companion object {
        /** Máximo de un archivo importado (docs/limits.md). */
        const val MAX_FILE_BYTES = ImportLimits.MAX_FILE_BYTES
    }
}
