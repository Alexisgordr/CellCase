package com.alexisgordr.cellcase.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alexisgordr.cellcase.R
import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.SpoofingRelevance
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.model.ValueState
import com.alexisgordr.cellcase.core.report.Availability
import com.alexisgordr.cellcase.core.report.CoordinateMode
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.IdentifierMode
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.time.CaptureTime
import com.alexisgordr.cellcase.core.time.DeclaredZone
import com.alexisgordr.cellcase.core.time.TimeResolution
import com.alexisgordr.cellcase.data.CaseSnapshot
import com.alexisgordr.cellcase.data.FileOutcome
import com.alexisgordr.cellcase.data.ImportRecord
import com.alexisgordr.cellcase.data.ReportRecord
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseScreen(caseId: String, onBack: () -> Unit, vm: CaseViewModel = viewModel()) {
    LaunchedEffect(caseId) { vm.open(caseId) }
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    val issues by vm.issues.collectAsStateWithLifecycle()
    val batch by vm.lastBatch.collectAsStateWithLifecycle()

    // Selección múltiple: CSV y ZIP a la vez; el tipo se decide por el contenido, no por el MIME.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importMany(uris) }
    // Informe que se está exportando, por id y con rememberSaveable: si se gira el teléfono con el selector abierto
    // la actividad se recrea, y sin esto el destino quedaba creado y vacío (0 bytes).
    var exportingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deletePreview by remember { mutableStateOf<com.alexisgordr.cellcase.data.CaseRepository.DeletionPreview?>(null) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val r = exportingId?.let { id -> vm.snapshot.value?.reports?.firstOrNull { it.id == id } }
        if (uri != null && r != null) vm.exportReport(r, uri)
        exportingId = null
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(snap?.case?.name ?: stringResource(R.string.case_title)) },
            navigationIcon = { TextButton(onClick = onBack) { Text(stringResource(R.string.action_back)) } }
        )
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            busy?.let { b -> item { BusyCard(b) { vm.cancel() } } }
            notice?.let { n -> item { NoticeCard(n) { vm.dismissNotice() } } }
            val s = snap
            if (s == null) {
                if (busy == null) item { Text(stringResource(R.string.case_load_failed)) }
                return@LazyColumn
            }
            item { CaseHeader(s, busy == null, onOrigin = { vm.setOrigin(it) }, onEdit = { editing = true },
                onDelete = { deleting = true; deletePreview = null; vm.deletionPreview { deletePreview = it } }) }
            item {
                Section(stringResource(R.string.files_title)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = busy == null) { Text(stringResource(R.string.action_add_files)) }
                        OutlinedButton(onClick = { vm.verifyOriginals() }, enabled = busy == null && s.imports.isNotEmpty()) { Text(stringResource(R.string.action_verify_originals)) }
                    }
                    Text(stringResource(R.string.files_help), style = MaterialTheme.typography.bodySmall)
                }
            }
            batch?.let { b -> item { BatchSummary(b, s) { vm.dismissBatch() } } }
            items(s.imports, key = { it.id }) { r ->
                ImportCard(r, s.results[r.id], s, issues = issues[r.id]) { vm.loadIssues(r.id) }
            }
            item { DuplicateReview(s) }
            item { EpisodesSummary(s) }
            item { CrossSourcesSummary(s) }
            item { LocalProfileSection(s) }
            item { DataSummary(s) }
            // Con un trabajo en curso el expediente puede cambiar: los editores se desactivan hasta que termine.
            item { PeriodEditor(s, busy == null) { vm.setPeriod(it) } }
            item { NotesEditor(s.case.analystNotes, busy == null) { vm.setNotes(it) } }
            item {
                ReportSection(s, busy == null,
                    onPrivacy = { c, i -> vm.setPrivacy(c, i) },
                    onGenerate = { mode, lang, annex -> vm.generateReport(mode, lang, annex) },
                    onExport = { rec -> exportingId = rec.id; saver.launch(exportName(s.case.name, rec)) })
            }
        }
    }
    val cur = snap
    if (editing && cur != null) CaseFormDialog(
        title = stringResource(R.string.form_edit_title), initial = CaseForm.of(cur.case), detected = cur.case.detected,
        confirmLabel = stringResource(R.string.action_save), showOrigin = false,
        onDismiss = { editing = false }, onSave = { f -> editing = false; vm.edit(f) }
    )
    if (deleting && cur != null) DeleteCaseDialog(cur.case.name, deletePreview, onDismiss = { deleting = false },
        onConfirm = { deleting = false; vm.deleteCase(onBack) })
}

