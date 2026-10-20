package com.alexisgordr.cellcase.core.pdf

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.Deflater

/** Fuentes estándar PDF (no se incrustan; todo lector PDF las proporciona). */
enum class PdfFont(val baseName: String, val resourceName: String) {
    REGULAR("Helvetica", "F1"),
    BOLD("Helvetica-Bold", "F2"),
    MONO("Courier", "F3");

    /** Ancho en puntos de [text] a [size] pt. El texto debe estar ya en WinAnsi (ver [WinAnsi.encode]). */
    fun width(text: String, size: Float): Float {
        var units = 0
        for (ch in WinAnsi.normalize(text)) units += units(ch)
        return units * size / 1000f
    }

    /** Unidades (milésimas de em) de un carácter ya normalizado a WinAnsi. */
    fun units(ch: Char): Int {
        val code = WinAnsi.code(ch) ?: '?'.code
        return when (this) {
            MONO -> 600
            REGULAR -> StandardFontMetrics.HELVETICA.getOrElse(code - 32) { 556 }
            BOLD -> StandardFontMetrics.HELVETICA_BOLD.getOrElse(code - 32) { 556 }
        }
    }
}

/**
 * Codificación WinAnsi (Windows-1252) para las fuentes estándar. Cubre el español completo.
 * Los caracteres que no existen en WinAnsi se sustituyen por equivalentes ASCII documentados
 * (D-024) y se cuentan para poder advertirlo.
 */
object WinAnsi {
    private val HIGH = mapOf(
        '€' to 0x80, '‚' to 0x82, 'ƒ' to 0x83, '„' to 0x84, '…' to 0x85, '†' to 0x86, '‡' to 0x87,
        'ˆ' to 0x88, '‰' to 0x89, 'Š' to 0x8A, '‹' to 0x8B, 'Œ' to 0x8C, 'Ž' to 0x8E, '‘' to 0x91,
        '’' to 0x92, '“' to 0x93, '”' to 0x94, '•' to 0x95, '–' to 0x96, '—' to 0x97, '˜' to 0x98,
        '™' to 0x99, 'š' to 0x9A, '›' to 0x9B, 'œ' to 0x9C, 'ž' to 0x9E, 'Ÿ' to 0x9F
    )
    val SUBSTITUTIONS = mapOf(
        '→' to "->", '←' to "<-", '↔' to "<->", '≥' to ">=", '≤' to "<=", '≠' to "!=", '≈' to "~", '−' to "-",
        '✓' to "OK", '✗' to "X", '⚠' to "(!)", '\t' to "    ", ' ' to " ", ' ' to " "
    )

    fun code(ch: Char): Int? = when {
        ch.code in 0x20..0x7E -> ch.code
        ch.code in 0xA0..0xFF -> ch.code
        else -> HIGH[ch]
    }

    /** Sustituye lo que no se puede representar. Devuelve el texto representable. */
    fun normalize(text: String): String {
        if (text.all { code(it) != null }) return text
        val sb = StringBuilder()
        for (ch in text) {
            when {
                code(ch) != null -> sb.append(ch)
                SUBSTITUTIONS.containsKey(ch) -> sb.append(SUBSTITUTIONS.getValue(ch))
                ch == '\n' || ch == '\r' -> sb.append(' ')
                else -> sb.append('?')
            }
        }
        return sb.toString()
    }

    fun encode(text: String): ByteArray {
        val n = normalize(text)
        return ByteArray(n.length) { (code(n[it]) ?: '?'.code).toByte() }
    }
}

data class Rgb(val r: Float, val g: Float, val b: Float) {
    companion object {
        val BLACK = Rgb(0f, 0f, 0f)
        val WHITE = Rgb(1f, 1f, 1f)
        fun hex(h: Int) = Rgb(((h shr 16) and 0xFF) / 255f, ((h shr 8) and 0xFF) / 255f, (h and 0xFF) / 255f)
    }
}

/**
 * Lienzo de una página con origen arriba a la izquierda y unidades en puntos (1/72 pulgada).
 * Genera los operadores de contenido PDF.
 */
class PdfPage(val width: Float, val height: Float) {
    private val ops = StringBuilder()
    private val bytes = ByteArrayOutputStream()

