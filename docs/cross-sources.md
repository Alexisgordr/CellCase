# Coincidencias y diferencias entre fuentes (`cross/4`)

Comparación de las fuentes de **un mismo expediente**: Historial, Radio, Topología, Stable-Site, Geometría
y caso forense. La comparación entre expedientes o sesiones queda para otra fase.

El motor está en `core/analysis/CrossSources.kt`. Produce un resultado estructurado (`CrossResult`) que
comparten la app, el informe técnico y el explicado, en los dos idiomas. Las plantillas solo lo presentan:
no recalculan ni deciden nada.

## 1. Lo que ya existía y se reutiliza

| Cruce existente | Dónde | Uso en `cross/1` |
|---|---|---|
| Estado de servicio del historial frente al último evento anterior de Radio (`serviceCoherence`) | `CaseAnalysis` | Base de **X-SVC**. Se añaden la frontera de transición, las horas ambiguas, los valores ausentes y el fin de la cobertura de Radio. La línea «Coherencia con el historial» del apartado de Radio remite ahora a esta sección para no duplicar el hallazgo. |
| Datos por identidad de cada episodio (filas y días del historial, Topología, Stable-Site sin sumar claves, Geometría) | `EpisodeEngine.identityFacts` | Se mantienen como comprobaciones del episodio (`IDENTITY_ESTABLISHED`, `TOPOLOGY_TRUSTED`, `GEOMETRY_KNOWN`). X-CELL compara la **presencia** de identidades en todo el expediente; no repite esas comprobaciones. |
| Radio en la ventana de un episodio | `EpisodeEngine` (R4) | Sin cambios. Los cruces no la repiten en el informe explicado. |
| Caso forense y Stable-Site enlazados a episodios por identidad y hora | `EpisodeEngine` (R1, R3) | Se usa para vincular cada resultado a sus episodios. |
| Familias de evidencia (colector frente a estado del teléfono) | `EvidenceFamily` | Base de la **derivación** de cada resultado. |
| Versiones de un mismo tipo (historiales, casos forenses) | `HistoryMerge`, `ForensicVersions` | Se quedan en su apartado: comparan exportaciones del mismo tipo, no fuentes distintas. |

## 2. Clasificación

- **Coincidencia comprobable** (`MATCH`): datos compatibles y comparables.
- **Diferencia comprobable** (`DIFFERENCE`): valores distintos para la misma entidad en un momento o intervalo comparable.
- **Información complementaria** (`COMPLEMENTARY`): una fuente aporta algo que la otra no contiene.
- **No comparable** (`NOT_COMPARABLE`): faltan periodo común, identidad, precisión temporal o cobertura.

Reglas generales:

1. Una **ausencia** nunca es una diferencia: como mucho, información complementaria o «no comparable».
2. Una diferencia **no** demuestra una anomalía de red ni un IMSI catcher.
3. Una celda se identifica por la identidad completa `MCC-MNC-TAC-CID-tecnología`. PCI y `rf_fingerprint` no identifican una celda. Una identidad incompleta o con tecnología `UNKNOWN` se trata como ausente (`UNKNOWN_IDENTITY`).
4. Las horas del historial solo se usan si tienen un instante único con la zona declarada y confirmada. Si la hora es ambigua o inexistente: `AMBIGUOUS_TIME`. Si no hay zona confirmada: `ZONE_NOT_CONFIRMED`. Nunca se elige uno de los dos instantes posibles.
5. Los valores centinela y vacíos ya llegan como ausentes y no se comparan (`MISSING_VALUE`).
6. Los agregados (Topología, Geometría, Stable-Site) no tienen momento: no se les asigna ninguno. Solo se usan sus horas explícitas (`last_seen_utc`, `first_seen_ms`…).
7. Las claves de Stable-Site se solapan (hasta cuatro por lugar): nunca se suman. Se usa el máximo por clave.
8. Topología (250 rutas) y Geometría (1 000 rutas o viajes) pueden estar truncadas. Una ruta ausente de una exportación truncada es `TRUNCATED_EXPORT`.
9. Los campos del historial que pueden reescribirse después de la captura (`Verified`, `ApiLat`, `ApiLon`, `Lat`, `Lon`) no generan diferencias: `MUTABLE_FIELD`, «no comparable».
10. Los contadores acumulados pueden crecer entre exportaciones. Si la exportación posterior tiene un contador mayor, es coincidencia (`COUNTER_GREW`); solo una disminución es diferencia.
11. **Derivación**: casi todas las fuentes proceden de ICdetector. Una coincidencia entre ellas comprueba la coherencia de los archivos, no aporta una confirmación independiente. Cada resultado indica de dónde vienen sus fuentes:
    - `SAME_MEASUREMENT`: la misma medida de un ciclo, registrada en dos archivos.
    - `SAME_OBSERVATION`: el mismo cambio de celda, registrado por dos funciones.
    - `SAME_DATABASE`: contadores de la misma base de datos.
    - `SAME_PHONE_STATE`: el estado de servicio de Android, leído dos veces.
    - `SAME_COLLECTOR`: las demás combinaciones que también proceden de ICdetector.
    - `INDEPENDENT_FAMILIES`: familias distintas.
