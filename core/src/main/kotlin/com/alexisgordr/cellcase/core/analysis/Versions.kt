package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.HistoryColumns
import com.alexisgordr.cellcase.core.importing.HistoryImport
import com.alexisgordr.cellcase.core.model.ForensicCaseData
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.time.Instant

/** Versión de las reglas de combinación de exportaciones (historiales y casos forenses). */
const val MERGE_RULES_VERSION = "merge/4"

/**
 * Columnas del historial que pueden cambiar después de escribirse la fila (export-formats.md §1.5 y §1.8). En 3.0,
 * `GpsAccuracyM` se rellena con las coordenadas, y el perfil local puede evaluar una fila después (evaluaciones
 * reservadas del primer uso).
 */
val MUTABLE_HISTORY_COLUMNS = setOf("Verified", "ApiLat", "ApiLon", "Lat", "Lon", "TAMeters", "GpsAccuracyM") + HistoryColumns.PROFILE

/** Columnas que no se comparan entre archivos: describen la exportación, no la fila (merge/4). */
val NOT_COMPARED_HISTORY_COLUMNS = HistoryColumns.PER_EXPORT

// ───────────────────────────── historiales ─────────────────────────────

/** EXACT: todas las columnas comunes coinciden. REVISION: coinciden las inmutables y difiere alguna mutable. */
enum class RowMatch { EXACT, REVISION }

/**
 * Par de filas de archivos distintos que describen la misma observación. Se conservan ambas procedencias y, en
 * una revisión, los valores en conflicto de cada archivo. [schemaDiffers]: los archivos no tienen las mismas
 * columnas; la comparación se hizo sobre las columnas comunes.
 */
data class RowConflict(
    val kept: Provenance,
    val other: Provenance,
    val kind: RowMatch,
    val fields: Map<String, Pair<String?, String?>>,
    val schemaDiffers: Boolean
)

/** Historial combinado de varias exportaciones (D-017, D-040). */
data class MergedHistory(
    val import: HistoryImport,
    val sourceIds: List<String>,
    val matches: List<RowConflict>,
    val rowsRead: Int
) {
    /** Compatibilidad: pares de procedencias coincidentes. */
    val crossFileMatches: List<Pair<Provenance, Provenance>> get() = matches.map { it.kept to it.other }
    val revisedMatches: Int get() = matches.count { it.kind == RowMatch.REVISION }
    val exactMatches: Int get() = matches.count { it.kind == RowMatch.EXACT }
}

