# CellCase · reglas de R8 (D-049, docs/apk-size.md)
#
# Además de estas reglas se aplican proguard-android-optimize.txt (AGP) y las que publica cada biblioteca
# (Compose, AppCompat, Lifecycle, corrutinas). No se conserva ninguna dependencia completa: solo lo que se
# usa por reflexión o por nombre.

# 1. ViewModels: viewModel() los crea por reflexión con el constructor (Application).
-keepclassmembers class com.alexisgordr.cellcase.** extends androidx.lifecycle.AndroidViewModel {
    <init>(android.app.Application);
}

# 2. Enumeraciones guardadas por nombre en la base de datos (DataOrigin, ExportKind, ImportState, Severity,
#    CoordinateMode, IdentifierMode, DeviceSource, ReportMode…): valueOf() con el texto guardado. Se conservan sus
#    nombres para que los expedientes existentes sigan abriendo aunque R8 optimice las clases.
-keepclassmembers,allowoptimization enum com.alexisgordr.cellcase.** {
    <fields>;
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 3. Mensajes de error: si una excepción no tiene texto, la interfaz muestra el nombre de su clase.
-keepnames class * extends java.lang.Exception

# 4. Catálogos de textos (cellcase/i18n/messages_*.txt) y generación de PDF: se cargan por ruta absoluta con
#    getResourceAsStream y no usan reflexión; R8 conserva los recursos Java. No hace falta ninguna regla.

# 5. Trazas legibles con el mapping.txt de cada versión (guárdalo junto a la APK publicada).
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
