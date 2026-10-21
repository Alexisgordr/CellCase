# Inventario de exportaciones de ICdetector

> **Fase 1 de CellCase.** Documento elaborado leyendo el código que escribe los archivos, no el README.
> Revisión del repositorio de referencia: `ICdetector-main` (versión `2.10.5`, `versionCode 33`,
> esquema de base de datos `19`; `app/build.gradle.kts` y `CellDbHelper.kt:65`).
>
> No se ha dispuesto de exportaciones reales. Todo lo descrito se deduce del código; las muestras
> de `samples/synthetic/` son **sintéticas** y solo demuestran conformidad con ese código.

## 0. Convenciones de este documento

- `IC/` abrevia `app/src/main/java/com/alexisgordr/icdetector/` del repositorio de ICdetector.
- Las referencias se escriben `archivo:línea` (función). Las líneas corresponden a la revisión analizada.
- **Original**: valor leído del sistema (Android/módem/GNSS) y guardado sin transformar.
  **Derivado**: calculado por ICdetector a partir de originales (puntuaciones, conversiones, bandas).
  **Agregado**: contador o resumen de muchas observaciones; no contiene los eventos individuales.
- «Hora local del dispositivo» significa `SimpleDateFormat` sin zona explícita, que en Android usa
  la zona por defecto del teléfono en el momento de escribir. **No queda registrada la zona.**

### 0.1 Identidad celular completa

En todo el código la identidad completa es la cadena

```
MCC-MNC-TAC-CID-RADIO
```

`IC/models/CellData.kt:99` (`CellData.identityKey`) y `IC/models/HistoryRecord.kt:90`
(`HistoryRecord.identityKey`). `RADIO` ∈ {`GSM`, `UMTS`, `LTE`, `NR`, `UNKNOWN`}
(`IC/models/RadioTech.kt`), tomado de la **clase** de `CellInfo`, no de la etiqueta de la barra de estado
(`IC/telephony/CellParser.kt:41-140`, `parseCell`).

Matices que afectan a cualquier relación entre archivos:

| Campo | Contenido real según `CellParser.parseCell` |
|---|---|
| TAC | En LTE/NR es TAC; en **GSM y UMTS es el LAC** (`id.lac`), pero se guarda en la misma columna. |
| CID | LTE: `ci` (28 bits). NR: `nci` (36 bits, cabe en `Long`). UMTS: `cid`. GSM: `cid`. `"N/A"` si no disponible (`valOrNa`, `CellParser.kt:231-232`). |
| MCC/MNC | Cadenas. Solo la celda registrada hereda el PLMN del operador si el módem no lo da; las vecinas sin identidad quedan `"N/A"` (`identityOrFallback`, `CellParser.kt:177-185`). |
| PCI | LTE/NR: PCI. **UMTS: PSC** (`id.psc`) en la misma columna. GSM: no se rellena. |
| ARFCN | LTE: EARFCN; NR: NR-ARFCN; UMTS: UARFCN; GSM: ARFCN. |

Consecuencias:

- Una vecina sin identidad produce identidades como `N/A-N/A-N/A-N/A-LTE`. **Muchas vecinas distintas
  comparten esa misma cadena.** No es un identificador.
- El PCI y el EARFCN de LTE **no se filtran** contra `Int.MAX_VALUE` en `parseCell`
  (`CellParser.kt:66`: `arfcn = id.earfcn, pci = id.pci`). En los archivos puede aparecer
  `2147483647` como centinela de «no disponible». Lo mismo con `dbm` en vecinas (solo el historial
  descarta `dbm` = `-999` o `Int.MAX_VALUE`, `IC/service/ObservationPersistenceController.kt:89`).
- `DBM` no mide lo mismo en todas las tecnologías: en LTE es `CellSignalStrengthLte.getDbm()`, en NR
  `ssRsrp` (o `getDbm()` si no hay RSRP), en UMTS y GSM `getDbm()` (`CellParser.kt:45,76,121,130`).
  La unidad es dBm, pero no es la misma magnitud física en todos los radios. **Duda abierta Q-07.**

### 0.2 Técnicas de escritura CSV (importa al leerlos)

| Exportación | Comillas | Función |
|---|---|---|
| Historial, Radio | Solo si el campo tiene `,` `"` CR o LF (RFC 4180) | `IC/utils/ExportUtils.kt:150` (`csvEscape`) |
| Stable-Site | Igual, solo si hace falta | `IC/storage/CellDbHelper.kt:1022` (`csv` local) |
| Caso forense, Topología, Geometría | **Todos** los campos entre comillas, también los numéricos y los vacíos (`""`) | `ForensicExporter.kt:106`, `TopologyExporter.kt:112`, `GeometryExporter.kt:87` |

- Saltos de línea: `\n` (`append("\n")`/`appendLine`).
- Codificación: UTF-8 (`OutputStreamWriter(…, UTF_8)` en historial/radio; `String.toByteArray()`
  en los ZIP, cuyo juego de caracteres por defecto en Android es UTF-8).
- Sin BOM. Sin separador decimal localizado: los `Double` se escriben con `toString()` (punto), y
  `AnomalyConfidence`/`trust_ratio` con `Locale.ROOT` (`ExportUtils.kt:90`, `TopologyExporter.kt:63`).
- Un lector debe aceptar campos entrecomillados con comillas dobladas y saltos de línea internos
  (las razones de heurísticas son texto libre).

### 0.3 Estructura de los ZIP

`ZipOutputStream` con una entrada por archivo, todas en la raíz (sin carpetas), escritas en orden
fijo. `ZipEntry` recibe la hora del momento de exportar, por lo que **dos exportaciones del mismo
contenido producen ZIP con bytes distintos**: el hash del ZIP completo no sirve para detectar
duplicados; hay que comparar el contenido de cada entrada.

### 0.4 Cómo distinguir cada exportación (detección de formato)

El nombre del archivo lo propone ICdetector pero el usuario puede cambiarlo en el selector de
Android, así que **no es fiable**. La detección debe basarse en el contenido:

| Formato | Firma | Nombre sugerido (solo indicativo) |
|---|---|---|
| Historial | CSV cuya primera línea empieza por `Timestamp,NetType,CID,MNC,TAC,MCC,DBM,` | `icdetector_history_<epochms>.csv` (`IC/ui/HistoryScreen.kt:316`) |
| Radio (estado de servicio) | CSV cuya cabecera es exactamente `SERVICE_STATE_CSV_HEADER` (`TimestampUtc,State,…`) | `icdetector_service_state_<epochms>.csv` (`IC/ui/RadioScreen.kt:287`) |
| Caso forense | ZIP con `case.json` + `timeline.csv` + `SHA256SUMS.txt` | `ICD-AAAA-MM-DD-NNNN.zip` o `ICD-OBS-…zip` (`HistoryScreen.kt:725`) |
| Topología | ZIP con `topology.graphml` + `transitions.csv` | `ICD-topology-<epochms>.zip` (`HistoryScreen.kt:515`) |
| Geometría | ZIP con `geometry.graphml` + `metadata.json` con `"feature":"MOBILITY_FAMILIARITY"` | `ICD-geometry-<epochms>.zip` (`IC/ui/GeometryScreen.kt:129`) |
| Stable-Site | ZIP con `sites.csv` + `site_cells.csv` + `motion.csv` + `shadow_episodes.csv` | `ICD-stable-site-<epochms>.zip` (`HistoryScreen.kt:565`) |

