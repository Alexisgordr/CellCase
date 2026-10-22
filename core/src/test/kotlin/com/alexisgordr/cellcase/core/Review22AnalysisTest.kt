package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.analysis.CaseAnalysis
import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.time.DeclaredZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Revisión independiente del 22/10/2026, zona B (análisis): una prueba por fallo, escrita antes de corregirlo. */
class Review22AnalysisTest {
    private fun imp(text: String, name: String, zone: DeclaredZone? = TestSupport.MADRID): ImportResult =
        ImportPipeline.process(text.toByteArray(), name, name, zone)
    private fun analyze(vararg r: ImportResult, zone: DeclaredZone? = TestSupport.MADRID): CaseAnalysis = CaseAnalyzer.analyze(r.toList(), zone, null)

    /** Columnas de ICdetector 3.0 tras las 35 de 2.10.x. */
    private val v3 = ",ObservedAtUtc,NotEvaluatedHeuristics,GpsAccuracyM,AppVersion,ExportDevice,ExportAndroid,LocationMode,ProfileMode,ProfileLevel," +
        "ProfileScore,ProfileCoverage,ProfileAbstainReason,ProfileAlgorithm,ProfileRevision,ProfileEvidence,ProfileLearned,ProfileHoldout,RowId,ObservationId"
    private fun row3(local: String, utc: String, id: Int, over: Map<String, String> = emptyMap()) =
        row(local, over) + ",$utc,NONE,,3.0.0+f5173a8,,,,,,,,,,,,,,$id,obs-$id"
    private fun csv3(vararg rows: String) = TestSupport.HEADER + v3 + "\n" + rows.joinToString("\n") + "\n"

    /**
     * B3: al fusionar dos exportaciones que difieren en `TAMeters` (columna que ICdetector reescribe), el valor queda
     * en conflicto y se anula; COH-TA lo comparaba (`null != 3 × 78`) y daba una incoherencia que no existe.
     */
    @Test fun taMetersInConflictAfterMergingIsNotAnIncoherence() {
        val a = imp(TestSupport.csv(row("2026-07-01 10:00:00")), "a.csv")
        val b = imp(TestSupport.csv(row("2026-07-01 10:00:00", mapOf("TAMeters" to ""))), "b.csv")
        val ha = analyze(a, b).historyAnalysis!!
        assertEquals(1, ha.ta.rowsWithTa)
        assertTrue(ha.ta.incoherent.toString(), ha.ta.incoherent.isEmpty())
        // Un valor que de verdad no cuadra sigue siendo incoherente
        assertEquals(1, analyze(imp(TestSupport.csv(row("2026-07-01 10:00:00", mapOf("TAMeters" to "500"))), "c.csv")).historyAnalysis!!.ta.incoherent.size)
    }

    /**
     * B5: en la hora repetida de octubre (25/10/2026, 02:00–03:00 en Madrid), dos filas con la misma hora local y
     * distinto `ObservedAtUtc` son dos momentos distintos: no son «misma hora e identidad» para COH-DUP.
     */
    @Test fun repeatedOctoberHourWithUtcIsNotADuplicate() {
        val h = imp(csv3(row3("2026-10-25 02:30:00", "2026-10-25T00:30:00.000Z", 1), row3("2026-10-25 02:30:00", "2026-10-25T01:30:00.000Z", 2)), "h.csv")
        val ha = analyze(h).historyAnalysis!!
        assertEquals(2, ha.time.exact)
        assertEquals(0, ha.duplicates.sameTimeIdentity.size)
        // Sin ObservedAtUtc (2.10.x) la hora es ambigua y la regla no cambia
        val old = analyze(imp(TestSupport.csv(row("2026-10-25 02:30:00"), row("2026-10-25 02:30:00", mapOf("DBM" to "-80"))), "o.csv")).historyAnalysis!!
        assertEquals(1, old.duplicates.sameTimeIdentity.size)
    }

