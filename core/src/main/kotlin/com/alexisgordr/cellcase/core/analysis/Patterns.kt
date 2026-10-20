package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.RadioTech

/**
 * Tipo de patrón al que se refiere una señal. Se deduce del TEXTO del motivo que escribe ICdetector
 * (`FailedHeuristics`), de la fuente (caso forense, Stable-Site) o del estado de Radio. Es una clasificación
 * de lo registrado, no una conclusión.
 */
enum class PatternKind {
    BAND_CHANGE, RF_IDENTITY, POWER_DEVIATION, TAC_DEVIATION, TIMING_ADVANCE, TRANSITION, ISOLATED_CELL,
    NETWORK_IDENTITY, SPECTRUM, CIPHERING, GEOGRAPHY, LATENCY, FORENSIC_CASE, STABLE_SITE_NOVELTY, SERVICE_DEGRADED, OTHER
}

/** Un motivo individual de ICdetector (una fila puede tener varios, separados por « | »). */
data class Reason(
    val kind: PatternKind,
    /** Texto tal cual lo escribió ICdetector (sin el prefijo de sub-umbral). */
    val text: String,
    val freqFromMhz: Int? = null,
    val freqToMhz: Int? = null,
    val bandFrom: Int? = null,
    val bandTo: Int? = null,
    val db: Int? = null
)

object ReasonParser {
    const val SUBTHRESHOLD_PREFIX = "[sub-umbral]"
    private val BAND = Regex("""\((\d+)\s*MHz\s*(?:→|->|-&gt;)\s*(\d+)\s*MHz,\s*B(\d+)\s*(?:→|->|-&gt;)\s*B(\d+)\)""")
    private val DB = Regex("""\(([+-]?\d+)\s*dB\)""")

    fun parse(text: String?): List<Reason> {
        val t = text?.trim()?.removePrefix(SUBTHRESHOLD_PREFIX)?.trim().orEmpty()
        if (t.isEmpty() || t == "OK") return emptyList()
        return t.split(" | ").map { it.trim() }.filter { it.isNotEmpty() }.map { part ->
            val l = part.lowercase()
            val kind = when {
                "downgrade de banda" in l -> PatternKind.BAND_CHANGE
                "identidad rf" in l -> PatternKind.RF_IDENTITY
                "potencia anómala" in l || "salto potencia" in l || "huella rf" in l -> PatternKind.POWER_DEVIATION
                "desviación tac" in l -> PatternKind.TAC_DEVIATION
                "suplantación ta" in l || "proximidad anómala" in l -> PatternKind.TIMING_ADVANCE
                "transición" in l || "ping-pong" in l -> PatternKind.TRANSITION
                "celda aislada" in l -> PatternKind.ISOLATED_CELL
                "mcc" in l || "mnc" in l -> PatternKind.NETWORK_IDENTITY
                "frecuencia" in l || "vecinos fantasma" in l -> PatternKind.SPECTRUM
                "cifrado" in l || "a5/0" in l -> PatternKind.CIPHERING
                "geográfica" in l -> PatternKind.GEOGRAPHY
                "latencia" in l -> PatternKind.LATENCY
                else -> PatternKind.OTHER
            }
            val b = BAND.find(part)
            Reason(kind, part, b?.groupValues?.get(1)?.toIntOrNull(), b?.groupValues?.get(2)?.toIntOrNull(),
                b?.groupValues?.get(3)?.toIntOrNull(), b?.groupValues?.get(4)?.toIntOrNull(),
                DB.find(part)?.groupValues?.get(1)?.toIntOrNull())
        }
    }
}

/** Bandas LTE por EARFCN de bajada (3GPP TS 36.101, tabla 5.7.3-1; subconjunto habitual en Europa y otras regiones). */
object LteBands {
    private val ranges = listOf(
        1 to 0..599, 2 to 600..1199, 3 to 1200..1949, 4 to 1950..2399, 5 to 2400..2649, 7 to 2750..3449, 8 to 3450..3799,
        12 to 5010..5179, 13 to 5180..5279, 14 to 5280..5379, 17 to 5730..5849, 20 to 6150..6449, 25 to 8040..8689,
        26 to 8690..9039, 28 to 9210..9659, 32 to 9920..10359, 38 to 37750..38249, 40 to 38650..39649, 41 to 39650..41589,
        42 to 41590..43589, 43 to 43590..45589, 66 to 66436..67335, 71 to 68586..68935
    )
    /** Frecuencia nominal aproximada de cada banda, solo para etiquetas. */
    private val nominal = mapOf(1 to 2100, 2 to 1900, 3 to 1800, 4 to 1700, 5 to 850, 7 to 2600, 8 to 900, 12 to 700, 13 to 700, 14 to 700,
        17 to 700, 20 to 800, 25 to 1900, 26 to 850, 28 to 700, 32 to 1500, 38 to 2600, 40 to 2300, 41 to 2500, 42 to 3500, 43 to 3700, 66 to 1700, 71 to 600)

    fun bandOfEarfcn(earfcn: Int?): Int? = earfcn?.let { e -> ranges.firstOrNull { e in it.second }?.first }
    fun nominalMhz(band: Int?): Int? = band?.let { nominal[it] }

    /** Banda de una fila LTE: la columna `Bands` si existe; si no, la deducida del EARFCN. Solo LTE. */
    fun bandOf(o: HistoryObservation): Int? {
        if (o.identity.radio != RadioTech.LTE) return null
        return o.bands?.firstOrNull() ?: bandOfEarfcn(o.arfcn)
    }
}
