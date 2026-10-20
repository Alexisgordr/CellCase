package com.alexisgordr.cellcase.core.report

import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** Contexto que reciben las plantillas. */
data class TextContext(
    val case: CaseInfo,
    /** Todas las importaciones del expediente, correctas o no. */
    val results: List<ImportResult>,
    val options: ReportOptions
) {
    /** Zona para mostrar horas: la declarada si está confirmada; si no, UTC (y se indica). */
    val displayZone: ZoneId get() = if (case.zone.confirmed) case.zone.zoneId else ZoneOffset.UTC

    /** Identificadores cortos de celda de este informe (C1, C2…), con su leyenda. */
    val cells: CellLabels = CellLabels()
}

/**
 * Asigna identificadores cortos y estables a las identidades citadas en un informe, para gráficos y tablas
 * legibles. La leyenda relaciona cada uno con la identidad completa (o enmascarada).
 */
class CellLabels {
    private val map = LinkedHashMap<String, String>()
    fun of(key: String): String = map.getOrPut(key) { "C${map.size + 1}" }
    fun known(key: String): String? = map[key]
    fun index(key: String): Int = of(key).removePrefix("C").toInt() - 1
    val entries: List<Pair<String, String>> get() = map.entries.map { it.value to it.key }
}

/** Disponibilidad de un análisis (D-031): distingue los cuatro casos que pide el informe. */
enum class Availability { AVAILABLE, NOT_PROVIDED, NOT_COMPATIBLE, IMPORT_FAILED, NOT_EVALUABLE }

enum class AnalysisCode { EPISODES, SAMPLING, FORENSIC_TIMELINE, SERVICE_STATE, SERVICE_COHERENCE, TOPOLOGY, GEOMETRY, STABLE_SITE }

data class AnalysisAvailability(val code: AnalysisCode, val status: Availability, val reasonCode: String, val files: List<String>)

object AvailabilityTable {
    private fun forKind(kind: ExportKind, results: List<ImportResult>): Pair<Availability, List<String>> {
        val mine = results.filter { it.kind == kind }
        return when {
            mine.any { it.imported } -> Availability.AVAILABLE to mine.filter { it.imported }.map { it.fileName }
            mine.any { it.state == ImportState.INCOMPATIBLE || it.state == ImportState.NOT_IMPLEMENTED } -> Availability.NOT_COMPATIBLE to mine.map { it.fileName }
            mine.isNotEmpty() -> Availability.IMPORT_FAILED to mine.map { it.fileName }
            else -> Availability.NOT_PROVIDED to emptyList()
        }
    }

