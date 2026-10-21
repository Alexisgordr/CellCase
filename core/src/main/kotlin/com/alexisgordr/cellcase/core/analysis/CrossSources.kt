package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.GeometryData
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.ServiceStateEvent
import com.alexisgordr.cellcase.core.model.StableSiteData
import com.alexisgordr.cellcase.core.model.StableSiteEpisode
import com.alexisgordr.cellcase.core.model.TopologyData
import com.alexisgordr.cellcase.core.time.TimeResolution
import java.time.Duration
import java.time.Instant

/** Versión de las reglas de cruce entre fuentes (docs/cross-sources.md). */
const val CROSS_RULES_VERSION = "cross/3"

/** Tipo de resultado (docs/cross-sources.md §2). Ninguno es una conclusión sobre la red. */
enum class CrossOutcome { MATCH, DIFFERENCE, COMPLEMENTARY, NOT_COMPARABLE }

/** Comprobación del catálogo (§3). [id] es el identificador estable que cita el informe. */
enum class CrossCheck(val id: String) {
    SERVICE_STATE("X-SVC"), CELLS("X-CELL"), ROUTES("X-ROUTE"), AGGREGATE_COUNTERS("X-AGG"),
    STABLE_SITE_EPISODE("X-SHADOW"), FORENSIC_HISTORY("X-FOR"), FORENSIC_RADIO("X-FOR-RAD"), FORENSIC_AGGREGATES("X-FOR-AGG"),
    /** Varias exportaciones del mismo agregado sin orden demostrable y con contenido distinto (cross/2). */
    AGGREGATE_VERSIONS("X-VER")
}

/** Motivo del resultado: de él salen la explicación y las limitaciones en cada idioma. */
enum class CrossReason {
    // coincidencias
    SAME_VALUE, PRESENT_IN_BOTH, ROUTE_PRESENT, COUNTER_GREW, TRANSITION_IN_WINDOW, SERVING_IN_WINDOW,
    // diferencias
    VALUE_DIFFERS, COUNTER_DECREASED, OTHER_SERVING,
    // complementaria
    ONLY_IN_AGGREGATE, ONLY_IN_HISTORY, ONLY_IN_ONE_EXPORT, ROUTE_OTHER_TIME, NEIGHBOURS_ONLY_IN_CASE,
    RADIO_EVENTS_IN_WINDOW, NO_RADIO_EVENTS_IN_WINDOW, PRESENT_IN_AGGREGATE, ABSENT_FROM_AGGREGATE,
    // no comparable
    NO_OVERLAP, OUTSIDE_RADIO_COVERAGE, TRANSITION_BOUNDARY, AMBIGUOUS_TIME, ZONE_NOT_CONFIRMED, MISSING_VALUE,
    UNKNOWN_IDENTITY, NO_ROW_IN_WINDOW, TRUNCATED_EXPORT, AFTER_EXPORT, CELLS_NOT_IN_AGGREGATE, SAMPLED_HISTORY, ROUTE_NOT_EXPORTED,
    NO_EXPORT_TIME, MUTABLE_FIELD, NO_DATA,
    // cross/2
    STATE_NOT_INTERPRETABLE, SIMULTANEOUS_IDENTITIES, AMBIGUOUS_EXPORTS
}

/**
 * De dónde proceden las dos fuentes (§2.11). Solo [INDEPENDENT_FAMILIES] son familias distintas; en los demás
 * casos una coincidencia comprueba la coherencia de los archivos, no es una confirmación independiente.
 */
enum class Derivation { SAME_MEASUREMENT, SAME_OBSERVATION, SAME_DATABASE, SAME_PHONE_STATE, SAME_COLLECTOR, INDEPENDENT_FAMILIES }

/** Campo comparado. */
enum class CrossField { SERVICE_STATE, SERVING_CELL, PCI, ARFCN, VERIFIED, ROWS, DAYS, HANDOVERS, TRUSTED, MAX_SERVING_DAYS, FAMILIARITY, CHANGES, LAST_SEEN, EVENTS, NEIGHBOURS, TRANSITIONS, EXPORT_TIME, CONTENT }

/** Siguiente comprobación útil. */
enum class CrossNext { CONFIRM_ZONE, EXPORT_RADIO_COVERING, EXPORT_AGGREGATE_AGAIN, EXPORT_FORENSIC_CASE, REVIEW_CITED_ROWS, EXPORT_HISTORY_COVERING, KEEP_ONE_EXPORT }

/**
 * Un valor comparado: [a] de la primera fuente y [b] de la segunda, tal cual (códigos, números en texto plano,
 * identidades completas). [entity] es la identidad o ruta a la que se refiere, si es una sola. [count]: número de
 * filas o elementos con esa pareja de valores.
 */
data class Compared(val field: CrossField, val a: String?, val b: String?, val count: Int = 1, val entity: String? = null)

/** Una versión de un agregado: archivo, fecha declarada (o derivada) y resumen de su contenido. */
data class AggregateVersion(val importId: String, val fileName: String, val time: Instant?, val summary: String, val ref: Provenance?)

/**
 * Exportaciones del mismo agregado con fecha igual o ausente y contenido distinto (cross/2). No hay forma de saber
 * cuál es posterior, así que no se elige ninguna (ni por orden de importación ni por huella): se conservan todas y se
 * presenta el conflicto. Las versiones van ordenadas por nombre de archivo.
 */
data class AggregateConflict(val kind: ExportKind, val versions: List<AggregateVersion>)

/** Un resultado trazable (§4). */
data class CrossFinding(
    val check: CrossCheck,
    val outcome: CrossOutcome,
    val reason: CrossReason,
    val sourceA: ExportKind,
    val sourceB: ExportKind,
    /** Archivos usados de cada fuente (ordenados por nombre). */
    val files: List<String>,
    /** Identidades completas, rutas «origen>destino» o códigos de caso. */
    val entities: List<String>,
    val start: Instant?,
    val end: Instant?,
    /** Tolerancia usada para emparejar momentos (null si la comprobación no usa tiempo). */
    val window: Duration?,
    /** Elementos comparados con este resultado (filas, celdas, rutas, episodios). */
    val count: Int,
    val compared: List<Compared>,
    val refs: List<Provenance>,
    val derivation: Derivation,
    val episodes: List<Int>,
    val next: CrossNext?,
    /** Otras fuentes con exactamente el mismo resultado frente a [sourceA] (se presentan juntas; no se suman). */
    val alsoB: List<ExportKind> = emptyList()
) {
    val sourcesB: List<ExportKind> get() = listOf(sourceB) + alsoB
}

/**
 * Resultado del expediente. [sources]: tipos con datos importados. [pairsChecked]: parejas de fuentes que se
 * pudieron cruzar. Si no hay ninguna, [why] explica por qué (una sola fuente, ninguna pareja implementada…).
 */
data class CrossResult(
    val version: String,
    val findings: List<CrossFinding>,
    val sources: Set<ExportKind>,
    val pairsChecked: Set<Pair<ExportKind, ExportKind>>,
    val why: CrossReason?
) {
    fun count(o: CrossOutcome) = findings.filter { it.outcome == o }.size
    /** ¿Hay al menos un resultado comparable (coincidencia o diferencia)? */
    val anyComparable: Boolean get() = findings.any { it.outcome == CrossOutcome.MATCH || it.outcome == CrossOutcome.DIFFERENCE }
    fun forEpisode(n: Int) = findings.filter { n in it.episodes }
}

