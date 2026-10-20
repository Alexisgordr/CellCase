package com.alexisgordr.cellcase.core.pdf

import com.alexisgordr.cellcase.core.report.Align
import com.alexisgordr.cellcase.core.report.Block
import com.alexisgordr.cellcase.core.report.CalloutKind
import com.alexisgordr.cellcase.core.report.Column
import com.alexisgordr.cellcase.core.report.ReportDocument
import com.alexisgordr.cellcase.core.report.TextStyle
import java.io.OutputStream
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Maqueta un [ReportDocument] en páginas A4 y lo escribe con [PdfWriter].
 *
 * Reglas de maquetación:
 * - Un título nunca queda solo al final de una página: se reserva sitio para el comienzo del bloque siguiente.
 * - Las tablas se parten entre páginas repitiendo la cabecera; una tabla corta no se parte, y no se deja
 *   una página casi vacía por moverla entera.
 * - Tablas y figuras se numeran automáticamente y de forma consecutiva.
 * - Los gráficos no se parten.
 * - Cabecera, pie y «Página X de Y» se dibujan al final, conocido el total de páginas.
 */
class ReportRenderer(private val theme: Theme = Theme()) {

    data class Theme(
        val marginLeft: Float = 54f,
        val marginRight: Float = 54f,
        val marginTop: Float = 66f,
        val marginBottom: Float = 62f,
        val bodySize: Float = 10f,
        val smallSize: Float = 8.5f,
        val tableSize: Float = 8.5f,
        val accent: Rgb = Rgb.hex(0x1F3A5F),
        val muted: Rgb = Rgb.hex(0x56606B),
        val rule: Rgb = Rgb.hex(0xC9CED6),
        val tableHeader: Rgb = Rgb.hex(0xE4E9F0),
        val zebra: Rgb = Rgb.hex(0xF6F7F9),
        val warning: Rgb = Rgb.hex(0xA14A00),
        val warningBg: Rgb = Rgb.hex(0xFFF4E5),
        val banner: Rgb = Rgb.hex(0xB93C0C),
        val noteBg: Rgb = Rgb.hex(0xEEF3F9),
        val analystBg: Rgb = Rgb.hex(0xF3EEF9),
        val analyst: Rgb = Rgb.hex(0x5B3A8C),
        val bandFill: Rgb = Rgb.hex(0xFBE7D3),
        val series: List<Rgb> = listOf(Rgb.hex(0x2F6690), Rgb.hex(0xC0392B), Rgb.hex(0xD68910), Rgb.hex(0x117A65), Rgb.hex(0x6C3483), Rgb.hex(0x7F8C8D), Rgb.hex(0x1ABC9C), Rgb.hex(0x8E5B3A))
    )