    fun compute(results: List<ImportResult>, a: CaseAnalysis, zoneConfirmed: Boolean): List<AnalysisAvailability> {
        val out = ArrayList<AnalysisAvailability>()
        val (h, hf) = forKind(ExportKind.HISTORY, results)
        out += when {
            h != Availability.AVAILABLE -> AnalysisAvailability(AnalysisCode.SAMPLING, h, "HISTORY_" + h.name, hf)
            !zoneConfirmed -> AnalysisAvailability(AnalysisCode.SAMPLING, Availability.NOT_EVALUABLE, "ZONE_NOT_CONFIRMED", hf)
            a.historyAnalysis?.sampling?.evaluable != true -> AnalysisAvailability(AnalysisCode.SAMPLING, Availability.NOT_EVALUABLE, "TOO_FEW_ROWS", hf)
            else -> AnalysisAvailability(AnalysisCode.SAMPLING, Availability.AVAILABLE, "OK", hf)
        }
        val anySignalSource = listOf(ExportKind.HISTORY, ExportKind.FORENSIC_CASE, ExportKind.STABLE_SITE).any { forKind(it, results).first == Availability.AVAILABLE }
        out += AnalysisAvailability(AnalysisCode.EPISODES, if (anySignalSource) Availability.AVAILABLE else Availability.NOT_EVALUABLE,
            if (anySignalSource) "OK" else "NO_SIGNAL_SOURCE", emptyList())
        val (f, ff) = forKind(ExportKind.FORENSIC_CASE, results)
        out += AnalysisAvailability(AnalysisCode.FORENSIC_TIMELINE, f, if (f == Availability.AVAILABLE) "OK" else "FORENSIC_" + f.name, ff)
        val (r, rf) = forKind(ExportKind.SERVICE_STATE, results)
        out += if (r == Availability.AVAILABLE && a.serviceEvents.isEmpty()) AnalysisAvailability(AnalysisCode.SERVICE_STATE, Availability.NOT_EVALUABLE, "RADIO_EMPTY", rf)
        else AnalysisAvailability(AnalysisCode.SERVICE_STATE, r, if (r == Availability.AVAILABLE) "OK" else "RADIO_" + r.name, rf)
        out += when {
            r != Availability.AVAILABLE -> AnalysisAvailability(AnalysisCode.SERVICE_COHERENCE, r, "RADIO_" + r.name, rf)
            h != Availability.AVAILABLE -> AnalysisAvailability(AnalysisCode.SERVICE_COHERENCE, h, "HISTORY_" + h.name, hf)
            (a.serviceCoherence?.compared ?: 0) == 0 -> AnalysisAvailability(AnalysisCode.SERVICE_COHERENCE, Availability.NOT_EVALUABLE, "NO_OVERLAP", rf + hf)
            else -> AnalysisAvailability(AnalysisCode.SERVICE_COHERENCE, Availability.AVAILABLE, "OK", rf + hf)
        }
        for ((code, kind) in listOf(AnalysisCode.TOPOLOGY to ExportKind.TOPOLOGY, AnalysisCode.GEOMETRY to ExportKind.GEOMETRY, AnalysisCode.STABLE_SITE to ExportKind.STABLE_SITE)) {
            val (s, fs) = forKind(kind, results)
            out += AnalysisAvailability(code, s, if (s == Availability.AVAILABLE) "OK" else kind.name + "_" + s.name, fs)
        }
        return out
    }
}

/** Utilidades de ejes temporales, sin texto propio de un idioma. */
object ChartSupport {
    private val steps = listOf(
        Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5), Duration.ofMinutes(10), Duration.ofMinutes(15), Duration.ofMinutes(30),
        Duration.ofHours(1), Duration.ofHours(2), Duration.ofHours(3), Duration.ofHours(6), Duration.ofHours(12),
        Duration.ofDays(1), Duration.ofDays(2), Duration.ofDays(7), Duration.ofDays(14), Duration.ofDays(30)
    )

    /** Marcas alineadas a la zona de visualización, como máximo [maxTicks]. */
    fun ticks(from: Instant, to: Instant, zone: ZoneId, maxTicks: Int = 8, label: (ZonedDateTime, Duration) -> String): List<TimeTick> {
        val span = Duration.between(from, to).coerceAtLeast(Duration.ofMinutes(1))
        val step = steps.firstOrNull { span.toMillis() / it.toMillis() <= maxTicks } ?: Duration.ofDays(span.toDays() / maxTicks + 1)
        var t = from.atZone(zone).let { z ->
            if (step >= Duration.ofDays(1)) z.truncatedTo(ChronoUnit.DAYS)
            else { val m = step.toMinutes().coerceAtLeast(1); z.truncatedTo(ChronoUnit.HOURS).plusMinutes((z.minute / m) * m) }
        }
        val out = ArrayList<TimeTick>()
        while (!t.toInstant().isAfter(to)) {
            if (!t.toInstant().isBefore(from)) out += TimeTick(t.toInstant().toEpochMilli(), label(t, step))
            t = if (step >= Duration.ofDays(1)) t.plusDays(step.toDays()) else t.plus(step)
            if (out.size > 40) break
        }
        return out
    }
}
