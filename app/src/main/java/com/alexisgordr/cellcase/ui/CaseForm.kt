package com.alexisgordr.cellcase.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.alexisgordr.cellcase.R
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.data.CaseRecord
import com.alexisgordr.cellcase.data.CaseRepository
import java.time.ZoneId

/** Datos del formulario de expediente (creación o edición). */
data class CaseForm(
    val name: String,
    val description: String,
    val deviceSource: DeviceSource,
    /** Texto del dispositivo declarado cuando es «otro dispositivo». */
    val otherDevice: String,
    val zone: String,
    val zoneConfirmed: Boolean,
    val origin: DataOrigin
) {
    /** Etiqueta que se guarda como dispositivo declarado. «Este móvil» copia el modelo detectado, pero sigue siendo una declaración. */
    fun deviceLabel(detected: DetectedEnvironment?): String = when (deviceSource) {
        DeviceSource.THIS_DEVICE -> detected?.deviceLabel ?: THIS_DEVICE_LABEL
        DeviceSource.OTHER_DEVICE -> otherDevice.trim()
        DeviceSource.NOT_DECLARED -> NOT_DECLARED_LABEL
    }

    companion object {
        /**
         * Valores internos que se guardan como etiqueta (compatibles con versiones anteriores). No se muestran tal
         * cual: la interfaz y el informe los presentan en su idioma ([deviceText]).
         */
        const val NOT_DECLARED_LABEL = "no declarado"
        const val THIS_DEVICE_LABEL = "este móvil"

        fun forNew(detected: DetectedEnvironment) = CaseForm("", "", DeviceSource.NOT_DECLARED, "", detected.zoneId, false, DataOrigin.UNDETERMINED)
        fun of(c: CaseRecord) = CaseForm(c.name, c.description.orEmpty(), c.deviceSource,
            if (c.deviceSource == DeviceSource.OTHER_DEVICE) c.deviceLabel else "", c.zoneId, c.zoneConfirmed, c.origin)
    }
}

/**
 * Formulario común de creación y edición (D-032): muestra el dispositivo de ANÁLISIS detectado, deja declarar
 * si las capturas proceden de este móvil o de otro, y sugiere la zona actual sin darla por buena.
 */
@Composable
fun CaseFormDialog(
    title: String,
    initial: CaseForm,
    detected: DetectedEnvironment?,
    confirmLabel: String,
    showOrigin: Boolean,
    onDismiss: () -> Unit,
    onSave: (CaseForm) -> Unit
) {
    var f by remember { mutableStateOf(initial) }
    val zoneValid = remember(f.zone) { runCatching { ZoneId.of(f.zone.trim()) }.isSuccess }
    val deviceOk = f.deviceSource != DeviceSource.OTHER_DEVICE || f.otherDevice.isNotBlank()
    val canSave = f.name.isNotBlank() && zoneValid && f.zoneConfirmed && deviceOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(f.name, { f = f.copy(name = it) }, label = { Text(stringResource(R.string.form_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(f.description, { f = f.copy(description = it) }, label = { Text(stringResource(R.string.form_description)) }, minLines = 2, modifier = Modifier.fillMaxWidth())

                Text(stringResource(R.string.form_detected_title), style = MaterialTheme.typography.labelLarge)
                Text(detected?.deviceLabel ?: stringResource(R.string.form_not_available), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.form_detected_note), style = MaterialTheme.typography.bodySmall)

                Text(stringResource(R.string.form_source_title), style = MaterialTheme.typography.labelLarge)
                listOf(
                    DeviceSource.THIS_DEVICE to stringResource(R.string.form_source_this),
                    DeviceSource.OTHER_DEVICE to stringResource(R.string.form_source_other),
                    DeviceSource.NOT_DECLARED to stringResource(R.string.form_source_none)
                ).forEach { (src, label) ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { f = f.copy(deviceSource = src) }) {
                        RadioButton(selected = f.deviceSource == src, onClick = { f = f.copy(deviceSource = src) })
                        Text(label, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (f.deviceSource == DeviceSource.OTHER_DEVICE) OutlinedTextField(f.otherDevice, { f = f.copy(otherDevice = it) },
                    label = { Text(stringResource(R.string.form_other_device)) }, singleLine = true, isError = !deviceOk, modifier = Modifier.fillMaxWidth())

                OutlinedTextField(f.zone, { f = f.copy(zone = it, zoneConfirmed = false) }, label = { Text(stringResource(R.string.form_zone)) },
                    singleLine = true, isError = !zoneValid, modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text(if (!zoneValid) stringResource(R.string.form_zone_invalid)
                            else stringResource(R.string.form_zone_suggested, detected?.zoneId ?: stringResource(R.string.form_unknown)))
                    })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.zoneConfirmed, { f = f.copy(zoneConfirmed = it) }, enabled = zoneValid)
                    Text(stringResource(R.string.form_zone_confirm), style = MaterialTheme.typography.bodySmall)
                }
                if (showOrigin) OriginChooser(f.origin) { f = f.copy(origin = it) }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(f) }, enabled = canSave) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

/** Confirmación de borrado: explica exactamente qué se borra y qué no (D-033). */
@Composable
fun DeleteCaseDialog(name: String, preview: CaseRepository.DeletionPreview?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.delete_intro), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.delete_case_item), style = MaterialTheme.typography.bodySmall)
                Text(if (preview == null) stringResource(R.string.delete_records_item)
                    else stringResource(R.string.delete_records_item_counts, pluralStringResource(R.plurals.imports_count, preview.imports, preview.imports),
                        pluralStringResource(R.plurals.reports_count, preview.reports, preview.reports)), style = MaterialTheme.typography.bodySmall)
                Text(if (preview == null) stringResource(R.string.delete_copies_item)
                    else stringResource(R.string.delete_copies_item_size, formatMib(preview.bytes)), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.delete_not_deleted), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.delete_irreversible), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}
