# Reglas de episodios (`episodes/6`)

Código: `core/.../analysis/Episodes.kt` (`EpisodeEngine`) y `Patterns.kt`. Todo es determinista: las mismas
importaciones producen los mismos episodios, prioridades y numeración.

En los informes, cada incidencia se presenta en tres capas separadas:
- **Observación**: lo registrado por ICdetector, citado literalmente.
- **Comprobación de CellCase**: el contraste con las mediciones del historial y con otras fuentes.
- **Interpretación**: las explicaciones posibles para ese patrón concreto, y lo que no permite concluir.

## 1. Señales y patrones

Solo forman episodios las señales con **instante UTC único**.

| Fuente | Señal | Familia |
|---|---|---|
| Historial | fila con `FailedHeuristics` distinto de `OK` (con o sin `[sub-umbral]`) | colector |
| Caso forense | ventana del caso (`startedAt`–`endedAt`) | colector |
| Stable-Site | episodio en sombra registrado, con hora epoch | colector |
| Radio | `OUT_OF_SERVICE`, `EMERGENCY_ONLY`, búsqueda de red u operador distinto de la SIM | estado del teléfono |

El **patrón** se deduce del texto del motivo de ICdetector (`ReasonParser`), según la siguiente tabla.

| Texto de ICdetector | Patrón |
|---|---|
| «Downgrade de banda» | cambio de banda |
| «Identidad RF inestable» | PCI alternante |
| «Potencia anómala», «Salto potencia», «Huella RF» | desviación de potencia |
| «Desviación TAC» | TAC |
| «Suplantación TA», «Proximidad anómala» | Timing Advance |
| «Transición», «Ping-Pong» | transición |

CellCase no adopta las valoraciones del texto: «forzado», por ejemplo, solo aparece dentro de la cita.
Un cambio de banda dentro de LTE se distingue de un cambio de tecnología (LTE→GSM), que se detecta
comparando la tecnología de la fila anterior.

## 2. Agrupación

- **R1** Las señales de la misma identidad completa y del mismo tipo de patrón, separadas por ≤ 30 min, forman un episodio. Un caso forense o un episodio de Stable-Site de esa celda se unen a él.
- **R3** Un caso forense sin identidad enlaza lo que se solapa con su ventana (±2 min).
- **R4** Radio se asocia a los episodios cuya ventana ±5 min contiene el estado degradado. Si no hay ninguno, queda aislado y no forma incidencia.
- **R5** Los episodios separados por ≤ 15 min forman un **grupo** (G1, G2…). Los grupos no se fusionan: cada episodio conserva su identidad, sus referencias y sus conclusiones, y la proximidad no implica una causa común.

## 3. Medidas propias de CellCase

| Patrón | Comprobación en el historial | Resultado |
|---|---|---|
| Cambio de banda | Banda (`Bands` o EARFCN, 3GPP TS 36.101) de la fila anterior a ≤ 10 min frente a la de la fila con señal | visible / no visible / no comprobable |
| PCI | Serie temporal de la **identidad completa** (MCC, MNC, área, Cell ID, tecnología) y la misma portadora (EARFCN), ±30 min; filas repetidas no cuentan como cambio. Tramos: A (sin cambio), A→B (cambio único), A→B→A (retorno), A→B→A→B… (alternancia repetida) | sin cambio = no visible; cambio único = **parcial** («cambio de PCI observado», no alternancia); retorno o alternancia repetida = visible |
| Potencia | dBm frente a la mediana de la misma celda fuera de ±30 min (≥ 5 filas); diferencia ≥ 10 dB y del mismo signo que la indicada por ICdetector | visible / no visible / no comprobable |
| Otros | — | no comprobable |

## 4. Tiempo

- **Momento**: señales a ≤ 60 s del inicio del momento cuentan una vez. Así, varias representaciones del mismo hecho no inflan la persistencia.
- **Duración**: con un solo momento es **desconocida**. Con varios, solo se conoce el intervalo entre el primero y el último, que **no** demuestra actividad continua. La ventana de un caso forense sí es un intervalo registrado.
- **Persistencia** (dentro del episodio): ≥ 3 momentos en ≥ 10 min.
- **Recurrencia** (entre días): la misma celda con el mismo patrón en ≥ 2 fechas locales. Es un criterio distinto de la persistencia.

## 5. Prioridad (sin porcentajes)

