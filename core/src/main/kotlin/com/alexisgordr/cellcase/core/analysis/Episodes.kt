package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.model.CollectorVerdict
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.GeometryData
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.ServiceStateEvent
import com.alexisgordr.cellcase.core.model.StableSiteData
import com.alexisgordr.cellcase.core.model.TopologyData
import com.alexisgordr.cellcase.core.time.TimeResolution
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * Versión de las reglas de episodios. Cambios respecto a `episodes/1` en docs/episode-rules.md §6.
 */
const val EPISODE_RULES_VERSION = "episodes/6"

/** Origen de una señal. Historial, caso forense y Stable-Site son valoraciones de ICdetector. */
enum class SignalSource { HISTORY_COLLECTOR, FORENSIC_CASE, SERVICE_STATE, STABLE_SITE_SHADOW }

/**
 * Familia de evidencia. Las fuentes de una misma familia derivan del mismo colector y NO son pruebas
 * independientes entre sí (D-035): solo cuenta como corroboración una familia distinta.
 */
enum class EvidenceFamily { COLLECTOR, PHONE_STATE }

val SignalSource.family: EvidenceFamily get() = if (this == SignalSource.SERVICE_STATE) EvidenceFamily.PHONE_STATE else EvidenceFamily.COLLECTOR

/** Una señal temporal registrada en una fuente. No es una conclusión. */
data class Signal(
    val source: SignalSource,
    val start: Instant,
    val end: Instant,
    val identity: CellIdentity?,
    /** Texto original (motivo de ICdetector, estado de servicio, código de caso…). */
    val text: String,
    val subthreshold: Boolean,
    val refs: List<Provenance>,
    val row: HistoryObservation? = null,
    val reasons: List<Reason> = emptyList(),
    /** Solo casos forenses: `captureOrigin` (ALARM / TRUST_CONTRADICTION…) y `confirmed`. */
    val forensicOrigin: String? = null,
    val forensicConfirmed: Boolean? = null
) {
    /** Un caso forense es alarma solo si su origen es ALARM; abrir un caso de observación no es una alarma. */
    val isAlarm: Boolean get() = (source == SignalSource.HISTORY_COLLECTOR && !subthreshold) || (source == SignalSource.FORENSIC_CASE && forensicOrigin == "ALARM")
    /** Calculado una vez: la agrupación lo consulta para cada par de señales. */
    val kinds: Set<PatternKind> by lazy(LazyThreadSafetyMode.NONE) { when (source) {
        SignalSource.HISTORY_COLLECTOR -> reasons.map { it.kind }.toSet().ifEmpty { setOf(PatternKind.OTHER) }
        SignalSource.FORENSIC_CASE -> setOf(PatternKind.FORENSIC_CASE)
        SignalSource.STABLE_SITE_SHADOW -> setOf(PatternKind.STABLE_SITE_NOVELTY)
        SignalSource.SERVICE_STATE -> setOf(PatternKind.SERVICE_DEGRADED)
    } }
}

enum class Level { HIGH, LOW }
enum class Quality { GOOD, LIMITED }

/** Tres niveles (D-035). No es una probabilidad. */
enum class Priority { PRIORITY, REVIEW, CONTEXT }

/** Resultado de contrastar lo que dice ICdetector con las mediciones del historial (comprobación de CellCase). */
/**
 * Resultado de la comprobación del patrón (D-039). «No evaluable» NO equivale a «no contradictorio».
 * PARTIAL: se ve solo una parte de lo descrito (p. ej. un cambio único de PCI, no una alternancia).
 */
enum class MeasureStatus { CONSISTENT, PARTIAL, NOT_VISIBLE, NOT_EVALUABLE }

/** Disponibilidad de las mediciones que necesita la comprobación (separada del resultado y de la calidad de los datos). */
enum class MeasurementAvailability { AVAILABLE, MISSING, CONFLICTING, NO_CHECK_IMPLEMENTED }

/** Secuencia de PCI en una misma identidad completa y portadora (D-039). */
enum class PciPattern { NO_CHANGE, SINGLE_CHANGE, RETURN, REPEATED_ALTERNATION }

data class Measurement(val kind: PatternKind, val status: MeasureStatus, val values: Map<String, Any> = emptyMap()) {
    val availability: MeasurementAvailability get() = when {
        kind !in EpisodeEngine.MEASURABLE -> MeasurementAvailability.NO_CHECK_IMPLEMENTED
        status == MeasureStatus.NOT_EVALUABLE && values["conflictBlocks"] == true -> MeasurementAvailability.CONFLICTING
        status == MeasureStatus.NOT_EVALUABLE -> MeasurementAvailability.MISSING
        else -> MeasurementAvailability.AVAILABLE
    }
}

/** Comprobaciones propias de CellCase (D-030). */
enum class CheckCode {
    // apoyan la revisión
    RADIO_DEGRADED_IN_WINDOW,
    // matizan
    RADIO_NO_DEGRADED_RECORDED, IDENTITY_ESTABLISHED, TOPOLOGY_TRUSTED, GEOMETRY_KNOWN,
    // contexto (no cambian la prioridad)
    VERIFICATION_STATUS, IDENTITY_RARE, TECH_CHANGE_OBSERVED,
    // no permiten evaluar
    NO_RADIO_DATA, NO_FORENSIC, NO_GNSS, TA_NOT_USABLE, NO_TOPOLOGY, AMBIGUOUS_TIMES_EXCLUDED
}

data class Check(val code: CheckCode, val values: Map<String, Any> = emptyMap())