12. Los cruces son **contexto**: no cambian prioridades, relevancias ni la valoración del expediente (D-048).
13. **Estados de servicio** (cross/2): solo se comparan `IN_SERVICE`, `OUT_OF_SERVICE`, `EMERGENCY_ONLY` y `POWER_OFF`. Un estado vacío, `UNKNOWN` u otro texto, en Radio o en el historial, es «no comparable» (`STATE_NOT_INTERPRETABLE`), nunca una diferencia.
14. **Instantes con identidades simultáneas** (cross/2): si el historial tiene filas con identidades completas distintas en el mismo instante, no se sabe cuál fue la servidora ni en qué orden. Ese instante corta la cadena: no se deriva un cambio hacia él, desde él ni a través de él (X→{A,B}→Y no da X→A, B→Y ni X→Y). Tampoco se emparejan esas filas con un caso forense ni con un episodio de Stable-Site. Se presenta como «no comparable» (`SIMULTANEOUS_IDENTITIES`).
15. **Exportaciones de un agregado sin orden demostrable** (cross/2): si varias exportaciones de Topología, Geometría o Stable-Site tienen la misma fecha o no la tienen y su contenido es distinto, no se elige ninguna: ni por orden de importación, ni por identificador interno, ni por huella. El análisis (episodios y cruces) no usa ese agregado y el conflicto se presenta en X-VER y en el apartado de contexto de red. Con fechas distintas se usa la más reciente. Con contenido idéntico se toma la primera por nombre de archivo y huella, y el resultado es el mismo en cualquier orden.
16. **Ventanas** (cross/2): una coincidencia dentro de una ventana (±60 s en Topología, ±5 s en el historial) se describe como «compatible» y con el margen usado. No demuestra que sea exactamente el mismo evento ni la misma medición.
17. **Verificación externa retirada** (cross/3, D-057): ICdetector 3.1.0 quitó la consulta a OpenCellID y sus filas llevan siempre `Verified` = `PENDING` sin nada pendiente. En X-FOR, el `Verified` no se compara cuando la fila del historial tiene `AppVersion` 3.1.0 o posterior **o cuando el caso forense lo grabó la 3.1.0 o posterior** (`appVersion` de `case.json`; sus muestras llevan siempre `PENDING`): no produce «no comparable» (`MUTABLE_FIELD`) ni ninguna otra entrada. Las filas anteriores a 3.1.0 se comparan como en `cross/2`. Es el único cambio de `cross/3`.
18. **Frontera de transición simétrica** (cross/4, D-061): a ≤ 60 s de un **cambio** de estado de Radio (un evento cuyo estado difiere del anterior), una fila del historial no se compara, coincida o no (`TRANSITION_BOUNDARY`). Antes una coincidencia a ≤ 60 s contaba como `MATCH` y solo la diferencia era frontera, lo que inflaba las coincidencias. Un evento cercano que repite el estado (un sondeo) no es un cambio.
19. **Zona sin confirmar** (cross/4): una fila con `ObservedAtUtc` tiene instante exacto aunque la zona no esté confirmada. X-SHADOW y X-FOR solo dan `ZONE_NOT_CONFIRMED` si hay filas sin situar (sin `ObservedAtUtc`) que, con cualquier desfase horario (UTC−12 a UTC+14), podrían caer en la ventana. Antes lo daban para todo el historial en cuanto la zona no estaba confirmada (X-SVC ya comparaba fila a fila).
20. **Resultados frente a la misma fuente** (cross/4): la presentación conjunta de «el mismo resultado frente a varios agregados» (§6) solo junta resultados frente a fuentes B **distintas**. Dos versiones usadas de un caso forense (D-040) frente al historial son dos resultados; antes se juntaban como «Historial, Historial» con un solo recuento.
21. **`Verified` en X-FOR** (cross/4): se toma de una muestra del caso a ±3 s de la fila, como el resto del emparejamiento. Antes, de la muestra más cercana a cualquier distancia.
22. El resultado no depende del orden de importación. Se ordena por comprobación, resultado, motivo, entidades y hora. Las referencias se ordenan por archivo, entrada y registro.

