# Catálogo de reglas de análisis

Conjunto de reglas: **`cellcase-rules/1`**. Cada hallazgo del informe indica la regla y su versión.

Principios (D-016, D-021, requisitos de fase 5):

- Primero análisis descriptivo y comprobaciones de coherencia. Las correlaciones entre formatos se
  añadirán cuando haya importadores para ellos.
- Las puntuaciones de ICdetector (`SecurityScore`, `AnomalyConfidence`, `FailedHeuristics`,
  `Verified`) se presentan como **resultados del colector**, separados del análisis propio, y nunca
  como probabilidades.
- Ninguna regla declara detección de IMSI catchers, interceptación ni vigilancia.
- Cada hallazgo referencia las filas que lo sustentan (archivo y línea física).
- «No evaluable» es un resultado válido y se explica.

Fuentes de rangos físicos: PCI LTE 0–503 (3GPP TS 36.211), NR 0–1007 (TS 38.211), PSC UMTS 0–511
(TS 25.213); EARFCN 0–262143 (TS 36.101), NR-ARFCN 0–3279165 (TS 38.104), UARFCN 0–16383 (TS 25.101),
ARFCN GSM 0–1023 (TS 45.005); RSRQ −43…20 dB (rango de informe NR, TS 38.133, que incluye el de LTE).
Los rangos de potencia (−160…−20 dBm) y SINR (−30…50 dB) son márgenes amplios elegidos por CellCase
para descartar valores imposibles, no límites normativos.

---

## DESC-PERIOD/1 · Periodo y volumen del historial

- **Pregunta:** ¿qué periodo cubre el historial y cuántos registros contiene?
- **Datos:** `Timestamp` (hora local del teléfono), zona declarada.
- **Calcula:** primera y última hora local (texto original); su interpretación UTC si la zona está
  confirmada y no son ambiguas; filas totales, filas con hora válida, días naturales con datos
  (fecha local), filas por día; filas dentro del periodo de interés si se ha definido.
- **Evidencia:** filas de la primera y la última hora.
- **Alternativas:** el periodo refleja lo **retenido y exportado**, no necesariamente la campaña: ICdetector
  borra filas de más de 120 días y solo escribe cuando hay celda servidora registrable.
- **No evaluable:** si ninguna fila tiene hora válida.
- **No permite concluir:** que el teléfono no estuviera en uso fuera del periodo, ni que la exportación
  esté completa.

## QUAL-FIELDS/1 · Completitud y validez de campos

- **Pregunta:** ¿qué campos tienen datos utilizables?
- **Datos:** todas las columnas; estados de valor del lector (`PRESENT`, `EMPTY`, `SENTINEL`, `INVALID`,
  `OUT_OF_RANGE`).
- **Calcula:** por columna relevante, número y porcentaje de filas en cada estado.
- **Evidencia:** ejemplos de filas por incidencia (anexo de incidencias).
- **Alternativas:** un campo vacío puede significar que el módem no lo informa, que la versión de
  ICdetector no lo recogía o que no había posición GNSS; no implica fallo del teléfono.
- **No evaluable:** nunca (siempre hay recuento).
- **No permite concluir:** la causa de la ausencia.

## QUAL-TIME/1 · Interpretación temporal

- **Pregunta:** ¿se pueden situar las horas en UTC con la zona declarada?
- **Datos:** `Timestamp`, zona declarada y confirmada.
- **Calcula:** filas con instante único, ambiguas (hora repetida en el cambio de horario), inexistentes
  (hora que no existió) e inválidas.
- **Evidencia:** filas ambiguas e inexistentes.
- **Alternativas:** una hora inexistente puede indicar que la zona declarada es incorrecta o que el
  teléfono cambió de zona.
- **No evaluable:** sin zona confirmada (las horas se usan solo como texto local).
- **No permite concluir:** cuál de los dos instantes de una hora ambigua es el correcto.

## DESC-SAMPLING/1 · Intervalos entre registros

- **Pregunta:** ¿hay periodos sin registros dentro del historial?
- **Datos:** instantes UTC exactos (filas ambiguas, inexistentes o inválidas excluidas), ordenados.
- **Calcula:** intervalo entre filas consecutivas; huecos ≥ 60 min; número, duración total y los 10
  mayores. Umbral: ICdetector escribe como mínimo cada 15 min con pantalla apagada mientras el servicio
  funciona y hay servidora registrable (export-formats.md §1.2); 60 min es 4 veces ese periodo.
- **Evidencia:** fila anterior y posterior de cada hueco listado.
- **Alternativas:** servicio detenido, teléfono apagado, sin cobertura o sin celda registrable,
  ahorro de energía del sistema, filas podadas, archivo parcial.
- **No evaluable:** sin zona confirmada o con menos de 2 filas con instante exacto.
- **No permite concluir:** la causa de un hueco ni que durante él ocurriera algo.

