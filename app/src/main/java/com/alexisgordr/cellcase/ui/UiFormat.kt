package com.alexisgordr.cellcase.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.RegionalFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Formato regional de la interfaz (D-045). Mismas reglas que el informe ([RegionalFormat]): fechas inequívocas
 * (en inglés, mes con letras o año-mes-día) y separadores del idioma. Solo cambia la escritura, nunca el valor.
 */
@Composable
fun uiFormat(): RegionalFormat = RegionalFormat.of(coreLanguage(LocalConfiguration.current.locales[0]))

/** Fecha y hora del teléfono con el mes con letras (no se confunde día y mes en ningún idioma). */
@Composable
fun formatDateTime(i: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale).withZone(ZoneId.systemDefault()).format(i)
}

@Composable
fun formatMib(bytes: Long): String = uiFormat().dec(bytes / 1048576.0) + " MiB"

/**
 * Formato de entrada de fecha y hora del periodo de interés. Cambia con el idioma, pero el valor guardado es el
 * mismo instante: se interpreta en la zona declarada del expediente, no en la del idioma.
 */
fun inputPattern(lang: Language): String = if (lang == Language.ES) "dd/MM/yyyy HH:mm" else "yyyy-MM-dd HH:mm"
