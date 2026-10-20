package com.alexisgordr.cellcase.core.report

import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.EPISODE_RULES_VERSION
import com.alexisgordr.cellcase.core.analysis.RULESET_VERSION
import com.alexisgordr.cellcase.core.pdf.PdfInfo
import com.alexisgordr.cellcase.core.pdf.ReportRenderer
import java.io.OutputStream

/**
 * Catálogo de plantillas de un idioma (D-018). Cada método produce los bloques de una sección; los títulos
 * de nivel 1 se numeran en [ReportBuilder]. Las secciones comunes (resumen, incidencias, conclusiones,
 * limitaciones) no dependen de la modalidad: así el resumido y el completo no pueden concluir cosas distintas.
 */
interface ReportTexts {
    /** Idioma de presentación (D-044). No interviene en el análisis. */
    val language: com.alexisgordr.cellcase.core.i18n.Language
    val pageLabel: String
    val tableLabel: String
    val figureLabel: String
    val continuedLabel: String
    val noticeLabel: String
    fun int(v: Long): String
    fun banner(origin: DataOrigin): String?
    fun title(ctx: TextContext): String
    fun runningHeader(ctx: TextContext): String
    fun runningFooter(ctx: TextContext): String
    fun pdfSubject(ctx: TextContext): String

    fun header(ctx: TextContext, a: CaseAnalysis): Block.Cover
    fun summary(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block>
    /** Cuerpo del informe explicado (D-036): mismas conclusiones y relevancias, en lenguaje cotidiano. */
    fun explained(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block>
    fun incidents(ctx: TextContext, a: CaseAnalysis): List<Block>
    /** Explicación de incidencias: en el resumido, las que merecen revisión; en el completo, todas. */
    fun explanations(ctx: TextContext, a: CaseAnalysis): List<Block>
    fun chronology(ctx: TextContext, a: CaseAnalysis): List<Block>
    fun networkContext(ctx: TextContext, a: CaseAnalysis): List<Block>
    /** Coincidencias y diferencias entre fuentes (docs/cross-sources.md): solo presenta [CaseAnalysis.cross]. */
    fun crossSources(ctx: TextContext, a: CaseAnalysis): List<Block>
    /** Perfil local de ICdetector 3.0 (docs/local-profile.md): contexto en sombra. Vacío si el historial no lo trae. */
    fun localProfile(ctx: TextContext, a: CaseAnalysis): List<Block>
    fun sourcesAndQuality(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block>
    fun methodology(ctx: TextContext, a: CaseAnalysis): List<Block>
    fun conclusions(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block>
    fun limitations(ctx: TextContext, a: CaseAnalysis, availability: List<AnalysisAvailability>): List<Block>
    fun analyst(ctx: TextContext): List<Block>
    fun annexes(ctx: TextContext, a: CaseAnalysis): List<Block>
}

/** Ordena las secciones según la modalidad (D-034). Ambas modalidades parten del mismo [CaseAnalysis]. */
class ReportBuilder(private val texts: ReportTexts) {

    fun build(ctx: TextContext, a: CaseAnalysis): ReportDocument {
        val availability = AvailabilityTable.compute(a.results, a, ctx.case.zone.confirmed)
        val full = ctx.options.mode == ReportMode.FULL
        val sections = ArrayList<List<Block>>()
        if (full) {
            sections += texts.summary(ctx, a, availability)
            sections += texts.incidents(ctx, a)
            sections += texts.explanations(ctx, a)
            sections += texts.chronology(ctx, a)
            sections += texts.networkContext(ctx, a)
            sections += texts.crossSources(ctx, a)
            sections += texts.localProfile(ctx, a)
            sections += texts.sourcesAndQuality(ctx, a, availability)
            sections += texts.methodology(ctx, a)
            sections += texts.conclusions(ctx, a, availability)
            sections += texts.limitations(ctx, a, availability)
            sections += texts.analyst(ctx)
        } else {
            sections += texts.explained(ctx, a, availability)
            if (!ctx.case.analystNotes.isNullOrBlank()) sections += texts.analyst(ctx)
        }

        val blocks = ArrayList<Block>()
        blocks += texts.header(ctx, a)
        var n = 0
        for (sec in sections) for (b in sec) blocks += if (b is Block.Heading && b.level == 1) Block.Heading(1, "${++n}. ${b.text}") else b
        if (full) { blocks += Block.SoftBreak(0.5f); blocks += texts.annexes(ctx, a) }
        return ReportDocument(
            title = texts.title(ctx),
            runningHeader = texts.runningHeader(ctx),
            runningFooter = texts.runningFooter(ctx),
            banner = texts.banner(ctx.case.origin),
            pageLabel = texts.pageLabel,
            blocks = blocks,
            intFormat = { texts.int(it) },
            tableLabel = texts.tableLabel,
            figureLabel = texts.figureLabel,
            continuedLabel = texts.continuedLabel,
            noticeLabel = texts.noticeLabel,
            languageTag = texts.language.locale.toLanguageTag()
        )
    }

    /** Genera el PDF completo. Devuelve el número de páginas. */
    fun writePdf(ctx: TextContext, a: CaseAnalysis, out: OutputStream): Int {
        val doc = build(ctx, a)
        val r = ReportRenderer()
        r.render(doc, PdfInfo(doc.title, texts.pdfSubject(ctx), "CellCase ${ctx.options.appVersion} ($RULESET_VERSION, $EPISODE_RULES_VERSION, ${com.alexisgordr.cellcase.core.analysis.CROSS_RULES_VERSION})", ctx.options.generatedAt, doc.languageTag), out)
        return r.pageCount
    }
}