/** Criterio usado para fijar la prioridad; el informe los enumera para justificarla. */
enum class CriterionCode {
    COLLECTOR_ALARM, FORENSIC_CASE_OPENED, FORENSIC_ALARM_CASE, FORENSIC_OBSERVATION_CASE, SUBTHRESHOLD_ONLY,
    MEASURED_CONSISTENT, MEASURED_PARTIAL, MEASURED_NOT_VISIBLE, NOT_MEASURABLE, MEASUREMENTS_MISSING, MEASUREMENTS_CONFLICTING, NO_CHECK_IMPLEMENTED,
    RADIO_COINCIDENCE_CONTEXT,
    RECURRENT, NOT_RECURRENT, SUSTAINED, SINGLE_OBSERVATION, BRIEF_REPEAT,
    INDEPENDENT_CORROBORATION, DEPENDENT_SOURCES_ONLY, QUALITY_LIMITED
}

data class Criterion(val code: CriterionCode, val values: Map<String, Any> = emptyMap())

/** Hechos sobre una identidad celular en todo el expediente. */
data class IdentityFacts(
    val key: String,
    val identity: CellIdentity,
    val historyRows: Int,
    val historyDays: Int,
    val firstSeen: HistoryObservation?,
    val lastSeen: HistoryObservation?,
    /** Estados de verificación en bases públicas (contexto; no cambian la prioridad). */
    val verification: Map<String, Int>,
    val topologyHandovers: Int?,
    val topologyTrusted: Int?,
    /** Máximo de días como servidora en UNA clave de sitio (no se suman claves solapadas). */
    val stableSiteMaxServingDays: Int?,
    val stableSiteKeys: Int,
    val geometryFamiliarity: String?
)

data class Episode(
    val number: Int,
    /** Grupo de episodios próximos en el tiempo (G1, G2…). Proximidad no implica causa común. */
    val group: Int,
    val start: Instant,
    val end: Instant,
    val signals: List<Signal>,
    val identities: List<IdentityFacts>,
    val radios: Set<RadioTech>,
    val before: List<HistoryObservation>,
    val during: List<HistoryObservation>,
    val after: List<HistoryObservation>,
    val serviceEvents: List<ServiceStateEvent>,
    val forensicCaseIds: List<String>,
    val patterns: Set<PatternKind>,
    val mainPattern: PatternKind,
    /** Momentos de observación distintos (señales a ≤ 60 s se cuentan una vez: varias representaciones del mismo hecho). */
    val moments: Int,
    /** Con un solo momento la duración es desconocida; con varios, solo se conoce el intervalo entre el primero y el último. */
    val durationKnown: Boolean,
    /** Persistencia observada DENTRO del episodio. */
    val persistence: Level,
    /** Días distintos en los que la misma celda tiene el mismo patrón (recurrencia ENTRE días). */
    val recurrenceDays: Int,
    val recurrenceEpisodes: List<Int>,
    val families: Set<EvidenceFamily>,
    val measurement: Measurement,
    val quality: Quality,
    val priority: Priority,
    val criteria: List<Criterion>,
    val supports: List<Check>,
    val contradicts: List<Check>,
    val context: List<Check>,
    val notEvaluable: List<Check>,
    /** Relevancia para la pregunta de suplantación (§7), calculada con los mismos hallazgos. Distinta de [priority]. */
    val relevance: EpisodeRelevance = EpisodeRelevance(SpoofingRelevance.NO_INDICATION, emptyList())
) {
    val duration: Duration get() = Duration.between(start, end)
    val sources: Set<SignalSource> get() = signals.map { it.source }.toSet()
    val collectorSignals: List<Signal> get() = signals.filter { it.source != SignalSource.SERVICE_STATE }
    val onlySubthreshold: Boolean get() = signals.none { it.isAlarm } &&
        signals.filter { it.source == SignalSource.HISTORY_COLLECTOR }.all { it.subthreshold }
    val hasAlarm: Boolean get() = signals.any { it.isAlarm }
}

data class EpisodeGroup(val number: Int, val episodes: List<Int>, val start: Instant, val end: Instant)

data class EpisodeSet(
    val episodes: List<Episode>,
    val groups: List<EpisodeGroup>,
    /** Observaciones agrupadas por tipo y relevancia (ocho episodios de un mismo patrón no son ocho amenazas). */
    val observationGroups: List<ObservationGroup>,
    /** Estado del expediente para la pregunta de suplantación. */
    val overall: SpoofingRelevance,
    /** Estados degradados de Radio sin ninguna señal del colector cercana (no forman episodio). */
    val isolatedServiceSignals: List<Signal>,
    /** Filas del colector sin instante único (hora ambigua o sin zona): no se agrupan. */
    val unplacedCollectorRows: List<HistoryObservation>
) {
    fun group(n: Int) = groups.firstOrNull { it.number == n }
}

/** Agrupación de señales en episodios (docs/episode-rules.md, [EPISODE_RULES_VERSION]). */
object EpisodeEngine {
    val SAME_IDENTITY_GAP: Duration = Duration.ofMinutes(30)
    val GROUP_GAP: Duration = Duration.ofMinutes(15)
    val SERVICE_LINK: Duration = Duration.ofMinutes(5)
    val CONTEXT: Duration = Duration.ofMinutes(30)
    val SAME_MOMENT: Duration = Duration.ofSeconds(60)
    val SUSTAINED_SPAN: Duration = Duration.ofMinutes(10)
    const val SUSTAINED_MOMENTS = 3
    val PREVIOUS_ROW_MAX: Duration = Duration.ofMinutes(10)
    const val BASELINE_MIN_ROWS = 5
    const val POWER_DIFF_DB = 10