object HistoryMerge {
    /**
     * Combina historiales sin eliminar nada de los originales (merge/2):
     * - Orden canónico por contenido (no por orden de importación ni por identificadores internos): inicio, fin,
     *   número de filas, nombre y huella del contenido. Importar los mismos archivos en otro orden da los mismos hechos.
     * - Clave de comparación: pares «columna=valor» de las columnas inmutables COMUNES a las dos filas. Si los
     *   esquemas difieren, se compara solo lo común y se marca [RowConflict.schemaDiffers].
     * - Duplicado exacto: se cuenta una vez. Revisión (difieren columnas mutables): se cuenta una vez, pero los
     *   campos en conflicto quedan SIN valor en el análisis (no se sabe cuál es más reciente: los archivos no traen
     *   una fecha fiable de exportación) y ambos valores se conservan en [MergedHistory.matches].
     */
    fun merge(imports: List<HistoryImport>, zone: DeclaredZone?): MergedHistory? {
        if (imports.isEmpty()) return null
        val ordered = imports.sortedWith(compareBy<HistoryImport>(
            { i -> i.observations.mapNotNull { it.time.instantOrNull }.minOrNull() ?: Instant.MAX },
            { i -> i.observations.mapNotNull { it.time.instantOrNull }.maxOrNull() ?: Instant.MAX },
            { i -> -i.observations.size }, { it.fileName }, { contentHash(it) }))
        if (ordered.size == 1) {
            val i = ordered[0]
            return MergedHistory(i, listOf(i.importId), emptyList(), i.observations.size)
        }
        val baseImmutable = HistoryColumns.BASE.filter { it !in MUTABLE_HISTORY_COLUMNS }
        val byBase = HashMap<List<String>, MutableList<Int>>()
        val kept = ArrayList<HistoryObservation>()
        val matches = ArrayList<RowConflict>()
        for (imp in ordered) for (o in imp.observations) {
            val baseKey = baseImmutable.map { "$it=${o.raw[it] ?: "∅"}" }
            val candidates = byBase[baseKey].orEmpty()
            val hit = candidates.firstOrNull { idx ->
                val c = kept[idx]
                c.provenance.importId != o.provenance.importId &&
                    (c.raw.keys intersect o.raw.keys).filter { it !in MUTABLE_HISTORY_COLUMNS && it !in NOT_COMPARED_HISTORY_COLUMNS }.all { c.raw[it] == o.raw[it] }
            }
            if (hit == null) {
                byBase.getOrPut(baseKey) { ArrayList() } += kept.size
                kept += o
                continue
            }
            val c = kept[hit]
            val diffs = MUTABLE_HISTORY_COLUMNS.filter { it in c.raw && it in o.raw && c.raw[it] != o.raw[it] }
                .associateWith { c.raw[it] to o.raw[it] }
            val schemaDiffers = (c.raw.keys - NOT_COMPARED_HISTORY_COLUMNS) != (o.raw.keys - NOT_COMPARED_HISTORY_COLUMNS)
            if (diffs.isEmpty()) matches += RowConflict(c.provenance, o.provenance, RowMatch.EXACT, emptyMap(), schemaDiffers)
            else {
                matches += RowConflict(c.provenance, o.provenance, RowMatch.REVISION, diffs, schemaDiffers)
                kept[hit] = withoutConflicting(c, o, diffs.keys)
            }
        }
        kept.sortWith(compareBy<HistoryObservation>({ it.time.instantOrNull ?: Instant.MAX }, { it.time.raw },
            { ordered.indexOfFirst { i -> i.importId == it.provenance.importId } }, { it.provenance.startLine }))
        val first = ordered.first()
        val merged = first.copy(
            importId = "merged",
            fileName = ordered.joinToString(" + ") { it.fileName },
            observations = kept,
            dataRows = ordered.sumOf { it.dataRows },
            rejectedRows = ordered.sumOf { it.rejectedRows },
            issues = ordered.flatMap { it.issues },
            uninterpretedColumns = ordered.flatMap { it.uninterpretedColumns }.distinct().sorted(),
            zone = zone ?: first.zone
        )
        return MergedHistory(merged, ordered.map { it.importId }, matches, ordered.sumOf { it.observations.size })
    }

    /**
     * Valor desconocido en los campos mutables en conflicto (el texto original de cada archivo sigue en `raw`).
     * Perfil local (merge/4): ICdetector guarda como mucho una evaluación por fila y nunca la reemplaza, así que si
     * solo una de las dos versiones la tiene, se conserva esa (la otra se exportó antes de evaluarse). Si las dos la
     * tienen y difieren, queda desconocida como cualquier otro conflicto.
     */
    private fun withoutConflicting(o: HistoryObservation, other: HistoryObservation, cols: Set<String>): HistoryObservation {
        val coords = "Lat" in cols || "Lon" in cols
        val profileConflict = cols.any { it in HistoryColumns.PROFILE }
        val profile = when {
            !profileConflict -> o.profile
            o.profile == null -> other.profile
            other.profile == null -> o.profile
            else -> null
        }
        return o.copy(
            verified = if ("Verified" in cols) null else o.verified,
            apiLat = if ("ApiLat" in cols || "ApiLon" in cols) null else o.apiLat,
            apiLon = if ("ApiLat" in cols || "ApiLon" in cols) null else o.apiLon,
            lat = if (coords) null else o.lat,
            lon = if (coords) null else o.lon,
            gpsAccuracyM = if (coords || "GpsAccuracyM" in cols) null else o.gpsAccuracyM,
            taMeters = if ("TAMeters" in cols) null else o.taMeters,
            profile = profile
        )
    }

    private fun contentHash(i: HistoryImport): String =
        Hashing.sha256Hex(i.observations.joinToString("\n") { o -> o.raw.toSortedMap().entries.joinToString("\u0001") { "${it.key}=${it.value}" } }.toByteArray())
}

// ───────────────────────────── casos forenses ─────────────────────────────

