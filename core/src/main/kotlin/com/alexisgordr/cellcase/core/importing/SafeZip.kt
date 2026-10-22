package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Límites para leer ZIP importados (fase 3: tamaños, número de entradas, descompresión, rutas). */
/** Límites razonados en docs/limits.md: el mayor ZIP real conocido descomprime a < 1 MiB. */
data class ZipLimits(
    val maxEntries: Int = 64,
    val maxEntryBytes: Long = 6L * 1024 * 1024,
    val maxTotalBytes: Long = 8L * 1024 * 1024,
    /** Relación máxima descomprimido/comprimido del archivo completo (defensa contra bombas). */
    val maxRatio: Long = 200
)

/**
 * Lectura defensiva de ZIP. No extrae nada a disco: devuelve el contenido en memoria, de modo que
 * una ruta maliciosa nunca puede escapar de una carpeta. Aun así, las rutas sospechosas se rechazan
 * porque ICdetector solo escribe entradas planas en la raíz (export-formats.md §0.3).
 * Los tamaños se cuentan con los bytes realmente descomprimidos, no con lo que declara la cabecera.
 */
object SafeZip {
    fun validateEntryName(name: String): Msg? = when {
        name.isEmpty() -> msg("import.validateEntryName.nombre_vacio")
        name.startsWith("/") || name.startsWith("\\") -> msg("import.validateEntryName.ruta_absoluta")
        name.contains('\\') -> msg("import.validateEntryName.separador_de_windows")
        name.length >= 2 && name[1] == ':' -> msg("import.validateEntryName.unidad_de_disco")
        name.split('/').any { it == ".." } -> msg("import.validateEntryName.ruta_que_sale_de_la")
        name.endsWith("/") -> msg("import.validateEntryName.directorio")
        name.contains('/') -> msg("import.validateEntryName.entrada_en_subcarpeta_icdetector_no")
        name.any { it.code < 0x20 } -> msg("import.validateEntryName.caracteres_de_control")
        else -> null
    }

    /**
     * Lee todas las entradas de un ZIP completo en memoria.
     * Lanza [ImportRejected] ante cualquier violación de límites o rutas, o si el archivo está truncado.
     *
     * Además de leer secuencialmente, comprueba el directorio central: debe existir y listar
     * exactamente las mismas entradas. Así se detectan archivos truncados (que la lectura secuencial
     * puede devolver a medias sin error) y discrepancias entre cabeceras locales y directorio central.
     */
    fun readAll(
        zip: ByteArray,
        limits: ZipLimits = ZipLimits(),
        checkpoint: Checkpoint = Checkpoint.NONE
    ): LinkedHashMap<String, ByteArray> {
        val central = centralDirectoryNames(zip)
        val out = readSequential(zip.inputStream(), zip.size.toLong(), limits, checkpoint)
        if (central.size != out.size || central.toSet() != out.keys) {
            throw ImportRejected(msg("import.readAll.el_directorio_central_del_zip", central.size, out.size), RejectKind.DAMAGED_ZIP)
        }
        return out
    }

    /**
     * Tamaño máximo del directorio central que se acepta antes de leerlo. ICdetector escribe como mucho unas decenas de
     * entradas planas (unos pocos KiB); un directorio declarado mayor es un ZIP manipulado. `java.util.zip.ZipFile`
     * reserva de golpe un `byte[]` del tamaño declarado en el EOCD, así que este límite se comprueba antes de abrirlo.
     */
    const val MAX_CENTRAL_DIRECTORY_BYTES: Long = 1L shl 20

    private const val EOCD_SIZE = 22
    private const val EOCD_SIG = 0x06054b50L

    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    /** Posición del EOCD dentro de [b] (buscando hacia atrás, con el comentario máximo de 64 KiB), o -1. */
    private fun findEocd(b: ByteArray): Int {
        var i = b.size - EOCD_SIZE
        val lowest = maxOf(0, b.size - EOCD_SIZE - 0xFFFF)
        while (i >= lowest) { if (u32(b, i) == EOCD_SIG) return i; i-- }
        return -1
    }

