package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.Priority
import com.alexisgordr.cellcase.core.analysis.SpoofingRelevance
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportDocument
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.ReportTextsEs
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.time.DeclaredZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId

/** Informe explicado (D-036): mismas conclusiones que el técnico, sin tecnicismos ni alarmismo. */
class ExplainedReportTest {
    private val SUB_BAND = "[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)"
    private fun r(ts: String, cid: String, earfcn: Int, reason: String = "OK", extra: Map<String, String> = emptyMap()) =
        row(ts, mapOf("CID" to cid, "ARFCN" to earfcn.toString(), "Bands" to "", "FailedHeuristics" to reason) + extra)

    private fun results(vararg files: Pair<String, String>, zone: DeclaredZone = TestSupport.MADRID): List<ImportResult> =
        files.mapIndexed { i, (n, t) -> ImportPipeline.process(t.toByteArray(), n, "i$i", zone) }

    private fun docs(res: List<ImportResult>, zone: DeclaredZone = TestSupport.MADRID): Triple<CaseAnalysis, ReportDocument, ReportDocument> {
        val a = CaseAnalyzer.analyze(res, zone, null)
        fun ctx(m: ReportMode) = TextContext(CaseInfo("c", "Prueba", Instant.EPOCH, "Teléfono de prueba", zone, DataOrigin.SYNTHETIC, null, null), res,
            ReportOptions(generatedAt = Instant.parse("2026-10-02T08:00:00Z"), appVersion = "t", mode = m))
        return Triple(a, ReportBuilder(ReportTextsEs).build(ctx(ReportMode.EXPLAINED), a), ReportBuilder(ReportTextsEs).build(ctx(ReportMode.FULL), a))
    }

    private fun text(d: ReportDocument) = d.blocks.joinToString("\n") { b ->
        when (b) {
            is Block.Paragraph -> b.text; is Block.Bullets -> b.items.joinToString("\n"); is Block.Labeled -> b.label + "\n" + b.items.joinToString("\n")
            is Block.Heading -> b.text; is Block.Callout -> b.title + "\n" + b.text
            is Block.Table -> (b.caption ?: "") + "\n" + b.rows.joinToString("\n") { it.joinToString(" | ") }
            is Block.KeyValues -> b.rows.joinToString("\n") { "${it.first}: ${it.second}" }
            is Block.Cover -> b.title + b.fields.joinToString { it.second } + (b.notice ?: "")
            is Block.Timeline -> b.title + b.caption + b.lanes.joinToString()
            is Block.PointChart -> b.title + b.caption
            else -> ""
        }
    }

    /** Vocabulario que no debe aparecer en el informe explicado. */
    private fun assertPlain(d: ReportDocument) {
        val t = text(d)
        for (w in listOf("PCI", "EARFCN", "ARFCN", "sub-umbral", "subumbral", "IMSI catcher detectado", "seguro", "sin vigilancia", "forzado", "Downgrade", "probabilidad", "001-01-"))
            assertFalse("aparece «$w»", t.contains(w, ignoreCase = w != "PCI"))
        assertFalse("códigos de banda", Regex("""\bB\d{1,2}\b""").containsMatchIn(t))
        assertFalse("tablas de parámetros en el cuerpo", d.blocks.any { it is Block.Table })
        assertEquals("«IMSI catcher» se define una sola vez", 1, Regex("IMSI catcher").findAll(t).count())
        assertTrue(t.contains("Una estación base falsa (a veces llamada «IMSI catcher»)"))
    }

    private fun assertSameConclusion(a: CaseAnalysis, ex: ReportDocument, full: ReportDocument) {
        val st = ReportTextsEs.overallStatement(a)
        assertTrue(text(ex).contains("Estado: " + st.removeSuffix(".")))
        assertTrue(text(full).contains(st))
        assertFalse(text(ex).contains(ReportTextsEs.relevanceLabel(SpoofingRelevance.SPECIALIST)))
        assertFalse(text(full).contains(ReportTextsEs.relevanceLabel(SpoofingRelevance.SPECIALIST)))
        // Cada episodio tiene la misma relevancia en la tabla técnica y en el grupo del explicado
        val table = full.blocks.filterIsInstance<Block.Table>().firstOrNull { it.caption?.startsWith("Incidencias") == true }
        for (e in a.episodes.episodes) {
            val g = a.episodes.observationGroups.single { e.number in it.episodes }
            assertEquals(e.relevance.relevance, g.relevance)
            table?.rows?.firstOrNull { it[0] == "E${e.number}" }?.let { assertEquals(ReportTextsEs.relevanceShort(e.relevance.relevance), it[6]) }
            assertTrue(text(ex).contains("${g.number}. ") && text(ex).contains(ReportTextsEs.relevanceShort(g.relevance)))
        }
    }

