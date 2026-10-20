package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.PatternKind
import com.alexisgordr.cellcase.core.analysis.ReasonParser
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.i18n.Messages
import com.alexisgordr.cellcase.core.i18n.ReasonGlossary
import com.alexisgordr.cellcase.core.i18n.RegionalFormat
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.SelectedFile
import com.alexisgordr.cellcase.core.pdf.PdfFont
import com.alexisgordr.cellcase.core.pdf.ReportRenderer
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.CoordinateMode
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.IdentifierMode
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportDocument
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.ReportTextsEn
import com.alexisgordr.cellcase.core.report.ReportTextsEs
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.report.reportTexts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/**
 * Informes y textos en español e inglés (D-044): el idioma cambia la presentación, nunca el análisis.
 * Datos sintéticos del repositorio.
 */
class LanguageTest {
    private val case: Pair<List<ImportResult>, CaseAnalysis> by lazy {
        var n = 0
        val outcomes = BatchImport.run(GenerateSampleReport.PATTERNS.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
        val results = outcomes.mapNotNull { (it as? BatchOutcome.Processed)?.result }
        results to CaseAnalyzer.analyze(results, TestSupport.MADRID, null)
    }

    private fun ctx(mode: ReportMode, notes: String? = null, description: String? = null) = TextContext(
        CaseInfo("id", "Expediente de prueba", Instant.parse("2026-10-02T07:55:00Z"), "Teléfono de prueba", TestSupport.MADRID, DataOrigin.SYNTHETIC,
            notes, null, description = description),
        case.first, ReportOptions(CoordinateMode.ROUND_3, IdentifierMode.FULL, Instant.parse("2026-10-02T08:05:00Z"), "t", mode = mode))

    private fun doc(lang: Language, mode: ReportMode, notes: String? = null, description: String? = null): ReportDocument =
        ReportBuilder(reportTexts(lang)).build(ctx(mode, notes, description), case.second)

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

    /** Forma del documento: tipos de bloque, filas y columnas de cada tabla, carriles y marcas de los gráficos. */
    private fun shape(d: ReportDocument): List<String> = d.blocks.map { b ->
        when (b) {
            is Block.Table -> "Table ${b.columns.size}x${b.rows.size}"
            is Block.Bullets -> "Bullets ${b.items.size}"
            is Block.Labeled -> "Labeled ${b.items.size}"
            is Block.KeyValues -> "KeyValues ${b.rows.size}"
            is Block.Timeline -> "Timeline ${b.lanes.size} ${b.marks.size}"
            is Block.Heading -> "Heading ${b.level}"
            else -> b.javaClass.simpleName
        }
    }

    // ── mismos hallazgos, prioridades y conclusiones ────────────────────

    @Test fun sameCaseHasTheSameStructureFindingsAndIdentifiersInBothLanguages() {
        for (mode in ReportMode.values()) {
            val es = doc(Language.ES, mode); val en = doc(Language.EN, mode)
            assertEquals("forma ($mode)", shape(es), shape(en))
            val tes = text(es); val ten = text(en)
            // identificadores, referencias, celdas cortas, episodios, huellas y valores originales: idénticos
            val tokens = Regex("""\b(E\d+|C\d+|\d{3}-\d{2}-\d+-\d+-[A-Z]+|[0-9a-f]{64}|-?\d{2,3} dBm|EARFCN \d+|PCI \d+|SYNTHETIC_[\w.-]+:\d+|episodes/\d+|readers/[\d.]+|merge/\d+)\b""")
            val a = tokens.findAll(tes).map { it.value }.toSortedSet(); val b = tokens.findAll(ten).map { it.value }.toSortedSet()
            assertEquals("identificadores ($mode)", a, b)
            assertTrue(a.isNotEmpty())
        }
    }

    @Test fun prioritiesAndConclusionsMapOneToOne() {
        val es = text(doc(Language.ES, ReportMode.FULL)); val en = text(doc(Language.EN, ReportMode.FULL))
        fun count(t: String, s: String) = Regex(Regex.escape(s)).findAll(t).count()
        assertEquals(count(es, "· Revisar"), count(en, "· Review"))
        assertEquals(count(es, "Necesita más datos"), count(en, "More data needed"))
        assertEquals(count(es, "Sin indicios suficientes"), count(en, "Insufficient evidence"))
        assertTrue(es.contains("Observaciones que necesitan más datos") && en.contains("Observations that need more data"))
        // la misma conclusión sobre estaciones base falsas en ambos idiomas
        assertTrue(en.contains("no observation can be attributed to a fake base station"))
        assertTrue(es.contains("no se puede atribuir ninguna observación a una estación base falsa"))
    }

    @Test fun languageNeverChangesTheAnalysis() {
        val before = case.second
        doc(Language.EN, ReportMode.FULL); doc(Language.ES, ReportMode.EXPLAINED)
        val again = CaseAnalyzer.analyze(case.first, TestSupport.MADRID, null)
        assertEquals(before.episodes.episodes.map { listOf(it.number, it.priority, it.relevance.relevance, it.measurement.status, it.start, it.end) },
            again.episodes.episodes.map { listOf(it.number, it.priority, it.relevance.relevance, it.measurement.status, it.start, it.end) })
    }

    // ── catálogos y plurales ────────────────────────────────────────────

    @Test fun catalogsAreCompleteWithTheSamePlaceholders() {
        val es = Messages.catalog(Language.ES); val en = Messages.catalog(Language.EN)
        assertEquals(es.keys, en.keys)
        fun marks(v: String) = Regex("""\{(\d+)""").findAll(v).map { it.groupValues[1] }.toSortedSet()
        for (k in es.keys) assertEquals(k, marks(es.getValue(k)), marks(en.getValue(k)))
    }

    @Test fun everyKeyUsedInTheCodeExists() {
        val root = File(System.getProperty("cellcase.root") ?: ".", "core/src/main/kotlin")
        val used = root.walkTopDown().filter { it.extension == "kt" }.flatMap { f ->
            Regex("""\b(?:t|msg)\("([a-z]+\.[A-Za-z0-9_.]+)"""").findAll(f.readText()).map { it.groupValues[1] }
        }.filter { !it.endsWith(".") }.toSet() + com.alexisgordr.cellcase.core.importing.ExportKind.values().map { "kind." + it.name } + crossKeys()
        val missing = used.filter { !Messages.has(Language.ES, it) || !Messages.has(Language.EN, it) }
        assertTrue("claves sin texto: $missing", missing.isEmpty())
        assertTrue(used.size > 500)
    }

    /** Claves de los cruces entre fuentes que se forman con valores de enumeraciones: todas deben existir en los dos idiomas. */
    private fun crossKeys(): List<String> {
        val a = com.alexisgordr.cellcase.core.analysis.CrossCheck.values().map { "cross.check." + it.name } +
            com.alexisgordr.cellcase.core.analysis.CrossOutcome.values().map { "cross.outcome." + it.name } +
            com.alexisgordr.cellcase.core.analysis.CrossReason.values().map { "cross.reason." + it.name } +
            com.alexisgordr.cellcase.core.analysis.Derivation.values().map { "cross.derivation." + it.name } +
            com.alexisgordr.cellcase.core.analysis.CrossNext.values().map { "cross.next." + it.name } +
            com.alexisgordr.cellcase.core.analysis.CrossField.values().map { "cross.field." + it.name } +
            com.alexisgordr.cellcase.core.importing.ExportKind.values().map { "cross.src." + it.name } +
            listOf("cross.none.NO_DATA", "cross.none.NO_OVERLAP")
        return a
    }

    @Test fun pluralsFollowTheCount() {
        val k = "report.countsText.filas"
        assertEquals("1 fila", Messages.format(Language.ES, k, listOf("1")))
        assertEquals("2 filas", Messages.format(Language.ES, k, listOf("2")))
        assertEquals("0 filas", Messages.format(Language.ES, k, listOf("0")))
        assertEquals("1 row", Messages.format(Language.EN, k, listOf("1")))
        assertEquals("1,234 rows", Messages.format(Language.EN, k, listOf(RegionalFormat.of(Language.EN).int(1234))))
        assertEquals("12.345 filas", Messages.format(Language.ES, k, listOf(RegionalFormat.of(Language.ES).int(12345))))
        val two = "report.countsText.muestras_celdas"
        assertEquals("1 sample · 3 cells", Messages.format(Language.EN, two, listOf("1", "3")))
        assertEquals("2 muestras · 1 celda", Messages.format(Language.ES, two, listOf("2", "1")))
    }

    // ── formato regional sin cambiar valores ────────────────────────────

    @Test fun decimalSeparatorsAndDatesChangeOnlyTheWriting() {
        val es = RegionalFormat.of(Language.ES); val en = RegionalFormat.of(Language.EN)
        assertEquals("12,5", es.dec(12.5)); assertEquals("12.5", en.dec(12.5))
        assertEquals("1321", es.int(1321)); assertEquals("1,321", en.int(1321))
        val t = Instant.parse("2026-10-02T08:05:00Z")
        assertEquals("02/10/2026 08:05:00 UTC", es.utc(t))
        assertEquals("2026-10-02 08:05:00 UTC", en.utc(t))        // inequívoca: año-mes-día
        assertEquals("2 October", en.LAY.format(LocalDate.of(2026, 10, 2)))
        assertEquals("2 de octubre", es.LAY.format(LocalDate.of(2026, 10, 2)))
        // la misma hora local en ambos informes (la zona de captura no depende del idioma)
        val zt = es.at(t, TestSupport.MADRID.zoneId).takeLast(8); assertEquals(zt, en.at(t, TestSupport.MADRID.zoneId).takeLast(8))
    }

    @Test fun numericValuesAreTheSameInBothReports() {
        val es = text(doc(Language.ES, ReportMode.FULL)); val en = text(doc(Language.EN, ReportMode.FULL))
        fun dbm(t: String) = Regex("""-\d{2,3} dBm""").findAll(t).map { it.value }.toList()
        assertEquals(dbm(es), dbm(en))
        // porcentajes: misma cifra, distinta escritura
        fun pcts(t: String) = Regex("""(\d+)[.,](\d) ?%""").findAll(t).map { it.groupValues[1] + "." + it.groupValues[2] }.toList()
        assertEquals(pcts(es), pcts(en)); assertTrue(pcts(es).isNotEmpty())
    }

    // ── citas originales y anotaciones ──────────────────────────────────

    @Test fun originalIcdetectorQuotesArePreservedAndTheTranslationIsSeparate() {
        val en = text(doc(Language.EN, ReportMode.FULL)); val es = text(doc(Language.ES, ReportMode.FULL))
        val original = "«[sub-umbral] Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)»"
        assertTrue(es.contains(original)); assertTrue(en.contains(original))
        assertTrue(en.contains(original + " — explanatory translation: “[sub-threshold] Unstable RF identity"))
        assertFalse("en español no se añade traducción", es.contains("traducción explicativa"))
    }

    @Test fun userTextIsNotTranslated() {
        val notes = "Nota del usuario: estaba en el garaje de la calle Mayor"
        val en = text(doc(Language.EN, ReportMode.FULL, notes = notes, description = "descripción propia"))
        assertTrue(en.contains(notes)); assertTrue(en.contains("descripción propia"))
        assertTrue(en.contains("not translated"))
    }

    @Test fun glossaryCoversEveryKnownReasonAndKeepsFigures() {
        assertEquals("[sub-threshold] Forced band downgrade (1805MHz→791MHz, B3→B20)", ReasonGlossary.english("[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)"))
        assertEquals("Anomalous power vs history (+19dB) | Isolated cell", ReasonGlossary.english("Potencia anómala vs historial (+19dB) | Celda aislada"))
        assertEquals("[3/5 cycles confirming] TAC deviation", ReasonGlossary.english("[3/5 ciclos confirmando] Desviación TAC"))
        assertEquals(null, ReasonGlossary.english("texto desconocido"))
        assertEquals(null, ReasonGlossary.explain("Celda aislada", Language.ES))
    }

    /** ICdetector escribe sus motivos en español aunque el teléfono esté en otro idioma: el reconocimiento no depende del idioma de CellCase ni del sistema. */
    @Test fun reasonRecognitionDoesNotDependOnLocale() {
        val reasons = listOf("[sub-umbral] Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)",
            "Downgrade de banda forzado (2620MHz→925MHz, B7→B8)", "Suplantación TA", "DESVIACIÓN TAC", "Transición celular incoherente")
        val saved = Locale.getDefault()
        try {
            val results = listOf(Locale.ROOT, Locale.ENGLISH, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("es-ES")).map { l ->
                Locale.setDefault(l); reasons.map { ReasonParser.parse(it).map { r -> r.kind } }
            }
            assertTrue(results.all { it == results[0] })
            assertEquals(listOf(PatternKind.RF_IDENTITY), results[0][0])
            assertEquals(listOf(PatternKind.TAC_DEVIATION), results[0][3])
        } finally { Locale.setDefault(saved) }
    }

    // ── texto largo y cambio de idioma ──────────────────────────────────

    @Test fun longTextDoesNotOverflowInEitherLanguage() {
        val long = "Text " + "x".repeat(400) + " " + "Überlänge ".repeat(40)
        for (lang in Language.values()) {
            val d = doc(lang, ReportMode.FULL, notes = long, description = long.take(300))
            for (b in d.blocks) if (b is Block.Paragraph) for (l in ReportRenderer.wrap(b.text, PdfFont.REGULAR, 9f, 480f))
                assertTrue("$lang: $l", PdfFont.REGULAR.width(l, 9f) <= 480.01f)
            val out = ByteArrayOutputStream()
            val pages = ReportBuilder(reportTexts(lang)).writePdf(ctx(ReportMode.FULL, long, long.take(300)), case.second, out)
            assertTrue(pages > 5)
        }
    }

    @Test fun switchingLanguageLosesNothingAndIsReproducible() {
        fun pdf(lang: Language) = ByteArrayOutputStream().also { ReportBuilder(reportTexts(lang)).writePdf(ctx(ReportMode.EXPLAINED), case.second, it) }.toByteArray()
        val es1 = pdf(Language.ES); val en = pdf(Language.EN); val es2 = pdf(Language.ES)
        assertTrue("es → en → es da el mismo PDF", es1.contentEquals(es2))
        assertFalse(es1.contentEquals(en))
        assertTrue(String(en, Charsets.ISO_8859_1).contains("/Lang (en-GB)"))
        assertTrue(String(es1, Charsets.ISO_8859_1).contains("/Lang (es-ES)"))
        assertNotEquals(ReportTextsEs.title(ctx(ReportMode.FULL)), ReportTextsEn.title(ctx(ReportMode.FULL)))
    }

    @Test fun importMessagesAreShownInTheReportLanguage() {
        val r = ImportPipeline.process(ByteArray(0), "vacío.csv", "x", TestSupport.MADRID)
        assertEquals("El archivo está vacío.", r.message)
        assertEquals("The file is empty.", ReportTextsEn.tr(r.messageText!!))
        val h = ImportPipeline.process(TestSupport.sample("SYNTHETIC_ICD-geometry.zip").readBytes(), "g.zip", "g", TestSupport.MADRID)
        val issue = h.issues.first()
        assertNotEquals(ReportTextsEs.tr(issue.text), ReportTextsEn.tr(issue.text))
    }
}
