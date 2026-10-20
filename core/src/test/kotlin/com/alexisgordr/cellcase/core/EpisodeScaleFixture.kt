package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.EpisodeEngine
import com.alexisgordr.cellcase.core.analysis.EpisodeSet
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.Hashing
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Historial SINTÉTICO de 40.000 filas (el presupuesto del expediente) con la mitad sub-umbral, para medir el motor
 * de episodios y comprobar que una optimización no cambia ningún episodio (revisión del 20/10/2026, fallo 8).
 * Determinista: sin aleatorios; cada motivo tiene un solo tipo de patrón.
 */
object EpisodeScaleFixture {
    private val REASONS = listOf(
        "[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)",
        "[sub-umbral] Identidad RF inconsistente",
        "[sub-umbral] Potencia anómala vs historial (+19dB)",
        "[sub-umbral] Desviación TAC",
        "[sub-umbral] Proximidad anómala"
    )

    fun csv(rows: Int = 40_000): String {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        var t = LocalDateTime.of(2026, 7, 1, 0, 0, 0)
        val sb = StringBuilder(TestSupport.HEADER).append('\n')
        for (i in 0 until rows) {
            val cell = (i / 37) % 40                       // la celda servidora cambia cada ~18 min
            val sub = (i * 7919) % 2 == 0 && i % 3 != 1 || i % 11 == 0
            val reason = if (sub) REASONS[(i / 5) % REASONS.size] else "OK"
            val pci = 100 + cell + if (i % 13 == 0) 1 else 0
            val band = if (i % 17 == 0) "20" else "3"
            sb.append(TestSupport.row(fmt.format(t), mapOf(
                "CID" to (2_000_000 + cell).toString(), "PCI" to pci.toString(), "ARFCN" to if (band == "20") "6300" else "1850", "Bands" to band,
                "DBM" to (-75 - (i % 31) - if (i % 97 == 0) 25 else 0).toString(), "TAC" to (1000 + cell % 4).toString(),
                "FailedHeuristics" to reason, "TA" to (i % 9).toString()))).append('\n')
            t = t.plusSeconds(30)
        }
        return sb.toString()
    }

    fun episodes(csv: String = csv()): EpisodeSet {
        val r = ImportPipeline.process(csv.toByteArray(), "escala.csv", "e", TestSupport.MADRID)
        val rows = (r.data as ImportedData.History).data.observations
        return EpisodeEngine.build(rows, emptyList(), emptyList(), null, null, null, java.time.ZoneOffset.UTC)
    }

    /** Todo lo que define un episodio, en texto estable. */
    fun fingerprint(s: EpisodeSet): String = buildString {
        for (e in s.episodes) appendLine(listOf(e.number, e.group, e.start, e.end, e.mainPattern, e.patterns.sorted(), e.moments, e.durationKnown, e.persistence,
            e.recurrenceDays, e.recurrenceEpisodes, e.measurement.status, e.measurement.availability, e.quality, e.priority, e.relevance.relevance,
            e.criteria.map { it.code }, e.supports.map { it.code }, e.contradicts.map { it.code }, e.notEvaluable.map { it.code },
            e.signals.size, e.before.size, e.during.size, e.after.size, e.forensicCaseIds).joinToString("|"))
        appendLine("groups=" + s.groups.map { it.episodes })
        appendLine("overall=" + s.overall)
    }

    @JvmStatic fun main(args: Array<String>) {
        val t0 = System.nanoTime(); val s = episodes(); val ms = (System.nanoTime() - t0) / 1_000_000
        val fp = fingerprint(s)
        args.getOrNull(0)?.let { java.io.File(it).writeText(fp) }
        println("${s.episodes.size} episodios en $ms ms · huella ${Hashing.sha256Hex(fp.toByteArray())}")
    }
}