    @Test fun caseWithoutObservations() {
        val res = results("h.csv" to csv(r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:05:00", "5001", 1300)))
        val (a, ex, full) = docs(res)
        assertEquals(SpoofingRelevance.NO_INDICATION, a.assessment.overall)
        assertTrue(text(ex).contains("ICdetector no registró ningún aviso"))
        assertTrue(text(ex).contains("no demuestra que no haya habido ningún problema"))
        assertPlain(ex); assertSameConclusion(a, ex, full)
    }

    @Test fun caseWithInsufficientData() {
        // Solo Radio: no hay historial ni casos forenses
        val radio = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source\n" +
            "2026-09-25T08:00:00Z,IN_SERVICE,1,1,0,0,00101,P,00101,0,1300,20000,LTE,LTE,POLL\n"
        val (a, ex, full) = docs(results("radio.csv" to radio))
        assertTrue(text(ex).contains("no es un resultado tranquilizador", ignoreCase = true))
        assertTrue(text(ex).contains("Exporta desde ICdetector el historial"))
        assertPlain(ex); assertSameConclusion(a, ex, full)
        // Zona sin confirmar y hora ambigua: los registros sin hora única necesitan más datos
        val unconfirmed = DeclaredZone(ZoneId.of("Europe/Madrid"), false)
        val (a2, ex2, full2) = docs(results("h.csv" to csv(r("2026-09-25 10:05:00", "5002", 6300, SUB_BAND)), zone = unconfirmed), unconfirmed)
        assertEquals(SpoofingRelevance.NEEDS_MORE_DATA, a2.assessment.overall)
        assertTrue(text(ex2).contains("no se pudieron situar en el tiempo"))
        assertPlain(ex2); assertSameConclusion(a2, ex2, full2)
    }

    @Test fun repeatedCommonBandChangesStayWithoutIndicationEvenIfTechnicallyInteresting() {
        val (a, ex, full) = docs(results("h.csv" to csv(
            r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:05:00", "5002", 6300, SUB_BAND),
            r("2026-09-29 18:00:00", "5001", 1300), r("2026-09-29 18:05:00", "5002", 6300, SUB_BAND),
            r("2026-09-29 18:20:00", "5001", 1300), r("2026-09-29 18:25:00", "5003", 6300, SUB_BAND))))
        assertTrue(a.episodes.episodes.any { it.priority == Priority.REVIEW })          // interés técnico
        assertEquals(SpoofingRelevance.NO_INDICATION, a.assessment.overall)              // pero sin indicios
        assertEquals(1, a.episodes.observationGroups.size)                               // tres episodios, una observación
        assertTrue(text(ex).contains("que se repita no aumenta la sospecha"))
        assertTrue(text(ex).contains("Es interés técnico, no un motivo para sospechar"))
        assertPlain(ex); assertSameConclusion(a, ex, full)
    }

    @Test fun caseWithRelevantObservations() {
        val rf = "[sub-umbral] Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)"
        val (a, ex, full) = docs(results("h.csv" to csv(
            r("2026-09-29 13:53:00", "6001", 1300, extra = mapOf("PCI" to "12")), r("2026-09-29 13:53:44", "6001", 1300, rf, mapOf("PCI" to "13")),
            r("2026-09-29 13:55:00", "6001", 1300, extra = mapOf("PCI" to "12")),
            r("2026-09-30 09:00:00", "6005", 1300), r("2026-09-30 09:00:30", "6006", 1300, "Desviación TAC"))))
        assertEquals(SpoofingRelevance.NEEDS_MORE_DATA, a.assessment.overall)
        val t = text(ex)
        assertTrue(t.contains("Observaciones que necesitan más datos"))
        assertTrue(t.contains("Ninguna observación permite atribuir un ataque"))
        assertTrue(t.contains("no alcanzó el umbral de alarma de ICdetector", ignoreCase = true))
        assertTrue(t.contains("ICdetector lo marcó como alarma"))
        assertPlain(ex); assertSameConclusion(a, ex, full)
    }

    @Test fun specialistStateIsNeverAssignedWithoutEvaluatedCriteria() {
        assertFalse(com.alexisgordr.cellcase.core.analysis.SPECIALIST_CRITERIA_EVALUATED)
        val (a, _, _) = docs(results("h.csv" to csv(r("2026-09-25 10:00:00", "5001", 1300), r("2026-09-25 10:00:30", "5002", 1300, "Desviación TAC | Salto potencia (>35dB)"))))
        assertTrue(a.episodes.episodes.none { it.relevance.relevance == SpoofingRelevance.SPECIALIST })
    }

    @Test fun patternsSampleExplainedFitsInFewPagesAndMatchesTechnical() {
        val names = GenerateSampleReport.PATTERNS
        val res = names.mapIndexed { i, n -> ImportPipeline.process(TestSupport.sample(n).readBytes(), n, "p$i", TestSupport.MADRID) }
        val (a, ex, full) = docs(res)
        assertPlain(ex); assertSameConclusion(a, ex, full)
        assertTrue("8 episodios se agrupan en pocas observaciones", a.episodes.observationGroups.size < a.episodes.episodes.size)
        val ctx = TextContext(CaseInfo("c", "Prueba", Instant.EPOCH, "Teléfono", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null), res,
            ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = ReportMode.EXPLAINED))
        val pages = ReportBuilder(ReportTextsEs).writePdf(ctx, a, ByteArrayOutputStream())
        assertTrue("páginas: $pages", pages in 2..4)
    }
}
