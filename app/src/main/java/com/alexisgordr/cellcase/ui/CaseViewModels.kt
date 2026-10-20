package com.alexisgordr.cellcase.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alexisgordr.cellcase.CellCaseApp
import com.alexisgordr.cellcase.R
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.importing.Checkpoint
import com.alexisgordr.cellcase.core.report.CoordinateMode
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.data.CaseRepository
import com.alexisgordr.cellcase.core.report.IdentifierMode
import com.alexisgordr.cellcase.data.CaseRecord
import com.alexisgordr.cellcase.data.CaseSnapshot
import com.alexisgordr.cellcase.data.FileOutcome
import com.alexisgordr.cellcase.data.ReportRecord
import com.alexisgordr.cellcase.data.StoredIssue
import com.alexisgordr.cellcase.logic.CaseSession
import com.alexisgordr.cellcase.logic.guardedCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

/** Estado de un trabajo en curso: siempre visible y cancelable. */
data class Busy(val label: UiText, val detail: UiText? = null)

/** Mensaje de resultado para el usuario (sin idioma fijo: se presenta en el idioma activo, D-045). */
data class Notice(val text: UiText, val isError: Boolean)

class CaseListViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as CellCaseApp).repository
    private val _cases = MutableStateFlow<List<CaseRecord>>(emptyList())
    val cases: StateFlow<List<CaseRecord>> = _cases.asStateFlow()
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()

    private fun failed(e: Exception) { _notice.value = Notice(UiText.res(R.string.notice_error, e.uiText()), true) }

    fun refresh() {
        viewModelScope.launch {
            guardedCall(::failed) { _cases.value = withContext(Dispatchers.IO) { repo.listCases() } }
        }
    }

    fun create(f: CaseForm, detected: DetectedEnvironment, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            try {
                val zoneId = ZoneId.of(f.zone.trim())
                val c = withContext(Dispatchers.IO) {
                    repo.createCase(f.name, f.description, f.deviceSource, f.deviceLabel(detected), zoneId, f.zoneConfirmed, f.origin, detected)
                }
                refresh()
                onCreated(c.id)
            } catch (e: Exception) {
                _notice.value = Notice(UiText.res(R.string.notice_create_failed, e.uiText()), true)
            }
        }
    }

    fun preview(id: String, onReady: (CaseRepository.DeletionPreview) -> Unit) {
        viewModelScope.launch { guardedCall(::failed) { onReady(withContext(Dispatchers.IO) { repo.deletionPreview(id) }) } }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { repo.deleteCase(id) }
                _notice.value = Notice(UiText.res(R.string.notice_case_deleted), false)
            } catch (e: Exception) {
                _notice.value = Notice(UiText.res(R.string.notice_delete_failed, e.uiText()), true)
            }
            refresh()
        }
    }

    fun dismissNotice() { _notice.value = null }
}

class CaseViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as CellCaseApp).repository

    private val _snapshot = MutableStateFlow<CaseSnapshot?>(null)
    val snapshot: StateFlow<CaseSnapshot?> = _snapshot.asStateFlow()
    private val _busy = MutableStateFlow<Busy?>(null)
    val busy: StateFlow<Busy?> = _busy.asStateFlow()
    private val _notice = MutableStateFlow<Notice?>(null)
    val notice: StateFlow<Notice?> = _notice.asStateFlow()
    /** Resultado por archivo de la última selección múltiple (resumen de importación). */
    private val _lastBatch = MutableStateFlow<List<FileOutcome>?>(null)
    val lastBatch: StateFlow<List<FileOutcome>?> = _lastBatch.asStateFlow()
    private val _issues = MutableStateFlow<Map<String, List<StoredIssue>>>(emptyMap())
    val issues: StateFlow<Map<String, List<StoredIssue>>> = _issues.asStateFlow()

    /** Expediente abierto y trabajo en curso: un resultado solo se publica si sigue siendo del expediente abierto. */
    private val session = CaseSession()
    private val caseId: String? get() = session.caseId

    fun open(id: String) {
        if (caseId == id && _snapshot.value != null) return
        if (session.open(id)) {
            // Otro expediente: se cancela la carga del anterior y no se arrastra nada suyo.
            _busy.value = null
            _lastBatch.value = null
            _notice.value = null
            _issues.value = emptyMap()
        }
        _snapshot.value = null
        reload()
    }

    private fun failed(e: Exception) { _notice.value = Notice(UiText.res(R.string.notice_error, e.uiText()), true) }

    /** Publica [snap] solo si es del expediente abierto. */
    private fun publish(snap: CaseSnapshot) { session.publishIfCurrent(snap.case.id) { _snapshot.value = snap } }

    /** Lanza un trabajo pesado en segundo plano con progreso, error y cancelación. */
    /**
     * Cancelación cooperativa: «Cancelar» activa [softCancel] y el trabajo se detiene en el siguiente punto de
     * control sin cancelar la corrutina, de modo que puede informar y refrescar la vista (D-038).
     */
    private val softCancel = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun run(label: UiText, cancelledMessage: UiText = UiText.res(R.string.notice_cancelled), block: suspend (Checkpoint, (UiText) -> Unit) -> Unit) {
        if (session.busy) { _notice.value = Notice(UiText.res(R.string.notice_busy), true); return }
        softCancel.set(false)
        val owner = caseId
        // Lo que este trabajo muestre (progreso, avisos) solo vale mientras su expediente siga abierto.
        fun mine() = caseId == owner
        session.track(viewModelScope.launch {
            _busy.value = Busy(label)
            try {
                withContext(Dispatchers.Default) {
                    val ctx = currentCoroutineContext()
                    block(Checkpoint { ctx.ensureActive(); if (softCancel.get()) throw CancellationException("cancelado por el usuario") }) { detail -> if (mine()) _busy.update { it?.copy(detail = detail) } }
                }
            } catch (e: CancellationException) {
                if (mine()) _notice.value = Notice(cancelledMessage, false)
            } catch (e: Exception) {
                if (mine()) failed(e)
            } finally {
                if (mine()) _busy.value = null
            }
        })
    }

    fun cancel() { softCancel.set(true) }

    fun reload() {
        val id = caseId ?: return
        run(UiText.res(R.string.busy_loading)) { cp, detail ->
            val snap = withContext(Dispatchers.IO) { repo.snapshot(id, cp, detail) }
            session.publishIfCurrent(id) { _snapshot.value = snap }
        }
    }

    /** Selección múltiple: cada archivo se procesa por separado y el resumen muestra el resultado de cada uno. */
    fun importMany(uris: List<Uri>) {
        val id = caseId ?: return
        if (uris.isEmpty()) return
        run(UiText.plural(R.plurals.busy_importing, uris.size, uris.size)) { cp, detail ->
            val report = withContext(Dispatchers.IO) { repo.importFiles(id, uris, cp, detail) }
            val outcomes = report.outcomes
            if (!session.isCurrent(id)) return@run
            _lastBatch.value = outcomes
            val ok = outcomes.count { it is FileOutcome.Processed && it.result.imported }
            val notOk = outcomes.count { (it is FileOutcome.Processed && !it.result.imported) || it is FileOutcome.Failed }
            val dup = outcomes.count { it is FileOutcome.AlreadyPresent }
            val counts = UiText.res(R.string.import_counts, UiText.plural(R.plurals.files_imported, ok, ok), UiText.plural(R.plurals.files_not_imported, notOk, notOk),
                UiText.plural(R.plurals.files_already_present, dup, dup))
            _notice.value = if (report.cancelled) Notice(
                UiText.res(R.string.notice_import_cancelled, UiText.plural(R.plurals.files_count, outcomes.size, outcomes.size), counts, report.pending.joinToString()), true)
                else Notice(UiText.res(R.string.notice_import_done, counts), notOk > 0)
            // La vista se actualiza también tras cancelar: el refresco usa un punto de control que ignora la cancelación ya atendida.
            softCancel.set(false)
            detail(UiText.res(R.string.busy_reanalysing))
            publish(withContext(Dispatchers.IO) { repo.snapshot(id, cp, detail) })
        }
    }

    fun dismissBatch() { _lastBatch.value = null }

    fun loadIssues(importId: String) {
        val id = caseId ?: return
        viewModelScope.launch {
            guardedCall(::failed) {
                val list = withContext(Dispatchers.IO) { repo.issues(importId) }
                session.publishIfCurrent(id) { _issues.update { it + (importId to list) } }
            }
        }
    }

    fun verifyOriginals() {
        val s = _snapshot.value ?: return
        run(UiText.res(R.string.busy_verifying)) { _, detail ->
            val bad = s.imports.filter { r -> detail(UiText.Raw(r.fileName)); !withContext(Dispatchers.IO) { repo.verifyOriginal(r) } }
            _notice.value = if (bad.isEmpty()) Notice(UiText.plural(R.plurals.notice_originals_ok, s.imports.size, s.imports.size), false)
                else Notice(UiText.res(R.string.notice_originals_bad, bad.joinToString { it.fileName }), true)
        }
    }

    /** Guarda y, si el cambio afecta al análisis (zona, periodo), lo recalcula desde los originales. */
    fun saveCase(update: (CaseRecord) -> CaseRecord, reanalyze: Boolean = false) {
        val s = _snapshot.value ?: return
        // Mientras hay un trabajo en curso el snapshot puede cambiar; los editores se desactivan y aquí no se guarda.
        if (session.busy) { _notice.value = Notice(UiText.res(R.string.notice_busy), true); return }
        val updated = update(s.case)
        viewModelScope.launch {
            // Un expediente borrado (UserFacingException) o un disco lleno (SQLiteException) dan un aviso, no un cierre.
            val affects = guardedCall(::failed) { withContext(Dispatchers.IO) { repo.updateCase(updated) } } ?: return@launch
            if (!session.isCurrent(updated.id)) return@launch
            _snapshot.value = s.copy(case = updated)
            if (reanalyze || affects) {
                reload()
                if (s.reports.isNotEmpty()) _notice.value = Notice(UiText.res(R.string.notice_reanalysed), false)
            }
        }
    }

    fun edit(f: CaseForm) = saveCase({ c ->
        c.copy(name = f.name.trim(), description = f.description.trim().ifEmpty { null }, deviceSource = f.deviceSource,
            deviceLabel = f.deviceLabel(c.detected), zoneId = f.zone.trim(), zoneConfirmed = f.zoneConfirmed)
    })

    fun deletionPreview(onReady: (CaseRepository.DeletionPreview) -> Unit) {
        val id = caseId ?: return
        viewModelScope.launch {
            guardedCall(::failed) { val p = withContext(Dispatchers.IO) { repo.deletionPreview(id) }; session.publishIfCurrent(id) { onReady(p) } }
        }
    }

    fun deleteCase(onDeleted: () -> Unit) {
        val id = caseId ?: return
        run(UiText.res(R.string.busy_deleting)) { _, _ ->
            withContext(Dispatchers.IO) { repo.deleteCase(id) }
            if (!session.isCurrent(id)) return@run
            _snapshot.value = null
            _busy.value = null
            session.close()
            withContext(Dispatchers.Main) { onDeleted() }
        }
    }

    /** Declaración explícita del origen (D-028). Al elegirla deja de ser una declaración heredada. */
    fun setOrigin(origin: DataOrigin) = saveCase({ it.copy(origin = origin, originInherited = false) })
    fun setNotes(text: String) = saveCase({ it.copy(analystNotes = text.ifBlank { null }) })
    fun setPrivacy(coords: CoordinateMode, ids: IdentifierMode) = saveCase({ it.copy(coordinateMode = coords, identifierMode = ids) })
    fun setPeriod(p: PeriodOfInterest?) = saveCase({ it.copy(periodOfInterest = p) }, reanalyze = true)

    /** [crossAnnex]: añade el listado completo de cruces como anexo (desactivado por defecto, §6 de cross-sources.md). [language]: idioma del informe, independiente del de la interfaz (D-044). Solo cambia la presentación. */
    fun generateReport(mode: ReportMode, language: Language, crossAnnex: Boolean = false) {
        val s = _snapshot.value ?: return
        run(UiText.res(if (mode == ReportMode.EXPLAINED) R.string.busy_report_explained else R.string.busy_report_full),
            cancelledMessage = UiText.res(R.string.notice_report_cancelled)) { _, detail ->
            detail(UiText.res(R.string.busy_layout))
            val rec = withContext(Dispatchers.IO) { repo.generateReport(s, mode, language, crossAnnex) }
            if (!session.isCurrent(s.case.id)) return@run
            _snapshot.value = s.copy(reports = listOf(rec) + s.reports)
            _notice.value = Notice(UiText.plural(R.plurals.notice_report_done, rec.pages, rec.pages), false)
        }
    }

    fun exportReport(rec: ReportRecord, target: Uri) {
        run(UiText.res(R.string.busy_exporting)) { _, _ ->
            withContext(Dispatchers.IO) { repo.exportReport(rec, target) }
            _notice.value = Notice(UiText.res(R.string.notice_exported), false)
        }
    }

    fun dismissNotice() { _notice.value = null }
}