@Composable
fun NoticeCard(n: Notice, onDismiss: () -> Unit) {
    val colors = if (n.isError) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        else CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    Card(Modifier.fillMaxWidth().padding(horizontal = 0.dp), colors = colors) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(n.text.asString(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    }
}

@Composable
private fun BusyCard(b: Busy, onCancel: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(b.label.asString(), style = MaterialTheme.typography.titleSmall)
            b.detail?.let { Text(it.asString(), style = MaterialTheme.typography.bodySmall) }
            LinearProgressIndicator(Modifier.fillMaxWidth())
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun CaseHeader(s: CaseSnapshot, enabled: Boolean, onOrigin: (DataOrigin) -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val c = s.case
    val T = coreTexts()
    var editingOrigin by remember { mutableStateOf(false) }
    Section(stringResource(R.string.case_title)) {
        c.description?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(stringResource(R.string.case_declared_device, deviceText(c.deviceLabel)) + when (c.deviceSource) {
            DeviceSource.THIS_DEVICE -> stringResource(R.string.case_declared_this_suffix)
            DeviceSource.OTHER_DEVICE -> stringResource(R.string.case_declared_other_suffix)
            DeviceSource.NOT_DECLARED -> ""
        }, style = MaterialTheme.typography.bodyMedium)
        c.detected?.let { Text(stringResource(R.string.case_detected_device, it.deviceLabel, it.zoneId), style = MaterialTheme.typography.bodySmall) }
        Text(stringResource(if (c.zoneConfirmed) R.string.case_zone_confirmed else R.string.case_zone_unconfirmed, c.zoneId), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.case_origin, T.originText(c.origin)), style = MaterialTheme.typography.bodyMedium,
            color = if (c.origin == DataOrigin.DECLARED_REAL) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        if (c.originInherited) Text(stringResource(R.string.case_origin_inherited), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        if (s.originConflict) Text(stringResource(R.string.case_origin_conflict, s.indicatorFiles.joinToString { it.fileName }),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        else if (s.indicatorFiles.isNotEmpty()) Text(pluralStringResource(R.plurals.case_indicator_files, s.indicatorFiles.size, s.indicatorFiles.size),
            style = MaterialTheme.typography.bodySmall)
        s.discrepancies.forEach { d -> Text(stringResource(R.string.case_discrepancy, T.discrepancyText(d)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        if (editingOrigin) {
            OriginChooser(c.origin) { onOrigin(it); editingOrigin = false }
            TextButton(onClick = { editingOrigin = false }) { Text(stringResource(R.string.action_close)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit, enabled = enabled) { Text(stringResource(R.string.action_edit)) }
            if (!editingOrigin) OutlinedButton(onClick = { editingOrigin = true }, enabled = enabled) { Text(stringResource(R.string.action_origin)) }
            OutlinedButton(onClick = onDelete, enabled = enabled) { Text(stringResource(R.string.action_delete)) }
        }
    }
}

@Composable
private fun BatchSummary(outcomes: List<FileOutcome>, s: CaseSnapshot, onDismiss: () -> Unit) {
    val zone = displayZone(s)
    val T = coreTexts()
    Section(stringResource(R.string.batch_title)) {
        outcomes.forEach { o ->
            HorizontalDivider()
            Text(o.fileName, style = MaterialTheme.typography.titleSmall)
            when (o) {
                is FileOutcome.Processed -> T.importSummary(o.result, zone).forEachIndexed { i, line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = if (!o.result.imported && i == 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                is FileOutcome.AlreadyPresent -> Text(stringResource(R.string.batch_already_present, o.existingName), style = MaterialTheme.typography.bodySmall)
                is FileOutcome.Failed -> Text(stringResource(R.string.batch_failed, o.text.asString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close_summary)) }
    }
}

private fun displayZone(s: CaseSnapshot): ZoneId = if (s.case.zoneConfirmed) ZoneId.of(s.case.zoneId) else java.time.ZoneOffset.UTC

@Composable
private fun ImportCard(r: ImportRecord, result: ImportResult?, s: CaseSnapshot, issues: List<com.alexisgordr.cellcase.data.StoredIssue>?, onExpand: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    val T = coreTexts()
    Card(Modifier.fillMaxWidth().clickable { open = !open; if (open && issues == null && result == null) onExpand() }) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(r.fileName, style = MaterialTheme.typography.titleSmall)
            if (result != null) {
                T.importSummary(result, displayZone(s)).forEachIndexed { i, l ->
                    Text(l, style = MaterialTheme.typography.bodySmall, color = if (i == 0 && !result.imported) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            } else Text("${T.kindLabel(r.kind)} · ${T.stateLabel(r.state)}", style = MaterialTheme.typography.bodySmall)
            Text("SHA-256 ${r.sha256}", style = MaterialTheme.typography.labelSmall)
            if (open) {
                if (result != null && result.entries.isNotEmpty()) {
                    HorizontalDivider()
                    Text(pluralStringResource(R.plurals.zip_entries, result.entries.size, result.entries.size), style = MaterialTheme.typography.labelMedium)
                    result.entries.forEach { e ->
                        Text(listOfNotNull(e.name, T.roleLabel(e.role), e.rows?.let { pluralStringResource(R.plurals.rows_count, it, T.int(it.toLong())) }, T.bytes(e.bytes))
                            .joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    result.integrity?.let { ig ->
                        if (ig.missing.isNotEmpty()) Text(stringResource(R.string.zip_missing_listed, ig.missing.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        if (ig.unlisted.isNotEmpty()) Text(stringResource(R.string.zip_unlisted, ig.unlisted.joinToString()), style = MaterialTheme.typography.bodySmall)
                    }
                }
                HorizontalDivider()
                // Las incidencias de la relectura llevan clave y argumentos: se muestran en el idioma activo.
                // Las guardadas por versiones anteriores solo tienen el texto en español.
                val live = result?.issues
                when {
                    live != null && live.isEmpty() -> Text(stringResource(R.string.issues_none), style = MaterialTheme.typography.bodySmall)
                    live != null -> live.forEach { i ->
                        Text(listOfNotNull(sevText(i.severity), i.code, i.column, pluralStringResource(R.plurals.rows_count, i.count, T.int(i.count.toLong()))).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium)
                        Text(T.tr(i.text), style = MaterialTheme.typography.bodySmall)
                    }
                    issues == null -> Text(stringResource(R.string.issues_loading), style = MaterialTheme.typography.bodySmall)
                    issues.isEmpty() -> Text(stringResource(R.string.issues_none), style = MaterialTheme.typography.bodySmall)
                    else -> issues.forEach { i ->
                        Text(listOfNotNull(sevText(i.severity), i.code, i.column, pluralStringResource(R.plurals.rows_count, i.count, T.int(i.count.toLong()))).joinToString(" · "),
                            style = MaterialTheme.typography.labelMedium)
                        Text(i.message, style = MaterialTheme.typography.bodySmall)
                        if (i.examples.isNotEmpty()) Text(stringResource(R.string.issues_examples, i.examples), style = MaterialTheme.typography.bodySmall)
                    }
                }
            } else Text(stringResource(R.string.import_tap_hint), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun sevText(s: Severity) = stringResource(when (s) { Severity.ERROR -> R.string.severity_error; Severity.WARNING -> R.string.severity_warning; Severity.INFO -> R.string.severity_info })

/** Archivos distintos con observaciones coincidentes: se señalan para revisión; nunca se eliminan solos. */
@Composable
private fun DuplicateReview(s: CaseSnapshot) {
    val m = s.analysis.history
    val rows = m?.crossFileMatches?.size ?: 0
    val versions = s.analysis.forensicVersions.filter { it.all.size > 1 }
    if (rows == 0 && versions.isEmpty() && s.analysis.serviceDuplicates == 0) return
    val T = coreTexts()
    Section(stringResource(R.string.duplicates_title)) {
        if (rows > 0) Text(pluralStringResource(R.plurals.duplicates_rows, rows, T.int(rows.toLong())) +
            if (m!!.revisedMatches > 0) " " + pluralStringResource(R.plurals.duplicates_revised, m.revisedMatches, T.int(m.revisedMatches.toLong())) else "",
            style = MaterialTheme.typography.bodySmall)
        versions.forEach { g -> Text(pluralStringResource(R.plurals.duplicates_forensic, g.all.size, g.all.size, g.caseCode ?: "?", T.versionRelationLabel(g.relation)), style = MaterialTheme.typography.bodySmall) }
        if (s.analysis.serviceDuplicates > 0) Text(pluralStringResource(R.plurals.duplicates_radio, s.analysis.serviceDuplicates, T.int(s.analysis.serviceDuplicates.toLong())),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.duplicates_note), style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun EpisodesSummary(s: CaseSnapshot) {
    val eps = s.analysis.episodes.episodes
    val zone = displayZone(s)
    val T = coreTexts()
    val fmt = uiFormat()
    Section(stringResource(R.string.episodes_title)) {
        if (eps.isEmpty()) {
            Text(stringResource(R.string.episodes_none), style = MaterialTheme.typography.bodyMedium)
            return@Section
        }
        Text(stringResource(R.string.episodes_status, T.overallStatement(s.analysis)), style = MaterialTheme.typography.titleSmall,
            color = if (s.analysis.assessment.overall == SpoofingRelevance.NO_INDICATION) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        val pri = eps.count { it.priority == Priority.PRIORITY }; val rev = eps.count { it.priority == Priority.REVIEW }
        val ctx = eps.size - pri - rev
        Text(when {
            pri == 0 && rev == 0 -> pluralStringResource(R.plurals.episodes_only_context, ctx, ctx)
            else -> stringResource(R.string.episodes_counts, pri, rev, ctx)
        } + " " + stringResource(R.string.episodes_priority_note), style = MaterialTheme.typography.bodySmall)
        eps.sortedWith(compareBy({ it.priority.ordinal }, { it.start })).take(12).forEach { e ->
            HorizontalDivider()
            Text(stringResource(R.string.episode_line, e.number, fmt.SHORT.format(e.start.atZone(zone)), T.priorityLabel(e.priority), T.relevanceShort(e.relevance.relevance)),
                style = MaterialTheme.typography.titleSmall)
            Text(listOfNotNull(T.patternLabel(e.mainPattern, e), pluralStringResource(R.plurals.observations_count, e.moments, e.moments),
                if (e.recurrenceDays >= 2) pluralStringResource(R.plurals.recurs_on_days, e.recurrenceDays, e.recurrenceDays) else null,
                if (e.onlySubthreshold) stringResource(R.string.episode_subthreshold) else null).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            Text(T.priorityReason(e), style = MaterialTheme.typography.labelSmall)
        }
        val missing = s.availability.filter { it.status != Availability.AVAILABLE }
        if (missing.isNotEmpty()) Text(pluralStringResource(R.plurals.analyses_missing, missing.size, missing.size), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * Coincidencias y diferencias entre fuentes (docs/cross-sources.md). Presenta el mismo resultado estructurado que
 * los informes ([com.alexisgordr.cellcase.core.analysis.CrossResult]) con los textos del núcleo en el idioma de la
 * interfaz: resumen siempre visible y detalle desplegable. No recalcula nada.
 */
@Composable
private fun CrossSourcesSummary(s: CaseSnapshot) {
    val r = s.analysis.cross ?: return
    val T = coreTexts()
    val fmt = uiFormat()
    val zone = displayZone(s)
    var open by remember { mutableStateOf(false) }
    Section(stringResource(R.string.cross_title)) {
        T.crossWhyNone(r)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        if (r.findings.isEmpty()) return@Section
        Text(T.crossCounts(r), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.cross_note), style = MaterialTheme.typography.labelSmall)
        TextButton(onClick = { open = !open }) { Text(stringResource(if (open) R.string.cross_hide_detail else R.string.cross_show_detail)) }
        val v = remember(r) { com.alexisgordr.cellcase.core.analysis.CrossSummary.of(r) }
        Text(pluralStringResource(R.plurals.cross_groups, v.summary.size, v.summary.size, v.totalResults), style = MaterialTheme.typography.bodySmall)
        if (open) {
            val time: (java.time.Instant) -> String = { fmt.SHORT.format(it.atZone(zone)) }
            v.summary.forEach { g -> CrossGroupItem(g, T, time) }
            Text(stringResource(R.string.cross_detail_title), style = MaterialTheme.typography.titleSmall)
            if (v.detail.isEmpty()) Text(stringResource(R.string.cross_detail_none), style = MaterialTheme.typography.bodySmall)
            v.detail.forEach { g -> CrossGroupItem(g, T, time, withEpisodes = true) }
            Text(stringResource(R.string.cross_full_in_annex), style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun CrossGroupItem(g: com.alexisgordr.cellcase.core.analysis.CrossGroup, T: com.alexisgordr.cellcase.core.report.ReportTextsImpl,
                           time: (java.time.Instant) -> String, withEpisodes: Boolean = false) {
    HorizontalDivider()
    Text((if (withEpisodes && g.episodes.isNotEmpty()) g.episodes.joinToString(", ") { "E$it" } + " · " else "") +
        "${g.check.id} · ${T.crossCheckLabel(g.check)} — ${T.crossOutcomeLabel(g.outcome)}", style = MaterialTheme.typography.titleSmall,
        color = if (g.outcome == com.alexisgordr.cellcase.core.analysis.CrossOutcome.DIFFERENCE) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Text(T.crossGroupSources(g), style = MaterialTheme.typography.labelMedium)
    Text(T.crossGroupScope(g, time), style = MaterialTheme.typography.labelMedium)
    Text(T.crossGroupExplanation(g), style = MaterialTheme.typography.bodySmall)
    Text(T.crossGroupExamples(g, { it }, time), style = MaterialTheme.typography.bodySmall)
    Text(T.crossGroupLimitations(g), style = MaterialTheme.typography.labelSmall)
}

/** Perfil local de ICdetector 3.0: contexto en sombra (no cambia la valoración). Solo si el historial lo trae. */
@Composable
private fun LocalProfileSection(s: CaseSnapshot) {
    val p = s.analysis.localProfile ?: return
    val T = coreTexts()
    Section(stringResource(R.string.profile_title)) {
        Text(stringResource(R.string.profile_note), style = MaterialTheme.typography.labelSmall)
        if (p.evaluated == 0) {
            Text(stringResource(R.string.profile_none), style = MaterialTheme.typography.bodyMedium)
            return@Section
        }
        Text(pluralStringResource(R.plurals.profile_evaluated, p.evaluated, T.int(p.evaluated.toLong()), T.int(p.rows.toLong())), style = MaterialTheme.typography.bodyMedium)
        Text(p.byLevel.entries.joinToString(" · ") { "${it.key} ${T.int(it.value.toLong())}" }, style = MaterialTheme.typography.bodySmall)
        if (p.mixedAlgorithms) Text(stringResource(R.string.profile_mixed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        p.episodes.filter { it.evaluated > 0 }.forEach { e ->
            Text("E${e.episode}: " + e.byLevel.entries.joinToString(" · ") { "${it.key} ${T.int(it.value.toLong())}" } +
                (e.extreme?.score?.let { "  ·  max ${"%.2f".format(it)}" } ?: ""), style = MaterialTheme.typography.bodySmall)
        }
        if (p.deviatedOutsideEpisodes > 0) Text(pluralStringResource(R.plurals.profile_outside, p.deviatedOutsideEpisodes, T.int(p.deviatedOutsideEpisodes.toLong())), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun DataSummary(s: CaseSnapshot) {
    val a = s.analysis.historyAnalysis
    val fmt = uiFormat()
    Section(stringResource(R.string.history_title)) {
        if (a == null) {
            Text(stringResource(R.string.history_none), style = MaterialTheme.typography.bodyMedium)
            return@Section
        }
        val p = a.period
        val first = p.first?.time?.local; val last = p.last?.time?.local
        if (first != null && last != null) Text(stringResource(R.string.history_period, fmt.local(first), fmt.local(last)), style = MaterialTheme.typography.bodyMedium)
        Text(listOf(pluralStringResource(R.plurals.rows_count, p.totalRows, fmt.int(p.totalRows.toLong())),
            pluralStringResource(R.plurals.days_with_data, p.daysWithData, p.daysWithData),
            pluralStringResource(R.plurals.serving_identities, a.cells.cells.size, a.cells.cells.size)).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(R.string.history_times, fmt.int(a.time.exact.toLong()), a.time.ambiguous.size, a.time.nonexistent.size), style = MaterialTheme.typography.bodySmall)
        fun pct(col: String) = a.fields.fields.firstOrNull { it.column == col }?.let { if (it.total == 0) "—" else fmt.dec(100.0 * it.usable / it.total) + " %" } ?: "—"
        Text(stringResource(R.string.history_valid, pct("Lat"), pct("TA"), pct("RSRQ"), pct("SINR"), pct("ServiceState")), style = MaterialTheme.typography.bodySmall)
        val sentinels = a.fields.fields.sumOf { it.count(ValueState.SENTINEL) }
        Text(stringResource(R.string.history_sentinels, fmt.int(sentinels.toLong()), a.fields.rejectedRows), style = MaterialTheme.typography.bodySmall)
        Text(if (a.sampling.evaluable) stringResource(R.string.history_gaps, a.sampling.gaps.size) else stringResource(R.string.history_gaps_not_evaluable), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.history_collector, a.collector.reasonRows.size, a.collector.subthresholdRows.size), style = MaterialTheme.typography.bodySmall)
        p.rowsInPeriod?.let { Text(stringResource(R.string.history_in_period, fmt.int(it.toLong()), p.rowsNotAssignable ?: 0), style = MaterialTheme.typography.bodySmall) }
        (s.analysis.history?.sourceIds?.size ?: 0).let { if (it > 1) Text(pluralStringResource(R.plurals.history_combined, it, it), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PeriodEditor(s: CaseSnapshot, enabled: Boolean, onSave: (PeriodOfInterest?) -> Unit) {
    val zone = DeclaredZone(ZoneId.of(s.case.zoneId), s.case.zoneConfirmed)
    val pattern = inputPattern(coreLanguage(LocalConfiguration.current.locales[0]))
    val input = remember(pattern) { DateTimeFormatter.ofPattern(pattern) }
    val current = s.case.periodOfInterest
    var label by remember(current) { mutableStateOf(current?.label ?: "") }
    var start by remember(current, pattern) { mutableStateOf(current?.start?.atZone(zone.zoneId)?.format(input) ?: "") }
    var end by remember(current, pattern) { mutableStateOf(current?.end?.atZone(zone.zoneId)?.format(input) ?: "") }
    var error by remember { mutableStateOf<UiText?>(null) }
    val defaultLabel = stringResource(R.string.period_default_label)
    Section(stringResource(R.string.period_title)) {
        Text(stringResource(R.string.period_help, zone.zoneId.id), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(label, { label = it }, label = { Text(stringResource(R.string.period_label)) }, singleLine = true, enabled = enabled, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(start, { start = it }, label = { Text(stringResource(R.string.period_start, pattern.lowercase())) }, singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
            OutlinedTextField(end, { end = it }, label = { Text(stringResource(R.string.period_end, pattern.lowercase())) }, singleLine = true, enabled = enabled, modifier = Modifier.weight(1f))
        }
        error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                fun resolve(t: String): java.time.Instant? {
                    val local = runCatching { LocalDateTime.parse(t.trim(), input) }.getOrNull() ?: run { error = UiText.res(R.string.period_bad_format, t); return null }
                    return when (val r = CaptureTime.resolve(local, zone)) {
                        is TimeResolution.Exact -> r.instant
                        is TimeResolution.Ambiguous -> { error = UiText.res(R.string.period_ambiguous, t); null }
                        is TimeResolution.Nonexistent -> { error = UiText.res(R.string.period_nonexistent, t); null }
                        TimeResolution.NoConfirmedZone -> { error = UiText.res(R.string.period_confirm_zone); null }
                    }
                }
                error = null
                val a = resolve(start) ?: return@Button
                val b = resolve(end) ?: return@Button
                if (b.isBefore(a)) { error = UiText.res(R.string.period_end_before_start); return@Button }
                onSave(PeriodOfInterest(a, b, label.ifBlank { defaultLabel }))
            }, enabled = enabled) { Text(stringResource(R.string.action_save_period)) }
            if (current != null) OutlinedButton(onClick = { onSave(null) }, enabled = enabled) { Text(stringResource(R.string.action_remove)) }
        }
    }
}

@Composable
private fun NotesEditor(notes: String?, enabled: Boolean, onSave: (String) -> Unit) {
    var text by remember(notes) { mutableStateOf(notes ?: "") }
    Section(stringResource(R.string.notes_title)) {
        Text(stringResource(R.string.notes_help), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, enabled = enabled)
        Button(onClick = { onSave(text) }, enabled = enabled && text != (notes ?: "")) { Text(stringResource(R.string.action_save_notes)) }
    }
}

@Composable
private fun ReportSection(
    s: CaseSnapshot, enabled: Boolean,
    onPrivacy: (CoordinateMode, IdentifierMode) -> Unit, onGenerate: (ReportMode, Language, Boolean) -> Unit, onExport: (ReportRecord) -> Unit
) {
    var mode by remember { mutableStateOf(ReportMode.EXPLAINED) }
    val uiLang = coreLanguage(LocalConfiguration.current.locales[0])
    var lang by remember(uiLang) { mutableStateOf(uiLang) }
    var crossAnnex by remember { mutableStateOf(false) }
    Section(stringResource(R.string.report_title)) {
        Text(stringResource(R.string.report_mode), style = MaterialTheme.typography.labelLarge)
        listOf(ReportMode.EXPLAINED to R.string.report_mode_explained, ReportMode.FULL to R.string.report_mode_full).forEach { (m, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { mode = m }) {
                RadioButton(selected = mode == m, onClick = { mode = m })
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
        }
        Text(stringResource(R.string.report_same_analysis), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.report_language), style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Language.ES to R.string.language_spanish, Language.EN to R.string.language_english).forEach { (l, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { lang = l }) {
                    RadioButton(selected = lang == l, onClick = { lang = l })
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Text(stringResource(R.string.report_language_note), style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.report_coordinates), style = MaterialTheme.typography.labelLarge)
        listOf(CoordinateMode.OMIT to R.string.coords_omit, CoordinateMode.ROUND_2 to R.string.coords_round2, CoordinateMode.ROUND_3 to R.string.coords_round3, CoordinateMode.FULL to R.string.coords_full)
            .forEach { (m, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(enabled = enabled) { onPrivacy(m, s.case.identifierMode) }) {
                    RadioButton(selected = s.case.coordinateMode == m, onClick = { onPrivacy(m, s.case.identifierMode) }, enabled = enabled)
                    Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                }
            }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = s.case.identifierMode == IdentifierMode.MASKED, enabled = enabled,
                onCheckedChange = { onPrivacy(s.case.coordinateMode, if (it) IdentifierMode.MASKED else IdentifierMode.FULL) })
            Text("  " + stringResource(R.string.report_mask_ids), style = MaterialTheme.typography.bodyMedium)
        }
        Text(stringResource(R.string.report_rounding_note), style = MaterialTheme.typography.bodySmall)
        if (mode == ReportMode.FULL && s.analysis.cross?.findings?.isNotEmpty() == true) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = crossAnnex, onCheckedChange = { crossAnnex = it })
                Text("  " + stringResource(R.string.report_cross_annex), style = MaterialTheme.typography.bodyMedium)
            }
            Text(stringResource(R.string.report_cross_annex_note), style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = { onGenerate(mode, lang, mode == ReportMode.FULL && crossAnnex) }, enabled = enabled) { Text(stringResource(R.string.action_generate_pdf)) }
        s.reports.forEach { r ->
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(listOf(modeLabel(r.mode), languageLabel(r.language), formatDateTime(r.createdAt), pluralStringResource(R.plurals.pages_count, r.pages, r.pages))
                        .joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                    if (s.isOutdated(r)) Text(stringResource(R.string.report_outdated, r.configSummary ?: "—"),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                    Text("${r.ruleset} · SHA-256 ${r.sha256.take(16)}…", style = MaterialTheme.typography.labelSmall)
                }
                androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onExport(r) }, enabled = enabled) { Text(stringResource(R.string.action_save_copy)) }
            }
        }
    }
}

@Composable
private fun modeLabel(m: String) = stringResource(when (m) { "EXPLAINED" -> R.string.mode_explained; "SUMMARY" -> R.string.mode_summary_old; else -> R.string.mode_full })

@Composable
private fun languageLabel(tag: String) = stringResource(if (tag == "en") R.string.language_english else R.string.language_spanish)

/** Nombre del archivo exportado en el idioma del informe; sin caracteres que den problemas en otros sistemas. */
private fun exportName(caseName: String, r: ReportRecord): String =
    com.alexisgordr.cellcase.logic.ReportFileName.of(caseName, r.mode, r.language, r.id)
