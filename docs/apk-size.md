# Tamaño de la APK (D-049)

## 1. Punto de partida (medido el 2026-10-02)

- **APK analizada**: `app/build/outputs/apk/release/app-release-unsigned.apk`, compilada en el ordenador del autor.
- **Tipo**: release sin firmar, **sin optimizar**: `isMinifyEnabled = false`, sin R8 ni `isShrinkResources`.
- **Tamaño**: 9 784 146 bytes (9,33 MiB; 9,8 MB).
- **Cómo se midió**: `python3 tools/apk_size.py APK`.

| Categoría | Comprimido | Descomprimido | Comentario |
|---|---:|---:|---|
| Código (2 DEX, 16 781 clases) | 8 457 KiB | 24 287 KiB | El 90 % del archivo. Bibliotecas completas sin reducir. |
| `resources.arsc` | 744 KiB | 744 KiB | Tabla de recursos con **84 idiomas** de las bibliotecas; CellCase solo usa inglés y español. |
| `res/` (346 archivos) | 137 KiB | 227 KiB | Imágenes y XML de AppCompat y Material. Los propios de CellCase son vectores. |
| Catálogos de textos (`cellcase/i18n`) | 53 KiB | 183 KiB | Necesarios (informes en español e inglés). |
| `META-INF` | 18 KiB | 50 KiB | Licencias de AndroidX (se conservan), servicios y versiones. |
| `assets/dexopt` (perfil de referencia) | 14 KiB | 14 KiB | Se conserva: acelera el arranque. |
| `kotlin/*.kotlin_builtins` | 12 KiB | 52 KiB | Solo los usa kotlin-reflect, que CellCase no emplea. |
| Nativo: `libandroidx.graphics.path.so` | 37 KiB en total | 37 KiB | x86_64 10,5 · arm64-v8a 9,9 · x86 9,1 · armeabi-v7a 7,1 KiB. |
| `DebugProbesKt.bin` | 0,8 KiB | 1,7 KiB | Agente de depuración de corrutinas. |

Reparto aproximado del código por paquete (instrucciones de los métodos):

| Paquete | KiB | Paquete | KiB |
|---|---:|---|---:|
| `androidx.compose.material3` | 1 409 | `androidx.collection` | 310 |
| `androidx.compose.foundation` | 1 284 | `kotlin.collections` | 266 |
| `androidx.compose.ui` | 1 235 | `androidx.compose.animation` | 234 |
| `com.alexisgordr.cellcase` (app + núcleo) | 541 | `androidx.appcompat.widget` | 151 |
| `androidx.compose.runtime` | 475 | `kotlinx.coroutines.flow` | 103 |

No hay **fuentes** incluidas: el PDF usa las 14 fuentes estándar con métricas propias (`StandardFontMetrics`) y la codificación WinAnsi, que cubre el español y el inglés. Tampoco hay **imágenes** propias en mapa de bits: el icono es vectorial. Por eso no hay nada que optimizar en ninguno de los dos casos sin tocar la calidad de los PDF.

No hay muestras, informes ni archivos de prueba en la APK: `samples/`, `core/src/test` y `tools/` no se empaquetan, y la herramienta lo comprueba.

## 2. Cambios

1. **R8** (`isMinifyEnabled`) con `proguard-android-optimize.txt` y reglas propias mínimas (`app/proguard-rules.pro`):
   - constructores de los `AndroidViewModel`, que se crean por reflexión;
   - nombres de los enums que se guardan en la base de datos (los expedientes existentes siguen abriendo);
   - nombres de las excepciones, que se muestran en los mensajes de error;
   - número de línea en las trazas, que se leen con el `mapping.txt` de cada versión.

   No se conserva ninguna dependencia completa. Los catálogos y el PDF no usan reflexión: los catálogos se cargan por ruta absoluta y R8 conserva los recursos Java.