    /** Número de entradas, tamaño y posición del directorio central, comprobados frente a la posición del EOCD. */
    private fun directory(b: ByteArray, eocd: Int, eocdPos: Long): Triple<Int, Long, Long> {
        val count = u16(b, eocd + 10)
        val cdSize = u32(b, eocd + 12)
        val cdOffset = u32(b, eocd + 16)
        if (count == 0xFFFF || cdOffset == 0xFFFFFFFFL) throw ImportRejected(msg("import.u32.zip64_no_admitido_icdetector_no"), RejectKind.INCOMPATIBLE)
        if (cdOffset + cdSize > eocdPos) throw ImportRejected(msg("import.u32.zip_danado_el_directorio_central"), RejectKind.DAMAGED_ZIP)
        return Triple(count, cdSize, cdOffset)
    }

    /** Nombres de [count] entradas del directorio central que empieza en [start] de [b] y no pasa de [end]. */
    private fun names(b: ByteArray, start: Int, end: Int, count: Int): List<String> {
        val names = ArrayList<String>()
        var p = start
        repeat(count) {
            if (p + 46 > end || u32(b, p) != 0x02014b50L) throw ImportRejected(msg("import.u32.zip_danado_directorio_central_incompleto"), RejectKind.DAMAGED_ZIP)
            val nameLen = u16(b, p + 28); val extraLen = u16(b, p + 30); val commentLen = u16(b, p + 32)
            if (p + 46 + nameLen > end) throw ImportRejected(msg("import.u32.zip_danado_nombre_de_entrada"), RejectKind.DAMAGED_ZIP)
            names += String(b, p + 46, nameLen, Charsets.UTF_8)
            p += 46 + nameLen + extraLen + commentLen
        }
        return names
    }

    /** Nombres del directorio central. Lanza [ImportRejected] si no existe o está incompleto. */
    fun centralDirectoryNames(zip: ByteArray): List<String> {
        if (zip.size < EOCD_SIZE) throw ImportRejected(msg("import.u32.zip_truncado_no_contiene_el"), RejectKind.DAMAGED_ZIP)
        val eocd = findEocd(zip)
        if (eocd < 0) throw ImportRejected(msg("import.u32.zip_truncado_o_danado_falta"), RejectKind.DAMAGED_ZIP)
        val (count, _, cdOffset) = directory(zip, eocd, eocd.toLong())
        return names(zip, cdOffset.toInt(), eocd, count)
    }

    /**
     * Nombres del directorio central de un ZIP en disco, leyendo solo su final y el propio directorio (como mucho
     * [MAX_CENTRAL_DIRECTORY_BYTES]). Se llama ANTES de abrir el archivo con `java.util.zip.ZipFile`, que reserva
     * memoria para el directorio declarado sin comprobarlo (revisión 22/10, A1: OutOfMemoryError con un EOCD falso).
     */
    fun centralDirectoryNames(file: java.io.File): List<String> = java.io.RandomAccessFile(file, "r").use { raf ->
        val len = raf.length()
        if (len < EOCD_SIZE) throw ImportRejected(msg("import.u32.zip_truncado_no_contiene_el"), RejectKind.DAMAGED_ZIP)
        val tail = ByteArray(minOf(len, (EOCD_SIZE + 0xFFFF).toLong()).toInt())
        val tailStart = len - tail.size
        raf.seek(tailStart); raf.readFully(tail)
        val eocd = findEocd(tail)
        if (eocd < 0) throw ImportRejected(msg("import.u32.zip_truncado_o_danado_falta"), RejectKind.DAMAGED_ZIP)
        val (count, cdSize, cdOffset) = directory(tail, eocd, tailStart + eocd)
        if (cdSize > MAX_CENTRAL_DIRECTORY_BYTES)
            throw ImportRejected(msg("import.zip.central_directory_too_large", cdSize, MAX_CENTRAL_DIRECTORY_BYTES), RejectKind.ZIP_SECURITY)
        val cd = ByteArray(cdSize.toInt())
        raf.seek(cdOffset); raf.readFully(cd)
        names(cd, 0, cd.size, count)
    }

