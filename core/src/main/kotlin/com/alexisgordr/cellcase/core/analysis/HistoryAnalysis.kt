package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.HistoryImport
import com.alexisgordr.cellcase.core.model.CollectorVerdict
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.ValueState
import com.alexisgordr.cellcase.core.time.TimeResolution
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/** Clave de recuento para «sin valor» (unidad de TA ausente, verificación vacía): el informe la nombra en su idioma. */
const val NO_VALUE = "\u2205"


const val RULESET_VERSION = "cellcase-rules/3"

/** Identificador y versión de una regla del catálogo `docs/analysis-rules.md`. */
data class RuleRef(val id: String, val version: Int) {
    override fun toString() = "$id/$version"
}

object Rules {
    val PERIOD = RuleRef("DESC-PERIOD", 1)
    val FIELDS = RuleRef("QUAL-FIELDS", 1)
    val TIME = RuleRef("QUAL-TIME", 1)
    /** /2: sin zona confirmada se muestrea si todas las filas tienen instante exacto (B2). */
    val SAMPLING = RuleRef("DESC-SAMPLING", 2)
    val CELLS = RuleRef("DESC-CELLS", 1)
    val POWER = RuleRef("DESC-POWER", 1)
    /** /2: sin TAMeters (vacío o en conflicto tras fusionar) no se compara (B3). */
    val TA = RuleRef("COH-TA", 2)
    /** /2: las filas de ICdetector 3.1.0 no tienen verificación externa (cellcase-rules/2). */
    val COLLECTOR = RuleRef("COL-RESULTS", 2)
    /** Reglas no evaluadas por ICdetector, separando falta de datos de retirada, sombra y reserva (NotEvaluated.kt). */
    val RULE_COVERAGE = RuleRef("COL-RULES", 1)
    /** Historial con filas de antes y de después de ICdetector 3.1.0 (DatasetCuts.kt). */
    val DATASET_CUT = RuleRef("QUAL-CUT", 1)
    /** /2: la «misma hora» es el mismo instante cuando hay ObservedAtUtc (B5). */
    val DUP = RuleRef("COH-DUP", 2)
    val TESTNET = RuleRef("QUAL-TESTNET", 1)
    fun missing(kind: ExportKind) = RuleRef("MISSING-${kind.name}", 1)
}

/** Periodo de interés declarado por el usuario (instantes UTC, extremos incluidos). */
data class PeriodOfInterest(val start: Instant, val end: Instant, val label: String)

// ───────────────────────── resultados tipados (sin texto) ─────────────────────────

data class PeriodResult(
    val totalRows: Int,
    val validTimeRows: Int,
    val first: HistoryObservation?,
    val last: HistoryObservation?,
    val rowsPerDay: List<Pair<LocalDate, Int>>,
    val calendarDays: Int,
    val daysWithData: Int,
    val periodOfInterest: PeriodOfInterest?,
    val rowsInPeriod: Int?,
    val rowsNotAssignable: Int?
)

data class FieldStat(val column: String, val counts: Map<ValueState, Int>, val total: Int) {
    fun count(s: ValueState) = counts[s] ?: 0
    val usable: Int get() = count(ValueState.PRESENT)
}

data class FieldQualityResult(val fields: List<FieldStat>, val rejectedRows: Int, val dataRows: Int)

data class TimeResult(
    val zoneConfirmed: Boolean,
    val zoneId: String?,
    val exact: Int,
    val ambiguous: List<HistoryObservation>,
    val nonexistent: List<HistoryObservation>,
    val invalid: Int
)

data class Gap(val before: HistoryObservation, val after: HistoryObservation, val duration: Duration)

data class SamplingResult(
    val evaluable: Boolean,
    val notEvaluableReason: String?,
    val rowsUsed: Int,
    val medianInterval: Duration?,
    val gapThreshold: Duration,
    val gaps: List<Gap>,
    val totalGap: Duration
)

data class CellCount(
    val key: String,
    val radio: RadioTech,
    val rows: Int,
    val first: HistoryObservation,
    val last: HistoryObservation
)

