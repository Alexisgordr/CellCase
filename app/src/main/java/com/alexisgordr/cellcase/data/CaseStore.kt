package com.alexisgordr.cellcase.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.model.Issue
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.report.CoordinateMode
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.IdentifierMode
import java.time.Instant

/** Expediente guardado. */
data class CaseRecord(
    val id: String,
    val name: String,
    val createdAt: Instant,
    /** Dispositivo de captura declarado por el usuario (texto libre). */
    val deviceLabel: String,
    val zoneId: String,
    val zoneConfirmed: Boolean,
    /** Origen declarado por el usuario (D-028). Por defecto, no determinado. */
    val origin: DataOrigin,
    /** La declaración procede de la casilla «datos sintéticos» de la versión 1 y debe revisarse. */
    val originInherited: Boolean,
    val analystNotes: String?,
    val periodOfInterest: PeriodOfInterest?,
    val coordinateMode: CoordinateMode,
    val identifierMode: IdentifierMode,
    val description: String? = null,
    /** Declaración: las capturas proceden de este móvil, de otro, o no se declara (D-032). */
    val deviceSource: DeviceSource = DeviceSource.NOT_DECLARED,
    /** Valores detectados al crear el expediente; se guardan aparte de las declaraciones. */
    val detected: DetectedEnvironment? = null,
    /** Borrado en curso (fase 1 del borrado en dos fases). */
    val deleting: Boolean = false
)

/** Importación guardada: el original privado y lo que se obtuvo al leerlo. */
data class ImportRecord(
    val id: String,
    val caseId: String,
    val fileName: String,
    val storedName: String,
    val sha256: String,
    val sizeBytes: Long,
    val importedAt: Instant,
    val kind: ExportKind,
    /** Estado de la última lectura (D-027); se actualiza si una versión nueva de los lectores lo cambia. */
    val state: ImportState,
    val readerVersion: String?,
    val schemaLabel: String?,
    val issueCount: Int?,
    val message: String?,
    /** Zona usada al leer este archivo (D-010: preparado para grupos de captura). */
    val zoneId: String?,
    val zoneConfirmed: Boolean
)

data class StoredIssue(val code: String, val severity: Severity, val column: String?, val message: String, val count: Int, val examples: String)

data class ReportRecord(
    val id: String, val caseId: String, val createdAt: Instant, val storedName: String, val sha256: String, val pages: Int, val ruleset: String,
    /** Huella de la configuración de análisis con la que se generó (D-033). */
    val configFingerprint: String? = null,
    /** Descripción legible de esa configuración (zona, periodo, reglas). */
    val configSummary: String? = null,
    /** EXPLAINED o FULL (SUMMARY en informes de versiones anteriores). */
    val mode: String = "FULL",
    /** Idioma del informe (es, en). Los informes de versiones anteriores son en español (D-044). */
    val language: String = "es"
)

