package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.backup.AndroidJson
import com.alexisgordr.cellcase.core.backup.CsvText
import com.alexisgordr.cellcase.core.backup.Icd30Exports
import com.alexisgordr.cellcase.core.backup.SqliteFile
import com.alexisgordr.cellcase.core.csv.CsvGuard
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.zip.ZipFile

/**
 * Reconstrucción de las exportaciones de ICdetector 3.0.0 desde la base de la copia (backup/Icd30Exports.kt).
 * La referencia es tools/synth/generate_icd30.py, una segunda implementación en Python que consulta la base con
 * SQLite de verdad: las dos deben producir los mismos bytes en las tres variantes (nueva, actualizada desde 2.x y vacía).
 */
class Icd30ExportsTest {
    private val at = Instant.parse("2026-10-26T11:30:05.250Z").toEpochMilli()

    private fun exports(variant: String, f: (Icd30Exports) -> Unit) {
        val z = ZipFile(TestSupport.sample("icd30/SYNTHETIC_icd30_$variant.icdbackup"))
        val tmp = File.createTempFile("icd30-", ".db"); tmp.deleteOnExit()
        z.use { it.getInputStream(it.getEntry("icdetector_history.db")).use { i -> tmp.outputStream().use { o -> i.copyTo(o) } } }
        SqliteFile(tmp).use { f(Icd30Exports(it, "3.0.0+f5173a8", at)) }
    }

    private fun expected(variant: String, path: String) = TestSupport.sample("icd30/expected/$variant/$path").readBytes()

    private fun same(variant: String, path: String, actual: ByteArray?) {
        val e = expected(variant, path)
        if (actual == null || !e.contentEquals(actual)) {
            val a = String(actual ?: ByteArray(0)).lines(); val x = String(e).lines()
            val i = x.indices.firstOrNull { it >= a.size || a[it] != x[it] } ?: a.size
            throw AssertionError("$variant/$path difiere en la línea ${i + 1}:\n esperado: ${x.getOrNull(i)}\n obtenido: ${a.getOrNull(i)}")
        }
    }

    @Test fun everyExportIsByteIdenticalToTheReference() {
        for (v in listOf("fresh", "upgraded", "empty")) exports(v) { ex ->
            same(v, "history.csv", ex.historyCsv())
            same(v, "service_state.csv", ex.serviceStateCsv())
            ex.topology()!!.forEach { (n, b) -> same(v, "topology/$n", b) }
            ex.geometry()!!.forEach { (n, b) -> same(v, "geometry/$n", b) }
            ex.stableSite()!!.forEach { (n, b) -> same(v, "stable_site/$n", b) }
            val cases = ex.forensicCases()!!
            assertEquals(File(TestSupport.sample("icd30/expected/$v"), "forensic").listFiles()?.size ?: 0, cases.size)
            cases.forEach { c -> c.files.forEach { (n, b) -> same(v, "forensic/${c.caseCode}/$n", b) } }
        }
    }

    @Test fun backupOfAnUpgradedPhoneGivesTheSameExportsExceptTheDefaultsOfOldRows() {
        // Mismos datos en las dos bases; solo cambian las 30 filas de 2.0, que toman los valores por defecto de las migraciones.
        var fresh: List<String> = emptyList(); var upgraded: List<String> = emptyList()
        exports("fresh") { fresh = String(it.historyCsv()!!).lines() }
        exports("upgraded") { upgraded = String(it.historyCsv()!!).lines() }
        assertEquals(fresh.size, upgraded.size)
        val diff = fresh.indices.filter { fresh[it] != upgraded[it] }
        assertEquals(30, diff.size)
        assertTrue(diff.all { upgraded[it].contains(",N/A,") && upgraded[it].contains(",PENDING,") })
        var a: Map<String, ByteArray> = emptyMap(); var b: Map<String, ByteArray> = emptyMap()
        exports("fresh") { a = it.stableSite()!! }; exports("upgraded") { b = it.stableSite()!! }
        a.forEach { (n, x) -> assertArrayEquals(n, x, b[n]) }
    }

