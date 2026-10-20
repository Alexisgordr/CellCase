package com.alexisgordr.cellcase.core.importing

/**
 * Versión de ICdetector escrita en una exportación: `2.10.5`, `3.0.0`, `3.0.0-beta4+6bb5ed3` (desde 3.0 lleva el
 * commit tras `+`, `AppBuildId`). Solo se usa para saber qué reglas de escritura aplicó ICdetector, nunca para
 * rechazar un archivo.
 */
object IcdVersion {
    private val RE = Regex("""^\s*(\d+)\.(\d+)(?:\.(\d+))?""")

    /** (mayor, menor, parche), o null si el texto no empieza por una versión o algún número no cabe en un Int. */
    fun parse(text: String?): Triple<Int, Int, Int>? {
        val m = RE.find(text ?: return null) ?: return null
        val major = m.groupValues[1].toIntOrNull() ?: return null
        val minor = m.groupValues[2].toIntOrNull() ?: return null
        val patch = m.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
        return Triple(major, minor, patch)
    }

    /** ¿[text] es una versión igual o posterior a mayor.menor.parche? false si no se puede leer. */
    fun atLeast(text: String?, major: Int, minor: Int, patch: Int): Boolean {
        val (a, b, c) = parse(text) ?: return false
        return compareValuesBy(Triple(a, b, c), Triple(major, minor, patch), { it.first }, { it.second }, { it.third }) >= 0
    }
}

/** Entradas propias del ZIP de Stable-Site en ICdetector 3.0. */
object StableSiteFormat {
    /** Nota de privacidad que ICdetector 3.0 pone la primera en el ZIP (`StableSiteExportNote`). */
    const val PRIVACY_NOTE = "PRIVACY.txt"
}