Atención: **topología y caso forense contienen ambos un `cells.csv` con cabeceras distintas**, y
**topología y geometría contienen ambos un `metadata.json`** con `schemaVersion` 1 y 17
respectivamente. Hay que identificar el ZIP por el conjunto de entradas, no por un solo archivo.

El `<epochms>` del nombre sugerido es la hora de pulsar «exportar» (no la del contenido) y puede
haberse perdido si el usuario renombró el archivo.

---

## 1. Historial de observaciones (CSV)

### 1.1 Origen en el código

- Interfaz: `IC/ui/HistoryScreen.kt:270-319`. Botón *EXPORT CSV* → `CreateDocument("text/csv")` →
  `ExportUtils.exportToCsv(…, streamRecords = dbHelper::forEachRecord)` en `Dispatchers.IO`.
- Escritura: `IC/utils/ExportUtils.kt:49` (`exportToCsv`), fila en `ExportUtils.kt:73` (`csvRow`),
  cabecera `ExportUtils.kt:13` (`CSV_HEADER`).
- Lectura de la base: `IC/storage/CellDbHelper.kt:1870` (`forEachRecord`), mapeo de columnas
  `CellDbHelper.kt:1589` (`readRecord`).
- Escritura de cada fila en la base: `CellDbHelper.kt:1362` (`logConnection`), invocado solo desde
  `IC/service/ObservationPersistenceController.kt:54` (`insert`).

### 1.2 Alcance

- **Historial acumulado completo retenido**, no un incidente ni una selección. El filtro de búsqueda
  de la pantalla no afecta a la exportación (`forEachRecord` hace `SELECT * … ORDER BY id DESC`).
- Retención: filas con `timestamp` anterior a 120 días se borran en cada arranque del servicio
  (`CellDbHelper.kt:1739`, `pruneOldRecords`, `DEFAULT_RETENTION_DAYS = 120`). La comparación de
  borrado es **textual** sobre la cadena de hora local.
- **Una fila = la celda servidora en un momento concreto**, nunca vecinas. Se escribe:
  - en cada handover (`recordHandover`),
  - periódicamente cada 5 min con pantalla encendida o 15 min apagada (`recordPeriodicIfDue`,
    `ObservationPersistenceController.kt:93-94`),
  - al confirmar una alarma (`recordConfirmedAlarm`), con `FailedHeuristics` = razón o
    `"ALARMA CONFIRMADA"`.
  Por tanto es un **muestreo**, no un registro continuo. Un handover y una alarma en el mismo segundo
  producen dos filas casi idénticas legítimas.
- Filas excluidas en origen: `CID = "N/A"`, `dbm = -999` o `dbm = Int.MAX_VALUE`
  (`ObservationPersistenceController.kt:89`, `isRecordable`).
- Orden en el archivo: **de la más reciente a la más antigua** (`ORDER BY id DESC`).
- Integridad de la exportación: el número de filas escritas se compara con el `COUNT(*)` de la misma
  transacción; si no coinciden la exportación falla (`ExportUtils.kt:31`, `verifyRowCount`). Esta
  comprobación **no queda registrada en el archivo**.

### 1.3 Columnas

Cabecera exacta (35 columnas, versión ≥ 2.10.4):

```
Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio,ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName,SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming
```

| # | Columna | Tipo / valores | Unidad | Ausente | Naturaleza | Notas (código) |
|---|---|---|---|---|---|---|
| 1 | Timestamp | `yyyy-MM-dd HH:mm:ss` | hora local del dispositivo, resolución 1 s | nunca vacío | original (momento de **inserción**) | `logConnection`, `CellDbHelper.kt:1396-1397`. Sin zona. |
| 2 | NetType | texto (p. ej. `4G`, `5G`) | — | puede ser cualquier cadena | original de presentación | Viene de `TelephonyDisplayInfo`: etiqueta del icono, **no** tecnología (`HistoryRecord.kt:61-67`). |
| 3 | CID | entero decimal en texto | — | no (filtrado) | original | Ver §0.1. |
| 4 | MNC | texto | — | `N/A` posible | original | Conservar ceros a la izquierda: es texto. |
| 5 | TAC | entero en texto | — | `N/A` posible | original | LAC en GSM/UMTS. |
| 6 | MCC | texto | — | `N/A` posible | original | |
| 7 | DBM | entero | dBm | no | original | Magnitud depende de la tecnología (§0.1). |
| 8 | Verified | `PENDING` `VERIFIED` `NOT_FOUND` `REJECTED` `ERROR` | — | — | **derivado externo, mutable** | `IC/models/VerificationStatus.kt`. Puede reescribirse después de insertar (ver §1.5). |
| 9 | SecurityScore | entero 0–100 | puntos (no calibrado) | — | derivado | `ThreatAnalyzer.kt:607` (`coerceIn(0,100)`). 100 = ninguna penalización. |
| 10 | FailedHeuristics | texto libre en español | — | `OK` si no falló nada | derivado | Razones unidas con ` \| `; prefijo `[sub-umbral] ` si fallaron sin alcanzar alarma (`IC/models/HistoryRecord.kt:15`, `IC/core/TemporalConfidence.kt:123`); `ALARMA CONFIRMADA` como valor por defecto en alarmas. |
| 11-12 | Lat, Lon | decimal | grados WGS84 del **dispositivo** | vacío = sin fijación | original (con salvedades §1.5) | Nunca la antena desde v2.1. |
| 13 | PCI | entero | — | vacío; posible `2147483647` | original | PSC en UMTS. |
| 14 | ARFCN | entero | número de canal | vacío; posible `2147483647` | original | |
| 15 | RSRQ | entero | dB | vacío | original | Solo LTE/NR. |
| 16 | SINR | entero | dB | vacío | original | LTE: `rssnr`; NR: `ssSinr`. |
| 17 | AnomalyConfidence | decimal con 1 cifra, 0.0–95.0 | «%» no calibrado | `0.0` también si no se calculó | derivado | `IC/core/BayesianScorer.kt:191`. **No es una probabilidad** (`HistoryRecord.kt:34-40`). Filas de esquemas antiguos sin la columna en BD exportan `0.0` (`readRecord`, `CellDbHelper.kt:1605-1606`). |
| 18-19 | ApiLat, ApiLon | decimal | grados (antena según WiGLE/OpenCellID) | vacío | derivado externo, mutable | Se rellenan en `updateVerificationStatus`. Siempre vacías desde 3.1.0 (consulta externa retirada, §1.9). |
| 20 | TA | entero | crudo, unidad en TAUnit | vacío = no reportado | original | `logConnection` solo lo guarda si no es nulo. |
| 21 | TAUnit | `LTE_INDEX` `GSM_INDEX` `NR_RAW` `STUB_ZERO` `UNKNOWN` | — | vacío si TA vacío | derivado (procedencia) | `IC/models/TimingAdvanceUnit.kt`. |
| 22 | TAMeters | entero | metros | vacío si no hay conversión defendible | derivado | `LTE_INDEX`×78, `GSM_INDEX`×554, resto nulo (`TimingAdvanceUnit.toMeters`). Se recalcula **al exportar** con la regla vigente. |
| 23 | Radio | `GSM` `UMTS` `LTE` `NR` `UNKNOWN` | — | `UNKNOWN` en filas previas a v2.1 | original | |
| 24 | ServingConnection | `PRIMARY_SERVING` `SECONDARY_SERVING` `NONE` `UNKNOWN` | — | vacío en filas previas a 2.10.4 | original | `IC/models/RadioContext.kt:21`. |
| 25 | BandwidthKHz | entero | kHz | vacío | original | Solo LTE. |
| 26 | Bands | enteros separados por `;` | número de banda 3GPP | vacío | original (API 30+) | |
| 27 | AdditionalPlmns | PLMN separados por `;` | — | vacío | original | |
| 28 | CsgIndicator | `1`/`0` | — | vacío = no disponible | original | `1` sugiere femtocelda; no es anomalía por sí. |
| 29 | CsgIdentity | entero | — | vacío | original | |
| 30 | CsgName | texto | — | vacío | original | Puede contener comas → entrecomillado. |
| 31 | SecondaryCarriers | `RADIO:ARFCN:PCI` separados por `;`, `?` si falta | — | vacío | original resumido | `SecondaryCarrier.compact` (`RadioContext.kt:65`). **No incluye ancho de banda** aunque el modelo lo tenga. |
| 32 | ServiceState | `IN_SERVICE` `OUT_OF_SERVICE` `EMERGENCY_ONLY` `POWER_OFF` `UNKNOWN` | — | vacío | original (último conocido) | Último `ServiceState` en memoria al insertar, no necesariamente simultáneo. |
| 33 | NetworkOperator | PLMN `MCCMNC` | — | vacío | original | |
| 34 | SimOperator | PLMN | — | vacío | original | |
| 35 | NetworkRoaming | `1`/`0` | — | vacío | original | |

