# Copia de seguridad de ICdetector 3.0.0 y 3.1.0 (`.icdbackup`) en CellCase

Lector: `backup-icd/1.2.0` (`core/.../backup/IcdBackup.kt`). Decisiones: D-052 (licencia y reproducción autorizada),
D-053 (compatibilidad 3.0), D-054 (lectura de la copia y presupuesto), D-057 (esquema 26). Fuentes consultadas:
ICdetector `v3.0.0` (commit `f5173a8`) y `v3.1.0` (commit `90627b7`, §7).

## 1. Qué es

ICdetector 3.0.0 y 3.1.0 escriben la copia desde Ajustes. Es un ZIP con tres entradas:

| Entrada | Contenido |
|---|---|
| `manifest.json` | `format` (1), `appId`, `appVersion` (`3.0.0+commit`), `schemaVersion`, `createdAtUtc`, `databaseSha256`, `databaseBytes` |
| `icdetector_history.db` | La base SQLite completa (esquema 25 en 3.0.0, 26 en 3.1.0), consolidada antes de copiarla: checkpoint del WAL y modo de diario `DELETE`, en un solo archivo |
| `settings.json` | Ajustes de la app. CellCase solo cuenta sus claves; no los usa para analizar |

La copia contiene **lo mismo que la base de ICdetector**, no las exportaciones. Las exportaciones (historial, Radio,
Topología, Geometría, Stable-Site y casos forenses) se calculan a partir de la base; CellCase hace ese cálculo con
las mismas reglas (§5).

## 2. Qué comprueba CellCase antes de leer

- Tamaño de la copia (512 MiB), número de entradas (16), nombres y entradas repetidas, ratio de compresión (200) y
  tamaño descomprimido de la base (1 GiB) y de los textos (1 MiB). Las entradas que ICdetector no escribe se
  enumeran y no se leen.
- Manifiesto: `appId` de ICdetector, formato 1 y esquema 25 o 26 (`IcdBackupFormat.SUPPORTED_SCHEMAS`). Un esquema
  20–24 es de una beta de 3.0; uno mayor que 26 es de una versión posterior a la 3.1.0 que CellCase aún no conoce. En los dos casos **no se interpreta** y se explica por qué.
- La base se descomprime a una carpeta temporal privada (en la app, la caché) y se calcula su SHA-256 mientras se
  escribe. Si no coincide con el manifiesto, o el tamaño no coincide, se analiza igualmente y queda marcado en el
  informe. La base temporal **se borra siempre**, también si hay error o se cancela.
- Cabecera SQLite válida, `user_version` igual al esquema del manifiesto, aviso si la base estaba en modo WAL.

## 3. Cómo se lee la base

Con un lector SQLite propio de solo lectura (`SqliteFile.kt`), sin bibliotecas nativas y sin cargar la base en
memoria: páginas de tabla interiores y hoja, páginas de desbordamiento, columnas añadidas con `ALTER TABLE` (toman su
valor por defecto con su afinidad), alias de `rowid`, texto UTF-8 y UTF-16. Rechaza las tablas `WITHOUT ROWID`.
Comprueba ciclos, profundidad y rangos de página; un registro no puede pasar de 16 MiB. Las conversiones de tipo
reproducen las de Android (`Cursor.getString` sobre un REAL, `getLong` sobre un texto, `NULL` → 0), porque de ellas
depende el texto exacto de las exportaciones.

## 4. Qué se importa

Antes de abrir el ZIP se lee solo su final y su directorio central, con un tope de 1 MiB (D-059):
`java.util.zip.ZipFile` reserva de golpe el tamaño que declara el EOCD, y un archivo manipulado agotaba la memoria.

Cada exportación obtenida de la base es una **parte** de la importación de la copia (`copia.icdbackup › history.csv`,
`… › topology.zip`, etc.) y pasa por **su mismo lector y sus mismos límites** que si se hubiera importado el archivo
exportado: filas por tabla, tamaño, y el presupuesto de historial del expediente (D-054). Solo se crean las partes
cuyas tablas tienen filas. Lo que se conserva como original es la copia, con su huella; las partes constan en el
informe (tabla de archivos, integridad y anexo), con el aviso de que se han reconstruido.

El límite de bytes de cada parte se aplica **mientras** se leen sus tablas y se escribe el CSV, no después de montarlo
en memoria (D-060, D-063): el historial, el de un historial exportado (leyendo como mucho el doble); las demás partes,
32 MiB leídos de sus tablas y después el límite de su archivo exportado. La reconstrucción consulta la cancelación dentro de sus bucles.

Los casos forenses se construyen de uno en uno, al leerlos. Un caso cuyo `payload_json` suma más de lo que cabe
descomprimido en un ZIP exportado (`ZipLimits.maxTotalBytes`, 8 MiB) no se carga: queda como parte «demasiado
grande» y el resto de la copia se importa (D-056).

Dos datos no viajan en la copia y quedan vacíos: `ExportDevice` y `ExportAndroid` (los escribe el teléfono al
exportar). La hora de exportación es la de la copia (`createdAtUtc`).

Además, la copia aporta lo que ninguna exportación tiene: el número de filas de cada tabla y la madurez de las
referencias del perfil local (§5 de docs/local-profile.md).

## 5. Lógica reproducida de ICdetector (autorizada, D-052)

Para que la copia dé exactamente las mismas exportaciones que ICdetector 3.0.0, `Icd30Exports.kt` reproduce:

