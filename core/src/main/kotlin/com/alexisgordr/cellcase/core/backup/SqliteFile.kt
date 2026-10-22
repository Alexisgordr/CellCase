package com.alexisgordr.cellcase.core.backup

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/** Archivo SQLite no válido o con una estructura que este lector no admite. El mensaje es técnico (va al registro). */
class SqliteFormatException(message: String) : Exception(message)

/**
 * Lector de **solo lectura** del formato de archivo de SQLite 3 (https://www.sqlite.org/fileformat2.html), escrito
 * para la base de la copia de seguridad de ICdetector (docs/backup-format.md §3). No ejecuta SQL ni escribe nada:
 * recorre los árboles B de las tablas y decodifica sus registros.
 *
 * Admite lo que produce la base de ICdetector: tablas con rowid, páginas de desbordamiento, columnas añadidas con
 * `ALTER TABLE … ADD COLUMN` (los registros antiguos no las llevan y se devuelve su valor por defecto, como SQLite),
 * alias de rowid (`INTEGER PRIMARY KEY`) y texto en UTF-8 o UTF-16. No admite tablas `WITHOUT ROWID` ni lee el
 * diario WAL (ICdetector consolida la copia en un solo archivo antes de guardarla).
 *
 * Defensas ante archivos dañados o manipulados: números de página fuera del archivo, ciclos en árboles y cadenas de
 * desbordamiento, profundidad máxima, celdas fuera de la página y registros de tamaño absurdo producen
 * [SqliteFormatException]; nunca un bucle infinito ni una reserva de memoria descontrolada.
 */
