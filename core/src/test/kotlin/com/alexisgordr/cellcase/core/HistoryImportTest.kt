package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.TestSupport.csv
import com.alexisgordr.cellcase.core.TestSupport.importHistory
import com.alexisgordr.cellcase.core.TestSupport.importSample
import com.alexisgordr.cellcase.core.TestSupport.row
import com.alexisgordr.cellcase.core.importing.HistorySchema
import com.alexisgordr.cellcase.core.importing.ImportRejected
import com.alexisgordr.cellcase.core.model.RadioTech
import com.alexisgordr.cellcase.core.model.Severity
import com.alexisgordr.cellcase.core.model.ValueState
import com.alexisgordr.cellcase.core.time.TimeResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HistoryImportTest {

    @Test fun syntheticHistoryImportsWithoutErrors() {
        val imp = importSample("SYNTHETIC_icdetector_history.csv")
        assertEquals(HistorySchema.V2_10_4_PLUS, imp.schema)
        assertEquals(651, imp.dataRows)          // filas declaradas en MANIFEST.json
        assertEquals(651, imp.observations.size)
        assertEquals(0, imp.rejectedRows)
        assertTrue(imp.issues.none { it.severity == Severity.ERROR })
        // Procedencia: la primera fila de datos es la línea física 2.
        assertEquals(2, imp.observations.first().provenance.startLine)
        assertEquals(0, imp.observations.first().provenance.recordIndex)
    }

    @Test fun provenanceLineMatchesOriginalText() {
        val f = TestSupport.sample("SYNTHETIC_icdetector_history.csv")
        val lines = f.readLines()
        val imp = importSample("SYNTHETIC_icdetector_history.csv")
        for (o in imp.observations.take(50) + imp.observations.takeLast(5)) {
            assertTrue(lines[o.provenance.startLine - 1].startsWith(o.time.raw + ","))
        }
    }

    @Test fun edgeCasesKeepOriginalsAndNormalizeSentinels() {
        val imp = importSample("SYNTHETIC_icdetector_history_edge_cases.csv")
        val o = imp.observations[0]
        // Comillas y coma dentro del campo
        assertEquals("Razón con \"comillas\", y coma", o.failedHeuristics)
        // Notación científica de Double.toString()
        assertEquals(5.0e-4, o.lat!!, 1e-12)
        // Centinela 2147483647: ausente en normalizado, original intacto
        assertNull(o.pci); assertEquals(ValueState.SENTINEL, o.stateOf("PCI")); assertEquals("2147483647", o.raw["PCI"])
        assertNull(o.arfcn); assertEquals(ValueState.SENTINEL, o.stateOf("ARFCN"))
        // Hora ambigua del cambio de horario (dos filas a las 02:30 del 25/10)
        assertEquals(2, imp.observations.count { it.time.resolution is TimeResolution.Ambiguous })
        // NR_RAW y STUB_ZERO: TAMeters vacío es coherente; no hay incidencia de TA
        assertTrue(imp.issues.none { it.code == "TA_METERS_MISMATCH" })
        // Radio UNKNOWN → identidad incompleta, nunca clave de unión
        val unk = imp.observations.first { it.raw["Radio"] == "UNKNOWN" }
        assertNull(unk.identity.joinKey)
        assertEquals(RadioTech.UNKNOWN, unk.identity.radio)
        // Campo con salto de línea: ocupa dos líneas físicas
        val multi = imp.observations.first { it.failedHeuristics?.contains("\n") == true }
        assertEquals(multi.provenance.startLine + 1, multi.provenance.endLine)
    }

    @Test fun olderSchemaIsReadByNameWithWarning() {
        val imp = importSample("SYNTHETIC_icdetector_history_v21_23cols.csv")
        assertEquals(HistorySchema.V2_1_TO_2_10_3, imp.schema)
        assertTrue(imp.issues.any { it.code == "SCHEMA_OLDER" && it.severity == Severity.WARNING })
        assertEquals(3, imp.observations.size)
        assertNull(imp.observations[0].serviceState)
    }

    @Test fun malformedFileIsRejectedWithLine() {
        try {
            importSample("SYNTHETIC_icdetector_history_malformed.csv"); fail()
        } catch (e: ImportRejected) {
            assertTrue(e.message!!, e.message!!.contains("línea"))
        }
    }

    @Test fun missingRequiredColumnIsRejectedByName() {
        val header = TestSupport.HEADER.replace("DBM,", "")
        try {
            importHistory(header + "\n"); fail()
        } catch (e: ImportRejected) { assertTrue(e.message!!.contains("DBM")) }
    }

    @Test fun columnCountAloneDoesNotDecideCompatibility() {
        // Mismo número de columnas pero una renombrada → rechazado.
        val header = TestSupport.HEADER.replace("Radio,", "Tecnologia,")
        try { importHistory(header + "\n"); fail() } catch (e: ImportRejected) { assertTrue(e.message!!.contains("Radio")) }
    }

    @Test fun columnsAreReadByNameInAnyOrderAndExtrasArePreserved() {
        val cols = TestSupport.HEADER.split(",")
        val values = row("2026-09-27 10:00:00", mapOf("DBM" to "-77")).split(",")
        val order = cols.indices.reversed()
        val text = (order.map { cols[it] } + "Extra").joinToString(",") + "\n" + (order.map { values[it] } + "dato libre").joinToString(",") + "\n"
        val imp = importHistory(text)
        val o = imp.observations.single()
        assertEquals(-77, o.dbm)
        assertEquals("dato libre", o.uninterpreted["Extra"])
        assertTrue(imp.issues.any { it.code == "EXTRA_COLUMNS" })
        assertTrue(imp.issues.any { it.code == "COLUMN_ORDER" })
    }

    @Test fun rowsWithWrongFieldCountAreExcludedButCounted() {
        val imp = importHistory(csv(row("2026-09-27 10:00:00"), "2026-09-27 10:05:00,4G,1"))
        assertEquals(2, imp.dataRows); assertEquals(1, imp.rejectedRows); assertEquals(1, imp.observations.size)
        val issue = imp.issues.first { it.code == "ROW_FIELD_COUNT" }
        assertEquals(3, issue.examples.single().provenance!!.startLine)
    }

    @Test fun outOfRangeAndInvalidValuesAreFlaggedNotFixed() {
        val imp = importHistory(csv(row("2026-09-27 10:00:00", mapOf("DBM" to "-5", "PCI" to "999", "Lat" to "123.0", "SecurityScore" to "abc"))))
        val o = imp.observations.single()
        assertNull(o.dbm); assertEquals(ValueState.OUT_OF_RANGE, o.stateOf("DBM")); assertEquals("-5", o.raw["DBM"])
        assertNull(o.pci); assertEquals(ValueState.OUT_OF_RANGE, o.stateOf("PCI"))
        assertNull(o.lat); assertEquals(ValueState.OUT_OF_RANGE, o.stateOf("Lat"))
        assertNull(o.securityScore); assertEquals(ValueState.INVALID, o.stateOf("SecurityScore"))
    }

    @Test fun naIdentityIsNeverAJoinKey() {
        val o = importHistory(csv(row("2026-09-27 10:00:00", mapOf("MCC" to "N/A")))).observations.single()
        assertNull(o.identity.joinKey)
        assertEquals(ValueState.SENTINEL, o.stateOf("MCC"))
    }

    @Test fun taMetersContradictionIsReportedAndKept() {
        val imp = importHistory(csv(row("2026-09-27 10:00:00", mapOf("TA" to "3", "TAMeters" to "999"))))
        assertEquals(999, imp.observations.single().taMeters)
        assertTrue(imp.issues.any { it.code == "TA_METERS_MISMATCH" })
    }

    @Test fun collectorContradictionScoreWithoutReason() {
        val imp = importHistory(csv(row("2026-09-27 10:00:00", mapOf("SecurityScore" to "85", "FailedHeuristics" to "OK"))))
        assertTrue(imp.issues.any { it.code == "COLLECTOR_SCORE_REASON" })
    }

    @Test fun duplicatesAreFlaggedNeverRemoved() {
        val r = row("2026-09-27 10:00:00")
        val imp = importHistory(csv(r, r, row("2026-09-27 10:00:00", mapOf("FailedHeuristics" to "ALARMA CONFIRMADA", "SecurityScore" to "40"))))
        assertEquals(3, imp.observations.size)
        assertEquals(1, imp.issues.first { it.code == "DUP_EXACT" }.count)
        assertEquals(1, imp.issues.first { it.code == "DUP_SAME_TIME_IDENTITY" }.count)
    }

    @Test fun nonUtf8IsRejected() {
        val bytes = (TestSupport.HEADER + "\n").toByteArray() + byteArrayOf(0xC3.toByte(), 0x28)
        try {
            com.alexisgordr.cellcase.core.importing.HistoryCsvImporter().import(bytes.inputStream(), "x.csv", "i", TestSupport.MADRID); fail()
        } catch (e: ImportRejected) { assertTrue(e.message!!.contains("UTF-8")) }
    }

    @Test fun bomIsAcceptedWithInfo() {
        val imp = importHistory("﻿" + csv(row("2026-09-27 10:00:00")))
        assertEquals(1, imp.observations.size)
        assertTrue(imp.issues.any { it.code == "BOM" })
    }

    /** Revisión 22/10 (A5): la BOM se quita antes de leer la cabecera, también si la primera columna va entre comillas. */
    @Test fun bomBeforeAQuotedHeaderIsAccepted() {
        val body = csv(row("2026-09-27 10:00:00"))
        val first = body.substringBefore(',')
        val imp = importHistory("\uFEFF\"" + first + "\"" + body.removePrefix(first))
        assertEquals(1, imp.observations.size)
        assertTrue(imp.issues.any { it.code == "BOM" })
    }

    @Test fun emptyFileIsRejected() {
        try { importHistory(""); fail() } catch (e: ImportRejected) { assertTrue(e.message!!.contains("vacío")) }
    }

    @Test fun headerOnlyIsAWarningNotACrash() {
        val imp = importHistory(TestSupport.HEADER + "\n")
        assertEquals(0, imp.observations.size)
        assertTrue(imp.issues.any { it.code == "NO_DATA_ROWS" })
    }

    @Test fun duplicatedHeaderColumnsAreRejected() {
        try { importHistory(TestSupport.HEADER + ",DBM\n"); fail() } catch (e: ImportRejected) { assertTrue(e.message!!.contains("repetidas")) }
    }

    @Test fun cancellationIsCooperative() {
        val big = csv(*Array(2000) { row("2026-09-27 10:00:00") })
        var calls = 0
        try {
            com.alexisgordr.cellcase.core.importing.HistoryCsvImporter().import(big.byteInputStream(), "b.csv", "i", TestSupport.MADRID,
                checkpoint = { calls++; if (calls > 2) throw java.util.concurrent.CancellationException("cancelado") })
            fail()
        } catch (e: java.util.concurrent.CancellationException) { assertFalse(calls < 3) }
    }

    @Test fun secondaryCarriersAndListsParse() {
        val o = importHistory(csv(row("2026-09-27 10:00:00", mapOf("SecondaryCarriers" to "NR:632448:501;LTE:?:7", "Bands" to "3;20")))).observations.single()
        assertEquals(2, o.secondaryCarriers!!.size)
        assertNull(o.secondaryCarriers!![1].arfcn)
        assertEquals(listOf(3, 20), o.bands)
        assertNotNull(o.identity.joinKey)
    }
}