    fun build(
        rows: List<HistoryObservation>,
        events: List<ServiceStateEvent>,
        forensic: List<Pair<String, ForensicCaseData>>,
        topology: TopologyData?,
        geometry: GeometryData?,
        stable: StableSiteData?,
        /** Zona para fechas locales de los grupos de observaciones. */
        zone: java.time.ZoneId = java.time.ZoneOffset.UTC
    ): EpisodeSet {
        val sortedRows = rows.filter { it.time.instantOrNull != null }.sortedWith(compareBy<HistoryObservation> { it.time.instantOrNull }.thenBy { it.provenance.startLine })
        val index = RowIndex(sortedRows)

        // ── señales ─────────────────────────────────────────────
        val collectorRows = rows.filter { it.collectorVerdict == CollectorVerdict.SUBTHRESHOLD || it.collectorVerdict == CollectorVerdict.REASON_WITHOUT_PREFIX }
        val unplaced = collectorRows.filter { it.time.instantOrNull == null }
        val signals = ArrayList<Signal>()
        collectorRows.filter { it.time.instantOrNull != null }.forEach { o ->
            signals += Signal(SignalSource.HISTORY_COLLECTOR, o.time.instantOrNull!!, o.time.instantOrNull!!, o.identity, o.failedHeuristics.orEmpty(),
                o.collectorVerdict == CollectorVerdict.SUBTHRESHOLD, listOf(o.provenance), o, ReasonParser.parse(o.failedHeuristics))
        }
        forensic.forEach { (_, c) ->
            val s = c.start ?: return@forEach
            signals += Signal(SignalSource.FORENSIC_CASE, s, c.end ?: s, c.meta.cellIdentity, c.meta.caseCode ?: "?", false,
                listOfNotNull(c.samples.firstOrNull()?.provenance, c.samples.lastOrNull()?.provenance),
                forensicOrigin = c.meta.captureOrigin, forensicConfirmed = c.meta.confirmed)
        }
        stable?.episodes?.filter { it.shadowRecorded == true && it.first != null }?.forEach { e ->
            signals += Signal(SignalSource.STABLE_SITE_SHADOW, e.first!!, e.last ?: e.first, e.candidate, e.reason ?: "Stable-Site", false, listOf(e.provenance))
        }
        val timedEvents = events.filter { it.time != null }.sortedBy { it.time }
        val serviceSignals = timedEvents.withIndex().filter { (_, e) -> e.isDegraded || e.searching == true || e.operatorDiffersFromSim }.map { (i, e) ->
            val end = timedEvents.drop(i + 1).firstOrNull { !it.isDegraded && it.searching != true }?.time ?: e.time!!
            Signal(SignalSource.SERVICE_STATE, e.time!!, end, null, listOfNotNull(e.state, if (e.searching == true) "searching" else null,
                if (e.operatorDiffersFromSim) "OPERATOR_NOT_SIM" else null).joinToString(" · "), false, listOf(e.provenance))
        }

        // ── agrupación: misma celda y mismo tipo de patrón ──────
        val base = signals.sortedWith(compareBy<Signal> { it.start }.thenBy { it.source.ordinal })
        val parent = IntArray(base.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }
        fun gap(a: Signal, b: Signal): Duration =
            if (!a.start.isAfter(b.end) && !b.start.isAfter(a.end)) Duration.ZERO
            else if (a.end.isBefore(b.start)) Duration.between(a.end, b.start) else Duration.between(b.end, a.start)
        val representations = setOf(PatternKind.FORENSIC_CASE, PatternKind.STABLE_SITE_NOVELTY)
        for (i in base.indices) for (j in i + 1 until base.size) {
            val a = base[i]; val b = base[j]
            // `base` está ordenado por inicio: si b empieza más de 30 min después del final de a, las siguientes también.
            if (Duration.between(a.end, b.start) > SAME_IDENTITY_GAP) break
            val g = gap(a, b)
            if (g > SAME_IDENTITY_GAP) continue
            val ka = a.identity?.joinKey; val kb = b.identity?.joinKey
            val linked = when {
                // R1: misma identidad completa, mismo tipo de patrón (o un caso forense/Stable-Site sobre esa celda), ≤ 30 min
                ka != null && ka == kb && (a.kinds.intersect(b.kinds).isNotEmpty() || a.kinds.any { it in representations } || b.kinds.any { it in representations }) -> true
                // R3: un caso forense sin identidad enlaza lo que se solapa con su ventana (±2 min)
                (a.source == SignalSource.FORENSIC_CASE && ka == null || b.source == SignalSource.FORENSIC_CASE && kb == null) && g <= Duration.ofMinutes(2) -> true
                else -> false
            }
            if (linked) union(i, j)
        }
        val clusters = base.indices.groupBy { find(it) }.values.map { idx -> idx.map { base[it] }.sortedBy { it.start } }.sortedBy { c -> c.first().start }

        // R4: Radio se une a los episodios cuya ventana ±5 min la contiene; si no, queda aislada.
        val serviceFor = HashMap<Int, MutableList<Signal>>()
        val isolated = ArrayList<Signal>()
        for (s in serviceSignals) {
            var attached = false
            clusters.forEachIndexed { i, c ->
                val st = c.first().start.minus(SERVICE_LINK); val en = c.maxOf { it.end }.plus(SERVICE_LINK)
                if (!s.start.isAfter(en) && !s.end.isBefore(st)) { serviceFor.getOrPut(i) { ArrayList() } += s; attached = true }
            }
            if (!attached) isolated += s
        }

        // R5: grupos de episodios próximos (≤ 15 min entre uno y el siguiente). No se fusionan.
        val groupOf = IntArray(clusters.size)
        var gNo = 0; var gEnd: Instant? = null
        clusters.forEachIndexed { i, c ->
            val st = c.first().start; val en = c.maxOf { it.end }
            if (gEnd == null || Duration.between(gEnd, st) > GROUP_GAP) gNo++
            groupOf[i] = gNo
            gEnd = if (gEnd == null || en.isAfter(gEnd)) en else gEnd
        }

        // Recurrencia entre días: (celda, tipo de patrón) → fechas locales con señales del colector.
        val recurrence = HashMap<Pair<String, PatternKind>, MutableSet<LocalDate>>()
        collectorRows.forEach { o ->
            val k = o.identity.joinKey ?: return@forEach
            val d = o.time.local?.toLocalDate() ?: return@forEach
            ReasonParser.parse(o.failedHeuristics).map { it.kind }.distinct().forEach { recurrence.getOrPut(k to it) { HashSet() } += d }
        }

        val idFacts = HashMap<String, IdentityFacts>()
        fun facts(id: CellIdentity): IdentityFacts? {
            val key = id.joinKey ?: return null
            return idFacts.getOrPut(key) { identityFacts(key, id, rows, topology, geometry, stable) }
        }

        val drafts = clusters.mapIndexed { i, c ->
            buildEpisode(i + 1, groupOf[i], c, serviceFor[i].orEmpty(), index, timedEvents, forensic, topology, recurrence, ::facts)
        }
        // Episodios de la misma celda y patrón en otros días (se citan; no se fusionan).
        // Fecha en la MISMA zona que la recurrencia (fecha local de las filas), no en UTC.
        fun day(t: Instant) = t.atZone(zone).toLocalDate()
        val byPatternAndCell = HashMap<Pair<PatternKind, String>, MutableList<Episode>>()
        drafts.forEach { d -> d.identities.map { it.key }.distinct().forEach { k -> byPatternAndCell.getOrPut(d.mainPattern to k) { ArrayList() } += d } }
        val episodes = drafts.map { e ->
            val others = e.identities.map { it.key }.distinct().flatMap { k -> byPatternAndCell[e.mainPattern to k].orEmpty() }
                .filter { o -> o.number != e.number && day(o.start) != day(e.start) }.map { it.number }.distinct().sorted()
            e.copy(recurrenceEpisodes = others).let { it.copy(relevance = RelevanceRules.relevance(it)) }
        }
        val groups = episodes.groupBy { it.group }.map { (g, l) -> EpisodeGroup(g, l.map { it.number }, l.minOf { it.start }, l.maxOf { it.end }) }.sortedBy { it.number }
        return EpisodeSet(episodes, groups, RelevanceRules.groups(episodes, zone), RelevanceRules.overall(episodes, unplaced.isNotEmpty()), isolated, unplaced)
    }