Booleanos del historial: `1`/`0`/vacío (`ExportUtils.kt:101,108`).

### 1.4 Versiones de esquema

- **El CSV no lleva versión, ni metadatos, ni hash.** La versión solo puede inferirse por las columnas:
  - 35 columnas → v2.10.4 o posterior (esquema BD 19).
  - 23 columnas (hasta `Radio`) → v2.1 a v2.10.3 (`tools/check_export.py`, `EXPECTED_COLUMNS_V21`).
  - Sin `AnomalyConfidence` → anterior a v2.1. `check_export.py:162,286` menciona una columna antigua
    `ThreatProb`. **El repositorio no contiene el exportador anterior a v2.1**: su orden de columnas
    exacto no puede verificarse (Q-01).
- No se registra la versión de la app, el dispositivo, la zona horaria ni la hora de exportación.
- Una fila escrita por una versión antigua y exportada con la actual lleva las columnas nuevas vacías:
  vacío significa «no se recogía», no «no había» (`CellDbHelper.kt:107-108`).

### 1.5 Fechas y mutabilidad

- `Timestamp` es el momento de **inserción en la base**, que ocurre en una corrutina de IO lanzada
  tras el ciclo de análisis (`ObservationPersistenceController.kt:39-51`): diferencia de milisegundos
  a segundos respecto al instante de la medida.
- Hora local sin zona: un historial que cruza un cambio de horario (último domingo de octubre en
  España) tiene **una hora repetida y ambigua**; en marzo, una hora inexistente. Si el teléfono
  viajó entre zonas, el desplazamiento cambia sin aviso.
- Valores que **pueden cambiar después de escribirse la fila**:
  - `Verified`, `ApiLat`, `ApiLon`: `updateVerificationStatus` (`CellDbHelper.kt:1548`) actualiza solo
    la fila más reciente de esa identidad cuando responde la API.
  - `Lat`, `Lon`: `updateNullCoordinates` (`CellDbHelper.kt:1891`) rellena la última fila de la celda
    si estaba vacía cuando llega una fijación posterior. **La coordenada puede ser posterior a la
    medida** y el archivo no lo indica.
  - `Lat`, `Lon` de inserción proceden de `location()` (última posición conocida del servicio); el
    CSV no exporta precisión ni antigüedad de la fijación.
- Consecuencia: dos exportaciones del mismo historial hechas en días distintos pueden diferir en esas
  columnas para las mismas filas.

### 1.6 Identificadores

- **No hay identificador de fila** (el `id` de SQLite no se exporta). La procedencia de una fila
  solo puede expresarse como número de línea del archivo y contenido.
- La identidad de celda se reconstruye con `MCC-MNC-TAC-CID-Radio`. Con `Radio = UNKNOWN` (filas
  previas a v2.1) la identidad es incompleta.

### 1.7 Hashes

Ninguno.

### 1.8 ICdetector 3.0.0 (54 columnas; D-053)

Fuente: `utils/ExportUtils.kt` y `utils/CsvText.kt` de `v3.0.0`. Las 35 columnas anteriores no cambian de nombre ni
de orden; se añaden 19 al final:

| Columnas | Contenido | En CellCase |
|---|---|---|
| `ObservedAtUtc` | Instante UTC de la observación (ISO-8601 con milisegundos) | Instante exacto (truncado a segundos) y desfase de la hora local; resuelve la hora repetida del cambio de horario y avisa si no cuadra con la zona declarada |
| `NotEvaluatedHeuristics` | Heurísticas que no se pudieron evaluar en esa fila | Se conserva |
| `GpsAccuracyM` | Precisión de la posición | Reescribible al fusionar (`merge/4`) |
| `AppVersion` | Versión de ICdetector que escribió la fila | Se conserva |
| `ExportDevice`, `ExportAndroid` | Teléfono y Android **que exportan** (iguales en todas las filas) | Datos de la exportación, no de la fila; no se comparan al fusionar |
| `LocationMode` | Modo de localización | Se conserva |
| `Profile*` (10) | Perfil local de la fila | docs/local-profile.md |
| `RowId`, `ObservationId` | Identificador de fila de la base e identificador de la observación | Una `ObservationId` repetida es duplicado (DUP_SAME_OBSERVATION) |

Esto corrige, para 3.0, lo dicho en §1.4–§1.6: sí hay versión de la app, dispositivo e identificador de fila, y la
hora tiene zona. Las filas grabadas antes de actualizar a 3.0 llevan las columnas nuevas vacías.

**Protección contra fórmulas**: 3.0 antepone un apóstrofo a los textos que empiezan por `=`, `+`, `-`, `@` (y sus
formas de ancho completo), `'` o un carácter de control, salvo los números. CellCase lo quita solo si el resto,
protegido de nuevo, da exactamente el campo leído (`CsvGuard.unguard`), y lo anota (TEXT_GUARD_REMOVED).


### 1.9 ICdetector 3.1.0 (57 columnas; D-057)

Fuente: `utils/ExportUtils.kt`, `storage/CellDbHelper.kt` (`SchemaV26`), `docs/history-csv-columns.md` y
`CHANGELOG.md` de `v3.1.0` (`versionCode` 40, `BUILD_COMMIT` `90627b7`; `AppVersion` = `3.1.0+90627b7`). Las 54
columnas de 3.0 no cambian de nombre ni de orden; se añaden 3 al final:

| Columna | Contenido | En CellCase |
|---|---|---|
| `ChurnCells15m` | Identidades de celda distintas que han servido al teléfono en los 15 min anteriores (H17) | Entero ≥ 0; vacío = sin dato |
| `ChurnChanges15m` | Cambios de servidora en esos 15 min | Entero ≥ 0; vacío = sin dato |
| `ChurnMotion` | Movimiento del teléfono: `STILL`, `MOVING` o `UNKNOWN` (sin fix GPS de 60 s o menos) | Uno de los tres; vacío = sin dato |

La cuenta se reinicia tras una pérdida de servicio o un hueco de más de 2 minutos. Un valor no válido (negativo, no
entero o un movimiento desconocido) es un **aviso con su procedencia**, no un rechazo del archivo (`history-csv/1.2.0`).
Un historial con solo una o dos de las tres columnas es un 3.x parcial, con aviso. Las filas grabadas antes de
actualizar a 3.1.0 llevan las tres vacías.

Cambios de **significado** en columnas que ya existían (sin cambiar el formato):

- `Verified`, `ApiLat`, `ApiLon`: la verificación externa (OpenCellID) se retiró en 3.1.0. Las filas nuevas llevan
  siempre `PENDING` y coordenadas vacías: **no hay nada pendiente**. CellCase clasifica las filas con `AppVersion`
  3.1.0 o posterior como «sin verificación externa (retirada)» y no las cuenta como pendientes ni las compara con
  otras fuentes (`cellcase-rules/2`, `cross/3`).
- `NotEvaluatedHeuristics`: formato `Hn` o `Hn(motivo)` separados por `;`, o `NONE`. Motivos nuevos de 3.1.0:
  `H12(retirada)` (en todas las filas: la regla de latencia + RF se retiró), `H17(sombra)` (en todas las filas: el
  cambio de celdas se registra pero no se evalúa), `H6(ta_gsm)`, `H6(ta_stub)`, `H6(ta_sin_verificar)` y
  `H11(fix_impreciso)`. Los dos primeros **no son abstenciones por falta de datos**; un `H12` sin motivo de una fila
  de 3.0 sí lo es (docs/analysis-rules.md, COL-RULES/1).
- **Cortes de dataset** (CHANGELOG 3.1.0): `H6`, `TAUnit`, `H11`, `H12`, `H14`, `SecurityScore`,
  `AnomalyConfidence` y la confianza local de celda (y con ella las alarmas) se calculan de otra forma desde 3.1.0.
  Un historial con filas de antes y de después no es comparable en esos campos; el informe técnico lo dice
  (QUAL-CUT/1).

---

## 2. Radio: eventos de estado de servicio (CSV)

### 2.1 Origen

- Interfaz: `IC/ui/RadioScreen.kt:174-187` → `ExportUtils.exportServiceStateCsv(context, uri,
  dbHelper.getServiceStateEvents())`.
- Escritura: `IC/utils/ExportUtils.kt:131` (`exportServiceStateCsv`), fila `ExportUtils.kt:112`
  (`serviceStateCsvRow`), cabecera `ExportUtils.kt:20`.
- Base: `CellDbHelper.kt:415` (`insertServiceStateEvent`), `CellDbHelper.kt:438`
  (`getServiceStateEvents`, sin límite en la exportación).

### 2.2 Alcance

- **Historial acumulado** de **cambios** de estado de servicio, no lecturas periódicas: un evento se
  guarda cuando cambia `changeSignature` (`RadioContext.kt:147-151`), que excluye a propósito canal y
  anchos de banda. Si solo cambia el canal no hay fila.
- Retención: misma antigüedad que el historial (120 días) y además **como máximo los 5 000 eventos
  más recientes** (`CellDbHelper.kt:138`, `pruneOldRecords` líneas 1757-1765). Un módem inestable
  puede vaciar el principio del periodo antes de los 120 días.
- Orden en el archivo: **del más antiguo al más reciente** (`sortedBy { it.timestampMs }`), al
  contrario que el historial.
- `getServiceStateEvents` captura cualquier excepción y devuelve lista vacía
  (`CellDbHelper.kt:468-470`): **un archivo con solo la cabecera puede significar fallo de lectura**,
  no ausencia de eventos. No hay comprobación de recuento como en el historial.

### 2.3 Columnas

```
TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source
```

| Columna | Tipo | Unidad / formato | Ausente | Notas |
|---|---|---|---|---|
| TimestampUtc | ISO-8601 `Instant.toString()` | UTC con `Z`; milisegundos si no son cero, si no se omiten | nunca | `ExportUtils.kt:113`. |
| State | `IN_SERVICE` `OUT_OF_SERVICE` `EMERGENCY_ONLY` `POWER_OFF` `UNKNOWN` | — | — | Valor desconocido en BD se lee como `UNKNOWN` (`CellDbHelper.kt:449`). |
| DataRegistered / VoiceRegistered / Searching | `1`/`0` | — | vacío = no disponible (API < 30) | «nulo significa no disponible, nunca falso» (`RadioContext.kt:106-108`). |
| Roaming | `1`/`0` | — | nunca vacío (falso por defecto) | |
| NetworkOperator | PLMN `MCCMNC` | — | vacío | |
| NetworkName | texto | — | vacío | Puede llevar comas. |
| SimOperator | PLMN | — | vacío | |
| ManualSelection | `1`/`0` | — | nunca vacío | |
| ChannelNumber | entero | número de canal | vacío | |
| CellBandwidthsKHz | enteros separados por `;` | kHz | cadena vacía | |
| DataNetwork / VoiceNetwork | nombre (`LTE`, `NR`, `UMTS`… o `TYPE_n`) | — | vacío | `NetworkTypeNames.name` (`RadioContext.kt:158`). |
| Source | `CALLBACK` / `POLL` | — | — | Valor desconocido se lee como `POLL`: **la procedencia puede estar sustituida** (`CellDbHelper.kt:462`). |

### 2.4 Versión, identificadores, hashes

Sin versión, sin metadatos, sin identificador de evento, sin hash. Existe desde v2.10.4.
No contiene identidades celulares: se relaciona con el resto solo por tiempo y PLMN.

---

## 3. Caso forense (ZIP)

### 3.1 Origen

- Interfaz: `IC/ui/HistoryScreen.kt:700-726` (`ForensicPanel`): aviso de privacidad → `CreateDocument("application/zip")`
  → `ForensicExporter.export(context, dbHelper, selected, uri)`.
- Escritura: `IC/forensics/ForensicExporter.kt:15-103` (`export`).
- Muestras: `CellDbHelper.kt:1218` (`getForensicSamples`, `ORDER BY id`).
- Contenido de cada muestra: `IC/forensics/ForensicRecorder.kt:197` (`payload`).
- Apertura y cierre de casos: `ForensicRecorder.kt:84` (`observe`), `CellDbHelper.kt:1086`
  (`createForensicCase`).

### 3.2 Alcance

