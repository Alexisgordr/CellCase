package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.HistoryImport
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.leaves
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.GeometryData
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.ServiceStateEvent
import com.alexisgordr.cellcase.core.model.StableSiteData
import com.alexisgordr.cellcase.core.model.TopologyData
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.time.Instant



/** Una exportación agregada elegida como referencia y las demás del mismo tipo (que no se suman). */
data class Selected<T>(val primary: T, val primaryImportId: String, val others: List<String>)

/** Coherencia entre la columna ServiceState del historial y el último evento de Radio anterior. */
data class ServiceCoherence(val compared: Int, val matching: Int, val differing: List<HistoryObservation>, val withoutPriorEvent: Int)

/** Modelo común del expediente: todas las importaciones correctas y sus análisis. */
data class CaseAnalysis(
    val results: List<ImportResult>,
    val history: MergedHistory?,
    val historyAnalysis: HistoryAnalysis?,
    val serviceEvents: List<ServiceStateEvent>,
    val serviceDuplicates: Int,
    val serviceCoherence: ServiceCoherence?,
    val forensicCases: List<Pair<String, ForensicCaseData>>,
    val forensicReexports: List<Pair<String, String>>,
    val topology: Selected<TopologyData>?,
    val geometry: Selected<GeometryData>?,
    val stableSite: Selected<StableSiteData>?,
    val episodes: EpisodeSet,
    /** Valoración para el lector no especialista; la comparten el informe explicado y el técnico. */
    val assessment: Assessment,
    /** Exportaciones con el mismo código de caso y contenido distinto (no se descartan; requieren revisión). */
    val forensicConflicts: List<Pair<String, String>> = emptyList(),
    /** Versiones de cada caso forense y su relación (equivalente, ampliada, contradictoria, divergente). */
    val forensicVersions: List<ForensicVersionGroup> = emptyList(),
    /** Coincidencias y diferencias entre fuentes (docs/cross-sources.md). Contexto: no cambia prioridades. */
    val cross: CrossResult? = null,
    /** Agregados con varias exportaciones sin orden demostrable y contenido distinto: no se usa ninguna (cross/2). */
    val aggregateConflicts: List<AggregateConflict> = emptyList(),
    /** Perfil local de ICdetector 3.0 (contexto en sombra; no interviene en la valoración, D-055). Null antes de 3.0. */
    val localProfile: LocalProfileSummary? = null
) {
    fun imported(kind: ExportKind) = results.filter { it.imported && it.kind == kind }
}

