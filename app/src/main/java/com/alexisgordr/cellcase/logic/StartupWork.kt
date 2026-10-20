package com.alexisgordr.cellcase.logic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Trabajo de arranque del repositorio (terminar borrados interrumpidos, limpiar temporales) fuera del hilo
 * principal, SIN dependencias de Android. [await] bloquea al llamante hasta que termine: el repositorio lo llama
 * antes de leer expedientes, siempre desde un hilo de E/S, así que nadie lee el disco a medio limpiar.
 * Los errores de cada paso se ignoran, como antes (un fallo al limpiar no debe impedir abrir la app).
 */
class StartupWork(steps: List<() -> Unit>, scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
    private val job: Job = scope.launch { steps.forEach { step -> runCatching(step) } }

    val done: Boolean get() = job.isCompleted

    fun await() { if (!job.isCompleted) runBlocking { job.join() } }
}

/** Exportación de un informe al destino elegido. */
object ReportExport {
    /** «w» + «t»: escribe desde el principio y TRUNCA; sin «t», un destino más largo conservaría bytes del anterior. */
    const val WRITE_MODE = "wt"
}
