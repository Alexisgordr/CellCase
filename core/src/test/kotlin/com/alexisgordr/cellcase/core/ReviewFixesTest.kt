package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.backup.AndroidJson
import com.alexisgordr.cellcase.core.csv.CsvReader
import com.alexisgordr.cellcase.core.importing.ExportKind
import com.alexisgordr.cellcase.core.importing.Hashing
import com.alexisgordr.cellcase.core.importing.IcdVersion
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.importing.ImportState
import com.alexisgordr.cellcase.core.importing.ImportedData
import com.alexisgordr.cellcase.core.importing.MiniJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Fallos de la revisión de código del 20/10/2026 (lectura de CSV y JSON hostiles). */
class ReviewFixesTest {

    // ── 1. Líneas en blanco: bucle, no recursión ──

    @Test fun manyBlankLinesDoNotOverflowTheStack() {
        val csv = TestSupport.HEADER + "\n" + "\n".repeat(50_000)
        val r = ImportPipeline.process(csv.toByteArray(), "blancos.csv", "b", TestSupport.MADRID)
        assertEquals(ExportKind.HISTORY, r.kind)
        assertEquals(0, (r.data as ImportedData.History).data.observations.size)
    }

    @Test fun blankLinesBetweenRecordsKeepLineNumbers() {
        val reader = CsvReader("a,b\n\r\n\n\r\n1,2\n\n\n".reader())
        assertEquals(listOf("a", "b"), reader.next()!!.fields)
        val rec = reader.next()!!
        assertEquals(listOf("1", "2"), rec.fields)
        assertEquals(5, rec.startLine)
        assertNull(reader.next())
        // CRLF y CR sueltos repetidos también
        val crs = CsvReader(("x\n" + "\r".repeat(20_000) + "y\n").reader())
        assertEquals(listOf("x"), crs.next()!!.fields); assertEquals(listOf("y"), crs.next()!!.fields); assertNull(crs.next())
    }

    // ── 4. JSON y versiones hostiles ──

    private val forensic = TestSupport.sample("SYNTHETIC_ICD-2026-09-27-0007.zip").readBytes()

    private fun withCaseJson(edit: (String) -> String): ByteArray = ReviewZip.withCaseJson(forensic, edit)

    private fun import(bytes: ByteArray): ImportResult = ImportPipeline.process(bytes, "caso.zip", "c", TestSupport.MADRID)

    @Test fun invalidUnicodeEscapeIsAReadError() {
        val r = import(withCaseJson { it.replace("\"SYNTHETIC Phone\"", "\"\\uZZZZ\"") })
        assertEquals(ImportState.READ_ERROR, r.state)
        try { MiniJson.parse("\"\\uZZZZ\""); fail() } catch (_: MiniJson.JsonException) {}
        try { MiniJson.parse("\"\\u+1F6\""); fail() } catch (_: MiniJson.JsonException) {}
        assertEquals("é", MiniJson.parse("\"\\u00e9\""))
        try { AndroidJson.parse("\"\\uZZZZ\""); fail() } catch (_: AndroidJson.ParseException) {}
        assertEquals("é", AndroidJson.parse("\"\\u00E9\""))
    }

    @Test fun infiniteNumbersAreAReadError() {
        val r = import(withCaseJson { it.replace("\"sampleCount\": 54", "\"sampleCount\": 1e999") })
        assertEquals(ImportState.READ_ERROR, r.state)
        for (t in listOf("1e999", "-1e999", "[1E400]")) try { MiniJson.parse(t); fail(t) } catch (_: MiniJson.JsonException) {}
        assertEquals(1.0E300, MiniJson.parse("1e300"))
    }

    @Test fun absurdAppVersionDoesNotRejectTheCase() {
        assertNull(IcdVersion.parse("99999999999.0.0"))
        assertNull(IcdVersion.parse("3.99999999999"))
        assertFalse(IcdVersion.atLeast("99999999999.0.0", 3, 0, 0))
        assertEquals(Triple(3, 0, 0), IcdVersion.parse("3.0.0+f5173a8"))
        val r = import(withCaseJson { it.replace("\"appVersion\": \"2.10.5\"", "\"appVersion\": \"99999999999.0.0\"") })
        assertEquals(ImportState.IMPORTED, r.state)
        assertEquals("99999999999.0.0", (r.data as ImportedData.Forensic).data.meta.appVersion)
    }

    @Test fun anyOtherRuntimeFailureBecomesAReadableReadError() {
        // Un objeto en lugar de texto en un campo que se lee como texto no debe cerrar la app.
        val odd = import(withCaseJson { it.replace("\"cellIdentity\": \"001-01-1099-9999001-LTE\"", "\"cellIdentity\": {\"a\": [1, 2]}") })
        assertTrue(odd.state.toString(), odd.state == ImportState.IMPORTED || odd.state == ImportState.READ_ERROR || odd.state == ImportState.INCOMPATIBLE)
        // Los tres casos de la revisión, ninguno lanza excepción
        for (edit in listOf<(String) -> String>({ it.replace("\"16\"", "\"\\uZZZZ\"") }, { it.replace("\"highestPhase\": 3", "\"highestPhase\": 1e999") },
            { it.replace("\"2.10.5\"", "\"99999999999.0.0\"") })) {
            val r = import(withCaseJson(edit))
            assertTrue(r.state.toString(), r.state == ImportState.READ_ERROR || r.state == ImportState.IMPORTED)
            if (r.state == ImportState.READ_ERROR) assertTrue(r.message!!.isNotBlank())
        }
    }
}

/** Ayudas compartidas por las pruebas de la revisión. */
object ReviewZip {
    /** El caso forense [zip] con otro case.json y su SHA256SUMS recalculado. */
    fun withCaseJson(zip: ByteArray, edit: (String) -> String): ByteArray {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(zip.inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        entries["case.json"] = edit(String(entries["case.json"]!!, Charsets.UTF_8)).toByteArray()
        entries["SHA256SUMS.txt"] = entries.filterKeys { it != "SHA256SUMS.txt" }.entries
            .joinToString("") { (n, b) -> Hashing.sha256Hex(b) + "  " + n + "\n" }.toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return out.toByteArray()
    }
}
