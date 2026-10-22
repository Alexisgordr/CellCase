package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.backup.CursorValue
import com.alexisgordr.cellcase.core.backup.SqliteFile
import com.alexisgordr.cellcase.core.backup.SqliteFormatException
import com.alexisgordr.cellcase.core.backup.SqliteSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.util.zip.ZipFile

/** Lector SQLite de solo lectura (backup/SqliteFile.kt) con las bases sintéticas de ICdetector 3.0.0. */
class SqliteFileTest {
    private fun db(variant: String): File {
        val z = ZipFile(TestSupport.sample("icd30/SYNTHETIC_icd30_$variant.icdbackup"))
        val f = File.createTempFile("sqlite-test-", ".db"); f.deleteOnExit()
        z.use { it.getInputStream(it.getEntry("icdetector_history.db")).use { i -> f.outputStream().use { o -> i.copyTo(o) } } }
        return f
    }

    private fun expectedRows(variant: String, file: String) =
        TestSupport.sample("icd30/expected/$variant/$file").readLines().size - 1

    @Test fun readsHeaderSchemaAndAllTablesOfSchema25() {
        for (v in listOf("fresh", "upgraded", "empty")) SqliteFile(db(v)).use { d ->
            assertEquals(25, d.userVersion)
            assertEquals(4096, d.pageSize)
            assertEquals(1, d.textEncoding)
            for (t in listOf("history", "profile_evaluations", "local_profile", "profile_mode_events", "service_state_events", "cell_transitions",
                "mobility_trips", "mobility_trip_cells", "mobility_trip_edges", "site_cell_evidence", "site_cell_days", "site_motion_days",
                "site_rf_neighbours", "site_rf_neighbour_days", "site_shadow_triggers", "site_holds", "site_cell_events", "forensic_cases",
                "forensic_samples", "incidents")) assertTrue("$v: falta $t", d.table(t) != null)
        }
    }

    @Test fun rowCountsMatchTheReferenceExports() {
        for (v in listOf("fresh", "upgraded", "empty")) SqliteFile(db(v)).use { d ->
            val h = d.table("history")!!
            assertEquals(expectedRows(v, "history.csv").toLong(), d.count(h))
            assertEquals(d.count(h), d.rows(h).count().toLong())
            assertEquals(expectedRows(v, "service_state.csv").toLong(), d.count(d.table("service_state_events")!!))
        }
    }

    @Test fun rowsComeInRowidOrderAndIntegerPrimaryKeyIsTheRowid() = SqliteFile(db("fresh")).use { d ->
        val h = d.table("history")!!
        val id = h.indexOf("id")
        assertTrue(h.columns[id].rowidAlias)
        val rows = d.rows(h).toList()
        assertEquals(rows.map { it.rowid }.sorted(), rows.map { it.rowid })
        assertTrue(rows.all { it[id] == it.rowid })
        // 12 filas borradas por retención: los rowid tienen un hueco, no se renumeran
        assertTrue(rows.zipWithNext().any { (a, b) -> b.rowid - a.rowid > 1 })
    }

    @Test fun columnsAddedWithAlterTableReturnTheirDefaultInOldRecords() = SqliteFile(db("upgraded")).use { d ->
        val h = d.table("history")!!
        // En la base actualizada el orden físico es el de las migraciones: «verified» va después de «dbm».
        assertEquals(listOf("id", "timestamp", "net_type", "cid", "mnc", "tac", "dbm", "verified", "mcc"), h.columns.take(9).map { it.name })
        val first = d.rows(h).first()
        assertEquals("PENDING", first[h.indexOf("verified")])
        assertEquals("N/A", first[h.indexOf("mcc")])
        assertEquals(100L, first[h.indexOf("score")])
        assertEquals("", first[h.indexOf("failed_heuristics")])
        assertEquals(0.0, first[h.indexOf("threat_prob")])
        assertNull(first[h.indexOf("radio")])
        assertNull(first[h.indexOf("observed_at_ms")])
        // Una ruta de 2.x anterior al esquema 25: failed_count NOT NULL DEFAULT 0
        val t = d.table("cell_transitions")!!
        assertTrue(d.rows(t).all { it[t.indexOf("failed_count")] is Long })
        assertTrue(d.freelistPages > 0)
    }