- **Un incidente**: un caso por exportación.
- Ventana: hasta 60 s de **prebúfer** anterior a la apertura (máx. 180 muestras), la captura, y 60 s
  posteriores a la recuperación; tope de 30 min (`ForensicRecorder.kt:53-56`). Al alcanzar el tope el
  estado es `INTERRUPTED`.
- Origen (`captureOrigin`): `ALARM` (episodio del detector) o `TRUST_CONTRADICTION` (contradicción del
  perfil local o novedad Stable-Site); un caso de observación puede **promocionarse** a `ALARM` si
  durante la captura empieza un episodio (`ForensicRecorder.kt:157-164`). El código de caso conserva
  entonces el prefijo `ICD-OBS-` aunque el origen exportado sea `ALARM` (Q-05).
- Una muestra por ciclo del detector, con **servidora y vecinas** (a diferencia del historial).
- Retención: casos cuyo `updated_at` supera 120 días se borran enteros; además hay tope de 20 000
  muestras que borra casos cerrados completos (`CellDbHelper.kt:189`, `IC/core/ForensicRetentionPolicy.kt`).

### 3.3 Entradas del ZIP, en este orden

#### `case.json` (`ForensicExporter.kt:22-34`)

| Clave | Tipo | Notas |
|---|---|---|
| schemaVersion | `1` | Versión del formato del caso. |
| caseId | `ICD-AAAA-MM-DD-NNNN` o `ICD-OBS-AAAA-MM-DD-NNNN` | Fecha local del dispositivo; `NNNN` = id SQLite con ceros. **No es único entre dispositivos ni tras reinstalar** (el id reinicia). |
| state | `CAPTURING` `POST_CAPTURE` `READY` `INTERRUPTED` | La interfaz solo ofrece exportar casos `READY` o `INTERRUPTED` (`HistoryScreen.kt:779`); el exportador en sí no lo comprueba. Un archivo con otro estado indicaría otra vía de exportación (Q-04). |
| createdAt, updatedAt | `yyyy-MM-dd HH:mm:ss` | **Hora local sin zona** (`CellDbHelper.kt:1088`). |
| closedAt | igual o `null` | |
| captureOrigin | `ALARM` / `TRUST_CONTRADICTION` | |
| cellIdentity | `MCC-MNC-TAC-CID-RADIO` | Identidad de la servidora al abrir. |
| highestPhase | entero | Fase máxima de confianza temporal. |
| confirmed | booleano JSON | |
| sampleCount | entero | Número de muestras exportadas (calculado al exportar). |
| appVersion, appVersionCode | texto, entero | Versión **que exporta**, no necesariamente la que capturó. |
| device, android | texto | Tomados de la **primera** muestra; `"unknown"` si no hay muestras. |
| integrityNote | texto fijo | «SHA-256 detects later modification; it is not a legal chain-of-custody signature.» |

#### `timeline.csv` (`ForensicExporter.kt:36-47`) — una fila por muestra

```
timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason
```

| Columna | Tipo / unidad | Notas |
|---|---|---|
| timestamp_utc | ISO-8601 UTC (`Instant`) | Reloj de pared: puede saltar si cambia la hora del sistema. |
| elapsed_ms | entero, ms desde arranque | Monótono **dentro de un mismo arranque**; útil para ordenar y medir intervalos. |
| event | `OBSERVATION` `ANOMALY_STARTED` `PHASE_CHANGED` `CONFIRMED` `RECOVERY_STARTED` `TRUST_CONTRADICTION` `TRUST_CONTRADICTION_ON_START` `STABLE_SITE_NEW_SERVING` y combinaciones `<X>_AND_ANOMALY_STARTED` | `ForensicRecorder.kt:95-108`. Las muestras del prebúfer conservan el evento que tuvieron en su ciclo. |
| phase | entero | `optInt`: 0 si falta. |
| score | entero 0–100 | SecurityScore del ciclo. |
| anomaly_confidence | decimal | `optDouble`: `NaN` si falta. |
| cell_identity | identidad completa de la servidora | |
| verified | estado de verificación | |
| latency_state | `N/A` `OK` `ANOMALA` (vacía desde 3.1.0: sonda de latencia retirada, §8c) | `IC/service/NetworkLatencyMonitor.kt:21,53,62`, `MiniICService.kt:1358`. Cadena libre en el tipo: aceptar otros valores como desconocidos (Q-06). |
| gps_lat, gps_lon | grados | **vacío** si no había posición. |
| gps_accuracy_m | metros | Precisión declarada por Android. |
| reason | texto libre | Razón sospechosa del ciclo. |

#### `cells.csv` (`ForensicExporter.kt:49-64`) — servidora y vecinas de cada muestra

```
timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit,connection_state,bandwidth_khz
```

- `role` = `SERVING` (siempre una por muestra) o `NEIGHBOR`.
- Unidades como el historial. `band` es la **banda LTE derivada del EARFCN** por ICdetector
  (`IC/core/BandPlan.kt`), no la reportada.
- `ta` crudo + `ta_unit`. **No hay columna de metros.**
- `connection_state` y `bandwidth_khz` vacíos en muestras anteriores a 2.10.4.
- El payload tiene `bands`, `csgIndicator`, `secondaryCarriers` y `registered`, pero **el exportador
  no los escribe**.
- Vecinas sin identidad: `cid`=`N/A`, `identity` no única (§0.1). Solo `(radio, arfcn, pci)` las
  describe, y no de forma única.

#### `heuristics.csv` (`ForensicExporter.kt:66-75`)

```
timestamp_utc,heuristic_id,name,status,explanation
```

Una fila por heurística por muestra. `heuristic_id` 1–16, `name` en español fijo
(`IC/core/DiagnosticEngine.kt:40-79`), `status` ∈ `PASSED` `FAILED` `NOT_EVALUATED`
(`IC/models/Diagnostics.kt:14`, `IC/models/HeuristicReport.kt:10`),
`explanation` texto libre. **Son resultados del colector, no evidencia primaria.**

#### `capabilities.json` (`ForensicExporter.kt:77-83`)

`{"schemaVersion":1,"observations":[{"timestamp":"<ISO UTC>","values":{"timingAdvance":bool,"rsrq":bool,"sinr":bool,"arfcn":bool,"gps":bool,"hardwareCiphering":…}}]}`
— qué campos traía cada ciclo (`ForensicRecorder.kt:247-251`).

#### `terminal.log` (`ForensicExporter.kt:85-92`)

- Unión **sin duplicados** de las últimas 40 líneas del terminal guardadas en cada muestra.
- Formato de línea `[HH:mm:ss.SSS] TIPO mensaje` en **hora local y sin fecha**
  (`IC/service/MiniICService.kt:142-146`). Al deduplicar se pierde a qué muestra pertenecía cada
  línea. Las líneas pueden ser **anteriores** al prebúfer (cada muestra arrastra 40 líneas).
  Usar solo como contexto.

#### `SHA256SUMS.txt` (`ForensicExporter.kt:94-96`)

Formato `sha256sum`: `<hex>␠␠<nombre>`, una línea por cada entrada anterior (no se incluye a sí mismo).

### 3.4 Qué protegen los hashes

