package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import java.time.Duration
import java.time.Instant

/** Versión de la presentación del cambio de celdas (docs/churn.md). Solo describe: no hay umbrales propios. */
const val CHURN_RULES_VERSION = "churn/1"

/** Distribución de `ChurnCells15m` y `ChurnChanges15m` para un estado de movimiento (P90 por rango más cercano). */
data class ChurnStats(
    val motion: String,
    val rows: Int,
    val cellsMedian: Int, val cellsP90: Int, val cellsMax: Int,
    val changesMedian: Int, val changesP90: Int, val changesMax: Int
)

/** Filas seguidas (≤ 15 min entre una y la siguiente) con `ChurnChanges15m` ≥ el P90 del expediente. */
data class ChurnBand(
    val start: Instant, val end: Instant, val rows: Int,
    val peakChanges: Int, val peakCells: Int, val motions: Set<String>,
    /** Fila con más cambios (la primera si hay empate): su archivo y línea. */
    val peak: Provenance
)

/**
 * Cambio de celdas de H17 (ICdetector 3.1.0), en sombra y sin calibrar: datos descriptivos del historial. No
 * interviene en episodios, prioridades, relevancia ni valoración (como el perfil local, D-055).
 */
data class ChurnSummary(
    /** Filas grabadas por ICdetector 3.1.0 o posterior. */
    val rowsV31: Int,
    /** Filas con `ChurnCells15m` y `ChurnChanges15m`. */
    val rowsWithChurn: Int,
    /** En el orden STILL, MOVING, UNKNOWN (solo los presentes). */
    val byMotion: List<ChurnStats>,
    /** P90 de `ChurnChanges15m` en todas las filas con churn: referencia del propio expediente, no un umbral. */
    val changesP90: Int,
    /** Hasta [ChurnAnalyzer.MAX_BANDS] franjas, de más a menos cambios. */
    val bands: List<ChurnBand>,
    val first: Instant?, val last: Instant?
)

object ChurnAnalyzer {
    val MOTIONS = listOf("STILL", "MOVING", "UNKNOWN")
    /** Franjas: filas seguidas a ≤ 15 min (la ventana de H17). */
    val BAND_GAP: Duration = Duration.ofMinutes(15)
    const val MAX_BANDS = 5

    /** Percentil por rango más cercano de una lista ordenada: el valor en la posición ⌈p·n⌉. */
    fun percentile(sorted: List<Int>, p: Double): Int = sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.size - 1)]

    /** Null si el historial no tiene ninguna fila con churn (anterior a 3.1.0). */
    fun of(rows: List<HistoryObservation>): ChurnSummary? {
        val with = rows.filter { it.churnCells15m != null && it.churnChanges15m != null }
        if (with.isEmpty()) return null
        val byMotion = MOTIONS.mapNotNull { m ->
            val l = with.filter { (it.churnMotion ?: "UNKNOWN") == m }
            if (l.isEmpty()) null else {
                val c = l.map { it.churnCells15m!! }.sorted(); val ch = l.map { it.churnChanges15m!! }.sorted()
                ChurnStats(m, l.size, percentile(c, 0.5), percentile(c, 0.9), c.last(), percentile(ch, 0.5), percentile(ch, 0.9), ch.last())
            }
        }
        val p90 = percentile(with.map { it.churnChanges15m!! }.sorted(), 0.9)
        val timed = with.filter { it.time.instantOrNull != null }.sortedWith(compareBy<HistoryObservation> { it.time.instantOrNull }.thenBy { it.provenance.startLine })
        val bands = ArrayList<List<HistoryObservation>>()
        if (p90 > 0) {
            var cur = ArrayList<HistoryObservation>()
            for (o in timed) {
                if (o.churnChanges15m!! < p90) continue
                if (cur.isNotEmpty() && Duration.between(cur.last().time.instantOrNull!!, o.time.instantOrNull!!) > BAND_GAP) { bands += cur; cur = ArrayList() }
                cur += o
            }
            if (cur.isNotEmpty()) bands += cur
        }
        val top = bands.map { b ->
            val peak = b.maxWith(compareBy<HistoryObservation> { it.churnChanges15m!! }.thenByDescending { it.time.instantOrNull })
            ChurnBand(b.first().time.instantOrNull!!, b.last().time.instantOrNull!!, b.size, peak.churnChanges15m!!, b.maxOf { it.churnCells15m!! },
                b.map { it.churnMotion ?: "UNKNOWN" }.toSortedSet(), peak.provenance)
        }.sortedWith(compareByDescending<ChurnBand> { it.peakChanges }.thenBy { it.start }).take(MAX_BANDS)
        return ChurnSummary(rows.count { it.externalVerificationRetired }, with.size, byMotion, p90, top,
            timed.firstOrNull()?.time?.instantOrNull, timed.lastOrNull()?.time?.instantOrNull)
    }
}
