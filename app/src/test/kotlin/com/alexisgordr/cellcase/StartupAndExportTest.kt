package com.alexisgordr.cellcase

import com.alexisgordr.cellcase.logic.ReportExport
import com.alexisgordr.cellcase.logic.StartupWork
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Revisión del 20/10/2026, fallos 11 y 12: exportación que trunca el destino y arranque fuera del hilo principal. */
class StartupAndExportTest {
    @Test fun reportExportTruncatesTheTarget() {
        assertEquals("wt", ReportExport.WRITE_MODE)
    }

    @Test fun startupRunsInTheBackgroundAndAwaitWaitsForIt() {
        val release = CountDownLatch(1)
        val done = Collections.synchronizedList(ArrayList<String>())
        val caller = Thread.currentThread()
        val work = StartupWork(listOf(
            { assertFalse(Thread.currentThread() == caller); release.await(5, TimeUnit.SECONDS); done += "borrados" },
            { done += "temporales" }), CoroutineScope(SupervisorJob() + Dispatchers.IO))
        // El constructor no espera: la app arranca sin hacer E/S en el hilo que la crea.
        assertFalse(work.done); assertTrue(done.isEmpty())
        Thread { Thread.sleep(200); release.countDown() }.start()
        work.await()                               // la primera lectura espera a que termine
        assertTrue(work.done)
        assertEquals(listOf("borrados", "temporales"), done.toList())
        work.await()                               // y después no vuelve a esperar ni a ejecutar nada
        assertEquals(2, done.size)
    }

    @Test fun aFailingStepDoesNotStopTheOthersNorTheApp() {
        val done = Collections.synchronizedList(ArrayList<String>())
        val work = StartupWork(listOf({ throw IllegalStateException("carpeta ilegible") }, { done += "temporales" }))
        work.await()
        assertEquals(listOf("temporales"), done.toList())
    }
}