Los calcula el mismo exportador en el mismo momento sobre los bytes que mete en el ZIP. Permiten
detectar que **una entrada cambió después de exportarse respecto a lo que dice `SHA256SUMS.txt`**.
No prueban autoría, momento de captura ni integridad frente a quien modifique a la vez una entrada y
`SHA256SUMS.txt`. No cubren el propio `SHA256SUMS.txt` ni el ZIP. El propio `case.json` y
`MANUAL.md` (§11) lo dicen.

### 3.5 Datos originales, derivados y agregados

- Originales: identidades, dbm, pci, arfcn, rsrq, sinr, ta crudo, GPS, connection_state,
  bandwidth, device/android, timestamps.
- Derivados: event, phase, score, anomaly_confidence, verified, reason, band, ta_unit, diagnósticos,
  capacidades, estado/origen del caso.
- No contiene agregados.

---

## 4. Topología (ZIP)

### 4.1 Origen

- Interfaz: `IC/ui/HistoryScreen.kt:478-516` (`TopologyPanel`): aviso → `TopologyExporter.export(context, transitions, uri)`.
- **La lista `transitions` es la que tiene cargada la pantalla**: `dbHelper.getCellTransitions()`
  sin argumento (`HistoryScreen.kt:94,106`) → `LIMIT 250` por defecto, ordenadas por
  `last_seen_ms DESC` (`CellDbHelper.kt:655`).
- Escritura: `IC/forensics/TopologyExporter.kt:14-102` (`buildFiles`).

### 4.2 Alcance

- **Agregado acumulado**: las 250 rutas `origen → destino` vistas más recientemente. Si hay más, las
  demás **no se exportan** y el archivo no lo indica (Q-02). `cellCount`/`routeCount` de
  `metadata.json` se refieren solo a lo exportado.
- Cada ruta acumula contadores desde que se creó; las rutas sin actividad en 120 días se borran
  (`pruneOldRecords`). **No hay periodo inicial** de los contadores ni eventos individuales.
- La lista se carga al abrir la pestaña; si el servicio sigue registrando, el ZIP puede no reflejar
  los últimos handovers.

### 4.3 Entradas

| Entrada | Cabecera / estructura | Notas |
|---|---|---|
| `cells.csv` | `identity,incoming_routes,outgoing_routes,incoming_handovers,outgoing_handovers,trusted_incoming,trusted_outgoing` | Derivado de `transitions` exportadas. Identidades ordenadas alfabéticamente. |
| `transitions.csv` | `from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc` | `trust_ratio` con 4 decimales (derivado). `last_status` ∈ `PASSED` `FAILED` `NOT_EVALUATED` (estado del **último** handover, `IC/models/HeuristicReport.kt:10`). `last_seen_utc` ISO UTC. |
| `topology.graphml` | GraphML dirigido; nodos `n<i>` con `identity`; aristas con `observations`, `trusted`, `status`, `last_seen` | Mismos datos que `transitions.csv`. |
| `metadata.json` | `schemaVersion:1`, `generatedAtUtc`, `appVersion`, `appVersionCode`, `directedGraph:true`, `cellCount`, `routeCount`, `handoverCount`, `privacyNote`, `integrityNote` | |
| `SHA256SUMS.txt` | igual que el caso forense | Cubre las cuatro entradas anteriores. |

`observations` cuenta handovers observados entre esas dos identidades;
`trusted_observations` los que pasaron la coherencia y eran aptos para aprender
(`CellDbHelper.kt` `recordCellTransition`, llamado desde `IC/service/ServingTransitionTracker.kt:60`).
**Las identidades son de celda servidora**: las transiciones se registran sobre la servidora
antes/después del handover.

### 4.4 Versionado y fechas

`schemaVersion` 1. Horas en UTC. No incluye la zona del dispositivo ni los campos de movilidad
(`trip_count`…) que sí existen en la tabla: esos solo salen en Geometría.

---

## 5. Geometría / familiaridad de movilidad (ZIP)

### 5.1 Origen

- Interfaz: `IC/ui/GeometryScreen.kt:115-130` → `GeometryExporter.export(context, dbHelper, uri)`.
- Escritura: `IC/forensics/GeometryExporter.kt:14-85` (`buildFiles`).
- Datos: `dbHelper.getMobilityGeometrySnapshot()` **con límite por defecto 1 000**
  (`CellDbHelper.kt:682`): hasta 1 000 rutas (por `last_seen_ms` desc) y hasta 1 000 viajes
  (por `started_at_ms` desc). La pantalla usa `limit = 400` para mostrar (`GeometryScreen.kt:140`),
  pero el exportador vuelve a consultar con 1 000.
- Proyección: `IC/models/MobilityGeometry.kt` (`MobilityGeometryProjection.build`).

### 5.2 Alcance

- **Agregado acumulado** de la misma tabla de transiciones que Topología, más un **resumen de viajes**.
- Viajes cerrados con más de 90 días se borran (`MobilityFamiliarityConfig.closedTripDetailRetentionMs`,
  `IC/core/MobilityFamiliarity.kt:19`) mientras los contadores `trip_count` de las aristas se
  mantienen: **`trip_count` puede ser mayor que los viajes presentes en `mobility_trips.csv`.**
- Contiene identidades celulares completas y relaciones de ruta. **No contiene coordenadas.**

### 5.3 Entradas

| Entrada | Cabecera | Notas |
|---|---|---|
| `geometry_cells.csv` | `identity,radio_tech,local_trust_state,mobility_familiarity,mobility_good_edges,mobility_relevant_trip_count,incoming_routes,outgoing_routes,incoming_transitions,outgoing_transitions,trusted_incoming,trusted_outgoing,first_mobility_seen_utc,last_mobility_seen_utc` | `radio_tech` = sufijo de la identidad. `local_trust_state` **siempre vacío** («Unavailable in schema 17», `metadata.json`). `mobility_familiarity` ∈ `UNKNOWN_ON_ROUTE` `OBSERVED_ON_ROUTE` `KNOWN_ON_ROUTE` (derivado; *KNOWN no significa fiable*). |
| `geometry_edges.csv` | `from_identity,to_identity,transition_count,trusted_transition_count,trip_count,pending_current_trip,projected_trip_count_after_valid_close,mobility_first_seen_utc,mobility_last_seen_utc,last_trip_id` | `pending_current_trip` = `true`/`false`. `projected_…` es una **proyección**, no un hecho. |
| `mobility_trips.csv` | `trip_id,started_at_utc,ended_at_utc,state,close_reason,had_moving,distinct_cells,edge_count` | `trip_id` UUID. `state` `OPEN`/`CLOSED`. `close_reason` ∈ `STATIC_CONFIRMED` `SAME_SERVING_TIMEOUT` `MAX_DURATION` `ABANDONED`. **Un viaje descartado también queda `CLOSED`** (`CellDbHelper.kt:836-845`); solo se distingue aplicando la regla de validez (`had_moving` y `distinct_cells ≥ 3`, `MobilityFamiliarity.kt:143-148`). |
| `geometry.graphml` | GraphML dirigido | Nodos con 6 atributos; `local_trust_state` vale `UNAVAILABLE` en GraphML y vacío en CSV. |
| `metadata.json` | `schemaVersion:17`, `appVersion`, `appVersionCode`, `exportTimeUtc`, `feature:"MOBILITY_FAMILIARITY"`, `featureMode:"CONTEXT_ONLY"`, parámetros (`K_PRIOR_TRIPS`, `R_GOOD_EDGES`, `MIN_DISTINCT_CELLS`, `*_DURATION_MS`), `semanticNote`, `privacyNote`, `coordinatesIncluded:false`, `localTrustStateAvailability`, `openTripContributionIncludedInFamiliarity:false` | `schemaVersion` 17 coincide con el esquema de BD que introdujo la función, no con el actual (19). |

