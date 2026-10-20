package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.importing.HistoryColumns
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.report.reportTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Perfil local de ICdetector 3.0 en CellCase: contexto en sombra, sin influencia en la valoración (D-055). */
class LocalProfileTest {
    private val csv = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv").readText()

    private fun result(text: String, name: String = "h.csv") = ImportPipeline.process(text.toByteArray(), name, "id-$name", TestSupport.MADRID)
    private fun analyze(vararg r: ImportResult) = CaseAnalyzer.analyze(r.toList(), TestSupport.MADRID, null)

    /** El mismo historial con las columnas del perfil vacías (como si ICdetector no hubiera evaluado nada). */
    private fun withoutProfile(text: String): String {
        val reader = com.alexisgordr.cellcase.core.csv.CsvReader(text.reader().buffered())
        val h = reader.next()!!.fields
        val prof = h.indices.filter { h[it] in HistoryColumns.PROFILE }.toSet()
        val out = StringBuilder(h.joinToString(",")).append('\n')
        while (true) {
            val r = reader.next() ?: break
            out.append(r.fields.mapIndexed { i, v -> if (i in prof) "" else v }.joinToString(",") { v ->
                if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v }).append('\n')
        }
        return out.toString()
    }

    @Test fun summaryCountsEveryEvaluatedRowOnce() {
        val a = analyze(result(csv))
        val p = a.localProfile!!
        val rows = csv.lines().drop(1).filter { it.isNotEmpty() }
        val header = csv.lines()[0].split(","); val iLevel = header.indexOf("ProfileLevel")
        val expected = rows.filter { !it.contains('"') }.count { it.split(",")[iLevel].isNotEmpty() } +
            rows.filter { it.contains('"') }.count { r -> com.alexisgordr.cellcase.core.csv.CsvReader(r.reader().buffered()).next()!!.fields[iLevel].isNotEmpty() }
        assertEquals(expected, p.evaluated)
        assertEquals(p.evaluated, p.byLevel.values.sum())
        assertEquals(listOf("NONE", "LOW", "MODERATE", "HIGH", "ABSTAIN").filter { it in p.byLevel }, p.byLevel.keys.toList())
        assertEquals(p.byLevel["ABSTAIN"], p.abstainReasons.values.sum())
        assertEquals(mapOf("SHADOW" to p.evaluated), p.modes)
        assertEquals(mapOf(2 to p.evaluated), p.algorithms); assertFalse(p.mixedAlgorithms)
        assertTrue(p.holdout in 1 until p.evaluated)
        assertTrue(p.rowsV3 < p.rows)              // las filas de 2.10.x no tienen perfil
        assertTrue(p.first!! < p.last!!)
        assertTrue(p.references.isEmpty())          // sin copia de seguridad no hay referencias
    }

    @Test fun episodesCarryTheEvaluationsOfTheirRows() {
        val a = analyze(result(csv))
        val p = a.localProfile!!
        assertEquals(a.episodes.episodes.map { it.number }, p.episodes.map { it.episode })
        for ((e, s) in a.episodes.episodes.zip(p.episodes)) {
            assertEquals(e.during.size, s.rows)
            assertEquals(e.during.count { it.profile?.level != null }, s.evaluated)
        }
        // el episodio de potencia anómala (+19 dB) tiene la desviación HIGH, con su lectura y su referencia
        val power = p.episodes.single { it.extreme?.level == "HIGH" }
        assertTrue(power.extreme!!.evidence!!.startsWith("SIGNAL_DBM -81 vs"))
        assertTrue(power.extreme!!.provenance.shortRef().startsWith("h.csv:"))
    }

    @Test fun profileNeverChangesEpisodesPrioritiesOrAssessment() {
        val with = analyze(result(csv)); val without = analyze(result(withoutProfile(csv)))
        assertEquals(with.episodes.episodes.map { listOf(it.number, it.start, it.end, it.priority, it.quality, it.criteria, it.supports) },
            without.episodes.episodes.map { listOf(it.number, it.start, it.end, it.priority, it.quality, it.criteria, it.supports) })
        assertEquals(with.assessment, without.assessment)
        assertEquals(with.cross?.findings?.size, without.cross?.findings?.size)
        assertEquals(0, without.localProfile!!.evaluated)
    }

