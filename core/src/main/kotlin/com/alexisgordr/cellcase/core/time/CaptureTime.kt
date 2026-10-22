package com.alexisgordr.cellcase.core.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/**
 * Zona horaria del dispositivo de captura, tal como la ha declarado el usuario.
 * Es un **supuesto declarado**: los archivos de ICdetector no la registran (export-formats.md §1.5).
 */
data class DeclaredZone(
    val zoneId: ZoneId,
    /** El usuario la ha confirmado explícitamente (no basta con la sugerencia por defecto). */
    val confirmed: Boolean
) {
    companion object {
        val SUGGESTED: ZoneId = ZoneId.of("Europe/Madrid")
    }
}

/** Cómo se ha podido interpretar una hora local sin zona. */
sealed interface TimeResolution {
    /** Un único instante posible con la zona declarada. */
    data class Exact(val instant: Instant, val offset: ZoneOffset) : TimeResolution
    /** La hora local existe dos veces (fin del horario de verano). No se elige ninguna. */
    data class Ambiguous(val earlier: Instant, val later: Instant) : TimeResolution
    /** La hora local no existió (inicio del horario de verano). */
    data class Nonexistent(val gapStart: Instant) : TimeResolution
    /** No hay zona confirmada: la hora se conserva solo como texto local. */
    data object NoConfirmedZone : TimeResolution
}

/** Hora leída de un archivo: texto original, interpretación local y resolución a UTC. */
data class RecordedTime(
    val raw: String,
    val local: LocalDateTime?,
    val resolution: TimeResolution?
) {
    /** Instante solo si es inequívoco. */
    val instantOrNull: Instant? get() = (resolution as? TimeResolution.Exact)?.instant
    val isValid: Boolean get() = local != null
}

object CaptureTime {
    /** Formato de hora local de ICdetector: `yyyy-MM-dd HH:mm:ss`, sin zona. */
    private val LOCAL_SECONDS: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", java.util.Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)
    private val LOCAL_DATE: DateTimeFormatter =
        DateTimeFormatter.ofPattern("uuuu-MM-dd", java.util.Locale.ROOT).withResolverStyle(ResolverStyle.STRICT)

    /**
     * La hora se guarda recortada (revisión 22/10, C1): es la que se analiza, y con espacios al final no se podía
     * volver a leer en el informe ni coincidía en las claves de duplicados. El campo original, con sus espacios, sigue
     * en las columnas crudas de la fila.
     */
    fun parseLocalSeconds(raw: String, zone: DeclaredZone?): RecordedTime {
        val text = raw.trim()
        val local = try { LocalDateTime.parse(text, LOCAL_SECONDS) } catch (_: DateTimeParseException) { null }
            ?: return RecordedTime(text, null, null)
        return RecordedTime(text, local, resolve(local, zone))
    }

    fun resolve(local: LocalDateTime, zone: DeclaredZone?): TimeResolution {
        if (zone == null || !zone.confirmed) return TimeResolution.NoConfirmedZone
        val rules = zone.zoneId.rules
        val offsets = rules.getValidOffsets(local)
        return when (offsets.size) {
            1 -> TimeResolution.Exact(local.toInstant(offsets[0]), offsets[0])
            2 -> {
                val a = local.toInstant(offsets[0]); val b = local.toInstant(offsets[1])
                TimeResolution.Ambiguous(minOf(a, b), maxOf(a, b))
            }
            else -> TimeResolution.Nonexistent(rules.getTransition(local).instant)
        }
    }

    /** Fecha sin hora (p. ej. `motion.day` de Stable-Site). Sigue siendo una fecha: no se le asigna hora. */
    fun parseDate(raw: String): LocalDate? =
        try { LocalDate.parse(raw.trim(), LOCAL_DATE) } catch (_: DateTimeParseException) { null }

    /** Instante ISO-8601 UTC (`Instant.toString()`), con o sin milisegundos. */
    fun parseInstant(raw: String): Instant? =
        try { Instant.parse(raw.trim()) } catch (_: DateTimeParseException) { null }
}