object CaseAnalyzer {
    fun analyze(input: List<ImportResult>, zone: DeclaredZone?, period: PeriodOfInterest?): CaseAnalysis {
        // Una copia de seguridad aporta una parte por exportación reconstruida: se analizan como archivos (backup-format.md §5).
        val results = input.leaves()
        val ok = results.filter { it.imported }
        val histories = ok.mapNotNull { (it.data as? ImportedData.History)?.data }
        val merged = HistoryMerge.merge(histories, zone)
        val ha = merged?.let { HistoryAnalyzer.analyze(it.import, period) }

        // Radio: eventos de varias exportaciones; los idénticos (mismo contenido) se cuentan una vez.
        val seenEv = HashSet<List<Any?>>()
        var evDup = 0
        val events = ok.mapNotNull { (it.data as? ImportedData.ServiceState)?.events }.flatten().filter { e ->
            val k = listOf(e.time, e.state, e.dataRegistered, e.voiceRegistered, e.searching, e.roaming, e.networkOperator, e.simOperator, e.dataNetwork, e.voiceNetwork, e.source)
            if (seenEv.add(k)) true else { evDup++; false }
        }.sortedWith(compareBy<ServiceStateEvent> { it.time ?: Instant.MAX }.thenBy { it.provenance.startLine })

        // Casos forenses: versiones del mismo caso clasificadas por contenido canónico (D-040). Ninguna se descarta.
        val forensicImports = ok.mapNotNull { r -> (r.data as? ImportedData.Forensic)?.data?.let { r to it } }
        val versions = ForensicVersions.group(forensicImports.map { (r, d) -> ForensicVersions.Version(r.importId, r.fileName, r.sha256, d) })
        val cases = versions.flatMap { g -> g.used.map { id -> id to forensicImports.first { it.first.importId == id }.second } }
        val reexports = versions.flatMap { g -> g.redundant.map { (kept, other) -> kept to other } }
        val conflicts = versions.filter { it.relation == VersionRelation.CONTRADICTORY || it.relation == VersionRelation.DIVERGENT }
            .flatMap { g -> g.used.zipWithNext() }

        /**
         * Exportación de referencia de un agregado (cross/2). Se usa la de fecha más reciente si es la única con esa fecha.
         * Si otras tienen la misma fecha o no tienen fecha y su contenido es distinto, no se puede saber cuál es posterior:
         * no se elige ninguna (ni por orden de importación ni por huella) y se devuelve el conflicto. Si el contenido es
         * idéntico, cualquiera sirve y se toma la primera por nombre de archivo y huella (independiente del orden).
         */
        fun <T : Any> pick(kind: ExportKind, get: (ImportedData) -> T?, time: (T) -> Instant?, canon: (T) -> Any, summary: (T) -> String, ref: (T) -> Provenance?): Pair<Selected<T>?, AggregateConflict?> {
            val list = ok.filter { it.kind == kind }.mapNotNull { r -> r.data?.let(get)?.let { r to it } }
                .sortedWith(compareByDescending<Pair<ImportResult, T>> { time(it.second) ?: Instant.MIN }.thenBy { it.first.fileName }.thenBy { it.first.sha256 })
            if (list.isEmpty()) return null to null
            val top = list.first(); val tTop = time(top.second)
            val undecided = list.filter { time(it.second) == null || time(it.second) == tTop }
            if (undecided.size > 1 && undecided.map { canon(it.second) }.distinct().size > 1)
                return null to AggregateConflict(kind, undecided.sortedWith(compareBy({ it.first.fileName }, { it.first.sha256 })).map { (r, d) ->
                    AggregateVersion(r.importId, r.fileName, time(d), summary(d), ref(d)) })
            return Pair(Selected(top.second, top.first.importId, list.drop(1).map { it.first.importId }), null)
        }
        val p0 = Provenance("", "", null, 0, 0, 0)
        val (topo, topoConflict) = pick(ExportKind.TOPOLOGY, { (it as? ImportedData.Topology)?.data }, { it.generatedAt },
            { d -> d.routes.map { it.copy(provenance = p0) }.sortedBy { it.toString() } }, { d -> "routes=${d.routes.size};handovers=${d.routes.sumOf { it.observations ?: 0 }}" },
            { d -> d.routes.firstOrNull()?.provenance })
        val (geo, geoConflict) = pick(ExportKind.GEOMETRY, { (it as? ImportedData.Geometry)?.data }, { it.exportTime },
            { d -> listOf(d.cells.map { it.copy(provenance = p0) }.sortedBy { it.toString() }, d.edges.map { it.copy(provenance = p0) }.sortedBy { it.toString() },
                d.trips.map { it.copy(provenance = p0) }.sortedBy { it.toString() }) },
            { d -> "cells=${d.cells.size};routes=${d.edges.size};trips=${d.trips.size}" }, { d -> d.edges.firstOrNull()?.provenance ?: d.cells.firstOrNull()?.provenance })
        val (ss, ssConflict) = pick(ExportKind.STABLE_SITE, { (it as? ImportedData.StableSiteSet)?.data }, { d -> d.sites.mapNotNull { it.lastSeen }.maxOrNull() },
            { d -> listOf(d.sites.map { it.copy(provenance = p0) }.sortedBy { it.toString() }, d.siteCells.map { it.copy(provenance = p0) }.sortedBy { it.toString() },
                d.episodes.map { it.copy(provenance = p0) }.sortedBy { it.toString() }) },
            { d -> "sites=${d.sites.size};cells=${d.siteCells.size};episodes=${d.episodes.size}" }, { d -> d.sites.firstOrNull()?.provenance })
        val aggregateConflicts = listOfNotNull(topoConflict, geoConflict, ssConflict)

        val coherence = if (merged != null && events.isNotEmpty()) serviceCoherence(merged.import.observations, events) else null
        val displayZone = if (zone?.confirmed == true) zone.zoneId else java.time.ZoneOffset.UTC
        val episodes = EpisodeEngine.build(merged?.import?.observations.orEmpty(), events, cases, topo?.primary, geo?.primary, ss?.primary, displayZone)
        // Vista de los mismos hallazgos: relevancia por episodio, grupos y estado los calcula EpisodeEngine.
        val assessment = Assessment(episodes.overall, episodes.episodes.associate { it.number to it.relevance }, episodes.observationGroups,
            DataGaps.of(episodes, results, zone?.confirmed == true))
        val fileOf = ok.associate { it.importId to it.fileName }
        val historyRows = merged?.import?.observations.orEmpty()
        val cross = CrossSources.compute(CrossSources.Input(
            history = historyRows,
            historyFiles = merged?.sourceIds?.mapNotNull { fileOf[it] }.orEmpty(),
            historyHasServiceState = historyRows.any { "ServiceState" in it.raw },
            zoneConfirmed = zone?.confirmed == true,
            events = events,
            radioFiles = ok.filter { it.kind == ExportKind.SERVICE_STATE }.map { it.fileName },
            topology = topo?.primary, topologyFile = topo?.let { fileOf[it.primaryImportId] },
            geometry = geo?.primary, geometryFile = geo?.let { fileOf[it.primaryImportId] },
            stable = ss?.primary, stableFile = ss?.let { fileOf[it.primaryImportId] },
            forensic = cases.map { (id, d) -> Triple(id, fileOf[id] ?: id, d) },
            episodes = episodes.episodes,
            aggregateConflicts = aggregateConflicts
        ))
        val backups = input.mapNotNull { (it.data as? ImportedData.Backup)?.info }
        val profile = LocalProfileAnalyzer.of(merged, episodes, backups)
        return CaseAnalysis(results, merged, ha, events, evDup, coherence, cases, reexports, topo, geo, ss, episodes, assessment, conflicts, versions, cross, aggregateConflicts, profile)
    }

    private fun serviceCoherence(rows: List<HistoryObservation>, events: List<ServiceStateEvent>): ServiceCoherence {
        val timed = events.filter { it.time != null }
        var compared = 0; var matching = 0; var noPrior = 0
        val differing = ArrayList<HistoryObservation>()
        for (o in rows) {
            val t = o.time.instantOrNull ?: continue
            val st = o.serviceState ?: continue
            // último evento con hora <= t (búsqueda binaria)
            var lo = 0; var hi = timed.size - 1; var idx = -1
            while (lo <= hi) { val mid = (lo + hi) / 2; if (!timed[mid].time!!.isAfter(t)) { idx = mid; lo = mid + 1 } else hi = mid - 1 }
            if (idx < 0) { noPrior++; continue }
            compared++
            if (timed[idx].state == st) matching++ else differing += o
        }
        return ServiceCoherence(compared, matching, differing, noPrior)
    }
}
