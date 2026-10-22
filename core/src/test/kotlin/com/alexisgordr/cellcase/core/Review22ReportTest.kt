package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.ReportTextsEn
import com.alexisgordr.cellcase.core.report.ReportTextsEs
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.time.DeclaredZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.Instant

/** Revisión independiente del 22/10/2026, zona C (informes y app): una prueba por fallo, escrita antes de corregirlo. */
class Review22ReportTest {
    private fun pdfs(res: List<ImportResult>, zone: DeclaredZone = TestSupport.MADRID): List<ByteArray> {
        val a = CaseAnalyzer.analyze(res, zone, null)
        return listOf(ReportTextsEs, ReportTextsEn).flatMap { texts -> ReportMode.values().map { m ->
            val ctx = TextContext(CaseInfo("c", "Prueba", Instant.EPOCH, "Teléfono de prueba", zone, DataOrigin.SYNTHETIC, null, null), res,
                ReportOptions(generatedAt = Instant.parse("2026-10-02T08:00:00Z"), appVersion = "t", mode = m))
            ByteArrayOutputStream().also { ReportBuilder(texts).writePdf(ctx, a, it) }.toByteArray()
        } }
    }

    /**
     * C1: un `Timestamp` con espacio final se importa (el lector recorta al leer la hora), pero el resumen de la
     * importación y el PDF volvían a leer el texto crudo y lanzaban DateTimeParseException: en la app, el resumen se
     * muestra desde la pantalla sin captura, así que la app se cerraba.
     */
    @Test fun timestampWithTrailingSpaceDoesNotBreakTheSummaryOrThePdf() {
        val lines = TestSupport.sample("SYNTHETIC_patrones_historial.csv").readText().split("\n").toMutableList()
        val ts = lines[1].substringBefore(',')
        lines[1] = "$ts " + lines[1].removePrefix(ts)
        val r = ImportPipeline.process(lines.joinToString("\n").toByteArray(), "h.csv", "h", TestSupport.MADRID)
        assertTrue(r.imported)
        for (texts in listOf(ReportTextsEs, ReportTextsEn)) {
            val summary = texts.importSummary(r, TestSupport.MADRID.zoneId)
            assertTrue(summary.toString(), summary.isNotEmpty())
        }
        assertEquals(4, pdfs(listOf(r)).size)
        // La hora de la fila queda recortada, como la que se analiza
        val obs = (r.data as com.alexisgordr.cellcase.core.importing.ImportedData.History).data.observations
        assertTrue(obs.none { it.time.raw.endsWith(" ") })
    }

    /**
     * C2: una fila de 3.0 con `Timestamp` inválido pero `ObservedAtUtc` válido se conserva con instante exacto y sin
     * hora local; si era el borde de un hueco de ≥ 60 min, la cronología del informe técnico hacía `local!!` y lanzaba
     * NullPointerException.
     */
    @Test fun gapNextToARowWithoutLocalTimeDoesNotBreakTheChronology() {
        val lines = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv").readText().trimEnd('\n').split("\n")
        val head = lines[0].split(',')
        val obsCol = head.indexOf("ObservedAtUtc")
        fun fields(l: String) = com.alexisgordr.cellcase.core.csv.CsvReader(l.reader()).next()!!.fields
        // Filas con ObservedAtUtc, del 20/10 en adelante; se quitan 50 para abrir un hueco y se rompe la hora local de
        // la fila anterior al hueco.
        val data = lines.drop(1).filter { fields(it)[obsCol].isNotEmpty() }
        val cut = data.size / 2
        val kept = data.take(cut) + data.drop(cut + 50)
        val before = kept[cut - 1]
        val t = before.substringBefore(',')
        val broken = kept.toMutableList().also { it[cut - 1] = "2026-13-45 00:00:00" + before.removePrefix(t) }
        val r = ImportPipeline.process((listOf(lines[0]) + broken).joinToString("\n", postfix = "\n").toByteArray(), "h.csv", "h", TestSupport.MADRID)
        assertTrue(r.imported)
        val a = CaseAnalyzer.analyze(listOf(r), TestSupport.MADRID, null)
        val gaps = a.historyAnalysis!!.sampling.gaps
        assertTrue("hay un hueco junto a la fila sin hora local", gaps.any { it.before.time.local == null || it.after.time.local == null })
        assertEquals(4, pdfs(listOf(r)).size)
    }

