package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.csv.CsvFormatException
import com.alexisgordr.cellcase.core.csv.CsvReader
import com.alexisgordr.cellcase.core.time.CaptureTime
import com.alexisgordr.cellcase.core.time.DeclaredZone
import com.alexisgordr.cellcase.core.time.TimeResolution
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class CsvAndTimeTest {
    private fun all(s: String): List<com.alexisgordr.cellcase.core.csv.CsvRecord> {
        val r = CsvReader(s.reader()); val out = ArrayList<com.alexisgordr.cellcase.core.csv.CsvRecord>()
        while (true) out += r.next() ?: break
        return out
    }

    @Test fun quotedFieldsCommasAndEscapedQuotes() {
        val recs = all("a,b,c\n1,\"x, \"\"y\"\"\",3\n")
        assertEquals(listOf("1", "x, \"y\"", "3"), recs[1].fields)
        assertEquals(2, recs[1].startLine)
    }

    @Test fun newlineInsideQuotesKeepsPhysicalLines() {
        val recs = all("h1,h2\n\"línea\nsegunda\",2\nz,9\n")
        assertEquals("línea\nsegunda", recs[1].fields[0])
        assertEquals(2, recs[1].startLine); assertEquals(3, recs[1].endLine)
        assertEquals(4, recs[2].startLine)
    }

    @Test fun crlfAndTrailingEmptyField() {
        val recs = all("a,b\r\n1,\r\n")
        assertEquals(listOf("1", ""), recs[1].fields)
        assertEquals(2, recs.size)
    }

    @Test fun missingFinalNewlineStillReadsLastRecord() {
        val recs = all("a,b\n1,2")
        assertEquals(listOf("1", "2"), recs[1].fields)
    }

    @Test(expected = CsvFormatException::class)
    fun unterminatedQuoteIsAnError() { all("a,b\n\"abierta,2\n") }

    private val madrid = DeclaredZone(ZoneId.of("Europe/Madrid"), true)

    @Test fun exactLocalTime() {
        val t = CaptureTime.parseLocalSeconds("2026-09-27 21:14:00", madrid)
        assertEquals(Instant.parse("2026-09-27T19:14:00Z"), t.instantOrNull)
    }

    @Test fun ambiguousHourAtEndOfSummerTimeIsNotResolved() {
        val t = CaptureTime.parseLocalSeconds("2026-10-25 02:30:00", madrid)
        val r = t.resolution as TimeResolution.Ambiguous
        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), r.earlier)
        assertEquals(Instant.parse("2026-10-25T01:30:00Z"), r.later)
        assertNull(t.instantOrNull)
    }

    @Test fun nonexistentHourAtStartOfSummerTime() {
        val t = CaptureTime.parseLocalSeconds("2026-03-29 02:30:00", madrid)
        assertTrue(t.resolution is TimeResolution.Nonexistent)
        assertNull(t.instantOrNull)
    }

    @Test fun unconfirmedZoneKeepsLocalTextOnly() {
        val t = CaptureTime.parseLocalSeconds("2026-09-27 21:14:00", DeclaredZone(ZoneId.of("Europe/Madrid"), confirmed = false))
        assertEquals(TimeResolution.NoConfirmedZone, t.resolution)
        assertEquals("2026-09-27 21:14:00", t.raw)
    }

    @Test fun invalidTimesAreRejectedStrictly() {
        assertNull(CaptureTime.parseLocalSeconds("2026-02-30 10:00:00", madrid).local)
        assertNull(CaptureTime.parseLocalSeconds("2026-09-27T21:14:00", madrid).local)
    }

    @Test fun dateOnlyStaysADate() {
        assertEquals(LocalDate.of(2026, 9, 28), CaptureTime.parseDate("2026-09-28"))
    }

    @Test fun instantWithAndWithoutMillis() {
        assertEquals(Instant.parse("2026-09-26T04:30:01Z"), CaptureTime.parseInstant("2026-09-26T04:30:01Z"))
        assertEquals(1_759_000_000_123L % 1000, (CaptureTime.parseInstant("2025-09-27T19:06:40.123Z")!!.toEpochMilli() % 1000))
    }
}