class SqliteFile(file: File) : Closeable {
    private val raf = RandomAccessFile(file, "r")
    val fileBytes: Long = raf.length()
    private val header: Header
    val pageSize: Int get() = header.pageSize
    private val usable: Int get() = header.usable
    val pageCount: Long get() = header.pageCount
    val textEncoding: Int get() = header.textEncoding
    val userVersion: Int get() = header.userVersion
    /** 2 si la base estaba en modo WAL al copiarla (lo que no se volcó al archivo principal no está aquí). */
    val readVersion: Int get() = header.readVersion
    val freelistPages: Long get() = header.freelistPages
    private val charset: Charset get() = header.charset
    private val cache = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > 256
    }

    init {
        // Si la cabecera no vale, el objeto no llega a construirse y nadie podría cerrarlo: se cierra aquí (D-058).
        header = try { readHeader() } catch (e: Throwable) { runCatching { raf.close() }; throw e }
    }

    private class Header(val pageSize: Int, val usable: Int, val pageCount: Long, val textEncoding: Int, val userVersion: Int,
        val readVersion: Int, val freelistPages: Long, val charset: Charset)

    private fun readHeader(): Header {
        if (fileBytes < 512) fail("archivo demasiado corto ($fileBytes bytes)")
        val h = ByteArray(100); raf.seek(0); raf.readFully(h)
        if (String(h, 0, 16, StandardCharsets.ISO_8859_1) != "SQLite format 3\u0000") fail("no es una base de datos SQLite 3")
        val ps = u16(h, 16)
        val pageSize = if (ps == 1) 65536 else ps
        if (pageSize < 512 || pageSize > 65536 || pageSize and (pageSize - 1) != 0) fail("tamaño de página no válido: $ps")
        val readVersion = h[19].toInt() and 0xFF
        if (readVersion !in 1..2) fail("versión de lectura no admitida: $readVersion")
        val reserved = h[20].toInt() and 0xFF
        val usable = pageSize - reserved
        if (usable < 480) fail("espacio reservado excesivo: $reserved")
        val declared = u32(h, 28)
        val valid = u32(h, 92) == u32(h, 24)
        val pageCount = if (declared > 0 && valid) declared else fileBytes / pageSize
        if (pageCount <= 0 || pageCount * pageSize > fileBytes) fail("el archivo es más corto que sus $pageCount páginas")
        val freelistPages = u32(h, 36)
        val textEncoding = u32(h, 56).toInt().let { if (it == 0) 1 else it }
        val charset = when (textEncoding) {
            1 -> StandardCharsets.UTF_8
            2 -> StandardCharsets.UTF_16LE
            3 -> StandardCharsets.UTF_16BE
            else -> fail("codificación de texto no válida: $textEncoding")
        }
        val userVersion = u32(h, 60).toInt()
        return Header(pageSize, usable, pageCount, textEncoding, userVersion, readVersion, freelistPages, charset)
    }

    override fun close() = raf.close()

    private fun fail(m: String): Nothing = throw SqliteFormatException(m)

    /**
     * Una base dañada no debe salir como IndexOutOfBounds/IllegalArgument/NegativeArraySize: se convierten en
     * [SqliteFormatException] para que quien lee lo trate como formato corrupto.
     */
    private inline fun <T> corrupt(pn: Long, block: () -> T): T = try { block() } catch (e: SqliteFormatException) { throw e
    } catch (e: IndexOutOfBoundsException) { fail("lectura fuera de los datos en la página $pn")
    } catch (e: IllegalArgumentException) { fail("dato no válido en la página $pn")
    } catch (e: NegativeArraySizeException) { fail("longitud negativa en la página $pn") }

    private fun page(n: Long): ByteArray {
        if (n < 1 || n > pageCount) fail("referencia a la página $n fuera del archivo ($pageCount páginas)")
        cache[n]?.let { return it }
        val b = ByteArray(pageSize)
        raf.seek((n - 1) * pageSize); raf.readFully(b)
        cache[n] = b
        return b
    }

    // ───────────────────────── esquema ─────────────────────────

    /** Tabla del esquema con sus columnas en el orden físico de los registros. */
    class Table(val name: String, val rootPage: Long, val sql: String, val columns: List<Column>) {
        private val index = columns.withIndex().associate { (i, c) -> c.name.lowercase() to i }
        fun indexOf(column: String): Int = index[column.lowercase()] ?: -1
        fun has(column: String) = indexOf(column) >= 0
    }

    /**
     * Columna declarada. [rowidAlias]: `INTEGER PRIMARY KEY` (el registro guarda NULL y el valor es el rowid).
     * [default]: valor por defecto ya con la afinidad de la columna; es lo que SQLite devuelve para los registros
     * escritos antes de añadir la columna con ALTER TABLE.
     */
    data class Column(val name: String, val type: String, val rowidAlias: Boolean, val default: Any?)

    /** Tablas por nombre (sin distinguir mayúsculas). */
    val tables: Map<String, Table> by lazy { readSchema() }

    fun table(name: String): Table? = tables[name.lowercase()]

    private fun readSchema(): Map<String, Table> {
        val out = LinkedHashMap<String, Table>()
        val master = Table("sqlite_master", 1, "", listOf("type", "name", "tbl_name", "rootpage", "sql").map { Column(it, "", false, null) })
        for (r in rows(master)) {
            if (r[0] != "table") continue
            val name = r[1] as? String ?: continue
            val sql = r[4] as? String ?: continue
            val root = (r[3] as? Long) ?: continue
            if (name.startsWith("sqlite_")) continue
            if (Regex("\\)\\s*WITHOUT\\s+ROWID\\s*;?\\s*$", RegexOption.IGNORE_CASE).containsMatchIn(sql)) fail("tabla WITHOUT ROWID no admitida: $name")
            out[name.lowercase()] = Table(name, root, sql, SqliteSchema.columns(sql))
        }
        return out
    }

    // ───────────────────────── registros ─────────────────────────

    /** Filas de [table] en orden de rowid. Cada fila tiene un valor por columna (Long, Double, String, ByteArray o null). */
    fun rows(table: Table): Sequence<Row> = sequence {
        // Una página se marca como visitada al apilarla, no al sacarla (A4): así la pila nunca tiene más páginas que
        // la base, aunque una base manipulada repita los mismos hijos en muchas páginas interiores.
        val visited = HashSet<Long>()
        val stack = ArrayDeque<Pair<Long, Int>>()
        fun push(pn: Long, depth: Int) { if (!visited.add(pn)) fail("ciclo en el árbol de ${table.name} (página $pn)"); stack.addLast(pn to depth) }
        push(table.rootPage, 0)
        // Recorrido en profundidad por orden de clave: los hijos se apilan al revés.
        while (stack.isNotEmpty()) {
            val (pn, depth) = stack.removeLast()
            if (depth > 40) fail("árbol demasiado profundo en ${table.name}")
            val p = page(pn)
            val off = if (pn == 1L) 100 else 0
            val type = p[off].toInt() and 0xFF
            val cells = u16(p, off + 3)
            val headerLen = if (type == 5) 12 else 8
            if (off + headerLen + 2 * cells > usable) fail("demasiadas celdas en la página $pn")
            fun cellAt(i: Int): Int = u16(p, off + headerLen + 2 * i).also { if (it < off + headerLen || it >= usable) fail("celda fuera de la página $pn") }
            when (type) {
                13 -> for (i in 0 until cells) yield(corrupt(pn) { leafCell(table, p, cellAt(i), pn) })
                5 -> {
                    push(u32(p, off + 8), depth + 1)
                    for (i in cells - 1 downTo 0) push(child(p, cellAt(i), pn), depth + 1)
                }
                else -> fail("página $pn de ${table.name}: tipo $type, se esperaba una página de tabla")
            }
        }
    }

    /** Página hija de una celda interior: los 4 bytes deben caber en la zona útil de la página. */
    private fun child(p: ByteArray, at: Int, pn: Long): Long {
        if (at + 4 > usable) fail("celda interior fuera de la página $pn")
        return u32(p, at)
    }

    /** Número de filas de [table], sin decodificar los registros (solo se cuentan las celdas de las hojas). */
    fun count(table: Table): Long = corrupt(table.rootPage) { countRows(table) }

    private fun countRows(table: Table): Long {
        var n = 0L
        val visited = HashSet<Long>()
        val stack = ArrayDeque<Pair<Long, Int>>()
        fun push(pn: Long, depth: Int) { if (!visited.add(pn)) fail("ciclo en el árbol de ${table.name} (página $pn)"); stack.addLast(pn to depth) }
        push(table.rootPage, 0)
        while (stack.isNotEmpty()) {
            val (pn, depth) = stack.removeLast()
            if (depth > 40) fail("árbol demasiado profundo en ${table.name}")
            val p = page(pn); val off = if (pn == 1L) 100 else 0
            val type = p[off].toInt() and 0xFF
            val cells = u16(p, off + 3)
            // Las mismas comprobaciones que rows(): contar no debe leer fuera de la página con una base dañada.
            val headerLen = if (type == 5) 12 else 8
            if (off + headerLen + 2 * cells > usable) fail("demasiadas celdas en la página $pn")
            fun cellAt(i: Int): Int = u16(p, off + headerLen + 2 * i).also { if (it < off + headerLen || it >= usable) fail("celda fuera de la página $pn") }
            when (type) {
                13 -> { for (i in 0 until cells) cellAt(i); n += cells }
                5 -> { push(u32(p, off + 8), depth + 1); for (i in 0 until cells) push(child(p, cellAt(i), pn), depth + 1) }
                else -> fail("página $pn de ${table.name}: no es una página de tabla")
            }
        }
        return n
    }

    /** Una fila: rowid y valores en el orden de [Table.columns]. */
    class Row(val rowid: Long, val values: Array<Any?>) {
        operator fun get(i: Int): Any? = if (i < 0 || i >= values.size) null else values[i]
    }

    private fun leafCell(t: Table, p: ByteArray, at: Int, pn: Long): Row {
        var pos = at
        val (size, n1) = varint(p, pos); pos += n1
        val (rowid, n2) = varint(p, pos); pos += n2
        if (size < 0 || size > MAX_RECORD) fail("registro de tamaño no válido ($size) en la página $pn")
        val payload = payload(p, pos, size.toInt(), pn)
        return Row(rowid, decode(t, payload, rowid))
    }

    private fun payload(p: ByteArray, start: Int, size: Int, pn: Long): ByteArray {
        val x = usable - 35
        val local = if (size <= x) size else {
            val m = ((usable - 12) * 32 / 255) - 23
            val k = m + ((size - m) % (usable - 4))
            if (k <= x) k else m
        }
        if (start + local > usable || (size > local && start + local + 4 > usable)) fail("registro fuera de la página $pn")
        val out = ByteArray(size)
        System.arraycopy(p, start, out, 0, local)
        var done = local
        var next = if (size > local) u32(p, start + local) else 0L
        val seen = HashSet<Long>()
        while (done < size) {
            if (next == 0L) fail("cadena de desbordamiento incompleta (página $pn)")
            if (!seen.add(next)) fail("ciclo en una cadena de desbordamiento (página $next)")
            val op = page(next)
            val n = minOf(usable - 4, size - done)
            System.arraycopy(op, 4, out, done, n)
            done += n
            next = u32(op, 0)
        }
        return out
    }

    private fun decode(t: Table, rec: ByteArray, rowid: Long): Array<Any?> {
        val (hlen, n0) = varint(rec, 0)
        if (hlen < n0 || hlen > rec.size) fail("cabecera de registro no válida en ${t.name}")
        val cols = t.columns
        val types = ArrayList<Long>()
        var pos = n0
        // Solo los tipos de las columnas de la tabla (A4): un registro manipulado puede declarar millones de tipos en
        // su cabecera; los que sobran no se usan (el cuerpo empieza en hlen igualmente).
        while (pos < hlen && (cols.isEmpty() || types.size < cols.size)) { val (st, n) = varint(rec, pos); types += st; pos += n }
        var body = hlen.toInt()
        val count = if (cols.isEmpty()) types.size else cols.size
        val out = arrayOfNulls<Any?>(count)
        for (i in 0 until count) {
            if (i >= types.size) { out[i] = cols.getOrNull(i)?.default; continue }   // columna añadida después: valor por defecto
            val st = types[i]
            val len = when {
                st in 0L..4L -> intArrayOf(0, 1, 2, 3, 4)[st.toInt()]
                st == 5L -> 6; st == 6L || st == 7L -> 8; st == 8L || st == 9L -> 0
                // En Long: un tipo enorme no debe desbordar a una longitud negativa al pasar a Int.
                st >= 12 -> ((st - 12) / 2).let { l -> if (l < 0 || l > MAX_RECORD) fail("valor de longitud no válida ($l) en ${t.name}") else l.toInt() }
                else -> fail("tipo de valor reservado ($st) en ${t.name}")
            }
            if (body + len > rec.size) fail("valor fuera del registro en ${t.name}")
            out[i] = when {
                st == 0L -> null
                st in 1L..6L -> int(rec, body, len)
                st == 7L -> java.lang.Double.longBitsToDouble(int(rec, body, 8))
                st == 8L -> 0L
                st == 9L -> 1L
                st % 2 == 0L -> rec.copyOfRange(body, body + len)
                else -> text(rec, body, len)
            }
            body += len
        }
        cols.forEachIndexed { i, c -> if (c.rowidAlias && i < out.size) out[i] = rowid }
        return out
    }

    private fun text(b: ByteArray, at: Int, len: Int): String =
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(b, at, len)).toString()

    companion object {
        /** Registro más grande que se acepta (las filas de ICdetector miden centenares de bytes; una muestra forense, unos KiB). */
        const val MAX_RECORD = 16L * 1024 * 1024

        private fun u16(b: ByteArray, at: Int) = ((b[at].toInt() and 0xFF) shl 8) or (b[at + 1].toInt() and 0xFF)
        private fun u32(b: ByteArray, at: Int): Long =
            ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

        /** Entero con signo big-endian de [len] bytes. */
        private fun int(b: ByteArray, at: Int, len: Int): Long {
            var v = b[at].toLong()   // con signo
            for (i in 1 until len) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
            return v
        }

        /** Varint de SQLite (1–9 bytes): valor y número de bytes. */
        fun varint(b: ByteArray, at: Int): Pair<Long, Int> {
            var v = 0L
            for (i in 0 until 9) {
                if (at + i >= b.size) throw SqliteFormatException("varint truncado")
                val x = b[at + i].toLong() and 0xFF
                if (i == 8) return ((v shl 8) or x) to 9
                v = (v shl 7) or (x and 0x7F)
                if (x and 0x80 == 0L) return v to (i + 1)
            }
            return v to 9
        }
    }
}

