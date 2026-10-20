package com.alexisgordr.cellcase.core.csv

/**
 * Apóstrofo protector de ICdetector 3.0 (`CsvText.guard`, export-formats.md §0.2). Al escribir un CSV, ICdetector
 * antepone `'` a un texto que una hoja de cálculo podría tomar por fórmula: el que empieza por `=`, `+`, `-`, `@`
 * (o sus variantes de ancho completo), por un carácter de control o por `'`. Un número literal (`-95`, `40.4168`)
 * no se toca. El cambio es reversible: quitar exactamente UN apóstrofo devuelve el valor original.
 *
 * [unguard] solo quita el apóstrofo cuando ICdetector lo habría puesto: si el resto del texto es uno de los que
 * ICdetector protege. Así un texto que ya empezaba por `'` sin estar protegido (exportaciones 2.10.x) no cambia.
 */
object CsvGuard {
    const val TEXT_GUARD = '\''
    private val FORMULA_START = setOf('=', '+', '-', '@', '\uFF1D', '\uFF0B', '\uFF0D', '\uFF20', TEXT_GUARD)
    private val PLAIN_NUMBER = Regex("""[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?""")

    /** Lo que ICdetector 3.0 escribe para [value] (misma regla que `CsvText.guard`). */
    fun guard(value: String): String {
        if (value.isEmpty()) return value
        val first = value[0]
        val risky = first in FORMULA_START || Character.isISOControl(first)
        if (!risky || PLAIN_NUMBER.matches(value)) return value
        return TEXT_GUARD + value
    }

    /** Valor original de un campo escrito por ICdetector 3.0, o el mismo texto si no lleva protección. */
    fun unguard(field: String): String {
        if (field.length < 2 || field[0] != TEXT_GUARD) return field
        val rest = field.substring(1)
        return if (guard(rest) == field) rest else field
    }

    /** ¿Este campo lleva el apóstrofo protector? */
    fun isGuarded(field: String): Boolean = unguard(field).length != field.length
}