/** Relación entre versiones exportadas de un mismo caso (mismo código). */
enum class VersionRelation {
    /** Una sola versión. */
    SINGLE,
    /** Mismo contenido canónico (reexportación). */
    EQUIVALENT,
    /** Una versión contiene a las demás y añade contenido sin contradecirlas (versión ampliada o actualizada). */
    EXTENDED,
    /** Registros con la misma clave y valores distintos, o metadatos esenciales distintos. */
    CONTRADICTORY,
    /** Contenidos distintos sin contradicción y sin que ninguna contenga a las demás. */
    DIVERGENT
}

/** Diferencia concreta entre dos versiones (o dentro de una), con la importación de cada valor. */
data class VersionConflict(
    /** Tabla o campo: "samples", "cells", "heuristics", "capabilities", o un metadato ("state", "confirmed"…). */
    val field: String,
    /** Clave del registro (hora, papel…), o null para un metadato. */
    val key: String?,
    val a: String, val aImport: String,
    val b: String, val bImport: String
)

data class ForensicVersionGroup(
    val caseCode: String?,
    val relation: VersionRelation,
    /** Importaciones usadas en el análisis: una si hay una versión elegible, todas si hay conflicto. */
    val used: List<String>,
    /** Pares (versión usada, versión equivalente o contenida en ella): se conservan pero no se cuentan dos veces. */
    val redundant: List<Pair<String, String>>,
    val all: List<String>,
    /** Conflictos entre versiones (registros o metadatos incompatibles), como máximo [ForensicVersions.MAX_CONFLICTS]. */
    val conflicts: List<VersionConflict> = emptyList(),
    /**
     * Conflictos internos de cada versión (importación → conflictos): registros con la misma clave y valores
     * distintos ([VersionConflict.a] y [VersionConflict.b]), y `sampleCount` (a) distinto de las muestras distintas (b).
     */
    val internal: Map<String, List<VersionConflict>> = emptyMap()
)

object ForensicVersions {
    const val MAX_CONFLICTS = 20

    data class Version(val importId: String, val fileName: String, val sha256: String, val data: ForensicCaseData)

    /** Estados de ICdetector en orden de avance; READY e INTERRUPTED son finales (`ForensicCaseState`, referencia). */
    private val STATE_RANK = mapOf("CAPTURING" to 0, "POST_CAPTURE" to 1, "READY" to 2, "INTERRUPTED" to 2)
    private val FINAL_STATES = setOf("READY", "INTERRUPTED")

    /**
     * Representación canónica documentada de un caso (merge/3, docs/forensic-versions.md): sin procedencia
     * (archivo, línea, importación). Las filas repetidas con la misma clave y el mismo valor se cuentan una vez.
     * - [essential]: deben coincidir: código (sin el prefijo de observación «OBS-»), identidad, origen, creación.
     * - [progress]: pueden avanzar en una versión ampliada: estado, confirmado, fase, nº de muestras, actualización, cierre, código.
     * - [tables]: contenido por clave. Muestras por (hora, ms transcurridos); celdas por (hora, papel, identidad o
     *   radio/ARFCN/PCI); diagnósticos por (hora, id); capacidades por hora (con todos sus valores);
     *   terminal: cada línea es su propia clave (ICdetector la deduplica y no la fecha).
     */
    data class Canonical(
        val essential: List<Any?>,
        val progress: Progress,
        val tables: Map<String, Map<String, List<Any?>>>,
        val internal: List<VersionConflict>
    )

    data class Progress(
        val code: String?, val state: String?, val confirmed: Boolean?, val phase: Long?, val sampleCount: Long?,
        val updatedAt: String?, val closedAt: String?
    )

    fun groupCode(code: String?): String? = code?.replace("ICD-OBS-", "ICD-")

