package com.alexisgordr.cellcase.core.model

import com.alexisgordr.cellcase.core.i18n.Msg

/**
 * Procedencia de un dato normalizado: de qué importación, archivo, entrada de ZIP y fila sale.
 * Las líneas son físicas (1-based) y cuentan la cabecera, de modo que se pueden abrir y comprobar
 * con cualquier editor de texto.
 */
data class Provenance(
    val importId: String,
    val fileName: String,
    /** Entrada interna del ZIP, o null si el archivo importado es un CSV suelto. */
    val zipEntry: String?,
    val startLine: Int,
    val endLine: Int,
    /** Índice del registro de datos (0 = primera fila tras la cabecera). */
    val recordIndex: Int
) {
    fun shortRef(): String = buildString {
        append(fileName)
        if (zipEntry != null) append("!").append(zipEntry)
        append(":").append(startLine)
        if (endLine != startLine) append("-").append(endLine)
    }
}

/** Estado del valor de un campo tras la lectura. El texto original se conserva siempre aparte. */
enum class ValueState {
    /** Valor válido y dentro de rango. */
    PRESENT,
    /** Campo vacío: dato no disponible en origen. */
    EMPTY,
    /** Valor centinela que significa «no disponible» (`N/A`, 2147483647, -999…). Se trata como ausente. */
    SENTINEL,
    /** No se puede interpretar con el tipo esperado. */
    INVALID,
    /** Interpretable, pero fuera del rango físico o documentado. Se trata como ausente en los cálculos. */
    OUT_OF_RANGE
}

enum class RadioTech { GSM, UMTS, LTE, NR, UNKNOWN }

/**
 * Identidad celular completa `MCC-MNC-TAC-CID-RADIO` (export-formats.md §0.1).
 * [joinKey] es null cuando falta algún componente o la tecnología es desconocida: en ese caso la
 * identidad **no puede usarse para relacionar registros** (D-016).
 */
data class CellIdentity(
    val mcc: String?,
    val mnc: String?,
    val tac: String?,
    val cid: String?,
    val radio: RadioTech
) {
    val isComplete: Boolean
        get() = mcc != null && mnc != null && tac != null && cid != null && radio != RadioTech.UNKNOWN

    val joinKey: String? get() = if (isComplete) "$mcc-$mnc-$tac-$cid-${radio.name}" else null

    /** Texto para mostrar, con `?` en los componentes ausentes. */
    val display: String get() = "${mcc ?: "?"}-${mnc ?: "?"}-${tac ?: "?"}-${cid ?: "?"}-${radio.name}"
}

enum class Severity { INFO, WARNING, ERROR }

/** Ejemplo concreto de una incidencia, con su procedencia y el valor original. */
data class IssueExample(val provenance: Provenance?, val column: String?, val rawValue: String?, val note: Msg? = null)

/**
 * Incidencia de validación agrupada por código y columna: miles de filas con el mismo problema
 * forman una sola incidencia con su recuento y algunos ejemplos.
 */
data class Issue(
    val code: String,
    val severity: Severity,
    val column: String?,
    /** Texto en el idioma de presentación que se elija (D-044). */
    val text: Msg,
    val count: Int,
    val examples: List<IssueExample>
) {
    /** Texto en español (idioma de referencia; lo que guardan las versiones anteriores). */
    val message: String get() = text.toString()
}

/** Acumulador de incidencias con un tope de ejemplos por grupo. */
class IssueCollector(private val maxExamples: Int = 5) {
    private data class Key(val code: String, val column: String?)
    private class Group(val severity: Severity, val message: Msg) {
        var count = 0
        val examples = ArrayList<IssueExample>()
    }

    private val groups = LinkedHashMap<Key, Group>()

    fun add(code: String, severity: Severity, column: String?, message: Msg, example: IssueExample? = null) {
        val g = groups.getOrPut(Key(code, column)) { Group(severity, message) }
        g.count++
        if (example != null && g.examples.size < maxExamples) g.examples += example
    }

    fun result(): List<Issue> = groups.map { (k, g) ->
        Issue(k.code, g.severity, k.column, g.message, g.count, g.examples.toList())
    }.sortedWith(compareByDescending<Issue> { it.severity.ordinal }.thenBy { it.code })

    fun hasErrors(): Boolean = groups.values.any { it.severity == Severity.ERROR }
}