### 5.4 Hashes

**Ninguno** (a diferencia de Topología).

---

## 6. Stable-Site (ZIP)

### 6.1 Origen

- Interfaz: `IC/ui/HistoryScreen.kt:496-501,565`.
- Escritura: `IC/forensics/StableSiteExporter.kt` → `CellDbHelper.kt:1021` (`getStableSiteExportFiles`).
- Registro: `CellDbHelper.kt:854` (`recordStableSiteContext`), llamado en
  `IC/service/MiniICService.kt:1091-1095` **una vez por cada una de las claves candidatas**.

### 6.2 Concepto de `site_key`

`StableSiteKey.candidates` (`IC/core/StableSite.kt:176-190`): la posición del dispositivo (si la
precisión es ≤ 50 m, `MiniICService.kt:878`) se proyecta a una rejilla de 500 m y se calculan
**cuatro rejillas superpuestas**; cada una da una clave = primeros 10 bytes de SHA-256 en hexadecimal
(20 caracteres). **Cada observación se registra en las cuatro claves.** Por tanto:

- Las filas de `sites.csv` no son lugares independientes: un mismo emplazamiento físico aparece hasta
  4 veces. **Sumar observaciones entre sitios cuenta cada observación hasta cuatro veces.**
- La clave no tiene sal: cualquiera con coordenadas candidatas puede recalcularla. Reduce la precisión,
  no anonimiza (Q-09).

### 6.3 Alcance

Agregado acumulado. Retención (`pruneOldRecords`): días > 120 borrados; celdas con < 3 observaciones y
sin actividad en 120 días borradas; todo lo que no se vea en 365 días borrado; eventos de 14 días
(no exportados). Los contadores `observations` se deduplican por minuto
(`eventKey = minuto:clave`, `MiniICService.kt:1095`).

### 6.4 Entradas

| Entrada | Cabecera | Unidades / notas |
|---|---|---|
| `sites.csv` | `site_key,feature_state,neighbour_capability,maturity_reason,serving_observations,serving_distinct_days,static_distinct_days,full_neighbour_observations,full_neighbour_distinct_days,rf_neighbour_observations,rf_neighbour_distinct_days,first_seen_ms,last_seen_ms` | `feature_state` etc. derivados con la política vigente **al exportar** (`StableSiteMaturityPolicy`, `StableSite.kt:72`). `*_ms` epoch UTC en ms. |
| `site_cells.csv` | `site_key,cell_identity,serving_observations,serving_distinct_days,neighbour_observations,neighbour_distinct_days,first_serving_ms,last_serving_ms,first_neighbour_ms,last_neighbour_ms` | Identidades completas; vecinas sin CID no entran aquí (`it.cellId != "N/A"`). |
| `site_rf_neighbours.csv` | `site_key,rf_fingerprint,evidence_type,radio,arfcn,pci,mcc_metadata,mnc_metadata,tac_metadata,observations,distinct_days,first_seen_ms,last_seen_ms` | `rf_fingerprint` = `RFCTX:v1:RADIO:ARFCN:PCI` (`StableSite.kt:51-57`); `evidence_type` siempre `RF_CONTEXT`. **No es identidad celular.** `*_metadata` solo informativos. `MANUAL.md` no menciona este archivo: la fuente de verdad es el código. |
| `motion.csv` | `site_key,day,state,accuracy_band_m,duration_s,displacement_band_m` | `day` = `yyyy-MM-dd` **en hora local**; `state` `STATIC_CONFIRMED`/`MOVING` (UNKNOWN no se guarda); bandas de 10 m redondeadas hacia abajo; `duration_s` en segundos. Una fila por sitio y día: **se sobrescribe** con la última lectura del día (`CONFLICT_REPLACE`). |
| `shadow_episodes.csv` | `episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,duration_ms,evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated` | `episode_id` UUID. Booleanos `1`/`0`. `duration_ms` derivado. `closed_ms` vacío si abierto. `site_key` vacío si nunca se evaluó. Modo sombra: `enforced` no existe aquí y la función estaba en sombra (`STABLE_SITE_ENFORCEMENT_ENABLED = false`, `StableSite.kt:107`). |

### 6.5 Versión, metadatos, hashes

**Ninguno.** Sin `metadata.json`, sin versión de app ni de esquema, sin hora de exportación, sin
`SHA256SUMS.txt`. La única pista de versión es la presencia de `site_rf_neighbours.csv` y de las
columnas de capacidad (v2.10.0+, esquema 18).

---

## 7. Resumen comparativo

| | Historial | Radio | Caso forense | Topología | Geometría | Stable-Site |
|---|---|---|---|---|---|---|
| Contenedor | CSV | CSV | ZIP | ZIP | ZIP | ZIP |
| Alcance | historial completo retenido | historial de cambios | 1 incidente | 250 rutas más recientes | ≤1 000 rutas + ≤1 000 viajes | acumulado |
| Tipo | observaciones muestreadas | eventos de cambio | observaciones por ciclo | agregado | agregado + viajes | agregado |
| Hora | local sin zona, 1 s | UTC ms | UTC ms + monótono; `case.json` local | UTC | UTC | epoch ms UTC; `day` local |
| Versión declarada | no | no | `schemaVersion` 1 + app | `schemaVersion` 1 + app | `schemaVersion` 17 + app | no |
| Hash | no | no | SHA-256 por entrada | SHA-256 por entrada | no | no |
| Coordenadas | sí (dispositivo y antena) | no | sí (dispositivo) | no | no | no (clave de rejilla) |
| Identidades completas | sí (reconstruible) | no | sí | sí | sí | sí |
| Vecinas | no | no | sí | no | no | sí (agregadas) |

---

## 8. Relaciones entre exportaciones

### 8.1 Posibles