    fun canonical(d: ForensicCaseData): Canonical {
        val internal = ArrayList<VersionConflict>()
        fun <T> table(name: String, rows: List<T>, key: (T) -> String, value: (T) -> List<Any?>): Map<String, List<Any?>> {
            val m = java.util.TreeMap<String, List<Any?>>()
            for (r in rows) {
                val k = key(r); val v = value(r); val prev = m[k]
                if (prev == null) m[k] = v else if (prev != v) internal += VersionConflict(name, k, prev.joinToString("|"), "", v.joinToString("|"), "")
            }
            return m
        }
        val meta = d.meta
        val samples = table("samples", d.samples, { "${it.time}|${it.elapsedMs}" }, { listOf(it.event, it.phase, it.score, it.anomalyConfidence, it.identity?.joinKey, it.verified, it.latencyState, it.lat, it.lon, it.accuracyM, it.reason) })
        if (meta.sampleCount != null && meta.sampleCount != samples.size.toLong()) internal += VersionConflict("sampleCount", null, "${meta.sampleCount}", "", "${samples.size}", "")
        return Canonical(
            listOf(groupCode(meta.caseCode), meta.cellIdentity?.joinKey, meta.captureOrigin, meta.createdAt?.raw),
            Progress(meta.caseCode, meta.state, meta.confirmed, meta.highestPhase, meta.sampleCount, meta.updatedAt?.raw, meta.closedAt?.raw),
            mapOf(
                "samples" to samples,
                "cells" to table("cells", d.cells, { "${it.time}|${it.versionRole}|${it.identity?.joinKey ?: "${it.radio}/${it.arfcn}/${it.pci}"}" }, { listOf(it.dbm, it.pci, it.arfcn, it.band, it.rsrq, it.sinr, it.ta, it.taUnit) }),
                "heuristics" to table("heuristics", d.heuristics, { "${it.time}|${it.id}" }, { listOf(it.name, it.status, it.explanation) }),
                "capabilities" to table("capabilities", d.capabilities, { it.time?.toString() ?: it.timeRaw ?: "?" }, { it.values.entries.map { e -> "${e.key}=${e.value}" } }),
                "terminal" to table("terminal", d.terminal, { it }, { emptyList() })
            ),
            internal
        )
    }

    /** Registros con la misma clave y distinto valor, y metadatos esenciales distintos. */
    private fun contradictions(a: Canonical, aId: String, b: Canonical, bId: String): List<VersionConflict> {
        val out = ArrayList<VersionConflict>()
        val names = listOf("code", "cellIdentity", "captureOrigin", "createdAt")
        a.essential.indices.filter { a.essential[it] != b.essential[it] }.forEach { out += VersionConflict(names[it], null, "${a.essential[it]}", aId, "${b.essential[it]}", bId) }
        for ((t, ma) in a.tables) {
            val mb = b.tables.getValue(t)
            for (k in ma.keys) { val vb = mb[k] ?: continue; if (ma[k] != vb) out += VersionConflict(t, k, ma[k]!!.joinToString("|"), aId, vb.joinToString("|"), bId) }
        }
        return out
    }

    private fun contains(big: Canonical, small: Canonical): Boolean =
        small.tables.all { (t, ms) -> big.tables.getValue(t).keys.containsAll(ms.keys) }

    private fun sameContent(a: Canonical, b: Canonical) = a.tables == b.tables

    /**
     * ¿Puede [newer] ser la evolución de [older]? Reglas explícitas (D-043), tomadas de cómo ICdetector actualiza
     * un caso: estado CAPTURING → POST_CAPTURE → READY/INTERRUPTED (finales); confirmado y fase solo suben;
     * nº de muestras no baja; actualización no retrocede; el cierre se fija una vez y un caso cerrado no añade
     * contenido; el código puede pasar de ICD-OBS- a ICD-, no al revés. Devuelve los conflictos (vacío = compatible).
     */
    fun progression(older: Canonical, oId: String, newer: Canonical, nId: String): List<VersionConflict> {
        val o = older.progress; val n = newer.progress; val out = ArrayList<VersionConflict>()
        fun c(f: String, a: Any?, b: Any?) { out += VersionConflict(f, null, "$a", oId, "$b", nId) }
        val ro = STATE_RANK[o.state]; val rn = STATE_RANK[n.state]
        if (ro == null || rn == null || rn < ro || (o.state in FINAL_STATES && n.state != o.state)) c("state", o.state, n.state)
        if (o.confirmed == true && n.confirmed != true) c("confirmed", o.confirmed, n.confirmed)
        if (o.phase != null && (n.phase == null || n.phase < o.phase)) c("highestPhase", o.phase, n.phase)
        if (o.sampleCount != null && (n.sampleCount == null || n.sampleCount < o.sampleCount)) c("sampleCount", o.sampleCount, n.sampleCount)
        if (o.updatedAt != null && (n.updatedAt == null || n.updatedAt < o.updatedAt)) c("updatedAt", o.updatedAt, n.updatedAt)
        if (o.closedAt != null && n.closedAt != o.closedAt) c("closedAt", o.closedAt, n.closedAt)
        if (o.closedAt != null && !sameContent(older, newer)) c("contentAfterClosure", o.closedAt, n.updatedAt)
        if (o.code != n.code && !(o.code?.startsWith("ICD-OBS-") == true && groupCode(o.code) == n.code)) c("code", o.code, n.code)
        return out
    }