    @Test fun historiesBefore30HaveNoProfileSection() {
        val a = analyze(ImportPipeline.process(TestSupport.sample("SYNTHETIC_patrones_historial.csv").readBytes(), "p.csv", "p", TestSupport.MADRID))
        assertNull(a.localProfile)
        val doc = ReportBuilder(reportTexts(Language.ES)).build(ctx(ReportMode.FULL, a), a)
        val title = Messages.format(Language.ES, "profile.title", emptyList())
        assertFalse(doc.blocks.filterIsInstance<Block.Heading>().any { it.text.endsWith(title) })
    }

    private fun ctx(mode: ReportMode, a: CaseAnalysis) = TextContext(CaseInfo("x", "Perfil", Instant.EPOCH, "Sintético", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null),
        a.results, ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = mode))

    private fun text(doc: com.alexisgordr.cellcase.core.report.ReportDocument) = doc.blocks.joinToString("\n") { b ->
        when (b) { is Block.Heading -> b.text; is Block.Paragraph -> b.text; is Block.KeyValues -> b.rows.joinToString { "${it.first}: ${it.second}" }
            is Block.Labeled -> b.label + ": " + b.items.joinToString(); is Block.Bullets -> b.items.joinToString(); is Block.Table -> (b.caption ?: "") + b.rows.flatten().joinToString()
            else -> b.toString() } }

    @Test fun allFourReportsShowTheProfileAsContext() {
        val a = analyze(result(csv))
        for (lang in Language.values()) {
            fun m(k: String) = Messages.format(lang, k, emptyList())
            val full = text(ReportBuilder(reportTexts(lang)).build(ctx(ReportMode.FULL, a), a))
            assertTrue("$lang técnico: sección", full.contains(m("profile.title")))
            assertTrue("$lang técnico: contexto", full.contains(m("profile.context")))
            assertTrue("$lang técnico: limitación", full.contains(m("profile.limitation")))
            assertTrue("$lang técnico: tabla por episodio", full.contains(Messages.format(lang, "profile.caption", listOf("profile/1"))))
            assertTrue("$lang técnico: versión", full.contains("profile/1"))
            val plain = text(ReportBuilder(reportTexts(lang)).build(ctx(ReportMode.EXPLAINED, a), a))
            assertTrue("$lang explicado: fila de la observación", plain.contains(m("profile.lay.label")))
            assertTrue("$lang explicado: advertencia", plain.contains(m("profile.lay.limit")))
            assertTrue("$lang explicado: desviación sin alarma", plain.contains(Messages.format(lang, "profile.lay.deviated", listOf(1, m("profile.level.HIGH"))).take(40)))
            ReportBuilder(reportTexts(lang)).writePdf(ctx(ReportMode.FULL, a), a, java.io.ByteArrayOutputStream())
        }
    }

    @Test fun withoutEvaluationsTheReportSaysWhy() {
        val a = analyze(result(withoutProfile(csv)))
        for (lang in Language.values()) {
            val full = text(ReportBuilder(reportTexts(lang)).build(ctx(ReportMode.FULL, a), a))
            assertTrue(full.contains(Messages.format(lang, "profile.none_evaluated", listOf(a.localProfile!!.rows, a.localProfile!!.rowsV3))))
            val plain = text(ReportBuilder(reportTexts(lang)).build(ctx(ReportMode.EXPLAINED, a), a))
            assertFalse(plain.contains(Messages.format(lang, "profile.lay.label", emptyList())))
        }
    }

    @Test fun mixedAlgorithmVersionsAreFlagged() {
        val h = csv.lines()[0].split(","); val iAlg = h.indexOf("ProfileAlgorithm")
        var changed = 0
        val mixed = csv.lines().joinToString("\n") { l ->
            val f = l.split(",")
            if (changed < 5 && !l.contains('"') && f.size == h.size && f[iAlg] == "2") { changed++; f.toMutableList().also { it[iAlg] = "1" }.joinToString(",") } else l
        }
        val p = analyze(result(mixed)).localProfile!!
        assertTrue(p.mixedAlgorithms); assertEquals(5, p.algorithms[1])
        val full = text(ReportBuilder(reportTexts(Language.EN)).build(ctx(ReportMode.FULL, analyze(result(mixed))), analyze(result(mixed))))
        assertTrue(full.contains(Messages.format(Language.EN, "profile.mixed", emptyList())))
    }

    @Test fun backupAddsTheLearnedReferences() {
        val r = ImportPipeline.processFile(TestSupport.sample("icd30/SYNTHETIC_icd30_fresh.icdbackup"), "c.icdbackup", "c", TestSupport.MADRID)
        assertEquals(mapOf("MATURE" to 6), analyze(r).localProfile!!.references)
    }
}
