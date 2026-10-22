package com.alexisgordr.cellcase.core.casefile

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Escritura atómica (D-041): se escribe en `<destino>.part`, se sincroniza con el disco y solo entonces se
 * renombra al destino. Si la escritura falla o se cancela (disco lleno, error al generar el PDF, cancelación),
 * el temporal se borra y el destino no se crea: nunca queda un archivo parcial que parezca válido.
 */
object AtomicFiles {
    fun <T> write(target: File, block: (OutputStream) -> T): T {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        try {
            val result = FileOutputStream(part).use { fos ->
                val r = block(fos)
                fos.flush()
                fos.fd.sync()
                r
            }
            // Renombrado que sustituye el destino de una vez (revisión 22/10, B7): antes se borraba el destino y después
            // se renombraba, y un corte entre los dos pasos dejaba sin archivo. Si el sistema no admite el movimiento
            // atómico, se sustituye igualmente con un solo movimiento.
            val from = part.toPath(); val to = target.toPath()
            try {
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            }
            return result
        } catch (t: Throwable) {
            part.delete()
            throw t
        }
    }

    /** Temporales `.part` que quedaron de una ejecución interrumpida (se borran al abrir la app). */
    fun leftovers(dir: File): List<File> = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".part") }.toList()
}
