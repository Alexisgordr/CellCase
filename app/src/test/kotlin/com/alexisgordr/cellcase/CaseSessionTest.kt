package com.alexisgordr.cellcase

import com.alexisgordr.cellcase.logic.CaseSession
import com.alexisgordr.cellcase.logic.guardedCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Expediente abierto y errores de las corrutinas de la pantalla del expediente (revisión del 20/10/2026, fallos 2 y 3).
 * Reproduce el recorrido de CaseViewModel con un repositorio falso: la lógica es la misma clase que usa la app.
 */
class CaseSessionTest {
    /** Lo que haría CaseViewModel.reload(): cargar en segundo plano y publicar solo si sigue abierto. */
    private fun reload(scope: CoroutineScope, session: CaseSession, shown: AtomicReference<String?>, load: (String) -> String, done: CountDownLatch? = null) {
        val id = session.caseId ?: return
        session.track(scope.launch {
            val snap = withContext(Dispatchers.IO) { load(id) }
            session.publishIfCurrent(id) { shown.set(snap) }
            done?.countDown()
        })
    }

    @Test fun slowLoadOfAIsNotShownAfterOpeningB() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = CaseSession(); val shown = AtomicReference<String?>(null)
        val aStarted = CountDownLatch(1); val releaseA = CountDownLatch(1); val aFinished = CountDownLatch(1)
        // Carga de A lenta y NO cooperativa (bloquea un hilo, como una consulta SQLite): termina aunque se cancele.
        val load = { id: String ->
            if (id == "A") { aStarted.countDown(); releaseA.await(5, TimeUnit.SECONDS); aFinished.countDown() }
            "snapshot de $id"
        }
        assertTrue(session.open("A")); reload(scope, session, shown, load)
        assertTrue(aStarted.await(5, TimeUnit.SECONDS))
        val bDone = CountDownLatch(1)
        assertTrue(session.open("B"))            // cancela la carga de A antes de cambiar de expediente
        assertFalse(session.busy)
        reload(scope, session, shown, load, bDone)
        assertTrue(bDone.await(5, TimeUnit.SECONDS))
        releaseA.countDown()                     // ahora termina A, tarde
        assertTrue(aFinished.await(5, TimeUnit.SECONDS))
        Thread.sleep(100)
        assertEquals("snapshot de B", shown.get())
        scope.cancel()
    }

    @Test fun reopeningTheSameCaseDoesNotCancelItsWork() {
        val s = CaseSession()
        assertTrue(s.open("A")); assertFalse(s.open("A"))
        assertTrue(s.isCurrent("A")); assertFalse(s.isCurrent("B"))
        var published = false
        assertFalse(s.publishIfCurrent("B") { published = true }); assertFalse(published)
        assertTrue(s.publishIfCurrent("A") { published = true }); assertTrue(published)
        s.close()
        assertNull(s.caseId); assertFalse(s.publishIfCurrent("A") { throw AssertionError() })
    }

    @Test fun errorsBecomeANoticeAndCancellationPropagates() = runBlocking {
        val notices = ArrayList<String>()
        // saveCase sobre un expediente borrado: el repositorio lanza; la pantalla recibe un aviso, no una excepción.
        val r: Boolean? = guardedCall({ notices += "error: ${it.message}" }) { throw IllegalStateException("expediente borrado") }
        assertNull(r); assertEquals(listOf("error: expediente borrado"), notices)
        // Errores de base de datos (SQLiteException es una RuntimeException) igual.
        guardedCall<Unit>({ notices += it.javaClass.simpleName }) { throw RuntimeException("disco lleno") }
        assertEquals("RuntimeException", notices.last())
        // El valor normal pasa sin aviso
        assertEquals(true, guardedCall({ notices += "x" }) { true }); assertEquals(2, notices.size)
        // La cancelación no se convierte en aviso
        try { guardedCall<Unit>({ notices += "no" }) { throw CancellationException("cancelado") }; throw AssertionError() }
        catch (_: CancellationException) {}
        assertEquals(2, notices.size)
        withTimeout(1000) { }
    }

    /**
     * Revisión 22/10 (C4): al girar la pantalla, la pantalla vuelve a pedir abrir el mismo expediente. Si la carga
     * inicial seguía en curso, se lanzaba otra y salía un aviso de error «operación en curso» sin que el usuario hubiera
     * hecho nada. Mientras se carga o ya está cargado, abrir el mismo expediente no hace nada.
     */
    @Test fun reopeningTheSameCaseWhileItLoadsDoesNothing() = runBlocking {
        val session = CaseSession()
        assertTrue(session.needsLoad("a", loaded = false))        // nada abierto
        session.open("a")
        assertTrue(session.needsLoad("a", loaded = false))        // abierto, sin cargar y sin trabajo
        val release = CountDownLatch(1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = scope.launch { withContext(Dispatchers.IO) { release.await() } }
        session.track(job)
        assertFalse("girar mientras carga", session.needsLoad("a", loaded = false))
        assertTrue("otro expediente sí se carga", session.needsLoad("b", loaded = false))
        release.countDown(); job.join()
        assertTrue("terminó sin publicar (falló): se puede volver a cargar", session.needsLoad("a", loaded = false))
        assertFalse("ya cargado", session.needsLoad("a", loaded = true))
        scope.cancel()
    }
}
