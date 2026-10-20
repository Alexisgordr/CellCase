package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.SelectedFile

/** Imprime los cruces entre fuentes de un expediente de muestra (herramienta de desarrollo; no es una prueba). */
object CrossProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val names = if (args.firstOrNull() == "PATRONES") GenerateSampleReport.PATTERNS else GenerateSampleReport.ALL
        var n = 0
        val outcomes = BatchImport.run(names.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
        val results = outcomes.mapNotNull { (it as? BatchOutcome.Processed)?.result }
        val a = CaseAnalyzer.analyze(results, TestSupport.MADRID, null)
        val c = a.cross!!
        println("sources=${c.sources} pairs=${c.pairsChecked} why=${c.why}")
        c.findings.forEach { f ->
            println("${f.check.id} ${f.sourceA}->${f.sourceB} ${f.outcome} ${f.reason} n=${f.count} ents=${f.entities.take(4)} ${f.start}..${f.end} eps=${f.episodes} der=${f.derivation} next=${f.next}")
            f.compared.take(4).forEach { println("     $it") }
            println("     refs=${f.refs.map { it.shortRef() }}")
        }
    }
}
