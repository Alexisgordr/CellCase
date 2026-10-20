package com.alexisgordr.cellcase.core

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Expediente SINTÉTICO a escala (docs/cross-sources.md §6): los archivos de «Patrones» con un Stable-Site que tiene
 * [episodes] episodios en sombra rutinarios, como un expediente real con muchos cambios de celda. Cada episodio se
 * construye a partir de un cambio de servidora del historial sintético (celda anterior y candidata, hora UTC de la
 * fila). No reproduce ningún dato real: solo su volumen.
 */
object ScaleFixture {
    const val NAME = "SYNTHETIC_ICD-stable-site-escala.zip"

    fun stableSiteZip(episodes: Int = 548): ByteArray {
        val zone = ZoneId.of("Europe/Madrid")
        val rows = TestSupport.sample("SYNTHETIC_patrones_historial.csv").readLines().drop(1).mapNotNull { l ->
            val c = l.split(',')
            if (c.size < 6) null else Triple(LocalDateTime.parse(c[0].replace(' ', 'T')), "${c[5]}-${c[3]}-${c[4]}-${c[2]}-LTE", l)
        }.sortedBy { it.first }
        val changes = rows.zipWithNext().filter { (a, b) -> a.second != b.second }
        val sb = StringBuilder("episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,duration_ms,evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated\n")
        for (i in 0 until episodes) {
            val (a, b) = changes[i % changes.size]
            val ms = b.first.atZone(zone).toInstant().toEpochMilli() + (i / changes.size) * 1000L
            sb.append("5e0a7c1d-0000-4000-8000-${"%012d".format(1000 + i)},,${a.second},${b.second},$ms,${ms + 20_000},20000,,,0,,,,0,0,0,0,0,0\n")
        }
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(TestSupport.sample("SYNTHETIC_ICD-stable-site.zip").inputStream()).use { z -> while (true) { val e = z.nextEntry ?: break; entries[e.name] = z.readBytes() } }
        entries["shadow_episodes.csv"] = sb.toString().toByteArray()
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> entries.forEach { (k, v) -> z.putNextEntry(ZipEntry(k)); z.write(v); z.closeEntry() } }
        return out.toByteArray()
    }

    /** Archivos del expediente a escala: los de «Patrones» con el Stable-Site sustituido. */
    fun files(): List<Pair<String, ByteArray>> = GenerateSampleReport.PATTERNS.map { n ->
        if (n == "SYNTHETIC_ICD-stable-site.zip") NAME to stableSiteZip() else n to TestSupport.sample(n).readBytes()
    }
}
