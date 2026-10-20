package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.analysis.CaseAnalyzer
import com.alexisgordr.cellcase.core.i18n.Language
import com.alexisgordr.cellcase.core.importing.ImportLimits
import com.alexisgordr.cellcase.core.importing.ImportPipeline
import com.alexisgordr.cellcase.core.importing.ImportResult
import com.alexisgordr.cellcase.core.report.CaseInfo
import com.alexisgordr.cellcase.core.report.DataOrigin
import com.alexisgordr.cellcase.core.report.ReportBuilder
import com.alexisgordr.cellcase.core.report.ReportMode
import com.alexisgordr.cellcase.core.report.ReportOptions
import com.alexisgordr.cellcase.core.report.TextContext
import com.alexisgordr.cellcase.core.report.reportTexts
import java.io.File
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Medición de memoria en la JVM con un lote completo cercano a los límites (docs/limits.md). No es una prueba
 * automática ni una medición en Android: sirve para saber cuánta memoria usa el núcleo con los límites actuales.
 * Datos SINTÉTICOS generados al vuelo (PLMN de pruebas 001-01).
 *
 * Uso: java -Xmx256m … com.alexisgordr.cellcase.core.MemoryProbe DIRECTORIO_TEMPORAL
 * Sigue el recorrido de la app: los archivos están en disco y se leen de uno en uno con processFile; después se
 * analizan todos juntos y se generan los dos informes en los dos idiomas.
 */
object MemoryProbe {
    /**
     * Tamaños configurables: -Dprobe.rows=FILAS del historial (por defecto, el presupuesto del expediente),
     * -Dprobe.v3=true para un historial con el formato de ICdetector 3.0 (54 columnas) y -Dprobe.entry=BYTES por
     * entrada del ZIP forense.
     */
    private val HIST_ROWS = System.getProperty("probe.rows")?.toInt() ?: ImportLimits.CASE_HISTORY_ROWS
    private val V3 = System.getProperty("probe.v3") == "true"
    private val ENTRY_BYTES = System.getProperty("probe.entry")?.toLong() ?: 22_000_000L
    private val heap = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
    private fun resetPeak() { System.gc(); heap.forEach { it.resetPeakUsage() } }
    private fun peakMiB() = heap.sumOf { it.peakUsage.used } / 1048576.0
    private fun usedMiB(): Double { System.gc(); return heap.sumOf { it.usage.used } / 1048576.0 }

    private fun <T> stage(name: String, block: () -> T): T {
        resetPeak(); val t0 = System.nanoTime()
        val r = block()
        val ms = (System.nanoTime() - t0) / 1_000_000
        println(String.format("%-38s %7d ms   pico de montón %7.1f MiB   retenido después %7.1f MiB", name, ms, peakMiB(), usedMiB()))
        return r
    }

