package com.alexisgordr.cellcase.core.model

import com.alexisgordr.cellcase.core.time.RecordedTime
import java.time.Instant
import java.time.LocalDate

// ───────────────────────── Radio (estado de servicio) ─────────────────────────

/** Un cambio de estado de servicio (export-formats.md §2). Hora UTC exacta. */
data class ServiceStateEvent(
    val provenance: Provenance,
    val time: Instant?,
    val state: String?,
    val dataRegistered: Boolean?,
    val voiceRegistered: Boolean?,
    val searching: Boolean?,
    val roaming: Boolean?,
    val networkOperator: String?,
    val networkName: String?,
    val simOperator: String?,
    val manualSelection: Boolean?,
    val channel: Int?,
    val bandwidthsKhz: List<Int>,
    val dataNetwork: String?,
    val voiceNetwork: String?,
    val source: String?
) {
    /** Estado de servicio degradado según el propio estado exportado (no es una conclusión). */
    val isDegraded: Boolean get() = state == "OUT_OF_SERVICE" || state == "EMERGENCY_ONLY"
    val operatorDiffersFromSim: Boolean
        get() = roaming == false && !networkOperator.isNullOrBlank() && !simOperator.isNullOrBlank() && networkOperator != simOperator
}

// ───────────────────────── Caso forense ─────────────────────────

data class ForensicCaseMeta(
    val schemaVersion: Long?,
    val caseCode: String?,
    val state: String?,
    val captureOrigin: String?,
    val createdAt: RecordedTime?,
    val updatedAt: RecordedTime?,
    val closedAt: RecordedTime?,
    val cellIdentity: CellIdentity?,
    val highestPhase: Long?,
    val confirmed: Boolean?,
    val sampleCount: Long?,
    val appVersion: String?,
    val device: String?,
    val android: String?,
    val raw: Map<String, Any?>,
    /** 3.0: versión del formato de las muestras guardadas (la de la primera muestra). */
    val sampleSchemaVersion: Long? = null
)

data class ForensicSample(
    val provenance: Provenance,
    val time: Instant?,
    val elapsedMs: Long?,
    val event: String?,
    val phase: Int?,
    val score: Int?,
    val anomalyConfidence: Double?,
    val identity: CellIdentity?,
    val verified: String?,
    val latencyState: String?,
    val lat: Double?,
    val lon: Double?,
    val accuracyM: Double?,
    val reason: String?,
    // 3.0 (columnas nuevas de timeline.csv): fases necesarias, aviso de calidad del dato (H8) y estado de cada regla.
    val requiredPhases: Int? = null,
    val dataQualityReason: String? = null,
    val heuristics: String? = null
)

data class ForensicCell(
    val provenance: Provenance,
    val time: Instant?,
    val role: String?,
    val identity: CellIdentity?,
    val radio: RadioTech,
    val dbm: Int?,
    val pci: Int?,
    val arfcn: Int?,
    val band: Int?,
    val rsrq: Int?,
    val sinr: Int?,
    val ta: Int?,
    val taUnit: String?,
    // 3.0 (columnas nuevas de cells.csv)
    val registered: Boolean? = null,
    val bands: String? = null,
    val csgIndicator: Boolean? = null,
    val secondaryCarriers: String? = null
) {
    /**
     * Vecina de verdad. En 3.0 las portadoras secundarias de la conexión tienen el papel `SECONDARY`; antes
     * salían como `NEIGHBOR` (sin estado guardado no se podían distinguir, export-formats.md §3.3).
     */
    val isNeighbour: Boolean get() = role == "NEIGHBOR"
    /** Papel para comparar versiones de un mismo caso: `SECONDARY` (3.0) y `NEIGHBOR` (2.10.x) son la misma lista. */
    val versionRole: String? get() = if (role == "SECONDARY") "NEIGHBOR" else role
}

/** Confianza local y Stable-Site de una muestra (3.0, `trust_site.csv`). Contexto: se cuenta y se muestra. */
data class ForensicTrustSite(
    val provenance: Provenance,
    val time: Instant?,
    val trustState: String?,
    val trustConfidencePercent: Int?,
    val contradictions: String?,
    val siteKey: String?,
    val siteFeatureState: String?,
    val siteWouldTrigger: Boolean?,
    val siteReason: String?
)

data class ForensicHeuristic(val provenance: Provenance, val time: Instant?, val id: Int?, val name: String?, val status: String?, val explanation: String?)

/**
 * Una observación de `capabilities.json`: qué campos traía un ciclo. [values] conserva cada valor tal como
 * aparece en el JSON (texto canónico: true/false, número o cadena), ordenado por nombre.
 */
data class ForensicCapability(val timeRaw: String?, val time: Instant?, val values: Map<String, String>)

