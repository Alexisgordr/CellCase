package com.alexisgordr.cellcase.core.report

import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.casefile.Discrepancy
import com.alexisgordr.cellcase.core.model.CellIdentity
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.time.Instant
import java.util.Locale

/** Datos del expediente que necesita el informe. */
data class CaseInfo(
    val id: String,
    val name: String,
    val createdAt: Instant,
    /** Dispositivo declarado por el usuario (D-011). No se verifica técnicamente. */
    val deviceLabel: String,
    val zone: DeclaredZone,
    /** Origen de los datos declarado por el usuario (D-028). Nunca se deduce de la ausencia de marcas. */
    val origin: DataOrigin,
    /** Anotaciones del analista: se muestran separadas del texto automático. */
    val analystNotes: String?,
    val periodOfInterest: PeriodOfInterest?,
    val description: String? = null,
    /** Declaración del usuario sobre el origen de las capturas (D-032). */
    val deviceSource: DeviceSource = DeviceSource.NOT_DECLARED,
    /** Teléfono en el que se ejecutó CellCase al crear el expediente: no identifica el de captura. */
    val detected: DetectedEnvironment? = null,
    /** Avisos de discrepancia entre detectado, declarado y archivos (no se resuelven automáticamente). */
    val discrepancies: List<Discrepancy> = emptyList()
)

/**
 * Modalidad del PDF (D-034, D-036). Las dos se generan desde el mismo análisis.
 * EXPLAINED sustituye al antiguo «resumido» (los informes guardados como SUMMARY se muestran como resumidos antiguos).
 */
enum class ReportMode { EXPLAINED, FULL }

/**
 * Clasificación explícita del origen de los datos de un expediente (D-028).
 * «Real declarado» es una declaración del usuario: no significa autenticidad verificada.
 */
enum class DataOrigin { DECLARED_REAL, SYNTHETIC, MIXED, UNDETERMINED }

enum class CoordinateMode { OMIT, ROUND_2, ROUND_3, FULL }
enum class IdentifierMode { FULL, MASKED }

data class ReportOptions(
    val coordinates: CoordinateMode = CoordinateMode.OMIT,
    val identifiers: IdentifierMode = IdentifierMode.FULL,
    val generatedAt: Instant,
    val appVersion: String,
    /** Máximo de filas de evidencia listadas por episodio en el anexo (el resto se cuenta). */
    val maxEvidenceRows: Int = 20,
    /** Máximo de episodios descritos en detalle (el resto va en una tabla). */
    val maxDetailedEpisodes: Int = 8,
    val mode: ReportMode = ReportMode.EXPLAINED,
    /** Anexo con el listado completo de cruces entre fuentes (opcional, desactivado por defecto). */
    val crossAnnex: Boolean = false
)

/** Transformaciones de privacidad y redondeos visuales aplicados al texto del informe. */
object Privacy {
    /** Decimales con que se muestran las coordenadas en modo «completas» (redondeo visual declarado). */
    const val FULL_DISPLAY_DECIMALS = 6

    fun identity(id: CellIdentity, mode: IdentifierMode): String = when (mode) {
        IdentifierMode.FULL -> id.display
        IdentifierMode.MASKED -> "${id.mcc ?: "?"}-${id.mnc ?: "?"}-${mask(id.tac)}-${mask(id.cid)}-${id.radio.name}"
    }

    fun identityKey(key: String, mode: IdentifierMode): String {
        if (mode == IdentifierMode.FULL) return key
        val p = key.split('-')
        return if (p.size == 5) "${p[0]}-${p[1]}-${mask(p[2])}-${mask(p[3])}-${p[4]}" else "***"
    }

    /** Conserva los dos últimos dígitos para poder distinguir celdas dentro del informe. */
    fun mask(v: String?): String = when {
        v == null -> "?"
        v.length <= 2 -> "**"
        else -> "*".repeat(v.length - 2) + v.takeLast(2)
    }

    /** Redondeo sin «-0.000»: un valor que redondea a cero se escribe sin signo. */
    fun round(v: Double, decimals: Int): String {
        val t = String.format(Locale.ROOT, "%.${decimals}f", v)
        return if (t.trimStart('-').all { it == '0' || it == '.' }) t.trimStart('-') else t
    }

    fun coordinate(lat: Double?, lon: Double?, mode: CoordinateMode): String? {
        if (lat == null || lon == null) return null
        val d = when (mode) {
            CoordinateMode.OMIT -> return null
            CoordinateMode.ROUND_2 -> 2
            CoordinateMode.ROUND_3 -> 3
            CoordinateMode.FULL -> FULL_DISPLAY_DECIMALS
        }
        return "${round(lat, d)}, ${round(lon, d)}"
    }
}