    /** Historial con el formato de ICdetector 3.0: las filas de la muestra sintética repetidas, desplazadas 12 días cada vuelta. */
    private fun historyV3(f: File) {
        val src = TestSupport.sample("icd30/SYNTHETIC_icd30_history.csv")
        val reader = com.alexisgordr.cellcase.core.csv.CsvReader(src.reader().buffered()); val header = reader.next()!!.fields
        val rows = generateSequence { reader.next()?.fields }.toList()
        val iTs = header.indexOf("Timestamp"); val iUtc = header.indexOf("ObservedAtUtc"); val iRow = header.indexOf("RowId"); val iObs = header.indexOf("ObservationId")
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        fun esc(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        f.bufferedWriter().use { w ->
            w.write(header.joinToString(",")); w.write("\n")
            var n = 0; var k = 0L
            while (n < HIST_ROWS) {
                for (r in rows) {
                    if (n >= HIST_ROWS) break
                    val g = r.toMutableList(); val shift = java.time.Duration.ofDays(12L * k)
                    g[iTs] = fmt.format(LocalDateTime.parse(g[iTs], fmt).plus(shift))
                    if (g[iUtc].isNotEmpty()) g[iUtc] = Instant.parse(g[iUtc]).plus(shift).toString()
                    g[iRow] = (n + 1).toString(); if (g[iObs].isNotEmpty()) g[iObs] = g[iObs].dropLast(6) + "%06d".format(n % 1_000_000)
                    w.write(g.joinToString(",") { esc(it) }); w.write("\n"); n++
                }
                k++
            }
        }
    }

    /** Historial con el formato de ICdetector 2.10.5 y [HIST_ROWS] filas. */
    private fun history(f: File) {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        var t = LocalDateTime.of(2026, 8, 1, 0, 0, 0)
        f.bufferedWriter().use { w ->
            w.write(TestSupport.HEADER); w.write("\n")
            var i = 0
            while (i < HIST_ROWS) {
                val cid = 1_000_000 + (i / 40) % 60
                val reason = if (i % 997 == 0) "[sub-umbral] Downgrade de banda forzado (1805MHz→791MHz, B3→B20)" else "OK"
                w.write(TestSupport.row(fmt.format(t), mapOf("CID" to cid.toString(), "PCI" to (100 + cid % 400).toString(), "ARFCN" to "1850",
                    "DBM" to (-70 - i % 40).toString(), "FailedHeuristics" to reason)))
                w.write("\n")
                t = t.plusSeconds(30); i++
                if (i % 5000 == 0) w.flush()
            }
        }
    }

    /** Caso forense (por defecto ~46 MiB descomprimidos (límite: 24 MiB por entrada y 48 MiB en total). */
    private fun forensicZip(f: File) {
        val parts = File(f.parentFile, "parts").apply { mkdirs() }
        fun part(name: String, write: (java.io.Writer) -> Unit): File = File(parts, name).also { p -> p.bufferedWriter().use(write) }
        val start = Instant.parse("2026-09-27T19:13:00Z")
        val files = listOf(
            part("case.json") { it.write("""{"schemaVersion":1,"caseId":"ICD-2026-09-30-0099","state":"READY","createdAt":"2026-09-27 21:13:00","captureOrigin":"ALARM","updatedAt":"2026-09-28 06:00:00","closedAt":"2026-09-28 06:00:00","cellIdentity":"001-01-1099-9999001-LTE","highestPhase":3,"confirmed":true,"sampleCount":6000,"appVersion":"2.10.5","device":"SYNTHETIC Phone","android":"16"}""") },
            part("timeline.csv") { w ->
                w.write("timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason\n")
                for (i in 0 until 6000) w.write("\"${start.plusSeconds(5L * i)}\",\"${5000L * i}\",\"CYCLE\",\"${i % 4}\",\"${60 + i % 40}\",\"${i % 10}.0\",\"001-01-1099-9999001-LTE\",\"NOT_FOUND\",\"OK\",\"0.01\",\"0.04\",\"12.0\",\"OK\"\n")
            },
            part("cells.csv") { w ->
                w.write("timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit\n")
                var n = 0L; var i = 0
                while (n < ENTRY_BYTES) {
                    val line = "\"${start.plusSeconds(5L * (i / 23))}\",\"NEIGHBOUR\",\"001-01-1001-${2_000_000 + i % 23}-LTE\",\"001\",\"01\",\"1001\",\"${2_000_000 + i % 23}\",\"LTE\",\"4G\",\"-${80 + i % 30}\",\"${i % 504}\",\"1850\",\"3\",\"-10\",\"12\",\"\",\"\"\n"
                    w.write(line); n += line.length; i++
                }
            },
            part("heuristics.csv") { w ->
                w.write("timestamp_utc,heuristic_id,name,status,explanation\n")
                var n = 0L; var i = 0
                val expl = "Sintético: explicación larga de prueba para llenar la entrada sin pasar de los límites de filas ni de bytes descomprimidos. ".repeat(2)
                while (n < ENTRY_BYTES) {
                    val line = "\"${start.plusSeconds(5L * (i / 16))}\",\"${1 + i % 16}\",\"Heurística ${1 + i % 16}\",\"PASSED\",\"$expl$i\"\n"
                    w.write(line); n += line.toByteArray().size; i++
                }
            }
        )
        val sums = files.joinToString("") { p -> com.alexisgordr.cellcase.core.importing.Hashing.sha256Hex(p.readBytes()) + "  " + p.name + "\n" }
        ZipOutputStream(f.outputStream()).use { zip ->
            for (p in files) { zip.putNextEntry(ZipEntry(p.name)); p.inputStream().use { it.copyTo(zip) }; zip.closeEntry() }
            zip.putNextEntry(ZipEntry("SHA256SUMS.txt")); zip.write(sums.toByteArray()); zip.closeEntry()
        }
        parts.deleteRecursively()
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val dir = File(args.getOrElse(0) { "build-local/memprobe" }).apply { mkdirs() }
        println("JVM: máximo de montón ${Runtime.getRuntime().maxMemory() / 1048576} MiB · límites: archivo ${ImportLimits.MAX_FILE_BYTES / 1048576} MiB, " +
            "historial ${ImportLimits.MAX_HISTORY_FILE_BYTES / 1048576} MiB, filas ${ImportLimits.MAX_TABLE_ROWS}, presupuesto ${ImportLimits.CASE_HISTORY_ROWS} filas, " +
            "campo ${ImportLimits.MAX_FIELD_CHARS} caracteres")
        val tag = (if (V3) "v3_" else "v2_") + HIST_ROWS
        val hist = File(dir, "SYNTHETIC_large_history_$tag.csv"); val zip = File(dir, "SYNTHETIC_large_forensic_$ENTRY_BYTES.zip")
        if (!hist.exists()) if (V3) historyV3(hist) else history(hist)
        if (!zip.exists()) forensicZip(zip)
        val big = File(dir, "SYNTHETIC_too_large_$tag.csv")
        if (!big.exists()) big.outputStream().use { o -> while (big.length() <= ImportLimits.MAX_HISTORY_FILE_BYTES) { hist.inputStream().use { it.copyTo(o) }; o.flush() } }
        val hist2 = File(dir, "SYNTHETIC_second_history_$tag.csv")
        if (!hist2.exists()) hist2.writeText(hist.readText().replace("2026-08", "2026-07"))
        val files = listOf(hist, hist2, zip, big) + GenerateSampleReport.PATTERNS.filter { !it.startsWith("SYNTHETIC_patrones_historial") }.map { TestSupport.sample(it) }
        println("Lote: " + files.joinToString { "${it.name} (${it.length() / 1024} KiB)" })
        val zone = TestSupport.MADRID
        val results = ArrayList<ImportResult>()
        val budget = com.alexisgordr.cellcase.core.importing.CaseBudget()
        for ((i, f) in files.withIndex()) {
            val r = stage("leer ${f.name.take(32)}") { ImportPipeline.processFile(f, f.name, "imp-$i", zone, budget = budget) }
            println("    → ${r.kind} · ${r.state} · ${r.counts} · ${r.message ?: ""}")
            results += r
        }
        // Desglose del análisis para localizar picos
        val histories = results.mapNotNull { (it.data as? com.alexisgordr.cellcase.core.importing.ImportedData.History)?.data }
        val merged = stage("  · fusión de historiales") { com.alexisgordr.cellcase.core.analysis.HistoryMerge.merge(histories, zone) }
        stage("  · descripción del historial") { com.alexisgordr.cellcase.core.analysis.HistoryAnalyzer.analyze(merged!!.import, null) }
        stage("  · episodios") { com.alexisgordr.cellcase.core.analysis.EpisodeEngine.build(merged!!.import.observations, emptyList(), emptyList(), null, null, null, zone.zoneId) }
        val analysis = stage("análisis conjunto") { CaseAnalyzer.analyze(results, zone, null) }
        println("    → ${analysis.episodes.episodes.size} episodios; ${results.count { it.imported }} importados")
        for (lang in Language.values()) for (mode in ReportMode.values()) {
            val ctx = TextContext(CaseInfo("m", "Memoria", Instant.now(), "SYNTHETIC", zone, DataOrigin.SYNTHETIC, null, null), results,
                ReportOptions(generatedAt = Instant.parse("2026-10-02T08:00:00Z"), appVersion = "probe", mode = mode))
            val out = File(dir, "probe_${mode}_${lang.tag}.pdf")
            if (mode == ReportMode.FULL && lang == Language.ES) stage("  · documento (bloques) $mode") { ReportBuilder(reportTexts(lang)).build(ctx, analysis).blocks.size }
            val pages = stage("informe $mode ${lang.tag}") { out.outputStream().buffered().use { ReportBuilder(reportTexts(lang)).writePdf(ctx, analysis, it) } }
            println("    → $pages páginas, ${out.length() / 1024} KiB")
        }
    }
}
