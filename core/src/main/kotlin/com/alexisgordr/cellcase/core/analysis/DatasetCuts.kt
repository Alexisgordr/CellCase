package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.IcdVersion
import com.alexisgordr.cellcase.core.model.HistoryObservation
import java.time.Instant

/**
 * Cortes de dataset de ICdetector: cambios de una versión que hacen que un mismo campo no signifique lo mismo antes y
 * después (CHANGELOG de ICdetector, marcas «Dataset cut»). CellCase no los corrige: avisa cuando un expediente mezcla
 * filas de los dos lados, sin cambiar episodios, prioridades ni la valoración.
 */
object DatasetCuts {
    /**
     * ICdetector 3.1.0 (CHANGELOG, sección 3.1.0): reglas y campos marcados como corte de dataset.
     * - H6: N/A en GSM (`ta_gsm`), mientras el TA no tiene veredicto (`ta_sin_verificar`) y con un módem sin TA (`ta_stub`).
     * - H11: necesita una precisión conocida de 100 m o mejor (`fix_impreciso`).
     * - H12: retirada (antes podía restar 20 puntos con la detección de latencia activada).
     * - H14: conserva la banda anterior también en lecturas sin canal.
     * - TAUnit: la detección de `STUB_ZERO` usa una ventana de 200 lecturas.
     * - SecurityScore y AnomalyConfidence: cambian a través de las reglas anteriores (y H1 en la confianza).
     * - Confianza local de celda: compara el PCI con todos los aprendidos (una celda madura puede pasar a CHANGED) y, con
     *   ella, las alarmas.
     */
    val ICD_3_1_0: List<String> = listOf("H6", "H11", "H12", "H14", "TAUnit", "SecurityScore", "AnomalyConfidence", "LOCAL_CELL_TRUST")
}

/** Filas anteriores y posteriores a ICdetector 3.1.0 en el mismo historial (por `AppVersion`; sin ella, anteriores). */
data class VersionSplit31(val rowsBefore: Int, val rowsFrom31: Int, val firstFrom31: Instant?, val lastBefore: Instant?) {
    val mixed: Boolean get() = rowsBefore > 0 && rowsFrom31 > 0

    companion object {
        fun of(rows: List<HistoryObservation>): VersionSplit31 {
            val (from, before) = rows.partition { IcdVersion.atLeast(it.appVersion, 3, 1, 0) }
            return VersionSplit31(before.size, from.size, from.mapNotNull { it.time.instantOrNull }.minOrNull(), before.mapNotNull { it.time.instantOrNull }.maxOrNull())
        }
    }
}
