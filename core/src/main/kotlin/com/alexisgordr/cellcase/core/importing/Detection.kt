package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg
import com.alexisgordr.cellcase.core.i18n.plus

import java.io.InputStream
import java.security.MessageDigest

/** Tipos de exportación de ICdetector reconocibles (export-formats.md §0.4). */
enum class ExportKind(val labelText: Msg, val container: String) {
    HISTORY(msg("kind.HISTORY"), "CSV"),
    SERVICE_STATE(msg("kind.SERVICE_STATE"), "CSV"),
    FORENSIC_CASE(msg("kind.FORENSIC_CASE"), "ZIP"),
    TOPOLOGY(msg("kind.TOPOLOGY"), "ZIP"),
    GEOMETRY(msg("kind.GEOMETRY"), "ZIP"),
    STABLE_SITE(msg("kind.STABLE_SITE"), "ZIP"),
    /** Copia de seguridad `.icdbackup` de ICdetector 3.0 (docs/backup-format.md). */
    BACKUP(msg("kind.BACKUP"), "ZIP"),
    UNKNOWN(msg("kind.UNKNOWN"), "?");

    /** Nombre en español (idioma de referencia); el informe usa [labelText] en su idioma. */
    val label: String get() = labelText.toString()
}

/** Qué tipos tienen ya importador completo en esta versión. El resto se reconoce pero no se importa. */
object ImporterAvailability {
    val implemented: Set<ExportKind> = setOf(
        ExportKind.HISTORY, ExportKind.SERVICE_STATE, ExportKind.FORENSIC_CASE,
        ExportKind.TOPOLOGY, ExportKind.GEOMETRY, ExportKind.STABLE_SITE, ExportKind.BACKUP
    )
    fun isImplemented(kind: ExportKind) = kind in implemented
}

data class Detection(val kind: ExportKind, val reason: Msg)

object FormatDetector {
    private const val HISTORY_PREFIX = "Timestamp,NetType,CID,MNC,TAC,MCC,DBM,"
    private const val SERVICE_STATE_HEADER = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching," +
        "Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber," +
        "CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source"

    fun isZip(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 0x50.toByte() && head[1] == 0x4B.toByte() &&
            (head[2] == 0x03.toByte() && head[3] == 0x04.toByte() || head[2] == 0x05.toByte() && head[3] == 0x06.toByte())

    /** Detección de un CSV por su primera línea (sin BOM). */
    fun detectCsv(firstLine: String): Detection {
        val l = firstLine.removePrefix("﻿").trimEnd('\r')
        return when {
            l.startsWith(HISTORY_PREFIX) -> Detection(ExportKind.HISTORY, msg("import.detectCsv.la_cabecera_empieza_por_las"))
            l == SERVICE_STATE_HEADER -> Detection(ExportKind.SERVICE_STATE, msg("import.detectCsv.la_cabecera_coincide_con_la"))
            l.startsWith("TimestampUtc,State,") -> Detection(ExportKind.SERVICE_STATE, msg("import.detectCsv.la_cabecera_se_parece_a"))
            else -> Detection(ExportKind.UNKNOWN, msg("import.detectCsv.la_primera_linea_no_corresponde"))
        }
    }

    /** Detección de un ZIP por el conjunto de entradas, nunca por un único archivo (D-003). */
    fun detectZip(entries: Set<String>, metadataFeature: String? = null): Detection = when {
        entries.containsAll(listOf("case.json", "timeline.csv", "SHA256SUMS.txt")) ->
            Detection(ExportKind.FORENSIC_CASE, msg("import.detectZip.contiene_case_json_timeline_csv"))
        entries.containsAll(listOf("topology.graphml", "transitions.csv")) ->
            Detection(ExportKind.TOPOLOGY, msg("import.detectZip.contiene_topology_graphml_y_transitions"))
        entries.contains("geometry.graphml") && (metadataFeature == null || metadataFeature == "MOBILITY_FAMILIARITY") ->
            Detection(ExportKind.GEOMETRY, msg("import.detectZip.contiene_geometry_graphml") + (if (metadataFeature != null) msg("import.detectZip.y_metadata_json_de_mobility") else Msg.raw(".")))
        entries.containsAll(listOf("sites.csv", "site_cells.csv", "motion.csv", "shadow_episodes.csv")) ->
            Detection(ExportKind.STABLE_SITE, msg("import.detectZip.contiene_sites_csv_site_cells"))
        else -> Detection(ExportKind.UNKNOWN, msg("import.detectZip.el_conjunto_de_entradas_no"))
    }
}

object Hashing {
    fun sha256Hex(bytes: ByteArray): String = hex(MessageDigest.getInstance("SHA-256").digest(bytes))

    /** Copia [input] a [sink] calculando SHA-256 y respetando un tamaño máximo. */
    fun copyHashing(
        input: InputStream,
        sink: (ByteArray, Int) -> Unit,
        maxBytes: Long,
        checkpoint: Checkpoint = Checkpoint.NONE,
        progress: (Long) -> Unit = {}
    ): Pair<String, Long> {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            checkpoint.check()
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw ImportRejected(msg("import.copyHashing.el_archivo_supera_el_tamano", maxBytes / (1024 * 1024)), RejectKind.INCOMPATIBLE)
            md.update(buf, 0, n)
            sink(buf, n)
            progress(total)
        }
        return hex(md.digest()) to total
    }

    fun hex(d: ByteArray): String = d.joinToString("") { "%02x".format(it) }
}

/** Punto de cancelación cooperativa: el trabajo pesado lo consulta periódicamente. */
fun interface Checkpoint {
    /** Lanza una excepción si el trabajo debe cancelarse. */
    fun check()

    companion object {
        val NONE = Checkpoint { }
    }
}

/** Motivo de un rechazo, para distinguirlo claramente ante el usuario. */
enum class RejectKind {
    /** No corresponde a ninguna exportación conocida. */
    UNRECOGNIZED,
    /** ZIP truncado, corrupto o con directorio central incoherente. */
    DAMAGED_ZIP,
    /** ZIP rechazado por límites de seguridad (rutas, tamaño, número de entradas, compresión). */
    ZIP_SECURITY,
    /** Tipo reconocido pero estructura o campos obligatorios incompatibles. */
    INCOMPATIBLE,
    /** No se pudo leer: codificación, CSV mal formado, JSON inválido o error de E/S. */
    READ_ERROR
}

/** Rechazo controlado de una importación, con un mensaje para el usuario. */
class ImportRejected(val text: Msg, val kind: RejectKind = RejectKind.INCOMPATIBLE) : Exception(text.toString())
