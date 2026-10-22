# Catálogo de reglas de análisis

Conjunto de reglas: **`cellcase-rules/3`**. Cada hallazgo del informe indica la regla y su versión.

Cambios de `cellcase-rules/3` (revisión de código del 22/10/2026, D-061): `DESC-SAMPLING/2` (sin zona confirmada se
muestrea si ninguna fila depende de la zona; mediana por rango más cercano), `COH-TA/2` (sin `TAMeters` no se compara)
y `COH-DUP/2` (con `ObservedAtUtc`, la «misma hora» es el mismo instante).

**Mediana** (en todo CellCase): con un número par de valores, el menor de los dos centrales, es decir, el valor en
la posición ⌈n/2⌉ de la lista ordenada (rango más cercano). Siempre es un valor observado. La usan DESC-POWER,
DESC-SAMPLING, la línea base de potencia de los episodios (`episodes/7`) y el churn (`churn/1`).

Cambios de `cellcase-rules/2` (CellCase 0.2.0, D-057, para ICdetector 3.1.0): `COL-RESULTS/2` (verificación externa
retirada), `COL-RULES/1` (reglas no evaluadas) y `QUAL-CUT/1` (corte de dataset) son nuevas o cambian; el resto de
reglas siguen en la versión 1. Las reglas de episodios (entonces `episodes/6`) no cambiaron.

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

## DESC-SAMPLING/2 · Intervalos entre registros

- **Pregunta:** ¿hay periodos sin registros dentro del historial?
- **Datos:** instantes UTC exactos (filas ambiguas, inexistentes o inválidas excluidas), ordenados.
- **Calcula:** intervalo entre filas consecutivas; huecos ≥ 60 min; número, duración total y los 10
  mayores. Umbral: ICdetector escribe como mínimo cada 15 min con pantalla apagada mientras el servicio
  funciona y hay servidora registrable (export-formats.md §1.2); 60 min es 4 veces ese periodo.
- **Evidencia:** fila anterior y posterior de cada hueco listado.
- **Alternativas:** servicio detenido, teléfono apagado, sin cobertura o sin celda registrable,
  ahorro de energía del sistema, filas podadas, archivo parcial.
- **Mediana del intervalo** (/2): con un número par de intervalos, el menor de los dos centrales (antes el mayor).
- **No evaluable:** con menos de 2 filas con instante exacto, o sin zona confirmada **si alguna fila depende de la
  zona** (no tiene `ObservedAtUtc`): faltaría en los intervalos y daría huecos falsos. Un historial de 3.0 o posterior
  con `ObservedAtUtc` en todas las filas se muestrea igual con la zona sin confirmar (/2; antes, «sin zona»).
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

## COH-TA/2 · Coherencia de Timing Advance

- **Pregunta:** ¿es coherente `TAMeters` con `TA` y `TAUnit` según la conversión que documenta la
  exportación?
- **Datos:** `TA`, `TAUnit`, `TAMeters`.
- **Calcula:** filas con TA; reparto por unidad; filas donde `TAMeters` ≠ TA×78 (`LTE_INDEX`), ≠ TA×554
  (`GSM_INDEX`) o no vacío para otras unidades. Los factores son los de la exportación descrita en
  export-formats.md §1.3 (pasos de TA de 3GPP redondeados); no se recalcula nada. Sin `TAMeters` (vacío, o anulado
  al fusionar dos exportaciones que no coinciden en ese valor) no hay nada que comparar (/2; antes contaba como
  incoherente).
- **Evidencia:** filas incoherentes.
- **Alternativas:** exportación de otra versión con otra regla, edición manual del archivo.
- **No evaluable:** sin filas con TA.
- **No permite concluir:** distancias reales a la antena (TA cuantizado, unidad dudosa en NR).

## COL-RESULTS/2 · Resultados del colector (ICdetector)

- **Pregunta:** ¿qué registró ICdetector sobre sus propias heurísticas?
- **Datos:** `SecurityScore`, `FailedHeuristics`, `AnomalyConfidence`, `Verified`.
- **Calcula:** recuentos por clase de `FailedHeuristics` (`OK`, prefijo `[sub-umbral]`, motivo sin
  prefijo), puntuaciones < 100, máximo de `AnomalyConfidence`, reparto de `Verified` **solo en las filas que tenían
  verificación externa**.
