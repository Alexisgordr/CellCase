package com.alexisgordr.cellcase.logic

import com.alexisgordr.cellcase.core.backup.BackupLimits
import com.alexisgordr.cellcase.core.casefile.AnalysisConfig
import com.alexisgordr.cellcase.core.importing.FormatDetector
import com.alexisgordr.cellcase.core.importing.ImportLimits
import java.io.File
import java.io.InputStream

/**
 * Lógica de la app SIN dependencias de Android: se prueba en la JVM (app/src/test, tools/local/build-core.sh).
 * Lo que necesita Android (Uri, Context, base de datos, Compose) se queda en `data` y `ui`.
 */
object ImportPolicy {
    /**
     * Tamaño máximo que se copia de un archivo elegido, según sus primeros bytes. Un ZIP puede ser una copia de
     * seguridad de ICdetector (cientos de MiB): se admite hasta [BackupLimits.maxBackupBytes] y después el lector
     * decide (un ZIP que no sea una copia sigue limitado a [ImportLimits.MAX_FILE_BYTES] al procesarlo). Un archivo
     * de texto puede ser un historial de 3.0 (hasta [ImportLimits.MAX_HISTORY_FILE_BYTES], D-054); el resto de CSV se
     * sigue limitando a [ImportLimits.MAX_FILE_BYTES] al procesarlo.
     */
    fun copyLimit(head: ByteArray, backup: BackupLimits = BackupLimits()): Long =
        if (FormatDetector.isZip(head)) backup.maxBackupBytes else ImportLimits.MAX_HISTORY_FILE_BYTES

    /**
     * Filas de historial ya reservadas por los originales del expediente (D-054). Las copias de seguridad no se
     * cuentan aquí: su historial se reserva al analizar el expediente completo (instantánea), en orden de importación.
     */
    fun historyRows(files: List<java.io.File>): Int = files.sumOf { com.alexisgordr.cellcase.core.importing.CaseBudget.csvRows(it) }

    /** Lee los primeros [n] bytes sin consumirlos (el flujo debe admitir mark/reset). */
    fun peek(input: InputStream, n: Int = 4): ByteArray {
        require(input.markSupported()) { "el flujo no admite mark/reset" }
        input.mark(n)
        val b = ByteArray(n)
        var read = 0
        while (read < n) { val k = input.read(b, read, n - read); if (k < 0) break; read += k }
        input.reset()
        return b.copyOf(read)
    }

    /** Carpeta privada para la base temporal de una copia de seguridad (se vacía antes de usarla). */
    fun backupTempDir(cacheDir: File): File = File(cacheDir, "backup-import").apply { deleteRecursively(); mkdirs() }
}

/** SHA-256 de un archivo por bloques (sin cargarlo en memoria: una copia puede ocupar cientos de MiB). */
object FileHash {
    fun sha256(file: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

/** Nombre del PDF exportado: legible, sin caracteres problemáticos y distinto para cada informe. */
object ReportFileName {
    fun of(caseName: String, mode: String, language: String, reportId: String): String {
        val m = when (mode) {
            "EXPLAINED" -> if (language == "en") "plain" else "explicado"
            "SUMMARY" -> "resumido"
            else -> if (language == "en") "technical" else "tecnico"
        }
        return "CellCase-${caseName.replace(Regex("[^A-Za-z0-9_-]"), "_")}-$m-$language-${reportId.take(8)}.pdf"
    }
}

/** Resumen de la configuración sin palabras de un idioma (se guarda con el informe y se muestra en cualquier idioma). */
object ConfigSummary {
    fun of(cfg: AnalysisConfig): String =
        "${cfg.zoneId}${if (cfg.zoneConfirmed) "" else " (?)"} · " +
            (cfg.period?.let { "${it.start}–${it.end}" } ?: "*") + " · ${cfg.fileHashes.size} × SHA-256 · ${cfg.rules}"
}
