package com.alexisgordr.cellcase.core.casefile

import com.alexisgordr.cellcase.core.analysis.EPISODE_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.PeriodOfInterest
import com.alexisgordr.cellcase.core.analysis.RULESET_VERSION
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportedData
import java.time.Instant
import java.time.ZoneId

/**
 * Lo que la app DETECTA del teléfono en el que se ejecuta (APIs públicas, sin permisos).
 * No dice nada sobre el teléfono que hizo las capturas (D-032).
 */
data class DetectedEnvironment(
    val manufacturer: String?,
    val model: String?,
    val androidRelease: String?,
    val sdkInt: Int?,
    /** `ZoneId.systemDefault()` en el momento de crear el expediente. */
    val zoneId: String,
    val detectedAt: Instant
) {
    val deviceLabel: String
        get() {
            val m = model?.trim().orEmpty(); val f = manufacturer?.trim().orEmpty()
            val name = if (f.isNotEmpty() && !m.lowercase().startsWith(f.lowercase())) "$f $m" else m.ifEmpty { f }
            return listOfNotNull(name.ifEmpty { null }, androidRelease?.let { "Android $it" }).joinToString(" · ").ifEmpty { "?" }
        }
}

/** Declaración del usuario sobre el origen de las capturas (D-032). */
enum class DeviceSource { THIS_DEVICE, OTHER_DEVICE, NOT_DECLARED }

data class DeviceDeclaration(val source: DeviceSource, val label: String)

/** Dispositivo que figura DENTRO de un archivo importado (hoy solo `case.json` de los casos forenses). */
data class FileDeviceInfo(val fileName: String, val device: String?, val android: String?)

enum class DiscrepancyCode {
    /** Declarado «este móvil», pero un archivo nombra otro modelo. */
    FILE_DEVICE_DIFFERS_FROM_DETECTED,
    /** Un archivo nombra un modelo distinto del dispositivo declarado. */
    FILE_DEVICE_DIFFERS_FROM_DECLARED,
    /** Los archivos nombran modelos distintos entre sí. */
    FILES_DISAGREE,
    /** Declarado «otro dispositivo», pero los archivos nombran el modelo de este móvil. */
    DECLARED_OTHER_BUT_FILES_MATCH_DETECTED,
    /** La zona declarada para la captura no es la zona actual del teléfono (puede ser correcto). */
    ZONE_DIFFERS_FROM_CURRENT
}

data class Discrepancy(val code: DiscrepancyCode, val values: Map<String, String>)

/**
 * Compara valores detectados, declaraciones y metadatos de archivos. Solo AVISA: nunca corrige ni elige
 * por el usuario, y ninguna coincidencia se presenta como verificación.
 */
object DeviceCrossCheck {
    fun fileDevices(results: List<ImportResult>): List<FileDeviceInfo> = results.mapNotNull { r ->
        val d = (r.data as? ImportedData.Forensic)?.data ?: return@mapNotNull null
        val dev = d.meta.device?.takeIf { it.isNotBlank() && it != "unknown" }
        if (dev == null && d.meta.android == null) null else FileDeviceInfo(r.fileName, dev, d.meta.android)
    }

    /** Normaliza para comparar modelos: minúsculas y solo letras y cifras. */
    fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

    /** ¿Nombran el mismo modelo? Contención en cualquier sentido («Pixel 9a» frente a «Google Pixel 9a»). */
    fun sameModel(a: String?, b: String?): Boolean {
        val x = norm(a); val y = norm(b)
        if (x.length < 3 || y.length < 3) return false
        return x.contains(y) || y.contains(x)
    }

    fun check(detected: DetectedEnvironment?, declaration: DeviceDeclaration, declaredZone: String, results: List<ImportResult>): List<Discrepancy> {
        val out = ArrayList<Discrepancy>()
        val files = fileDevices(results).filter { it.device != null }
        val distinct = files.map { norm(it.device) }.distinct()
        if (distinct.size > 1) out += Discrepancy(DiscrepancyCode.FILES_DISAGREE, mapOf("devices" to files.joinToString("; ") { "${it.fileName}: ${it.device}" }))
        val detModel = detected?.model
        for (f in files) {
            when (declaration.source) {
                DeviceSource.THIS_DEVICE -> if (detModel != null && !sameModel(f.device, detModel))
                    out += Discrepancy(DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DETECTED, mapOf("file" to f.fileName, "fileDevice" to f.device!!, "detected" to detected.deviceLabel))
                DeviceSource.OTHER_DEVICE -> {
                    if (declaration.label.isNotBlank() && !sameModel(f.device, declaration.label))
                        out += Discrepancy(DiscrepancyCode.FILE_DEVICE_DIFFERS_FROM_DECLARED, mapOf("file" to f.fileName, "fileDevice" to f.device!!, "declared" to declaration.label))
                    if (detModel != null && sameModel(f.device, detModel))
                        out += Discrepancy(DiscrepancyCode.DECLARED_OTHER_BUT_FILES_MATCH_DETECTED, mapOf("file" to f.fileName, "fileDevice" to f.device!!, "detected" to detected.deviceLabel))
                }
                DeviceSource.NOT_DECLARED -> {}
            }
        }
        if (detected != null && runCatching { ZoneId.of(detected.zoneId).normalized() != ZoneId.of(declaredZone).normalized() }.getOrDefault(detected.zoneId != declaredZone))
            out += Discrepancy(DiscrepancyCode.ZONE_DIFFERS_FROM_CURRENT, mapOf("current" to detected.zoneId, "declared" to declaredZone))
        return out.distinct()
    }
}

/**
 * Configuración que determina el resultado del análisis. Cada informe guarda su huella; si la configuración
 * actual cambia (zona, periodo, reglas), los informes anteriores se señalan como «generados con una
 * configuración previa» y no se sobrescriben (D-033).
 */
data class AnalysisConfig(
    val zoneId: String,
    val zoneConfirmed: Boolean,
    val period: PeriodOfInterest?,
    /**
     * Versiones de todo lo que cambia el resultado (revisión 22/10, B7): lectores, reglas del historial, episodios,
     * cruces, fusión, perfil local y churn. Antes solo las dos primeras.
     */
    val rules: String = listOf(com.alexisgordr.cellcase.core.importing.READERS_VERSION, RULESET_VERSION, EPISODE_RULES_VERSION,
        com.alexisgordr.cellcase.core.analysis.CROSS_RULES_VERSION, com.alexisgordr.cellcase.core.analysis.MERGE_RULES_VERSION,
        com.alexisgordr.cellcase.core.analysis.PROFILE_RULES_VERSION, com.alexisgordr.cellcase.core.analysis.CHURN_RULES_VERSION).joinToString("+"),
    /** Huellas SHA-256 de los archivos incluidos (añadir o quitar archivos también cambia el resultado). */
    val fileHashes: List<String> = emptyList()
) {
    fun canonical(): String = listOf(
        "zone=$zoneId", "confirmed=$zoneConfirmed",
        "period=${period?.let { "${it.start.toEpochMilli()}..${it.end.toEpochMilli()}" } ?: "-"}",
        "rules=$rules", "files=${fileHashes.sorted().joinToString(",")}"
    ).joinToString(";")

    fun fingerprint(): String = Hashing.sha256Hex(canonical().toByteArray(Charsets.UTF_8)).take(16)
}
