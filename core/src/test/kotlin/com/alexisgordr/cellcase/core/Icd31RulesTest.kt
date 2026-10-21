package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.Abstention
import com.alexisgordr.cellcase.core.analysis.AbstentionKind
import com.alexisgordr.cellcase.core.analysis.CROSS_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.CrossField
import com.alexisgordr.cellcase.core.analysis.DatasetCuts
import com.alexisgordr.cellcase.core.analysis.NotEvaluated
import com.alexisgordr.cellcase.core.analysis.RULESET_VERSION
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportedData
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
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** ICdetector 3.1.0 en el análisis y el informe: verificación retirada, reglas no evaluadas y corte de dataset. */
class Icd31RulesTest {
    private val csv31 = TestSupport.sample("icd31/SYNTHETIC_icd31_history.csv")
    private val csv30 = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv")

    private fun import(bytes: ByteArray, name: String = "h.csv"): ImportResult = ImportPipeline.process(bytes, name, name, TestSupport.MADRID)
    private fun analyze(vararg r: ImportResult) = CaseAnalyzer.analyze(r.toList(), TestSupport.MADRID, null)
    private val a31 by lazy { analyze(import(csv31.readBytes(), csv31.name)) }

    private fun text(lang: Language, a: CaseAnalysis, mode: ReportMode = ReportMode.FULL): String {
        val ctx = TextContext(CaseInfo("x", "3.1", Instant.EPOCH, "Sintético", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null), a.results,
            ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = mode))
        return ReportBuilder(reportTexts(lang)).build(ctx, a).blocks.joinToString("\n") { b ->
            when (b) { is Block.Heading -> b.text; is Block.Paragraph -> b.text; is Block.KeyValues -> b.rows.joinToString { "${it.first}: ${it.second}" }
                is Block.Labeled -> b.label + ": " + b.items.joinToString(); is Block.Bullets -> b.items.joinToString("\n"); is Block.Callout -> b.title + ": " + b.text
                is Block.Table -> (b.caption ?: "") + "\n" + b.rows.joinToString("\n") { it.joinToString(" | ") }; else -> "" } }
    }
    private fun m(lang: Language, k: String, vararg args: Any) = Messages.format(lang, k, args.toList())

    // ── 3. Verified / ApiLat / ApiLon desde 3.1.0 ──

    @Test fun rowsFrom31AreNotPendingVerification() {
        val rows = a31.history!!.import.observations
        val from31 = rows.count { it.appVersion?.startsWith("3.1.0") == true }
        assertTrue(from31 > 0 && rows.all { !it.externalVerificationRetired || (it.verified == "PENDING" && it.apiLat == null) })
        val col = a31.historyAnalysis!!.collector
        assertEquals(from31, col.verificationRetired)
        assertEquals(rows.size - from31, col.verified.values.sum())          // los PENDING de antes siguen contándose
        assertTrue(col.verificationMixed)
        assertEquals("cellcase-rules/2", RULESET_VERSION)
        for (lang in Language.values()) {
            val t = text(lang, a31)
            assertTrue(t.contains(m(lang, "report.limitations.verification_retired_mixed", from31.toString(), (rows.size - from31).toString()).take(40)))
            assertTrue(t.contains(m(lang, "report.historyAnnex.verification_retired", "").trim().take(30)))
        }
        // Historial solo de 3.0: sin frase de verificación retirada
        val a30 = analyze(import(csv30.readBytes()))
        assertEquals(0, a30.historyAnalysis!!.collector.verificationRetired)
        assertFalse(text(Language.ES, a30).contains(m(Language.ES, "report.limitations.verification_retired_mixed", "", "").take(30)))
    }

    @Test fun appVersionThresholdIs310() {
        fun row(v: String?) = a31.history!!.import.observations.first().copy(appVersion = v)
        assertTrue(row("3.1.0+90627b7").externalVerificationRetired)
        assertTrue(row("3.2.0").externalVerificationRetired)
        assertFalse(row("3.0.0+f5173a8").externalVerificationRetired)
        assertFalse(row(null).externalVerificationRetired)
    }

    /** Fila de 3.1.0 a la hora de la primera muestra del caso 0004 (15:12:00Z, celda 1000542) con la versión [version]. */
    private fun rowAtCase(version: String): String {
        val lines = csv31.readLines(); val head = lines[0].split(",")
        val f = lines.first { "3.1.0+" in it && '"' !in it }.split(",").toMutableList()
        fun set(c: String, v: String) { f[head.indexOf(c)] = v }
        set("Timestamp", "2026-10-28 16:12:00"); set("ObservedAtUtc", "2026-10-28T15:12:00.000Z"); set("CID", "1000542"); set("TAC", "3002")
        set("PCI", "42"); set("ARFCN", "3100"); set("Bands", "7"); set("AppVersion", version); set("Verified", "PENDING"); set("RowId", "1")
        return lines[0] + "\n" + f.joinToString(",") + "\n"
    }

    /** El caso 0004 con `verified` = NOT_FOUND en sus muestras (como una captura de 3.0 sobre la misma celda). */
    private fun caseNotFound(): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(TestSupport.sample("icd31/SYNTHETIC_icd31_ICD-2026-10-28-0004.zip").inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        entries["timeline.csv"] = String(entries.getValue("timeline.csv")).replace("\"PENDING\"", "\"NOT_FOUND\"").toByteArray()
        entries["SHA256SUMS.txt"] = entries.filterKeys { it != "SHA256SUMS.txt" }.entries.joinToString("") { (n, b) -> Hashing.sha256Hex(b) + "  " + n + "\n" }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun crossSourcesDoNotCompareVerifiedOnRowsFrom31() {
        assertEquals("cross/3", CROSS_RULES_VERSION)
        fun verifiedCompared(version: String) = analyze(import(rowAtCase(version).toByteArray()), import(caseNotFound(), "caso.zip"))
            .cross!!.findings.any { f -> f.compared.any { it.field == CrossField.VERIFIED } }
        assertFalse("fila 3.1.0: su PENDING no se compara", verifiedCompared("3.1.0+90627b7"))
        assertTrue("fila 3.0.0: se sigue comparando", verifiedCompared("3.0.0+f5173a8"))
    }

    // ── 4. Motivos de «no evaluada» ──

    @Test fun notEvaluatedPartsAreParsedAndClassified() {
        assertEquals(emptyList<Abstention>(), NotEvaluated.parse("NONE"))
        assertEquals(emptyList<Abstention>(), NotEvaluated.parse(null))
        val p = NotEvaluated.parse("H1(csg);H6(ta_gsm);H9;H11(fix_impreciso);H12(retirada);H17(sombra)")!!
        assertEquals(listOf("H1", "H6", "H9", "H11", "H12", "H17"), p.map { it.rule })
        assertEquals(listOf(AbstentionKind.DATA, AbstentionKind.DATA, AbstentionKind.RESERVED, AbstentionKind.DATA, AbstentionKind.RETIRED, AbstentionKind.SHADOW), p.map { it.kind })
        // El H12 sin motivo de una fila de 3.0 fue una abstención real: se trata como antes, como falta de datos
        assertEquals(AbstentionKind.DATA, NotEvaluated.parse("H12")!!.single().kind)
        for (c in listOf("ta_gsm", "ta_stub", "ta_sin_verificar", "fix_impreciso", "retirada", "sombra")) assertTrue(c in NotEvaluated.KNOWN_REASONS)
        assertNull(NotEvaluated.parse("H1;cosa rara"))
    }

    @Test fun retiredAndShadowRulesAreNotDataGaps() {
        val c = a31.historyAnalysis!!.ruleCoverage!!
        val from31 = a31.history!!.import.observations.count { it.appVersion?.startsWith("3.1.0") == true }
        assertEquals(from31, c.byRule.single { it.abstention.code == "H12(retirada)" }.rows)
        assertEquals(from31, c.byRule.single { it.abstention.code == "H17(sombra)" }.rows)
        // Las filas 3.1.0 que solo tienen H9, H12(retirada) y H17(sombra) no cuentan como falta de datos
        val onlyNonData = a31.history!!.import.observations.count { it.notEvaluatedHeuristics == "H9;H12(retirada);H17(sombra)" }
        val dataRows = a31.history!!.import.observations.count { o -> NotEvaluated.parse(o.notEvaluatedHeuristics)!!.any { it.kind == AbstentionKind.DATA } }
        assertTrue(onlyNonData > 0)
        assertEquals(dataRows, c.rowsWithDataAbstention)
        assertTrue(c.of(AbstentionKind.DATA).none { it.abstention.rule == "H17" || it.abstention.code == "H12(retirada)" })
        assertTrue(c.of(AbstentionKind.DATA).any { it.abstention.code == "H6(ta_sin_verificar)" })
    }

    @Test fun glossaryTextsInBothLanguages() {
        for (lang in Language.values()) {
            val t = text(lang, a31)
            for (code in listOf("retirada", "sombra", "ta_sin_verificar", "ta_stub", "fix_impreciso", "fix_viejo"))
                assertTrue("$lang $code", t.contains(m(lang, "noteval.reason.$code")))
            assertTrue(t.contains("H17 " + m(lang, "rule.H17.name")))
            assertTrue(t.contains("H12 " + m(lang, "rule.H12.name")))
            assertTrue(t.contains(m(lang, "noteval.kind.SHADOW")) && t.contains(m(lang, "noteval.kind.RETIRED")))
        }
        assertEquals("Cambio de celdas", m(Language.ES, "rule.H17.name")); assertEquals("Cell churn", m(Language.EN, "rule.H17.name"))
    }

    // ── 5. Corte de dataset entre 3.0 y 3.1.0 ──

    @Test fun mixedHistoryReportsTheDatasetCut() {
        val v = a31.historyAnalysis!!.versions31
        assertTrue(v.mixed); assertTrue(v.lastBefore!! < v.firstFrom31!!)
        for (f in listOf("H6", "TAUnit", "SecurityScore", "AnomalyConfidence")) assertTrue(f in DatasetCuts.ICD_3_1_0)
        for (lang in Language.values()) {
            val t = text(lang, a31)
            assertTrue(t.contains(m(lang, "dataset_cut.title")))
            for (f in listOf("H6", "H11", "H12", "H14", "TAUnit", "SecurityScore", "AnomalyConfidence", m(lang, "dataset_cut.local_cell_trust")))
                assertTrue("$lang $f", t.substringAfter(m(lang, "dataset_cut.title")).substringBefore("\n").contains(f))
            assertFalse(text(lang, a31, ReportMode.EXPLAINED).contains(m(lang, "dataset_cut.title")))
        }
        val a30 = analyze(import(csv30.readBytes()))
        assertFalse(a30.historyAnalysis!!.versions31.mixed)
        assertFalse(text(Language.ES, a30).contains(m(Language.ES, "dataset_cut.title")))
    }

    @Test fun datasetCutDoesNotChangeEpisodePriorities() {
        // Las mismas filas sin AppVersion (sin corte detectable) dan los mismos episodios y prioridades
        val lines = csv31.readLines(); val i = lines[0].split(",").indexOf("AppVersion")
        val stripped = lines.joinToString("\n") { l -> if ('"' in l) l else l.split(",").toMutableList().also { if (it.size > i && it[0] != "Timestamp") it[i] = "" }.joinToString(",") }
            .lines().filter { '"' !in it || it.startsWith("Timestamp") }.joinToString("\n") + "\n"
        val kept = lines.filter { '"' !in it }.joinToString("\n") + "\n"
        val with = analyze(import(kept.toByteArray())); val without = analyze(import(stripped.toByteArray()))
        assertTrue(with.historyAnalysis!!.versions31.mixed); assertFalse(without.historyAnalysis!!.versions31.mixed)
        assertEquals(without.episodes.episodes.map { listOf(it.start, it.mainPattern, it.priority) }, with.episodes.episodes.map { listOf(it.start, it.mainPattern, it.priority) })
    }
}
