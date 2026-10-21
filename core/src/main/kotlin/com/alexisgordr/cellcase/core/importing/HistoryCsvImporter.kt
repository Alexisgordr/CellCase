package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.model.HeaderIndex
import com.alexisgordr.cellcase.core.model.RowValues
import com.alexisgordr.cellcase.core.model.StringPool
import com.alexisgordr.cellcase.core.i18n.msg
import com.alexisgordr.cellcase.core.i18n.plus

import com.alexisgordr.cellcase.core.csv.CsvFormatException
import com.alexisgordr.cellcase.core.csv.CsvGuard
import com.alexisgordr.cellcase.core.csv.CsvReader
import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Issue
import com.alexisgordr.cellcase.core.model.IssueCollector
import com.alexisgordr.cellcase.core.model.IssueExample
import com.alexisgordr.cellcase.core.model.Provenance
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.SecondaryCarrier
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.model.ValueState
import com.alexisgordr.cellcase.core.time.CaptureTime
import com.alexisgordr.cellcase.core.time.DeclaredZone
import com.alexisgordr.cellcase.core.time.TimeResolution
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Versión del lector. Se guarda con cada importación: el análisis se reproduce con el lector registrado. */
const val HISTORY_READER_VERSION = "history-csv/1.2.0"

/** Columnas del historial de ICdetector 2.10.5 (export-formats.md §1.3). */
object HistoryColumns {
    /** Columnas presentes desde v2.1. Todas obligatorias. */
    val BASE = listOf(
        "Timestamp", "NetType", "CID", "MNC", "TAC", "MCC", "DBM", "Verified", "SecurityScore",
        "FailedHeuristics", "Lat", "Lon", "PCI", "ARFCN", "RSRQ", "SINR", "AnomalyConfidence",
        "ApiLat", "ApiLon", "TA", "TAUnit", "TAMeters", "Radio"
    )

    /** Contexto de radio añadido en v2.10.4. Presente en toda exportación de 2.10.5. */
    val RADIO_CONTEXT = listOf(
        "ServingConnection", "BandwidthKHz", "Bands", "AdditionalPlmns", "CsgIndicator",
        "CsgIdentity", "CsgName", "SecondaryCarriers", "ServiceState", "NetworkOperator",
        "SimOperator", "NetworkRoaming"
    )

    /**
     * Añadidas en ICdetector 3.0, al final (export-formats.md §1.8): instante UTC, cobertura de reglas, precisión
     * del GPS, versión de la app y teléfono de la exportación, modo de localización, perfil local e identificadores.
     */
    val V3 = listOf(
        "ObservedAtUtc", "NotEvaluatedHeuristics", "GpsAccuracyM", "AppVersion", "ExportDevice", "ExportAndroid", "LocationMode",
        "ProfileMode", "ProfileLevel", "ProfileScore", "ProfileCoverage", "ProfileAbstainReason", "ProfileAlgorithm",
        "ProfileRevision", "ProfileEvidence", "ProfileLearned", "ProfileHoldout", "RowId", "ObservationId"
    )

    /**
     * Añadidas en ICdetector 3.1.0, al final: churn de celdas de 15 min de H17, en sombra (docs/history-csv-columns.md
     * de ICdetector). Vacías en las filas grabadas antes de 3.1.0.
     */
    val V31 = listOf("ChurnCells15m", "ChurnChanges15m", "ChurnMotion")

    /** Valores de `ChurnMotion` (`ChurnMotion` de ICdetector 3.1.0). */
    val CHURN_MOTIONS = setOf("STILL", "MOVING", "UNKNOWN")

    /** Columnas del perfil local (3.0). Pueden rellenarse después de grabarse la fila (evaluaciones reservadas). */
    val PROFILE = V3.filter { it.startsWith("Profile") }

    /** Constantes de toda la exportación, no de la fila: no se comparan entre archivos (merge/4). */
    val PER_EXPORT = setOf("ExportDevice", "ExportAndroid")

    val ALL = BASE + RADIO_CONTEXT + V3 + V31
}

enum class HistorySchema(val label: Msg, val supported: Boolean) {
    V2_10_4_PLUS(msg("import.supported.icdetector_2_10_4_o"), true),
    V2_1_TO_2_10_3(msg("import.supported.icdetector_2_12_10_3"), true),
    PARTIAL_RADIO_CONTEXT(msg("import.supported.contexto_de_radio_incompleto"), true),
    /** ICdetector 3.0: todas las columnas nuevas. Los textos llevan el apóstrofo protector (§0.2). */
    V3_0(msg("import.supported.icdetector_3_0"), true),
    /** Algunas columnas de 3.0 o de 3.1.0, no todas (archivo editado o versión intermedia). */
    PARTIAL_V3(msg("import.supported.v3_partial"), true),
    /** ICdetector 3.1.0: las columnas de 3.0 y las tres del churn de H17 (57). */
    V3_1(msg("import.supported.icdetector_3_1"), true);