    /**
     * B4: dos versiones usadas del mismo caso forense (contenido distinto, D-040) dan resultados X-FOR iguales en motivo,
     * entidades y periodo. La fusión pensada para «el mismo resultado frente a varios agregados» los juntaba en uno:
     * «Historial, Historial», un solo resultado y los archivos de las dos versiones mezclados.
     */
    @Test fun twoVersionsOfAForensicCaseAreNotMergedInCrossChecks() {
        fun s(n: String) = TestSupport.sample(n).readBytes()
        val r = listOf(ImportPipeline.process(s("SYNTHETIC_icdetector_history.csv"), "h.csv", "h", TestSupport.MADRID),
            ImportPipeline.process(s("SYNTHETIC_ICD-2026-09-27-0007.zip"), "c1.zip", "c1", TestSupport.MADRID),
            ImportPipeline.process(s("SYNTHETIC_ICD-2026-09-27-0007_tampered.zip"), "c2.zip", "c2", TestSupport.MADRID))
        val a = CaseAnalyzer.analyze(r, TestSupport.MADRID, null)
        val all = a.cross!!.findings
        assertTrue(all.isNotEmpty())
        for (f in all) assertEquals(f.toString(), f.sourcesB.distinct(), f.sourcesB)
        val xfor = all.filter { it.check == com.alexisgordr.cellcase.core.analysis.CrossCheck.FORENSIC_HISTORY }
        assertTrue(xfor.isNotEmpty())
        // Cada resultado X-FOR viene de una sola versión del caso
        for (f in xfor) assertTrue(f.files.toString(), listOf("c1.zip", "c2.zip").count { it in f.files } == 1)
        assertTrue(xfor.any { "c1.zip" in it.files } && xfor.any { "c2.zip" in it.files })
        // Tampoco frente a los agregados: ningún resultado de un caso forense cita las dos versiones a la vez
        // (verificación: con el número de orden por fuente B, la v2 frente a Topología y la v1 frente a Geometría se juntaban).
        val withAggregates = CaseAnalyzer.analyze(r + listOf("SYNTHETIC_ICD-topology.zip", "SYNTHETIC_ICD-geometry.zip", "SYNTHETIC_ICD-stable-site.zip")
            .map { ImportPipeline.process(s(it), it, it, TestSupport.MADRID) }, TestSupport.MADRID, null).cross!!.findings
        for (f in withAggregates.filter { it.sourceA == com.alexisgordr.cellcase.core.importing.ExportKind.FORENSIC_CASE })
            assertTrue(f.toString(), listOf("c1.zip", "c2.zip").count { it in f.files } <= 1)
    }

    /**
     * B6: una sola definición de mediana. Con un número par de valores es el menor de los dos centrales (rango más
     * cercano, siempre un valor observado), como DESC-POWER y el churn. La línea base de potencia y DESC-SAMPLING usaban
     * el mayor, y en el borde decidía el resultado: con la línea base [−100, −100, −100, −80, −80, −80] y una señal a
     * −90 «(−10dB)», la mediana superior (−80) daba «visible»; con −100 (o con la media de los centrales, −90) no lo es.
     */
    @Test fun evenMedianIsTheLowerMiddleEverywhere() {
        val base = listOf("08:00", "08:10", "08:20", "08:30", "08:40", "08:50").mapIndexed { i, h ->
            row("2026-07-01 $h:00", mapOf("DBM" to if (i < 3) "-100" else "-80")) }
        val sig = row("2026-07-01 12:00:00", mapOf("DBM" to "-90", "FailedHeuristics" to "[sub-umbral] Potencia anómala vs historial (-10dB)"))
        val e = analyze(imp(TestSupport.csv(*(base + sig).toTypedArray()), "h.csv")).episodes.episodes.single()
        assertEquals(com.alexisgordr.cellcase.core.analysis.MeasureStatus.NOT_VISIBLE, e.measurement.status)
        @Suppress("UNCHECKED_CAST")
        assertEquals(-100, (e.measurement.values["measures"] as List<Map<String, Int>>).single()["median"])
        // DESC-SAMPLING: intervalos 10, 10, 30, 30 min → mediana 10 min
        val s = analyze(imp(TestSupport.csv(row("2026-07-01 10:00:00"), row("2026-07-01 10:10:00"), row("2026-07-01 10:20:00"),
            row("2026-07-01 10:50:00"), row("2026-07-01 11:20:00")), "s.csv")).historyAnalysis!!.sampling
        assertEquals(java.time.Duration.ofMinutes(10), s.medianInterval)
    }

    /**
     * B7: con una fila repetida dentro de un archivo (`r, r`) y el mismo par en otro, cada fila del segundo se empareja
     * con una fila distinta del primero. Antes las dos se emparejaban con la primera y se citaba dos veces la misma línea.
     */
    @Test fun repeatedRowsArePairedOneToOne() {
        fun h(name: String) = (imp(TestSupport.csv(row("2026-07-01 10:00:00"), row("2026-07-01 10:00:00")), name).data
            as com.alexisgordr.cellcase.core.importing.ImportedData.History).data
        val m = com.alexisgordr.cellcase.core.analysis.HistoryMerge.merge(listOf(h("a.csv"), h("b.csv")), TestSupport.MADRID)!!
        assertEquals(2, m.matches.size)
        assertEquals(m.matches.map { it.kept.startLine }.toString(), 2, m.matches.map { it.kept }.distinct().size)
        assertEquals(2, m.matches.map { it.other }.distinct().size)
    }
}