object CrossSources {
    /** X-SVC: una diferencia a ≤ 60 s de un cambio de Radio no es comparable (hora de inserción, resolución de segundos). */
    val TRANSITION_TOLERANCE: Duration = Duration.ofSeconds(60)
    /** X-ROUTE: dos filas consecutivas a más de 10 min no describen un cambio directo de servidora. */
    val ROUTE_MAX_GAP: Duration = EpisodeEngine.PREVIOUS_ROW_MAX
    /** X-SHADOW (Topología): margen sobre la ventana del episodio de Stable-Site. */
    val SHADOW_ROUTE_MARGIN: Duration = Duration.ofSeconds(60)
    /** X-SHADOW (Historial): margen por la hora de inserción del historial. */
    val SHADOW_ROW_MARGIN: Duration = Duration.ofSeconds(5)
    /** X-FOR: margen alrededor de la ventana del caso y tolerancia para emparejar una fila con una muestra. */
    val CASE_MARGIN: Duration = Duration.ofSeconds(60)
    val PAIR_TOLERANCE: Duration = Duration.ofSeconds(3)
    /** Margen para vincular un resultado a un episodio. */
    val EPISODE_LINK: Duration = EpisodeEngine.SERVICE_LINK
    /** Estados de servicio de Android que se pueden comparar. Vacío, «UNKNOWN» u otro texto no se interpretan (cross/2). */
    val COMPARABLE_STATES = setOf("IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF")
    const val MAX_REFS = 6
    const val MAX_COMPARED = 8

    private val provOrder = compareBy<Provenance>({ it.fileName }, { it.zipEntry ?: "" }, { it.recordIndex })

    /** Entradas que necesita el motor; todas ya calculadas por [CaseAnalyzer]. */
    data class Input(
        val history: List<HistoryObservation>,
        val historyFiles: List<String>,
        /** La tabla del historial incluye la columna ServiceState (si no, X-SVC no se intenta). */
        val historyHasServiceState: Boolean,
        val zoneConfirmed: Boolean,
        val events: List<ServiceStateEvent>,
        val radioFiles: List<String>,
        val topology: TopologyData?, val topologyFile: String?,
        val geometry: GeometryData?, val geometryFile: String?,
        val stable: StableSiteData?, val stableFile: String?,
        val forensic: List<Triple<String, String, ForensicCaseData>>,
        val episodes: List<Episode>,
        /** Exportaciones de un mismo agregado sin orden demostrable y con contenido distinto: no se ha elegido ninguna. */
        val aggregateConflicts: List<AggregateConflict> = emptyList()
    )

    fun compute(i: Input): CrossResult {
        val out = ArrayList<CrossFinding>()
        val sources = buildSet {
            if (i.history.isNotEmpty()) add(ExportKind.HISTORY)
            if (i.events.isNotEmpty()) add(ExportKind.SERVICE_STATE)
            if (i.topology != null) add(ExportKind.TOPOLOGY)
            if (i.geometry != null) add(ExportKind.GEOMETRY)
            if (i.stable != null) add(ExportKind.STABLE_SITE)
            if (i.forensic.isNotEmpty()) add(ExportKind.FORENSIC_CASE)
            i.aggregateConflicts.forEach { add(it.kind) }
        }
        val pairs = LinkedHashSet<Pair<ExportKind, ExportKind>>()
        val exactRows = i.history.filter { it.time.instantOrNull != null }
            .sortedWith(compareBy<HistoryObservation>({ it.time.instantOrNull }, { it.provenance.fileName }, { it.provenance.recordIndex }))
        val ctx = Ctx(i, exactRows)

        if (i.history.isNotEmpty() && i.events.isNotEmpty() && i.historyHasServiceState) { pairs += ExportKind.HISTORY to ExportKind.SERVICE_STATE; out += serviceState(ctx) }
        if (i.history.isNotEmpty()) {
            i.topology?.let { pairs += ExportKind.HISTORY to ExportKind.TOPOLOGY; out += cells(ctx, ExportKind.TOPOLOGY); out += routes(ctx, ExportKind.TOPOLOGY) }
            i.geometry?.let { pairs += ExportKind.HISTORY to ExportKind.GEOMETRY; out += cells(ctx, ExportKind.GEOMETRY); out += routes(ctx, ExportKind.GEOMETRY) }
            i.stable?.let { pairs += ExportKind.HISTORY to ExportKind.STABLE_SITE; out += cells(ctx, ExportKind.STABLE_SITE) }
        }
        if (i.topology != null && i.geometry != null) { pairs += ExportKind.TOPOLOGY to ExportKind.GEOMETRY; out += aggregateCounters(ctx) }
        if (i.stable != null && i.stable.episodes.isNotEmpty()) {
            if (i.topology != null) { pairs += ExportKind.STABLE_SITE to ExportKind.TOPOLOGY; out += shadowTopology(ctx) }
            if (i.history.isNotEmpty()) { pairs += ExportKind.STABLE_SITE to ExportKind.HISTORY; out += shadowHistory(ctx) }
        }
        i.aggregateConflicts.forEach { cf ->
            pairs += cf.kind to cf.kind
            out += finding(CrossCheck.AGGREGATE_VERSIONS, CrossOutcome.NOT_COMPARABLE, CrossReason.AMBIGUOUS_EXPORTS, cf.kind, cf.kind, cf.versions.map { it.fileName },
                cf.versions.map { it.fileName }, null, null, null, cf.versions.size,
                cf.versions.flatMap { v -> listOf(Compared(CrossField.EXPORT_TIME, v.time?.toString(), null, entity = v.fileName), Compared(CrossField.CONTENT, v.summary, null, entity = v.fileName)) },
                cf.versions.mapNotNull { it.ref }, Derivation.SAME_DATABASE, next = CrossNext.KEEP_ONE_EXPORT)
        }
        if (i.forensic.isNotEmpty()) {
            if (i.history.isNotEmpty()) { pairs += ExportKind.FORENSIC_CASE to ExportKind.HISTORY; out += forensicHistory(ctx) }
            if (i.events.isNotEmpty()) { pairs += ExportKind.FORENSIC_CASE to ExportKind.SERVICE_STATE; out += forensicRadio(ctx) }
            if (i.topology != null || i.geometry != null || i.stable != null) { out += forensicAggregates(ctx) }
        }
        // Resultados idénticos frente a varios agregados (mismas entidades, motivo y periodo) se presentan juntos.
        val merged = out.groupBy { f -> listOf(f.check, f.outcome, f.reason, f.sourceA, f.entities, f.start, f.end, f.window, f.count, f.derivation, f.episodes.sorted(), f.next) }
            .values.map { l -> if (l.size == 1) l[0] else l.sortedBy { it.sourceB.ordinal }.let { s0 ->
                s0[0].copy(alsoB = s0.drop(1).map { it.sourceB }, files = s0.flatMap { it.files }, compared = s0.flatMap { it.compared }.distinct(), refs = s0.flatMap { it.refs }) } }
        val sorted = merged.map { f -> f.copy(refs = f.refs.distinct().sortedWith(provOrder).take(MAX_REFS), files = f.files.distinct().sorted(),
            compared = f.compared.sortedWith(compareBy<Compared>({ it.field }, { it.entity ?: "" }, { it.a ?: "" }, { it.b ?: "" })).take(MAX_COMPARED),
            episodes = f.episodes.distinct().sorted()) }
            .sortedWith(compareBy<CrossFinding>({ it.check.ordinal }, { it.sourceB.ordinal }, { it.outcome.ordinal }, { it.reason.ordinal },
                { it.start ?: Instant.MIN }, { it.entities.joinToString() }))
        val why = when {
            sources.size <= 1 -> CrossReason.NO_DATA
            pairs.isEmpty() -> CrossReason.NO_OVERLAP
            else -> null
        }
        return CrossResult(CROSS_RULES_VERSION, sorted, sources, pairs, why)
    }