    private fun buildEpisode(
        number: Int, group: Int, g: List<Signal>, service: List<Signal>, index: RowIndex, timedEvents: List<ServiceStateEvent>,
        forensic: List<Pair<String, ForensicCaseData>>, topology: TopologyData?,
        recurrence: Map<Pair<String, PatternKind>, Set<LocalDate>>, facts: (CellIdentity) -> IdentityFacts?
    ): Episode {
        val start = g.first().start; val end = g.maxOf { it.end }
        val all = (g + service).sortedBy { it.start }
        val ids = g.mapNotNull { it.identity }.filter { it.joinKey != null }.distinctBy { it.joinKey }
        val idf = ids.mapNotNull { facts(it) }
        val winStart = start.minus(CONTEXT); val winEnd = end.plus(CONTEXT)
        val inWin = index.between(winStart, winEnd)
        val before = inWin.filter { it.time.instantOrNull!!.isBefore(start) }
        val after = inWin.filter { it.time.instantOrNull!!.isAfter(end) }
        val during = inWin.filter { val t = it.time.instantOrNull!!; !t.isBefore(start) && !t.isAfter(end) }
        val evs = timedEvents.filter { !it.time!!.isBefore(winStart) && !it.time.isAfter(winEnd) }
        // Un caso forense con identidad solo cuenta si es de una celda del episodio; sin identidad se enlaza por la
        // ventana (R3), pero entonces no se sabe de qué celda es y la calidad queda limitada.
        val idKeys = ids.mapNotNull { it.joinKey }.toSet()
        val forensicInWindow = forensic.filter { (_, c) -> c.start != null && !c.start!!.isAfter(winEnd) && !(c.end ?: c.start!!).isBefore(winStart) }
            .filter { (_, c) -> c.meta.cellIdentity?.joinKey.let { k -> k == null || k in idKeys } }
        val forensicWithoutIdentity = forensicInWindow.any { (_, c) -> c.meta.cellIdentity?.joinKey == null }
        val forensicIds = forensicInWindow.map { it.first }
        val radios = (ids.map { it.radio } + during.map { it.identity.radio }).filter { it != RadioTech.UNKNOWN }.toSet()

        // patrón principal: el más frecuente entre las señales del colector (las representaciones no cuentan si hay otro)
        val kindCounts = g.flatMap { it.kinds }.groupingBy { it }.eachCount()
        val specific = kindCounts.filterKeys { it !in setOf(PatternKind.FORENSIC_CASE, PatternKind.STABLE_SITE_NOVELTY) }
        val mainPattern = (specific.ifEmpty { kindCounts }).maxByOrNull { it.value }?.key ?: PatternKind.OTHER

        // momentos distintos (varias representaciones del mismo hecho a ≤ 60 s cuentan una vez)
        // Un momento empieza en su primera señal; no se encadena (tres señales a 50 s seguidas son dos momentos si la última dista > 60 s de la primera).
        var moments = 0; var anchor: Instant? = null
        for (s in g.sortedBy { it.start }) { if (anchor == null || Duration.between(anchor, s.start) > SAME_MOMENT) { moments++; anchor = s.start } }
        // Duración conocida: varios momentos o una señal que ya es un intervalo (ventana de un caso forense).
        val durationKnown = moments > 1 || g.any { it.end.isAfter(it.start) }
        val persistence = if (moments >= SUSTAINED_MOMENTS && Duration.between(start, end) >= SUSTAINED_SPAN) Level.HIGH else Level.LOW
        val recurrenceDays = idf.maxOfOrNull { f -> recurrence[f.key to mainPattern]?.size ?: 0 } ?: 0

        val signalRows = g.mapNotNull { it.row }
        val measurement = measure(mainPattern, g, signalRows, index, start, end)

        val supports = ArrayList<Check>(); val contradicts = ArrayList<Check>(); val context = ArrayList<Check>(); val notEval = ArrayList<Check>()
        for (f in idf) {
            if (f.verification.isNotEmpty()) context += Check(CheckCode.VERIFICATION_STATUS, mapOf("key" to f.key, "counts" to f.verification))
            if (f.historyRows <= 2 || f.historyDays <= 1) context += Check(CheckCode.IDENTITY_RARE, mapOf("key" to f.key, "rows" to f.historyRows, "days" to f.historyDays))
            if (f.historyDays >= 3 || (f.stableSiteMaxServingDays ?: 0) >= 3)
                contradicts += Check(CheckCode.IDENTITY_ESTABLISHED, mapOf("key" to f.key, "days" to f.historyDays, "siteDays" to (f.stableSiteMaxServingDays ?: 0)))
            if ((f.topologyTrusted ?: 0) > 0) contradicts += Check(CheckCode.TOPOLOGY_TRUSTED, mapOf("key" to f.key, "trusted" to f.topologyTrusted!!, "total" to (f.topologyHandovers ?: 0)))
            if (f.geometryFamiliarity == "KNOWN_ON_ROUTE") contradicts += Check(CheckCode.GEOMETRY_KNOWN, mapOf("key" to f.key))
        }
        (measurement.values["techChanges"] as? List<*>)?.takeIf { it.isNotEmpty() }?.let { context += Check(CheckCode.TECH_CHANGE_OBSERVED, mapOf("changes" to it)) }
        // Radio: solo se dice lo que hay en los datos; su continuidad no está demostrada.
        val radioBefore = timedEvents.any { !it.time!!.isAfter(start) }
        if (service.isNotEmpty()) supports += Check(CheckCode.RADIO_DEGRADED_IN_WINDOW, mapOf("count" to service.size, "states" to service.map { it.text }.distinct().joinToString()))
        else if (!radioBefore) notEval += Check(CheckCode.NO_RADIO_DATA)
        else contradicts += Check(CheckCode.RADIO_NO_DEGRADED_RECORDED, mapOf("count" to evs.size))
        if (forensicIds.isEmpty() && (mainPattern == PatternKind.RF_IDENTITY || g.any { it.source == SignalSource.HISTORY_COLLECTOR && !it.subthreshold }))
            notEval += Check(CheckCode.NO_FORENSIC)
        if (mainPattern == PatternKind.TIMING_ADVANCE) {
            val units = signalRows.mapNotNull { it.taUnit }.toSet()
            if (units.none { it == "LTE_INDEX" || it == "GSM_INDEX" }) notEval += Check(CheckCode.TA_NOT_USABLE, mapOf("units" to units.joinToString()))
        }
        if (mainPattern == PatternKind.TRANSITION && topology == null) notEval += Check(CheckCode.NO_TOPOLOGY)
        if (mainPattern == PatternKind.GEOGRAPHY && (signalRows + during).none { it.lat != null }) notEval += Check(CheckCode.NO_GNSS)

        // ── prioridad (D-035, D-039): comportamiento, recurrencia, comprobación y calidad, por separado ──
        val families = all.map { it.source.family }.toSet()
        val historyAlarm = g.any { it.source == SignalSource.HISTORY_COLLECTOR && !it.subthreshold }
        val forensicSignals = g.filter { it.source == SignalSource.FORENSIC_CASE }
        val forensicAlarm = forensicSignals.any { it.forensicOrigin == "ALARM" }
        val alarm = historyAlarm || forensicAlarm
        // Radio es contexto adicional: no es evidencia independiente de ataque ni eleva la prioridad.
        val radioCoincidence = EvidenceFamily.PHONE_STATE in families && EvidenceFamily.COLLECTOR in families
        val sustained = persistence == Level.HIGH
        val recurrent = recurrenceDays >= 2
        // Calidad de los DATOS: identidades completas de todas las señales. Es independiente de que exista una
        // comprobación del patrón (disponibilidad) y de su resultado (measurement.status).
        val identitiesComplete = ids.isNotEmpty() && ids.size == g.mapNotNull { it.identity }.distinctBy { it.display }.size
        val quality = if (identitiesComplete && !forensicWithoutIdentity) Quality.GOOD else Quality.LIMITED
        val crit = ArrayList<Criterion>()
        if (historyAlarm) crit += Criterion(CriterionCode.COLLECTOR_ALARM)
        forensicSignals.forEach { f ->
            crit += Criterion(if (f.forensicOrigin == "ALARM") CriterionCode.FORENSIC_ALARM_CASE else CriterionCode.FORENSIC_OBSERVATION_CASE,
                mapOf("code" to f.text, "origin" to (f.forensicOrigin ?: "?"), "confirmed" to (f.forensicConfirmed?.toString() ?: "?")))
        }
        if (!alarm) crit += Criterion(CriterionCode.SUBTHRESHOLD_ONLY)
        crit += when (measurement.availability) {
            MeasurementAvailability.NO_CHECK_IMPLEMENTED -> Criterion(CriterionCode.NO_CHECK_IMPLEMENTED)
            MeasurementAvailability.MISSING -> Criterion(CriterionCode.MEASUREMENTS_MISSING)
            MeasurementAvailability.CONFLICTING -> Criterion(CriterionCode.MEASUREMENTS_CONFLICTING, mapOf("conflicts" to (measurement.values["conflicts"] ?: 0)))
            MeasurementAvailability.AVAILABLE -> when (measurement.status) {
                MeasureStatus.CONSISTENT -> Criterion(CriterionCode.MEASURED_CONSISTENT)
                MeasureStatus.PARTIAL -> Criterion(CriterionCode.MEASURED_PARTIAL)
                else -> Criterion(CriterionCode.MEASURED_NOT_VISIBLE)
            }
        }
        crit += when {
            sustained -> Criterion(CriterionCode.SUSTAINED, mapOf("moments" to moments, "span" to Duration.between(start, end)))
            moments == 1 -> Criterion(CriterionCode.SINGLE_OBSERVATION)
            else -> Criterion(CriterionCode.BRIEF_REPEAT, mapOf("moments" to moments, "span" to Duration.between(start, end)))
        }
        crit += if (recurrent) Criterion(CriterionCode.RECURRENT, mapOf("days" to recurrenceDays)) else Criterion(CriterionCode.NOT_RECURRENT)
        if (radioCoincidence) crit += Criterion(CriterionCode.RADIO_COINCIDENCE_CONTEXT)
        if (g.map { it.source }.distinct().size > 1) crit += Criterion(CriterionCode.DEPENDENT_SOURCES_ONLY, mapOf("sources" to g.map { it.source }.distinct()))
        if (quality == Quality.LIMITED) crit += Criterion(CriterionCode.QUALITY_LIMITED)

        val measuredOk = measurement.status == MeasureStatus.CONSISTENT
        val priority = when {
            // Prioritaria: alarma de ICdetector (no un caso de observación) + datos completos + comportamiento
            // COMPROBADO en las mediciones (no basta con «no contradictorio») + repetición dentro o entre días.
            alarm && quality == Quality.GOOD && measuredOk && (sustained || recurrent) -> Priority.PRIORITY
            alarm -> Priority.REVIEW
            measuredOk && (recurrent || sustained) -> Priority.REVIEW
            else -> Priority.CONTEXT
        }
        return Episode(number, group, start, end, all, idf, radios, before, during, after, evs, forensicIds,
            g.flatMap { it.kinds }.toSet(), mainPattern, moments, durationKnown, persistence, recurrenceDays, emptyList(), families,
            measurement, quality, priority, crit, supports.distinct(), contradicts.distinct(), context.distinct(), notEval.distinct())
    }