    private fun flushOps() {
        if (ops.isNotEmpty()) { bytes.write(ops.toString().toByteArray(Charsets.US_ASCII)); ops.setLength(0) }
    }

    private fun f(v: Float): String {
        val r = Math.round(v * 100f) / 100f
        return if (r == r.toLong().toFloat()) r.toLong().toString() else r.toString()
    }

    fun text(x: Float, yTop: Float, font: PdfFont, size: Float, text: String, color: Rgb = Rgb.BLACK) {
        val baseline = height - yTop - size * 0.8f
        ops.append("BT /${font.resourceName} ${f(size)} Tf ${f(color.r)} ${f(color.g)} ${f(color.b)} rg ${f(x)} ${f(baseline)} Td (")
        flushOps()
        for (b in WinAnsi.encode(text)) {
            val c = b.toInt() and 0xFF
            when (c) {
                '('.code, ')'.code, '\\'.code -> { bytes.write('\\'.code); bytes.write(c) }
                else -> bytes.write(c)
            }
        }
        ops.append(") Tj ET\n")
    }

    fun fillRect(x: Float, yTop: Float, w: Float, h: Float, color: Rgb) {
        ops.append("${f(color.r)} ${f(color.g)} ${f(color.b)} rg ${f(x)} ${f(height - yTop - h)} ${f(w)} ${f(h)} re f\n")
    }

    fun strokeRect(x: Float, yTop: Float, w: Float, h: Float, color: Rgb, lineWidth: Float = 0.5f) {
        ops.append("${f(lineWidth)} w ${f(color.r)} ${f(color.g)} ${f(color.b)} RG ${f(x)} ${f(height - yTop - h)} ${f(w)} ${f(h)} re S\n")
    }

    fun line(x1: Float, y1Top: Float, x2: Float, y2Top: Float, color: Rgb, lineWidth: Float = 0.5f) {
        ops.append("${f(lineWidth)} w ${f(color.r)} ${f(color.g)} ${f(color.b)} RG ${f(x1)} ${f(height - y1Top)} m ${f(x2)} ${f(height - y2Top)} l S\n")
    }

    fun dashedLine(x1: Float, y1Top: Float, x2: Float, y2Top: Float, color: Rgb, lineWidth: Float = 0.5f, dash: Float = 3f) {
        ops.append("[${f(dash)} ${f(dash)}] 0 d ")
        line(x1, y1Top, x2, y2Top, color, lineWidth)
        ops.append("[] 0 d\n")
    }

    /** Círculo aproximado con cuatro curvas de Bézier. */
    fun circle(cx: Float, cyTop: Float, r: Float, fill: Rgb?, stroke: Rgb?, lineWidth: Float = 0.6f) {
        val cy = height - cyTop
        val k = 0.5523f * r
        ops.append("${f(cx + r)} ${f(cy)} m ")
        ops.append("${f(cx + r)} ${f(cy + k)} ${f(cx + k)} ${f(cy + r)} ${f(cx)} ${f(cy + r)} c ")
        ops.append("${f(cx - k)} ${f(cy + r)} ${f(cx - r)} ${f(cy + k)} ${f(cx - r)} ${f(cy)} c ")
        ops.append("${f(cx - r)} ${f(cy - k)} ${f(cx - k)} ${f(cy - r)} ${f(cx)} ${f(cy - r)} c ")
        ops.append("${f(cx + k)} ${f(cy - r)} ${f(cx + r)} ${f(cy - k)} ${f(cx + r)} ${f(cy)} c ")
        if (fill != null) ops.append("${f(fill.r)} ${f(fill.g)} ${f(fill.b)} rg ")
        if (stroke != null) ops.append("${f(lineWidth)} w ${f(stroke.r)} ${f(stroke.g)} ${f(stroke.b)} RG ")
        ops.append(if (fill != null && stroke != null) "B\n" else if (fill != null) "f\n" else "S\n")
    }

