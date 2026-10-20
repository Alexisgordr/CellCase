package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.model.RadioTech
import java.time.LocalDate
import java.time.ZoneId

/*
 * Relevancia para la pregunta de suplantación (docs/episode-rules.md §7, D-036). NO es un segundo motor:
 * la calcula [EpisodeEngine] con los mismos hallazgos (señales, comprobaciones y criterios) de cada episodio, y
 * la guardan el propio [Episode] y el [EpisodeSet]. El informe técnico y el explicado leen esos mismos campos.
 * Es independiente del «interés técnico» ([Priority]): un cambio de banda que se repite puede merecer revisión
 * técnica sin aportar nada a la pregunta de si hubo una suplantación. Ningún estado es una probabilidad.
 */

enum class SpoofingRelevance {
    /** Sin indicios suficientes para atribuir un ataque. */
    NO_INDICATION,
    /** Observaciones que necesitan más datos para aclararse. */
    NEEDS_MORE_DATA,
    /** Indicios que requieren investigación especializada. Solo con criterios implementados y evaluados. */
    SPECIALIST
}

/**
 * No hay todavía criterios de «investigación especializada» implementados y evaluados con datos de referencia.
 * Mientras sea false, [SpoofingRelevance.SPECIALIST] nunca se asigna (comprobado en las pruebas).
 */
const val SPECIALIST_CRITERIA_EVALUATED = false

enum class RelevanceReason {
    /** Comportamiento habitual de las redes (cambio de banda dentro de 4G, potencia, transición, pérdida de cobertura). */
    COMMON_BEHAVIOUR,
    /** Lo que indica ICdetector no se ve en las mediciones. */
    NOT_VISIBLE_IN_DATA,
    /** ICdetector registró una alarma (no solo una observación por debajo de su umbral). */
    COLLECTOR_ALARM,
    /** ICdetector abrió un caso forense. */
    FORENSIC_CASE,
    /** Patrón sobre la identidad o los parámetros de la antena (PCI alternante, red, cifrado, TAC, TA…). */
    IDENTITY_PATTERN,
    /** Paso a una tecnología más antigua (2G/3G). */
    TECHNOLOGY_DOWNGRADE,
    /** Obsoleto desde episodes/4: la coincidencia con Radio es contexto y ya no eleva la relevancia. */
    PHONE_STATE_COINCIDENCE
}

data class EpisodeRelevance(val relevance: SpoofingRelevance, val reasons: List<RelevanceReason>)

/** Carencias de datos que limitan la valoración. */
enum class DataGap { NO_SIGNAL_SOURCE, ZONE_NOT_CONFIRMED, UNPLACED_ROWS, NO_RADIO }

/** Varias incidencias del mismo tipo y relevancia se explican juntas: ocho episodios no son ocho amenazas. */
data class ObservationGroup(
    val number: Int,
    val kind: PatternKind,
    val technologyDowngrade: Boolean,
    val relevance: SpoofingRelevance,
    val reasons: List<RelevanceReason>,
    val episodes: List<Int>,
    val cells: List<String>,
    val days: List<LocalDate>,
    val moments: Int,
    val anyAlarm: Boolean
)

/** Vista de la valoración del expediente: todo procede de [EpisodeSet] y de la disponibilidad de datos. */
data class Assessment(
    val overall: SpoofingRelevance,
    val perEpisode: Map<Int, EpisodeRelevance>,
    val groups: List<ObservationGroup>,
    val dataGaps: List<DataGap>
)

/** Reglas de relevancia y agrupación usadas por [EpisodeEngine] (no se invocan desde los informes). */
internal object RelevanceRules {
    private val IDENTITY_KINDS = setOf(PatternKind.RF_IDENTITY, PatternKind.NETWORK_IDENTITY, PatternKind.CIPHERING, PatternKind.TAC_DEVIATION,
        PatternKind.TIMING_ADVANCE, PatternKind.ISOLATED_CELL, PatternKind.SPECTRUM)
    private val OLD_TECH = setOf(RadioTech.GSM.name, RadioTech.UMTS.name)

    @Suppress("UNCHECKED_CAST")
    fun downgrade(e: Episode): Boolean = (e.measurement.values["techChanges"] as? List<String>).orEmpty()
        .any { it.substringAfter("→") in OLD_TECH && it.substringBefore("→") !in OLD_TECH }