    /** Escrito por ICdetector 3.0 o posterior: los textos pueden llevar el apóstrofo protector. */
    val isV3: Boolean get() = this == V3_0 || this == PARTIAL_V3 || this == V3_1
}

data class HistoryImportLimits(
    val maxRecords: Int = ImportLimits.MAX_TABLE_ROWS,
    val maxFieldChars: Int = ImportLimits.MAX_FIELD_CHARS
)

data class HistoryImport(
    val importId: String,
    val fileName: String,
    val readerVersion: String,
    val schema: HistorySchema,
    val header: List<String>,
    val uninterpretedColumns: List<String>,
    val observations: List<HistoryObservation>,
    /** Filas de datos leídas del archivo (incluidas las rechazadas). */
    val dataRows: Int,
    /** Filas descartadas por estructura (número de campos incorrecto). Constan en [issues]. */
    val rejectedRows: Int,
    val issues: List<Issue>,
    val zone: DeclaredZone?,
    /** Teléfono y Android que hicieron la exportación (3.0, `ExportDevice`/`ExportAndroid`); null si no consta. */
    val exportDevice: String? = null,
    val exportAndroid: String? = null
)

/**
 * Lector del historial CSV de ICdetector. Código propio a partir de `docs/export-formats.md`.
 *
 * Reglas (D-012, D-016):
 * - Compatibilidad por **nombres de columna**, no por número. Faltar una columna base es un error.
 * - Columnas desconocidas: se conservan como datos no interpretados.
 * - Cada valor conserva su texto original; los centinelas y valores fuera de rango quedan ausentes en
 *   la versión normalizada y se registran como incidencia.
 * - No se corrige ni se borra nada: las contradicciones se señalan.
 */
class HistoryCsvImporter(private val limits: HistoryImportLimits = HistoryImportLimits()) {

    fun import(
        input: InputStream,
        fileName: String,
        importId: String,
        zone: DeclaredZone?,
        checkpoint: Checkpoint = Checkpoint.NONE,
        progress: (rows: Int) -> Unit = {}
    ): HistoryImport {
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val reader = CsvReader(InputStreamReader(input, decoder).buffered(), maxFieldChars = limits.maxFieldChars)
        val issues = IssueCollector()
        try {
            val headerRec = reader.next() ?: throw ImportRejected(msg("import.fail.el_archivo_esta_vacio"))
            var header = headerRec.fields
            if (header.isNotEmpty() && header[0].startsWith("﻿")) {
                header = listOf(header[0].removePrefix("﻿")) + header.drop(1)
                issues.add("BOM", Severity.INFO, null, msg("import.import.el_archivo_empieza_con_marca"))
            }
            val schema = checkHeader(header, issues)
            val headerIndex = HeaderIndex(header)
            val index = headerIndex.index
            // Columnas de valores casi únicos: no se comparten (D-041, memoria)
            val unique = setOf("Timestamp", "Lat", "Lon", "ApiLat", "ApiLon", "AnomalyConfidence").mapNotNull { index[it] }.toSet()
            val pool = StringPool()
            val identities = HashMap<CellIdentity, CellIdentity>()
            val provName = fileName
            val uninterpreted = header.filter { it !in HistoryColumns.ALL }
            val observations = ArrayList<HistoryObservation>()
            val exportDevice = PerExport(); val exportAndroid = PerExport()
            var dataRows = 0
            var rejected = 0
            while (true) {
                if (dataRows % 256 == 0) { checkpoint.check(); progress(dataRows) }
                val rec = reader.next() ?: break
                dataRows++
                if (dataRows > limits.maxRecords) throw ImportRejected(msg("import.import.el_archivo_supera_filas", limits.maxRecords))
                val prov = Provenance(importId, provName, null, rec.startLine, rec.endLine, rec.index - 1)
                if (rec.fields.size != header.size) {
                    rejected++
                    issues.add("ROW_FIELD_COUNT", Severity.ERROR, null,
                        msg("import.import.fila_con_un_numero_de", header.size),
                        IssueExample(prov, null, "${rec.fields.size} campos"))
                    continue
                }
                if (rec.strayQuote) {
                    issues.add("STRAY_QUOTE", Severity.WARNING, null,
                        msg("import.import.comilla_fuera_de_un_campo"), IssueExample(prov, null, null))
                }
                val fields = rec.fields.mapIndexed { i, v0 ->
                    val v = if (schema.isV3) CsvGuard.unguard(v0) else v0
                    if (v !== v0) issues.add("TEXT_GUARD_REMOVED", Severity.INFO, header[i], msg("import.guard.removed"), IssueExample(prov, header[i], v0))
                    if (i in unique) v else pool.of(v)
                }
                exportDevice.see(fields, index["ExportDevice"]); exportAndroid.see(fields, index["ExportAndroid"])
                observations += parseRow(fields, headerIndex, uninterpreted, prov, zone, issues, identities)
            }
            progress(dataRows)
            if (dataRows == 0) issues.add("NO_DATA_ROWS", Severity.WARNING, null, msg("import.import.el_archivo_solo_contiene_la"))
            checkDuplicates(observations, issues)
            if (exportDevice.varies || exportAndroid.varies) issues.add("EXPORT_DEVICE_VARIES", Severity.WARNING, "ExportDevice",
                msg("import.export_device.varies"))
            return HistoryImport(importId, fileName, HISTORY_READER_VERSION, schema, header, uninterpreted,
                observations, dataRows, rejected, issues.result(), zone, exportDevice.value, exportAndroid.value)
        } catch (e: CharacterCodingException) {
            throw ImportRejected(msg("import.import.el_archivo_no_esta_codificado"), RejectKind.READ_ERROR)
        } catch (e: CsvFormatException) {
            throw ImportRejected(msg("import.import.csv_mal_formado_en_la", e.line, e.message), RejectKind.READ_ERROR)
        }
    }