| Nivel | Criterio |
|---|---|
| **Revisión prioritaria** | Alarma de ICdetector (aviso del colector no sub-umbral, o caso forense con origen `ALARM`) **y** calidad de datos buena **y** comportamiento **comprobado** en las mediciones (visible; «no evaluable» o «parcial» no bastan) **y** persistencia o recurrencia. |
| **Revisar** | Alarma de ICdetector; **o** comportamiento comprobado en las mediciones **y** con recurrencia o persistencia. |
| **Contexto** | El resto. |

Qué no cambia la prioridad:
- Los estados de verificación (VERIFIED, NOT_FOUND, PENDING…) son contexto. VERIFIED no autentica una estación base; NOT_FOUND solo indica ausencia en una base incompleta.
- Lo habitual de una celda se informa, pero no cambia la prioridad.
- Historial, caso forense y Stable-Site son la misma familia (el colector), así que no se corroboran entre sí.
- Una señal sub-umbral nunca llega a revisión prioritaria.
- Un caso forense de observación (origen distinto de `ALARM`) no es una alarma: se informa su origen y si está confirmado.
- La coincidencia con Radio es contexto (`RADIO_COINCIDENCE_CONTEXT`), no evidencia independiente.
- La calidad describe solo los datos (identidades completas). La disponibilidad de las mediciones necesarias y el resultado de la comprobación se informan aparte: «no evaluable» no equivale a «no contradictorio».

Sobre Radio:
- Si no registró eventos degradados, se dice exactamente eso. Su continuidad de registro no está demostrada.
- Sin datos de Radio anteriores al episodio, «no evaluable».

CellCase no asigna «posible IMSI catcher» ni otra atribución: no hay criterios implementados y evaluados para ello. La falta de indicios tampoco demuestra que el entorno sea seguro.

## 6. Cambios respecto a `episodes/1`

| `episodes/1` | `episodes/2` |
|---|---|
| NOT_FOUND apoyaba la revisión; VERIFIED la matizaba | Son contexto y no cambian la prioridad |
| Identidades distintas consecutivas se fusionaban (R2) | Episodios separados; los próximos en el tiempo forman grupos (R5) |
| Persistencia = ≥ 3 señales, ≥ 10 min o señales en ≥ 2 días | Persistencia (≥ 3 momentos en ≥ 10 min) y recurrencia (≥ 2 días) son criterios distintos |
| Corroboración = número de fuentes | Corroboración = familia distinta (solo Radio frente al colector) |
| «Radio cubre la ventana y no registra servicio degradado» | «No se registraron eventos degradados en los datos disponibles; continuidad no demostrada» |
| «duración 0 s» | «duración desconocida» con una sola observación |
| Explicaciones genéricas por palabra clave | Explicaciones por patrón y según lo medido; «no permite concluir» específico |
| Niveles: revisar primero / revisar / contexto | Revisión prioritaria / revisar / contexto, con criterios enumerados en cada incidencia |
| — | Comprobación propia de banda, PCI y potencia sobre las mediciones |

## 7. Relevancia para la pregunta de suplantación (`episodes/3`)

La calcula el mismo motor, con los mismos hallazgos de cada episodio (señales, comprobaciones y criterios).
No hay un segundo motor de valoración: el informe técnico y el explicado leen `Episode.relevance`,
`EpisodeSet.observationGroups` y `EpisodeSet.overall`. Es un eje **distinto** del interés técnico (§5).

| Estado | Cuándo |
|---|---|
| **Sin indicios suficientes para atribuir un ataque** | Comportamientos habituales de la red (cambio de banda dentro de 4G, potencia, transición, pérdida de cobertura) sin alarma de ICdetector, sin caso forense y sin coincidencia con Radio. Que se repitan (persistencia o recurrencia) **no** eleva la sospecha. También cuando lo indicado por ICdetector no se ve en las mediciones. |
| **Observaciones que necesitan más datos** | Alarma o caso forense de ICdetector; patrón sobre la identidad o los parámetros de la antena (PCI alternante, MCC/MNC, cifrado, TAC, TA, celda aislada, espectro) salvo que no se vea en las mediciones; paso de 4G a 2G/3G. Desde `episodes/4`, la coincidencia con Radio no cambia la relevancia y un caso forense solo cuenta si su origen es una alarma. El estado del expediente también es este si hay registros del colector que no se pudieron situar en el tiempo. |
| **Indicios que requieren investigación especializada** | Solo con criterios implementados **y evaluados** con datos de referencia. Hoy no existen (`SPECIALIST_CRITERIA_EVALUATED = false`): este estado no se asigna nunca, y una prueba lo comprueba. |

