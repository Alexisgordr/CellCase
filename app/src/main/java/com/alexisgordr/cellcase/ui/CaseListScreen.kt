package com.alexisgordr.cellcase.ui

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.alexisgordr.cellcase.R
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.data.CaseRecord
import com.alexisgordr.cellcase.data.CaseRepository
import com.alexisgordr.cellcase.data.DeviceInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaseListScreen(onOpen: (String) -> Unit, vm: CaseListViewModel = viewModel()) {
    val cases by vm.cases.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()
    var creating by remember { mutableStateOf(false) }
    var choosingLanguage by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<CaseRecord?>(null) }
    var preview by remember { mutableStateOf<CaseRepository.DeletionPreview?>(null) }
    val detected = remember { DeviceInfo.detect() }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.cases_title)) },
                actions = { TextButton(onClick = { choosingLanguage = true }) { Text(stringResource(R.string.action_language)) } }
            )
        },
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { creating = true }) { Text(stringResource(R.string.action_new_case)) } }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            notice?.let { NoticeCard(it) { vm.dismissNotice() } }
            if (cases.isEmpty()) Text(stringResource(R.string.cases_empty), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cases, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().clickable { onOpen(c.id) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(c.name, style = MaterialTheme.typography.titleMedium)
                            c.description?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                            Text(stringResource(R.string.case_declared_device, deviceText(c.deviceLabel)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.case_zone_created, c.zoneId, formatDateTime(c.createdAt)), style = MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.case_origin_short, originShort(c.origin)) + if (c.originInherited) stringResource(R.string.case_origin_inherited_suffix) else "",
                                color = if (c.origin == DataOrigin.DECLARED_REAL) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(onClick = { onOpen(c.id) }) { Text(stringResource(R.string.action_open)) }
                                TextButton(onClick = { deleting = c; preview = null; vm.preview(c.id) { preview = it } }) { Text(stringResource(R.string.action_delete)) }
                            }
                        }
                    }
                }
            }
        }
    }

    if (creating) CaseFormDialog(
        title = stringResource(R.string.form_new_title), initial = CaseForm.forNew(detected), detected = detected, confirmLabel = stringResource(R.string.action_create),
        showOrigin = true, onDismiss = { creating = false },
        onSave = { f -> vm.create(f, detected) { id -> creating = false; onOpen(id) } }
    )
    deleting?.let { c -> DeleteCaseDialog(c.name, preview, onDismiss = { deleting = null }, onConfirm = { deleting = null; vm.delete(c.id) }) }
    if (choosingLanguage) LanguageDialog { choosingLanguage = false }
}

/**
 * Idioma de la interfaz (D-045): seguir el del sistema, español o inglés. Se usa el mecanismo oficial de idioma por
 * aplicación (AppCompat): en Android 13+ lo guarda el sistema; en Android 8–12 lo guarda AppCompat
 * (`autoStoreLocales`). Cambiarlo no toca expedientes, archivos ni informes: solo recrea la pantalla.
 */
@Composable
fun LanguageDialog(onDismiss: () -> Unit) {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val options = listOf("" to R.string.language_system, "es" to R.string.language_spanish, "en" to R.string.language_english)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_title)) },
        text = {
            Column {
                options.forEach { (tag, label) ->
                    val selected = if (tag.isEmpty()) current.isEmpty() else current.startsWith(tag)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { applyLanguage(tag); onDismiss() }) {
                        RadioButton(selected = selected, onClick = { applyLanguage(tag); onDismiss() })
                        Text(stringResource(label), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Text(stringResource(R.string.language_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

private fun applyLanguage(tag: String) =
    AppCompatDelegate.setApplicationLocales(if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))

/** Etiqueta del dispositivo guardada: los valores internos «no declarado» y «este móvil» se muestran en el idioma activo. */
@Composable
fun deviceText(label: String): String = when (label) {
    CaseForm.NOT_DECLARED_LABEL, "" -> stringResource(R.string.device_not_declared)
    CaseForm.THIS_DEVICE_LABEL -> stringResource(R.string.device_this_phone)
    else -> label
}

@Composable
fun originShort(o: DataOrigin) = stringResource(when (o) {
    DataOrigin.DECLARED_REAL -> R.string.origin_short_real
    DataOrigin.SYNTHETIC -> R.string.origin_short_synthetic
    DataOrigin.MIXED -> R.string.origin_short_mixed
    DataOrigin.UNDETERMINED -> R.string.origin_short_undetermined
})

/** Elección explícita del origen de los datos (D-028). Se puede cambiar después en el expediente. */
@Composable
fun OriginChooser(selected: DataOrigin, onSelect: (DataOrigin) -> Unit) {
    Column {
        Text(stringResource(R.string.origin_title), style = MaterialTheme.typography.labelLarge)
        listOf(
            DataOrigin.UNDETERMINED to R.string.origin_undetermined,
            DataOrigin.DECLARED_REAL to R.string.origin_real,
            DataOrigin.SYNTHETIC to R.string.origin_synthetic,
            DataOrigin.MIXED to R.string.origin_mixed
        ).forEach { (o, label) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onSelect(o) }) {
                RadioButton(selected = selected == o, onClick = { onSelect(o) })
                Text(stringResource(label), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