    /** Patrones para los que CellCase tiene una comprobación propia sobre las mediciones. */
    val MEASURABLE: Set<PatternKind> = setOf(PatternKind.BAND_CHANGE, PatternKind.RF_IDENTITY, PatternKind.POWER_DEVIATION)

    /** Contraste de lo que dice ICdetector con las filas del historial (comprobación de CellCase, D-030). */
    fun measure(kind: PatternKind, g: List<Signal>, signalRows: List<HistoryObservation>, sortedRows: List<HistoryObservation>, start: Instant, end: Instant): Measurement =
        measure(kind, g, signalRows, RowIndex(sortedRows), start, end)

    fun measure(kind: PatternKind, g: List<Signal>, signalRows: List<HistoryObservation>, index: RowIndex, start: Instant, end: Instant): Measurement {
        // Fila anterior (la última antes de t, a ≤ 10 min): búsqueda binaria en lugar de recorrer todo el historial.
        fun previousOf(o: HistoryObservation): HistoryObservation? {
            val t = o.time.instantOrNull ?: return null
            val p = index.lastBefore(t) ?: return null
            return if (Duration.between(p.time.instantOrNull!!, t) <= PREVIOUS_ROW_MAX) p else null
        }
        val techChanges = signalRows.mapNotNull { o -> previousOf(o)?.let { p -> if (p.identity.radio != o.identity.radio && p.identity.radio != RadioTech.UNKNOWN && o.identity.radio != RadioTech.UNKNOWN) "${p.identity.radio.name}→${o.identity.radio.name}" else null } }
        return when (kind) {
            PatternKind.BAND_CHANGE -> {
                val seen = signalRows.map { o ->
                    val p = previousOf(o)
                    Triple(o, p, if (p == null) null else LteBands.bandOf(p) to LteBands.bandOf(o))
                }
                val observed = seen.filter { (_, p, b) -> p != null && b != null && b.first != null && b.second != null && b.first != b.second }
                val comparable = seen.filter { (_, p, b) -> p != null && b?.first != null && b.second != null }
                val status = when {
                    observed.isNotEmpty() -> MeasureStatus.CONSISTENT
                    comparable.isNotEmpty() -> MeasureStatus.NOT_VISIBLE
                    else -> MeasureStatus.NOT_EVALUABLE
                }
                Measurement(kind, status, mapOf(
                    "transitions" to observed.map { (o, p, b) -> mapOf("from" to b!!.first!!, "to" to b.second!!, "fromKey" to (p!!.identity.joinKey ?: "?"), "toKey" to (o.identity.joinKey ?: "?"),
                        "at" to o.time.instantOrNull!!, "dbmBefore" to (p.dbm ?: Int.MIN_VALUE), "dbmAfter" to (o.dbm ?: Int.MIN_VALUE)) },
                    "compared" to comparable.size, "signals" to signalRows.size, "techChanges" to techChanges))
            }
            PatternKind.RF_IDENTITY -> {
                // Misma celda = identidad COMPLETA (MCC-MNC-TAC-CID-tecnología) y misma portadora (EARFCN). Un CID igual
                // bajo otra área, tecnología o portadora es otra serie y no se mezcla.
                val keys = signalRows.mapNotNull { it.identity.joinKey }.toSet()
                val win = index.between(start.minus(CONTEXT), end.plus(CONTEXT)).filter { r -> r.identity.joinKey in keys && r.pci != null && r.arfcn != null }
                val series = win.groupBy { it.identity.joinKey!! to it.arfcn!! }.map { (k, l) -> pciSeries(k.first, k.second, l) }
                val best = series.maxByOrNull { it.pattern.ordinal }
                // Un conflicto temporal solo bloquea si, sin él, ninguna serie demuestra un retorno.
                val blocked = series.isNotEmpty() && series.none { it.pattern.ordinal >= PciPattern.RETURN.ordinal } && series.any { it.blocked }
                Measurement(kind, when {
                    best == null || blocked -> MeasureStatus.NOT_EVALUABLE
                    else -> when (best.pattern) {
                    PciPattern.REPEATED_ALTERNATION, PciPattern.RETURN -> MeasureStatus.CONSISTENT
                    PciPattern.SINGLE_CHANGE -> MeasureStatus.PARTIAL
                    PciPattern.NO_CHANGE -> MeasureStatus.NOT_VISIBLE
                    }
                }, mapOf("series" to series, "pattern" to (best?.pattern ?: PciPattern.NO_CHANGE), "rows" to win.size, "techChanges" to techChanges,
                    "conflicts" to series.sumOf { it.conflicts.size }, "conflictBlocks" to blocked))
            }
            PatternKind.POWER_DEVIATION -> {
                // Línea base por celda (fuera de la ventana del episodio): la misma para todas sus filas, se calcula una vez.
                val baseByKey = HashMap<String, List<Int>>()
                val res = signalRows.mapNotNull { o ->
                    val k = o.identity.joinKey ?: return@mapNotNull null
                    val dbm = o.dbm ?: return@mapNotNull null
                    val base = baseByKey.getOrPut(k) { index.dbmOutside(k, start.minus(CONTEXT), end.plus(CONTEXT)) }
                    if (base.size < BASELINE_MIN_ROWS) null else Triple(dbm, base[base.size / 2], base.size)
                }
                val reported = g.flatMap { it.reasons }.firstOrNull { it.kind == PatternKind.POWER_DEVIATION }?.db
                val consistent = res.any { (d, m, _) -> kotlin.math.abs(d - m) >= POWER_DIFF_DB && (reported == null || (d - m) * reported > 0) }
                Measurement(kind, when {
                    res.isEmpty() -> MeasureStatus.NOT_EVALUABLE
                    consistent -> MeasureStatus.CONSISTENT
                    else -> MeasureStatus.NOT_VISIBLE
                }, mapOf("measures" to res.map { (d, m, n) -> mapOf("dbm" to d, "median" to m, "n" to n, "diff" to d - m) }, "reported" to (reported ?: Int.MIN_VALUE), "techChanges" to techChanges))
            }
            else -> Measurement(kind, MeasureStatus.NOT_EVALUABLE, mapOf("techChanges" to techChanges))
        }
    }