    @Test fun overflowPagesRebuildLongRecords() = SqliteFile(db("fresh")).use { d ->
        val s = d.table("forensic_samples")!!
        val payloads = d.rows(s).map { it[s.indexOf("payload_json")] as String }.toList()
        assertTrue(payloads.any { it.toByteArray().size > 4096 })
        payloads.forEach { com.alexisgordr.cellcase.core.backup.AndroidJson.obj(it) }   // JSON completo, sin cortes
    }

    @Test fun readsUtf16Text() = SqliteFile(TestSupport.sample("icd30/fixtures/utf16le.db")).use { d ->
        assertEquals(2, d.textEncoding)
        val t = d.table("t")!!
        val rows = d.rows(t).toList()
        assertEquals("ñandú ☂ €", rows[0][t.indexOf("txt")])
        assertEquals(1.5, rows[0][t.indexOf("n")])
        assertNull(rows[1][t.indexOf("txt")])
        assertEquals(-2.25, rows[1][t.indexOf("n")])
    }

    @Test fun rejectsWithoutRowidTables() {
        SqliteFile(TestSupport.sample("icd30/fixtures/without_rowid.db")).use { d ->
            try { d.tables; fail("debía rechazar WITHOUT ROWID") } catch (e: SqliteFormatException) { assertTrue(e.message!!.contains("WITHOUT ROWID")) }
        }
    }

    @Test fun varintsOfOneToNineBytes() {
        fun enc(v: Long): ByteArray {
            if (v < 0 || v > 0x00FFFFFFFFFFFFFFL) {   // 9 bytes: 8 de 7 bits y el último de 8
                val b = ByteArray(9); var x = v
                b[8] = (x and 0xFF).toByte(); x = x ushr 8
                for (i in 7 downTo 0) { b[i] = ((x and 0x7F) or 0x80).toByte(); x = x ushr 7 }
                return b
            }
            val out = ArrayList<Byte>(); var x = v
            out += (x and 0x7F).toByte(); x = x ushr 7
            while (x != 0L) { out.add(0, ((x and 0x7F) or 0x80).toByte()); x = x ushr 7 }
            return out.toByteArray()
        }
        for (v in listOf(0L, 1L, 127L, 128L, 16383L, 16384L, 2_097_151L, 1L shl 35, 1L shl 55, (1L shl 56) + 5, Long.MAX_VALUE, -1L)) {
            val b = enc(v)
            assertEquals("v=$v", v to b.size, SqliteFile.varint(b, 0))
        }
        try { SqliteFile.varint(byteArrayOf(0x81.toByte()), 0); fail() } catch (_: SqliteFormatException) {}
    }

    @Test fun parsesCreateTableIncludingAlterAddedColumnsDefaultsAndConstraints() {
        val cols = SqliteSchema.columns("CREATE TABLE \"h\" (id INTEGER PRIMARY KEY AUTOINCREMENT, \"weird, name\" TEXT, " +
            "score INTEGER DEFAULT 100, v TEXT DEFAULT 'it''s', r REAL DEFAULT 0, n NUMERIC DEFAULT '7', k TEXT NOT NULL DEFAULT (3), " +
            "c TEXT CHECK (c IN ('a','b')), PRIMARY KEY(x, y), FOREIGN KEY(a) REFERENCES b(c), d, e INTEGER DEFAULT -5)")
        assertEquals(listOf("id", "weird, name", "score", "v", "r", "n", "k", "c", "d", "e"), cols.map { it.name })
        assertTrue(cols[0].rowidAlias); assertFalse(cols[2].rowidAlias)
        assertEquals(100L, cols[2].default); assertEquals("it's", cols[3].default); assertEquals(0.0, cols[4].default)
        assertEquals(7L, cols[5].default); assertEquals("3", cols[6].default); assertNull(cols[8].default); assertEquals(-5L, cols[9].default)
        assertFalse(SqliteSchema.columns("CREATE TABLE t (id INTEGER PRIMARY KEY DESC)")[0].rowidAlias)
        assertFalse(SqliteSchema.columns("CREATE TABLE t (episode_id TEXT PRIMARY KEY NOT NULL)")[0].rowidAlias)
    }

    // ── archivos dañados o manipulados: error controlado, nunca un bucle ──

    private fun mutated(f: (ByteArray) -> Unit): File {
        val b = db("fresh").readBytes(); f(b)
        return File.createTempFile("sqlite-bad-", ".db").also { it.writeBytes(b); it.deleteOnExit() }
    }