data class CellsResult(val cells: List<CellCount>, val incompleteIdentityRows: Int, val rowsByRadio: Map<RadioTech, Int>)

data class PowerStat(
    val radio: RadioTech,
    val n: Int,
    val min: Int,
    val p10: Int,
    val median: Int,
    val p90: Int,
    val max: Int,
    val minRow: HistoryObservation,
    val maxRow: HistoryObservation
)

data class PowerResult(val stats: List<PowerStat>, val excludedRows: Int)

data class TaResult(
    val rowsWithTa: Int,
    val byUnit: Map<String, Int>,
    val incoherent: List<HistoryObservation>
)

data class CollectorResult(
    val verdicts: Map<CollectorVerdict, Int>,
    val reasonRows: List<HistoryObservation>,
    val subthresholdRows: List<HistoryObservation>,
    val scoreBelow100: Int,
    val maxAnomalyConfidence: Double?,
    val maxConfidenceRow: HistoryObservation?,
    /** Estados de verificación externa de las filas que la tenían (ICdetector anterior a 3.1.0). */
    val verified: Map<String, Int>,
    /**
     * Filas de ICdetector 3.1.0 o posterior: sin verificación externa (retirada). Su `PENDING` no significa
     * «pendiente» y no se cuenta en [verified] (cellcase-rules/2).
     */
    val verificationRetired: Int = 0
) {
    /** Historial con filas de las dos clases: con verificación (anteriores a 3.1.0) y sin ella (3.1.0 o posterior). */
    val verificationMixed: Boolean get() = verificationRetired > 0 && verified.values.sum() > 0
}

data class DuplicateResult(val exactPairs: List<Pair<Provenance, Provenance>>, val sameTimeIdentity: List<Pair<Provenance, Provenance>>)

data class TestNetResult(val rows: Int, val example: HistoryObservation?)

/** Resultado completo del análisis del historial (todas las reglas). */
data class HistoryAnalysis(
    val import: HistoryImport,
    val period: PeriodResult,
    val fields: FieldQualityResult,
    val time: TimeResult,
    val sampling: SamplingResult,
    val cells: CellsResult,
    val power: PowerResult,
    val ta: TaResult,
    val collector: CollectorResult,
    val duplicates: DuplicateResult,
    val testNet: TestNetResult,
    /** Reglas que ICdetector no evaluó, separando la falta de datos de las retiradas o en sombra (cellcase-rules/2). */
    val ruleCoverage: RuleCoverage? = null,
    /** Filas anteriores y posteriores a ICdetector 3.1.0 (cortes de dataset). */
    val versions31: VersionSplit31 = VersionSplit31(0, 0, null, null)
)

/**
 * Motor descriptivo del historial. Determinista: mismas filas → mismo resultado.
 * No produce texto: eso lo hacen las plantillas de `report`.
 */
object HistoryAnalyzer {
    val GAP_THRESHOLD: Duration = Duration.ofMinutes(60)

    /** Columnas cuya completitud se informa, en orden de presentación. */
    val QUALITY_COLUMNS = listOf(
        "Timestamp", "MCC", "MNC", "TAC", "CID", "Radio", "DBM", "PCI", "ARFCN", "RSRQ", "SINR",
        "Lat", "Lon", "TA", "TAUnit", "ServingConnection", "ServiceState", "SecurityScore", "AnomalyConfidence", "Verified"
    )

    fun analyze(imp: HistoryImport, period: PeriodOfInterest? = null): HistoryAnalysis {
        val obs = imp.observations
        return HistoryAnalysis(
            import = imp,
            period = period(obs, period),
            fields = fields(imp),
            time = time(imp),
            sampling = sampling(obs, imp.zone?.confirmed == true),
            cells = cells(obs),
            power = power(obs),
            ta = ta(obs),
            collector = collector(obs),
            duplicates = duplicates(obs),
            testNet = TestNetResult(obs.count { it.identity.mcc == "001" }, obs.firstOrNull { it.identity.mcc == "001" }),
            ruleCoverage = NotEvaluated.coverage(obs),
            versions31 = VersionSplit31.of(obs)
        )
    }