| Relación | Clave | Condiciones |
|---|---|---|
| Historial ↔ Caso forense | identidad completa + tiempo | Convertir la hora local del historial a UTC exige **conocer o suponer la zona** (Q-03). Solo filas servidoras. Resultado: *posible coincidencia*, nunca duplicado exacto (no comparten id, el instante difiere en ms-s y el historial está muestreado). |
| Caso forense ↔ Topología/Geometría | identidad completa | La identidad del caso aparece como nodo/arista. Solo permite decir «esta ruta existe en el agregado», no que ocurriera durante el caso, salvo que `last_seen_utc` caiga dentro de la ventana del caso (y aun así sería el último handover, no necesariamente el del caso). |
| Topología ↔ Geometría | `(from_identity, to_identity)` | Misma tabla en momentos distintos: `geometry_edges.transition_count` debe ser ≥ `transitions.observations` si la geometría es posterior y la ruta no se podó. Permite comprobar coherencia y orden de exportación. |
| Historial ↔ Topología/Geometría | identidad completa | Secuencias de servidoras del historial sugieren handovers; **no pueden sumarse** a los contadores porque el historial está muestreado y los contadores son acumulados. |
| Stable-Site ↔ resto | identidad completa en `site_cells`, `previous_identity`/`candidate_identity` | Relación por identidad. Los episodios tienen tiempos UTC ms: comparables con casos forenses `TRUST_CONTRADICTION` / evento `STABLE_SITE_NEW_SERVING`. |
| Radio ↔ Historial | tiempo + PLMN + `ServiceState` | La columna `ServiceState` del historial debe coincidir con el último evento anterior de Radio, salvo desfase de zona horaria. Es una **comprobación de coherencia**, no una unión. |
| Radio ↔ Caso forense | tiempo UTC | Ambos en UTC: permite situar cambios de servicio dentro de la ventana del caso. |
| Mismo tipo exportado dos veces | contenido de las entradas | Ver §8.4. |

### 8.2 Ambiguas

- **Hora local del historial, `case.json` y `motion.day`**: sin zona, la correspondencia con UTC
  depende de una suposición; en el cambio de horario de octubre una hora es ambigua.
- **Vecinas sin identidad** (`N/A-…`): solo `(radio, arfcn, pci)`, que se repite en el espacio.
  No unir por PCI ni por `rf_fingerprint` fuera de un mismo lugar y momento.
- **`site_key`**: hasta 4 claves por lugar; no se puede saber cuáles corresponden al mismo lugar
  sin coordenadas (salvo recalcular la clave desde el GPS del historial, ver Q-09).
- **Caso `ICD-OBS-` promocionado**: el prefijo y `captureOrigin` dicen cosas distintas.
- **`Verified`/`ApiLat`/`Lat` del historial**: valores posiblemente escritos después de la fila.
- **Identidad con `Radio = UNKNOWN`** (historial previo a v2.1): no casa con identidades completas
  de las demás exportaciones.
- **`cells.csv` de caso vs `cells.csv` de topología**: mismo nombre, contenido distinto.

### 8.3 No reconstruibles

- Handovers individuales (cuándo, en qué orden) a partir de Topología o Geometría: solo hay totales
  y la última vez.
- El periodo que cubre un contador de Topología/Geometría/Stable-Site.
- Las rutas fuera de las 250 (Topología) o 1 000 (Geometría) más recientes.
- Viajes podados (> 90 días) que siguen contando en `trip_count`.
- Vecinas en momentos sin caso forense (el historial no las guarda).
- Precisión o antigüedad de la posición de cada fila del historial.
- Zona horaria del dispositivo, modelo y versión de app del historial, Radio y Stable-Site.
- Coordenadas a partir de `site_key` (hash), ni a qué rejilla de las cuatro pertenece una clave.
- Si un viaje `CLOSED` fue confirmado o descartado, salvo aplicando la regla de validez.
- Qué ocurrió con el contexto de radio de vecinas (`bands`, CSG, portadoras secundarias) en casos
  forenses: el payload lo tenía y el exportador no lo escribe.

### 8.4 Duplicados

| Situación | Cómo se manifiesta | Tratamiento propuesto (fase 4) |
|---|---|---|
| Mismo archivo importado dos veces | Mismo SHA-256 del archivo | Duplicado exacto: rechazar o vincular a la importación existente. |
| Mismo caso forense exportado dos veces | ZIP con bytes distintos (fechas de entrada) pero mismas entradas internas y mismo `caseId` | Comparar hashes de entradas excepto `case.json` (cambia `appVersion` si se actualizó la app, `state`, `sampleCount`). Duplicado exacto si todo coincide; si el segundo tiene más muestras, *versión ampliada* del mismo caso. |
| Dos historiales del mismo teléfono en fechas distintas | Filas en común en el solapamiento | Sin id de fila: emparejar por igualdad de todas las columnas **inmutables** (no `Verified`, `ApiLat`, `ApiLon`, `Lat`, `Lon`, `TAMeters`) respetando multiplicidad. Diferencias en las mutables → *mismo registro con valores actualizados*, documentado. |
| Topología o Geometría exportadas dos veces | Contadores distintos para las mismas rutas | **No son duplicados ni se suman**: son instantáneas sucesivas de un agregado. Usar la más reciente y registrar la otra como contexto. |
| Historial vs caso forense | Fila del historial cerca de una muestra del caso | *Posible duplicado* (misma servidora, misma medida en distinta exportación). No contar dos veces la misma medida en estadísticas conjuntas. |
| Historiales de teléfonos distintos | Sin marca de dispositivo | No se pueden distinguir automáticamente; pedir al usuario que lo declare al importar (Q-08). |

---

## 8b. Otros cambios de ICdetector 3.0.0 (D-053)

- **Radio**: sin cambios de formato.
- **Caso forense**: columnas nuevas en `timeline.csv` y `cells.csv` (fases requeridas, motivo de calidad de datos,
  heurísticas, celda registrada, bandas, indicador CSG, portadoras secundarias), rol `SECONDARY`, entrada
  `trust_site.csv` y `sampleSchemaVersion` en `case.json`. Un rol desconocido se conserva y se avisa.
- **Stable-Site**: entrada `PRIVACY.txt` (nota de privacidad), que CellCase trata como contexto.
- **Topología y Geometría**: sin límite de rutas desde 2.10.6; el aviso de rutas truncadas solo se da antes.
- **Copia de seguridad `.icdbackup`**: nueva; docs/backup-format.md.

## 8c. Otros cambios de ICdetector 3.1.0 (D-057)

- **Radio, Topología, Geometría y Stable-Site**: sin cambios de formato.
- **Caso forense**: mismo formato. `payload_json` ya no lleva `latencyState` y la columna `latency_state` de
  `timeline.csv` queda vacía en las muestras nuevas (se retiró la sonda de latencia); `verified` es `PENDING`.
  CellCase lo importa igual y el informe no habla de latencia.
- **Copia de seguridad `.icdbackup`**: esquema 26; docs/backup-format.md §7.
- **Sin red**: ICdetector 3.1.0 ya no pide el permiso de Internet.

## 9. Implicaciones directas para CellCase

1. Detección de formato por contenido (§0.4), nunca por extensión o nombre.
2. Guardar con cada importación la **suposición de zona horaria** usada para el historial, el
   `case.json` y `motion.day`, y mostrarla en el informe.
3. Tratar `AnomalyConfidence`, `SecurityScore`, `FailedHeuristics`, diagnósticos y estados de
   madurez como **resultados del colector**, separados del análisis propio.
4. Tratar Topología, Geometría y Stable-Site como **agregados**: nunca generar eventos a partir de
   ellos ni sumarlos a observaciones.
5. Marcar el alcance: historial y Radio son acumulados; asociarlos al incidente solo por solapamiento
   temporal declarado.
6. Verificar `SHA256SUMS.txt` donde exista y explicar exactamente qué demuestra.
7. Normalizar centinelas (`N/A`, `2147483647`, `NaN`, vacío) a «ausente» conservando el valor crudo.