## 3. Comprobaciones

| Id | Fuentes | Entidad y ventana | Resultados posibles |
|---|---|---|---|
| X-SVC | Historial `ServiceState` ↔ Radio | Cada fila del historial con instante único, frente al último evento de Radio anterior; solo entre el primer y el último evento de Radio | `MATCH` y `DIFFERENCE` (las dos a más de 60 s de cualquier cambio de estado de Radio); no comparable si está a ≤ 60 s de un cambio, coincida o no (`TRANSITION_BOUNDARY`, cross/4: la hora del historial es de inserción y tiene resolución de segundos), si es anterior o posterior a la cobertura de Radio, si la hora es ambigua, si no hay zona o si falta el valor; sin periodo común: `NO_OVERLAP` |
| X-CELL | Historial ↔ Topología / Geometría / Stable-Site (celdas) | Identidades servidoras, sin tiempo | `MATCH` (en ambas); `COMPLEMENTARY` (solo en una; el agregado es acumulado y el historial es un periodo); `NOT_COMPARABLE` (identidades incompletas) |
| X-ROUTE | Historial ↔ Topología / Geometría (rutas) | Cambios de servidora entre filas consecutivas del historial a ≤ 10 min, frente a las rutas del agregado | `MATCH` (la ruta existe; los contadores no se suman); ruta ausente: no comparable (`TRUNCATED_EXPORT`, `AFTER_EXPORT` o `SAMPLED_HISTORY`, porque el historial está muestreado y puede faltar una celda intermedia) |
| X-AGG | Topología ↔ Geometría | Misma ruta `(origen, destino)`, contadores y orden de exportación | `MATCH` (iguales, o mayor en la posterior: `COUNTER_GREW`); `DIFFERENCE` (menor en la posterior: `COUNTER_DECREASED`); `COMPLEMENTARY` (la ruta solo está en una); `NOT_COMPARABLE` (sin hora de exportación y con valores distintos) |
| X-SHADOW | Stable-Site (episodio) ↔ Topología | Ruta anterior → candidata; `last_seen_utc` dentro de la ventana del episodio ± 60 s | `MATCH` (`TRANSITION_IN_WINDOW`: compatible con el mismo cambio, sin demostrarlo); `COMPLEMENTARY` (la ruta existe con otra hora); no comparable (ruta no exportada, truncada o posterior) |
| X-SHADOW | Stable-Site (episodio) ↔ Historial | Filas con instante único dentro del episodio ± 5 s, sin instantes simultáneos | `MATCH` (`SERVING_IN_WINDOW`: la candidata aparece como servidora en la ventana); `DIFFERENCE` (todas las filas muestran otra identidad completa); no comparable (no hay filas en la ventana, la hora es ambigua, no hay zona o las filas son simultáneas) |
| X-VER | Varias exportaciones del mismo agregado | Fecha de exportación (o derivada) y contenido | `NOT_COMPARABLE` (`AMBIGUOUS_EXPORTS`): misma fecha o sin fecha y contenido distinto; no se elige ninguna |
| X-FOR | Caso forense (servidora de `cells.csv`) ↔ Historial | Fila del historial y muestra servidora a ≤ 3 s, dentro de la ventana del caso ± 60 s | `MATCH` (identidad, PCI y EARFCN iguales; `SAME_MEASUREMENT`); `DIFFERENCE`; `MUTABLE_FIELD` (`Verified` distinto); `COMPLEMENTARY` (vecinas solo en el caso); no comparable (sin filas en la ventana, sin zona) |
| X-FOR-RAD | Caso forense ↔ Radio | Ventana del caso | `COMPLEMENTARY` (eventos de Radio en la ventana, o ninguno con cobertura previa); no comparable sin cobertura |
| X-FOR-AGG | Caso forense ↔ Topología / Geometría / Stable-Site | Identidad del caso, sin tiempo | `COMPLEMENTARY` (presencia o ausencia en el agregado; no dice que la ruta ocurriera durante el caso) |