    /** Polígono relleno (p. ej. punta de flecha). Puntos con origen arriba a la izquierda. */
    fun fillPolygon(points: List<Pair<Float, Float>>, color: Rgb) {
        if (points.size < 3) return
        ops.append("${f(color.r)} ${f(color.g)} ${f(color.b)} rg ")
        points.forEachIndexed { i, (x, y) -> ops.append("${f(x)} ${f(height - y)} ${if (i == 0) "m" else "l"} ") }
        ops.append("h f\n")
    }

    internal fun content(): ByteArray { flushOps(); return bytes.toByteArray() }
}

data class PdfInfo(val title: String, val subject: String, val producer: String, val creationDate: Instant, val language: String? = null)

/** Escritor PDF 1.4 mínimo: páginas, fuentes estándar, compresión Flate y metadatos. */
class PdfWriter(private val info: PdfInfo) {
    val pages = ArrayList<PdfPage>()

    fun newPage(width: Float = A4_W, height: Float = A4_H): PdfPage = PdfPage(width, height).also { pages += it }

    fun write(out: OutputStream) {
        val buf = CountingOutput(out)
        val offsets = ArrayList<Long>()
        fun obj(body: ByteArray) { offsets += buf.count; buf.write("${offsets.size} 0 obj\n".toByteArray()); buf.write(body); buf.write("\nendobj\n".toByteArray()) }
        fun obj(body: String) = obj(body.toByteArray(Charsets.ISO_8859_1))

        buf.write("%PDF-1.4\n%".toByteArray()); buf.write(byteArrayOf(0xE2.toByte(), 0xE3.toByte(), 0xCF.toByte(), 0xD3.toByte())); buf.write("\n".toByteArray())
        // 1 catálogo, 2 páginas, 3-5 fuentes, 6 info, luego pares (página, contenido)
        val n = pages.size
        val firstPageObj = 7
        obj("<< /Type /Catalog /Pages 2 0 R /PageMode /UseNone" + (info.language?.filter { it.isLetterOrDigit() || it == '-' }?.let { " /Lang ($it)" } ?: "") + " >>")
        obj("<< /Type /Pages /Count $n /Kids [${(0 until n).joinToString(" ") { "${firstPageObj + it * 2} 0 R" }}] >>")
        for (font in PdfFont.values()) obj("<< /Type /Font /Subtype /Type1 /BaseFont /${font.baseName} /Encoding /WinAnsiEncoding >>")
        val date = "D:" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC).format(info.creationDate) + "Z"
        obj("<< /Title ${pdfText(info.title)} /Subject ${pdfText(info.subject)} /Producer ${pdfText(info.producer)} /CreationDate ($date) >>")
        val fonts = PdfFont.values().withIndex().joinToString(" ") { (i, ft) -> "/${ft.resourceName} ${3 + i} 0 R" }
        pages.forEachIndexed { i, p ->
            val contentObj = firstPageObj + i * 2 + 1
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${p.width} ${p.height}] /Resources << /Font << $fonts >> >> /Contents $contentObj 0 R >>")
            val raw = p.content()
            val z = deflate(raw)
            val head = "<< /Length ${z.size} /Filter /FlateDecode >>\nstream\n".toByteArray()
            val body = ByteArrayOutputStream().apply { write(head); write(z); write("\nendstream".toByteArray()) }.toByteArray()
            obj(body)
        }
        val xref = buf.count
        val sb = StringBuilder("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        offsets.forEach { sb.append(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", it)) }
        sb.append("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R /Info 6 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.write(sb.toString().toByteArray(Charsets.US_ASCII))
        buf.flush()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data); d.finish()
        val out = ByteArrayOutputStream()
        val b = ByteArray(8192)
        while (!d.finished()) { val k = d.deflate(b); out.write(b, 0, k) }
        d.end()
        return out.toByteArray()
    }

    /** Cadena de metadatos en UTF-16BE con BOM (admite cualquier carácter). */
    private fun pdfText(s: String): String {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + s.toByteArray(Charsets.UTF_16BE)
        return "<" + bytes.joinToString("") { "%02X".format(it) } + ">"
    }

    private class CountingOutput(private val out: OutputStream) {
        var count = 0L
        fun write(b: ByteArray) { out.write(b); count += b.size }
        fun flush() = out.flush()
    }

    companion object {
        const val A4_W = 595.28f
        const val A4_H = 841.89f
    }
}