    // ───────────────────────── utilidades ─────────────────────────

    private class Ctx(val i: Input, val rows: List<HistoryObservation>) {
        val times: List<Instant> = rows.map { it.time.instantOrNull!! }
        /** Filas con instante único en [from, to] (búsqueda binaria). */
        fun rowsIn(from: Instant, to: Instant): List<HistoryObservation> {
            var lo = 0; var hi = times.size
            while (lo < hi) { val m = (lo + hi) / 2; if (times[m].isBefore(from)) lo = m + 1 else hi = m }
            val out = ArrayList<HistoryObservation>()
            var k = lo
            while (k < times.size && !times[k].isAfter(to)) { out += rows[k]; k++ }
            return out
        }
        /** Filas del historial sin instante único cuya hora local podría caer en [from, to] (ambiguas o sin zona). */
        fun unplacedNear(from: Instant, to: Instant): List<HistoryObservation> = i.history.filter { o ->
            when (val r = o.time.resolution) {
                is TimeResolution.Ambiguous -> !r.later.isBefore(from) && !r.earlier.isAfter(to)
                is TimeResolution.Nonexistent -> !r.gapStart.isBefore(from.minus(Duration.ofHours(1))) && !r.gapStart.isAfter(to)
                else -> false
            }
        }
        fun episodesAt(t: Instant, key: String? = null): List<Int> = i.episodes.filter { e ->
            !t.isBefore(e.start.minus(EPISODE_LINK)) && !t.isAfter(e.end.plus(EPISODE_LINK)) &&
                (key == null || e.identities.any { it.key == key } || e.signals.any { it.identity?.joinKey == key })
        }.map { it.number }
        val historyPeriod: Pair<Instant, Instant>? = if (times.isEmpty()) null else times.first() to times.last()
        /**
         * Instantes con más de una identidad completa distinta (cross/2). La hora del historial tiene resolución de
         * segundos y no hay otro campo de orden fiable: no se sabe cuál fue antes, así que esas filas no sirven para
         * derivar cambios de servidora ni para emparejarlas con otra fuente.
         */
        val simultaneous: Map<Instant, List<HistoryObservation>> = rows.groupBy { it.time.instantOrNull!! }
            .filterValues { l -> l.mapNotNull { it.identity.joinKey }.distinct().size > 1 }
        fun isSimultaneous(o: HistoryObservation) = o.time.instantOrNull?.let { it in simultaneous } == true
    }

    private fun key(id: CellIdentity?) = id?.joinKey

    private fun finding(check: CrossCheck, outcome: CrossOutcome, reason: CrossReason, a: ExportKind, b: ExportKind, files: List<String?>,
                        entities: List<String>, start: Instant?, end: Instant?, window: Duration?, count: Int, compared: List<Compared>,
                        refs: List<Provenance>, derivation: Derivation, episodes: List<Int> = emptyList(), next: CrossNext? = null) =
        CrossFinding(check, outcome, reason, a, b, files.filterNotNull(), entities.distinct().sorted(), start, end, window, count, compared, refs, derivation, episodes, next)

    private fun nextFor(r: CrossReason): CrossNext? = when (r) {
        CrossReason.ZONE_NOT_CONFIRMED, CrossReason.AMBIGUOUS_TIME -> CrossNext.CONFIRM_ZONE
        CrossReason.OUTSIDE_RADIO_COVERAGE, CrossReason.NO_OVERLAP -> CrossNext.EXPORT_RADIO_COVERING
        CrossReason.TRUNCATED_EXPORT, CrossReason.AFTER_EXPORT, CrossReason.ROUTE_NOT_EXPORTED -> CrossNext.EXPORT_AGGREGATE_AGAIN
        CrossReason.VALUE_DIFFERS, CrossReason.OTHER_SERVING, CrossReason.COUNTER_DECREASED -> CrossNext.REVIEW_CITED_ROWS
        CrossReason.NO_ROW_IN_WINDOW -> CrossNext.EXPORT_FORENSIC_CASE
        else -> null
    }

    // ───────────────────────── X-SVC: Historial ↔ Radio ─────────────────────────

    private data class SvcItem(val reason: CrossReason, val row: HistoryObservation, val event: ServiceStateEvent?, val t: Instant?)