    /** Tramo de una secuencia de PCI: mismo valor seguido, con sus horas y referencias. */
    data class PciRun(val pci: Int, val from: Instant, val to: Instant, val rows: Int, val refs: List<Provenance>)

    /**
     * Conflicto temporal: la misma identidad completa y portadora con PCI distintos a la MISMA hora. La hora de
     * ICdetector tiene resolución de segundos y no hay otro campo temporal fiable, así que no se puede saber en
     * qué orden ocurrieron: no demuestra A→B ni alternancia.
     */
    data class PciConflict(val time: Instant, val pcis: List<Int>, val refs: List<Provenance>)

    /**
     * [coincident]: observaciones con la misma hora y el mismo PCI (contadas una vez, con todas sus procedencias).
     * [blocked]: los conflictos impiden evaluar la secuencia (sin ellos no se demuestra un retorno).
     */
    data class PciSeries(
        val key: String, val arfcn: Int, val runs: List<PciRun>, val pattern: PciPattern, val coincident: Int,
        val conflicts: List<PciConflict> = emptyList(), val blocked: Boolean = false
    )

    /** Orden estable de procedencias que no depende de identificadores internos ni del orden de importación. */
    private val provenanceOrder = compareBy<Provenance>({ it.fileName }, { it.zipEntry ?: "" }, { it.recordIndex })