    /** Una base dañada debe dar SqliteFormatException tanto al leer las filas como al contarlas (revisión, fallo 5). */
    private fun expectFailure(file: File, alsoCount: Boolean = true) {
        try {
            SqliteFile(file).use { d -> d.tables.values.forEach { t -> d.rows(t).count() } }
            fail("rows: debía fallar")
        } catch (_: SqliteFormatException) {}
        if (alsoCount) try {
            SqliteFile(file).use { d -> d.tables.values.forEach { t -> d.count(t) } }
            fail("count: debía fallar")
        } catch (_: SqliteFormatException) {}
    }

    /** Página (número y desplazamiento) de la primera hoja de `history`, bajando por el primer hijo. */
    private fun firstLeafOfHistory(b: ByteArray): Int {
        fun u16(at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        fun u32(at: Int) = ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF)
        var pn = SqliteFile(db("fresh")).use { it.table("history")!!.rootPage.toInt() }
        while (true) {
            val off = (pn - 1) * 4096
            when (b[off].toInt()) { 13 -> return off; 5 -> pn = u32(off + u16(off + 12)); else -> error("tipo ${b[off]}") }
        }
    }

    @Test fun cellCountBeyondThePageFailsInRowsAndCount() {
        val (_, off) = interiorPageOfHistory()
        expectFailure(mutated { b -> b[off + 3] = 0xFF.toByte(); b[off + 4] = 0xFF.toByte() })
        // también en una hoja
        expectFailure(mutated { b -> val leaf = firstLeafOfHistory(b); b[leaf + 3] = 0xFF.toByte(); b[leaf + 4] = 0xFF.toByte() })
        // puntero de celda interior en los últimos bytes de la página (el número de página no cabe)
        expectFailure(mutated { b -> val cp = off + 12; val at = 4096 - 2; b[cp] = (at ushr 8).toByte(); b[cp + 1] = at.toByte() })
    }

    @Test fun hugeSerialTypeIsAFormatErrorNotACrash() {
        expectFailure(mutated { b ->
            val leaf = firstLeafOfHistory(b)
            val cell = leaf + (((b[leaf + 8].toInt() and 0xFF) shl 8) or (b[leaf + 9].toInt() and 0xFF))
            var pos = cell
            pos += SqliteFile.varint(b, pos).second            // tamaño del registro
            pos += SqliteFile.varint(b, pos).second            // rowid
            val (hlen, n) = SqliteFile.varint(b, pos)
            assertTrue("cabecera con sitio para el varint", hlen >= n + 6)
            // tipo 2^35: longitud (2^35 − 12) / 2, que al pasar a Int daba un número negativo
            byteArrayOf(0x81.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x80.toByte(), 0x00).copyInto(b, pos + n)
        }, alsoCount = false)   // count() solo cuenta celdas: no decodifica registros, así que no llega a este valor
    }

    @Test fun rejectsNonSqliteAndTruncatedFiles() {
        expectFailure(File.createTempFile("bad-", ".db").also { it.writeText("no soy una base"); it.deleteOnExit() })
        val b = db("fresh").readBytes()
        expectFailure(File.createTempFile("trunc-", ".db").also { it.writeBytes(b.copyOf(b.size / 2)); it.deleteOnExit() })
        expectFailure(mutated { it[16] = 0; it[17] = 3 })      // tamaño de página que no es potencia de dos
        expectFailure(mutated { it[56] = 0; it[57] = 0; it[58] = 0; it[59] = 9 })   // codificación inexistente
    }

    /** Página interior de la tabla `history` (la primera que haya) para manipularla. */
    private fun interiorPageOfHistory(): Pair<Int, Int> {
        SqliteFile(db("fresh")).use { d ->
            val root = d.table("history")!!.rootPage.toInt()
            val bytes = db("fresh").readBytes()
            val off = (root - 1) * 4096
            assertEquals("la raíz de history debe ser interior en la muestra", 5, bytes[off].toInt())
            return root to off
        }
    }

    @Test fun detectsCyclesAndOutOfRangePages() {
        val (root, off) = interiorPageOfHistory()
        // el hijo derecho apunta a la propia página: ciclo
        expectFailure(mutated { b -> b[off + 8] = (root ushr 24).toByte(); b[off + 9] = (root ushr 16).toByte(); b[off + 10] = (root ushr 8).toByte(); b[off + 11] = root.toByte() })
        // el hijo derecho apunta fuera del archivo
        expectFailure(mutated { b -> b[off + 8] = 0x7F; b[off + 9] = 0; b[off + 10] = 0; b[off + 11] = 1 })
        // tipo de página imposible
        expectFailure(mutated { b -> b[off] = 7 })
    }

