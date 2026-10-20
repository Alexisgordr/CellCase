package com.alexisgordr.cellcase.core.i18n

/**
 * Traducción EXPLICATIVA al inglés de los motivos que escribe ICdetector 2.10.5 (siempre en español: son literales
 * de `ThreatAnalyzer`, `TemporalConfidence`, `LocalCellTrust` y `ThreatEpisodeTracker`, no recursos traducibles;
 * docs/i18n.md §4). No sustituye al original: el informe muestra siempre la cita original y, aparte, esta traducción.
 * No interviene en el reconocimiento de patrones ([com.alexisgordr.cellcase.core.analysis.ReasonParser]).
 *
 * Las cifras y unidades del original se copian tal cual. Un motivo que no está en la lista se deja sin traducir.
 */
object ReasonGlossary {
    private val prefixes = listOf(
        Regex("""^\[sub-umbral]\s*""") to { _: MatchResult -> "[sub-threshold] " },
        Regex("""^\[(\d+)/(\d+) ciclos confirmando]\s*""") to { m: MatchResult -> "[${m.groupValues[1]}/${m.groupValues[2]} cycles confirming] " }
    )

    private val parts: List<Pair<Regex, (MatchResult) -> String>> = listOf(
        r("""Celda aislada""") { "Isolated cell" },
        r("""Salto potencia \(>35dB\)""") { "Power jump (>35dB)" },
        r("""Inconsistencia MCC""") { "MCC inconsistency" },
        r("""Multitud de MNCs""") { "Many MNCs" },
        r("""Desviación TAC""") { "TAC deviation" },
        r("""Suplantación TA""") { "TA spoofing" },
        r("""Proximidad anómala \(TA\)""") { "Anomalous proximity (TA)" },
        r("""Vecinos fantasma""") { "Phantom neighbours" },
        r("""Frecuencia \(ARFCN\) 5G sospechosa""") { "Suspicious 5G frequency (ARFCN)" },
        r("""Frecuencia \(EARFCN\) 4G sospechosa""") { "Suspicious 4G frequency (EARFCN)" },
        r("""Cifrado de red anulado \(A5/0\)""") { "Network ciphering disabled (A5/0)" },
        r("""Efecto Ping-Pong \(Cambios rápidos en parado\)""") { "Ping-pong effect (rapid changes while stationary)" },
        r("""RF anómalo \+ latencia: posible MITM \(Experimental\)""") { "Anomalous RF + latency: possible MITM (experimental)" },
        r("""Potencia anómala vs historial \(([+-]?\d+)dB\)""") { "Anomalous power vs history (${it.groupValues[1]}dB)" },
        r("""Huella RF incoherente con el historial \(RSRQ/SINR muy desviados\)""") { "RF fingerprint inconsistent with history (RSRQ/SINR far off)" },
        r("""Downgrade de banda forzado (\(.*\))""") { "Forced band downgrade ${it.groupValues[1]}" },
        r("""Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora \(posible clon\)""") { "Unstable RF identity: same Cell ID alternating PCI on the same carrier (possible clone)" },
        r("""Transición celular incoherente""") { "Inconsistent cell transition" },
        r("""Consistencia geográfica fallida""") { "Geographic consistency check failed" },
        r("""Inconsistencia RF \(PCI/ARFCN\) detectada a gran distancia""") { "RF inconsistency (PCI/ARFCN) detected at a long distance" },
        r("""Celda detectada a distancia anómala \((.*)\)""") { "Cell detected at an anomalous distance (${it.groupValues[1]})" },
        r("""Cambio en identidad celular consolidada(.*)""") { "Change in consolidated cell identity${it.groupValues[1]}" },
        r("""Episodio multiseñal (\(.*\))""") { "Multi-signal episode ${it.groupValues[1]}" }
    )

    private fun r(p: String, f: (MatchResult) -> String) = Regex("^$p$") to f

    /**
     * Traducción explicativa de un motivo completo (con varios motivos separados por « | »), o null si no se
     * reconoce ninguno. Los motivos no reconocidos se dejan en el original.
     */
    fun english(original: String): String? {
        var known = false
        val out = original.split(" | ").joinToString(" | ") { raw ->
            var part = raw.trim(); var prefix = ""
            for ((re, f) in prefixes) re.find(part)?.let { m -> prefix += f(m); part = part.substring(m.range.last + 1) }
            if (part.startsWith("[site-unverified]")) { prefix += "[site-unverified] "; part = part.removePrefix("[site-unverified]").trim() }
            val hit = parts.firstNotNullOfOrNull { (re, f) -> re.matchEntire(part)?.let(f) }
            if (hit != null || prefix.isNotEmpty()) known = true
            prefix + (hit ?: part)
        }
        return if (known) out.trim() else null
    }

    /** Traducción explicativa en [lang], o null para el español (idioma del original) o si no se reconoce. */
    fun explain(original: String, lang: Language): String? = if (lang == Language.ES) null else english(original)
}