    private fun serviceState(c: Ctx): List<CrossFinding> {
        val i = c.i
        val ev = i.events.filter { it.time != null }.sortedWith(compareBy<ServiceStateEvent>({ it.time }, { it.provenance.fileName }, { it.provenance.recordIndex }))
        if (ev.isEmpty()) return emptyList()
        val first = ev.first().time!!; val last = ev.last().time!!
        val files = i.historyFiles + i.radioFiles
        val withState = i.history.filter { "ServiceState" in it.raw }
        if (withState.isEmpty()) return emptyList()
        // Sin periodo común: un único resultado con los dos periodos.
        val hp = c.historyPeriod
        if (hp != null && (hp.second.isBefore(first) || hp.first.isAfter(last))) {
            return listOf(finding(CrossCheck.SERVICE_STATE, CrossOutcome.NOT_COMPARABLE, CrossReason.NO_OVERLAP, ExportKind.HISTORY, ExportKind.SERVICE_STATE, files,
                listOf("SERVICE"), hp.first, hp.second, null, withState.size,
                listOf(Compared(CrossField.EVENTS, "${hp.first}/${hp.second}", "$first/$last")), listOfNotNull(c.rows.firstOrNull()?.provenance, ev.first().provenance),
                Derivation.SAME_PHONE_STATE, next = CrossNext.EXPORT_RADIO_COVERING))
        }
        val items = withState.map { o ->
            val st = o.serviceState
            val t = o.time.instantOrNull
            when {
                o.time.resolution is TimeResolution.NoConfirmedZone || (o.time.resolution == null && !i.zoneConfirmed) -> SvcItem(CrossReason.ZONE_NOT_CONFIRMED, o, null, null)
                o.time.resolution is TimeResolution.Ambiguous || o.time.resolution is TimeResolution.Nonexistent -> SvcItem(CrossReason.AMBIGUOUS_TIME, o, null, null)
                t == null -> SvcItem(CrossReason.MISSING_VALUE, o, null, null)
                st == null -> SvcItem(CrossReason.MISSING_VALUE, o, null, t)
                st !in COMPARABLE_STATES -> SvcItem(CrossReason.STATE_NOT_INTERPRETABLE, o, null, t)
                t.isBefore(first) || t.isAfter(last) -> SvcItem(CrossReason.OUTSIDE_RADIO_COVERAGE, o, null, t)
                else -> {
                    var lo = 0; var hi = ev.size - 1; var idx = -1
                    while (lo <= hi) { val m = (lo + hi) / 2; if (!ev[m].time!!.isAfter(t)) { idx = m; lo = m + 1 } else hi = m - 1 }
                    val prior = ev[idx]
                    val next = ev.getOrNull(idx + 1)
                    val near = listOfNotNull(prior, next).minOf { Duration.between(it.time, t).abs() }
                    when {
                        // Estado de Radio ausente o no interpretable: nunca se compara (cross/2).
                        prior.state == null || prior.state !in COMPARABLE_STATES -> SvcItem(CrossReason.STATE_NOT_INTERPRETABLE, o, prior, t)
                        prior.state == st -> SvcItem(CrossReason.SAME_VALUE, o, prior, t)
                        near <= TRANSITION_TOLERANCE -> SvcItem(CrossReason.TRANSITION_BOUNDARY, o, prior, t)
                        else -> SvcItem(CrossReason.VALUE_DIFFERS, o, prior, t)
                    }
                }
            }
        }
        return items.groupBy { it.reason }.map { (r, l) ->
            val outcome = when (r) { CrossReason.SAME_VALUE -> CrossOutcome.MATCH; CrossReason.VALUE_DIFFERS -> CrossOutcome.DIFFERENCE; else -> CrossOutcome.NOT_COMPARABLE }
            val ts = l.mapNotNull { it.t }
            val pairsCount = l.filter { it.event != null }.groupingBy { (it.row.serviceState ?: "") to (it.event!!.state ?: "") }.eachCount()
            val compared = if (outcome == CrossOutcome.MATCH) pairsCount.map { (p, n) -> Compared(CrossField.SERVICE_STATE, p.first, p.second, n) }
                else pairsCount.entries.sortedByDescending { it.value }.map { (p, n) -> Compared(CrossField.SERVICE_STATE, p.first, p.second, n) }
            val examples = l.sortedWith(compareBy({ it.t ?: Instant.MIN }, { it.row.provenance.recordIndex })).let { if (outcome == CrossOutcome.MATCH) it.take(1) else it.take(3) }
            finding(CrossCheck.SERVICE_STATE, outcome, r, ExportKind.HISTORY, ExportKind.SERVICE_STATE, files, listOf("SERVICE"),
                ts.minOrNull(), ts.maxOrNull(), if (r == CrossReason.TRANSITION_BOUNDARY || r == CrossReason.VALUE_DIFFERS) TRANSITION_TOLERANCE else null, l.size, compared,
                examples.flatMap { listOfNotNull(it.row.provenance, it.event?.provenance) }, Derivation.SAME_PHONE_STATE,
                episodes = if (outcome == CrossOutcome.DIFFERENCE) l.flatMap { it.t?.let { t -> c.episodesAt(t) }.orEmpty() } else emptyList(),
                next = nextFor(r))
        }
    }

    // ───────────────────────── X-CELL: Historial ↔ agregados (identidades) ─────────────────────────

    private fun aggregateFile(i: Input, k: ExportKind) = when (k) { ExportKind.TOPOLOGY -> i.topologyFile; ExportKind.GEOMETRY -> i.geometryFile; else -> i.stableFile }

    /** Identidades completas de un agregado con su valor de contexto y una referencia; y las identidades desconocidas. */
    private fun aggregateCells(i: Input, k: ExportKind): Pair<Map<String, Pair<String, Provenance>>, List<Pair<String, Provenance>>> {
        val known = LinkedHashMap<String, Pair<String, Provenance>>()
        val unknown = ArrayList<Pair<String, Provenance>>()
        when (k) {
            ExportKind.TOPOLOGY -> {
                val routes = i.topology!!.routes
                val handovers = HashMap<String, Int>()
                routes.forEach { r -> listOf(r.from to r.fromRaw, r.to to r.toRaw).forEach { (id, raw) ->
                    val kk = key(id); if (kk == null) unknown += raw to r.provenance else handovers.merge(kk, r.observations ?: 0, Int::plus) } }
                routes.forEach { r -> listOf(r.from, r.to).forEach { id -> key(id)?.let { kk -> if (kk !in known) known[kk] = (handovers[kk] ?: 0).toString() to r.provenance } } }
            }
            ExportKind.GEOMETRY -> i.geometry!!.cells.forEach { g -> val kk = key(g.identity); if (kk == null) unknown += g.raw to g.provenance else known.putIfAbsent(kk, (g.familiarity ?: "?") to g.provenance) }
            else -> {
                // Claves solapadas: máximo de días por clave, nunca la suma (§2.7).
                val byKey = i.stable!!.siteCells.groupBy { key(it.identity) }
                byKey.forEach { (kk, l) -> if (kk == null) l.forEach { unknown += it.raw to it.provenance }
                    else known[kk] = (l.mapNotNull { it.servingDays }.maxOrNull()?.toString() ?: "?") to l.map { it.provenance }.minWith(provOrder) }
            }
        }
        return known to unknown.distinctBy { it.first }
    }