    /**
     * Secuencia temporal de PCI de una identidad y portadora (D-042). Solo se ordena por la hora registrada; nunca
     * por importación, archivo ni número de línea. En cada instante:
     *  - un solo PCI (aunque venga de varias filas o archivos): un momento, con todas sus procedencias;
     *  - varios PCI: conflicto temporal. No se ordena ni se usa para demostrar cambios.
     * El patrón se calcula con los instantes sin conflicto: A→B cambio único · A→B→A retorno · dos o más retornos
     * alternancia repetida. Si hay conflictos y sin ellos no se llega a un retorno, la secuencia no es evaluable.
     */
    fun pciSeries(key: String, arfcn: Int, rows: List<HistoryObservation>): PciSeries {
        val byInstant = rows.groupBy { it.time.instantOrNull!! }.toSortedMap()
        val conflicts = ArrayList<PciConflict>()
        val moments = ArrayList<Triple<Instant, Int, List<Provenance>>>()
        var coincident = 0
        for ((t, l) in byInstant) {
            val pcis = l.mapNotNull { it.pci }.toSortedSet()
            coincident += l.size - l.map { it.pci }.distinct().size
            if (pcis.size == 1) moments += Triple(t, pcis.first(), l.map { it.provenance }.sortedWith(provenanceOrder))
            else conflicts += PciConflict(t, pcis.toList(), l.map { it.provenance }.sortedWith(provenanceOrder))
        }
        val runs = ArrayList<PciRun>()
        for ((t, pci, refs) in moments) {
            val last = runs.lastOrNull()
            if (last != null && last.pci == pci) runs[runs.size - 1] = last.copy(to = t, rows = last.rows + 1, refs = last.refs + refs)
            else runs += PciRun(pci, t, t, 1, refs)
        }
        val returns = runs.indices.count { i -> i >= 2 && runs.subList(0, i - 1).any { it.pci == runs[i].pci } }
        val pattern = when {
            runs.size <= 1 -> PciPattern.NO_CHANGE
            returns == 0 -> PciPattern.SINGLE_CHANGE
            returns == 1 -> PciPattern.RETURN
            else -> PciPattern.REPEATED_ALTERNATION
        }
        val blocked = conflicts.isNotEmpty() && pattern.ordinal < PciPattern.RETURN.ordinal
        return PciSeries(key, arfcn, runs, pattern, coincident, conflicts, blocked)
    }

