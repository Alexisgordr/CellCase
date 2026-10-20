package com.alexisgordr.cellcase.logic

import kotlinx.coroutines.Job
import kotlin.coroutines.cancellation.CancellationException

/**
 * Expediente abierto en la pantalla y trabajo en curso sobre él, SIN dependencias de Android (se prueba en la JVM).
 *
 * Al abrir otro expediente se cancela el trabajo del anterior ANTES de cambiar de expediente, y un resultado solo se
 * publica si sigue siendo del expediente abierto ([publishIfCurrent]). Así una carga lenta de A que termina después de
 * abrir B no muestra los datos de A en la pantalla de B, ni una acción sobre B usa el nombre de A.
 */
class CaseSession {
    @Volatile var caseId: String? = null
        private set
    private var job: Job? = null

    /** Cambia de expediente: cancela lo que hubiera en curso. Devuelve false si ya estaba abierto. */
    fun open(id: String): Boolean {
        if (caseId == id) return false
        job?.cancel()
        job = null
        caseId = id
        return true
    }

    /**
     * El expediente se ha borrado o cerrado: ya no hay expediente abierto y nada de lo que siga en curso se publica.
     * No cancela el trabajo (puede ser el propio borrado, que termina con normalidad).
     */
    fun close() { caseId = null }

    val busy: Boolean get() = job?.isActive == true

    /** Registra el trabajo en curso (uno a la vez). */
    fun track(j: Job) { job = j }

    fun isCurrent(id: String): Boolean = caseId == id

    /** Ejecuta [publish] solo si [id] sigue siendo el expediente abierto. Devuelve si se publicó. */
    inline fun publishIfCurrent(id: String, publish: () -> Unit): Boolean = if (isCurrent(id)) { publish(); true } else false
}

/**
 * Ejecuta [block] y convierte cualquier error en una llamada a [onError] en lugar de dejar que cierre la app
 * (UserFacingException, SQLiteException por disco lleno…). La cancelación de la corrutina se propaga.
 */
suspend inline fun <T> guardedCall(onError: (Exception) -> Unit, block: () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    onError(e); null
}
