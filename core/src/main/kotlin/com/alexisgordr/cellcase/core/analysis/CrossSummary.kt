package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.model.Provenance
import java.time.Instant

/**
 * Presentación resumida de los cruces (docs/cross-sources.md §6). No calcula nada nuevo: reparte los resultados de
 * [CrossResult] en grupos. Cada resultado pertenece a un solo grupo del resumen, así que los totales del resumen son
 * los del listado completo. Los totales cuentan RESULTADOS DE COMPROBACIONES (una fila, una celda, una ruta, un
 * episodio de Stable-Site…), no incidentes ni evidencias independientes.
 */
data class CrossGroup(
    val check: CrossCheck,
    val outcome: CrossOutcome,
    val reason: CrossReason,
    val sourceA: ExportKind,
    val sourcesB: List<ExportKind>,
    /** Resultados del grupo, en el orden canónico del motor (independiente del orden de importación). */
    val findings: List<CrossFinding>
) {
    val results: Int get() = findings.size
    /** Elementos comparados (suma de [CrossFinding.count]): filas, celdas, rutas… según la comprobación. */
    val items: Int get() = findings.sumOf { it.count }
    val start: Instant? get() = findings.mapNotNull { it.start }.minOrNull()
    val end: Instant? get() = findings.mapNotNull { it.end ?: it.start }.maxOrNull()
    val entities: List<String> get() = findings.flatMap { it.entities }.distinct()
    val episodes: List<Int> get() = findings.flatMap { it.episodes }.distinct().sorted()
    val derivation: Derivation get() = findings.first().derivation
    val next: CrossNext? get() = findings.firstNotNullOfOrNull { it.next }
    /** Unos pocos resultados representativos: los primeros en el orden canónico. */
    fun examples(n: Int): List<CrossFinding> = findings.take(n)
    fun refs(n: Int): List<Provenance> = examples(n).flatMap { it.refs.take(2) }.distinct()
}

data class CrossView(
    val result: CrossResult,
    /** Todos los resultados agrupados por comprobación, resultado, motivo y fuentes. */
    val summary: List<CrossGroup>,
    /**
     * Diferencias y resultados vinculados a episodios, agrupados además por episodios para no repetir el mismo
     * contexto. Es un subconjunto: sus resultados también están en [summary].
     */
    val detail: List<CrossGroup>
) {
    fun total(o: CrossOutcome): Int = summary.filter { it.outcome == o }.sumOf { it.results }
    val totalResults: Int get() = summary.sumOf { it.results }
}

object CrossSummary {
    fun of(r: CrossResult): CrossView {
        fun groups(list: List<CrossFinding>, byEpisodes: Boolean): List<CrossGroup> =
            list.groupBy { f -> listOf(if (byEpisodes) f.episodes else null, f.check, f.outcome, f.reason, f.sourceA, f.sourcesB) }
                .values.map { l -> l[0].let { f -> CrossGroup(f.check, f.outcome, f.reason, f.sourceA, f.sourcesB, l) } }
        val summary = groups(r.findings, false)
            .sortedWith(compareBy<CrossGroup>({ it.check.ordinal }, { it.sourcesB.first().ordinal }, { it.outcome.ordinal }, { it.reason.ordinal }))
        val detail = groups(r.findings.filter { it.outcome == CrossOutcome.DIFFERENCE || it.episodes.isNotEmpty() }, true)
            .sortedWith(compareBy<CrossGroup>({ it.episodes.firstOrNull() ?: Int.MAX_VALUE }, { it.check.ordinal }, { it.sourcesB.first().ordinal },
                { it.outcome.ordinal }, { it.reason.ordinal }))
        return CrossView(r, summary, detail)
    }
}
