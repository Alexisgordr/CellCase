package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.importing.BatchImport
import com.alexisgordr.cellcase.core.importing.BatchOutcome
import com.alexisgordr.cellcase.core.importing.SelectedFile
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.CoordinateMode
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.IdentifierMode
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.reportTexts
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.report.TextContext
import java.io.File
import java.time.Instant

/**
 * Genera un PDF de muestra SINTÉTICO con el mismo código que usa la app:
 * selección múltiple → importación por archivo → análisis conjunto → episodios → plantillas → PDF.
 * Uso: java ... GenerateSampleReport <salida.pdf> [archivos...] [--masked] [--coords=MODO]
 */
object GenerateSampleReport {
    val ALL = listOf(
        "SYNTHETIC_icdetector_history.csv",
        "SYNTHETIC_icdetector_service_state.csv",
        "SYNTHETIC_ICD-topology.zip",
        "SYNTHETIC_ICD-stable-site.zip",
        "SYNTHETIC_ICD-geometry.zip",
        "SYNTHETIC_ICD-2026-09-27-0007.zip",
        "SYNTHETIC_ICD-2026-09-27-0007_reexport.zip",
        "SYNTHETIC_icdetector_history.csv",          // repetido: se detecta por hash y no se cuenta
        "SYNTHETIC_hostile_truncated.zip"            // dañado: no impide importar el resto
    )

    /** Expediente que reproduce los patrones de un caso real (sin sus datos) con los cinco formatos habituales. */
    val PATTERNS = listOf(
        "SYNTHETIC_patrones_historial.csv", "SYNTHETIC_patrones_radio.csv",
        "SYNTHETIC_ICD-topology.zip", "SYNTHETIC_ICD-stable-site.zip", "SYNTHETIC_ICD-geometry.zip"
    )

    /** Copia de seguridad SINTÉTICA de ICdetector 3.0.0 (tools/synth/generate_icd30.py): historial con perfil local y hora UTC. */
    val BACKUP30 = listOf("icd30/SYNTHETIC_icd30_fresh.icdbackup")

    @JvmStatic
    fun main(args: Array<String>) {
        val out = File(args.getOrElse(0) { "samples/reports/SYNTHETIC_informe_expediente.pdf" })
        val flags = args.drop(1).filter { it.startsWith("--") }
        val scale = args.drop(1).filter { !it.startsWith("--") } == listOf("ESCALA")
        val names = args.drop(1).filter { !it.startsWith("--") }.let { when (it) { listOf("PATRONES"), listOf("ESCALA") -> PATTERNS; listOf("COPIA30") -> BACKUP30; else -> it } }.ifEmpty { ALL }
        val crossAnnex = "--cross-annex" in flags
        val mode = flags.firstOrNull { it.startsWith("--mode=") }?.substringAfter("=")?.let { ReportMode.valueOf(it) } ?: ReportMode.FULL
        val masked = "--masked" in flags
        val coords = flags.firstOrNull { it.startsWith("--coords=") }?.substringAfter("=")?.let { CoordinateMode.valueOf(it) } ?: CoordinateMode.OMIT
        val lang = Language.fromTag(flags.firstOrNull { it.startsWith("--lang=") }?.substringAfter("="))
        var n = 0
        val selected = if (scale) ScaleFixture.files().map { (nm, b) -> SelectedFile(nm, b) } else names.map { SelectedFile(it, TestSupport.sample(it).readBytes()) }
        val outcomes = BatchImport.run(selected, emptyMap(), TestSupport.MADRID, { "imp-${++n}" })
        val results = outcomes.mapNotNull { (it as? BatchOutcome.Processed)?.result }
        outcomes.filterIsInstance<BatchOutcome.AlreadyPresent>().forEach { println("Repetido (no se importa de nuevo): ${it.fileName} = ${it.existingName}") }
        val analysis = CaseAnalyzer.analyze(results, TestSupport.MADRID, null)
        val ctx = TextContext(
            CaseInfo(
                id = "c0a1b2c3-sint-0000-0000-000000000001",
                name = when { scale -> "Escala sintética"; names == BACKUP30 -> "Copia sintética de ICdetector 3.0"; names == ALL -> "Demostración sintética 01"; names == PATTERNS -> "Patrones sintéticos"; else -> "Casos límite sintéticos" },
                createdAt = Instant.parse("2026-10-02T07:55:00Z"),
                deviceLabel = "Teléfono sintético (sin dispositivo real)",
                zone = TestSupport.MADRID,
                origin = DataOrigin.SYNTHETIC,
                analystNotes = if (names == ALL) "Expediente creado para comprobar el recorrido completo de CellCase con las muestras sintéticas del repositorio: " +
                    "los cinco formatos habituales, un caso forense con su reexportación, un archivo repetido y un ZIP dañado."
                    else if (names == PATTERNS || names == BACKUP30) null else "Expediente de prueba con: ${names.joinToString()}.",
                description = if (names == PATTERNS) "Reproduce la forma de los patrones de un expediente real, con datos inventados"
                    else if (names == BACKUP30) "Una sola copia de seguridad de ICdetector 3.0.0, con datos inventados" else null,
                periodOfInterest = null
            ),
            results,
            ReportOptions(coords, if (masked) IdentifierMode.MASKED else IdentifierMode.FULL, Instant.parse("2026-10-02T08:05:00Z"), "0.1.0", mode = mode, crossAnnex = crossAnnex)
        )
        out.parentFile?.mkdirs()
        val pages = out.outputStream().use { ReportBuilder(reportTexts(lang)).writePdf(ctx, analysis, it) }
        println("PDF escrito: ${out.absolutePath} ($pages páginas, ${results.size} importaciones, ${analysis.episodes.episodes.size} episodios)")
    }
}
