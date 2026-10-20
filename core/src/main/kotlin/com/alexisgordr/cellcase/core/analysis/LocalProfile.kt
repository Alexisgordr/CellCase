package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.backup.BackupInfo
import com.alexisgordr.cellcase.core.importing.HistoryColumns
import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.ProfileEvaluation
import com.alexisgordr.cellcase.core.model.Provenance
import java.time.Instant

/** Versión de la presentación del perfil local (docs/local-profile.md). Solo resume: no hay umbrales propios. */
const val PROFILE_RULES_VERSION = "profile/1"

/** Evaluación más desviada de un conjunto de filas (mayor puntuación; con empate, la primera en el tiempo). */
data class ProfileExtreme(val level: String, val score: Double?, val evidence: String?, val provenance: Provenance, val time: Instant?)

/** Evaluaciones del perfil local en las filas de un episodio (las de su intervalo, `Episode.during`). */
data class ProfileEpisode(
    val episode: Int,
    val rows: Int,
    val evaluated: Int,
    val byLevel: Map<String, Int>,
    val extreme: ProfileExtreme?
) {
    /** Filas con desviación MODERATE o HIGH (convenciones de ICdetector, no calibradas). */
    val deviated: Int get() = (byLevel["MODERATE"] ?: 0) + (byLevel["HIGH"] ?: 0)
    val abstained: Int get() = byLevel["ABSTAIN"] ?: 0
}

/**
 * Resumen del **perfil local** de ICdetector 3.0 (columnas `Profile*` del historial). Es contexto, igual que en
 * ICdetector: modo sombra, umbrales no calibrados, una desviación no es una probabilidad y `HIGH` no es una alerta.
 * No interviene en episodios, prioridades ni en la valoración (D-055). Mira la misma señal que la regla de potencia
 * (H13), así que coincidir con ella no es una confirmación independiente.
 */
data class LocalProfileSummary(
    /** Filas del historial combinado. */
    val rows: Int,
    /** Filas con columnas de 3.0 (las grabadas antes de 3.0 no tienen perfil). */
    val rowsV3: Int,
    val evaluated: Int,
    /** Por nivel, en el orden NONE, LOW, MODERATE, HIGH, ABSTAIN (solo los presentes). */
    val byLevel: Map<String, Int>,
    val abstainReasons: Map<String, Int>,
    /** SHADOW o EXPERIMENTAL_DISPLAY. */
    val modes: Map<String, Int>,
    /** Versiones del algoritmo (`ProfileAlgorithm`): ICdetector advierte que no se comparen entre sí. */
    val algorithms: Map<Int, Int>,
    val holdout: Int,
    val first: Instant?,
    val last: Instant?,
    val episodes: List<ProfileEpisode>,
    /** Filas MODERATE o HIGH fuera de todo episodio. */
    val deviatedOutsideEpisodes: Int,
    val extremeOutsideEpisodes: ProfileExtreme?,
    /** Referencias del perfil (`local_profile`) por madurez, solo si se aportó una copia de seguridad. */
    val references: Map<String, Int>
) {
    val deviated: Int get() = (byLevel["MODERATE"] ?: 0) + (byLevel["HIGH"] ?: 0)
    val mixedAlgorithms: Boolean get() = algorithms.size > 1
}

object LocalProfileAnalyzer {
    private val LEVEL_ORDER = ProfileEvaluation.LEVELS

    /** Null si el historial no tiene columnas del perfil local (ICdetector anterior a 3.0) ni hay copia de seguridad. */
    fun of(history: MergedHistory?, episodes: EpisodeSet, backups: List<BackupInfo>): LocalProfileSummary? {
        val obs = history?.import?.observations ?: return null
        val hasColumns = history.import.header.contains("ProfileLevel") || obs.any { it.profile != null }
        if (!hasColumns && backups.isEmpty()) return null
        val evaluated = obs.filter { it.profile?.level != null }
        val inEpisodes = HashSet<Provenance>()
        val perEpisode = episodes.episodes.map { e ->
            e.during.forEach { inEpisodes += it.provenance }
            val ev = e.during.filter { it.profile?.level != null }
            ProfileEpisode(e.number, e.during.size, ev.size, levels(ev), extreme(ev))
        }
        val outside = evaluated.filter { it.provenance !in inEpisodes && it.profile!!.level in setOf("MODERATE", "HIGH") }
        val times = evaluated.mapNotNull { it.observedAtUtc ?: it.time.instantOrNull }
        return LocalProfileSummary(
            rows = obs.size,
            rowsV3 = obs.count { it.appVersion != null || it.observedAtUtc != null || it.profile != null },
            evaluated = evaluated.size,
            byLevel = levels(evaluated),
            abstainReasons = evaluated.mapNotNull { it.profile!!.abstainReason }.groupingBy { it }.eachCount().toSortedMap(),
            modes = evaluated.mapNotNull { it.profile!!.mode }.groupingBy { it }.eachCount().toSortedMap(),
            algorithms = evaluated.mapNotNull { it.profile!!.algorithm }.groupingBy { it }.eachCount().toSortedMap(),
            holdout = evaluated.count { it.profile!!.holdout == true },
            first = times.minOrNull(), last = times.maxOrNull(),
            episodes = perEpisode,
            deviatedOutsideEpisodes = outside.size,
            extremeOutsideEpisodes = extreme(outside),
            references = backups.flatMap { it.profileReferences.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }.toSortedMap()
        )
    }

    private fun levels(rows: List<HistoryObservation>): Map<String, Int> {
        val c = rows.mapNotNull { it.profile?.level }.groupingBy { it }.eachCount()
        return LinkedHashMap<String, Int>().apply { LEVEL_ORDER.forEach { l -> c[l]?.let { put(l, it) } } }
    }

    private fun extreme(rows: List<HistoryObservation>): ProfileExtreme? {
        val candidates = rows.filter { it.profile?.level != null && it.profile.level != "ABSTAIN" }
        val best = candidates.maxWithOrNull(compareBy<HistoryObservation> { LEVEL_ORDER.indexOf(it.profile!!.level) }
            .thenBy { it.profile!!.score ?: -1.0 }.thenByDescending { it.observedAtUtc ?: it.time.instantOrNull ?: Instant.MAX }) ?: return null
        val p = best.profile!!
        return ProfileExtreme(p.level!!, p.score, p.evidence, best.provenance, best.observedAtUtc ?: best.time.instantOrNull)
    }

    /** Columnas del historial que forman el perfil local (para la documentación del informe). */
    val COLUMNS: List<String> get() = HistoryColumns.PROFILE
}