| Qué | Origen en ICdetector `v3.0.0` (`app/src/main/java/com/alexisgordr/icdetector/…`) |
|---|---|
| Historial CSV (54 columnas) y Radio (`TimestampUtc,State,…`) | `utils/ExportUtils.kt` |
| Protección de textos contra fórmulas | `utils/CsvText.kt` |
| Casos forenses (ZIP, `case.json`, CSV, `trust_site.csv`, `mergeLogWindows`) | `forensics/ForensicExporter.kt` |
| Topología | `forensics/TopologyExporter.kt` |
| Geometría | `forensics/GeometryExporter.kt` |
| Stable-Site y `PRIVACY.txt` | `forensics/StableSiteExporter.kt`, `forensics/StableSiteExportNote.kt` |
| Madurez de las referencias de Stable-Site | `core/StableSite.kt` (`StableSiteMaturityPolicy`) |
| Esquema de la base | `storage/CellDbHelper.kt` |
| Manifiesto y consolidación de la base | `backup/BackupManifest.kt`, `backup/BackupArchive.kt`, `backup/AndroidDatabaseOps.kt` |

También se reproduce el comportamiento de Android del que dependen los textos: `org.json` (tipos de número,
`toString(2)`, barra escapada), `String.format` con redondeo HALF_UP y el orden de SQLite en los empates de
`ORDER BY` con índice.

## 6. Cómo se ha comprobado

`tools/synth/generate_icd30.py` crea tres copias sintéticas (base nueva, base actualizada desde versiones anteriores
con columnas añadidas, páginas libres y desbordamiento, y copia vacía) y calcula sus exportaciones con SQLite real.
Las pruebas (`Icd30ExportsTest`) comprueban que CellCase obtiene **los mismos bytes** en las 102 entradas, y
`BackupImportTest` que importar la copia y las ocho exportaciones da el mismo análisis, en cualquier orden.
`tools/synth/verify_icd30_against_source.py` contrasta cabeceras, entradas, claves de `case.json`, nota de
privacidad y columnas del esquema con el código de ICdetector.

**Límite**: todo lo anterior es con datos sintéticos. Falta contrastar una copia real con las exportaciones que haga
el mismo teléfono en el mismo momento.

## 7. Esquema 26 (ICdetector 3.1.0 y 3.1.1, D-057, D-064)

ICdetector 3.1.0 (`versionCode` 40, `appVersion` `3.1.0+90627b7`) escribe el mismo ZIP con el mismo `format` (1);
solo cambia la base. `SchemaV26` (`storage/CellDbHelper.kt`) añade a `history` tres columnas con `ALTER TABLE`:

| Columna | Tipo | Exportación |
|---|---|---|
| `churn_cells_15m` | `INTEGER` (puede ser `NULL`) | `ChurnCells15m` |
| `churn_changes_15m` | `INTEGER` (puede ser `NULL`) | `ChurnChanges15m` |
| `churn_motion` | `TEXT` (`STILL`, `MOVING`, `UNKNOWN` o `NULL`) | `ChurnMotion` |

Qué hace CellCase:

- Acepta los esquemas 25 y 26. Los mensajes (es/en) dicen qué versiones de ICdetector escriben cada uno, y que un
  esquema mayor que 26 es de una versión posterior a la 3.1.0.
- El historial reconstruido tiene las 57 columnas de 3.1.0 **si la tabla `history` tiene las tres columnas del
  churn**, y las 54 de 3.0.0 si no. Se decide por las columnas de la tabla, no por el número de esquema: una base
  de esquema 25 actualizada a mano o una de esquema 26 a la que le faltara alguna columna dan el historial que
  corresponde a sus columnas. Un `NULL` se exporta vacío, como en ICdetector (`optInt`).
- Una base de 3.0.0 actualizada a 3.1.0 tiene filas antiguas (sin churn: columnas vacías) y nuevas. Las filas
  conservan su `AppVersion`, que es lo que usa el análisis para separarlas (verificación retirada, cortes de dataset;
  docs/analysis-rules.md y docs/churn.md).
- El resto de exportaciones (Radio, Topología, Geometría, Stable-Site y casos forenses) no cambia de formato. El
  caso forense de 3.1.0 ya no lleva `latencyState` en `payload_json` y su columna `latency_state` queda vacía; se
  reconstruye igual.

Comprobación: `tools/synth/generate_icd31.py` crea una copia de esquema 26 con filas de 3.0.0 y de 3.1.0 (una base
de 3.0 actualizada con `ALTER TABLE`, como hace ICdetector), sus exportaciones con SQLite real y un caso forense de
3.1.0 sin latencia. `Icd31CompatTest` comprueba que CellCase obtiene los mismos bytes, y
`tools/synth/verify_icd31_against_source.py` contrasta cabecera, columnas del esquema, `NotEvaluatedHeuristics` y
claves del caso forense con el código de ICdetector `v3.1.0`. Igual que en §6: solo con datos sintéticos.

**ICdetector 3.1.1** (`versionCode` 41, etiqueta `v3.1.1`, commit `8ec6f29`): mismo esquema 26 y mismas exportaciones que
3.1.0. Entre las dos etiquetas no cambia ningún archivo de exportación, de copia ni de esquema (`utils/ExportUtils.kt`,
`forensics/`, `backup/`, `storage/CellDbHelper.kt`); solo arreglos de la app. CellCase la lee igual; lo único que conoce
de ella es su `versionCode` (41), que escribe en el `case.json` de los casos forenses reconstruidos.
