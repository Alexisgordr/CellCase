package com.alexisgordr.cellcase.core

import com.alexisgordr.cellcase.core.importing.HistoryCsvImporter
import com.alexisgordr.cellcase.core.importing.HistoryImport
import com.alexisgordr.cellcase.core.time.DeclaredZone
import java.io.File
import java.time.ZoneId

object TestSupport {
    /** Raíz del proyecto: propiedad `cellcase.root` (la fija Gradle y el script local) o directorio actual. */
    val root: File = File(System.getProperty("cellcase.root") ?: ".").let { if (File(it, "samples").exists()) it else it.parentFile }

    fun sample(name: String): File = File(root, "samples/synthetic/$name").also {
        require(it.exists()) { "No existe la muestra $it" }
    }

    val MADRID = DeclaredZone(ZoneId.of("Europe/Madrid"), confirmed = true)

    fun importHistory(text: String, zone: DeclaredZone? = MADRID, name: String = "test.csv"): HistoryImport =
        HistoryCsvImporter().import(text.byteInputStream(Charsets.UTF_8), name, "imp-test", zone)

    fun importSample(name: String, zone: DeclaredZone? = MADRID): HistoryImport =
        sample(name).inputStream().use { HistoryCsvImporter().import(it, name, "imp-$name", zone) }

    const val HEADER = "Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio," +
        "ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName,SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming"

    /** Fila de historial válida con valores por defecto; [over] sustituye columnas por nombre. */
    fun row(ts: String, over: Map<String, String> = emptyMap()): String {
        val base = linkedMapOf(
            "Timestamp" to ts, "NetType" to "4G", "CID" to "1000001", "MNC" to "01", "TAC" to "1001", "MCC" to "001", "DBM" to "-90",
            "Verified" to "VERIFIED", "SecurityScore" to "100", "FailedHeuristics" to "OK", "Lat" to "0.01", "Lon" to "0.02",
            "PCI" to "101", "ARFCN" to "1850", "RSRQ" to "-10", "SINR" to "12", "AnomalyConfidence" to "0.0", "ApiLat" to "", "ApiLon" to "",
            "TA" to "3", "TAUnit" to "LTE_INDEX", "TAMeters" to "234", "Radio" to "LTE", "ServingConnection" to "PRIMARY_SERVING",
            "BandwidthKHz" to "20000", "Bands" to "3", "AdditionalPlmns" to "", "CsgIndicator" to "", "CsgIdentity" to "", "CsgName" to "",
            "SecondaryCarriers" to "", "ServiceState" to "IN_SERVICE", "NetworkOperator" to "00101", "SimOperator" to "00101", "NetworkRoaming" to "0"
        )
        base.putAll(over)
        return base.values.joinToString(",") { v -> if (v.contains(',') || v.contains('"')) "\"" + v.replace("\"", "\"\"") + "\"" else v }
    }

    fun csv(vararg rows: String) = HEADER + "\n" + rows.joinToString("\n") + "\n"
}