/** Persistencia de expedientes en SQLite del sistema (D-025). Sin dependencias externas. */
class CaseStore(context: Context) : SQLiteOpenHelper(context, "cellcase.db", null, 4) {

    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE cases (id TEXT PRIMARY KEY, name TEXT NOT NULL, created_at INTEGER NOT NULL, device_label TEXT NOT NULL, " +
                "zone_id TEXT NOT NULL, zone_confirmed INTEGER NOT NULL, synthetic INTEGER NOT NULL DEFAULT 0, analyst_notes TEXT, " +
                "poi_start INTEGER, poi_end INTEGER, poi_label TEXT, coord_mode TEXT NOT NULL, id_mode TEXT NOT NULL, " +
                "origin TEXT NOT NULL DEFAULT 'UNDETERMINED', origin_inherited INTEGER NOT NULL DEFAULT 0, " + V3_CASE_COLUMNS.joinToString(", ") + ")"
        )
        db.execSQL(
            "CREATE TABLE imports (id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE CASCADE, file_name TEXT NOT NULL, " +
                "stored_name TEXT NOT NULL, sha256 TEXT NOT NULL, size INTEGER NOT NULL, imported_at INTEGER NOT NULL, kind TEXT NOT NULL, " +
                "status TEXT NOT NULL, reader_version TEXT, schema_label TEXT, data_rows INTEGER, rejected_rows INTEGER, issue_count INTEGER, " +
                "message TEXT, zone_id TEXT, zone_confirmed INTEGER NOT NULL DEFAULT 0, UNIQUE(case_id, sha256))"
        )
        db.execSQL(
            "CREATE TABLE issues (import_id TEXT NOT NULL REFERENCES imports(id) ON DELETE CASCADE, code TEXT NOT NULL, severity TEXT NOT NULL, " +
                "col TEXT, message TEXT NOT NULL, count INTEGER NOT NULL, examples TEXT NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE reports (id TEXT PRIMARY KEY, case_id TEXT NOT NULL REFERENCES cases(id) ON DELETE CASCADE, created_at INTEGER NOT NULL, " +
                "stored_name TEXT NOT NULL, sha256 TEXT NOT NULL, pages INTEGER NOT NULL, ruleset TEXT NOT NULL, " + V3_REPORT_COLUMNS.joinToString(", ") + ", " + V4_REPORT_LANGUAGE + ")"
        )
    }

    /**
     * v1 → v2 (D-028): la casilla binaria «datos sintéticos» se sustituye por un origen explícito.
     * synthetic=1 → SYNTHETIC (marcado como heredado para que el usuario lo revise);
     * synthetic=0 → UNDETERMINED: no marcar «sintético» no equivale a declarar datos reales.
     * Los estados de importación antiguos se traducen; al abrir el expediente se releen los originales
     * con los lectores actuales y el estado guardado se actualiza.
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE cases ADD COLUMN origin TEXT NOT NULL DEFAULT 'UNDETERMINED'")
            db.execSQL("ALTER TABLE cases ADD COLUMN origin_inherited INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE cases SET origin='SYNTHETIC', origin_inherited=1 WHERE synthetic=1")
            db.execSQL("UPDATE imports SET status='NOT_IMPLEMENTED' WHERE status='RECOGNIZED_NOT_SUPPORTED'")
            db.execSQL("UPDATE imports SET status='INCOMPATIBLE' WHERE status='REJECTED'")
        }
        if (oldVersion < 3) {
            // v2 → v3 (D-032, D-033): descripción, dispositivo detectado separado del declarado, marca de borrado,
            // y huella de configuración en los informes. Los informes anteriores quedan sin huella: se muestran
            // como «generados con una configuración anterior» porque no se puede saber cuál fue.
            V3_CASE_COLUMNS.forEach { db.execSQL("ALTER TABLE cases ADD COLUMN $it") }
            V3_REPORT_COLUMNS.forEach { db.execSQL("ALTER TABLE reports ADD COLUMN $it") }
            db.execSQL("UPDATE cases SET device_source='NOT_DECLARED'")
        }
        if (oldVersion < 4) {
            // v3 → v4 (D-044): idioma de cada informe. Los informes existentes se generaron en español; no se
            // modifica ningún expediente, importación ni archivo.
            db.execSQL("ALTER TABLE reports ADD COLUMN $V4_REPORT_LANGUAGE")
        }
    }

    companion object {
        val V3_CASE_COLUMNS = listOf(
            "description TEXT", "device_source TEXT NOT NULL DEFAULT 'NOT_DECLARED'",
            "det_manufacturer TEXT", "det_model TEXT", "det_android TEXT", "det_sdk INTEGER", "det_zone TEXT", "det_at INTEGER",
            "deleting INTEGER NOT NULL DEFAULT 0"
        )
        val V3_REPORT_COLUMNS = listOf("config_fingerprint TEXT", "config_summary TEXT", "mode TEXT NOT NULL DEFAULT 'FULL'")
        /** v4 (D-044): idioma del informe; los anteriores se generaron en español. */
        const val V4_REPORT_LANGUAGE = "language TEXT NOT NULL DEFAULT 'es'"
    }

    // ───────────────── expedientes ─────────────────

    fun insertCase(c: CaseRecord) {
        writableDatabase.insertOrThrow("cases", null, caseValues(c))
    }

    fun updateCase(c: CaseRecord) {
        writableDatabase.update("cases", caseValues(c), "id=?", arrayOf(c.id))
    }

    private fun caseValues(c: CaseRecord) = ContentValues().apply {
        put("id", c.id); put("name", c.name); put("created_at", c.createdAt.toEpochMilli()); put("device_label", c.deviceLabel)
        put("zone_id", c.zoneId); put("zone_confirmed", if (c.zoneConfirmed) 1 else 0); put("synthetic", if (c.origin == DataOrigin.SYNTHETIC) 1 else 0)
        put("origin", c.origin.name); put("origin_inherited", if (c.originInherited) 1 else 0)
        put("analyst_notes", c.analystNotes)
        val poi = c.periodOfInterest
        if (poi != null) { put("poi_start", poi.start.toEpochMilli()); put("poi_end", poi.end.toEpochMilli()); put("poi_label", poi.label) }
        else { putNull("poi_start"); putNull("poi_end"); putNull("poi_label") }
        put("coord_mode", c.coordinateMode.name); put("id_mode", c.identifierMode.name)
        put("description", c.description); put("device_source", c.deviceSource.name); put("deleting", if (c.deleting) 1 else 0)
        val d = c.detected
        put("det_manufacturer", d?.manufacturer); put("det_model", d?.model); put("det_android", d?.androidRelease)
        put("det_sdk", d?.sdkInt); put("det_zone", d?.zoneId); put("det_at", d?.detectedAt?.toEpochMilli())
    }

    /** Fase 1 del borrado: marca el expediente; ya no aparece en la lista aunque el borrado se interrumpa. */
    fun markDeleting(id: String, deleting: Boolean) {
        writableDatabase.update("cases", ContentValues().apply { put("deleting", if (deleting) 1 else 0) }, "id=?", arrayOf(id))
    }

    fun listDeleting(): List<String> = readableDatabase.rawQuery("SELECT id FROM cases WHERE deleting=1", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    /** Fase 3: borra expediente, importaciones, incidencias e informes registrados en una sola transacción. */
    fun deleteCaseRows(id: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("issues", "import_id IN (SELECT id FROM imports WHERE case_id=?)", arrayOf(id))
            db.delete("imports", "case_id=?", arrayOf(id))
            db.delete("reports", "case_id=?", arrayOf(id))
            db.delete("cases", "id=?", arrayOf(id))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Recuento de lo que se borrará, para el diálogo de confirmación. */
    fun countFor(caseId: String): Pair<Int, Int> {
        val imports = readableDatabase.rawQuery("SELECT COUNT(*) FROM imports WHERE case_id=?", arrayOf(caseId)).use { c -> if (c.moveToFirst()) c.getLong(0).toInt() else 0 }
        val reports = readableDatabase.rawQuery("SELECT COUNT(*) FROM reports WHERE case_id=?", arrayOf(caseId)).use { c -> if (c.moveToFirst()) c.getLong(0).toInt() else 0 }
        return imports to reports
    }

    /** Al cambiar la zona del expediente se cambia en todas sus importaciones (un grupo de captura por expediente, D-010). */
    fun updateImportsZone(caseId: String, zoneId: String, confirmed: Boolean) {
        writableDatabase.update("imports", ContentValues().apply { put("zone_id", zoneId); put("zone_confirmed", if (confirmed) 1 else 0) }, "case_id=?", arrayOf(caseId))
    }

    fun listCases(): List<CaseRecord> = readableDatabase.rawQuery("SELECT * FROM cases WHERE deleting=0 ORDER BY created_at DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(readCase(c)) }
    }

    fun getCase(id: String): CaseRecord? = readableDatabase.rawQuery("SELECT * FROM cases WHERE id=?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) readCase(c) else null
    }


    private fun readCase(c: Cursor): CaseRecord {
        val poiStart = c.longOrNull("poi_start")
        val poiEnd = c.longOrNull("poi_end")
        return CaseRecord(
            id = c.str("id")!!, name = c.str("name")!!, createdAt = Instant.ofEpochMilli(c.long("created_at")),
            deviceLabel = c.str("device_label")!!, zoneId = c.str("zone_id")!!, zoneConfirmed = c.long("zone_confirmed") == 1L,
            origin = runCatching { DataOrigin.valueOf(c.str("origin")!!) }.getOrDefault(DataOrigin.UNDETERMINED),
            originInherited = c.long("origin_inherited") == 1L, analystNotes = c.str("analyst_notes"),
            periodOfInterest = if (poiStart != null && poiEnd != null) PeriodOfInterest(Instant.ofEpochMilli(poiStart), Instant.ofEpochMilli(poiEnd), c.str("poi_label") ?: "") else null,
            coordinateMode = CoordinateMode.valueOf(c.str("coord_mode")!!), identifierMode = IdentifierMode.valueOf(c.str("id_mode")!!),
            description = c.str("description"),
            deviceSource = runCatching { DeviceSource.valueOf(c.str("device_source")!!) }.getOrDefault(DeviceSource.NOT_DECLARED),
            detected = c.str("det_zone")?.let { z ->
                DetectedEnvironment(c.str("det_manufacturer"), c.str("det_model"), c.str("det_android"), c.longOrNull("det_sdk")?.toInt(), z,
                    Instant.ofEpochMilli(c.longOrNull("det_at") ?: 0L))
            },
            deleting = c.long("deleting") == 1L
        )
    }

    // ───────────────── importaciones ─────────────────

    fun findImportBySha(caseId: String, sha: String): ImportRecord? =
        readableDatabase.rawQuery("SELECT * FROM imports WHERE case_id=? AND sha256=?", arrayOf(caseId, sha)).use { c ->
            if (c.moveToFirst()) readImport(c) else null
        }

    fun insertImport(r: ImportRecord, issues: List<Issue>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.insertOrThrow("imports", null, ContentValues().apply {
                put("id", r.id); put("case_id", r.caseId); put("file_name", r.fileName); put("stored_name", r.storedName); put("sha256", r.sha256)
                put("size", r.sizeBytes); put("imported_at", r.importedAt.toEpochMilli()); put("kind", r.kind.name); put("status", r.state.name)
                put("reader_version", r.readerVersion); put("schema_label", r.schemaLabel)
                r.issueCount?.let { put("issue_count", it) }
                put("message", r.message); put("zone_id", r.zoneId); put("zone_confirmed", if (r.zoneConfirmed) 1 else 0)
            })
            insertIssues(db, r.id, issues)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Actualiza el estado guardado cuando la relectura con los lectores actuales da otro resultado. */
    fun updateImportState(r: ImportRecord, issues: List<Issue>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.update("imports", ContentValues().apply {
                put("kind", r.kind.name); put("status", r.state.name); put("reader_version", r.readerVersion); put("schema_label", r.schemaLabel)
                put("message", r.message); r.issueCount?.let { put("issue_count", it) } ?: putNull("issue_count")
            }, "id=?", arrayOf(r.id))
            db.delete("issues", "import_id=?", arrayOf(r.id))
            insertIssues(db, r.id, issues)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    private fun insertIssues(db: SQLiteDatabase, importId: String, issues: List<Issue>) {
            for (i in issues) {
                db.insertOrThrow("issues", null, ContentValues().apply {
                    put("import_id", importId); put("code", i.code); put("severity", i.severity.name); put("col", i.column); put("message", i.message)
                    put("count", i.count)
                    put("examples", i.examples.joinToString("; ") { e -> "#${e.provenance?.startLine ?: "-"}" + (e.rawValue?.let { v -> " «${v.take(60)}»" } ?: "") })
                })
            }
    }

    fun listImports(caseId: String): List<ImportRecord> =
        readableDatabase.rawQuery("SELECT * FROM imports WHERE case_id=? ORDER BY imported_at", arrayOf(caseId)).use { c ->
            buildList { while (c.moveToNext()) add(readImport(c)) }
        }

    fun listIssues(importId: String): List<StoredIssue> =
        readableDatabase.rawQuery("SELECT * FROM issues WHERE import_id=? ORDER BY rowid", arrayOf(importId)).use { c ->
            buildList {
                while (c.moveToNext()) add(StoredIssue(c.str("code")!!, Severity.valueOf(c.str("severity")!!), c.str("col"), c.str("message")!!, c.long("count").toInt(), c.str("examples")!!))
            }
        }

    private fun readImport(c: Cursor) = ImportRecord(
        id = c.str("id")!!, caseId = c.str("case_id")!!, fileName = c.str("file_name")!!, storedName = c.str("stored_name")!!,
        sha256 = c.str("sha256")!!, sizeBytes = c.long("size"), importedAt = Instant.ofEpochMilli(c.long("imported_at")),
        kind = runCatching { ExportKind.valueOf(c.str("kind")!!) }.getOrDefault(ExportKind.UNKNOWN),
        state = runCatching { ImportState.valueOf(c.str("status")!!) }.getOrDefault(ImportState.READ_ERROR), readerVersion = c.str("reader_version"),
        schemaLabel = c.str("schema_label"), issueCount = c.longOrNull("issue_count")?.toInt(), message = c.str("message"), zoneId = c.str("zone_id"), zoneConfirmed = c.long("zone_confirmed") == 1L
    )

    // ───────────────── informes ─────────────────

    fun insertReport(r: ReportRecord) {
        writableDatabase.insertOrThrow("reports", null, ContentValues().apply {
            put("id", r.id); put("case_id", r.caseId); put("created_at", r.createdAt.toEpochMilli()); put("stored_name", r.storedName)
            put("sha256", r.sha256); put("pages", r.pages); put("ruleset", r.ruleset)
            put("config_fingerprint", r.configFingerprint); put("config_summary", r.configSummary); put("mode", r.mode); put("language", r.language)
        })
    }

    fun listReports(caseId: String): List<ReportRecord> =
        readableDatabase.rawQuery("SELECT * FROM reports WHERE case_id=? ORDER BY created_at DESC", arrayOf(caseId)).use { c ->
            buildList {
                while (c.moveToNext()) add(ReportRecord(c.str("id")!!, c.str("case_id")!!, Instant.ofEpochMilli(c.long("created_at")), c.str("stored_name")!!,
                    c.str("sha256")!!, c.long("pages").toInt(), c.str("ruleset")!!, c.str("config_fingerprint"), c.str("config_summary"), c.str("mode") ?: "FULL", c.str("language") ?: "es"))
            }
        }

    private fun Cursor.str(col: String): String? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getString(it) }
    private fun Cursor.long(col: String): Long = getLong(getColumnIndexOrThrow(col))
    private fun Cursor.longOrNull(col: String): Long? = getColumnIndexOrThrow(col).let { if (isNull(it)) null else getLong(it) }
}