    private fun period(obs: List<HistoryObservation>, poi: PeriodOfInterest?): PeriodResult {
        val valid = obs.filter { it.time.local != null }
        val sorted = valid.sortedWith(compareBy<HistoryObservation> { it.time.local }.thenBy { it.provenance.startLine })
        val perDay = valid.groupingBy { it.time.local!!.toLocalDate() }.eachCount().toSortedMap().toList()
        val calendarDays = if (perDay.isEmpty()) 0 else
            (java.time.temporal.ChronoUnit.DAYS.between(perDay.first().first, perDay.last().first) + 1).toInt()
        var inPeriod: Int? = null
        var notAssignable: Int? = null
        if (poi != null) {
            inPeriod = obs.count { o -> o.time.instantOrNull?.let { !it.isBefore(poi.start) && !it.isAfter(poi.end) } == true }
            notAssignable = obs.count { it.time.instantOrNull == null }
        }
        return PeriodResult(obs.size, valid.size, sorted.firstOrNull(), sorted.lastOrNull(), perDay, calendarDays,
            perDay.size, poi, inPeriod, notAssignable)
    }

    private fun fields(imp: HistoryImport): FieldQualityResult {
        val obs = imp.observations
        val stats = QUALITY_COLUMNS.filter { it in imp.header }.map { col ->
            val counts = obs.groupingBy { it.stateOf(col) }.eachCount()
            FieldStat(col, counts, obs.size)
        }
        return FieldQualityResult(stats, imp.rejectedRows, imp.dataRows)
    }

    private fun time(imp: HistoryImport): TimeResult {
        val obs = imp.observations
        return TimeResult(
            zoneConfirmed = imp.zone?.confirmed == true,
            zoneId = imp.zone?.zoneId?.id,
            exact = obs.count { it.time.resolution is TimeResolution.Exact },
            ambiguous = obs.filter { it.time.resolution is TimeResolution.Ambiguous },
            nonexistent = obs.filter { it.time.resolution is TimeResolution.Nonexistent },
            invalid = obs.count { it.time.local == null }
        )
    }

    private fun sampling(obs: List<HistoryObservation>, zoneConfirmed: Boolean): SamplingResult {
        // Sin zona confirmada solo se para si hay filas que dependen de ella (sin ObservedAtUtc): faltarían en los
        // intervalos y darían huecos falsos. Un historial de 3.0 con instantes exactos se muestrea igual (B2).
        if (!zoneConfirmed && obs.any { it.time.resolution is TimeResolution.NoConfirmedZone })
            return SamplingResult(false, "NO_ZONE", 0, null, GAP_THRESHOLD, emptyList(), Duration.ZERO)
        val exact = obs.filter { it.time.instantOrNull != null }
            .sortedWith(compareBy<HistoryObservation> { it.time.instantOrNull }.thenBy { it.provenance.startLine })
        if (exact.size < 2) return SamplingResult(false, "TOO_FEW", exact.size, null, GAP_THRESHOLD, emptyList(), Duration.ZERO)
        val intervals = exact.zipWithNext { a, b -> Duration.between(a.time.instantOrNull, b.time.instantOrNull) }
        val sortedIntervals = intervals.sorted()
        // Mediana por rango más cercano: con un número par, el menor de los dos centrales (DESC-SAMPLING/2, B6)
        val median = sortedIntervals[(sortedIntervals.size - 1) / 2]
        val gaps = exact.zipWithNext().mapNotNull { (a, b) ->
            val d = Duration.between(a.time.instantOrNull, b.time.instantOrNull)
            if (d >= GAP_THRESHOLD) Gap(a, b, d) else null
        }
        val total = gaps.fold(Duration.ZERO) { acc, g -> acc.plus(g.duration) }
        return SamplingResult(true, null, exact.size, median, GAP_THRESHOLD, gaps, total)
    }