    private fun checkHeader(header: List<String>, issues: IssueCollector): HistorySchema {
        val dupes = header.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dupes.isNotEmpty()) throw ImportRejected(msg("import.checkHeader.cabecera_con_columnas_repetidas", dupes.joinToString()))
        val missingBase = HistoryColumns.BASE.filter { it !in header }
        if (missingBase.isNotEmpty()) {
            throw ImportRejected(
                msg("import.checkHeader.no_es_un_historial_compatible", missingBase.joinToString()) +
                    msg("import.checkHeader.los_historiales_anteriores_a_icdetector")
            )
        }
        val presentCtx = HistoryColumns.RADIO_CONTEXT.filter { it in header }
        val presentV3 = HistoryColumns.V3.filter { it in header }
        val presentV31 = HistoryColumns.V31.filter { it in header }
        val completeV3 = presentV3.size == HistoryColumns.V3.size && presentCtx.size == HistoryColumns.RADIO_CONTEXT.size
        val schema = when {
            completeV3 && presentV31.size == HistoryColumns.V31.size -> HistorySchema.V3_1
            completeV3 && presentV31.isEmpty() -> HistorySchema.V3_0
            presentV31.isNotEmpty() -> HistorySchema.PARTIAL_V3
            presentV3.isNotEmpty() -> HistorySchema.PARTIAL_V3
            presentCtx.size == HistoryColumns.RADIO_CONTEXT.size -> HistorySchema.V2_10_4_PLUS
            presentCtx.isEmpty() -> HistorySchema.V2_1_TO_2_10_3
            else -> HistorySchema.PARTIAL_RADIO_CONTEXT
        }
        when (schema) {
            HistorySchema.V2_1_TO_2_10_3 -> issues.add("SCHEMA_OLDER", Severity.WARNING, null,
                msg("import.checkHeader.historial_sin_columnas_de_contexto") +
                    msg("import.checkHeader.las_columnas_comunes_se_leen"))
            HistorySchema.PARTIAL_RADIO_CONTEXT -> issues.add("SCHEMA_PARTIAL", Severity.WARNING, null,
                msg("import.checkHeader.solo_de_columnas_de_contexto", presentCtx.size, HistoryColumns.RADIO_CONTEXT.size) +
                    HistoryColumns.RADIO_CONTEXT.filter { it !in header }.joinToString() + ".")
            HistorySchema.PARTIAL_V3 -> issues.add("SCHEMA_PARTIAL_V3", Severity.WARNING, null,
                msg("import.checkHeader.v3_partial", presentV3.size + presentV31.size,
                    HistoryColumns.V3.size + if (presentV31.isEmpty()) 0 else HistoryColumns.V31.size,
                    (HistoryColumns.V3 + HistoryColumns.RADIO_CONTEXT + if (presentV31.isEmpty()) emptyList() else HistoryColumns.V31)
                        .filter { it !in header }.joinToString()))
            else -> {}
        }
        val extra = header.filter { it !in HistoryColumns.ALL }
        if (extra.isNotEmpty()) issues.add("EXTRA_COLUMNS", Severity.INFO, null,
            msg("import.checkHeader.columnas_no_interpretadas_conservadas_tal", extra.joinToString()))
        val expectedOrder = HistoryColumns.ALL.filter { it in header }
        if (header.filter { it in HistoryColumns.ALL } != expectedOrder) issues.add("COLUMN_ORDER", Severity.INFO, null,
            msg("import.checkHeader.las_columnas_no_estan_en"))
        return schema
    }

    // ───────────────────────── lectura de una fila ─────────────────────────

    private class Row(
        val fields: List<String>,
        val index: Map<String, Int>,
        val prov: Provenance,
        val issues: IssueCollector
    ) {
        val states = HashMap<String, ValueState>()
        fun raw(col: String): String? = index[col]?.let { fields[it] }

        fun mark(col: String, state: ValueState, code: String, severity: Severity, message: Msg) {
            states[col] = state
            issues.add(code, severity, col, message, IssueExample(prov, col, raw(col)))
        }

        fun empty(col: String): Boolean {
            val r = raw(col)
            if (r == null || r.isEmpty()) { if (r != null) states[col] = ValueState.EMPTY; return true }
            return false
        }
    }

    // Expresiones compiladas una vez (antes se compilaban en cada fila).
    private val RE_MCC = Regex("\\d{3}")
    private val RE_MNC = Regex("\\d{2,3}")
    private val RE_TAC = Regex("\\d{1,10}")
    private val RE_CID = Regex("\\d{1,15}")
    private val RE_PLMN = Regex("\\d{5,6}")

    private fun parseRow(
        f: List<String>, headerIndex: HeaderIndex, uninterpretedCols: List<String>,
        prov: Provenance, zone: DeclaredZone?, issues: IssueCollector, identities: MutableMap<CellIdentity, CellIdentity>
    ): HistoryObservation {
        val index = headerIndex.index
        val r = Row(f, index, prov, issues)

        // Hora local sin zona
        val tsRaw = r.raw("Timestamp") ?: ""
        val utc = observedAtUtc(r)
        val time = withUtc(CaptureTime.parseLocalSeconds(tsRaw, zone), utc, zone, r)
        if (!time.isValid) {
            r.mark("Timestamp", ValueState.INVALID, "TIME_INVALID", Severity.ERROR,
                msg("import.parseRow.hora_que_no_sigue_el"))
        } else if (utc == null) when (time.resolution) {
            is TimeResolution.Ambiguous -> issues.add("TIME_AMBIGUOUS", Severity.WARNING, "Timestamp",
                msg("import.parseRow.hora_local_repetida_por_el"),
                IssueExample(prov, "Timestamp", tsRaw))
            is TimeResolution.Nonexistent -> issues.add("TIME_NONEXISTENT", Severity.WARNING, "Timestamp",
                msg("import.parseRow.hora_local_que_no_existio"),
                IssueExample(prov, "Timestamp", tsRaw))
            else -> {}
        }

        val radio = enumOrNull<RadioTech>(r, "Radio", required = true) ?: RadioTech.UNKNOWN
        if (radio == RadioTech.UNKNOWN && r.raw("Radio") == "UNKNOWN") {
            issues.add("RADIO_UNKNOWN", Severity.WARNING, "Radio",
                msg("import.parseRow.tecnologia_desconocida_la_identidad_no"), IssueExample(prov, "Radio", "UNKNOWN"))
        }

        val identity = CellIdentity(
            mcc = idPart(r, "MCC", RE_MCC),
            mnc = idPart(r, "MNC", RE_MNC),
            tac = idPart(r, "TAC", RE_TAC),
            cid = idPart(r, "CID", RE_CID),
            radio = radio
        )

        val dbm = int(r, "DBM", sentinels = setOf(INT_MAX, -999), range = -160..-20)
        val pci = int(r, "PCI", sentinels = setOf(INT_MAX), range = pciRange(radio))
        val arfcn = int(r, "ARFCN", sentinels = setOf(INT_MAX), range = arfcnRange(radio))
        val rsrq = int(r, "RSRQ", sentinels = setOf(INT_MAX), range = -43..20)
        val sinr = int(r, "SINR", sentinels = setOf(INT_MAX), range = -30..50)
        val score = int(r, "SecurityScore", sentinels = emptySet(), range = 0..100)
        val conf = double(r, "AnomalyConfidence", -0.0001, 95.0001)
        val lat = double(r, "Lat", -90.0, 90.0)
        val lon = double(r, "Lon", -180.0, 180.0)
        val apiLat = double(r, "ApiLat", -90.0, 90.0)
        val apiLon = double(r, "ApiLon", -180.0, 180.0)
        if ((lat == null) != (lon == null) && r.stateOk("Lat") && r.stateOk("Lon")) {
            issues.add("COORD_HALF", Severity.WARNING, "Lat/Lon", msg("import.parseRow.solo_una_de_las_dos"), IssueExample(prov, "Lat/Lon", "${r.raw("Lat")}/${r.raw("Lon")}"))
        }
        val ta = int(r, "TA", sentinels = setOf(INT_MAX), range = 0..100_000)
        val taUnit = enumText(r, "TAUnit", setOf("LTE_INDEX", "GSM_INDEX", "NR_RAW", "STUB_ZERO", "UNKNOWN"))
        val taMeters = int(r, "TAMeters", sentinels = emptySet(), range = 0..10_000_000)
        checkTaCoherence(r, ta, taUnit, taMeters)

        val verified = enumText(r, "Verified", setOf("PENDING", "VERIFIED", "NOT_FOUND", "REJECTED", "ERROR"))
        val failed = r.raw("FailedHeuristics")?.takeIf { it.isNotEmpty() }.also { if (it == null) r.states["FailedHeuristics"] = ValueState.EMPTY }
        if (score != null && score < 100 && (failed == null || failed.trim() == "OK")) {
            issues.add("COLLECTOR_SCORE_REASON", Severity.WARNING, "SecurityScore",
                msg("import.parseRow.puntuacion_del_colector_inferior_a"),
                IssueExample(prov, "SecurityScore", "${score} / ${failed ?: ""}"))
        }

        val netType = r.raw("NetType")?.takeIf { it.isNotEmpty() }

        // Contexto de radio (v2.10.4+). Vacío = no se recogía, no «no había».
        val serving = enumText(r, "ServingConnection", setOf("PRIMARY_SERVING", "SECONDARY_SERVING", "NONE", "UNKNOWN"))
        val bandwidth = int(r, "BandwidthKHz", sentinels = setOf(INT_MAX), range = 1..1_000_000)
        val bands = intList(r, "Bands")
        val plmns = r.raw("AdditionalPlmns")?.takeIf { it.isNotEmpty() }?.split(';')
        if (r.raw("AdditionalPlmns")?.isEmpty() == true) r.states["AdditionalPlmns"] = ValueState.EMPTY
        val csgInd = bool01(r, "CsgIndicator")
        val csgId = r.raw("CsgIdentity")?.let { raw ->
            if (raw.isEmpty()) { r.states["CsgIdentity"] = ValueState.EMPTY; null }
            else raw.toLongOrNull() ?: run { r.mark("CsgIdentity", ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.parseRow.valor_no_numerico")); null }
        }
        val csgName = r.raw("CsgName")?.takeIf { it.isNotEmpty() }
        val secondary = secondaryCarriers(r)
        val serviceState = enumText(r, "ServiceState", setOf("IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF", "UNKNOWN"))
        val netOp = plmn(r, "NetworkOperator")
        val simOp = plmn(r, "SimOperator")
        val roaming = bool01(r, "NetworkRoaming")

        val rawMap = RowValues(headerIndex, f.toTypedArray())
        val unint = if (uninterpretedCols.isEmpty()) emptyMap() else uninterpretedCols.associateWith { f[index.getValue(it)] }

        val profile = profile(r)
        val rowId = r.raw("RowId")?.let { v -> if (v.isEmpty()) { r.states["RowId"] = ValueState.EMPTY; null }
            else v.trim().toLongOrNull()?.takeIf { it > 0 } ?: run { r.mark("RowId", ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.int.valor_no_entero_se_trata")); null } }
        val notEvaluated = r.raw("NotEvaluatedHeuristics")?.takeIf { it.isNotEmpty() }
        val gpsAcc = double(r, "GpsAccuracyM", 0.0, 1_000_000.0)
        val appVersion = r.raw("AppVersion")?.takeIf { it.isNotEmpty() }
        val locationMode = enumText(r, "LocationMode", setOf("CONTINUOUS", "INTELLIGENT", "ADAPTIVE"))
        val observationId = r.raw("ObservationId")?.takeIf { it.isNotEmpty() }
        // Churn de H17 (3.1.0): enteros ≥ 0 y un movimiento conocido. Un valor inválido es un aviso con su
        // procedencia (se trata como sin dato), nunca un motivo para rechazar el archivo.
        val churnCells = int(r, "ChurnCells15m", sentinels = emptySet(), range = 0..Int.MAX_VALUE)
        val churnChanges = int(r, "ChurnChanges15m", sentinels = emptySet(), range = 0..Int.MAX_VALUE)
        val churnMotion = enumText(r, "ChurnMotion", HistoryColumns.CHURN_MOTIONS)

        return HistoryObservation(
            provenance = prov, time = time, identity = identities.getOrPut(identity) { identity }, netTypeLabel = netType, dbm = dbm,
            verified = verified, securityScore = score, failedHeuristics = failed, lat = lat, lon = lon,
            pci = pci, arfcn = arfcn, rsrq = rsrq, sinr = sinr, anomalyConfidence = conf,
            apiLat = apiLat, apiLon = apiLon, timingAdvance = ta, taUnit = taUnit, taMeters = taMeters,
            servingConnection = serving, bandwidthKhz = bandwidth, bands = bands, additionalPlmns = plmns,
            csgIndicator = csgInd, csgIdentity = csgId, csgName = csgName, secondaryCarriers = secondary,
            serviceState = serviceState, networkOperator = netOp, simOperator = simOp, networkRoaming = roaming,
            raw = rawMap, states = if (r.states.isEmpty()) emptyMap() else r.states.toMap(), uninterpreted = unint,
            observedAtUtc = utc, notEvaluatedHeuristics = notEvaluated, gpsAccuracyM = gpsAcc, appVersion = appVersion,
            locationMode = locationMode, profile = profile, rowId = rowId, observationId = observationId,
            churnCells15m = churnCells, churnChanges15m = churnChanges, churnMotion = churnMotion
        )
    }

    /** `ObservedAtUtc` (3.0): instante con milisegundos. Vacío en filas grabadas antes de 3.0 (desconocido). */
    private fun observedAtUtc(r: Row): java.time.Instant? {
        val raw = r.raw("ObservedAtUtc") ?: return null
        if (raw.isEmpty()) { r.states["ObservedAtUtc"] = ValueState.EMPTY; return null }
        return CaptureTime.parseInstant(raw) ?: run {
            r.mark("ObservedAtUtc", ValueState.INVALID, "TIME_UTC_INVALID", Severity.WARNING, msg("import.time.utc_invalid")); null
        }
    }

    /**
     * Con `ObservedAtUtc`, el instante es el que registró ICdetector (truncado al segundo, la resolución de la hora
     * local) y no depende de la zona declarada; el desfase es la diferencia entre la hora local y ese instante.
     * La zona declarada se contrasta: si da otro instante, se avisa (D-053).
     */
    private fun withUtc(t: com.alexisgordr.cellcase.core.time.RecordedTime, utc: java.time.Instant?, zone: DeclaredZone?, r: Row): com.alexisgordr.cellcase.core.time.RecordedTime {
        if (utc == null) return t
        val instant = utc.truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
        val local = t.local
        val diff = local?.let { java.time.Duration.between(instant, it.toInstant(java.time.ZoneOffset.UTC)).seconds }
        val offset = diff?.takeIf { kotlin.math.abs(it) <= 18 * 3600 && it % 60 == 0L }?.let { java.time.ZoneOffset.ofTotalSeconds(it.toInt()) }
        if (local != null && offset == null) r.issues.add("TIME_UTC_OFFSET", Severity.WARNING, "ObservedAtUtc", msg("import.time.utc_offset_odd"),
            IssueExample(r.prov, "ObservedAtUtc", "${r.raw("Timestamp")} / ${r.raw("ObservedAtUtc")}"))
        when (val res = t.resolution) {
            is TimeResolution.Exact -> if (res.instant != instant) r.issues.add("TIME_ZONE_MISMATCH", Severity.WARNING, "ObservedAtUtc",
                msg("import.time.zone_mismatch"), IssueExample(r.prov, "ObservedAtUtc", "${r.raw("Timestamp")} → ${res.instant} / ${r.raw("ObservedAtUtc")}"))
            is TimeResolution.Ambiguous -> if (instant == res.earlier || instant == res.later) r.issues.add("TIME_AMBIGUOUS_RESOLVED", Severity.INFO, "Timestamp",
                msg("import.time.resolved_by_utc"), IssueExample(r.prov, "Timestamp", r.raw("Timestamp")))
                else r.issues.add("TIME_ZONE_MISMATCH", Severity.WARNING, "ObservedAtUtc", msg("import.time.zone_mismatch"),
                    IssueExample(r.prov, "ObservedAtUtc", "${r.raw("Timestamp")} / ${r.raw("ObservedAtUtc")}"))
            is TimeResolution.Nonexistent -> r.issues.add("TIME_ZONE_MISMATCH", Severity.WARNING, "ObservedAtUtc", msg("import.time.zone_mismatch"),
                IssueExample(r.prov, "ObservedAtUtc", "${r.raw("Timestamp")} / ${r.raw("ObservedAtUtc")}"))
            else -> {}
        }
        return t.copy(resolution = TimeResolution.Exact(instant, offset ?: java.time.ZoneOffset.UTC))
    }

    /** Columnas `Profile*` (3.0). Vacías si la fila no se evaluó: entonces no hay evaluación (null). */
    private fun profile(r: Row): com.alexisgordr.cellcase.core.model.ProfileEvaluation? {
        if (HistoryColumns.PROFILE.all { r.raw(it).isNullOrEmpty() }) return null
        val level = enumText(r, "ProfileLevel", com.alexisgordr.cellcase.core.model.ProfileEvaluation.LEVELS.toSet())
        val mode = enumText(r, "ProfileMode", com.alexisgordr.cellcase.core.model.ProfileEvaluation.MODES)
        val score = double(r, "ProfileScore", 0.0, 1_000_000.0)
        val coverage = r.raw("ProfileCoverage")?.takeIf { it.isNotEmpty() }?.let { c ->
            val p = c.split('/').map { it.trim().toIntOrNull() }
            if (p.size == 2 && p[0] != null && p[1] != null && p[0]!! in 0..p[1]!!) p[0]!! to p[1]!!
            else { r.mark("ProfileCoverage", ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.profile.coverage_invalid")); null }
        }
        val abstain = enumText(r, "ProfileAbstainReason", com.alexisgordr.cellcase.core.model.ProfileEvaluation.ABSTAIN_REASONS)
        val algorithm = int(r, "ProfileAlgorithm", sentinels = emptySet(), range = 1..1000)
        return com.alexisgordr.cellcase.core.model.ProfileEvaluation(mode, level, score, coverage?.first, coverage?.second, abstain, algorithm,
            r.raw("ProfileRevision")?.takeIf { it.isNotEmpty() }, r.raw("ProfileEvidence")?.takeIf { it.isNotEmpty() },
            bool01(r, "ProfileLearned"), bool01(r, "ProfileHoldout"))
    }

    /** Valor que debe ser el mismo en todas las filas de una exportación (3.0: teléfono y Android de la exportación). */
    private class PerExport {
        var value: String? = null; private set
        var varies = false; private set
        private var seen = false
        fun see(fields: List<String>, i: Int?) {
            if (i == null) return
            val v = fields[i].takeIf { it.isNotEmpty() }
            if (!seen) { value = v; seen = true } else if (v != value) varies = true
        }
    }

    private fun Row.stateOk(col: String) = (states[col] ?: ValueState.PRESENT) == ValueState.PRESENT || states[col] == ValueState.EMPTY

    private fun idPart(r: Row, col: String, pattern: Regex): String? {
        if (r.empty(col)) {
            r.mark(col, ValueState.EMPTY, "ID_EMPTY", Severity.WARNING, msg("import.idPart.componente_de_identidad_vacio_la"))
            return null
        }
        val v = r.raw(col)!!.trim()
        if (v == "N/A") {
            r.mark(col, ValueState.SENTINEL, "ID_NA", Severity.INFO, msg("import.idPart.componente_de_identidad_n_a"))
            return null
        }
        if (!pattern.matches(v)) {
            r.mark(col, ValueState.INVALID, "ID_INVALID", Severity.WARNING, msg("import.idPart.componente_de_identidad_con_formato"))
            return null
        }
        return v
    }

    private fun int(r: Row, col: String, sentinels: Set<Int>, range: IntRange?): Int? {
        if (r.raw(col) == null || r.empty(col)) return null
        val raw = r.raw(col)!!.trim()
        val v = raw.toIntOrNull() ?: run {
            r.mark(col, ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.int.valor_no_entero_se_trata"))
            return null
        }
        if (v in sentinels) {
            r.mark(col, ValueState.SENTINEL, "SENTINEL", Severity.INFO, msg("import.int.valor_centinela_de_no_disponible", v))
            return null
        }
        if (range != null && v !in range) {
            r.mark(col, ValueState.OUT_OF_RANGE, "OUT_OF_RANGE", Severity.WARNING, msg("import.int.valor_fuera_del_rango_admitido", range.first, range.last))
            return null
        }
        return v
    }

    private fun double(r: Row, col: String, min: Double, max: Double): Double? {
        if (r.raw(col) == null || r.empty(col)) return null
        val v = r.raw(col)!!.trim().toDoubleOrNull()
        if (v == null || v.isNaN() || v.isInfinite()) {
            r.mark(col, ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.double.valor_no_numerico_se_trata"))
            return null
        }
        if (v < min || v > max) {
            r.mark(col, ValueState.OUT_OF_RANGE, "OUT_OF_RANGE", Severity.WARNING, msg("import.double.valor_fuera_del_rango_admitido"))
            return null
        }
        return v
    }

    private inline fun <reified E : Enum<E>> enumOrNull(r: Row, col: String, required: Boolean): E? {
        if (r.raw(col) == null || r.empty(col)) {
            if (required) r.mark(col, ValueState.EMPTY, "VALUE_EMPTY", Severity.WARNING, msg("import.double.campo_obligatorio_vacio"))
            return null
        }
        val raw = r.raw(col)!!.trim()
        return enumValues<E>().firstOrNull { it.name == raw } ?: run {
            r.mark(col, ValueState.INVALID, "ENUM_UNKNOWN", Severity.WARNING, msg("import.double.valor_no_reconocido_se_conserva"))
            null
        }
    }

    private fun enumText(r: Row, col: String, allowed: Set<String>): String? {
        if (r.raw(col) == null || r.empty(col)) return null
        val raw = r.raw(col)!!.trim()
        if (raw !in allowed) {
            r.mark(col, ValueState.INVALID, "ENUM_UNKNOWN", Severity.WARNING, msg("import.double.valor_no_reconocido_se_conserva"))
            return null
        }
        return raw
    }

    private fun bool01(r: Row, col: String): Boolean? {
        if (r.raw(col) == null || r.empty(col)) return null
        return when (r.raw(col)!!.trim()) {
            "1" -> true
            "0" -> false
            else -> { r.mark(col, ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.bool01.se_esperaba_1_o_0")); null }
        }
    }

    private fun intList(r: Row, col: String): List<Int>? {
        if (r.raw(col) == null || r.empty(col)) return null
        val parts = r.raw(col)!!.split(';').map { it.trim().toIntOrNull() }
        if (parts.any { it == null }) {
            r.mark(col, ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.intList.lista_con_elementos_no_numericos"))
            return null
        }
        return parts.filterNotNull()
    }

    private fun plmn(r: Row, col: String): String? {
        if (r.raw(col) == null || r.empty(col)) return null
        val v = r.raw(col)!!.trim()
        if (!RE_PLMN.matches(v)) {
            r.mark(col, ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.plmn.plmn_con_formato_inesperado_se"))
            return null
        }
        return v
    }

    private fun secondaryCarriers(r: Row): List<SecondaryCarrier>? {
        val col = "SecondaryCarriers"
        if (r.raw(col) == null || r.empty(col)) return null
        val out = ArrayList<SecondaryCarrier>()
        for (item in r.raw(col)!!.split(';')) {
            val p = item.split(':')
            val radio = p.getOrNull(0)?.let { n -> RadioTech.values().firstOrNull { it.name == n } }
            if (p.size != 3 || radio == null) {
                r.mark(col, ValueState.INVALID, "VALUE_INVALID", Severity.WARNING, msg("import.secondaryCarriers.portadora_secundaria_con_formato_distinto"))
                return null
            }
            fun num(s: String) = if (s == "?") null else s.toIntOrNull()
            out += SecondaryCarrier(radio, num(p[1]), num(p[2]))
        }
        return out
    }

    /** TAMeters debe ser TA×78 (LTE_INDEX), TA×554 (GSM_INDEX) o vacío (export-formats.md §1.3). */
    private fun checkTaCoherence(r: Row, ta: Int?, unit: String?, meters: Int?) {
        if (r.raw("TA").isNullOrEmpty()) {
            if (!r.raw("TAUnit").isNullOrEmpty() || !r.raw("TAMeters").isNullOrEmpty()) {
                r.issues.add("TA_UNIT_WITHOUT_TA", Severity.WARNING, "TA", msg("import.checkTaCoherence.taunit_o_tameters_presentes_sin"), IssueExample(r.prov, "TA", r.raw("TA")))
            }
            return
        }
        if (ta == null) return
        if (unit == null) {
            r.issues.add("TA_WITHOUT_UNIT", Severity.WARNING, "TAUnit", msg("import.checkTaCoherence.ta_sin_unidad_no_se"), IssueExample(r.prov, "TAUnit", r.raw("TAUnit")))
            return
        }
        val factor = when (unit) { "LTE_INDEX" -> 78; "GSM_INDEX" -> 554; else -> null }
        val expected = factor?.let { ta * it }
        val rawMeters = r.raw("TAMeters").orEmpty()
        val coherent = if (expected == null) rawMeters.isEmpty() else meters == expected
        if (!coherent) {
            r.issues.add("TA_METERS_MISMATCH", Severity.WARNING, "TAMeters",
                msg("import.checkTaCoherence.tameters_no_coincide_con_la"),
                IssueExample(r.prov, "TAMeters", "TA=${ta} ${unit} → ${rawMeters}"))
        }
    }

    /**
     * Duplicados dentro del archivo (D-017). No se elimina nada:
     * - filas idénticas en todas las columnas → «duplicado exacto»;
     * - misma hora e identidad con contenido distinto → «posible revisión o doble escritura».
     */
    private fun checkDuplicates(obs: List<HistoryObservation>, issues: IssueCollector) {
        val byContent = HashMap<List<String>, HistoryObservation>()
        val byKey = HashMap<String, HistoryObservation>()
        val byObservation = HashMap<String, HistoryObservation>()
        for (o in obs) {
            val content = o.raw.values.toList()
            val prev = byContent.putIfAbsent(content, o)
            if (prev != null) {
                issues.add("DUP_EXACT", Severity.INFO, null,
                    msg("import.checkDuplicates.fila_identica_a_otra_del"),
                    IssueExample(o.provenance, null, null, msg("import.checkDuplicates.igual_que_la_linea", prev.provenance.startLine)))
                continue
            }
            val obsId = o.observationId
            if (obsId != null) {
                val p1 = byObservation.putIfAbsent(obsId, o)
                if (p1 != null) {
                    issues.add("DUP_SAME_OBSERVATION", Severity.INFO, "ObservationId", msg("import.checkDuplicates.misma_lectura"),
                        IssueExample(o.provenance, "ObservationId", obsId, msg("import.checkDuplicates.ver_linea", p1.provenance.startLine)))
                    continue
                }
            }
            // Con ObservedAtUtc (3.0) la «misma hora» es el mismo instante: las dos filas de la hora repetida del cambio de hora no lo son.
            val key = o.identity.joinKey?.let { "${o.observedAtUtc ?: o.time.raw}|${it}" } ?: continue
            val p2 = byKey.putIfAbsent(key, o)
            if (p2 != null) {
                issues.add("DUP_SAME_TIME_IDENTITY", Severity.INFO, null,
                    msg("import.checkDuplicates.misma_hora_e_identidad_que"),
                    IssueExample(o.provenance, null, null, msg("import.checkDuplicates.ver_linea", p2.provenance.startLine)))
            }
        }
    }

    private fun pciRange(radio: RadioTech): IntRange? = when (radio) {
        RadioTech.LTE -> 0..503
        RadioTech.NR -> 0..1007
        RadioTech.UMTS -> 0..511
        else -> null
    }

    private fun arfcnRange(radio: RadioTech): IntRange? = when (radio) {
        RadioTech.LTE -> 0..262_143
        RadioTech.NR -> 0..3_279_165
        RadioTech.UMTS -> 0..16_383
        RadioTech.GSM -> 0..1023
        else -> null
    }

    private companion object {
        const val INT_MAX = Int.MAX_VALUE
    }
}
