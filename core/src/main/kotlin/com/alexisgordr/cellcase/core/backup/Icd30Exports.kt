package com.alexisgordr.cellcase.core.backup

import com.alexisgordr.cellcase.core.csv.CsvGuard
import com.alexisgordr.cellcase.core.importing.Hashing

/**
 * Reconstruye, a partir de la base de una copia de seguridad, las exportaciones que habría hecho **ICdetector 3.0.0**
 * en el momento de la copia (docs/backup-format.md §4). Reproduce la lógica de sus exportadores con autorización de
 * su autor (D-052); cada función cita su origen. El resultado se lee después con los mismos lectores que los archivos
 * exportados, así que la copia y las exportaciones dan el mismo análisis.
 *
 * Lo que no viaja en la copia y por tanto no puede reconstruirse:
 * - el teléfono y la versión de Android de la exportación (`ExportDevice`/`ExportAndroid`): se dejan vacíos;
 * - el `versionCode` de la app: se toma de [knownVersionCode] si la versión es conocida, si no 0.
 */
class Icd30Exports(
    private val db: SqliteFile,
    /** `appVersion` del manifiesto (`versionName+commit`), lo que ICdetector escribe en sus exportaciones. */
    private val appVersion: String,
    /** Momento de la copia (`createdAtUtc`): hace de momento de exportación. */
    private val exportAtMs: Long,
    private val device: String = "",
    private val android: String = "",
    private val maxRows: Int = Int.MAX_VALUE,
    /**
     * Bytes máximos del historial CSV reconstruido (el límite de un historial exportado). Se aplica MIENTRAS se leen
     * las tablas y se escribe el CSV, no después (revisión 22/10, A3): las tablas pueden leer hasta [RAW_FACTOR] veces
     * este tope (tienen columnas que no se exportan) y el CSV se corta en cuanto lo pasa.
     */
    private val maxHistoryBytes: Long = Long.MAX_VALUE,
    /**
     * Bytes máximos que se leen de las tablas del resto de partes (Radio, Topología, Geometría, Stable-Site, lista de
     * casos forenses). Es un tope de LECTURA, no de salida: Stable-Site exporta recuentos de tablas que pueden ser mucho
     * mayores que su ZIP, así que no se deriva del límite del ZIP (verificación de A3); la salida se comprueba después.
     */
    private val maxPartReadBytes: Long = Long.MAX_VALUE,
    /** Cancelación: se consulta mientras se leen las tablas, no solo entre partes (A4). */
    private val checkpoint: com.alexisgordr.cellcase.core.importing.Checkpoint = com.alexisgordr.cellcase.core.importing.Checkpoint.NONE
) {
    private val versionCode: Long = knownVersionCode(appVersion)

    class TooManyRows(val table: String, val rows: Int) : Exception("$table: $rows filas")

    /** Una parte pasaría de su límite de bytes: no se construye. [bytes] es lo leído hasta el corte. */
    class PartTooLarge(val table: String, val bytes: Long, val limit: Long) : Exception("$table: más de $limit bytes")

    // Bytes leídos de las tablas de la parte en curso y su tope (las partes se construyen de una en una)
    private var partRead = 0L
    private var partRawLimit = Long.MAX_VALUE
    private var partLimit = Long.MAX_VALUE

    /** [limit]: tope de la salida (el CSV se corta al pasarlo); [rawLimit]: tope de lo leído de las tablas. */
    private inline fun <T> part(limit: Long, rawLimit: Long, block: () -> T): T {
        partRead = 0; partLimit = limit; partRawLimit = rawLimit
        try { return block() } finally { partRead = 0; partLimit = Long.MAX_VALUE; partRawLimit = Long.MAX_VALUE }
    }

    /** Tamaño aproximado de una fila leída: texto por caracteres, BLOB por bytes, números 8. */
    private fun rowBytes(r: SqliteFile.Row): Long = r.values.sumOf { v -> when (v) { null -> 0L; is String -> v.length.toLong(); is ByteArray -> v.size.toLong(); else -> 8L } }

    // ───────────────────────── acceso a tablas ─────────────────────────

    private class Rows(val table: SqliteFile.Table, val rows: List<SqliteFile.Row>) {
        fun col(name: String) = table.indexOf(name)
    }

    private fun load(name: String): Rows? {
        val t = db.table(name) ?: return null
        val out = ArrayList<SqliteFile.Row>()
        for (r in db.rows(t)) {
            out += r
            if (out.size > maxRows) throw TooManyRows(name, out.size)
            partRead += rowBytes(r)
            if (partRead > partRawLimit) throw PartTooLarge(name, partRead, partRawLimit)
            if (out.size and 1023 == 0) checkpoint.check()
        }
        return Rows(t, out)
    }

    private fun SqliteFile.Row.v(rows: Rows, col: String): Any? = this[rows.col(col)]

    fun has(table: String) = db.table(table) != null

    // ───────────────────────── historial (ExportUtils.kt, CellDbHelper.forEachRecord/readRecord) ─────────────────────────

    /**
     * Historial CSV como `ExportUtils.CSV_HEADER`/`csvRow`: 54 columnas de 3.0.0 y, si la tabla `history` tiene las
     * columnas del churn (esquema 26, ICdetector 3.1.0), las 3 de 3.1.0 al final. Se decide por las columnas de la
     * tabla, no por el número de esquema. Null si no hay tabla `history`.
     */
    fun historyCsv(): ByteArray? = part(maxHistoryBytes, if (maxHistoryBytes >= Long.MAX_VALUE / RAW_FACTOR) Long.MAX_VALUE else maxHistoryBytes * RAW_FACTOR) { buildHistoryCsv() }

    private fun buildHistoryCsv(): ByteArray? {
        val h = load("history") ?: return null
        val ev = load("profile_evaluations")
        val byHistory = HashMap<Long, SqliteFile.Row>()
        // LEFT JOIN profile_evaluations e ON e.history_id = h.id (history_id es UNIQUE)
        ev?.rows?.forEach { e -> e.v(ev, "history_id")?.let { byHistory[CursorValue.long(it)] = e } }
        // ORDER BY h.observed_at_ms DESC, h.id DESC (NULL va al final en orden descendente)
        val obsCol = h.col("observed_at_ms")
        val ordered = h.rows.sortedWith(compareByDescending<SqliteFile.Row> { r -> r[obsCol]?.let { sortKey(it) } }.thenByDescending { it.rowid })
        val churn = CHURN_COLUMNS.all { h.col(it) >= 0 }
        val sb = StringBuilder(if (churn) HISTORY_HEADER_31 else HISTORY_HEADER).append('\n')
        for (r in ordered) {
            fun s(c: String) = CursorValue.string(r.v(h, c))
            fun has(c: String) = h.col(c) >= 0 && r.v(h, c) != null
            fun optInt(c: String) = if (has(c)) CursorValue.int(r.v(h, c)) else null
            fun dbl(c: String) = if (has(c)) CursorValue.double(r.v(h, c)) else null
            val ta = optInt("ta")
            val unit = enumOr(s("ta_unit"), TA_UNITS, "UNKNOWN")
            val e = byHistory[r.rowid]
            val level = e?.let { CursorValue.string(it.v(ev!!, "deviation_level")) }
            val p = if (level == null) null else e
            fun ps(c: String) = p?.let { CursorValue.string(it.v(ev!!, c)) }
            fun pi(c: String) = p?.v(ev!!, c)?.let { CursorValue.int(it) }
            val values = listOf(
                s("timestamp"), s("net_type"), s("cid"), s("mnc"), s("tac"), s("mcc"),
                CursorValue.int(r.v(h, "dbm")), enumOr(s("verified"), VERIFIED, "PENDING"), CursorValue.int(r.v(h, "score")), s("failed_heuristics") ?: "",
                dbl("lat"), dbl("lon"), optInt("pci"), optInt("arfcn"), optInt("rsrq"), optInt("sinr"),
                fixed(if (has("threat_prob")) CursorValue.float(r.v(h, "threat_prob")) else 0f, 1),
                dbl("api_lat"), dbl("api_lon"), ta, if (ta != null) unit else "", ta?.let { taMeters(unit, it) },
                enumOr(s("radio"), RADIOS, "UNKNOWN"),
                s("conn_status")?.takeIf { it in CONNECTION } ?: "", optInt("bandwidth_khz"), s("bands"), s("additional_plmns"),
                optInt("csg_indicator")?.let { if (it != 0) 1 else 0 }, optInt("csg_identity"), s("csg_name"), s("secondary_carriers"),
                s("service_state"), s("network_operator"), s("sim_operator"), optInt("network_roaming")?.let { if (it != 0) 1 else 0 },
                if (has("observed_at_ms")) utcIsoMillis(CursorValue.long(r.v(h, "observed_at_ms"))) else null,
                s("not_evaluated_heuristics"), if (has("gps_accuracy_m")) fixed(CursorValue.float(r.v(h, "gps_accuracy_m")), 1) else null,
                s("app_version"), device, android, s("location_mode"),
                p?.let { ps("mode").orEmpty() }, level,
                p?.v(ev!!, "deviation_score")?.let { fixed(CursorValue.double(it), 2) },
                p?.let { "${pi("metrics_evaluated") ?: 0}/${pi("metrics_total") ?: 0}" }, ps("abstain_reason"), p?.let { pi("algorithm_version") ?: 0 },
                ps("profile_revision"), ps("evidence"), p?.let { if (pi("learned") == 1) 1 else 0 }, p?.let { if (pi("holdout") == 1) 1 else 0 },
                if (h.col("id") >= 0) r.rowid else null, s("observation_id")
            ) + if (churn) listOf(optInt("churn_cells_15m"), optInt("churn_changes_15m"), s("churn_motion")) else emptyList()
            values.joinTo(sb, ",") { field(it) }; sb.append('\n')
            // Cada carácter ocupa al menos un byte en UTF-8: al pasar del tope en caracteres ya lo pasa en bytes
            if (sb.length > partLimit) throw PartTooLarge("history", sb.length.toLong(), partLimit)
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Clave de orden de SQLite para valores numéricos (los textos van después de los números). */
    private fun sortKey(v: Any): Comparable<*> = when (v) { is Long -> v.toDouble(); is Double -> v; else -> Double.MAX_VALUE }

    // ───────────────────────── Radio (ExportUtils.serviceStateCsvRow, CellDbHelper.getServiceStateEvents) ─────────────────────────

    fun serviceStateCsv(): ByteArray? = part(maxPartReadBytes, maxPartReadBytes) { buildServiceStateCsv() }

    private fun buildServiceStateCsv(): ByteArray? {
        val t = load("service_state_events") ?: return null
        // ORDER BY ts_ms DESC, id DESC y después sortedBy { timestampMs } (estable)
        val ordered = t.rows.sortedWith(compareByDescending<SqliteFile.Row> { CursorValue.long(it.v(t, "ts_ms")) }.thenByDescending { it.rowid })
            .sortedBy { CursorValue.long(it.v(t, "ts_ms")) }
        val sb = StringBuilder(SERVICE_HEADER).append('\n')
        for (r in ordered) {
            fun str(c: String) = if (t.col(c) >= 0) CursorValue.string(r.v(t, c)) else null
            fun int(c: String) = if (t.col(c) >= 0 && r.v(t, c) != null) CursorValue.int(r.v(t, c)) else null
            fun bool(c: String) = int(c)?.let { it != 0 }
            fun b(v: Boolean?) = v?.let { if (it) 1 else 0 }
            listOf(
                instantText(CursorValue.long(r.v(t, "ts_ms"))), enumOr(str("state") ?: "", SERVICE_STATES, "UNKNOWN"),
                b(bool("data_registered")), b(bool("voice_registered")), b(bool("searching")), if (bool("roaming") == true) 1 else 0,
                str("operator_numeric") ?: "", str("operator_alpha") ?: "", str("sim_operator") ?: "", if (bool("manual_selection") == true) 1 else 0,
                int("channel_number"), str("cell_bandwidths")?.split(";")?.mapNotNull { it.toIntOrNull() }.orEmpty().joinToString(";"),
                str("data_network") ?: "", str("voice_network") ?: "", enumOr(str("source") ?: "", setOf("CALLBACK", "POLL"), "POLL")
            ).joinTo(sb, ",") { field(it) }
            sb.append('\n')
        }
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ───────────────────────── topología (TopologyExporter.buildFiles, CellDbHelper.getAllCellTransitions) ─────────────────────────

    private class Route(val from: String, val to: String, val obs: Int, val trusted: Int, val status: String, val seen: Long, val trips: Int,
                        val lastTrip: String?, val mobFirst: Long?, val mobLast: Long?) {
        /** `CellTransitionSummary.trustRatio` (Float). */
        val trustRatio: Float get() = if (obs <= 0) 0f else trusted.toFloat() / obs
    }

    /** `SELECT … FROM cell_transitions ORDER BY last_seen_ms DESC`: con el índice `idx_cell_transitions_seen`, empates por rowid descendente. */
    private fun routes(): List<Route>? {
        val t = load("cell_transitions") ?: return null
        fun lng(r: SqliteFile.Row, c: String) = if (t.col(c) >= 0 && r.v(t, c) != null) CursorValue.long(r.v(t, c)) else null
        return t.rows.sortedWith(compareByDescending<SqliteFile.Row> { CursorValue.long(it.v(t, "last_seen_ms")) }.thenByDescending { it.rowid }).map { r ->
            Route(CursorValue.string(r.v(t, "from_identity")) ?: "", CursorValue.string(r.v(t, "to_identity")) ?: "",
                CursorValue.int(r.v(t, "observations")), CursorValue.int(r.v(t, "trusted_observations")),
                enumOr(CursorValue.string(r.v(t, "last_status")), HEURISTIC_STATUS, "NOT_EVALUATED"), CursorValue.long(r.v(t, "last_seen_ms")),
                if (t.col("trip_count") >= 0) CursorValue.int(r.v(t, "trip_count")) else 0,
                if (t.col("last_trip_id") >= 0) CursorValue.string(r.v(t, "last_trip_id")) else null, lng(r, "mobility_first_seen_ms"), lng(r, "mobility_last_seen_ms"))
        }
    }

    fun topology(): LinkedHashMap<String, ByteArray>? = part(maxPartReadBytes, maxPartReadBytes) { buildTopology() }

    private fun buildTopology(): LinkedHashMap<String, ByteArray>? {
        val tr = routes() ?: return null
        val files = LinkedHashMap<String, ByteArray>()
        val ids = tr.flatMap { listOf(it.from, it.to) }.distinct().sorted()
        val inc = tr.groupBy { it.to }; val out = tr.groupBy { it.from }
        files["cells.csv"] = buildString {
            append("identity,incoming_routes,outgoing_routes,incoming_handovers,outgoing_handovers,trusted_incoming,trusted_outgoing\n")
            for (i in ids) {
                val a = inc[i].orEmpty(); val b = out[i].orEmpty()
                line(this, listOf(i, a.size, b.size, a.sumOf { it.obs }, b.sumOf { it.obs }, a.sumOf { it.trusted }, b.sumOf { it.trusted }))
            }
        }.toByteArray()
        files["transitions.csv"] = buildString {
            append("from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc\n")
            for (x in tr.sortedWith(compareBy<Route> { it.from }.thenBy { it.to }))
                line(this, listOf(x.from, x.to, x.obs, x.trusted, fixed(x.trustRatio, 4), x.status, instantText(x.seen)))
        }.toByteArray()
        val node = ids.withIndex().associate { (k, i) -> i to "n$k" }
        files["topology.graphml"] = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n")
            append("  <key id=\"identity\" for=\"node\" attr.name=\"identity\" attr.type=\"string\"/>\n")
            append("  <key id=\"observations\" for=\"edge\" attr.name=\"observations\" attr.type=\"int\"/>\n")
            append("  <key id=\"trusted\" for=\"edge\" attr.name=\"trusted_observations\" attr.type=\"int\"/>\n")
            append("  <key id=\"status\" for=\"edge\" attr.name=\"last_status\" attr.type=\"string\"/>\n")
            append("  <key id=\"last_seen\" for=\"edge\" attr.name=\"last_seen_utc\" attr.type=\"string\"/>\n")
            append("  <graph id=\"ICdetectionTopology\" edgedefault=\"directed\">\n")
            for (i in ids) append("    <node id=\"${node.getValue(i)}\"><data key=\"identity\">${xml(i)}</data></node>\n")
            tr.forEachIndexed { k, x ->
                append("    <edge id=\"e$k\" source=\"${node.getValue(x.from)}\" target=\"${node.getValue(x.to)}\">\n")
                append("      <data key=\"observations\">${x.obs}</data>\n      <data key=\"trusted\">${x.trusted}</data>\n")
                append("      <data key=\"status\">${x.status}</data>\n      <data key=\"last_seen\">${instantText(x.seen)}</data>\n    </edge>\n")
            }
            append("  </graph>\n</graphml>\n")
        }.toByteArray()
        files["metadata.json"] = AndroidJson.pretty(linkedMapOf(
            "schemaVersion" to 1, "generatedAtUtc" to instantText(exportAtMs), "appVersion" to appVersion, "appVersionCode" to versionCode,
            "directedGraph" to true, "cellCount" to ids.size, "routeCount" to tr.size, "handoverCount" to tr.sumOf { it.obs },
            "privacyNote" to "Cell identities and handover routes can reveal habitual movement patterns.",
            "integrityNote" to INTEGRITY_NOTE)).toByteArray()
        files["SHA256SUMS.txt"] = sums(files)
        return files
    }

    // ───────────────────────── geometría (GeometryExporter.buildFiles, MobilityGeometryProjection, MobilityFamiliarityPolicy) ─────────────────────────

    fun geometry(): LinkedHashMap<String, ByteArray>? = part(maxPartReadBytes, maxPartReadBytes) { buildGeometry() }

    private fun buildGeometry(): LinkedHashMap<String, ByteArray>? {
        val tr = routes() ?: return null
        val trips = load("mobility_trips") ?: return null
        val tc = load("mobility_trip_cells"); val te = load("mobility_trip_edges")
        fun s(rows: Rows, r: SqliteFile.Row, c: String) = CursorValue.string(r.v(rows, c))
        val cellsByTrip = tc?.let { t -> t.rows.groupBy { s(t, it, "trip_id") }.mapValues { (_, l) -> l.mapNotNull { s(t, it, "cell_identity") }.toSet() } }.orEmpty()
        val edgesByTrip = te?.let { t -> t.rows.groupBy { s(t, it, "trip_id") }.mapValues { (_, l) -> l.map { s(t, it, "from_identity") to s(t, it, "to_identity") } } }.orEmpty()
        class Trip(val id: String, val start: Long, val end: Long?, val state: String?, val reason: String?, val moving: Boolean, val cells: Int, val edges: Int, val last: String?)
        // GROUP BY t.trip_id ORDER BY t.started_at_ms DESC (empates: por trip_id, el orden del grupo; no comprobado con datos reales)
        val list = trips.rows.map { r ->
            val id = s(trips, r, "trip_id") ?: ""
            Trip(id, CursorValue.long(r.v(trips, "started_at_ms")), r.v(trips, "closed_at_ms")?.let { CursorValue.long(it) }, s(trips, r, "state"),
                s(trips, r, "close_reason"), CursorValue.int(r.v(trips, "has_moving")) != 0, cellsByTrip[id]?.size ?: 0,
                edgesByTrip[id]?.filter { it.first != null && it.second != null }?.distinct()?.size ?: 0, s(trips, r, "last_serving"))
        }.sortedWith(compareBy<Trip> { it.id }).sortedByDescending { it.start }
        val open = list.firstOrNull { it.state == "OPEN" }
        val pending = if (open == null) emptySet() else edgesByTrip[open.id].orEmpty().mapNotNull { (a, b) -> if (a != null && b != null) a to b else null }.toSet()
        val ids = buildSet {
            tr.forEach { add(it.from); add(it.to) }
            pending.forEach { add(it.first); add(it.second) }
            open?.last?.let(::add)
        }.sorted()
        val inc = tr.groupBy { it.to }; val out = tr.groupBy { it.from }
        val validOpen = open != null && open.moving && open.cells >= MIN_DISTINCT_CELLS
        class Cell(val id: String, val values: List<Any?>, val fam: String, val good: Int, val relevant: Int, val radio: String)
        val cells = ids.map { id ->
            val a = inc[id].orEmpty(); val b = out[id].orEmpty(); val all = a + b
            val counts = all.map { it.trips }
            val good = counts.count { it >= PRIOR_TRIPS }
            val fam = when { good >= GOOD_EDGES -> "KNOWN_ON_ROUTE"; counts.any { it > 0 } -> "OBSERVED_ON_ROUTE"; else -> "UNKNOWN_ON_ROUTE" }
            val radio = id.substringAfterLast('-', "UNKNOWN")
            val relevant = all.maxOfOrNull { it.trips } ?: 0
            Cell(id, listOf(id, radio, null, fam, good, relevant, a.size, b.size, a.sumOf { it.obs }, b.sumOf { it.obs },
                a.sumOf { it.trusted }, b.sumOf { it.trusted }, all.mapNotNull { it.mobFirst }.minOrNull()?.let { instantText(it) },
                all.mapNotNull { it.mobLast }.maxOrNull()?.let { instantText(it) }), fam, good, relevant, radio)
        }
        val files = LinkedHashMap<String, ByteArray>()
        files["geometry_cells.csv"] = buildString {
            append("identity,radio_tech,local_trust_state,mobility_familiarity,mobility_good_edges,mobility_relevant_trip_count,incoming_routes,outgoing_routes,incoming_transitions,outgoing_transitions,trusted_incoming,trusted_outgoing,first_mobility_seen_utc,last_mobility_seen_utc\n")
            cells.forEach { line(this, it.values) }
        }.toByteArray()
        files["geometry_edges.csv"] = buildString {
            append("from_identity,to_identity,transition_count,trusted_transition_count,trip_count,pending_current_trip,projected_trip_count_after_valid_close,mobility_first_seen_utc,mobility_last_seen_utc,last_trip_id\n")
            for (e in tr) {
                val p = (e.from to e.to) in pending
                line(this, listOf(e.from, e.to, e.obs, e.trusted, e.trips, p, if (p && validOpen) e.trips + 1 else e.trips,
                    e.mobFirst?.let { instantText(it) }, e.mobLast?.let { instantText(it) }, e.lastTrip))
            }
        }.toByteArray()
        files["mobility_trips.csv"] = buildString {
            append("trip_id,started_at_utc,ended_at_utc,state,close_reason,had_moving,distinct_cells,edge_count\n")
            for (t in list) line(this, listOf(t.id, instantText(t.start), t.end?.let { instantText(it) }, t.state, t.reason, t.moving, t.cells, t.edges))
        }.toByteArray()
        val node = cells.withIndex().associate { (k, c) -> c.id to "n$k" }
        files["geometry.graphml"] = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<graphml xmlns=\"http://graphml.graphdrawing.org/xmlns\">\n")
            listOf("identity", "radio_tech", "local_trust_state", "mobility_familiarity", "mobility_good_edges", "mobility_relevant_trip_count")
                .forEach { append("  <key id=\"$it\" for=\"node\" attr.name=\"$it\" attr.type=\"string\"/>\n") }
            listOf("transition_count", "trusted_transition_count", "trip_count").forEach { append("  <key id=\"$it\" for=\"edge\" attr.name=\"$it\" attr.type=\"int\"/>\n") }
            append("  <graph id=\"ICdetectionGeometry\" edgedefault=\"directed\">\n")
            for (c in cells) append("    <node id=\"${node.getValue(c.id)}\"><data key=\"identity\">${xml(c.id)}</data><data key=\"radio_tech\">${xml(c.radio)}</data>" +
                "<data key=\"local_trust_state\">UNAVAILABLE</data><data key=\"mobility_familiarity\">${c.fam}</data><data key=\"mobility_good_edges\">${c.good}</data>" +
                "<data key=\"mobility_relevant_trip_count\">${c.relevant}</data></node>\n")
            tr.forEachIndexed { k, e -> append("    <edge id=\"e$k\" source=\"${node.getValue(e.from)}\" target=\"${node.getValue(e.to)}\"><data key=\"transition_count\">${e.obs}</data>" +
                "<data key=\"trusted_transition_count\">${e.trusted}</data><data key=\"trip_count\">${e.trips}</data></edge>\n") }
            append("  </graph>\n</graphml>\n")
        }.toByteArray()
        files["metadata.json"] = AndroidJson.pretty(linkedMapOf(
            "schemaVersion" to 17, "appVersion" to appVersion, "appVersionCode" to versionCode, "exportTimeUtc" to instantText(exportAtMs),
            "feature" to "MOBILITY_FAMILIARITY", "featureMode" to "CONTEXT_ONLY", "K_PRIOR_TRIPS" to PRIOR_TRIPS, "R_GOOD_EDGES" to GOOD_EDGES,
            "MIN_DISTINCT_CELLS" to MIN_DISTINCT_CELLS, "STATIC_CLOSE_DURATION_MS" to 180_000L, "SAME_SERVING_CLOSE_DURATION_MS" to 600_000L,
            "MAX_TRIP_DURATION_MS" to 21_600_000L,
            "semanticNote" to "Mobility Familiarity is contextual route memory. KNOWN_ON_ROUTE does not mean trusted, verified or legitimate.",
            "privacyNote" to "This export can reveal cell identities and patterns associated with frequently visited places or routes. Review it before sharing publicly.",
            "coordinatesIncluded" to false, "localTrustStateAvailability" to "Unavailable in schema 17; left empty rather than inferred.",
            "openTripContributionIncludedInFamiliarity" to false)).toByteArray()
        return files
    }

    // ───────────────────────── Stable-Site (CellDbHelper.getStableSiteExportFiles, StableSiteMaturityPolicy, StableSiteExportNote) ─────────────────────────

    fun stableSite(): LinkedHashMap<String, ByteArray>? = part(maxPartReadBytes, maxPartReadBytes) { buildStableSite() }

    private fun buildStableSite(): LinkedHashMap<String, ByteArray>? {
        val ev = load("site_cell_evidence") ?: return null
        val days = load("site_cell_days"); val motion = load("site_motion_days")
        val rf = load("site_rf_neighbours"); val rfDays = load("site_rf_neighbour_days"); val sh = load("site_shadow_triggers")
        fun s(rows: Rows?, r: SqliteFile.Row, c: String) = rows?.let { CursorValue.string(r.v(it, c)) }
        fun l(rows: Rows, r: SqliteFile.Row, c: String) = r.v(rows, c)
        val files = LinkedHashMap<String, ByteArray>()
        files[PRIVACY_FILE] = PRIVACY_NOTE.toByteArray()
        // Agrupación por site_key en el orden de la clave primaria (site_key, cell_identity, role): orden binario ascendente.
        val bySite = ev.rows.groupBy { s(ev, it, "site_key") ?: "" }.toSortedMap(BINARY)
        // Índices por site_key (A4: antes cada sitio recorría las tablas enteras). La clave es la del valor tal cual,
        // null incluido, para que las búsquedas den exactamente las mismas filas que el filtro `site_key == site`.
        fun index(rows: Rows?) = rows?.rows.orEmpty().groupBy { s(rows, it, "site_key") }
        val evIdx = index(ev); val daysIdx = index(days); val motionIdx = index(motion); val rfIdx = index(rf); val rfDaysIdx = index(rfDays)
        fun distinctDays(rows: Rows?, idx: Map<String?, List<SqliteFile.Row>>, site: String, filter: (SqliteFile.Row) -> Boolean) =
            idx[site].orEmpty().filter { filter(it) }.mapNotNull { s(rows, it, "day") }.toSet().size
        files["sites.csv"] = buildString {
            append("site_key,feature_state,neighbour_capability,maturity_reason,serving_observations,serving_distinct_days,static_distinct_days,full_neighbour_observations,full_neighbour_distinct_days,rf_neighbour_observations,rf_neighbour_distinct_days,first_seen_ms,last_seen_ms\n")
            for ((site, rows) in bySite) {
                val servingObs = rows.filter { s(ev, it, "role") == "SERVING" }.sumOf { CursorValue.long(l(ev, it, "observations")) }
                checkpoint.check()
                val servingDays = distinctDays(days, daysIdx, site) { s(days, it, "role") == "SERVING" }
                val staticDays = distinctDays(motion, motionIdx, site) { s(motion, it, "state") == "STATIC_CONFIRMED" }
                val fullDays = distinctDays(days, daysIdx, site) { s(days, it, "role") == "NEIGHBOUR" }
                val fullObs = evIdx[site].orEmpty().filter { s(ev, it, "role") == "NEIGHBOUR" }.sumOf { CursorValue.long(l(ev, it, "observations")) }
                val rfD = distinctDays(rfDays, rfDaysIdx, site) { true }
                val rfObs = rfIdx[site].orEmpty().sumOf { CursorValue.long(l(rf!!, it, "observations")) }
                val first = rows.mapNotNull { l(ev, it, "first_seen_ms")?.let { v -> CursorValue.long(v) } }.minOrNull()
                val last = rows.mapNotNull { l(ev, it, "last_seen_ms")?.let { v -> CursorValue.long(v) } }.maxOrNull()
                val (state, cap, reason) = maturity(servingDays, staticDays, fullDays, rfD, servingObs.toInt())
                listOf(site, state, cap, reason, servingObs, servingDays, staticDays, fullObs, fullDays, rfObs, rfD, first, last).joinTo(this, ",") { field(it) }
                append('\n')
            }
        }.toByteArray()
        files["site_cells.csv"] = buildString {
            append("site_key,cell_identity,serving_observations,serving_distinct_days,neighbour_observations,neighbour_distinct_days,first_serving_ms,last_serving_ms,first_neighbour_ms,last_neighbour_ms\n")
            val groups = ev.rows.groupBy { (s(ev, it, "site_key") ?: "") to (s(ev, it, "cell_identity") ?: "") }
                .toSortedMap(compareBy<Pair<String, String>, String>(BINARY) { it.first }.thenComparing({ it.second }, BINARY))
            val daysByCell = days?.rows.orEmpty().groupingBy { Triple(s(days, it, "site_key"), s(days, it, "cell_identity"), s(days, it, "role")) }.eachCount()
            for ((k, rows) in groups) {
                checkpoint.check()
                fun role(r: String) = rows.filter { s(ev, it, "role") == r }
                fun dayCount(r: String) = daysByCell[Triple<String?, String?, String?>(k.first, k.second, r)] ?: 0
                fun min(r: String, c: String) = role(r).mapNotNull { l(ev, it, c)?.let { v -> CursorValue.long(v) } }.minOrNull()
                fun max(r: String, c: String) = role(r).mapNotNull { l(ev, it, c)?.let { v -> CursorValue.long(v) } }.maxOrNull()
                listOf(k.first, k.second, role("SERVING").sumOf { CursorValue.long(l(ev, it, "observations")) }, dayCount("SERVING"),
                    role("NEIGHBOUR").sumOf { CursorValue.long(l(ev, it, "observations")) }, dayCount("NEIGHBOUR"),
                    min("SERVING", "first_seen_ms"), max("SERVING", "last_seen_ms"), min("NEIGHBOUR", "first_seen_ms"), max("NEIGHBOUR", "last_seen_ms"))
                    .joinTo(this, ",") { field(it?.toString()) }
                append('\n')
            }
        }.toByteArray()
        files["site_rf_neighbours.csv"] = buildString {
            append("site_key,rf_fingerprint,evidence_type,radio,arfcn,pci,mcc_metadata,mnc_metadata,tac_metadata,observations,distinct_days,first_seen_ms,last_seen_ms\n")
            val rfDaysByFp = rfDays?.rows.orEmpty().groupingBy { s(rfDays, it, "site_key") to s(rfDays, it, "fingerprint") }.eachCount()
            rf?.rows.orEmpty().sortedWith(compareBy<SqliteFile.Row, String>(BINARY) { s(rf, it, "site_key") ?: "" }.thenComparing({ s(rf, it, "fingerprint") ?: "" }, BINARY)).forEach { r ->
                val site = s(rf, r, "site_key"); val fp = s(rf, r, "fingerprint")
                val n = rfDaysByFp[site to fp] ?: 0
                listOf(site, fp, "RF_CONTEXT", s(rf, r, "radio"), s(rf, r, "arfcn"), s(rf, r, "pci"), s(rf, r, "mcc"), s(rf, r, "mnc"), s(rf, r, "tac"),
                    s(rf, r, "observations"), n.toString(), s(rf, r, "first_seen_ms"), s(rf, r, "last_seen_ms")).joinTo(this, ",") { field(it) }
                append('\n')
            }
        }.toByteArray()
        files["motion.csv"] = buildString {
            append("site_key,day,state,accuracy_band_m,duration_s,displacement_band_m\n")
            // ORDER BY day,site_key: recorrido de la tabla en orden de rowid y ordenación estable
            motion?.rows.orEmpty().sortedWith(compareBy<SqliteFile.Row, String>(BINARY) { s(motion, it, "day") ?: "" }.thenComparing({ s(motion, it, "site_key") ?: "" }, BINARY)).forEach { r ->
                listOf("site_key", "day", "state", "accuracy_band_m", "duration_s", "displacement_band_m").joinTo(this, ",") { field(s(motion, r, it)) }
                append('\n')
            }
        }.toByteArray()
        files["shadow_episodes.csv"] = buildString {
            append("episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,duration_ms,evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated\n")
            sh?.rows.orEmpty().sortedBy { CursorValue.long(it.v(sh!!, "first_seen_ms")) }.forEach { r ->
                val first = r.v(sh!!, "first_seen_ms"); val last = r.v(sh, "last_seen_ms")
                val duration = if (first == null || last == null) null else (CursorValue.long(last) - CursorValue.long(first)).toString()
                listOf(s(sh, r, "episode_id"), s(sh, r, "site_key"), s(sh, r, "previous_identity"), s(sh, r, "candidate_identity"), s(sh, r, "first_seen_ms"),
                    s(sh, r, "last_seen_ms"), duration, s(sh, r, "evaluated_ms"), s(sh, r, "closed_ms"), s(sh, r, "shadow_recorded"), s(sh, r, "reason"),
                    s(sh, r, "motion_state"), s(sh, r, "feature_state"), s(sh, r, "globally_known"), s(sh, r, "known_at_site"), s(sh, r, "seen_as_neighbour"),
                    s(sh, r, "serving_days"), s(sh, r, "neighbour_days"), s(sh, r, "corroborated")).joinTo(this, ",") { field(it) }
                append('\n')
            }
        }.toByteArray()
        return files
    }

    // ───────────────────────── casos forenses (ForensicExporter.buildFiles, CellDbHelper.getForensicCases/getForensicSamples) ─────────────────────────

    /**
     * Un caso forense de la copia, todavía sin construir: [files] lo construye al pedirlo (un caso cada vez), así la
     * importación no tiene en memoria las muestras de todos los casos a la vez. [payloadBytes] son los bytes de
     * `payload_json` del caso, contados sin conservarlos, para descartar un caso enorme antes de leerlo.
     */
    class ForensicExport(val caseCode: String, val payloadBytes: Long, val sampleRows: Int, private val build: () -> LinkedHashMap<String, ByteArray>) {
        val files: LinkedHashMap<String, ByteArray> by lazy { build() }
    }

    /**
     * Un ZIP por caso, del más reciente al más antiguo (`ORDER BY c.id DESC`). Una muestra ilegible lanza
     * [AndroidJson.ParseException] al construir ese caso, no al listar.
     */
    fun forensicCases(): List<ForensicExport>? = part(maxPartReadBytes, maxPartReadBytes) { buildForensicCases() }

    private fun buildForensicCases(): List<ForensicExport>? {
        val cases = load("forensic_cases") ?: return null
        val st = db.table("forensic_samples")
        // Primera pasada: bytes y muestras por caso, sin guardar los payloads.
        val bytes = HashMap<Long, Long>(); val counts = HashMap<Long, Int>()
        if (st != null) {
            val ci = st.indexOf("case_id"); val pi = st.indexOf("payload_json")
            var n = 0
            for (r in db.rows(st)) {
                if (++n and 1023 == 0) checkpoint.check()
                val id = CursorValue.long(r[ci])
                bytes.merge(id, payloadSize(r[pi]), Long::plus); counts.merge(id, 1, Int::plus)
            }
        }
        return cases.rows.sortedByDescending { it.rowid }.map { c ->
            fun cs(col: String) = CursorValue.string(c.v(cases, col))
            val code = cs("case_code") ?: ""
            ForensicExport(code, bytes[c.rowid] ?: 0L, counts[c.rowid] ?: 0) {
                val list = ArrayList<Pair<Triple<Long, Long, String?>, Map<String, Any?>>>()
                if (st != null) {
                    val ci = st.indexOf("case_id")
                    // Segunda pasada, solo las muestras de este caso (en orden de rowid, como `ORDER BY id`).
                    var n = 0
                    for (s in db.rows(st)) {
                        if (++n and 1023 == 0) checkpoint.check()
                        if (CursorValue.long(s[ci]) != c.rowid) continue
                        if (list.size >= maxRows) throw TooManyRows("forensic_samples", list.size + 1)
                        list += Triple(CursorValue.long(s[st.indexOf("wall_time_ms")]), CursorValue.long(s[st.indexOf("elapsed_time_ms")]), CursorValue.string(s[st.indexOf("event")])) to
                            AndroidJson.obj(CursorValue.string(s[st.indexOf("payload_json")]) ?: "")
                    }
                }
                forensicFiles(code, cs("created_at"), cs("updated_at"), cs("closed_at"),
                    enumOr(cs("state"), setOf("CAPTURING", "POST_CAPTURE", "READY", "INTERRUPTED"), "INTERRUPTED"),
                    cs("cell_identity"), CursorValue.int(c.v(cases, "highest_phase")), CursorValue.int(c.v(cases, "confirmed")) != 0, list)
            }
        }
    }

    /** Bytes UTF-8 de un valor de `payload_json` sin copiarlo. */
    private fun payloadSize(v: Any?): Long = when (v) {
        null -> 0L
        is ByteArray -> v.size.toLong()
        is String -> { var n = 0L; for (ch in v) n += when { ch.code < 0x80 -> 1; ch.code < 0x800 -> 2; Character.isSurrogate(ch) -> 2; else -> 3 }; n }
        else -> v.toString().length.toLong()
    }

    private fun forensicFiles(code: String, created: String?, updated: String?, closed: String?, state: String, identity: String?, phase: Int,
                              confirmed: Boolean, parsed: List<Pair<Triple<Long, Long, String?>, Map<String, Any?>>>): LinkedHashMap<String, ByteArray> {
        val J = AndroidJson
        val files = LinkedHashMap<String, ByteArray>()
        val first = parsed.firstOrNull()?.second
        files["case.json"] = J.pretty(linkedMapOf(
            "schemaVersion" to 1, "caseId" to code, "state" to state, "createdAt" to created,
            "captureOrigin" to if (code.startsWith("ICD-OBS-")) "TRUST_CONTRADICTION" else "ALARM",
            "updatedAt" to updated, "closedAt" to (closed ?: AndroidJson.Null), "cellIdentity" to identity, "highestPhase" to phase,
            "confirmed" to confirmed, "sampleCount" to parsed.size, "appVersion" to appVersion, "appVersionCode" to versionCode,
            "device" to (first?.let { J.optString(it, "device") } ?: "unknown"), "android" to (first?.let { J.optString(it, "android") } ?: "unknown"),
            "sampleSchemaVersion" to (first?.let { J.optInt(it, "schemaVersion", 0) } ?: 0),
            "integrityNote" to INTEGRITY_NOTE).filterValues { it != null }).toByteArray()
        fun nullable(o: Map<String, Any?>?, k: String): Any? = if (J.isNullOrMissing(o, k)) "" else o!![k]
        fun joined(a: List<Any?>?) = a?.joinToString(";") { J.valueOf(it) } ?: ""
        fun quoted(v: Any?) = CsvText.quoted(csvValue(v))
        files["timeline.csv"] = buildString {
            append("timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason,required_phases,data_quality_reason,heuristics\n")
            for ((s, j) in parsed) {
                val serving = J.optObject(j, "serving") ?: throw AndroidJson.ParseException("muestra sin «serving»")
                val gps = J.optObject(j, "gps")
                listOf(instantText(s.first), s.second, s.third, J.optInt(j, "phase"), J.optInt(j, "score"), J.optDouble(j, "anomalyConfidence"),
                    J.optString(serving, "identity"), J.optString(j, "verified"), J.optString(j, "latencyState"), gps?.let { J.optDouble(it, "lat") },
                    gps?.let { J.optDouble(it, "lon") }, gps?.let { J.optDouble(it, "accuracyM") }, J.optString(j, "reason"),
                    nullable(j, "requiredPhases"), nullable(j, "dataQualityReason"), J.optString(j, "heuristics")).joinTo(this, ",") { quoted(it) }
                append('\n')
            }
        }.toByteArray()
        files["cells.csv"] = buildString {
            append("timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit,connection_state,bandwidth_khz,registered,bands,csg_indicator,secondary_carriers\n")
            for ((s, j) in parsed) {
                fun row(role: String, c: Map<String, Any?>) {
                    listOf(instantText(s.first), role, J.optString(c, "identity"), J.optString(c, "mcc"), J.optString(c, "mnc"), J.optString(c, "tac"),
                        J.optString(c, "cid"), J.optString(c, "radio"), J.optString(c, "networkType"), nullable(c, "dbm"), nullable(c, "pci"), nullable(c, "arfcn"),
                        nullable(c, "band"), nullable(c, "rsrq"), nullable(c, "sinr"), nullable(c, "ta"), J.optString(c, "taUnit"), J.optString(c, "connectionState"),
                        nullable(c, "bandwidthKhz"), nullable(c, "registered"), joined(J.optArray(c, "bands")), nullable(c, "csgIndicator"),
                        joined(J.optArray(c, "secondaryCarriers"))).joinTo(this, ",") { quoted(it) }
                    append('\n')
                }
                row("SERVING", J.optObject(j, "serving") ?: throw AndroidJson.ParseException("muestra sin «serving»"))
                for (n in J.optArray(j, "neighbors").orEmpty()) {
                    @Suppress("UNCHECKED_CAST") val c = n as? Map<String, Any?> ?: throw AndroidJson.ParseException("vecina que no es un objeto")
                    row(if (J.optString(c, "connectionState") == "SECONDARY_SERVING") "SECONDARY" else "NEIGHBOR", c)
                }
            }
        }.toByteArray()
        files["heuristics.csv"] = buildString {
            append("timestamp_utc,heuristic_id,name,status,explanation\n")
            for ((s, j) in parsed) for (d in J.optArray(j, "diagnostics").orEmpty()) {
                @Suppress("UNCHECKED_CAST") val x = d as? Map<String, Any?> ?: throw AndroidJson.ParseException("diagnóstico que no es un objeto")
                listOf(instantText(s.first), J.optInt(x, "id"), J.optString(x, "name"), J.optString(x, "status"), J.optString(x, "explanation")).joinTo(this, ",") { quoted(it) }
                append('\n')
            }
        }.toByteArray()
        files["trust_site.csv"] = buildString {
            append("timestamp_utc,local_trust_state,local_trust_confidence_percent,local_trust_contradictions,site_key,site_feature_state,site_novelty,site_would_trigger,site_enforced,site_reason,site_static_duration_s,site_location_accuracy_m,site_serving_days,site_neighbour_days\n")
            for ((s, j) in parsed) {
                val trust = J.optObject(j, "localTrust") ?: emptyMap(); val site = J.optObject(j, "stableSite") ?: emptyMap()
                listOf(instantText(s.first), J.optString(trust, "state"), nullable(trust, "confidencePercent"), joined(J.optArray(trust, "contradictions")),
                    nullable(site, "siteKey"), J.optString(site, "featureState"), J.optString(site, "novelty"), nullable(site, "wouldTrigger"),
                    nullable(site, "enforced"), J.optString(site, "reason"), nullable(site, "staticDurationSeconds"), nullable(site, "locationAccuracyM"),
                    nullable(site, "servingDays"), nullable(site, "neighbourDays")).joinTo(this, ",") { quoted(it) }
                append('\n')
            }
        }.toByteArray()
        files["capabilities.json"] = J.pretty(linkedMapOf("schemaVersion" to 1, "observations" to parsed.map { (s, j) ->
            linkedMapOf("timestamp" to instantText(s.first), "values" to (J.optObject(j, "capabilities") ?: emptyMap<String, Any?>()))
        })).toByteArray()
        files["terminal.log"] = mergeLogWindows(parsed.map { (_, j) -> J.optArray(j, "logs").orEmpty().map { J.valueOf(it) } })
            .joinToString("") { it + "\n" }.toByteArray()
        files["SHA256SUMS.txt"] = sums(files)
        return files
    }

    /** Texto de un valor en un CSV del exportador forense (`toString()` de Kotlin de lo que devuelve org.json). */
    private fun csvValue(v: Any?): String = when (v) {
        null -> ""
        is String -> v
        is Double -> java.lang.Double.toString(v)
        is Map<*, *>, is List<*> -> AndroidJson.compact(v)
        AndroidJson.Null -> "null"
        else -> v.toString()
    }

    // ───────────────────────── utilidades ─────────────────────────

    private fun field(v: Any?): String = CsvText.field(when (v) { null -> ""; is Double -> java.lang.Double.toString(v); else -> v.toString() })
    private fun line(sb: StringBuilder, values: List<Any?>) { values.joinTo(sb, ",") { CsvText.quoted(csvValue(it)) }; sb.append('\n') }
    private fun xml(v: String) = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
    private fun sums(files: Map<String, ByteArray>): ByteArray = files.entries.joinToString("\n", postfix = "\n") { (n, b) -> "${Hashing.sha256Hex(b)}  $n" }.toByteArray()
    private fun enumOr(v: String?, allowed: Set<String>, fallback: String) = if (v != null && v in allowed) v else fallback

    /** `TimingAdvanceUnit.toMeters`. */
    private fun taMeters(unit: String, ta: Int): Int? {
        if (ta < 0) return null
        return when (unit) { "LTE_INDEX" -> if (ta in 0..1282) ta * 78 else null; "GSM_INDEX" -> if (ta in 0..219) ta * 554 else null; else -> null }
    }

    companion object {
        const val HISTORY_HEADER = "Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence," +
            "ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio,ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName," +
            "SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming,ObservedAtUtc,NotEvaluatedHeuristics,GpsAccuracyM," +
            "AppVersion,ExportDevice,ExportAndroid,LocationMode,ProfileMode,ProfileLevel,ProfileScore,ProfileCoverage,ProfileAbstainReason," +
            "ProfileAlgorithm,ProfileRevision,ProfileEvidence,ProfileLearned,ProfileHoldout,RowId,ObservationId"
        /** ICdetector 3.1.0: las mismas 54 columnas y el churn de H17 al final (57). */
        const val HISTORY_HEADER_31 = "$HISTORY_HEADER,ChurnCells15m,ChurnChanges15m,ChurnMotion"
        /** Columnas de `history` que añade el esquema 26 (`SchemaV26`). */
        val CHURN_COLUMNS = listOf("churn_cells_15m", "churn_changes_15m", "churn_motion")
        const val SERVICE_HEADER = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator," +
            "ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source"
        const val INTEGRITY_NOTE = "SHA-256 detects later modification; it is not a legal chain-of-custody signature."
        const val PRIVACY_FILE = "PRIVACY.txt"

        /** `StableSiteExportNote.PRIVACY_NOTE` de ICdetector 3.0.0. */
        val PRIVACY_NOTE = """
            ICdetection - Stable-Site export: privacy note

            Site keys (site_key) are not anonymous.
            - Each key is a deterministic, unsalted SHA-256 hash (first 10 bytes) of a cell of a fixed
              ~500 m grid. Four overlapping grids are used, so one place can have up to four keys.
            - The same place gives the same key on every phone and in every export.
            - Anyone can compute the keys of every grid cell in an area (a candidate search) and match
              them, so a key can be linked to an approximate location (about 500 m).

            The rest of the files can also reveal habits:
            - site_cells.csv and shadow_episodes.csv contain complete cell identities;
            - first/last-seen times, days, observation counts and motion bands show when and how often
              you were at each place.

            No exact coordinates, IMSI, IMEI, phone number or credentials are included.
            Treat this ZIP as sensitive location and routine data and review it before sharing it.
        """.trimIndent() + "\n"

        private val VERIFIED = setOf("PENDING", "VERIFIED", "NOT_FOUND", "REJECTED", "ERROR")
        private val TA_UNITS = setOf("LTE_INDEX", "GSM_INDEX", "NR_RAW", "STUB_ZERO", "UNKNOWN")
        private val RADIOS = setOf("GSM", "UMTS", "LTE", "NR", "UNKNOWN")
        private val CONNECTION = setOf("PRIMARY_SERVING", "SECONDARY_SERVING", "NONE", "UNKNOWN")
        private val HEURISTIC_STATUS = setOf("PASSED", "FAILED", "NOT_EVALUATED")
        private val SERVICE_STATES = setOf("IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF", "UNKNOWN")

        // MobilityFamiliarityConfig de ICdetector 3.0.0
        private const val PRIOR_TRIPS = 3
        private const val GOOD_EDGES = 2
        private const val MIN_DISTINCT_CELLS = 3

        /** Orden binario de SQLite (memcmp sobre UTF-8). */
        private val BINARY = Comparator<String> { a, b ->
            val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
            for (i in 0 until minOf(x.size, y.size)) { val d = (x[i].toInt() and 0xFF) - (y[i].toInt() and 0xFF); if (d != 0) return@Comparator d }
            x.size - y.size
        }

        /** Las tablas del historial pueden ocupar al leerlas hasta este múltiplo del tope de su CSV (A3). */
        const val RAW_FACTOR = 2L

        /** `versionCode` de las versiones publicadas que conoce CellCase (la copia no lo guarda). 0 si no se conoce. */
        fun knownVersionCode(appVersion: String): Long = when (appVersion.substringBefore('+')) {
            "3.0.0" -> 39L
            "3.1.0" -> 40L
            "3.1.1" -> 41L   // 3.1.1 y 3.1.2: mismo esquema 26 y mismas exportaciones que 3.1.0
            "3.1.2" -> 42L
            else -> 0L
        }

        /** `StableSiteMaturityPolicy.evaluate` de ICdetector 3.0.0: estado, capacidad de vecinas y motivo. */
        fun maturity(servingDays: Int, staticDays: Int, fullNeighbourDays: Int, rfNeighbourDays: Int, servingObservations: Int): Triple<String, String, String> {
            val cap = when {
                fullNeighbourDays >= 3 -> "FULL_NEIGHBOUR_IDENTITY"
                rfNeighbourDays > 0 -> "RF_NEIGHBOUR_ONLY"
                fullNeighbourDays > 0 -> "FULL_NEIGHBOUR_IDENTITY"
                else -> "NO_NEIGHBOUR_DATA"
            }
            val ready = fullNeighbourDays >= 3 || rfNeighbourDays >= 3
            val state = when {
                servingDays == 0 -> "BOOTSTRAP"
                servingDays < 5 || staticDays < 2 -> "LEARNING"
                servingDays < 7 || staticDays < 3 || !ready || servingObservations < 30 -> "SHADOW_READY"
                else -> "ACTIVE"
            }
            val reason = when {
                state == "ACTIVE" -> "MATURE_$cap"
                servingDays < 7 -> "NEEDS_SERVING_DAYS"
                staticDays < 3 -> "NEEDS_STATIC_DAYS"
                servingObservations < 30 -> "NEEDS_SERVING_OBSERVATIONS"
                fullNeighbourDays in 1 until 3 -> "NEEDS_FULL_NEIGHBOUR_DAYS"
                rfNeighbourDays in 1 until 3 -> "NEEDS_RF_NEIGHBOUR_DAYS"
                !ready && cap == "NO_NEIGHBOUR_DATA" -> "NO_USABLE_NEIGHBOUR_DATA"
                else -> "MATURE"
            }
            return Triple(state, cap, reason)
        }

        /** `ForensicExporter.mergeLogWindows`: une ventanas del terminal quitando solo el solapamiento. */
        fun mergeLogWindows(windows: List<List<String>>): List<String> {
            val merged = ArrayList<String>()
            for (w in windows) {
                val overlap = (minOf(merged.size, w.size) downTo 0).first { k -> k == 0 || merged.subList(merged.size - k, merged.size) == w.subList(0, k) }
                merged += w.drop(overlap)
            }
            return merged
        }
    }
}

/** `CsvText` de ICdetector 3.0: protección de texto y comillas (CsvGuard.guard). */
object CsvText {
    /** Comillas solo cuando hacen falta (historial, Radio, Stable-Site). */
    fun field(v: String): String { val s = CsvGuard.guard(v); return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) quote(s) else s }
    /** Siempre entre comillas (forense, topología, geometría). */
    fun quoted(v: String): String = quote(CsvGuard.guard(v))
    private fun quote(s: String) = "\"" + s.replace("\"", "\"\"") + "\""
}