    private fun cells(obs: List<HistoryObservation>): CellsResult {
        val complete = obs.filter { it.identity.joinKey != null }
        val groups = complete.groupBy { it.identity.joinKey!! }
        val counts = groups.map { (k, rows) ->
            val byTime = rows.sortedWith(compareBy<HistoryObservation> { it.time.local ?: LocalDateTime.MAX }.thenBy { it.provenance.startLine })
            CellCount(k, rows.first().identity.radio, rows.size, byTime.first(), byTime.last())
        }.sortedWith(compareByDescending<CellCount> { it.rows }.thenBy { it.key })
        return CellsResult(counts, obs.size - complete.size, obs.groupingBy { it.identity.radio }.eachCount())
    }

    private fun power(obs: List<HistoryObservation>): PowerResult {
        val stats = obs.filter { it.dbm != null && it.identity.radio != RadioTech.UNKNOWN }
            .groupBy { it.identity.radio }
            .toSortedMap()
            .map { (radio, rows) ->
                val sorted = rows.sortedBy { it.dbm!! }
                fun pct(p: Double): Int = sorted[((sorted.size - 1) * p).toInt()].dbm!!
                PowerStat(radio, rows.size, sorted.first().dbm!!, pct(0.10), pct(0.50), pct(0.90), sorted.last().dbm!!, sorted.first(), sorted.last())
            }
        return PowerResult(stats, obs.count { it.dbm == null || it.identity.radio == RadioTech.UNKNOWN })
    }

    private fun ta(obs: List<HistoryObservation>): TaResult {
        val withTa = obs.filter { it.timingAdvance != null }
        val incoherent = withTa.filter { o ->
            val unit = o.taUnit ?: return@filter false
            val factor = when (unit) { "LTE_INDEX" -> 78; "GSM_INDEX" -> 554; else -> null }
            val rawMeters = o.raw["TAMeters"].orEmpty()
            // Sin TAMeters (vacío, o anulado al fusionar dos exportaciones que no coinciden) no hay nada que comparar
            // (COH-TA/2, B3): antes `null != TA × factor` contaba como incoherencia.
            if (factor == null) rawMeters.isNotEmpty() else o.taMeters != null && o.taMeters != o.timingAdvance!! * factor
        }
        return TaResult(withTa.size, withTa.groupingBy { it.taUnit ?: NO_VALUE }.eachCount().toSortedMap(), incoherent)
    }

    private fun collector(obs: List<HistoryObservation>): CollectorResult {
        val maxConf = obs.filter { it.anomalyConfidence != null }.maxByOrNull { it.anomalyConfidence!! }
        return CollectorResult(
            verdicts = obs.groupingBy { it.collectorVerdict }.eachCount(),
            reasonRows = obs.filter { it.collectorVerdict == CollectorVerdict.REASON_WITHOUT_PREFIX }.sortedBy { it.provenance.startLine },
            subthresholdRows = obs.filter { it.collectorVerdict == CollectorVerdict.SUBTHRESHOLD }.sortedBy { it.provenance.startLine },
            scoreBelow100 = obs.count { (it.securityScore ?: 100) < 100 },
            maxAnomalyConfidence = maxConf?.anomalyConfidence,
            maxConfidenceRow = maxConf,
            verified = obs.filter { !it.externalVerificationRetired }.groupingBy { it.verified ?: NO_VALUE }.eachCount().toSortedMap(),
            verificationRetired = obs.count { it.externalVerificationRetired }
        )
    }

    private fun duplicates(obs: List<HistoryObservation>): DuplicateResult {
        val exact = ArrayList<Pair<Provenance, Provenance>>()
        val same = ArrayList<Pair<Provenance, Provenance>>()
        val byContent = HashMap<List<String>, HistoryObservation>()
        val byKey = HashMap<String, HistoryObservation>()
        for (o in obs) {
            val prev = byContent.putIfAbsent(o.raw.values.toList(), o)
            if (prev != null) { exact += prev.provenance to o.provenance; continue }
            // Misma clave que el importador (COH-DUP/2, B5): con ObservedAtUtc la «misma hora» es el mismo instante, y las
            // dos filas de la hora repetida de octubre no lo son.
            val key = o.identity.joinKey?.let { "${o.observedAtUtc ?: o.time.raw}|$it" } ?: continue
            byKey.putIfAbsent(key, o)?.let { same += it.provenance to o.provenance }
        }
        return DuplicateResult(exact, same)
    }
}