El estado del expediente es el más alto de sus episodios. Ningún estado es una probabilidad.
Los episodios del mismo patrón y la misma relevancia se agrupan en una **observación**: ocho cambios de banda
son una observación, no ocho amenazas.

### Cambios respecto a `episodes/2`

- Nueva relevancia por episodio, grupos de observaciones y estado del expediente.
- La prioridad técnica (§5) no cambia: un cambio de banda recurrente sigue pudiendo ser «Revisar», que es un interés técnico, pero su relevancia para la pregunta de suplantación es «sin indicios suficientes».

## 8. Cambios en `episodes/4` (revisión 2026-10-02)

| `episodes/3` | `episodes/4` |
|---|---|
| PCI: dos valores distintos bastaban para «alternancia» | Serie por identidad completa y portadora; A→B es «cambio de PCI observado» (parcial), A→B→A retorno, más tramos alternancia repetida; los duplicados no cuentan |
| Caso forense = alarma | Solo si `captureOrigin = ALARM`; los casos de observación se marcan como tales |
| Radio corroboraba (otra familia) y podía elevar la prioridad | Radio es solo contexto |
| Prioritaria con mediciones «no contradictorias» | Prioritaria exige comportamiento **comprobado** (visible) |
| Calidad mezclaba datos y comprobación | Calidad de datos, disponibilidad de mediciones (disponibles / faltan / sin comprobación implementada) y resultado, por separado |

Efecto en las muestras sintéticas: el número de episodios no cambia; ningún episodio se eleva por Radio ni por un caso de observación.

## 9. Cambios en `episodes/5` (segunda revisión, 2026-10-02)

- La serie de PCI solo se ordena por la hora registrada; nunca por importación, archivo ni número de línea.
- Misma identidad completa, portadora, hora y PCI: una observación coincidente que conserva todas sus procedencias.
- Misma identidad completa, portadora y hora con PCI distintos: **conflicto temporal**. ICdetector registra la hora
  con resolución de segundos y no exporta otro campo temporal fiable, así que el orden es desconocido: no demuestra
  un cambio ni una alternancia.
- El patrón se calcula con los instantes sin conflicto. Si así ya hay un retorno o una alternancia, se mantiene y el
  conflicto se informa. Si no, la comprobación es «no evaluable» con disponibilidad «mediciones en conflicto»
  (criterio `MEASUREMENTS_CONFLICTING`), y no puede llegar a revisión prioritaria.
- Los hechos, los conflictos y las conclusiones no cambian al importar en otro orden ni con otros identificadores
  internos (prueba `reverseOrderAndOtherInternalIdsGiveTheSameFactsConflictsAndConclusions`).

## 10. Cambios en `episodes/6` (revisión de código, 2026-10-20)

- **Recurrencia entre días**: los episodios que se citan como «la misma celda y patrón otro día» se comparan por la
  fecha en la zona del expediente, la misma que cuenta los días de recurrencia. Antes se usaba la fecha UTC, y en
  Madrid las 23:30 de un día y las 00:30 del siguiente contaban como el mismo día.
- **Relevancia (§7)**: un patrón de identidad cuenta aunque no sea el principal del episodio. Ejemplo: tres señales
  de banda y una con desviación de TAC pasan de «comportamiento habitual» a «necesita más datos».
- **Casos forenses**: un caso con identidad solo se asocia a los episodios de esa celda; antes bastaba con que su
  ventana estuviera a menos de 30 min, aunque fuera de otra celda (lo que quitaba «sin caso forense» y enlazaba
  cruces de otra celda). Un caso sin identidad se sigue asociando por la ventana, pero la calidad del episodio queda
  limitada porque no se sabe de qué celda es.
- **Rendimiento**: la agrupación corta la búsqueda en cuanto la siguiente señal empieza más de 30 min después, y la
  fila anterior, las ventanas y la línea base de potencia se buscan con búsqueda binaria. Con 40.000 filas (la mitad
  sub-umbral) el motor pasa de 38 s a 4 s en la JVM con exactamente los mismos episodios (`EpisodeReviewTest`).
