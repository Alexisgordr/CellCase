# Licencias y dependencias

> No soy abogado; esto es un registro técnico, no asesoramiento legal.
> CellCase se distribuye con la **GNU General Public License v3.0**, la misma que ICdetector (D-052, 20/10/2026;
> sustituye a D-007, que la declaraba propietaria). El texto está en `LICENSE`, copiado sin cambios del de ICdetector.
> ICdetector no indica si es «solo la 3.0» o «3.0 o posterior»; CellCase tampoco lo concreta hasta que el autor lo
> decida (Q-20).

## Relación con ICdetector (GPL-3.0)

- Las dos aplicaciones tienen la misma licencia y el mismo autor. CellCase sigue siendo independiente: no enlaza
  ICdetector ni lo modifica, y solo lee los archivos que exporta o su copia de seguridad.
- Hasta el 20/10/2026 (D-002) CellCase no reproducía lógica de ICdetector. Para leer la copia de seguridad igual que
  las exportaciones, el autor de ICdetector autorizó reproducir su lógica de exportación (D-052), y con la licencia
  común no hay conflicto. Lo reproducido está en `core/.../backup/` y se lista en `docs/backup-format.md` §5, con la
  ruta del código de ICdetector 3.0.0 del que procede. Todo el código de ICdetector afectado es de su autor
  (historial de git de ICdetector: commits de Alexis y commits hechos con Claude en su repositorio).
- El inventario de formatos se elaboró leyendo el código de ICdetector y cita sus rutas; los lectores de
  CellCase son código nuevo escrito a partir de esa descripción.
- Datos factuales usados que **no** proceden de código de ICdetector:
  - Rangos 3GPP de PCI/ARFCN, códigos MCC de prueba (UIT-T E.212).
  - Factores TA → metros de la exportación (78 y 554 m): pasos de TA de 3GPP; CellCase solo los usa
    para comprobar la coherencia interna del archivo, no para calcular distancias.
- No se recalcula `site_key` de Stable-Site (D-008).
- Las herramientas de desarrollo leen el código de ICdetector como texto para comprobar que las muestras
  coinciden con sus cabeceras (`tools/synth/verify_against_source.py`); no lo ejecutan ni lo copian.

## Dependencias de la aplicación

| Dependencia | Versión | Licencia | Uso | Fuente |
|---|---|---|---|---|
| Android Gradle Plugin | 9.4.0 | Apache-2.0 | Compilación | developer.android.com/build/releases/gradle-plugin |
| Gradle (wrapper) | 9.6.0 | Apache-2.0 | Compilación | docs.gradle.org |
| Kotlin (stdlib, plugins JVM y Compose) | 2.4.10 | Apache-2.0 | Lenguaje | kotlinlang.org / guía de Compose |
| Jetpack Compose BOM (material3, ui-tooling) | 2026.09.00 | Apache-2.0 | Interfaz | developer.android.com/develop/ui/compose/bom |
| androidx.activity:activity-compose | 1.13.0 | Apache-2.0 | Selector de archivos, actividad | AndroidX stable channel |
| androidx.lifecycle (viewmodel-compose, runtime-compose) | 2.11.0 | Apache-2.0 | ViewModel, estado | AndroidX stable channel |
| kotlinx-coroutines-android | 1.11.0 | Apache-2.0 | Trabajo en segundo plano | github.com/Kotlin/kotlinx.coroutines |
| androidx.appcompat:appcompat | 1.8.0 | Apache-2.0 | Idioma por aplicación (D-045) | AndroidX stable channel |
| JUnit (solo pruebas) | 4.13.2 | EPL-1.0 | Pruebas | junit.org |
| androidx.test:core (solo pruebas instrumentadas) | 1.7.0 | Apache-2.0 | Pruebas en el teléfono | developer.android.com/jetpack/androidx/releases/test |
| androidx.test:runner (solo pruebas instrumentadas) | 1.7.0 | Apache-2.0 | Pruebas en el teléfono | ídem |
| androidx.test.ext:junit (solo pruebas instrumentadas) | 1.3.0 | Apache-2.0 | Pruebas en el teléfono | ídem |

Apache-2.0 es compatible con la GPL-3.0. JUnit (EPL-1.0) no lo es, pero solo se usa en las pruebas y no se
distribuye con la app.

`:core` no tiene dependencias en tiempo de ejecución salvo la biblioteca estándar de Kotlin.

## Datos incluidos en el código

| Elemento | Origen | Licencia |
|---|---|---|
| Anchos de glifo Helvetica/Helvetica-Bold (`StandardFontMetrics.kt`) | Métricas AFM Core14 de Adobe, extraídas con reportlab 5.0.1 (`tools/pdf/gen_metrics.py`) | Las AFM de Adobe permiten uso y redistribución; reportlab (BSD) solo se usa en desarrollo |
| `gradle/wrapper/gradle-wrapper.jar`, `gradlew` | Wrapper de Gradle 9.6.0 (`gradle-wrapper.properties`); los scripts se generaron con la instalación local de Gradle del entorno de desarrollo | Apache-2.0 |

Las fuentes PDF estándar (Helvetica, Courier) no se incrustan: las aporta el lector de PDF.

## Herramientas de desarrollo (no se distribuyen con la app)

Python 3 (biblioteca estándar); reportlab (BSD) solo para regenerar las métricas; Pillow, qpdf y poppler para
revisar los PDF.