2. **`isShrinkResources`**: elimina los recursos de bibliotecas que nada referencia.
3. **`androidResources.localeFilters = en, es`**: quita las traducciones de las bibliotecas a idiomas que CellCase no ofrece, incluidas sus variantes regionales (`es-US`…): en la APK queda un solo idioma con calificador, `es`, y el inglés como predeterminado. Un teléfono en español de cualquier región usa `es`. Los idiomas de la app, sus textos y su accesibilidad no cambian.
4. **Exclusión de metadatos que no se usan en ejecución**: `DebugProbesKt.bin`, `kotlin/**.kotlin_builtins` y `META-INF/*.version`. Las licencias se conservan.
5. **Dependencias** (AppCompat costó ~1,1 MiB sin optimizar al añadir el idioma por aplicación: +744 KiB de DEX, +282 KiB de `resources.arsc` y +124 KiB de `res/`, medido entre la APK anterior y la actual; R8 y la eliminación de recursos deberían quitar la mayor parte. Sustituirlo por código propio con `LocaleManager` en Android 13+ y un contexto con configuración propia en Android 8–12 es posible, pero no se hace sin poder verificarlo en esos Android):
   - Se quita `ui-tooling-preview` de la release: no hay ninguna `@Preview`. `ui-tooling` sigue solo en debug.
   - El resto es necesario y no se sustituye:
     - Compose y Material 3 dibujan la interfaz;
     - AppCompat es imprescindible para elegir el idioma por aplicación en Android 8–12, y R8 elimina sus partes no usadas, como las vistas clásicas o Fragment;
     - las corrutinas y Lifecycle sostienen la carga y el análisis en segundo plano.
6. **Bibliotecas nativas**: solo `libandroidx.graphics.path.so`, 37 KiB para las cuatro arquitecturas (0,4 % del archivo). **No compensa dividir la APK**: se mantiene una APK universal para distribución directa.
7. **Comparación equivalente**: `-Pcellcase.sinOptimizar` compila la misma release, con el mismo código, sin R8 ni eliminación de recursos. Así la diferencia de tamaño se debe solo a estos cambios.

## 3. Resultado medido en el ordenador del usuario

APK release sin firmar compilada por el usuario con R8 (`app-release-unsigned.apk` de `cellcase.zip`), analizada con `tools/apk_size.py`:

| | Antes (sin optimizar) | Con R8 y eliminación de recursos |
|---|---:|---:|
| Archivo | 9 784 146 bytes (9,33 MiB) | 1 926 851 bytes (1,84 MiB), −80,3 % |
| Código DEX | 8 457 KiB, 16 781 clases | 1 450 KiB, 3 167 clases |
| `resources.arsc` | 744 KiB | 128 KiB |
| Idiomas con calificador | 84 | 1 (`es`) |

**No es una comparación equivalente**: entre las dos compilaciones cambió también el código de CellCase. Para una comparación exacta, usa `-Pcellcase.sinOptimizar` con el mismo código (apartado 4). Los PDF generados en el teléfono por la versión 0.1.0 indican que la compilación optimizada funciona, pero la comprobación funcional del apartado 5 sigue pendiente.

## 4. Medir antes y después (en el ordenador con el SDK)

```bash
./gradlew :app:assembleRelease -Pcellcase.sinOptimizar
cp app/build/outputs/apk/release/app-release-unsigned.apk /tmp/antes.apk
./gradlew :app:assembleRelease
cp app/build/outputs/apk/release/app-release-unsigned.apk /tmp/despues.apk
python3 tools/apk_size.py /tmp/antes.apk /tmp/despues.apk
```

Guarda `app/build/outputs/mapping/release/mapping.txt` con cada APK publicada: sirve para leer las trazas de errores.

**Espacio instalado** (distinto del archivo): APK + código que ART compila + datos.
1. Instala cada versión.
2. Ejecuta `adb shell cmd package compile -m speed-profile -f com.alexisgordr.cellcase` para que las dos queden compiladas igual.
3. Mira «Aplicación» en Ajustes › Aplicaciones › CellCase › Almacenamiento.

## 5. Comprobación funcional de la release optimizada (en un Android real)

1. **Instalación**: firma la APK con la misma clave que la versión instalada (fuera del repositorio) e instala **encima** con `adb install -r`, sin desinstalar. Así se comprueba que los expedientes existentes siguen abriendo.
2. **Expedientes existentes**: abre uno creado con la versión anterior; deben verse sus archivos, episodios, cruces e informes.
3. **Importación**: añade a la vez un CSV de historial, el CSV de Radio y los ZIP de Topología, Stable-Site, Geometría y caso forense (muestras `samples/synthetic`). Comprueba el análisis, los episodios y la sección «Coincidencias y diferencias entre fuentes».
4. **Idioma**: cambia de idioma (sistema, español e inglés) y vuelve; los textos cambian y el expediente sigue igual.
5. **Informes**: genera las cuatro variantes (explicado y técnico, en español e inglés). Ábrelas y compáralas con `samples/reports`.
6. **Eliminación**: crea un expediente de prueba y elimínalo.
7. **Errores**: revisa `adb logcat | grep -iE "ClassNotFound|NoSuchMethod|NoSuchField|cellcase"` durante las pruebas.