    private fun readSequential(
        input: InputStream,
        compressedSize: Long,
        limits: ZipLimits,
        checkpoint: Checkpoint
    ): LinkedHashMap<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        val ratioCap = maxOf(compressedSize, 1L) * limits.maxRatio
        try {
            ZipInputStream(input).use { zin ->
                while (true) {
                    checkpoint.check()
                    val e = zin.nextEntry ?: break
                    val problem = validateEntryName(e.name)
                    if (problem != null) throw ImportRejected(msg("import.readSequential.entrada_zip_rechazada", e.name.take(80), problem), RejectKind.ZIP_SECURITY)
                    if (out.containsKey(e.name)) throw ImportRejected(msg("import.readSequential.entrada_zip_duplicada", e.name), RejectKind.ZIP_SECURITY)
                    if (out.size + 1 > limits.maxEntries) throw ImportRejected(msg("import.readSequential.el_zip_tiene_mas_de", limits.maxEntries), RejectKind.ZIP_SECURITY)
                    // Tamaño declarado: si ya supera el límite, se rechaza sin leer ni acumular nada.
                    if (e.size > limits.maxEntryBytes) throw ImportRejected(msg("import.readSequential.la_entrada_declara_mib_descomprimida", e.name, e.size / (1024 * 1024), limits.maxEntryBytes / (1024 * 1024)), RejectKind.ZIP_SECURITY)
                    if (e.size > 0 && total + e.size > limits.maxTotalBytes) throw ImportRejected(msg("import.readSequential.el_zip_declara_mas_de", limits.maxTotalBytes / (1024 * 1024)), RejectKind.ZIP_SECURITY)
                    // Se cuentan los bytes REALMENTE descomprimidos (el tamaño declarado puede mentir) y se corta en cuanto
                    // superan un límite, antes de acumular el bloque que lo rebasa.
                    val bos = ByteArrayOutputStream(if (e.size in 1..(1L shl 20)) e.size.toInt() else 64 * 1024)
                    val buf = ByteArray(32 * 1024)
                    var entryBytes = 0L
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        entryBytes += n; total += n
                        if (entryBytes > limits.maxEntryBytes) throw ImportRejected(msg("import.readSequential.la_entrada_supera_mib_descomprimida", e.name, limits.maxEntryBytes / (1024 * 1024)), RejectKind.ZIP_SECURITY)
                        if (total > limits.maxTotalBytes) throw ImportRejected(msg("import.readSequential.el_zip_supera_mib_descomprimido", limits.maxTotalBytes / (1024 * 1024)), RejectKind.ZIP_SECURITY)
                        if (total > ratioCap) throw ImportRejected(msg("import.readSequential.relacion_de_compresion_anomala_mas", limits.maxRatio), RejectKind.ZIP_SECURITY)
                        bos.write(buf, 0, n)
                        checkpoint.check()
                    }
                    out[e.name] = bos.toByteArray()
                }
            }
        } catch (z: ZipException) {
            throw ImportRejected(msg("import.readSequential.zip_danado_o_incompleto", z.message ?: msg("import.readSequential.error_de_formato")), RejectKind.DAMAGED_ZIP)
        } catch (z: java.io.EOFException) {
            throw ImportRejected(msg("import.readSequential.zip_truncado_el_archivo_termina"), RejectKind.DAMAGED_ZIP)
        }
        if (out.isEmpty()) throw ImportRejected(msg("import.readSequential.el_zip_no_contiene_entradas"), RejectKind.DAMAGED_ZIP)
        return out
    }
}