    fun relevance(e: Episode): EpisodeRelevance {
        val r = ArrayList<RelevanceReason>()
        if (e.signals.any { it.source == SignalSource.HISTORY_COLLECTOR && !it.subthreshold }) r += RelevanceReason.COLLECTOR_ALARM
        // Un caso forense cuenta solo si lo abrió una alarma; un caso de observación es contexto.
        if (e.signals.any { it.source == SignalSource.FORENSIC_CASE && it.forensicOrigin == "ALARM" }) r += RelevanceReason.FORENSIC_CASE
        // Cualquier tipo de identidad del episodio cuenta, no solo el principal (§7 de episode-rules.md): un episodio
        // con tres señales de banda y una de TAC tiene un patrón de identidad. La medición es la del patrón principal,
        // así que solo descarta el patrón principal cuando no se ve en los datos.
        if (e.mainPattern in IDENTITY_KINDS && e.measurement.status != MeasureStatus.NOT_VISIBLE ||
            e.patterns.any { it != e.mainPattern && it in IDENTITY_KINDS }) r += RelevanceReason.IDENTITY_PATTERN
        if (downgrade(e)) r += RelevanceReason.TECHNOLOGY_DOWNGRADE
        // La coincidencia con Radio es contexto adicional, no evidencia independiente: no eleva la relevancia.
        if (r.isNotEmpty()) return EpisodeRelevance(SpoofingRelevance.NEEDS_MORE_DATA, r)
        // La repetición (persistencia o recurrencia) de un comportamiento habitual no eleva la sospecha.
        return EpisodeRelevance(SpoofingRelevance.NO_INDICATION,
            listOf(if (e.measurement.status == MeasureStatus.NOT_VISIBLE) RelevanceReason.NOT_VISIBLE_IN_DATA else RelevanceReason.COMMON_BEHAVIOUR))
    }

    fun groups(eps: List<Episode>, zone: ZoneId): List<ObservationGroup> =
        eps.groupBy { e -> Triple(e.mainPattern, downgrade(e), e.relevance.relevance) }
            .entries.sortedWith(compareByDescending<Map.Entry<Triple<PatternKind, Boolean, SpoofingRelevance>, List<Episode>>> { it.key.third.ordinal }.thenBy { it.value.minOf { e -> e.start } })
            .mapIndexed { i, (k, l) ->
                ObservationGroup(i + 1, k.first, k.second, k.third, l.flatMap { it.relevance.reasons }.distinct(), l.map { it.number },
                    l.flatMap { e -> e.identities.map { it.key } }.distinct(), l.map { it.start.atZone(zone).toLocalDate() }.distinct().sorted(),
                    l.sumOf { it.moments }, l.any { it.hasAlarm })
            }

    fun overall(eps: List<Episode>, unplaced: Boolean): SpoofingRelevance {
        val o = when {
            eps.any { it.relevance.relevance == SpoofingRelevance.NEEDS_MORE_DATA } -> SpoofingRelevance.NEEDS_MORE_DATA
            unplaced -> SpoofingRelevance.NEEDS_MORE_DATA
            else -> SpoofingRelevance.NO_INDICATION
        }
        check(SPECIALIST_CRITERIA_EVALUATED || o != SpoofingRelevance.SPECIALIST)
        return o
    }
}

/** Carencias de datos (disponibilidad de importaciones), compartidas por ambos informes. */
object DataGaps {
    fun of(set: EpisodeSet, results: List<ImportResult>, zoneConfirmed: Boolean): List<DataGap> {
        val gaps = ArrayList<DataGap>()
        val imported = results.filter { it.imported }.map { it.kind }.toSet()
        if (setOf(ExportKind.HISTORY, ExportKind.FORENSIC_CASE, ExportKind.STABLE_SITE).none { it in imported }) gaps += DataGap.NO_SIGNAL_SOURCE
        if (!zoneConfirmed && ExportKind.HISTORY in imported) gaps += DataGap.ZONE_NOT_CONFIRMED
        if (set.unplacedCollectorRows.isNotEmpty()) gaps += DataGap.UNPLACED_ROWS
        if (ExportKind.SERVICE_STATE !in imported) gaps += DataGap.NO_RADIO
        return gaps
    }
}