    private fun cells(c: Ctx, k: ExportKind): List<CrossFinding> {
        val i = c.i
        val hist = i.history.groupBy { it.identity.joinKey }
        val histKeys = hist.keys.filterNotNull().toSet()
        val unknownHist = hist[null].orEmpty()
        val (agg, aggUnknown) = aggregateCells(i, k)
        val files = i.historyFiles + aggregateFile(i, k)
        val field = when (k) { ExportKind.TOPOLOGY -> CrossField.HANDOVERS; ExportKind.GEOMETRY -> CrossField.FAMILIARITY; else -> CrossField.MAX_SERVING_DAYS }
        fun days(l: List<HistoryObservation>) = l.mapNotNull { it.time.local?.toLocalDate() }.toSet().size
        val both = agg.keys.filter { it in histKeys }
        val onlyAgg = agg.keys.filter { it !in histKeys }
        val onlyHist = histKeys.filter { it !in agg }
        val out = ArrayList<CrossFinding>()
        if (both.isNotEmpty()) out += finding(CrossCheck.CELLS, CrossOutcome.MATCH, CrossReason.PRESENT_IN_BOTH, ExportKind.HISTORY, k, files, both, null, null, null, both.size,
            both.flatMap { kk -> listOf(Compared(CrossField.ROWS, hist[kk]!!.size.toString(), null, entity = kk), Compared(field, null, agg[kk]!!.first, entity = kk)) },
            both.flatMap { kk -> listOf(hist[kk]!!.minWith(compareBy { it.provenance.recordIndex }).provenance, agg[kk]!!.second) }, Derivation.SAME_COLLECTOR)
        if (onlyAgg.isNotEmpty()) out += finding(CrossCheck.CELLS, CrossOutcome.COMPLEMENTARY, CrossReason.ONLY_IN_AGGREGATE, ExportKind.HISTORY, k, files, onlyAgg, null, null, null, onlyAgg.size,
            onlyAgg.map { kk -> Compared(field, null, agg[kk]!!.first, entity = kk) }, onlyAgg.map { agg[it]!!.second }, Derivation.SAME_COLLECTOR)
        if (onlyHist.isNotEmpty() && agg.isNotEmpty()) out += finding(CrossCheck.CELLS, CrossOutcome.COMPLEMENTARY, CrossReason.ONLY_IN_HISTORY, ExportKind.HISTORY, k, files, onlyHist, null, null, null, onlyHist.size,
            onlyHist.sortedByDescending { hist[it]!!.size }.map { kk -> Compared(CrossField.ROWS, hist[kk]!!.size.toString(), null, entity = kk) },
            onlyHist.map { kk -> hist[kk]!!.minWith(compareBy { it.provenance.recordIndex }).provenance }, Derivation.SAME_COLLECTOR,
            next = if (k == ExportKind.TOPOLOGY && i.topology!!.possibleTruncation) CrossNext.EXPORT_AGGREGATE_AGAIN else null)
        if (unknownHist.isNotEmpty() || aggUnknown.isNotEmpty()) out += finding(CrossCheck.CELLS, CrossOutcome.NOT_COMPARABLE, CrossReason.UNKNOWN_IDENTITY, ExportKind.HISTORY, k, files,
            emptyList(), null, null, null, unknownHist.size + aggUnknown.size,
            listOf(Compared(CrossField.ROWS, unknownHist.size.toString(), aggUnknown.size.toString())),
            unknownHist.take(2).map { it.provenance } + aggUnknown.take(2).map { it.second }, Derivation.SAME_COLLECTOR)
        return out
    }

    // ───────────────────────── X-ROUTE: cambios de servidora del historial ↔ rutas ─────────────────────────

    private data class Change(val from: String, val to: String, val at: Instant, val refs: List<Provenance>)

    /**
     * Cambios de servidora entre instantes consecutivos (cross/2). Cada instante debe tener una sola identidad completa
     * (sus filas repetidas cuentan una vez). Un instante con varias identidades o con una identidad incompleta corta la
     * cadena: no se deriva un cambio hacia él, desde él ni a través de él.
     */
    private fun historyChanges(c: Ctx): List<Change> {
        val instants = c.rows.groupBy { it.time.instantOrNull!! }.toSortedMap()
        val single = instants.map { (t, l) ->
            val keys = l.map { key(it.identity) }.distinct()
            Triple(t, if (keys.size == 1) keys[0] else null, l.map { it.provenance }.sortedWith(provOrder).first())
        }
        return single.zipWithNext().mapNotNull { (p, o) ->
            val a = p.second; val b = o.second
            if (a == null || b == null || a == b || Duration.between(p.first, o.first) > ROUTE_MAX_GAP) null else Change(a, b, o.first, listOf(p.third, o.third))
        }
    }

    private fun simultaneousFinding(c: Ctx, check: CrossCheck, b: ExportKind, files: List<String?>, window: Duration?, rows: Collection<HistoryObservation>? = null): CrossFinding? {
        val inst = if (rows == null) c.simultaneous else c.simultaneous.filterKeys { t -> rows.any { it.time.instantOrNull == t } }
        if (inst.isEmpty()) return null
        val all = inst.values.flatten()
        return finding(check, CrossOutcome.NOT_COMPARABLE, CrossReason.SIMULTANEOUS_IDENTITIES, ExportKind.HISTORY, b, files, all.mapNotNull { key(it.identity) },
            inst.keys.min(), inst.keys.max(), null, inst.size,
            inst.entries.sortedBy { it.key }.take(4).map { (t, l) -> Compared(CrossField.SERVING_CELL, l.mapNotNull { key(it.identity) }.distinct().sorted().joinToString(" + "), null, entity = t.toString()) },
            all.map { it.provenance }, Derivation.SAME_COLLECTOR, episodes = inst.keys.flatMap { c.episodesAt(it) })
    }

    private fun routes(c: Ctx, k: ExportKind): List<CrossFinding> {
        val i = c.i
        val files = i.historyFiles + aggregateFile(i, k)
        val changes = historyChanges(c)
        val sim = listOfNotNull(simultaneousFinding(c, CrossCheck.ROUTES, k, files, ROUTE_MAX_GAP))
        if (changes.isEmpty()) return sim + finding(CrossCheck.ROUTES, CrossOutcome.NOT_COMPARABLE, CrossReason.NO_DATA, ExportKind.HISTORY, k, files, emptyList(),
            c.historyPeriod?.first, c.historyPeriod?.second, ROUTE_MAX_GAP, 0, emptyList(), emptyList(), Derivation.SAME_OBSERVATION)
        data class R(val counter: Int?, val lastSeen: Instant?, val ref: Provenance)
        val agg: Map<Pair<String, String>, R>
        val exportAt: Instant?
        val truncated: Boolean
        if (k == ExportKind.TOPOLOGY) {
            val t = i.topology!!
            agg = t.routes.mapNotNull { r -> val a = key(r.from); val b = key(r.to); if (a == null || b == null) null else (a to b) to R(r.observations, r.lastSeen, r.provenance) }.toMap()
            exportAt = t.generatedAt; truncated = t.possibleTruncation
        } else {
            val g = i.geometry!!
            agg = g.edges.mapNotNull { r -> val a = key(r.from); val b = key(r.to); if (a == null || b == null) null else (a to b) to R(r.transitions, r.lastSeen, r.provenance) }.toMap()
            exportAt = g.exportTime; truncated = g.possibleTruncation
        }
        val nodes = agg.keys.flatMap { listOf(it.first, it.second) }.toSet()
        val byRoute = changes.groupBy { it.from to it.to }
        val items = byRoute.map { (route, l) ->
            val r = agg[route]
            val reason = when {
                r != null -> CrossReason.ROUTE_PRESENT
                truncated -> CrossReason.TRUNCATED_EXPORT
                exportAt != null && l.all { it.at.isAfter(exportAt) } -> CrossReason.AFTER_EXPORT
                route.first !in nodes || route.second !in nodes -> CrossReason.CELLS_NOT_IN_AGGREGATE
                else -> CrossReason.SAMPLED_HISTORY
            }
            Triple(route, l, reason)
        }
        return sim + items.groupBy { it.third }.map { (reason, l) ->
            val outcome = if (reason == CrossReason.ROUTE_PRESENT) CrossOutcome.MATCH else CrossOutcome.NOT_COMPARABLE
            val ts = l.flatMap { it.second.map { ch -> ch.at } }
            finding(CrossCheck.ROUTES, outcome, reason, ExportKind.HISTORY, k, files, l.map { "${it.first.first}>${it.first.second}" }, ts.minOrNull(), ts.maxOrNull(), ROUTE_MAX_GAP,
                l.size, l.sortedByDescending { it.second.size }.map { (route, ch, _) ->
                    Compared(CrossField.CHANGES, ch.size.toString(), agg[route]?.counter?.toString(), entity = "${route.first}>${route.second}") },
                l.flatMap { it.second.first().refs + listOfNotNull(agg[it.first]?.ref) }, Derivation.SAME_OBSERVATION,
                next = nextFor(reason))
        }
    }

