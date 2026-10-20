package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.importing.CaseBudget
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.pdf.PdfFont
import com.alexisgordr.cellcase.core.pdf.ReportRenderer
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Revisión del 20/10/2026, fallos 13 y 14 (PDF, integridad de Geometría y Stable-Site, recuento de filas). */
class ReviewMinorTest {

    // ── 13a. Figura de PCI con 60 pares PCI/EARFCN ──

    private fun pciCase(): Pair<TextContext, com.alexisgordr.cellcase.core.analysis.CaseAnalysis> {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val t0 = LocalDateTime.of(2026, 7, 1, 10, 0, 0)
        val rows = (0 until 60).map { i ->
            TestSupport.row(fmt.format(t0.plusSeconds(30L * i)), mapOf("PCI" to (100 + i).toString(), "ARFCN" to (if (i % 2 == 0) "1850" else "6300"),
                "FailedHeuristics" to if (i == 30) "[sub-umbral] Identidad RF inconsistente" else "OK"))
        }
        val r = ImportPipeline.process(TestSupport.csv(*rows.toTypedArray()).toByteArray(), "pci.csv", "p", TestSupport.MADRID)
        val a = CaseAnalyzer.analyze(listOf(r), TestSupport.MADRID, null)
        val ctx = TextContext(CaseInfo("x", "PCI", Instant.EPOCH, "Sintético", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null),
            a.results, ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = ReportMode.FULL))
        return ctx to a
    }

    @Test fun pciChartWithSixtyPairsKeeps24LanesAndANote() {
        val (ctx, a) = pciCase()
        for (lang in Language.values()) {
            val doc = ReportBuilder(reportTexts(lang)).build(ctx, a)
            val chart = doc.blocks.filterIsInstance<Block.Timeline>().single { it.lanes.any { l -> "EARFCN" in l } }
            assertEquals(24, chart.lanes.size)
            assertTrue(chart.marks.all { it.lane in chart.lanes.indices })
            assertEquals(60, chart.marks.size)                       // ninguna fila se pierde: las demás van a «otros»
            val note = com.alexisgordr.cellcase.core.i18n.Messages.format(lang, "report.pciChart.others_note", listOf(37, 23))
            assertTrue(chart.caption, chart.caption.contains(note))
            val out = ByteArrayOutputStream()
            val pages = ReportBuilder(reportTexts(lang)).writePdf(ctx, a, out)
            val pdf = String(out.toByteArray(), Charsets.ISO_8859_1)
            assertTrue(pages > 0 && pdf.startsWith("%PDF-") && pdf.trimEnd().endsWith("%%EOF"))
        }
    }

    @Test fun fewPairsKeepOneLaneEach() {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val rows = (0 until 6).map { i -> TestSupport.row(fmt.format(LocalDateTime.of(2026, 7, 1, 10, 0).plusMinutes(i.toLong())),
            mapOf("PCI" to (100 + i % 3).toString(), "FailedHeuristics" to if (i == 3) "[sub-umbral] Identidad RF inconsistente" else "OK")) }
        val r = ImportPipeline.process(TestSupport.csv(*rows.toTypedArray()).toByteArray(), "pci.csv", "p", TestSupport.MADRID)
        val a = CaseAnalyzer.analyze(listOf(r), TestSupport.MADRID, null)
        val ctx = TextContext(CaseInfo("x", "PCI", Instant.EPOCH, "S", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null), a.results,
            ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = ReportMode.FULL))
        val chart = ReportBuilder(reportTexts(Language.ES)).build(ctx, a).blocks.filterIsInstance<Block.Timeline>().single { it.lanes.any { l -> "EARFCN" in l } }
        assertEquals(3, chart.lanes.size)
        assertFalse(chart.caption.contains(com.alexisgordr.cellcase.core.i18n.Messages.format(Language.ES, "report.pciChart.others", emptyList())))
    }

    // ── 13b. Ajuste de una palabra muy larga ──

    /** El algoritmo anterior (cuadrático), para comprobar que el nuevo da exactamente lo mismo. */
    private fun oldWrapWord(word: String, font: PdfFont, size: Float, width: Float): List<String> {
        val out = ArrayList<String>(); var w = word
        while (font.width(w, size) > width && w.length > 1) {
            var k = w.length
            while (k > 1 && font.width(w.substring(0, k), size) > width) k--
            out += w.substring(0, k); w = w.substring(k)
        }
        out += w; return out
    }

    @Test fun longWordWrapsLikeBeforeAndFast() {
        for (word in listOf("a", "W".repeat(7), "MWiil".repeat(40), "x".repeat(333), "ñandú".repeat(50)))
            for (font in PdfFont.values()) for (width in listOf(5f, 40f, 120f))
                assertEquals("$font $width ${word.take(10)}", oldWrapWord(word, font, 9f, width), ReportRenderer.wrap(word, font, 9f, width))
        // con texto antes y después de la palabra larga
        assertEquals(listOf("hola"), ReportRenderer.wrap("hola", PdfFont.REGULAR, 9f, 200f))
        val mixed = ReportRenderer.wrap("uno dos " + "z".repeat(200) + " tres", PdfFont.REGULAR, 9f, 100f)
        assertEquals("uno dos", mixed.first()); assertTrue(mixed.last().endsWith("tres"))
        // 20.000 caracteres sin espacios
        val t0 = System.nanoTime()
        val lines = ReportRenderer.wrap("q".repeat(20_000), PdfFont.REGULAR, 9f, 450f)
        assertTrue((System.nanoTime() - t0) / 1e9 < 2.0)
        assertEquals(20_000, lines.sumOf { it.length })
    }

    @Test fun reportWithA20000CharacterNoteRendersInUnderTwoSeconds() {
        val r = ImportPipeline.process(TestSupport.sample("SYNTHETIC_patrones_historial.csv").readBytes(), "h.csv", "h", TestSupport.MADRID)
        val a = CaseAnalyzer.analyze(listOf(r), TestSupport.MADRID, null)
        val ctx = TextContext(CaseInfo("x", "Nota", Instant.EPOCH, "S", TestSupport.MADRID, DataOrigin.SYNTHETIC, "n".repeat(20_000), null), a.results,
            ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = ReportMode.FULL))
        ReportBuilder(reportTexts(Language.ES)).writePdf(ctx, a, ByteArrayOutputStream())   // calentamiento
        val t0 = System.nanoTime()
        val pages = ReportBuilder(reportTexts(Language.ES)).writePdf(ctx, a, ByteArrayOutputStream())
        assertTrue("tardó ${(System.nanoTime() - t0) / 1e9} s", (System.nanoTime() - t0) / 1e9 < 2.0)
        assertTrue(pages > 0)
    }

    // ── 14. SHA256SUMS en Geometría y Stable-Site ──

    private fun withSums(name: String, wrong: Boolean): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(TestSupport.sample(name).inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        val first = entries.keys.first()
        entries["SHA256SUMS.txt"] = entries.entries.joinToString("") { (n, b) ->
            (if (wrong && n == first) "0".repeat(64) else Hashing.sha256Hex(b)) + "  " + n + "\n" }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }

    @Test fun wrongSha256SumsInGeometryIsReported() {
        val bad = ImportPipeline.process(withSums("SYNTHETIC_ICD-geometry.zip", true), "g.zip", "g", TestSupport.MADRID)
        assertEquals(ImportState.IMPORTED, bad.state)
        assertTrue(bad.issues.any { it.code == "SHA256_MISMATCH" })
        val good = ImportPipeline.process(withSums("SYNTHETIC_ICD-geometry.zip", false), "g.zip", "g", TestSupport.MADRID)
        assertFalse(good.issues.any { it.code.startsWith("SHA256_") })
        // sin SHA256SUMS.txt (lo normal en ICdetector): ningún aviso de integridad
        val plain = ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-geometry.zip").readBytes(), "g.zip", "g", TestSupport.MADRID)
        assertFalse(plain.issues.any { it.code.startsWith("SHA256_") })
    }

    @Test fun wrongSha256SumsInStableSiteIsReported() {
        val bad = ImportPipeline.process(withSums("SYNTHETIC_ICD-stable-site.zip", true), "s.zip", "s", TestSupport.MADRID)
        assertEquals(ImportState.IMPORTED, bad.state)
        assertTrue(bad.issues.any { it.code == "SHA256_MISMATCH" })
        val good = ImportPipeline.process(withSums("SYNTHETIC_ICD-stable-site.zip", false), "s.zip", "s", TestSupport.MADRID)
        assertFalse(good.issues.any { it.code.startsWith("SHA256_") })
        val plain = ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-stable-site.zip").readBytes(), "s.zip", "s", TestSupport.MADRID)
        assertFalse(plain.issues.any { it.code.startsWith("SHA256_") })
    }

    // ── 14. Recuento de filas con una comilla suelta ──

    @Test fun strayQuoteDoesNotSwallowTheRestOfTheFile() {
        val sb = StringBuilder(TestSupport.HEADER).append('\n')
        for (i in 0 until 40_000) sb.append(TestSupport.row("2026-07-01 10:00:00", mapOf("CID" to if (i == 0) "foo\"bar" else "1"))).append('\n')
        val bytes = sb.toString().toByteArray()
        assertEquals(40_000, CaseBudget.csvRows(bytes))
        // y coincide con lo que lee el lector CSV
        val reader = com.alexisgordr.cellcase.core.csv.CsvReader(String(bytes).reader())
        var n = -1; while (reader.next() != null) n++
        assertEquals(n, CaseBudget.csvRows(bytes))
        assertEquals(1, CaseBudget.csvRows("a\n\"x\"\"y\",2\n".toByteArray()))      // comilla doblada dentro de comillas
        assertEquals(1, CaseBudget.csvRows("a\nfoo\"bar\n".toByteArray()))
    }
}