## 4. Qué guarda cada resultado

Cada resultado guarda:
- la comprobación, el resultado y su motivo;
- las dos fuentes y sus archivos;
- las entidades (identidades, rutas, códigos de caso);
- el periodo comparado y la ventana de tolerancia;
- el número de elementos comparados y los valores comparados;
- hasta seis referencias a archivo, entrada y fila;
- la derivación de las fuentes, los episodios vinculados y la siguiente comprobación útil.

La explicación y las limitaciones se escriben a partir de `(comprobación, resultado, motivo, derivación)` con los catálogos de textos de cada idioma.

## 5. Lo que no se puede comparar (y por qué)

- Handovers individuales a partir de Topología o Geometría: solo hay totales y la última vez.
- El periodo que cubre un contador acumulado.
- Las vecinas fuera de un caso forense: el historial no las guarda.
- `rf_fingerprint` y vecinas sin identidad: `(radio, ARFCN, PCI)` no identifica una celda.
- `motion.day` de Stable-Site (fecha local sin hora) con horas exactas.
- `site_key` con posiciones: es un resumen cifrado y no se convierte en coordenadas.
- La continuidad del registro de Radio: solo registra cambios.

## 6. Presentación

Con exportaciones reales, Stable-Site produce cientos de cruces rutinarios. Un expediente real de 8 episodios tenía 1 096 resultados X-SHADOW, y su tabla ocupaba 234 de las 255 páginas del técnico. La presentación se resume; el análisis es el mismo (`CrossSummary`, sin reglas nuevas).

- **Totales**: cuentan **resultados de comprobaciones** (una fila, una celda, una ruta o un episodio de Stable-Site comparados), no incidentes ni evidencias independientes. El informe lo dice junto al recuento.
- **Resumen por comprobación** (técnico): una fila por grupo (comprobación, resultado, motivo, fuente A y fuentes B). Cada resultado está en un solo grupo, así que los totales del resumen son los del listado completo. Cada fila muestra:
  - el número de resultados y de elementos comparados;
  - las entidades distintas y el periodo;
  - la explicación y la limitación (una vez por grupo);
  - dos ejemplos con sus referencias (archivo, entrada y línea física);
  - cuántos resultados más hay del mismo tipo.
- **Diferencias y cruces vinculados a los episodios** (técnico): todas las diferencias y todos los resultados con episodios, agrupados por episodio, comprobación, resultado, motivo y fuentes. Las limitaciones no se repiten, porque ya están en el resumen.
- **Listado completo** (técnico, anexo G): una fila por resultado, como antes. Es **opcional y está desactivado por defecto** (`ReportOptions.crossAnnex`, en la app «Incluir anexo con el listado completo de cruces»). Si no se incluye, el informe lo indica.
- **Explicado**: solo los resultados vinculados a una observación, en una fila «Otras fuentes» de esa observación, con referencia al técnico. Muestra una frase por comprobación y fuente (la del mejor resultado) y como máximo tres.
- **App**: el recuento, los grupos del resumen y el detalle por episodios. Si no hay nada comparable, se explica por qué.

Pruebas (`CrossSourcesTest`):
- en el expediente a escala (`ScaleFixture`: 548 episodios de Stable-Site, 1 108 resultados) y en las muestras, el resumen reúne exactamente todos los resultados, con los mismos totales por tipo, y el detalle contiene todas las diferencias y todos los resultados con episodios;
- el anexo tiene una fila por resultado y solo aparece si se pide;
- el resumen es el mismo con y sin anexo.

Medido con el expediente sintético a escala:
- técnico español: 27 páginas por defecto, 189 con el anexo; inglés: 26 y 181;
- sección 6: 7 páginas;
- explicado: 3 páginas en cada idioma.
