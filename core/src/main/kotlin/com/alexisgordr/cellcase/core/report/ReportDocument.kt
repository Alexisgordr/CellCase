package com.alexisgordr.cellcase.core.report

/** Documento de informe independiente del formato de salida. Lo construyen las plantillas. */
data class ReportDocument(
    val title: String,
    /** Texto de cabecera de página. */
    val runningHeader: String,
    /** Texto de pie de página (a la izquierda; el número de página va a la derecha). */
    val runningFooter: String,
    /** Marca visible en todas las páginas (origen sintético, mixto o no determinado), o null. */
    val banner: String?,
    /** Formato de «Página X de Y» con marcadores {page} y {total}. */
    val pageLabel: String,
    val blocks: List<Block>,
    /** Formato de enteros del idioma del informe (ejes de gráficos). */
    val intFormat: (Long) -> String = { it.toString() },
    /** Prefijos de numeración automática («Tabla», «Figura»). */
    val tableLabel: String = "Tabla",
    val figureLabel: String = "Figura",
    /** Sufijo del título de una tabla que sigue en otra página. */
    val continuedLabel: String = "(continuación)",
    /** Título del aviso de la portada (datos sintéticos, mixtos…). */
    val noticeLabel: String = "Aviso",
    /** Etiqueta BCP 47 del idioma del informe (se registra en el PDF, D-044). */
    val languageTag: String = "es-ES"
)

enum class TextStyle { BODY, SMALL, MONO, EMPHASIS }

enum class Align { LEFT, RIGHT }

/** Columna de tabla. [noWrap]: el valor no se parte en líneas (se recorta con «…» si no cabe). */
data class Column(val header: String, val weight: Float, val align: Align = Align.LEFT, val mono: Boolean = false, val noWrap: Boolean = false)

enum class CalloutKind { NOTE, WARNING, SYNTHETIC, ANALYST }

/** Marca de eje temporal: posición en milisegundos epoch y su etiqueta ya formateada. */
data class TimeTick(val x: Long, val label: String)

/** Intervalo resaltado en un gráfico temporal (p. ej. un episodio). */
data class Band(val start: Long, val end: Long, val label: String)

sealed interface Block {
    data class Cover(
        val kicker: String,
        val title: String,
        val subtitle: String,
        val fields: List<Pair<String, String>>,
        val notice: String?,
        /** Cabecera compacta en la primera página (informe resumido) en lugar de portada completa. */
        val compact: Boolean = false
    ) : Block

    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String, val style: TextStyle = TextStyle.BODY) : Block
    data class Bullets(val items: List<String>, val style: TextStyle = TextStyle.BODY) : Block
    /** Subapartado con etiqueta en negrita y viñetas (p. ej. «Hechos observados»). */
    data class Labeled(val label: String, val items: List<String>) : Block
    data class KeyValues(val rows: List<Pair<String, String>>) : Block
    /** [caption] sin número: el maquetador añade «Tabla N.». */
    data class Table(val columns: List<Column>, val rows: List<List<String>>, val caption: String?) : Block
    data class BarChart(
        val title: String,
        val categories: List<String>,
        val values: List<Int>,
        val yAxisLabel: String,
        val caption: String
    ) : Block

    /**
     * Cronología por carriles: cada marca es una señal en su carril; las bandas son episodios.
     * Solo se dibujan marcas en las horas de los datos: no hay interpolación.
     */
    data class Timeline(
        val title: String,
        val lanes: List<String>,
        val from: Long,
        val to: Long,
        val ticks: List<TimeTick>,
        val marks: List<Mark>,
        val bands: List<Band>,
        val axisLabel: String,
        val caption: String
    ) : Block {
        /** [label]: texto corto sobre la marca (p. ej. «C2»); [color]: índice de la paleta (por celda); si es null, el del carril. */
        data class Mark(val lane: Int, val start: Long, val end: Long, val strong: Boolean, val label: String? = null, val color: Int? = null)
    }

    /** Puntos medidos (sin líneas que los unan) frente al tiempo, por serie. */
    data class PointChart(
        val title: String,
        val series: List<Series>,
        val from: Long,
        val to: Long,
        val ticks: List<TimeTick>,
        val yLabel: String,
        val bands: List<Band>,
        val caption: String,
        /** Líneas horizontales de referencia (p. ej. mediana histórica), discontinuas y etiquetadas. */
        val refLines: List<RefLine> = emptyList(),
        /** Altura de la zona de trazado en puntos. */
        val height: Float = 150f
    ) : Block {
        /** [ring]: puntos dibujados como anillo (filas con señal de ICdetector). [color]: índice de paleta fijo. */
        data class Series(val name: String, val points: List<Pair<Long, Double>>, val color: Int? = null, val ring: Boolean = false)
        data class RefLine(val value: Double, val label: String, val color: Int? = null)
    }

    /** Grafo dirigido dibujado en círculo. Solo aristas presentes en los datos. */
    data class Graph(
        val title: String,
        val nodes: List<Node>,
        val edges: List<Edge>,
        val caption: String,
        val legend: List<String>
    ) : Block {
        data class Node(val label: String, val emphasis: Boolean)
        data class Edge(val from: Int, val to: Int, val weight: Int, val dashed: Boolean)
    }

    data class Callout(val kind: CalloutKind, val title: String, val text: String) : Block
    /** Salto de página incondicional (solo si la página actual ya tiene contenido). */
    data object PageBreak : Block
    /** Salto de página solo si queda menos de [minRemaining] (fracción de la zona útil) libre. */
    data class SoftBreak(val minRemaining: Float) : Block
    data class Spacer(val points: Float) : Block
}