- **Verificación retirada (/2):** ICdetector 3.1.0 quitó la consulta a OpenCellID. Sus filas llevan siempre
  `Verified` = `PENDING` y `ApiLat`/`ApiLon` vacías, sin nada pendiente. Las filas con `AppVersion` 3.1.0 o
  posterior se cuentan aparte como «sin verificación externa (retirada)», en el anexo del historial y en las
  limitaciones, y no se comparan con otras fuentes (`cross/3` y posteriores). Con un historial mixto, el informe lo explica en
  una frase. Las filas sin `AppVersion` (2.10.x) se tratan como antes.
- **Evidencia:** filas con motivo sin prefijo y con `[sub-umbral]`.
- **Alternativas:** las heurísticas de ICdetector no están calibradas; sus motivos son resultados de
  reglas propias del colector y pueden dispararse por condiciones de red normales.
- **No evaluable:** sin columnas del colector.
- **No permite concluir:** que exista un IMSI catcher, interceptación o vigilancia; `AnomalyConfidence`
  no es una probabilidad.

## COL-RULES/1 · Reglas que ICdetector no evaluó

- **Pregunta:** ¿qué reglas de ICdetector no se pudieron evaluar en cada fila, y por qué?
- **Datos:** `NotEvaluatedHeuristics` (3.0 o posterior): `Hn` o `Hn(motivo)` separados por `;`, o `NONE`.
- **Calcula:** filas con el campo; filas con al menos una regla sin evaluar **por falta de datos o de contexto**;
  por regla y motivo, número de filas y una fila de ejemplo (archivo y línea). Las partes que no siguen el formato
  se cuentan como ilegibles y se conservan.
- **Clases** (`NotEvaluated.kt`):
  - *Falta de datos o de contexto*: todos los motivos de 3.0 (`csg`, `roaming`, `frontera`, `sin_sim`,
    `sin_servicestate`, `sin_mcc_servidora`, `fix_viejo`), los de 3.1.0 `H6(ta_gsm)`, `H6(ta_stub)`,
    `H6(ta_sin_verificar)` y `H11(fix_impreciso)`, y una regla sin motivo. Un `H12` sin motivo de una fila de 3.0
    es una abstención real (por ejemplo, sin medida de latencia) y se trata como antes.
  - *Retirada*: `H12(retirada)`, en todas las filas de 3.1.0 (se quitó la sonda de latencia).
  - *En sombra*: `H17(sombra)`, en todas las filas de 3.1.0 (el cambio de celdas se registra, no se evalúa;
    docs/churn.md).
  - *Reservada*: `H9` (cifrado), siempre sin evaluar desde 3.0 porque Android no lo expone.
  Las tres últimas **no** son abstenciones por falta de datos y no cuentan como problema de calidad.
- **Presentación:** informe técnico, «Fuentes y calidad»: una frase, una tabla con el nombre de cada regla (H12
  marcada como retirada desde 3.1.0, H17 incluida) y el texto de cada motivo en el idioma del informe.
- **No permite concluir:** nada sobre la red; describe qué evaluó el colector.

## QUAL-CUT/1 · Corte de dataset de ICdetector 3.1.0

- **Pregunta:** ¿mezcla el historial filas de antes y de después de ICdetector 3.1.0?
- **Datos:** `AppVersion` de cada fila (las filas sin versión, de 2.10.x, cuentan como anteriores).
- **Calcula:** filas anteriores (y la última hora) y filas de 3.1.0 o posterior (y la primera hora).
- **Presentación:** si hay de los dos tramos, una nota en el informe técnico que nombra los campos no comparables
  entre tramos: `H6`, `TAUnit`, `H11`, `H12`, `H14`, `SecurityScore`, `AnomalyConfidence` y la confianza local de
  celda (y con ella las alarmas), según las marcas «Dataset cut» del CHANGELOG de ICdetector 3.1.0.
- **No cambia:** CellCase no recalcula nada ni corrige valores; las prioridades de los episodios no cambian.
- **No permite concluir:** que un cambio de esos campos entre tramos se deba a la red.

## COH-DUP/2 · Duplicados y posibles revisiones

- **Pregunta:** ¿hay filas repetidas?
- **Datos:** contenido completo de cada fila; hora + identidad completa.
- **Calcula:** filas idénticas; filas con misma hora e identidad y contenido distinto. La «misma hora» es el mismo
  `ObservedAtUtc` cuando lo hay (/2), como en el importador: las dos filas de la hora repetida de octubre no lo son.
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