    /**
     * Revisión 22/10 (A4): una página interior cuyos hijos apuntan todos a la misma página es un error de formato, y se
     * detecta al apilar el segundo hijo (antes se apilaban todos y la pila crecía con cada página interior).
     */
    @Test fun repeatedChildPagesAreACycle() {
        val (_, off) = interiorPageOfHistory()
        expectFailure(mutated { b ->
            val cells = ((b[off + 3].toInt() and 0xFF) shl 8) or (b[off + 4].toInt() and 0xFF)
            assertTrue("la raíz tiene al menos dos hijos", cells >= 1)
            val right = b.copyOfRange(off + 8, off + 12)
            for (i in 0 until cells) {
                val cell = off + (((b[off + 12 + 2 * i].toInt() and 0xFF) shl 8) or (b[off + 13 + 2 * i].toInt() and 0xFF))
                right.copyInto(b, cell)      // todos los hijos = el hijo derecho
            }
        })
    }

    /** Revisión 22/10 (A5): un identificador entrecomillado sin cerrar es un error de formato, no una excepción de índice. */
    @Test fun unclosedQuotedIdentifierIsAFormatError() {
        for (sql in listOf("CREATE TABLE t (\"abc TEXT, x INTEGER)", "CREATE TABLE t ([abc TEXT)"))
            try { SqliteSchema.columns(sql); fail(sql) } catch (_: SqliteFormatException) {}
    }

    @Test fun cursorConversionsLikeAndroid() {
        assertEquals("42", CursorValue.string(42L)); assertEquals("1.5", CursorValue.string(1.5))
        assertEquals("100000", CursorValue.string(100000.0)); assertEquals("1e+06", CursorValue.string(1_000_000.0))
        assertEquals("1.23457e-05", CursorValue.string(0.0000123456789)); assertEquals("-0.25", CursorValue.string(-0.25))
        assertEquals(0, CursorValue.int(null)); assertEquals(-97, CursorValue.int("-97 dBm")); assertEquals(26, CursorValue.int("0x1A"))
        assertEquals(8, CursorValue.int("010")); assertEquals(3, CursorValue.int(3.99)); assertEquals(-1, CursorValue.int(0xFFFFFFFFL))
        assertEquals(2.5, CursorValue.double("2.5abc"), 0.0); assertEquals(0.0, CursorValue.double("abc"), 0.0); assertEquals(7.0, CursorValue.double(7L), 0.0)
    }

    /**
     * Una cabecera corrupta no deja el archivo abierto (aviso externo, D-058): antes la excepción salía del constructor con
     * el RandomAccessFile ya abierto y nadie podía cerrarlo. En Linux se cuentan los descriptores del proceso.
     */
    @Test fun corruptHeaderDoesNotLeakTheFileHandle() {
        val fd = File("/proc/self/fd")
        org.junit.Assume.assumeTrue("solo en Linux (/proc/self/fd)", fd.isDirectory)
        val bad = listOf(
            ByteArray(100),                                                        // demasiado corto
            ByteArray(1024).also { "no soy sqlite".toByteArray().copyInto(it) },   // sin la firma
            ByteArray(1024).also { "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(it); it[16] = 0; it[17] = 3 }, // página de 3 bytes
            ByteArray(1024).also { "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1).copyInto(it); it[16] = 4; it[17] = 0; it[19] = 9 } // versión de lectura 9
        )
        val files = bad.mapIndexed { i, b -> File.createTempFile("corrupt$i", ".db").also { it.deleteOnExit(); it.writeBytes(b) } }
        fun open() = fd.list()!!.size
        // Una primera pasada por si la JVM abre algo perezosamente; después la cuenta no debe crecer.
        files.forEach { f -> try { SqliteFile(f); fail("debía fallar") } catch (_: SqliteFormatException) {} }
        val before = open()
        repeat(50) { files.forEach { f -> try { SqliteFile(f); fail("debía fallar") } catch (_: SqliteFormatException) {} } }
        val after = open()
        assertTrue("descriptores abiertos: antes $before, después $after", after <= before)
        files.forEach { it.delete() }
    }
}
