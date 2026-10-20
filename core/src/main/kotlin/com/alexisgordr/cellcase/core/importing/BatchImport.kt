package com.alexisgordr.cellcase.core.importing

import com.alexisgordr.cellcase.core.i18n.Msg
import com.alexisgordr.cellcase.core.i18n.msg

import com.alexisgordr.cellcase.core.time.DeclaredZone

/** Un archivo elegido por el usuario, ya leído en memoria. */
class SelectedFile(val name: String, val bytes: ByteArray)

sealed interface BatchOutcome {
    val fileName: String
    /** Procesado (importado o rechazado con su categoría). */
    data class Processed(val result: ImportResult) : BatchOutcome { override val fileName get() = result.fileName }
    /** Mismo SHA-256 que un archivo ya presente: no se procesa ni se cuenta dos veces. */
    data class AlreadyPresent(override val fileName: String, val sha256: String, val existingName: String) : BatchOutcome
    /** Error inesperado leyendo este archivo; los demás siguen. */
    data class Failed(override val fileName: String, val text: Msg) : BatchOutcome {
        val message: String get() = text.toString()
    }
}

/**
 * Importación de varios archivos en una sola operación. Cada archivo es independiente:
 * si uno falla, los demás se importan igual. Los archivos idénticos (mismo SHA-256) se detectan
 * frente a los ya importados y dentro del propio lote.
 */
object BatchImport {
    /**
     * Procesa cada archivo por separado. [commit] recibe cada resultado en cuanto termina (la app lo guarda
     * entonces). Si se cancela, la excepción de cancelación se propaga: los archivos ya entregados a [commit]
     * se conservan y el que estaba en curso no se entrega (D-038).
     */
    fun run(
        files: List<SelectedFile>,
        existing: Map<String, String>,
        zone: DeclaredZone?,
        newId: () -> String,
        checkpoint: Checkpoint = Checkpoint.NONE,
        progress: (index: Int, name: String) -> Unit = { _, _ -> },
        commit: (BatchOutcome) -> Unit = {},
        budget: CaseBudget = CaseBudget()
    ): List<BatchOutcome> {
        val seen = HashMap(existing)
        val out = ArrayList<BatchOutcome>()
        files.forEachIndexed { i, f ->
            checkpoint.check()
            progress(i, f.name)
            val outcome = try {
                if (f.bytes.size > ImportLimits.MAX_FILE_BYTES) BatchOutcome.Failed(f.name, ImportLimits.tooLargeMessage(f.bytes.size.toLong()))
                else {
                    val sha = Hashing.sha256Hex(f.bytes)
                    val prev = seen[sha]
                    if (prev != null) BatchOutcome.AlreadyPresent(f.name, sha, prev)
                    else BatchOutcome.Processed(ImportPipeline.process(f.bytes, f.name, newId(), zone, checkpoint, budget)).also { seen[sha] = f.name }
                }
            } catch (e: java.util.concurrent.CancellationException) {
                throw e
            } catch (e: Exception) {
                BatchOutcome.Failed(f.name, msg("import.run.error_al_leer", (e as? ImportRejected)?.text ?: Msg.raw(e.message ?: e.javaClass.simpleName)))
            }
            commit(outcome)
            out += outcome
        }
        return out
    }
}
