package com.alexisgordr.cellcase.ui

import android.content.Context
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.importing.ImportRejected
import com.alexisgordr.cellcase.core.report.ReportTextsImpl
import com.alexisgordr.cellcase.core.report.reportTexts
import java.util.Locale

/**
 * Texto para la interfaz sin idioma fijo (D-045). Los ViewModel y el repositorio devuelven [UiText]; la pantalla
 * lo presenta con los recursos del idioma activo. Así un cambio de idioma no deja mensajes en el idioma anterior.
 */
sealed interface UiText {
    data class Res(@StringRes val id: Int, val args: List<Any> = emptyList()) : UiText
    data class Plural(@PluralsRes val id: Int, val count: Int, val args: List<Any> = emptyList()) : UiText
    /** Texto del catálogo del núcleo (avisos de importación, etiquetas compartidas con el informe). */
    data class Core(val msg: Msg) : UiText
    /** Texto que no se traduce: nombres de archivo, mensajes de sistemas ajenos, datos del usuario. */
    data class Raw(val text: String) : UiText
    data class Join(val parts: List<UiText>, val separator: String = " ") : UiText

    companion object {
        fun res(@StringRes id: Int, vararg args: Any) = Res(id, args.toList())
        fun plural(@PluralsRes id: Int, count: Int, vararg args: Any) = Plural(id, count, args.toList())
    }
}

/** Error con un mensaje preparado para el usuario. */
class UserFacingException(val text: UiText) : Exception(text.toString())

/** Mensaje de una excepción para la interfaz: el preparado si existe; si no, el del sistema tal cual. */
fun Throwable.uiText(): UiText = when (this) {
    is UserFacingException -> text
    is ImportRejected -> UiText.Core(text)
    else -> UiText.Raw(message ?: javaClass.simpleName)
}

/** Idioma del núcleo que corresponde a la configuración actual: español si la interfaz está en español; si no, inglés (recursos por defecto). */
fun coreLanguage(locale: Locale): Language = if (locale.language == "es") Language.ES else Language.EN

fun Context.coreLanguage(): Language = coreLanguage(resources.configuration.locales[0])

fun UiText.asString(context: Context): String = when (this) {
    is UiText.Res -> context.getString(id, *args.map { a -> if (a is UiText) a.asString(context) else a }.toTypedArray())
    is UiText.Plural -> context.resources.getQuantityString(id, count, *args.map { a -> if (a is UiText) a.asString(context) else a }.toTypedArray())
    is UiText.Core -> msg.render(context.coreLanguage())
    is UiText.Raw -> text
    is UiText.Join -> parts.joinToString(separator) { it.asString(context) }
}

@Composable
fun UiText.asString(): String {
    LocalConfiguration.current   // se recompone al cambiar el idioma
    return asString(LocalContext.current)
}

/** Textos del núcleo (etiquetas, resúmenes) en el idioma de la interfaz. */
@Composable
fun coreTexts(): ReportTextsImpl = reportTexts(coreLanguage(LocalConfiguration.current.locales[0]))
