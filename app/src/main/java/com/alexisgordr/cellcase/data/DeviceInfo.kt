package com.alexisgordr.cellcase.data

import android.os.Build
import com.alexisgordr.cellcase.core.casefile.DetectedEnvironment
import java.time.Instant
import java.time.ZoneId

/**
 * Detecta el teléfono en el que se ejecuta CellCase con APIs públicas que no requieren permisos
 * (`Build.MANUFACTURER`, `Build.MODEL`, `Build.VERSION`) y la zona actual (`ZoneId.systemDefault()`).
 * No usa identificadores persistentes (serie, IMEI, Android ID) ni permisos de telefonía o ubicación (D-032).
 * El resultado describe el dispositivo de ANÁLISIS: no demuestra de qué teléfono proceden los archivos.
 */
object DeviceInfo {
    fun detect(): DetectedEnvironment = DetectedEnvironment(
        manufacturer = Build.MANUFACTURER?.takeIf { it.isNotBlank() },
        model = Build.MODEL?.takeIf { it.isNotBlank() },
        androidRelease = Build.VERSION.RELEASE?.takeIf { it.isNotBlank() },
        sdkInt = Build.VERSION.SDK_INT,
        zoneId = ZoneId.systemDefault().id,
        detectedAt = Instant.now()
    )
}