    private sealed interface Cmd {
        data class Text(val x: Float, val y: Float, val font: PdfFont, val size: Float, val text: String, val color: Rgb) : Cmd
        data class Fill(val x: Float, val y: Float, val w: Float, val h: Float, val color: Rgb) : Cmd
        data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val color: Rgb, val lw: Float, val dashed: Boolean = false) : Cmd
        data class Circle(val cx: Float, val cy: Float, val r: Float, val fill: Rgb?, val stroke: Rgb?) : Cmd
        data class Poly(val pts: List<Pair<Float, Float>>, val color: Rgb) : Cmd
    }

    private class Page(val isCover: Boolean) { val cmds = ArrayList<Cmd>() }

    private val pageW = PdfWriter.A4_W
    private val pageH = PdfWriter.A4_H
    private val left get() = theme.marginLeft
    private val contentW get() = pageW - theme.marginLeft - theme.marginRight
    private val bottom get() = pageH - theme.marginBottom
    private val usableH get() = bottom - theme.marginTop

    private val pages = ArrayList<Page>()
    private var y = 0f
    private lateinit var cur: Page
    private var tableNo = 0
    private var figureNo = 0
    private lateinit var doc: ReportDocument

    var pageCount = 0
        private set

    fun render(document: ReportDocument, info: PdfInfo, out: OutputStream) {
        doc = document
        pages.clear(); tableNo = 0; figureNo = 0
        layout()
        val w = PdfWriter(info)
        val total = pages.size
        pages.forEachIndexed { i, p ->
            val pg = w.newPage(pageW, pageH)
            for (c in p.cmds) when (c) {
                is Cmd.Text -> pg.text(c.x, c.y, c.font, c.size, c.text, c.color)
                is Cmd.Fill -> pg.fillRect(c.x, c.y, c.w, c.h, c.color)
                is Cmd.Line -> if (c.dashed) pg.dashedLine(c.x1, c.y1, c.x2, c.y2, c.color, c.lw) else pg.line(c.x1, c.y1, c.x2, c.y2, c.color, c.lw)
                is Cmd.Circle -> pg.circle(c.cx, c.cy, c.r, c.fill, c.stroke)
                is Cmd.Poly -> pg.fillPolygon(c.pts, c.color)
            }
            decorate(pg, i + 1, total, p.isCover)
        }
        pageCount = total
        w.write(out)
    }

    private fun decorate(pg: PdfPage, n: Int, total: Int, cover: Boolean) {
        val label = doc.pageLabel.replace("{page}", n.toString()).replace("{total}", total.toString())
        val footY = pageH - 40f
        pg.line(left, footY - 6f, pageW - theme.marginRight, footY - 6f, theme.rule, 0.5f)
        pg.text(left, footY, PdfFont.REGULAR, 7.5f, fitText(doc.runningFooter, PdfFont.REGULAR, 7.5f, contentW - 80f), theme.muted)
        pg.text(pageW - theme.marginRight - PdfFont.REGULAR.width(label, 7.5f), footY, PdfFont.REGULAR, 7.5f, label, theme.muted)
        val banner = doc.banner
        if (banner != null) {
            val bw = PdfFont.BOLD.width(banner, 7.5f) + 12f
            pg.fillRect(pageW - theme.marginRight - bw, 26f, bw, 14f, theme.banner)
            pg.text(pageW - theme.marginRight - bw + 6f, 29.5f, PdfFont.BOLD, 7.5f, banner, Rgb.WHITE)
        }
        if (!cover) {
            val maxW = contentW - (if (banner != null) PdfFont.BOLD.width(banner, 7.5f) + 24f else 0f)
            pg.text(left, 29f, PdfFont.REGULAR, 7.5f, fitText(doc.runningHeader, PdfFont.REGULAR, 7.5f, maxW), theme.muted)
            pg.line(left, 46f, pageW - theme.marginRight, 46f, theme.rule, 0.5f)
        }
    }

    // ───────────────────────── maquetación ─────────────────────────

    private fun newPage(cover: Boolean = false) { cur = Page(cover); pages += cur; y = theme.marginTop }
    private fun atTop() = y <= theme.marginTop + 1f
    private fun ensure(h: Float) { if (y + h > bottom && !atTop()) newPage() }
    private fun remaining() = bottom - y
    private fun add(c: Cmd) { cur.cmds += c }

    private fun layout() {
        val blocks = doc.blocks
        newPage(cover = (blocks.firstOrNull() as? Block.Cover)?.compact == false)
        for ((i, b) in blocks.withIndex()) {
            when (b) {
                is Block.Cover -> cover(b)
                is Block.Heading -> {
                    var need = headingHeight(b)
                    var j = i + 1
                    while (j < blocks.size && blocks[j] is Block.Heading) { need += headingHeight(blocks[j] as Block.Heading); j++ }
                    if (j < blocks.size) need += openingWithTrail(blocks, j)
                    if (!atTop() && y + need > bottom) newPage()
                    heading(b)
                }
                is Block.Paragraph -> paragraph(b.text, b.style, tail = trail(blocks, i))
                is Block.Bullets -> bullets(b.items, b.style, tail = trail(blocks, i))
                // Un apartado etiquetado sin elementos no se dibuja: un título sin contenido no aporta nada.
                is Block.Labeled -> if (b.items.isNotEmpty()) labeled(b, trail(blocks, i))
                is Block.KeyValues -> keyValues(b, trail(blocks, i))
                is Block.BarChart, is Block.Timeline, is Block.PointChart, is Block.Graph, is Block.Table -> {
                    // La nota breve que sigue a una figura o tabla (celdas servidoras, referencias) va en su misma página.
                    val tail = trail(blocks, i)
                    if (tail > 0f && b !is Block.Table) { val need = minHeight(b) + tail; if (!atTop() && need > remaining() && need <= usableH) newPage() }
                    if (tail > 0f && b is Block.Table) { val full = tableHeight(b.columns, b.rows, b.caption); if (!atTop() && full <= usableH * 0.5f && full + tail > remaining()) newPage() }
                    when (b) {
                        is Block.Table -> table(b.columns, b.rows, b.caption)
                        is Block.BarChart -> barChart(b)
                        is Block.Timeline -> timeline(b)
                        is Block.PointChart -> pointChart(b)
                        else -> graph(b as Block.Graph)
                    }
                }
                is Block.Callout -> callout(b)
                is Block.PageBreak -> if (!atTop()) newPage()
                is Block.SoftBreak -> if (!atTop() && remaining() < usableH * b.minRemaining) newPage()
                is Block.Spacer -> y += b.points
            }
        }
    }

    // ───────────────────── control de saltos de página ─────────────────────
    //
    // Reglas (solo deciden DÓNDE empieza cada página; no cambian el texto ni el tamaño de letra):
    //  · un título nunca queda al final de una página: va con el comienzo de su contenido (minHeight);
    //  · una lista, un bloque etiquetado o una tabla clave-valor cortos (≤ 40 % de la página) no se parten;
    //  · si se parten, quedan al menos dos elementos a cada lado: ni la primera viñeta sola al pie ni la
    //    última sola al comienzo de la página siguiente;
    //  · un párrafo largo deja al menos dos líneas a cada lado del salto;
    //  · lo que «cierra» un bloque (la nota breve «Detalle técnico…», el último grupo corto de una serie de
    //    bloques etiquetados, o un párrafo breve que termina el documento) va con el último elemento anterior.

    private val KEEP_WHOLE get() = usableH * 0.4f
    /**
     * Las tablas clave-valor (las observaciones del informe explicado) se mueven enteras solo si son cortas: una de
     * hasta un 40 % de página dejaba media página en blanco. Si son mayores se parten con al menos dos filas a cada lado.
     */
    private val KEEP_WHOLE_KV get() = usableH * 0.25f

    /** Altura de lo que debe ir pegado al final del bloque i (ver reglas). */
    private fun trail(blocks: List<Block>, i: Int): Float {
        val next = blocks.getOrNull(i + 1) ?: return 0f
        val isLast = i + 1 == blocks.lastIndex
        return when (next) {
            is Block.Paragraph -> {
                val n = wrap(next.text, fontOf(next.style), sizeOf(next.style), contentW).size
                if ((next.style == TextStyle.SMALL && n <= 3) || (isLast && n <= 3)) n * sizeOf(next.style) * 1.4f + sizeOf(next.style) * 0.6f else 0f
            }
            is Block.Labeled -> if (next.items.isEmpty()) trail(blocks, i + 1) else {
                val after = blocks.getOrNull(i + 2)
                val h = labeledHeight(next)
                if (after !is Block.Labeled && h <= usableH * 0.2f) h + trail(blocks, i + 1) else 0f
            }
            is Block.Bullets -> if (isLast) bulletsHeight(next.items, next.style, 0f).let { if (it <= usableH * 0.2f) it else 0f } else 0f
            else -> 0f
        }
    }

    private fun itemHeights(items: List<String>, style: TextStyle, indent: Float): List<Float> {
        val lead = sizeOf(style) * 1.4f
        return items.map { wrap(it, fontOf(style), sizeOf(style), contentW - indent - 12f).size * lead + 1.5f }
    }
    private fun bulletsHeight(items: List<String>, style: TextStyle, indent: Float) = itemHeights(items, style, indent).sum() + sizeOf(style) * 0.4f
    private fun labeledHeight(b: Block.Labeled) = 13.5f + bulletsHeight(b.items, TextStyle.BODY, 4f)

    /** Altura mínima que debe caber para empezar una lista: entera si es corta; si no, sus dos primeros elementos. */
    private fun bulletsOpening(items: List<String>, style: TextStyle, indent: Float): Float {
        val hs = itemHeights(items, style, indent)
        val total = hs.sum() + sizeOf(style) * 0.4f
        return if (total <= KEEP_WHOLE) total else hs.take(2).sumOf { min(it, sizeOf(style) * 1.4f * 4 + 1.5f).toDouble() }.toFloat()
    }

    private fun kvHeights(rows: List<Pair<String, String>>): Pair<Float, List<Float>> {
        val size = 9.5f; val lead = 13f
        val labelW = min(200f, rows.maxOfOrNull { PdfFont.BOLD.width(it.first, size) }?.plus(14f) ?: 120f)
        return labelW to rows.map { (k, v) -> max(wrap(v, PdfFont.REGULAR, size, contentW - labelW).size, wrap(k, PdfFont.BOLD, size, labelW - 8f).size) * lead + 2f }
    }
    private fun kvOpening(rows: List<Pair<String, String>>): Float {
        val hs = kvHeights(rows).second
        val total = hs.sum() + 6f
        return if (total <= KEEP_WHOLE_KV) total else hs.take(2).sum()
    }

    /**
     * Decide si el elemento k de una serie (viñetas o filas) debe empezar en una página nueva para que no quede
     * un elemento solo a ningún lado del salto. hs: alturas de los elementos; tail: lo que va pegado al final.
     */
    private fun breakBeforeItem(k: Int, hs: List<Float>, tail: Float, splittable: (Int) -> Boolean): Boolean {
        if (atTop()) return false
        val n = hs.size
        var need = if (splittable(k)) min(hs[k], 2 * 13.3f + 1.5f) else hs[k]
        if (n >= 2 && k == 0) need = hs[0] + if (splittable(1)) min(hs[1], 2 * 13.3f + 1.5f) else hs[1]   // la primera no queda sola al pie
        if (k == n - 2) need = hs[k] + hs[k + 1] + tail                                                    // la última no queda sola arriba
        if (k == n - 1) need = hs[k] + tail
        return need > remaining() && need <= usableH * 0.6f
    }

    /**
     * Lo que un título necesita debajo: el comienzo del bloque siguiente o, si ese bloque es corto y se moverá
     * entero junto con su cierre (ver trail), todo ello; así el título nunca se queda solo al pie.
     */
    private fun openingWithTrail(blocks: List<Block>, j: Int): Float {
        val b = blocks[j]
        val whole = when (b) {
            is Block.Labeled -> if (b.items.isEmpty()) return 0f else labeledHeight(b)
            is Block.Bullets -> bulletsHeight(b.items, b.style, 0f)
            is Block.KeyValues -> (kvHeights(b.rows).second.sum() + 6f).let { if (it + trail(blocks, j) <= KEEP_WHOLE_KV) it else return minHeight(b) }
            is Block.Paragraph -> wrap(b.text, fontOf(b.style), sizeOf(b.style), contentW).size * sizeOf(b.style) * 1.4f
            // Misma regla que al dibujar la tabla: si es corta y la sigue una nota, ambas van juntas.
            is Block.Table -> {
                val full = tableHeight(b.columns, b.rows, b.caption); val tail = trail(blocks, j)
                return if (tail > 0f && full <= usableH * 0.5f) full + tail else minHeight(b)
            }
            else -> return minHeight(b)
        }
        val tail = trail(blocks, j)
        return if (whole + tail <= KEEP_WHOLE) whole + tail else minHeight(b)
    }

    /** Altura mínima del comienzo de un bloque, para no dejar un título huérfano. */
    // Tabla: la previsión se hace antes de dibujar el título (hasta ~40 pt); se descuenta para aplicar la misma regla que [table].
    private fun minHeight(b: Block): Float = when (b) {
        is Block.Paragraph -> wrap(b.text, fontOf(b.style), sizeOf(b.style), contentW).size.let { n -> sizeOf(b.style) * 1.4f * (if (n <= 4) n else 2) }
        is Block.Bullets -> bulletsOpening(b.items, b.style, 0f)
        is Block.Labeled -> 13.5f + bulletsOpening(b.items, TextStyle.BODY, 4f)
        is Block.KeyValues -> kvOpening(b.rows)
        is Block.Table -> {
            val full = tableHeight(b.columns, b.rows, b.caption)
            if (full <= usableH * 0.3f || (full <= usableH && full > remaining() - 40f && remaining() - 40f < usableH * 0.35f)) full else tableOpening(b)
        }
        is Block.BarChart -> 230f
        is Block.Timeline -> timelineHeight(b)
        is Block.PointChart -> b.height + 100f
        is Block.Graph -> graphHeight(b)
        is Block.Callout -> min(120f, 40f + wrap(b.text, PdfFont.REGULAR, 9f, contentW - 24f).size * 12.4f)
        else -> 0f
    }

    private fun fontOf(style: TextStyle) = when (style) { TextStyle.MONO -> PdfFont.MONO; TextStyle.EMPHASIS -> PdfFont.BOLD; else -> PdfFont.REGULAR }
    private fun sizeOf(style: TextStyle) = when (style) { TextStyle.SMALL -> theme.smallSize; TextStyle.MONO -> 8.5f; else -> theme.bodySize }

    private fun cover(b: Block.Cover) {
        if (b.compact) { compactCover(b); return }
        y = 140f
        add(Cmd.Text(left, y, PdfFont.BOLD, 10f, b.kicker.uppercase(), theme.accent)); y += 22f
        for (line in wrap(b.title, PdfFont.BOLD, 24f, contentW)) { add(Cmd.Text(left, y, PdfFont.BOLD, 24f, line, Rgb.BLACK)); y += 30f }
        y += 4f
        for (line in wrap(b.subtitle, PdfFont.REGULAR, 12f, contentW)) { add(Cmd.Text(left, y, PdfFont.REGULAR, 12f, line, theme.muted)); y += 16f }
        y += 18f
        add(Cmd.Line(left, y, left + contentW, y, theme.accent, 1.2f)); y += 16f
        val labelW = 170f
        for ((k, v) in b.fields) {
            val lines = wrap(v, PdfFont.REGULAR, 10f, contentW - labelW)
            add(Cmd.Text(left, y, PdfFont.BOLD, 10f, k, theme.muted))
            lines.forEach { add(Cmd.Text(left + labelW, y, PdfFont.REGULAR, 10f, it, Rgb.BLACK)); y += 14f }
            y += 3f
        }
        if (b.notice != null) { y += 14f; callout(Block.Callout(CalloutKind.SYNTHETIC, doc.noticeLabel, b.notice)) }
        newPage()
    }

    /** Cabecera del informe resumido: título y datos clave en el inicio de la primera página. */
    private fun compactCover(b: Block.Cover) {
        add(Cmd.Text(left, y, PdfFont.BOLD, 8.5f, b.kicker.uppercase(), theme.accent)); y += 14f
        for (line in wrap(b.title, PdfFont.BOLD, 18f, contentW)) { add(Cmd.Text(left, y, PdfFont.BOLD, 18f, line, Rgb.BLACK)); y += 22f }
        for (line in wrap(b.subtitle, PdfFont.REGULAR, 9.5f, contentW)) { add(Cmd.Text(left, y, PdfFont.REGULAR, 9.5f, line, theme.muted)); y += 12.5f }
        y += 6f
        add(Cmd.Line(left, y, left + contentW, y, theme.accent, 0.8f)); y += 8f
        keyValues(Block.KeyValues(b.fields))
        if (b.notice != null) callout(Block.Callout(CalloutKind.SYNTHETIC, doc.noticeLabel, b.notice))
    }

    private fun headingHeight(b: Block.Heading) = when (b.level) { 1 -> 40f; 2 -> 28f; else -> 20f }

    private fun heading(b: Block.Heading) {
        val (font, size, color) = when (b.level) {
            1 -> Triple(PdfFont.BOLD, 16f, theme.accent)
            2 -> Triple(PdfFont.BOLD, 12.5f, Rgb.BLACK)
            else -> Triple(PdfFont.BOLD, 10.5f, theme.accent)
        }
        if (!atTop()) y += when (b.level) { 1 -> 10f; 2 -> 6f; else -> 3f }
        val lines = wrap(b.text, font, size, contentW)
        for (l in lines) { add(Cmd.Text(left, y, font, size, l, color)); y += size * 1.25f }
        if (b.level == 1) { add(Cmd.Line(left, y + 1f, left + contentW, y + 1f, theme.accent, 0.8f)); y += 10f } else y += 4f
    }

    private fun paragraph(text: String, style: TextStyle, indent: Float = 0f, color: Rgb = Rgb.BLACK, tail: Float = 0f) {
        val font = fontOf(style); val size = sizeOf(style); val lead = size * 1.4f
        val lines = wrap(text, font, size, contentW - indent)
        val whole = lines.size * lead
        // Hasta 4 líneas (o con lo que va pegado detrás) el párrafo no se parte.
        if (!atTop() && whole + tail > remaining() && (lines.size <= 4 || whole + tail <= KEEP_WHOLE)) newPage()
        // Párrafo largo partido: al menos dos líneas a cada lado del salto.
        var breakAt = -1
        if (!atTop() && whole > remaining()) {
            val fit = floor(remaining() / lead).toInt()
            breakAt = when { fit < 2 -> 0; lines.size - fit < 2 -> lines.size - 2; else -> fit }
        }
        lines.forEachIndexed { k, l ->
            if (k == breakAt) { if (!atTop()) newPage() } else ensure(lead)
            add(Cmd.Text(left + indent, y, font, size, l, color)); y += lead
        }
        y += size * 0.6f
    }

    private fun bullets(items: List<String>, style: TextStyle, indent: Float = 0f, tail: Float = 0f) {
        val font = fontOf(style); val size = sizeOf(style); val lead = size * 1.4f
        val hs = itemHeights(items, style, indent)
        val total = hs.sum() + size * 0.4f
        if (!atTop() && total + tail > remaining() && total + tail <= KEEP_WHOLE) newPage()
        for ((i, item) in items.withIndex()) {
            val lines = wrap(item, font, size, contentW - indent - 12f)
            if (breakBeforeItem(i, hs, tail) { lines.size > 4 }) newPage()
            lines.forEachIndexed { k, l ->
                ensure(lead)
                if (k == 0) add(Cmd.Text(left + indent + 2f, y, PdfFont.REGULAR, size, "•", theme.muted))
                add(Cmd.Text(left + indent + 12f, y, font, size, l, Rgb.BLACK)); y += lead
            }
            y += 1.5f
        }
        y += size * 0.4f
    }

    private fun labeled(b: Block.Labeled, tail: Float = 0f) {
        val whole = labeledHeight(b)
        if (!atTop() && whole + tail > remaining() && whole + tail <= KEEP_WHOLE) newPage()
        else ensure(13.5f + bulletsOpening(b.items, TextStyle.BODY, 4f))
        add(Cmd.Text(left, y, PdfFont.BOLD, 9.5f, b.label, theme.accent)); y += 13.5f
        bullets(b.items, TextStyle.BODY, indent = 4f, tail = tail)
    }

    private fun keyValues(b: Block.KeyValues, tail: Float = 0f) {
        val size = 9.5f; val lead = 13f
        val (labelW, hs) = kvHeights(b.rows)
        val total = hs.sum() + 6f
        if (!atTop() && total + tail > remaining() && total + tail <= KEEP_WHOLE_KV) newPage()
        for ((i, row) in b.rows.withIndex()) {
            val (k, v) = row
            val lines = wrap(v, PdfFont.REGULAR, size, contentW - labelW)
            val kl = wrap(k, PdfFont.BOLD, size, labelW - 8f)
            val h = max(lines.size, kl.size) * lead
            if (breakBeforeItem(i, hs, tail) { false }) newPage() else ensure(h)
            kl.forEachIndexed { j, l -> add(Cmd.Text(left, y + j * lead, PdfFont.BOLD, size, l, theme.muted)) }
            lines.forEachIndexed { j, l -> add(Cmd.Text(left + labelW, y + j * lead, PdfFont.REGULAR, size, l, Rgb.BLACK)) }
            y += h + 2f
        }
        y += 6f
    }

    private fun callout(b: Block.Callout) {
        val (bg, accent) = when (b.kind) {
            CalloutKind.NOTE -> theme.noteBg to theme.accent
            CalloutKind.WARNING -> theme.warningBg to theme.warning
            CalloutKind.SYNTHETIC -> theme.warningBg to theme.banner
            CalloutKind.ANALYST -> theme.analystBg to theme.analyst
        }
        val pad = 8f; val size = 9.5f; val lead = 13f
        val lines = wrap(b.text, PdfFont.REGULAR, size, contentW - 2 * pad - 4f)
        var idx = 0; var first = true
        while (idx < lines.size || first) {
            val titleH = if (first) 14f else 0f
            val avail = remaining() - 2 * pad - titleH
            if (avail < lead * 2 && !atTop()) { newPage(); continue }
            val fit = min(lines.size - idx, max(1, floor(avail / lead).toInt()))
            val h = 2 * pad + titleH + fit * lead
            add(Cmd.Fill(left, y, contentW, h, bg)); add(Cmd.Fill(left, y, 3f, h, accent))
            var yy = y + pad
            if (first) { add(Cmd.Text(left + pad + 4f, yy, PdfFont.BOLD, size, b.title, accent)); yy += titleH }
            for (k in 0 until fit) { add(Cmd.Text(left + pad + 4f, yy, PdfFont.REGULAR, size, lines[idx + k], Rgb.BLACK)); yy += lead }
            idx += fit; y += h + 8f; first = false
            if (idx < lines.size) newPage()
        }
    }

    // ───────────────────────── tablas ─────────────────────────

    /**
     * Anchos de columna: proporcionales al peso, pero nunca menores que la palabra más larga de la cabecera
     * (para no partir «Gravedad» en «Graveda/d») ni que el contenido de las columnas sin ajuste de línea.
     */
    private fun widths(columns: List<Column>, rows: List<List<String>> = emptyList()): List<Float> {
        val total = columns.sumOf { it.weight.toDouble() }.toFloat()
        val size = theme.tableSize
        val mins = columns.mapIndexed { i, c ->
            val head = c.header.split(' ').maxOfOrNull { PdfFont.BOLD.width(it, size) } ?: 0f
            val body = if (c.noWrap) rows.maxOfOrNull { (if (c.mono) PdfFont.MONO else PdfFont.REGULAR).width(it.getOrElse(i) { "" }, size) } ?: 0f else 0f
            (max(head, body) + 6.5f).coerceAtMost(contentW * 0.4f)
        }
        var ws = columns.map { contentW * it.weight / total }
        repeat(4) {
            val fixed = ws.indices.filter { ws[it] < mins[it] }.toSet()
            if (fixed.isEmpty()) return ws
            val fixedW = fixed.sumOf { mins[it].toDouble() }.toFloat()
            val freeWeight = ws.indices.filter { it !in fixed }.sumOf { ws[it].toDouble() }.toFloat()
            if (freeWeight <= 0f) return ws
            val scale = (contentW - fixedW) / freeWeight
            ws = ws.mapIndexed { i, w -> if (i in fixed) mins[i] else w * scale }
        }
        return ws
    }

    private fun cellLines(c: Column, text: String, w: Float): List<String> {
        val f = if (c.mono) PdfFont.MONO else PdfFont.REGULAR
        val size = theme.tableSize
        return if (c.noWrap) listOf(fitText(text, f, size, w - 6f)) else wrap(text, f, size, w - 6f)
    }

    private fun tableHeight(columns: List<Column>, rows: List<List<String>>, caption: String?): Float {
        val ws = widths(columns, rows); val lead = theme.tableSize * 1.3f
        val head = columns.mapIndexed { i, c -> wrap(c.header, PdfFont.BOLD, theme.tableSize, ws[i] - 6f).size }.max() * lead + 6f
        val body = rows.sumOf { r -> (columns.mapIndexed { i, c -> cellLines(c, r.getOrElse(i) { "" }, ws[i]).size }.maxOrNull() ?: 1) * lead.toDouble() + 6f }.toFloat()
        return (if (caption != null) 14f else 0f) + head + body + 10f
    }

    /**
     * Comienzo mínimo de una tabla que se parte: pie de tabla, cabecera y las dos primeras filas, la misma regla que
     * aplica [table]. Así un título no queda al pie de página cuando las filas son altas.
     */
    private fun tableOpening(b: Block.Table): Float {
        val ws = widths(b.columns, b.rows); val lead = theme.tableSize * 1.3f
        val head = b.columns.mapIndexed { i, c -> wrap(c.header, PdfFont.BOLD, theme.tableSize, ws[i] - 6f).size }.max() * lead + 6f
        val firstTwo = b.rows.take(2).sumOf { r -> (b.columns.mapIndexed { i, c -> cellLines(c, r.getOrElse(i) { "" }, ws[i]).size }.maxOrNull() ?: 1).coerceAtLeast(1) * lead.toDouble() + 6f }.toFloat()
        val cap = b.caption?.let { wrap("${doc.tableLabel} 00. $it", PdfFont.BOLD, 9f, contentW).size * 12.5f + 1.5f } ?: 0f
        return min(cap + head + firstTwo, usableH * 0.6f)
    }

    private fun table(columns: List<Column>, rows: List<List<String>>, caption: String?) {
        val size = theme.tableSize; val pad = 3f; val lead = size * 1.3f
        val ws = widths(columns, rows)
        val headerLines = columns.mapIndexed { i, c -> wrap(c.header, PdfFont.BOLD, size, ws[i] - 2 * pad) }
        val headerH = headerLines.maxOf { it.size } * lead + 2 * pad
        val cells = rows.map { r -> columns.mapIndexed { i, c -> cellLines(c, r.getOrElse(i) { "" }, ws[i]) } }
        val rowH = cells.map { cs -> (cs.maxOfOrNull { it.size } ?: 1).coerceAtLeast(1) * lead + 2 * pad }
        val captionH = if (caption != null) 14f else 0f
        val full = captionH + headerH + rowH.sum()
        // Tabla corta: entera en una página. Tabla larga: se parte, pero con al menos dos filas aquí.
        if (!atTop() && full > remaining()) {
            val firstTwo = captionH + headerH + rowH.take(2).sum()
            if (full <= usableH && remaining() < usableH * 0.35f) newPage()
            else if (firstTwo > remaining()) newPage()
        }
        var captionText: String? = null
        if (caption != null) { tableNo++; captionText = "${doc.tableLabel} $tableNo. $caption" }

        fun drawHeader(cont: Boolean) {
            add(Cmd.Fill(left, y, contentW, headerH, theme.tableHeader))
            var x = left
            columns.forEachIndexed { i, c ->
                headerLines[i].forEachIndexed { k, l ->
                    val tx = if (c.align == Align.RIGHT) x + ws[i] - pad - PdfFont.BOLD.width(l, size) else x + pad
                    add(Cmd.Text(tx, y + pad + k * lead, PdfFont.BOLD, size, l, Rgb.BLACK))
                }
                x += ws[i]
            }
            y += headerH
        }
        if (captionText != null) {
            for (l in wrap(captionText, PdfFont.BOLD, 9f, contentW)) { add(Cmd.Text(left, y, PdfFont.BOLD, 9f, l, theme.muted)); y += 12.5f }
            y += 1.5f
        }
        drawHeader(false)
        var rowsOnPage = 0
        rows.indices.forEach { r ->
            val cs = cells[r]
            // Evita que la continuación lleve una sola fila: si las dos últimas no caben, pasan juntas.
            if (r == rows.size - 2 && rowsOnPage >= 3 && rowH[r] + rowH[r + 1] > remaining() && rowH[r] + rowH[r + 1] <= usableH - headerH - 14f) {
                continuePage(captionText); drawHeader(true); rowsOnPage = 0
            }
            val maxLines = cs.maxOf { it.size }.coerceAtLeast(1)
            var start = 0
            while (start < maxLines) {
                val avail = remaining() - 2 * pad
                if (avail < lead) { continuePage(captionText); drawHeader(true); rowsOnPage = 0; continue }
                val fit = min(maxLines - start, floor(avail / lead).toInt())
                if (fit < maxLines - start && start == 0 && maxLines * lead + 2 * pad <= usableH - headerH - 14f) {
                    continuePage(captionText); drawHeader(true); rowsOnPage = 0; continue
                }
                val h = fit * lead + 2 * pad
                if (r % 2 == 1) add(Cmd.Fill(left, y, contentW, h, theme.zebra))
                var x = left
                columns.forEachIndexed { i, c ->
                    val f = if (c.mono) PdfFont.MONO else PdfFont.REGULAR
                    for (k in start until min(start + fit, cs[i].size)) {
                        val l = cs[i][k]
                        val tx = if (c.align == Align.RIGHT) x + ws[i] - pad - f.width(l, size) else x + pad
                        add(Cmd.Text(tx, y + pad + (k - start) * lead, f, size, l, Rgb.BLACK))
                    }
                    x += ws[i]
                }
                add(Cmd.Line(left, y + h, left + contentW, y + h, theme.rule, 0.3f))
                y += h; start += fit
                if (start < maxLines) { continuePage(captionText); drawHeader(true) }
            }
            rowsOnPage++
        }
        y += 10f
    }

    /** Nueva página al continuar una tabla: se repite el título con «(continuación)». */
    private fun continuePage(caption: String?) {
        newPage()
        if (caption != null) {
            val t = fitText("$caption ${doc.continuedLabel}", PdfFont.BOLD, 9f, contentW)
            add(Cmd.Text(left, y, PdfFont.BOLD, 9f, t, theme.muted)); y += 14f
        }
    }

    // ───────────────────────── gráficos ─────────────────────────

    private fun figureTitle(title: String): String { figureNo++; return "${doc.figureLabel} $figureNo. $title" }

    /** Espacio extra si el título de la figura y la nota de la derecha no caben en la misma línea. */
    private fun headerExtra(title: String, note: String): Float {
        val t = "${doc.figureLabel} ${figureNo + 1}. $title"
        val n = fitText(note, PdfFont.REGULAR, 7.5f, contentW / 2)
        return if (PdfFont.BOLD.width(t, 9.5f) + PdfFont.REGULAR.width(n, 7.5f) + 12f > contentW) 11f else 0f
    }

    /** Título de figura a la izquierda y nota (eje, hora local) a la derecha; la nota baja una línea si no cabe. */
    private fun figureHeader(title: String, note: String) {
        val extra = headerExtra(title, note)
        add(Cmd.Text(left, y, PdfFont.BOLD, 9.5f, figureTitle(title), Rgb.BLACK))
        val n = fitText(note, PdfFont.REGULAR, 7.5f, contentW / 2)
        add(Cmd.Text(left + contentW - PdfFont.REGULAR.width(n, 7.5f), y + 1.5f + extra, PdfFont.REGULAR, 7.5f, n, theme.muted))
        y += extra
    }

    private fun captionBlock(caption: String) {
        for (l in wrap(caption, PdfFont.REGULAR, 8.5f, contentW)) { add(Cmd.Text(left, y, PdfFont.REGULAR, 8.5f, l, theme.muted)); y += 11.5f }
        y += 8f
    }

    private fun barChart(b: Block.BarChart) {
        val plotH = 150f
        val captionLines = wrap(b.caption, PdfFont.REGULAR, 8.5f, contentW).size
        ensure(18f + 10f + plotH + 22f + captionLines * 11.5f + 10f + headerExtra(b.title, b.yAxisLabel))
        figureHeader(b.title, b.yAxisLabel)
        y += 28f
        val maxV = max(1, b.values.maxOrNull() ?: 1)
        val step = niceStep(maxV / 4.0)
        val top = ceil(maxV / step) * step
        val axisX = left + 40f; val plotW = contentW - 48f; val y0 = y + plotH
        var t = 0.0
        while (t <= top + 1e-9) {
            val yy = (y0 - (t / top) * plotH).toFloat()
            add(Cmd.Line(axisX, yy, axisX + plotW, yy, theme.rule, 0.3f))
            val lbl = doc.intFormat(t.toLong())
            add(Cmd.Text(axisX - 4f - PdfFont.REGULAR.width(lbl, 7.5f), yy - 3.5f, PdfFont.REGULAR, 7.5f, lbl, theme.muted))
            t += step
        }
        add(Cmd.Line(axisX, y, axisX, y0, theme.muted, 0.6f)); add(Cmd.Line(axisX, y0, axisX + plotW, y0, theme.muted, 0.6f))
        val n = b.values.size
        if (n > 0) {
            val slot = plotW / n; val bw = min(36f, slot * 0.7f); val every = max(1, ceil(n / 12.0).toInt())
            b.values.forEachIndexed { i, v ->
                val h = (v / top * plotH).toFloat(); val x = axisX + i * slot + (slot - bw) / 2
                add(Cmd.Fill(x, y0 - h, bw, h, theme.series[0]))
                if (n <= 16) { val s = doc.intFormat(v.toLong()); add(Cmd.Text(x + bw / 2 - PdfFont.REGULAR.width(s, 7.5f) / 2, y0 - h - 10f, PdfFont.REGULAR, 7.5f, s, Rgb.BLACK)) }
                if (i % every == 0) {
                    val c = fitText(b.categories[i], PdfFont.REGULAR, 7.5f, slot * every - 2f)
                    add(Cmd.Text(x + bw / 2 - PdfFont.REGULAR.width(c, 7.5f) / 2, y0 + 4f, PdfFont.REGULAR, 7.5f, c, theme.muted))
                }
            }
        }
        y = y0 + 22f
        captionBlock(b.caption)
    }

    private fun xOf(t: Long, from: Long, to: Long, x0: Float, w: Float) = x0 + ((t - from).toDouble() / max(1L, to - from) * w).toFloat()

    private fun timeAxis(ticks: List<com.alexisgordr.cellcase.core.report.TimeTick>, from: Long, to: Long, x0: Float, w: Float, yTop: Float, yBottom: Float) {
        for (tk in ticks) {
            if (tk.x < from || tk.x > to) continue
            val x = xOf(tk.x, from, to, x0, w)
            add(Cmd.Line(x, yTop, x, yBottom, theme.rule, 0.3f))
            val lw = PdfFont.REGULAR.width(tk.label, 7f)
            add(Cmd.Text(min(max(x - lw / 2, x0 - 10f), x0 + w - lw + 10f), yBottom + 3f, PdfFont.REGULAR, 7f, tk.label, theme.muted))
        }
    }

    /** Separación entre el título y las pistas; más si hay etiquetas de episodio encima (para que no pisen el título). */
    private fun timelineTop(b: Block.Timeline) = if (b.bands.any { it.label.isNotEmpty() }) 30f else 20f
    private fun timelineHeight(b: Block.Timeline) = 18f + 14f + (timelineTop(b) - 20f) + b.lanes.size * 20f + 30f + wrap(b.caption, PdfFont.REGULAR, 8.5f, contentW).size * 11.5f + 10f

    private fun timeline(b: Block.Timeline) {
        ensure(timelineHeight(b) + headerExtra(b.title, b.axisLabel))
        figureHeader(b.title, b.axisLabel)
        y += timelineTop(b)
        val labelW = 112f; val x0 = left + labelW; val w = contentW - labelW - 6f
        val laneH = 20f; val top = y; val h = b.lanes.size * laneH
        // bandas de episodios
        for (band in b.bands) {
            val xa = xOf(band.start, b.from, b.to, x0, w); val xb = max(xOf(band.end, b.from, b.to, x0, w), xa + 2.5f)
            add(Cmd.Fill(xa - 1f, top - 2f, xb - xa + 2f, h + 4f, theme.bandFill))
            val lw = PdfFont.BOLD.width(band.label, 7f)
            add(Cmd.Text((xa + xb) / 2 - lw / 2, top - 11f, PdfFont.BOLD, 7f, band.label, theme.warning))
        }
        b.lanes.forEachIndexed { i, lane ->
            val ly = top + i * laneH
            add(Cmd.Text(left, ly + 5f, PdfFont.REGULAR, 8f, fitText(lane, PdfFont.REGULAR, 8f, labelW - 6f), Rgb.BLACK))
            add(Cmd.Line(x0, ly + laneH / 2, x0 + w, ly + laneH / 2, theme.rule, 0.4f))
        }
        timeAxis(b.ticks, b.from, b.to, x0, w, top, top + h)
        val lastLabelEnd = HashMap<Int, Float>()
        for (m in b.marks.sortedBy { it.start }) {
            val ly = top + m.lane * laneH + laneH / 2
            val xa = xOf(m.start, b.from, b.to, x0, w); val xb = xOf(m.end, b.from, b.to, x0, w)
            val color = theme.series[(m.color ?: m.lane) % theme.series.size]
            if (xb - xa >= 2.5f) add(Cmd.Fill(xa, ly - 4f, xb - xa, 8f, color))
            else add(Cmd.Fill(xa - 1f, ly - (if (m.strong) 6f else 4f), 2f, if (m.strong) 12f else 8f, color))
            if (m.strong) add(Cmd.Circle(xa, ly - 8.5f, 1.6f, null, Rgb.BLACK))
            m.label?.let { lbl ->
                val lw = PdfFont.REGULAR.width(lbl, 6.5f); val lx = xa + 2.5f
                if (lx > (lastLabelEnd[m.lane] ?: -1f) + 4f) { add(Cmd.Text(lx, ly + 1.5f, PdfFont.REGULAR, 6.5f, lbl, theme.muted)); lastLabelEnd[m.lane] = lx + lw }
            }
        }
        y = top + h + 18f
        captionBlock(b.caption)
    }

    private fun pointChart(b: Block.PointChart) {
        val plotH = b.height
        val legendRows = ceil(b.series.size / 3.0).toInt()
        ensure(20f + plotH + 22f + legendRows * 12f + wrap(b.caption, PdfFont.REGULAR, 8.5f, contentW).size * 11.5f + 12f + headerExtra(b.title, b.yLabel))
        figureHeader(b.title, b.yLabel)
        y += 22f
        val values = b.series.flatMap { s -> s.points.map { it.second } } + b.refLines.map { it.value }
        val vmin = floor((values.minOrNull() ?: -120.0) / 10) * 10; val vmax = ceil((values.maxOrNull() ?: -60.0) / 10) * 10
        val lo = if (vmax - vmin < 20) vmin - 10 else vmin; val hi = if (vmax - vmin < 20) vmax + 10 else vmax
        val x0 = left + 36f; val w = contentW - 44f; val top = y; val y0 = y + plotH
        fun yOf(v: Double) = (y0 - (v - lo) / (hi - lo) * plotH).toFloat()
        for (band in b.bands) {
            val xa = xOf(band.start, b.from, b.to, x0, w); val xb = max(xOf(band.end, b.from, b.to, x0, w), xa + 2.5f)
            add(Cmd.Fill(xa, top, xb - xa, plotH, theme.bandFill))
        }
        val step = niceStep((hi - lo) / 4)
        var v = ceil(lo / step) * step
        while (v <= hi + 1e-9) {
            val yy = yOf(v); add(Cmd.Line(x0, yy, x0 + w, yy, theme.rule, 0.3f))
            val lbl = doc.intFormat(v.toLong())
            add(Cmd.Text(x0 - 4f - PdfFont.REGULAR.width(lbl, 7.5f), yy - 3.5f, PdfFont.REGULAR, 7.5f, lbl, theme.muted))
            v += step
        }
        add(Cmd.Line(x0, top, x0, y0, theme.muted, 0.6f)); add(Cmd.Line(x0, y0, x0 + w, y0, theme.muted, 0.6f))
        timeAxis(b.ticks, b.from, b.to, x0, w, y0, y0)
        for (r in b.refLines) {
            val yy = yOf(r.value); val c = r.color?.let { theme.series[it % theme.series.size] } ?: theme.muted
            add(Cmd.Line(x0, yy, x0 + w, yy, c, 0.8f, dashed = true))
            val lw = PdfFont.REGULAR.width(r.label, 6.8f)
            add(Cmd.Fill(x0 + w - lw - 4f, yy - 9.5f, lw + 3f, 8.5f, Rgb.WHITE))
            add(Cmd.Text(x0 + w - lw - 2.5f, yy - 8.5f, PdfFont.REGULAR, 6.8f, r.label, c))
        }
        b.series.forEachIndexed { i, s ->
            val c = theme.series[(s.color ?: i) % theme.series.size]
            for ((t, value) in s.points) {
                if (s.ring) add(Cmd.Circle(xOf(t, b.from, b.to, x0, w), yOf(value), 3.6f, null, Rgb.BLACK))
                else add(Cmd.Circle(xOf(t, b.from, b.to, x0, w), yOf(value), 2.2f, c, null))
            }
        }
        y = y0 + 18f
        // leyenda
        val colW = contentW / 3
        b.series.forEachIndexed { i, s ->
            val lx = left + (i % 3) * colW; val ly = y + (i / 3) * 12f
            if (s.ring) add(Cmd.Circle(lx + 4f, ly + 4f, 3.4f, null, Rgb.BLACK))
            else add(Cmd.Circle(lx + 4f, ly + 4f, 3f, theme.series[(s.color ?: i) % theme.series.size], null))
            add(Cmd.Text(lx + 11f, ly, PdfFont.REGULAR, 7.5f, fitText(s.name, PdfFont.REGULAR, 7.5f, colW - 14f), Rgb.BLACK))
        }
        y += legendRows * 12f + 6f
        captionBlock(b.caption)
    }

    private fun graphHeight(b: Block.Graph) = 20f + 260f + b.legend.size * 11.5f + wrap(b.caption, PdfFont.REGULAR, 8.5f, contentW).size * 11.5f + 14f

    private fun graph(b: Block.Graph) {
        ensure(graphHeight(b))
        add(Cmd.Text(left, y, PdfFont.BOLD, 9.5f, figureTitle(b.title), Rgb.BLACK)); y += 18f
        val h = 250f; val cx = left + contentW / 2; val cy = y + h / 2; val radius = h / 2 - 22f
        val n = b.nodes.size
        val pos = (0 until n).map { i ->
            val a = -PI / 2 + 2 * PI * i / max(1, n)
            (cx + radius * cos(a)).toFloat() to (cy + radius * sin(a)).toFloat()
        }
        val maxW = max(1, b.edges.maxOfOrNull { it.weight } ?: 1)
        val nodeR = 10f
        val pairs = b.edges.map { it.from to it.to }.toSet()
        for (e in b.edges) {
            if (e.from !in 0 until n || e.to !in 0 until n || e.from == e.to) continue
            val (x1, y1) = pos[e.from]; val (x2, y2) = pos[e.to]
            val ang = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
            // Desplazamiento lateral cuando existen ambas direcciones, para no superponerlas.
            val off = if ((e.to to e.from) in pairs) 3.5f else 0f
            val ox = (-sin(ang) * off).toFloat(); val oy = (cos(ang) * off).toFloat()
            val sx = x1 + (cos(ang) * nodeR).toFloat() + ox; val sy = y1 + (sin(ang) * nodeR).toFloat() + oy
            val ex = x2 - (cos(ang) * (nodeR + 1f)).toFloat() + ox; val ey = y2 - (sin(ang) * (nodeR + 1f)).toFloat() + oy
            val lw = (0.5 + 2.5 * ln(1.0 + e.weight) / ln(1.0 + maxW)).toFloat()
            val color = if (e.dashed) theme.warning else theme.muted
            add(Cmd.Line(sx, sy, ex, ey, color, lw, e.dashed))
            val ah = 6f; val aw = 3.2f
            val bx = ex - (cos(ang) * ah).toFloat(); val by = ey - (sin(ang) * ah).toFloat()
            add(Cmd.Poly(listOf(ex to ey, bx + (-sin(ang) * aw).toFloat() to by + (cos(ang) * aw).toFloat(),
                bx - (-sin(ang) * aw).toFloat() to by - (cos(ang) * aw).toFloat()), color))
        }
        b.nodes.forEachIndexed { i, node ->
            val (x, yy) = pos[i]
            add(Cmd.Circle(x, yy, nodeR, if (node.emphasis) theme.bandFill else Rgb.WHITE, if (node.emphasis) theme.warning else theme.accent))
            val lw = PdfFont.BOLD.width(node.label, 7.5f)
            add(Cmd.Text(x - lw / 2, yy - 3.8f, PdfFont.BOLD, 7.5f, node.label, Rgb.BLACK))
        }
        y += h + 8f
        for (l in b.legend) { add(Cmd.Text(left, y, PdfFont.REGULAR, 8f, fitText(l, PdfFont.REGULAR, 8f, contentW), Rgb.BLACK)); y += 11.5f }
        captionBlock(b.caption)
    }

    private fun niceStep(raw: Double): Double {
        if (raw <= 1) return 1.0
        val mag = 10.0.pow(floor(log10(raw)))
        val f = raw / mag
        return (when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 5 -> 5.0; else -> 10.0 }) * mag
    }

    /** Recorta con «…» para que quepa en [w]. */
    private fun fitText(s: String, font: PdfFont, size: Float, w: Float): String {
        if (font.width(s, size) <= w) return s
        var t = s
        while (t.isNotEmpty() && font.width("$t…", size) > w) t = t.dropLast(1)
        return "$t…"
    }

    @Suppress("unused")
    private fun dist(a: Pair<Float, Float>, b: Pair<Float, Float>) = hypot(a.first - b.first, a.second - b.second)

    companion object {
        /** Ajuste de línea por palabras; las palabras más largas que el ancho se parten. */
        fun wrap(text: String, font: PdfFont, size: Float, width: Float): List<String> {
            val out = ArrayList<String>()
            for (para in text.replace("\r", "").split('\n').map { WinAnsi.normalize(it) }) {
                if (para.isEmpty()) { out += ""; continue }
                var line = StringBuilder()
                for (word in para.split(' ')) {
                    val candidate = if (line.isEmpty()) word else "$line $word"
                    if (font.width(candidate, size) <= width) { line = StringBuilder(candidate); continue }
                    if (line.isNotEmpty()) { out += line.toString(); line = StringBuilder() }
                    // Palabra más larga que el ancho: se parte en una sola pasada, sumando el ancho carácter a carácter
                    // (antes se recalculaba el ancho de cada prefijo: cuadrático con una nota de miles de caracteres).
                    // Mismo resultado: cada trozo es el prefijo más largo que cabe, con al menos un carácter.
                    var chunk = StringBuilder(); var units = 0
                    for (ch in word) {
                        val u = font.units(ch)
                        if (chunk.isNotEmpty() && (units + u) * size / 1000f > width) { out += chunk.toString(); chunk = StringBuilder(); units = 0 }
                        chunk.append(ch); units += u
                    }
                    line.append(chunk)
                }
                out += line.toString()
            }
            return if (out.isEmpty()) listOf("") else out
        }
    }
}
