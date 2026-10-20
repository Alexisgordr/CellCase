package com.alexisgordr.cellcase.core.casefile

import java.io.File

/**
 * Carpeta privada de expedientes (`<root>/cases/<id>`) con borrado en dos fases, recuperable ante fallos:
 *
 * 1. El expediente se marca «borrando» en la base de datos (lo hace la app).
 * 2. [moveToTrash] renombra su carpeta a `<root>/cases-trash/<id>` (operación atómica en el mismo sistema de archivos).
 * 3. La app borra las filas de la base de datos en una transacción.
 * 4. [purge] elimina la papelera.
 *
 * Si la app se cierra en medio, [pendingTrash] y la marca «borrando» permiten terminar el borrado al
 * volver a abrir, sin dejar filas que apunten a archivos inexistentes ni archivos huérfanos.
 * Solo se toca el almacenamiento privado de CellCase: los archivos que el usuario eligió con el selector
 * y los PDF que guardó fuera nunca están aquí (D-033).
 */
class CaseStorage(private val root: File) {
    val casesDir: File get() = File(root, "cases")
    val trashDir: File get() = File(root, "cases-trash")

    fun caseDir(id: String): File { require(safeId(id)) { "Id de expediente no válido" }; return File(casesDir, id) }
    fun originals(id: String) = File(caseDir(id), "originals").apply { mkdirs() }
    fun reports(id: String) = File(caseDir(id), "reports").apply { mkdirs() }

    /** Fase 2. Devuelve false si no se pudo mover (la app debe deshacer la marca y avisar). */
    fun moveToTrash(id: String): Boolean {
        val src = caseDir(id)
        if (!src.exists()) return true
        trashDir.mkdirs()
        val dst = File(trashDir, id)
        if (dst.exists()) dst.deleteRecursively()
        return src.renameTo(dst)
    }

    /** Fase 4. Idempotente. Devuelve true si la papelera de ese expediente ya no existe. */
    fun purge(id: String): Boolean {
        require(safeId(id))
        val dst = File(trashDir, id)
        if (!dst.exists()) return true
        dst.walkBottomUp().forEach { it.setWritable(true); it.delete() }
        return !dst.exists()
    }

    /** Expedientes que quedaron en la papelera por un borrado interrumpido. */
    fun pendingTrash(): List<String> = trashDir.listFiles()?.filter { it.isDirectory && safeId(it.name) }?.map { it.name }.orEmpty()

    /** Tamaño ocupado por un expediente (para explicar qué se borrará). */
    fun sizeOf(id: String): Long = caseDir(id).takeIf { it.exists() }?.walkTopDown()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    private fun safeId(id: String) = id.isNotEmpty() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' }
}