    // ───────────────────────── X-AGG: Topología ↔ Geometría ─────────────────────────

    private fun aggregateCounters(c: Ctx): List<CrossFinding> {
        val i = c.i
        val t = i.topology!!; val g = i.geometry!!
        val files = listOf(i.topologyFile, i.geometryFile)
        val topo = t.routes.mapNotNull { r -> val a = key(r.from); val b = key(r.to); if (a == null || b == null) null else (a to b) to r }.toMap()
        val geo = g.edges.mapNotNull { r -> val a = key(r.from); val b = key(r.to); if (a == null || b == null) null else (a to b) to r }.toMap()
        val tAt = t.generatedAt; val gAt = g.exportTime
        data class It(val route: Pair<String, String>, val reason: CrossReason, val outcome: CrossOutcome, val compared: List<Compared>, val refs: List<Provenance>)
        val items = (topo.keys + geo.keys).distinct().map { route ->
            val a = topo[route]; val b = geo[route]
            val ent = "${route.first}>${route.second}"
            if (a == null || b == null) {
                val missingIn = if (a == null) t.possibleTruncation else g.possibleTruncation
                It(route, if (missingIn) CrossReason.TRUNCATED_EXPORT else CrossReason.ONLY_IN_ONE_EXPORT,
                    if (missingIn) CrossOutcome.NOT_COMPARABLE else CrossOutcome.COMPLEMENTARY,
                    listOf(Compared(CrossField.HANDOVERS, a?.observations?.toString(), b?.transitions?.toString(), entity = ent)), listOfNotNull(a?.provenance, b?.provenance))
            } else {
                val ta = a.observations; val tb = b.transitions
                val cmp = listOf(Compared(CrossField.HANDOVERS, ta?.toString(), tb?.toString(), entity = ent), Compared(CrossField.TRUSTED, a.trusted?.toString(), b.trusted?.toString(), entity = ent))
                val (reason, outcome) = when {
                    ta == null || tb == null -> CrossReason.MISSING_VALUE to CrossOutcome.NOT_COMPARABLE
                    ta == tb && a.trusted == b.trusted -> CrossReason.SAME_VALUE to CrossOutcome.MATCH
                    tAt == null || gAt == null -> CrossReason.NO_EXPORT_TIME to CrossOutcome.NOT_COMPARABLE
                    else -> {
                        // La exportación posterior no debería tener contadores menores (agregados acumulados de la misma base).
                        val (earlier, later) = if (gAt.isAfter(tAt)) (ta to tb) else if (tAt.isAfter(gAt)) (tb to ta) else (ta to tb)
                        val (eT, lT) = if (gAt.isAfter(tAt)) (a.trusted to b.trusted) else (b.trusted to a.trusted)
                        when {
                            tAt == gAt -> CrossReason.VALUE_DIFFERS to CrossOutcome.DIFFERENCE
                            later >= earlier && (eT == null || lT == null || lT >= eT) -> CrossReason.COUNTER_GREW to CrossOutcome.MATCH
                            else -> CrossReason.COUNTER_DECREASED to CrossOutcome.DIFFERENCE
                        }
                    }
                }
                It(route, reason, outcome, cmp, listOf(a.provenance, b.provenance))
            }
        }
        return items.groupBy { it.reason to it.outcome }.map { (ro, l) ->
            finding(CrossCheck.AGGREGATE_COUNTERS, ro.second, ro.first, ExportKind.TOPOLOGY, ExportKind.GEOMETRY, files, l.map { "${it.route.first}>${it.route.second}" },
                listOfNotNull(tAt, gAt).minOrNull(), listOfNotNull(tAt, gAt).maxOrNull(), null, l.size, l.flatMap { it.compared }, l.flatMap { it.refs },
                Derivation.SAME_DATABASE, next = nextFor(ro.first))
        }
    }

    // ───────────────────────── X-SHADOW: episodios de Stable-Site ↔ Topología e Historial ─────────────────────────

    private fun shadowWindow(e: StableSiteEpisode): Pair<Instant, Instant>? { val f = e.first ?: return null; return f to (listOfNotNull(e.last, e.closed).maxOrNull() ?: f) }

    private fun shadowEpisodes(c: Ctx, e: StableSiteEpisode): List<Int> {
        val (f, l) = shadowWindow(e) ?: return emptyList()
        val cand = key(e.candidate)
        return c.i.episodes.filter { ep -> ep.signals.any { s -> s.source == SignalSource.STABLE_SITE_SHADOW && s.refs.contains(e.provenance) } ||
            (cand != null && ep.identities.any { it.key == cand } && !l.isBefore(ep.start.minus(EPISODE_LINK)) && !f.isAfter(ep.end.plus(EPISODE_LINK))) }.map { it.number }
    }

    private fun shadowTopology(c: Ctx): List<CrossFinding> {
        val i = c.i; val t = i.topology!!
        val files = listOf(i.stableFile, i.topologyFile)
        return i.stable!!.episodes.mapNotNull { e ->
            val (f, l) = shadowWindow(e) ?: return@mapNotNull null
            val prev = key(e.previous); val cand = key(e.candidate)
            val ents = listOfNotNull(e.id)
            if (prev == null || cand == null) return@mapNotNull finding(CrossCheck.STABLE_SITE_EPISODE, CrossOutcome.NOT_COMPARABLE, CrossReason.UNKNOWN_IDENTITY,
                ExportKind.STABLE_SITE, ExportKind.TOPOLOGY, files, ents, f, l, SHADOW_ROUTE_MARGIN, 1, emptyList(), listOf(e.provenance), Derivation.SAME_OBSERVATION, shadowEpisodes(c, e))
            val route = t.routes.firstOrNull { key(it.from) == prev && key(it.to) == cand }
            val inWin = route?.lastSeen?.let { !it.isBefore(f.minus(SHADOW_ROUTE_MARGIN)) && !it.isAfter(l.plus(SHADOW_ROUTE_MARGIN)) } == true
            val (outcome, reason) = when {
                route != null && inWin -> CrossOutcome.MATCH to CrossReason.TRANSITION_IN_WINDOW
                route != null -> CrossOutcome.COMPLEMENTARY to CrossReason.ROUTE_OTHER_TIME
                t.possibleTruncation -> CrossOutcome.NOT_COMPARABLE to CrossReason.TRUNCATED_EXPORT
                t.generatedAt != null && f.isAfter(t.generatedAt) -> CrossOutcome.NOT_COMPARABLE to CrossReason.AFTER_EXPORT
                else -> CrossOutcome.NOT_COMPARABLE to CrossReason.ROUTE_NOT_EXPORTED
            }
            finding(CrossCheck.STABLE_SITE_EPISODE, outcome, reason, ExportKind.STABLE_SITE, ExportKind.TOPOLOGY, files, ents + "$prev>$cand", f, l, SHADOW_ROUTE_MARGIN, 1,
                listOfNotNull(Compared(CrossField.TRANSITIONS, "$prev>$cand", route?.let { "${key(it.from)}>${key(it.to)}" }),
                    route?.let { Compared(CrossField.LAST_SEEN, f.toString(), it.lastSeen?.toString()) },
                    route?.let { Compared(CrossField.HANDOVERS, null, it.observations?.toString()) }),
                listOfNotNull(e.provenance, route?.provenance), Derivation.SAME_OBSERVATION, shadowEpisodes(c, e), nextFor(reason))
        }
    }