data class ForensicCaseData(
    val meta: ForensicCaseMeta,
    val samples: List<ForensicSample>,
    val cells: List<ForensicCell>,
    val heuristics: List<ForensicHeuristic>,
    /** Contenido de `capabilities.json` (no solo su número). */
    val capabilities: List<ForensicCapability>,
    /**
     * Líneas de `terminal.log` tal cual (hora local sin fecha). 2.10.x: unión sin duplicados; 3.0: ventanas unidas
     * quitando solo el solapamiento, así que dos líneas iguales pueden ser dos eventos.
     */
    val terminal: List<String>,
    /** 3.0: `trust_site.csv` (vacío en exportaciones anteriores). */
    val trustSite: List<ForensicTrustSite> = emptyList()
) {
    val capabilityObservations: Int get() = capabilities.size
    val terminalLines: Int get() = terminal.size
    val start: Instant? get() = samples.mapNotNull { it.time }.minOrNull()
    val end: Instant? get() = samples.mapNotNull { it.time }.maxOrNull()
    /** Grabado por ICdetector 3.1.0 o posterior: sin verificación externa; el `verified` de sus muestras es siempre PENDING. */
    val externalVerificationRetired: Boolean get() = com.alexisgordr.cellcase.core.importing.IcdVersion.atLeast(meta.appVersion, 3, 1, 0)
}

// ───────────────────────── Topología ─────────────────────────

data class TopologyRoute(
    val provenance: Provenance,
    val from: CellIdentity,
    val to: CellIdentity,
    val fromRaw: String,
    val toRaw: String,
    val observations: Int?,
    val trusted: Int?,
    val trustRatio: Double?,
    val lastStatus: String?,
    val lastSeen: Instant?
)

data class TopologyData(
    val routes: List<TopologyRoute>,
    val cellRows: Int,
    val generatedAt: Instant?,
    val schemaVersion: Long?,
    val appVersion: String?,
    val declaredRouteCount: Long?,
    val declaredCellCount: Long?,
    val declaredHandovers: Long?,
    val graphmlEdges: Int?
) {
    /** La exportación de 2.10.5 incluye como máximo 250 rutas (D-014). */
    val possibleTruncation: Boolean get() = routes.size >= LIMIT
    companion object { const val LIMIT = 250 }
}

// ───────────────────────── Geometría ─────────────────────────

data class GeometryCell(val provenance: Provenance, val identity: CellIdentity, val raw: String, val familiarity: String?, val goodEdges: Int?, val relevantTrips: Int?,
                        val incomingTransitions: Int?, val outgoingTransitions: Int?)

data class GeometryEdge(val provenance: Provenance, val from: CellIdentity, val to: CellIdentity, val fromRaw: String, val toRaw: String,
                        val transitions: Int?, val trusted: Int?, val trips: Int?, val pendingCurrentTrip: Boolean?, val projectedTrips: Int?,
                        val firstSeen: Instant?, val lastSeen: Instant?)

data class MobilityTrip(val provenance: Provenance, val id: String?, val start: Instant?, val end: Instant?, val state: String?, val closeReason: String?,
                        val hadMoving: Boolean?, val distinctCells: Int?, val edgeCount: Int?) {
    /** Regla de validez documentada en export-formats.md §5.3: solo explica el dato exportado. */
    val meetsValidityRule: Boolean? get() = if (hadMoving == null || distinctCells == null) null else hadMoving && distinctCells >= 3
}

data class GeometryData(
    val cells: List<GeometryCell>,
    val edges: List<GeometryEdge>,
    val trips: List<MobilityTrip>,
    val exportTime: Instant?,
    val schemaVersion: Long?,
    val appVersion: String?,
    val graphmlEdges: Int?
) {
    val possibleTruncation: Boolean get() = edges.size >= LIMIT || trips.size >= LIMIT
    companion object { const val LIMIT = 1000 }
}

// ───────────────────────── Stable-Site ─────────────────────────

data class StableSite(val provenance: Provenance, val key: String, val featureState: String?, val capability: String?, val maturityReason: String?,
                      val servingObservations: Long?, val servingDays: Int?, val staticDays: Int?, val firstSeen: Instant?, val lastSeen: Instant?)

data class StableSiteCell(val provenance: Provenance, val key: String, val identity: CellIdentity, val raw: String,
                          val servingObservations: Long?, val servingDays: Int?, val neighbourObservations: Long?, val neighbourDays: Int?,
                          val firstServing: Instant?, val lastServing: Instant?)

data class StableSiteMotion(val provenance: Provenance, val key: String, val day: LocalDate?, val state: String?, val accuracyBandM: Int?, val durationS: Long?)

data class StableSiteEpisode(
    val provenance: Provenance, val id: String?, val key: String?, val previous: CellIdentity?, val candidate: CellIdentity?,
    val first: Instant?, val last: Instant?, val closed: Instant?, val shadowRecorded: Boolean?, val reason: String?, val motionState: String?,
    val featureState: String?, val globallyKnown: Boolean?, val knownAtSite: Boolean?, val seenAsNeighbour: Boolean?,
    val servingDays: Int?, val neighbourDays: Int?, val corroborated: Boolean?
)

data class StableSiteData(
    val sites: List<StableSite>,
    val siteCells: List<StableSiteCell>,
    val rfNeighbourRows: Int,
    val motion: List<StableSiteMotion>,
    val episodes: List<StableSiteEpisode>
)