    fun identityFacts(key: String, id: CellIdentity, rows: List<HistoryObservation>, topology: TopologyData?, geometry: GeometryData?, stable: StableSiteData?): IdentityFacts {
        val mine = rows.filter { it.identity.joinKey == key }
        val byTime = mine.filter { it.time.local != null }.sortedBy { it.time.local }
        val topoIn = topology?.routes?.filter { it.to.joinKey == key || it.from.joinKey == key }
        val siteCells = stable?.siteCells?.filter { it.identity.joinKey == key }
        return IdentityFacts(
            key, id, mine.size, byTime.map { it.time.local!!.toLocalDate() }.toSet().size, byTime.firstOrNull(), byTime.lastOrNull(),
            mine.mapNotNull { it.verified }.groupingBy { it }.eachCount().toSortedMap(),
            topoIn?.sumOf { it.observations ?: 0 }, topoIn?.sumOf { it.trusted ?: 0 },
            siteCells?.mapNotNull { it.servingDays }?.maxOrNull(), siteCells?.map { it.key }?.distinct()?.size ?: 0,
            geometry?.cells?.firstOrNull { it.identity.joinKey == key }?.familiarity
        )
    }

    /** ¿La hora de esta fila se pudo situar sin ambigüedad? */
    fun placed(o: HistoryObservation) = o.time.resolution is TimeResolution.Exact
}

/**
 * Filas del historial ordenadas por instante, con búsquedas binarias (revisión del 20/10/2026, fallo 8). Sustituye a
 * los recorridos completos del historial que se hacían por episodio y por fila de señal.
 */
class RowIndex(val rows: List<HistoryObservation>) {
    private val times: Array<Instant> = Array(rows.size) { rows[it].time.instantOrNull!! }
    /** Por celda: (instante, dBm) de sus filas con dBm, en orden. */
    private val dbmByKey: Map<String, Pair<Array<Instant>, IntArray>> by lazy {
        rows.filter { it.identity.joinKey != null && it.dbm != null }.groupBy { it.identity.joinKey!! }
            .mapValues { (_, l) -> Array(l.size) { l[it].time.instantOrNull!! } to IntArray(l.size) { l[it].dbm!! } }
    }

    /** Primer índice con instante ≥ t (o > t si [strict]). */
    private fun lowerBound(a: Array<Instant>, t: Instant, strict: Boolean): Int {
        var lo = 0; var hi = a.size
        while (lo < hi) { val m = (lo + hi) ushr 1; val c = a[m].compareTo(t); if (c < 0 || (strict && c == 0)) lo = m + 1 else hi = m }
        return lo
    }

    /** Filas con instante en [from, to], en orden. */
    fun between(from: Instant, to: Instant): List<HistoryObservation> {
        val a = lowerBound(times, from, false); val b = lowerBound(times, to, true)
        return if (a >= b) emptyList() else rows.subList(a, b)
    }

    /** Última fila con instante estrictamente anterior a [t] (con empates de instante, la última en el orden). */
    fun lastBefore(t: Instant): HistoryObservation? = lowerBound(times, t, false).let { if (it == 0) null else rows[it - 1] }

    /** dBm de la celda [key] FUERA de [from, to], ordenados. */
    fun dbmOutside(key: String, from: Instant, to: Instant): List<Int> {
        val (t, d) = dbmByKey[key] ?: return emptyList()
        val a = lowerBound(t, from, false); val b = lowerBound(t, to, true)
        val out = IntArray(a + (d.size - b)); System.arraycopy(d, 0, out, 0, a); System.arraycopy(d, b, out, a, d.size - b)
        out.sort(); return out.asList()
    }
}