    private fun shadowHistory(c: Ctx): List<CrossFinding> {
        val i = c.i
        val files = listOf(i.stableFile) + i.historyFiles
        return i.stable!!.episodes.mapNotNull { e ->
            val (f, l) = shadowWindow(e) ?: return@mapNotNull null
            val cand = key(e.candidate)
            val ents = listOfNotNull(e.id) + listOfNotNull(cand)
            val from = f.minus(SHADOW_ROW_MARGIN); val to = l.plus(SHADOW_ROW_MARGIN)
            val rows = c.rowsIn(from, to)
            val unplaced = c.unplacedNear(from, to)
            // Filas en instantes con varias identidades: no se sabe cuál fue la servidora en ese momento (cross/2).
            val known = rows.filter { key(it.identity) != null && !c.isSimultaneous(it) }
            val (outcome, reason) = when {
                cand == null -> CrossOutcome.NOT_COMPARABLE to CrossReason.UNKNOWN_IDENTITY
                !i.zoneConfirmed -> CrossOutcome.NOT_COMPARABLE to CrossReason.ZONE_NOT_CONFIRMED
                known.any { key(it.identity) == cand } -> CrossOutcome.MATCH to CrossReason.SERVING_IN_WINDOW
                unplaced.isNotEmpty() -> CrossOutcome.NOT_COMPARABLE to CrossReason.AMBIGUOUS_TIME
                known.isNotEmpty() -> CrossOutcome.DIFFERENCE to CrossReason.OTHER_SERVING
                rows.any { c.isSimultaneous(it) } -> CrossOutcome.NOT_COMPARABLE to CrossReason.SIMULTANEOUS_IDENTITIES
                rows.isNotEmpty() -> CrossOutcome.NOT_COMPARABLE to CrossReason.UNKNOWN_IDENTITY
                else -> CrossOutcome.NOT_COMPARABLE to CrossReason.NO_ROW_IN_WINDOW
            }
            val near = if (rows.isEmpty() && reason == CrossReason.NO_ROW_IN_WINDOW) {
                // filas más próximas antes y después, como referencia de por qué no hay comparación
                listOfNotNull(c.rows.lastOrNull { it.time.instantOrNull!!.isBefore(from) }, c.rows.firstOrNull { it.time.instantOrNull!!.isAfter(to) })
            } else rows
            finding(CrossCheck.STABLE_SITE_EPISODE, outcome, reason, ExportKind.STABLE_SITE, ExportKind.HISTORY, files, ents, f, l, SHADOW_ROW_MARGIN, rows.size,
                rows.groupingBy { key(it.identity) ?: it.identity.display }.eachCount().map { (k, n) -> Compared(CrossField.SERVING_CELL, cand, k, n) },
                listOf(e.provenance) + near.map { it.provenance }, Derivation.SAME_COLLECTOR, shadowEpisodes(c, e), nextFor(reason))
        }
    }

    // ───────────────────────── X-FOR: caso forense ↔ Historial, Radio y agregados ─────────────────────────

    private fun caseEpisodes(c: Ctx, importId: String) = c.i.episodes.filter { importId in it.forensicCaseIds }.map { it.number }