    @Test fun historyIsNewestFirstWithRowsWithoutUtcInstantLast() = exports("fresh") { ex ->
        val rows = String(ex.historyCsv()!!).lines().drop(1).filter { it.isNotEmpty() }
        val utc = rows.map { it.split(",").let { f -> f[f.size - 19 + 0] } }   // ObservedAtUtc es la columna 36 de 54
        val firstLegacy = utc.indexOfFirst { it.isEmpty() }
        assertTrue(firstLegacy > 0)
        assertTrue(utc.drop(firstLegacy).all { it.isEmpty() })
        assertEquals(utc.take(firstLegacy), utc.take(firstLegacy).sortedDescending())
    }

    @Test fun exportDeviceAndAndroidAreEmptyBecauseTheBackupDoesNotCarryThem() = exports("fresh") { ex ->
        val lines = String(ex.historyCsv()!!).lines()
        val h = lines[0].split(","); val iDev = h.indexOf("ExportDevice")
        assertTrue(lines.drop(1).filter { it.isNotEmpty() && !it.contains('"') }.all { it.split(",")[iDev].isEmpty() })
    }

    @Test fun stableSiteMaturityPolicy() {
        val m = Icd30Exports.Companion::maturity
        assertEquals(Triple("BOOTSTRAP", "NO_NEIGHBOUR_DATA", "NEEDS_SERVING_DAYS"), m(0, 0, 0, 0, 0))
        assertEquals("LEARNING", m(4, 5, 0, 0, 100).first)
        assertEquals("LEARNING", m(9, 1, 0, 0, 100).first)
        assertEquals(Triple("SHADOW_READY", "RF_NEIGHBOUR_ONLY", "NEEDS_SERVING_DAYS"), m(6, 3, 0, 4, 50))
        assertEquals(Triple("SHADOW_READY", "FULL_NEIGHBOUR_IDENTITY", "NEEDS_SERVING_OBSERVATIONS"), m(7, 3, 3, 0, 29))
        assertEquals(Triple("SHADOW_READY", "FULL_NEIGHBOUR_IDENTITY", "NEEDS_FULL_NEIGHBOUR_DAYS"), m(7, 3, 2, 0, 30))
        assertEquals(Triple("SHADOW_READY", "NO_NEIGHBOUR_DATA", "NO_USABLE_NEIGHBOUR_DATA"), m(7, 3, 0, 0, 30))
        assertEquals(Triple("ACTIVE", "FULL_NEIGHBOUR_IDENTITY", "MATURE_FULL_NEIGHBOUR_IDENTITY"), m(7, 3, 3, 1, 30))
        assertEquals(Triple("ACTIVE", "RF_NEIGHBOUR_ONLY", "MATURE_RF_NEIGHBOUR_ONLY"), m(8, 4, 0, 3, 31))
    }

    @Test fun terminalWindowsJoinRemovingOnlyTheOverlap() {
        val merge = Icd30Exports.Companion::mergeLogWindows
        assertEquals(listOf("a", "b", "c", "d"), merge(listOf(listOf("a", "b", "c"), listOf("b", "c", "d"))))
        // dos líneas iguales pueden ser dos eventos: no se deduplican
        assertEquals(listOf("x", "a", "x", "b"), merge(listOf(listOf("x", "a"), listOf("x", "b"))))
        // una ventana contenida en el final de lo ya unido no añade nada (como en ICdetector)
        assertEquals(listOf("x", "x"), merge(listOf(listOf("x", "x"), listOf("x"))))
        assertEquals(listOf("a", "b", "a", "b"), merge(listOf(listOf("a", "b"), listOf("a", "b", "a", "b"))))
        assertEquals(listOf("a", "b", "c"), merge(listOf(listOf("a"), listOf("b"), listOf("c"))))
        assertEquals(emptyList<String>(), merge(emptyList()))
    }

    @Test fun versionCodeOnlyForKnownReleases() {
        assertEquals(39L, Icd30Exports.knownVersionCode("3.0.0+f5173a8"))
        assertEquals(39L, Icd30Exports.knownVersionCode("3.0.0"))
        assertEquals(0L, Icd30Exports.knownVersionCode("3.0.1+abc"))
    }