/** Lectura de `CREATE TABLE` (incluidas las columnas que SQLite añade al texto con `ALTER TABLE … ADD COLUMN`). */
object SqliteSchema {
    private val CONSTRAINT = Regex("^(CONSTRAINT|PRIMARY\\s+KEY|UNIQUE|CHECK|FOREIGN\\s+KEY)\\b", RegexOption.IGNORE_CASE)

    fun columns(sql: String): List<SqliteFile.Column> {
        val open = sql.indexOf('(')
        val close = sql.lastIndexOf(')')
        if (open < 0 || close <= open) throw SqliteFormatException("CREATE TABLE sin lista de columnas")
        return split(sql.substring(open + 1, close)).map { it.trim() }.filter { it.isNotEmpty() && !CONSTRAINT.containsMatchIn(it) }.map { column(it) }
    }

    /** Separa por comas que no estén dentro de paréntesis ni de comillas. */
    private fun split(s: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder()
        var depth = 0; var quote: Char? = null
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (quote != null) {
                cur.append(c)
                if (c == quote) { if (i + 1 < s.length && s[i + 1] == quote) { cur.append(s[++i]) } else quote = null }
            } else when (c) {
                '\'', '"', '`' -> { quote = c; cur.append(c) }
                '[' -> { quote = ']'; cur.append(c) }
                '(' -> { depth++; cur.append(c) }
                ')' -> { depth--; cur.append(c) }
                ',' -> if (depth == 0) { out += cur.toString(); cur.setLength(0) } else cur.append(c)
                else -> cur.append(c)
            }
            i++
        }
        if (cur.isNotBlank()) out += cur.toString()
        return out
    }

    private fun column(def: String): SqliteFile.Column {
        val (name, rest) = name(def)
        val upper = rest.uppercase()
        val typeEnd = Regex("\\b(CONSTRAINT|PRIMARY|NOT|NULL|UNIQUE|CHECK|DEFAULT|COLLATE|REFERENCES|GENERATED|AS)\\b").find(upper)?.range?.first ?: rest.length
        val type = rest.substring(0, typeEnd).trim()
        val alias = type.equals("INTEGER", ignoreCase = true) && Regex("\\bPRIMARY\\s+KEY\\b(?!\\s+DESC)").containsMatchIn(upper)
        val default = Regex("\\bDEFAULT\\s+(\\(.*\\)|'(?:[^']|'')*'|[+-]?[0-9.]+(?:[eE][+-]?\\d+)?|NULL|TRUE|FALSE|\\w+)", RegexOption.IGNORE_CASE).find(rest)?.groupValues?.get(1)
        return SqliteFile.Column(name, type, alias, default?.let { affinity(type, literal(it)) })
    }

    private fun name(def: String): Pair<String, String> {
        val d = def.trim()
        val q = d.firstOrNull()
        val endChar = when (q) { '"' -> '"'; '`' -> '`'; '[' -> ']'; '\'' -> '\''; else -> null }
        if (endChar != null) {
            val e = d.indexOf(endChar, 1)
            if (e < 0) throw SqliteFormatException("identificador sin cerrar: ${d.take(40)}")
            return d.substring(1, e) to d.substring(e + 1)
        }
        val e = d.indexOfFirst { it.isWhitespace() }.let { if (it < 0) d.length else it }
        return d.substring(0, e) to d.substring(e)
    }

    private fun literal(t: String): Any? = when {
        t.equals("NULL", true) -> null
        t.equals("TRUE", true) -> 1L
        t.equals("FALSE", true) -> 0L
        t.startsWith("'") -> t.substring(1, t.length - 1).replace("''", "'")
        t.startsWith("(") -> t.substring(1, t.length - 1).trim().let { literal(it) }
        else -> t.toLongOrNull() ?: t.toDoubleOrNull() ?: t
    }

    /** Afinidad de columna (https://www.sqlite.org/datatype3.html §3.1) aplicada a un valor por defecto. */
    fun affinity(type: String, v: Any?): Any? {
        if (v == null) return null
        val t = type.uppercase()
        return when {
            "INT" in t -> numeric(v, integerOnly = true)
            "CHAR" in t || "CLOB" in t || "TEXT" in t -> when (v) { is Double -> java.lang.Double.toString(v); else -> v.toString() }
            t.isEmpty() || "BLOB" in t -> v
            "REAL" in t || "FLOA" in t || "DOUB" in t -> when (val n = numeric(v, integerOnly = false)) { is Long -> n.toDouble(); else -> n }
            else -> numeric(v, integerOnly = true)
        }
    }

    private fun numeric(v: Any, integerOnly: Boolean): Any = when (v) {
        is Long -> v
        is Double -> if (integerOnly && v == Math.floor(v) && Math.abs(v) < 9.2e18) v.toLong() else v
        is String -> v.trim().toLongOrNull() ?: v.trim().toDoubleOrNull()?.let { numeric(it, integerOnly) } ?: v
        else -> v
    }
}