    private fun forensicHistory(c: Ctx): List<CrossFinding> {
        val i = c.i
        val out = ArrayList<CrossFinding>()
        for ((importId, file, case) in i.forensic) {
            val s = case.start ?: continue
            val e = case.end ?: s
            val code = case.meta.caseCode ?: file
            val files = listOf(file) + i.historyFiles
            val eps = caseEpisodes(c, importId)
            val serving = case.cells.filter { it.role == "SERVING" && it.time != null }.sortedBy { it.time }
            val neighbours = case.cells.count { it.isNeighbour }
            fun one(o: CrossOutcome, r: CrossReason, n: Int, cmp: List<Compared>, refs: List<Provenance>, d: Derivation = Derivation.SAME_MEASUREMENT) =
                finding(CrossCheck.FORENSIC_HISTORY, o, r, ExportKind.FORENSIC_CASE, ExportKind.HISTORY, files, listOf(code), s, e, PAIR_TOLERANCE, n, cmp, refs, d, eps, nextFor(r))
            if (neighbours > 0) out += one(CrossOutcome.COMPLEMENTARY, CrossReason.NEIGHBOURS_ONLY_IN_CASE, neighbours,
                listOf(Compared(CrossField.NEIGHBOURS, neighbours.toString(), null)), listOfNotNull(case.cells.firstOrNull { it.isNeighbour }?.provenance), Derivation.SAME_COLLECTOR)
            if (!i.zoneConfirmed) { out += one(CrossOutcome.NOT_COMPARABLE, CrossReason.ZONE_NOT_CONFIRMED, 0, emptyList(), emptyList()); continue }
            val hp = c.historyPeriod
            if (hp == null || hp.second.isBefore(s.minus(CASE_MARGIN)) || hp.first.isAfter(e.plus(CASE_MARGIN))) {
                out += one(CrossOutcome.NOT_COMPARABLE, CrossReason.NO_OVERLAP, 0, emptyList(), listOfNotNull(serving.firstOrNull()?.provenance)); continue
            }
            val rows = c.rowsIn(s.minus(CASE_MARGIN), e.plus(CASE_MARGIN))
            if (rows.isEmpty()) {
                val r = if (c.unplacedNear(s.minus(CASE_MARGIN), e.plus(CASE_MARGIN)).isNotEmpty()) CrossReason.AMBIGUOUS_TIME else CrossReason.NO_ROW_IN_WINDOW
                out += one(CrossOutcome.NOT_COMPARABLE, r, 0, emptyList(), listOfNotNull(serving.firstOrNull()?.provenance)); continue
            }
            val match = ArrayList<Pair<HistoryObservation, com.alexisgordr.cellcase.core.model.ForensicCell>>()
            val diff = ArrayList<Triple<HistoryObservation, com.alexisgordr.cellcase.core.model.ForensicCell, List<Compared>>>()
            val mutable = ArrayList<Compared>(); val mutableRefs = ArrayList<Provenance>()
            val unknown = ArrayList<HistoryObservation>()
            val boundary = ArrayList<HistoryObservation>()
            var unpaired = 0
            val simRows = rows.filter { c.isSimultaneous(it) }
            simultaneousFinding(c, CrossCheck.FORENSIC_HISTORY, ExportKind.FORENSIC_CASE, files, PAIR_TOLERANCE, simRows)?.let { f ->
                out += f.copy(sourceA = ExportKind.FORENSIC_CASE, sourceB = ExportKind.HISTORY, entities = listOf(code), start = s, end = e, episodes = eps) }
            for (o in rows) {
                if (c.isSimultaneous(o)) continue
                val t = o.time.instantOrNull!!
                val near = serving.filter { Duration.between(it.time, t).abs() <= PAIR_TOLERANCE }
                val sm = near.minByOrNull { Duration.between(it.time, t).abs() }
                if (sm == null) { unpaired++; continue }
                // En pleno cambio de celda (muestras de más de una servidora a ±3 s) la fila no se puede emparejar con una sola muestra.
                if (near.map { listOf(key(it.identity), it.pci, it.arfcn) }.distinct().size > 1) { boundary += o; continue }
                val hk = key(o.identity); val fk = key(sm.identity)
                if (hk == null || fk == null) { unknown += o; continue }
                val cmp = ArrayList<Compared>()
                if (hk != fk) cmp += Compared(CrossField.SERVING_CELL, fk, hk)
                if (o.pci != null && sm.pci != null && o.pci != sm.pci) cmp += Compared(CrossField.PCI, sm.pci.toString(), o.pci.toString(), entity = hk)
                if (o.arfcn != null && sm.arfcn != null && o.arfcn != sm.arfcn) cmp += Compared(CrossField.ARFCN, sm.arfcn.toString(), o.arfcn.toString(), entity = hk)
                if (cmp.isEmpty()) match += o to sm else diff += Triple(o, sm, cmp)
                // Verified: campo que el historial puede reescribir después de la captura (§2.9). Una fila de 3.1.0 o
                // posterior no tiene verificación externa (cross/3): su PENDING no se compara con nada.
                val sv = case.samples.minByOrNull { Duration.between(it.time ?: Instant.MIN, t).abs() }?.verified
                if (!o.externalVerificationRetired && o.verified != null && sv != null && o.verified != sv) { mutable += Compared(CrossField.VERIFIED, sv, o.verified, entity = hk); mutableRefs += o.provenance }
            }
            if (match.isNotEmpty()) out += one(CrossOutcome.MATCH, CrossReason.SAME_VALUE, match.size,
                match.groupingBy { key(it.first.identity)!! }.eachCount().map { (k, n) -> Compared(CrossField.SERVING_CELL, k, k, n) },
                match.take(2).flatMap { listOf(it.second.provenance, it.first.provenance) })
            if (diff.isNotEmpty()) out += one(CrossOutcome.DIFFERENCE, CrossReason.VALUE_DIFFERS, diff.size,
                diff.flatMap { it.third }.groupBy { Triple(it.field, it.a, it.b) }.map { (k, l) -> Compared(k.first, k.second, k.third, l.size, l.first().entity) },
                diff.take(3).flatMap { listOf(it.second.provenance, it.first.provenance) })
            if (mutable.isNotEmpty()) out += one(CrossOutcome.NOT_COMPARABLE, CrossReason.MUTABLE_FIELD, mutable.size,
                mutable.groupBy { Triple(it.a, it.b, it.entity) }.map { (k, l) -> Compared(CrossField.VERIFIED, k.first, k.second, l.size, k.third) }, mutableRefs)
            if (unknown.isNotEmpty()) out += one(CrossOutcome.NOT_COMPARABLE, CrossReason.UNKNOWN_IDENTITY, unknown.size, emptyList(), unknown.take(2).map { it.provenance })
            if (boundary.isNotEmpty()) out += one(CrossOutcome.NOT_COMPARABLE, CrossReason.TRANSITION_BOUNDARY, boundary.size,
                boundary.groupingBy { key(it.identity) ?: it.identity.display }.eachCount().map { (k, n) -> Compared(CrossField.SERVING_CELL, null, k, n) }, boundary.take(2).map { it.provenance })
            if (match.isEmpty() && diff.isEmpty() && unknown.isEmpty() && boundary.isEmpty() && simRows.isEmpty()) out += one(CrossOutcome.NOT_COMPARABLE, CrossReason.NO_ROW_IN_WINDOW, unpaired,
                listOf(Compared(CrossField.ROWS, unpaired.toString(), serving.size.toString())), rows.take(2).map { it.provenance })
        }
        return out
    }

    private fun forensicRadio(c: Ctx): List<CrossFinding> {
        val i = c.i
        val ev = i.events.filter { it.time != null }.sortedBy { it.time }
        return i.forensic.mapNotNull { (importId, file, case) ->
            val s = case.start ?: return@mapNotNull null
            val e = case.end ?: s
            val code = case.meta.caseCode ?: file
            val inWin = ev.filter { !it.time!!.isBefore(s.minus(CASE_MARGIN)) && !it.time.isAfter(e.plus(CASE_MARGIN)) }
            val prior = ev.lastOrNull { it.time!!.isBefore(s.minus(CASE_MARGIN)) }
            val (o, r) = when {
                inWin.isNotEmpty() -> CrossOutcome.COMPLEMENTARY to CrossReason.RADIO_EVENTS_IN_WINDOW
                prior != null && ev.last().time!!.isAfter(e) -> CrossOutcome.COMPLEMENTARY to CrossReason.NO_RADIO_EVENTS_IN_WINDOW
                else -> CrossOutcome.NOT_COMPARABLE to CrossReason.OUTSIDE_RADIO_COVERAGE
            }
            finding(CrossCheck.FORENSIC_RADIO, o, r, ExportKind.FORENSIC_CASE, ExportKind.SERVICE_STATE, listOf(file) + i.radioFiles, listOf(code), s, e, CASE_MARGIN,
                inWin.size, inWin.groupingBy { it.state ?: "?" }.eachCount().map { (st, n) -> Compared(CrossField.EVENTS, null, st, n) },
                inWin.take(3).map { it.provenance } + listOfNotNull(prior?.provenance), Derivation.INDEPENDENT_FAMILIES, caseEpisodes(c, importId), nextFor(r))
        }
    }

    private fun forensicAggregates(c: Ctx): List<CrossFinding> {
        val i = c.i
        val out = ArrayList<CrossFinding>()
        for ((importId, file, case) in i.forensic) {
            val k = key(case.meta.cellIdentity) ?: continue
            val code = case.meta.caseCode ?: file
            for (agg in listOf(ExportKind.TOPOLOGY, ExportKind.GEOMETRY, ExportKind.STABLE_SITE)) {
                val available = when (agg) { ExportKind.TOPOLOGY -> i.topology != null; ExportKind.GEOMETRY -> i.geometry != null; else -> i.stable != null }
                if (!available) continue
                val present = aggregateCells(i, agg).first[k]
                val field = when (agg) { ExportKind.TOPOLOGY -> CrossField.HANDOVERS; ExportKind.GEOMETRY -> CrossField.FAMILIARITY; else -> CrossField.MAX_SERVING_DAYS }
                out += finding(CrossCheck.FORENSIC_AGGREGATES, CrossOutcome.COMPLEMENTARY, if (present != null) CrossReason.PRESENT_IN_AGGREGATE else CrossReason.ABSENT_FROM_AGGREGATE,
                    ExportKind.FORENSIC_CASE, agg, listOf(file, aggregateFile(i, agg)), listOf(code, k), case.start, case.end, null, 1,
                    listOf(Compared(field, null, present?.first, entity = k)), listOfNotNull(present?.second), Derivation.SAME_COLLECTOR, caseEpisodes(c, importId))
            }
        }
        return out
    }
}