    @Test fun csvTextGuardAndQuotesLikeIcdetector() {
        assertEquals("'=SUM(A1)", CsvGuard.guard("=SUM(A1)"))
        assertEquals("-95", CsvGuard.guard("-95")); assertEquals("-1.0E-5", CsvGuard.guard("-1.0E-5")); assertEquals("+3", CsvGuard.guard("+3"))
        assertEquals("'-Red-", CsvGuard.guard("-Red-")); assertEquals("''x", CsvGuard.guard("'x")); assertEquals("'\tx", CsvGuard.guard("\tx"))
        assertEquals("'＝x", CsvGuard.guard("＝x")); assertEquals("normal", CsvGuard.guard("normal")); assertEquals("", CsvGuard.guard(""))
        assertEquals("\"'=a,b\"", CsvText.field("=a,b")); assertEquals("\"a\"\"b\"", CsvText.quoted("a\"b")); assertEquals("x", CsvText.field("x"))
    }

    @Test fun guardIsReversibleForEveryValue() {
        val values = listOf("=1+1", "+34 600", "-Red", "@user", "'", "''", "'a", "＠x", "\u0007bell", "-12.5", "40.4168", "1e5", "texto", "", "a'b", "-", "+", "=")
        for (v in values) assertEquals(v, CsvGuard.unguard(CsvGuard.guard(v)))
        // Un texto que empieza por apóstrofo pero que ICdetector no habría protegido así se deja como está.
        assertEquals("'abc", CsvGuard.unguard("'abc")); assertEquals("'-95", CsvGuard.unguard("'-95"))
        assertFalse(CsvGuard.isGuarded("'abc")); assertTrue(CsvGuard.isGuarded("'=x"))
    }

    @Test fun androidJsonTypesFormattingAndOptSemantics() {
        val o = AndroidJson.obj("""{"i":5,"l":5000000000,"d":2.0,"e":1E3,"s":"a\/b","n":null,"t":true,"a":[1,"x",{"k":1.5}],"o":{}}""")
        assertTrue(o["i"] is Int); assertTrue(o["l"] is Long); assertTrue(o["d"] is Double); assertTrue(o["e"] is Double)
        assertEquals("a/b", o["s"]); assertTrue(o["n"] === AndroidJson.Null)
        assertEquals("5", AndroidJson.optString(o, "i")); assertEquals("2.0", AndroidJson.optString(o, "d")); assertEquals("null", AndroidJson.optString(o, "n"))
        assertEquals("", AndroidJson.optString(o, "missing")); assertEquals("""[1,"x",{"k":1.5}]""", AndroidJson.optString(o, "a"))
        assertEquals(2, AndroidJson.optInt(o, "d")); assertEquals(0, AndroidJson.optInt(o, "t")); assertEquals(0, AndroidJson.optInt(o, "missing"))
        assertTrue(AndroidJson.optDouble(o, "missing").isNaN()); assertEquals(1000.0, AndroidJson.optDouble(o, "e"), 0.0)
        assertTrue(AndroidJson.isNullOrMissing(o, "n")); assertFalse(AndroidJson.isNullOrMissing(o, "o"))
        assertEquals("{\n  \"a\": 2,\n  \"b\": [\n    1.5,\n    \"x\\/y\"\n  ],\n  \"c\": {},\n  \"d\": null,\n  \"e\": -0\n}",
            AndroidJson.pretty(linkedMapOf("a" to 2.0, "b" to listOf(1.5, "x/y"), "c" to emptyMap<String, Any?>(), "d" to AndroidJson.Null, "e" to -0.0)))
        for (bad in listOf("{", "{\"a\":}", "[1,]x", "{\"a\" 1}", "\"sin cerrar")) try { AndroidJson.parse(bad); throw AssertionError(bad) } catch (_: AndroidJson.ParseException) {}
        val deep = "[".repeat(100) + "]".repeat(100)
        try { AndroidJson.parse(deep); throw AssertionError("anidamiento") } catch (_: AndroidJson.ParseException) {}
    }
}