    /**
     * C3: `"%02d".format(…)` usa la configuración regional del teléfono, y con dígitos no latinos (árabe de Egipto) los
     * minutos de las duraciones y los segundos de las citas salían como `٠٥`, que el PDF no puede dibujar: el mismo
     * expediente daba un PDF distinto según el teléfono. Debe salir el mismo PDF byte a byte.
     */
    @Test fun pdfDoesNotDependOnTheDeviceLocale() {
        // Historial 3.0 con 50 filas quitadas: un hueco de varias horas («h min») en la cronología, y los casos forenses
        // del mismo teléfono (citas con segundos).
        val lines = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv").readText().trimEnd('\n').split("\n")
        val cut = lines.size / 2
        val hist = (lines.take(cut) + lines.drop(cut + 50)).joinToString("\n", postfix = "\n").toByteArray()
        val res = listOf(ImportPipeline.process(hist, "h.csv", "h", TestSupport.MADRID)) +
            listOf("icd30/SYNTHETIC_icd30_ICD-2026-10-22-0001.zip", "icd30/SYNTHETIC_icd30_ICD-OBS-2026-10-23-0002.zip").mapIndexed { i, n ->
                ImportPipeline.process(TestSupport.sample(n).readBytes(), n.substringAfter('/'), "c$i", TestSupport.MADRID) }
        val a = CaseAnalyzer.analyze(res, TestSupport.MADRID, null)
        assertTrue("hay un hueco de más de una hora", a.historyAnalysis!!.sampling.gaps.any { it.duration.toHours() in 1..47 })
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.ROOT)
            val reference = pdfs(res)
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
            assertEquals("٠٥", "%02d".format(5))   // la configuración de la prueba localiza los dígitos
            val arabic = pdfs(res)
            for ((a, b) in reference.zip(arabic)) assertTrue(a.contentEquals(b))
        } finally { java.util.Locale.setDefault(saved) }
    }

    private fun render(vararg blocks: com.alexisgordr.cellcase.core.report.Block): com.alexisgordr.cellcase.core.pdf.ReportRenderer {
        val doc = com.alexisgordr.cellcase.core.report.ReportDocument("Prueba", "cabecera", "pie", null, "{page}/{total}", blocks.toList())
        return com.alexisgordr.cellcase.core.pdf.ReportRenderer().also {
            it.render(doc, com.alexisgordr.cellcase.core.pdf.PdfInfo("Prueba", "s", "p", Instant.EPOCH), ByteArrayOutputStream())
        }
    }

    /**
     * C6: un título no queda solo al pie. La previsión del título limitaba el comienzo de la tabla al 60 % de la página,
     * pero la tabla exige que quepan sus dos primeras filas: con una primera fila muy alta, el título se quedaba en una
     * página y la tabla empezaba en la siguiente.
     */
    @Test fun headingStaysWithATableWhoseFirstRowIsVeryTall() {
        val filler = com.alexisgordr.cellcase.core.report.Block.Paragraph((1..120).joinToString(" ") { "relleno" })
        val tall = (1..900).joinToString(" ") { "palabra$it" }
        val r = render(filler, com.alexisgordr.cellcase.core.report.Block.Heading(2, "Titulo de la tabla alta"),
            com.alexisgordr.cellcase.core.report.Block.Table(listOf(com.alexisgordr.cellcase.core.report.Column("A", 1f), com.alexisgordr.cellcase.core.report.Column("B", 1f)),
                listOf(listOf("fila1", tall), listOf("fila2", "corta")), null))
        val pageOfHeading = r.pageTexts.indexOfFirst { p -> p.any { "Titulo de la tabla alta" in it } }
        val pageOfRow = r.pageTexts.indexOfFirst { p -> p.any { "palabra1 " in "$it " || it.startsWith("palabra1") } }
        assertTrue("título en la página ${pageOfHeading + 1}, primera fila en la ${pageOfRow + 1}", pageOfHeading >= 0 && pageOfHeading == pageOfRow)
    }

    /** C6: una tabla sin columnas no se dibuja (antes lanzaba NoSuchElementException). */
    @Test fun tableWithoutColumnsIsSkipped() {
        val r = render(com.alexisgordr.cellcase.core.report.Block.Paragraph("antes"),
            com.alexisgordr.cellcase.core.report.Block.Table(emptyList(), emptyList(), "vacía"), com.alexisgordr.cellcase.core.report.Block.Paragraph("después"))
        assertTrue(r.pageTexts.flatten().any { "después" in it })
    }

    /** C6: el número más negativo se formatea con un solo signo (antes `abs` desbordaba: «--9.223…»). */
    @Test fun mostNegativeLongHasOneSign() {
        assertEquals("-9.223.372.036.854.775.808", com.alexisgordr.cellcase.core.i18n.RegionalFormat(com.alexisgordr.cellcase.core.i18n.Language.ES).int(Long.MIN_VALUE))
        assertEquals("-9,223,372,036,854,775,808", com.alexisgordr.cellcase.core.i18n.RegionalFormat(com.alexisgordr.cellcase.core.i18n.Language.EN).int(Long.MIN_VALUE))
        assertEquals("-1234", com.alexisgordr.cellcase.core.i18n.RegionalFormat(com.alexisgordr.cellcase.core.i18n.Language.ES).int(-1234))
    }
}
