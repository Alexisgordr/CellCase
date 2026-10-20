package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.analysis.CrossCheck
import com.alexisgordr.cellcase.core.analysis.CrossReason
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.report.reportTexts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * La celda de un caso forense que NO aparece en un agregado no tiene valor ni referencia en ese agregado: el informe
 * no escribe el campo vacío («(máx. por clave): .») ni un «Ref.:» sin referencias. Lo hace visible la copia 3.0,
 * cuyo caso E6 no está en Stable-Site.
 */
class CrossAbsentPresentationTest {
    private val analysis = TestSupport.sample("icd30/SYNTHETIC_icd30_fresh.icdbackup").let { f ->
        CaseAnalyzer.analyze(listOf(ImportPipeline.processFile(f, f.name, "c", TestSupport.MADRID)), TestSupport.MADRID, null)
    }

    private fun tableText(lang: Language, annex: Boolean): String {
        val ctx = TextContext(CaseInfo("x", "Cruces", Instant.EPOCH, "Sintético", TestSupport.MADRID, DataOrigin.SYNTHETIC, null, null),
            analysis.results, ReportOptions(generatedAt = Instant.EPOCH, appVersion = "t", mode = ReportMode.FULL, crossAnnex = annex))
        return ReportBuilder(reportTexts(lang)).build(ctx, analysis).blocks.filterIsInstance<Block.Table>()
            .flatMap { it.rows.flatten() }.joinToString("\n")
    }

    @Test fun theSampleHasACaseAbsentFromAnAggregate() {
        assertTrue(analysis.cross!!.findings.any { it.check == CrossCheck.FORENSIC_AGGREGATES && it.reason == CrossReason.ABSENT_FROM_AGGREGATE && it.refs.isEmpty() })
    }

    @Test fun noEmptyFieldsOrReferencesInAnyTable() {
        for (lang in Language.values()) for (annex in listOf(false, true)) {
            val text = tableText(lang, annex)
            assertFalse("$lang anexo=$annex: campo sin valor", Regex("\\): \\.").containsMatchIn(text))
            assertFalse("$lang anexo=$annex: referencia vacía", Regex("Ref\\.: ?(\\.|$|\\n)").containsMatchIn(text))
            assertTrue(text.contains("Ref.: "))   // las demás filas sí llevan sus referencias
        }
    }
}
