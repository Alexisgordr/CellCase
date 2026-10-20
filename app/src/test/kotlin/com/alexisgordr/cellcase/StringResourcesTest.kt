package com.alexisgordr.cellcase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Recursos de la interfaz en inglés (predeterminado) y español (D-045): mismas claves, mismos marcadores de
 * formato y plurales completos. Prueba de JVM: no necesita un dispositivo.
 */
class StringResourcesTest {
    private val res = File(System.getProperty("cellcase.root") ?: "..", "app/src/main/res").let { if (it.exists()) it else File("src/main/res") }

    private data class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(dir: String): Res {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(res, "$dir/strings.xml"))
        val strings = LinkedHashMap<String, String>(); val plurals = LinkedHashMap<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            when (e.tagName) {
                "string" -> strings[e.getAttribute("name")] = e.textContent
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[e.getAttribute("name")] = (0 until items.length).associate { j -> (items.item(j) as Element).let { it.getAttribute("quantity") to it.textContent } }
                }
            }
        }
        return Res(strings, plurals)
    }

    private fun marks(s: String) = Regex("""%(\d+)\$[sd]""").findAll(s).map { it.value }.toSortedSet()

    @Test fun sameKeysAndPlaceholdersInBothLanguages() {
        val en = load("values"); val es = load("values-es")
        assertEquals(en.strings.keys - "app_name", es.strings.keys)
        assertEquals(en.plurals.keys, es.plurals.keys)
        for ((k, v) in es.strings) assertEquals(k, marks(en.strings.getValue(k)), marks(v))
        for ((k, forms) in es.plurals) for ((q, v) in forms) assertEquals("$k/$q", marks(en.plurals.getValue(k).getValue("other")), marks(v))
    }

    @Test fun pluralsHaveTheRequiredQuantities() {
        for ((k, f) in load("values").plurals) assertTrue(k, f.keys.containsAll(listOf("one", "other")))
        for ((k, f) in load("values-es").plurals) assertTrue(k, f.keys.containsAll(listOf("one", "many", "other")))
    }

    /** Lint PluralsCandidate: una cantidad seguida de palabras va en <plurals>, y una frase con varias cantidades se redacta como «etiqueta: número». */
    @Test fun quantitiesFollowedByWordsArePluralsAndMultiCountStringsAreLabelled() {
        val en = load("values"); val es = load("values-es")
        for (k in listOf("history_combined", "duplicates_forensic")) {
            assertTrue(k, k in en.plurals && k in es.plurals && k !in en.strings && k !in es.strings)
            assertTrue("$k: %1\$d es la cantidad", en.plurals.getValue(k).values.all { "%1\$d" in it } && es.plurals.getValue(k).values.all { "%1\$d" in it })
        }
        for (k in listOf("episodes_counts", "history_collector")) for (r in listOf(en, es)) {
            // Cada %N$d va precedido de «: » y seguido de «·» o del final: ninguna palabra depende del número.
            val labelled = Regex(":\\s%\\d+\\\$d(\\s·|\\.?\$)").findAll(r.strings.getValue(k)).count()
            assertEquals(k, marks(r.strings.getValue(k)).size, labelled)
        }
    }

    @Test fun noUnescapedApostrophesOrPercentSigns() {
        for (dir in listOf("values", "values-es")) {
            val raw = File(res, "$dir/strings.xml").readText()
            assertTrue("$dir: apóstrofo sin escapar", !Regex("""[^\\]'""").containsMatchIn(raw.substringAfter("<resources>")))
            assertTrue("$dir: % suelto", !Regex("""%(?!\d+\$[sd]|%)""").containsMatchIn(raw))
        }
    }

    @Test fun everyResourceUsedInTheCodeExists() {
        val en = load("values")
        val src = File(res.parentFile, "java")
        val used = src.walkTopDown().filter { it.extension == "kt" }.flatMap { f -> Regex("""R\.(string|plurals)\.(\w+)""").findAll(f.readText()).map { it.groupValues[1] to it.groupValues[2] } }.toSet()
        val missing = used.filter { (t, n) -> if (t == "string") n !in en.strings else n !in en.plurals }
        assertTrue("faltan: $missing", missing.isEmpty())
    }

    @Test fun supportedLocalesAreDeclared() {
        val cfg = File(res, "xml/locales_config.xml").readText()
        assertTrue(cfg.contains("android:name=\"en\"") && cfg.contains("android:name=\"es\""))
        val manifest = File(res.parentFile, "AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
        assertTrue(manifest.contains("AppLocalesMetadataHolderService") && manifest.contains("autoStoreLocales"))
    }
}
