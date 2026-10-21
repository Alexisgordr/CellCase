package com.alexisgordr.cellcase.core.analysis

import com.alexisgordr.cellcase.core.model.HistoryObservation
import com.alexisgordr.cellcase.core.model.Provenance

/**
 * Reglas que ICdetector no evaluó en una fila (`NotEvaluatedHeuristics`, desde 3.0): `H1;H6(ta_stub);H9` o `NONE`.
 * Un motivo entre paréntesis dice por qué (`HeuristicReport.abstentionCode` de ICdetector).
 *
 * No toda regla «no evaluada» es una falta de datos (docs/history-csv-columns.md de ICdetector 3.1.0):
 * - `H12(retirada)`: la regla de latencia + RF se retiró en 3.1.0 y aparece en todas sus filas;
 * - `H17(sombra)`: el cambio de celdas se calcula y se guarda, pero no se evalúa (modo sombra);
 * - `H9`: el cifrado está reservado desde 3.0 (Android no lo expone), siempre sin evaluar.
 * Esas tres no cuentan como «regla que no pudo evaluar» ni como problema de calidad. Un `H12` sin motivo de una fila
 * de 3.0 sí fue una abstención real (por ejemplo, sin medida de latencia) y se trata como las demás.
 */
enum class AbstentionKind { DATA, RETIRED, SHADOW, RESERVED }

data class Abstention(val rule: String, val reason: String?) {
    val kind: AbstentionKind get() = when {
        rule == "H12" && reason == NotEvaluated.RETIRED -> AbstentionKind.RETIRED
        rule == "H17" && reason == NotEvaluated.SHADOW -> AbstentionKind.SHADOW
        rule == "H9" -> AbstentionKind.RESERVED
        else -> AbstentionKind.DATA
    }
    val code: String get() = if (reason == null) rule else "$rule($reason)"
}

/** Filas con una regla no evaluada por el mismo motivo. */
data class RuleAbstention(val abstention: Abstention, val rows: Int, val example: Provenance)

/** Cobertura de reglas del historial: cuántas filas traen el campo y qué reglas no se evaluaron, con su clase. */
data class RuleCoverage(
    /** Filas con `NotEvaluatedHeuristics` (3.0 o posterior). */
    val rowsWithField: Int,
    /** Filas con al menos una regla no evaluada por falta de datos o de contexto. */
    val rowsWithDataAbstention: Int,
    /** Ordenadas por regla (H1…H17) y motivo. */
    val byRule: List<RuleAbstention>,
    /** Partes que no siguen el formato `Hn` o `Hn(motivo)`: se conservan tal cual. */
    val unreadable: Int
) {
    fun of(kind: AbstentionKind) = byRule.filter { it.abstention.kind == kind }
}

object NotEvaluated {
    const val NONE = "NONE"
    const val RETIRED = "retirada"
    const val SHADOW = "sombra"

    private val PART = Regex("""(H\d{1,2})(?:\(([^()]*)\))?""")

    /** Motivos conocidos (ICdetector 3.0.0 y 3.1.0); tienen texto en el catálogo (`noteval.reason.<código>`). */
    val KNOWN_REASONS = setOf(
        "csg", "roaming", "frontera", "sin_sim", "sin_servicestate", "sin_mcc_servidora", "fix_viejo",
        "fix_impreciso", "ta_gsm", "ta_stub", "ta_sin_verificar", RETIRED, SHADOW
    )

    /** Partes de un `NotEvaluatedHeuristics`; null si alguna parte no se entiende. Vacío para `NONE`. */
    fun parse(text: String?): List<Abstention>? {
        val t = text?.trim() ?: return emptyList()
        if (t.isEmpty() || t == NONE) return emptyList()
        return t.split(';').map { p -> PART.matchEntire(p.trim())?.let { m -> Abstention(m.groupValues[1], m.groupValues[2].ifEmpty { null }) } ?: return null }
    }

    fun coverage(rows: List<HistoryObservation>): RuleCoverage? {
        val withField = rows.filter { it.notEvaluatedHeuristics != null }
        if (withField.isEmpty()) return null
        val counts = LinkedHashMap<Abstention, Int>(); val examples = HashMap<Abstention, Provenance>()
        var unreadable = 0; var dataRows = 0
        for (o in withField) {
            val parts = parse(o.notEvaluatedHeuristics) ?: run { unreadable++; null } ?: continue
            if (parts.any { it.kind == AbstentionKind.DATA }) dataRows++
            for (a in parts.distinct()) { counts.merge(a, 1, Int::plus); examples.putIfAbsent(a, o.provenance) }
        }
        val order = compareBy<Abstention> { it.rule.drop(1).toInt() }.thenBy { it.reason ?: "" }
        return RuleCoverage(withField.size, dataRows, counts.keys.sortedWith(order).map { RuleAbstention(it, counts.getValue(it), examples.getValue(it)) }, unreadable)
    }
}