## DESC-CELLS/1 · Celdas servidoras observadas

- **Pregunta:** ¿qué identidades servidoras aparecen y con qué frecuencia?
- **Datos:** identidad completa `MCC-MNC-TAC-CID-RADIO`.
- **Calcula:** identidades completas distintas; filas por identidad; primera y última hora local;
  filas con identidad incompleta (no se agrupan).
- **Evidencia:** primera fila de cada identidad listada.
- **Alternativas:** el número de filas depende del muestreo (handovers, periódico, alarmas), no del
  tiempo real de conexión.
- **No evaluable:** sin identidades completas.
- **No permite concluir:** tiempo de permanencia en cada celda ni legitimidad de ninguna celda.

## DESC-POWER/1 · Potencia por tecnología

- **Pregunta:** ¿qué niveles de potencia registró el colector?
- **Datos:** `DBM` válido, `Radio`.
- **Calcula:** por tecnología: n, mínimo, percentil 10, mediana, percentil 90, máximo (dBm). **Nunca se
  mezclan tecnologías** porque la magnitud difiere: LTE `getDbm()` de Android (RSRP en versiones
  actuales), NR SS-RSRP (o `getDbm()` si falta), UMTS/GSM `getDbm()` según el módem.
- **Evidencia:** filas con mínimo y máximo por tecnología.
- **Alternativas:** la potencia depende de la posición, el entorno, el modelo de teléfono y la carga.
- **No evaluable:** sin valores válidos.
- **No permite concluir:** que una potencia alta o baja sea anómala.

## COH-TA/1 · Coherencia de Timing Advance

- **Pregunta:** ¿es coherente `TAMeters` con `TA` y `TAUnit` según la conversión que documenta la
  exportación?
- **Datos:** `TA`, `TAUnit`, `TAMeters`.
- **Calcula:** filas con TA; reparto por unidad; filas donde `TAMeters` ≠ TA×78 (`LTE_INDEX`), ≠ TA×554
  (`GSM_INDEX`) o no vacío para otras unidades. Los factores son los de la exportación descrita en
  export-formats.md §1.3 (pasos de TA de 3GPP redondeados); no se recalcula nada.
- **Evidencia:** filas incoherentes.
- **Alternativas:** exportación de otra versión con otra regla, edición manual del archivo.
- **No evaluable:** sin filas con TA.
- **No permite concluir:** distancias reales a la antena (TA cuantizado, unidad dudosa en NR).

## COL-RESULTS/1 · Resultados del colector (ICdetector)

- **Pregunta:** ¿qué registró ICdetector sobre sus propias heurísticas?
- **Datos:** `SecurityScore`, `FailedHeuristics`, `AnomalyConfidence`, `Verified`.
- **Calcula:** recuentos por clase de `FailedHeuristics` (`OK`, prefijo `[sub-umbral]`, motivo sin
  prefijo), puntuaciones < 100, máximo de `AnomalyConfidence`, reparto de `Verified`.
- **Evidencia:** filas con motivo sin prefijo y con `[sub-umbral]`.
- **Alternativas:** las heurísticas de ICdetector no están calibradas; sus motivos son resultados de
  reglas propias del colector y pueden dispararse por condiciones de red normales.
- **No evaluable:** sin columnas del colector.
- **No permite concluir:** que exista un IMSI catcher, interceptación o vigilancia; `AnomalyConfidence`
  no es una probabilidad.

## COH-DUP/1 · Duplicados y posibles revisiones

- **Pregunta:** ¿hay filas repetidas?
- **Datos:** contenido completo de cada fila; hora + identidad completa.
- **Calcula:** filas idénticas; filas con misma hora e identidad y contenido distinto.
- **Evidencia:** pares de filas.
- **Alternativas:** doble escritura legítima (handover y alarma en el mismo segundo), revisión de
  columnas actualizadas después (`Verified`, `ApiLat`…).
- **No evaluable:** nunca.
- **No permite concluir:** que una de las filas sea errónea. No se elimina ninguna.

## QUAL-TESTNET/1 · Red de pruebas

- **Pregunta:** ¿aparecen identidades de red de pruebas?
- **Datos:** `MCC`.
- **Calcula:** filas con MCC 001 (reservado para redes de prueba, UIT-T E.212).
- **Alternativas:** equipos de laboratorio, femtoceldas de prueba, **datos sintéticos**.
- **No permite concluir:** el origen de esas celdas.

## MISSING-*/1 · Evidencia no aportada

Por cada tipo de exportación no importado, un hallazgo «no evaluable» que explica qué análisis
requeriría (caso forense → cronología del incidente con vecinas; Radio → estado de servicio;
Topología/Geometría → contexto de rutas agregado; Stable-Site → contexto de sitio).
