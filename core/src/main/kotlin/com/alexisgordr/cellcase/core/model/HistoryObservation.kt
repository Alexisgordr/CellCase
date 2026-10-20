package com.alexisgordr.cellcase.core.model

import com.alexisgordr.cellcase.core.time.RecordedTime

/**
 * Una fila normalizada del historial de ICdetector: la celda **servidora** en un momento concreto
 * (export-formats.md §1). Se conserva el texto original de cada columna en [raw]; los campos
 * tipados son la interpretación normalizada (null = ausente, centinela, inválido o fuera de rango).
 * [states] solo contiene las columnas cuyo estado no es [ValueState.PRESENT].
 */
data class HistoryObservation(
    val provenance: Provenance,
    val time: RecordedTime,
    val identity: CellIdentity,
    val netTypeLabel: String?,
    val dbm: Int?,
    val verified: String?,
    val securityScore: Int?,
    val failedHeuristics: String?,
    val lat: Double?,
    val lon: Double?,
    val pci: Int?,
    val arfcn: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val anomalyConfidence: Double?,
    val apiLat: Double?,
    val apiLon: Double?,
    val timingAdvance: Int?,
    val taUnit: String?,
    val taMeters: Int?,
    val servingConnection: String?,
    val bandwidthKhz: Int?,
    val bands: List<Int>?,
    val additionalPlmns: List<String>?,
    val csgIndicator: Boolean?,
    val csgIdentity: Long?,
    val csgName: String?,
    val secondaryCarriers: List<SecondaryCarrier>?,
    val serviceState: String?,
    val networkOperator: String?,
    val simOperator: String?,
    val networkRoaming: Boolean?,
    /** Texto original por nombre de columna, incluidas las columnas no interpretadas. */
    val raw: Map<String, String>,
    val states: Map<String, ValueState>,
    /** Columnas presentes en el archivo que CellCase no interpreta (D-012). */
    val uninterpreted: Map<String, String>,
    // ── ICdetector 3.0 (export-formats.md §1.8). Null en historiales anteriores o en filas que no lo traen. ──
    /** Instante inequívoco registrado por ICdetector (`ObservedAtUtc`), con milisegundos. */
    val observedAtUtc: java.time.Instant? = null,
    /** Reglas que ICdetector no pudo evaluar en esta fila (`H1;H9`, `NONE` si se evaluaron todas). */
    val notEvaluatedHeuristics: String? = null,
    /** Precisión del GPS de la posición de la fila, en metros. */
    val gpsAccuracyM: Double? = null,
    /** Versión de ICdetector que grabó la fila (`3.0.0+commit`). */
    val appVersion: String? = null,
    /** Modo de localización: CONTINUOUS, INTELLIGENT o ADAPTIVE. */
    val locationMode: String? = null,
    /** Evaluación del perfil local de ICdetector (modo sombra, sin calibrar), o null si la fila no tiene. */
    val profile: ProfileEvaluation? = null,
    /** Identificador de la fila en la base de datos de ICdetector (`RowId` = `history.id`). */
    val rowId: Long? = null,
    /** Identificador de la lectura: dos filas de la misma lectura lo comparten (`ObservationId`). */
    val observationId: String? = null
) {
    fun stateOf(column: String): ValueState = states[column] ?: ValueState.PRESENT

    /** Clasificación del texto `FailedHeuristics` producido por ICdetector (resultado del colector). */
    val collectorVerdict: CollectorVerdict
        get() {
            val t = failedHeuristics?.trim() ?: return CollectorVerdict.UNKNOWN
            return when {
                t.isEmpty() -> CollectorVerdict.UNKNOWN
                t == "OK" -> CollectorVerdict.OK
                t.startsWith(SUBTHRESHOLD_PREFIX) -> CollectorVerdict.SUBTHRESHOLD
                else -> CollectorVerdict.REASON_WITHOUT_PREFIX
            }
        }

    companion object {
        const val SUBTHRESHOLD_PREFIX = "[sub-umbral]"
    }
}

/** Lo que dice el texto `FailedHeuristics` de ICdetector. No es una conclusión de CellCase. */
enum class CollectorVerdict { OK, SUBTHRESHOLD, REASON_WITHOUT_PREFIX, UNKNOWN }

/** Portadora secundaria `RADIO:ARFCN:PCI` (export-formats.md §1.3, columna 31). */
data class SecondaryCarrier(val radio: RadioTech, val arfcn: Int?, val pci: Int?)

/**
 * Evaluación del **perfil local** de ICdetector 3.0 para una fila (columnas `Profile*`, export-formats.md §1.8).
 * Es contexto: ICdetector la calcula en modo sombra, con umbrales **no calibrados**, y no interviene en sus
 * alertas. CellCase la muestra y la resume; no la usa para valorar nada (D-053).
 */
data class ProfileEvaluation(
    /** `SHADOW` o `EXPERIMENTAL_DISPLAY` (opción experimental visible). */
    val mode: String?,
    /** `NONE`, `LOW`, `MODERATE`, `HIGH` o `ABSTAIN`. */
    val level: String?,
    /** Mayor desviación entre las métricas evaluadas, en desviaciones típicas (no es una probabilidad). */
    val score: Double?,
    val metricsEvaluated: Int?,
    val metricsTotal: Int?,
    /** Motivo de abstención (`NO_PROFILE`, `PROFILE_IMMATURE`…), solo con nivel `ABSTAIN`. */
    val abstainReason: String?,
    val algorithm: Int?,
    /** Generación y revisión de cada referencia usada (`SIGNAL_DBM@g…:42`). */
    val revision: String?,
    /** Valores comparados (`SIGNAL_DBM -97 vs -84.000±3.000 z=4.333`). */
    val evidence: String?,
    /** La observación se usó después para aprender. */
    val learned: Boolean?,
    /** La observación se reservó para evaluar (no se aprendió de ella). */
    val holdout: Boolean?
) {
    val abstained: Boolean get() = level == "ABSTAIN"

    companion object {
        val LEVELS = listOf("NONE", "LOW", "MODERATE", "HIGH", "ABSTAIN")
        val MODES = setOf("SHADOW", "EXPERIMENTAL_DISPLAY")
        val ABSTAIN_REASONS = setOf("INCOMPLETE_CONTEXT", "NO_PROFILE", "PROFILE_IMMATURE", "PROFILE_STALE", "INCOMPATIBLE_DEVICE",
            "INCOMPATIBLE_RADIO", "INCOMPATIBLE_ALGORITHM", "NO_COMPATIBLE_METRIC")
    }
}
