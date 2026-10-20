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
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                part.copyTo(target, overwrite = true)
                part.delete()
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
