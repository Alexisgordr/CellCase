package com.alexisgordr.cellcase.core.model

/**
 * Cabecera de un CSV compartida por todas sus filas: nombres en orden y posición de cada nombre (si un nombre se
 * repite, cuenta su última aparición, como antes con un mapa por fila).
 */
class HeaderIndex(names: List<String>) {
    val index: Map<String, Int> = LinkedHashMap<String, Int>().also { m -> names.forEachIndexed { i, n -> m[n] = i } }
}

/**
 * Texto original de una fila (D-041, memoria): la cabecera se comparte entre todas las filas y los valores se
 * guardan en un array; los valores repetidos se comparten con [StringPool]. Se comporta como un `Map` de solo
 * lectura por nombre de columna, con el mismo contenido que el mapa por fila que sustituye.
 */
class RowValues(private val header: HeaderIndex, private val cells: Array<String>) : AbstractMap<String, String>() {
    override val size: Int get() = header.index.size
    override fun containsKey(key: String): Boolean = key in header.index
    override fun get(key: String): String? = header.index[key]?.let { cells[it] }
    override val keys: Set<String> get() = header.index.keys
    override val entries: Set<Map.Entry<String, String>>
        get() = header.index.entries.mapTo(LinkedHashSet(size * 2)) { (k, i) -> java.util.AbstractMap.SimpleImmutableEntry(k, cells[i]) }
}

/**
 * Comparte las cadenas repetidas de un archivo («LTE», «OK», «001», valores de dBm…). Las columnas de valores casi
 * únicos (hora, coordenadas) no pasan por aquí. Tiene un tope para no crecer sin límite con datos atípicos.
 */
class StringPool(private val max: Int = 20_000) {
    private val map = HashMap<String, String>()
    fun of(s: String): String = map[s] ?: if (map.size < max) { map[s] = s; s } else s
}
