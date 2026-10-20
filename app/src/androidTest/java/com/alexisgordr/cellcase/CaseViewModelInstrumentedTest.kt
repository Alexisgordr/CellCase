package com.alexisgordr.cellcase

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.alexisgordr.cellcase.core.casefile.DeviceSource
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.ui.CaseViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/** CaseViewModel real (revisión del 20/10/2026, fallos 2 y 3). `./gradlew :app:connectedDebugAndroidTest`. */
@RunWith(AndroidJUnit4::class)
class CaseViewModelInstrumentedTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val repo = (app as CellCaseApp).repository
    private val created = ArrayList<String>()

    @After fun tearDown() { created.forEach { runCatching { repo.deleteCase(it) } } }

    private fun case(name: String) = repo.createCase(name, null, DeviceSource.NOT_DECLARED, "SYNTHETIC", ZoneId.of("Europe/Madrid"), true,
        DataOrigin.SYNTHETIC, null).also { created += it.id }

    private fun <T> onMain(block: () -> T): T { var r: T? = null; InstrumentationRegistry.getInstrumentation().runOnMainSync { r = block() }; @Suppress("UNCHECKED_CAST") return r as T }

    private fun waitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 20_000
        while (!onMain(cond)) { if (System.currentTimeMillis() > end) throw AssertionError("no llegó: $what"); Thread.sleep(50) }
    }

    @Test fun openingBWhileALoadsShowsB() {
        val a = case("A"); val b = case("B")
        val vm = onMain { CaseViewModel(app) }
        onMain { vm.open(a.id); vm.open(b.id) }
        waitUntil("snapshot de B") { vm.busy.value == null && vm.snapshot.value != null }
        Thread.sleep(500)   // por si una carga tardía de A intentara publicar
        assertEquals(b.id, onMain { vm.snapshot.value!!.case.id })
    }

    @Test fun savingADeletedCaseGivesAnErrorNotice() {
        val a = case("Borrado")
        val vm = onMain { CaseViewModel(app) }
        onMain { vm.open(a.id) }
        waitUntil("snapshot") { vm.busy.value == null && vm.snapshot.value != null }
        repo.deleteCase(a.id); created.remove(a.id)
        onMain { vm.setNotes("nota") }
        waitUntil("aviso de error") { vm.notice.value != null }
        val n = onMain { vm.notice.value }
        assertNotNull(n); assertTrue(n!!.isError)
    }
}