    /**
     * Agrupa por código de caso y clasifica. Determinista: el resultado no depende del orden de importación ni de
     * los identificadores internos; la huella solo ordena la lista de versiones, nunca decide cuál se usa.
     */
    fun group(versions: List<Version>): List<ForensicVersionGroup> =
        versions.groupBy { groupCode(it.data.meta.caseCode) ?: "\u0000${it.sha256}" }.toSortedMap().map { (key, vs0) ->
            val code = groupCode(vs0.first().data.meta.caseCode)
            val vs = vs0.sortedBy { it.sha256 }
            val canon = vs.associate { it.importId to canonical(it.data) }
            val internal = canon.filterValues { it.internal.isNotEmpty() }.mapValues { (id, c) -> c.internal.take(MAX_CONFLICTS).map { it.copy(aImport = id, bImport = id) } }
            if (vs.size == 1) return@map ForensicVersionGroup(code, VersionRelation.SINGLE, listOf(vs[0].importId), emptyList(), listOf(vs[0].importId), internal = internal)
            // clases de equivalencia: mismo contenido Y mismos metadatos (incluidos los que avanzan)
            val classes = ArrayList<MutableList<Version>>()
            for (v in vs) {
                val c = canon.getValue(v.importId)
                classes.firstOrNull { canon.getValue(it[0].importId) == c }?.add(v) ?: classes.add(mutableListOf(v))
            }
            val reps = classes.map { it[0] }
            val redundantEq = classes.flatMap { cl -> cl.drop(1).map { cl[0].importId to it.importId } }
            val all = vs.map { it.importId }
            if (reps.size == 1) return@map ForensicVersionGroup(code, VersionRelation.EQUIVALENT, listOf(reps[0].importId), redundantEq, all, internal = internal)
            val pairs = reps.indices.flatMap { i -> (i + 1 until reps.size).map { j -> reps[i] to reps[j] } }
            val records = pairs.flatMap { (a, b) -> contradictions(canon.getValue(a.importId), a.importId, canon.getValue(b.importId), b.importId) }
            if (records.isNotEmpty()) return@map ForensicVersionGroup(code, VersionRelation.CONTRADICTORY, reps.map { it.importId }, redundantEq, all,
                records.take(MAX_CONFLICTS), internal)
            // candidata a versión ampliada: contiene a todas y todas pueden evolucionar hacia ella
            val candidates = reps.filter { r -> reps.all { o -> o === r || contains(canon.getValue(r.importId), canon.getValue(o.importId)) } }
            val valid = candidates.filter { r -> reps.all { o -> o === r || progression(canon.getValue(o.importId), o.importId, canon.getValue(r.importId), r.importId).isEmpty() } }
            if (valid.size == 1) {
                val sup = valid.single()
                return@map ForensicVersionGroup(code, VersionRelation.EXTENDED, listOf(sup.importId),
                    redundantEq + reps.filter { it !== sup }.map { sup.importId to it.importId }, all,
                    internal = internal)
            }
            if (candidates.isNotEmpty()) {
                // contiene el contenido, pero los metadatos no permiten decidir cuál es posterior: no se elige por huella
                val meta = candidates.flatMap { r -> reps.filter { it !== r }.flatMap { o -> progression(canon.getValue(o.importId), o.importId, canon.getValue(r.importId), r.importId) } }
                return@map ForensicVersionGroup(code, VersionRelation.CONTRADICTORY, reps.map { it.importId }, redundantEq, all,
                    meta.distinct().take(MAX_CONFLICTS), internal)
            }
            ForensicVersionGroup(code, VersionRelation.DIVERGENT, reps.map { it.importId }, redundantEq, all,
                internal = internal)
        }
}
