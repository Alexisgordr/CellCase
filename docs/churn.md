# Cambio de celdas de ICdetector 3.1.0 (H17), en modo sombra (`churn/1`)

Código: `core/.../analysis/Churn.kt` (`ChurnAnalyzer`), sección del informe en `ReportTextsImpl.churn`. Decisión:
D-057. Pruebas: `Icd31ChurnTest`. Fuente: ICdetector `v3.1.0` (`CHANGELOG.md`, `docs/history-csv-columns.md`).

## 1. H17 · Cambio de celdas (Cell churn)

**Qué mide.** Cuántas identidades de celda distintas han servido al teléfono en los 15 minutos anteriores
(`ChurnCells15m`) y cuántos cambios de servidora ha habido (`ChurnChanges15m`), con el estado de movimiento
(`ChurnMotion`: quieto `STILL`, en movimiento `MOVING` o desconocido `UNKNOWN`). La cuenta se reinicia tras una
pérdida de servicio o un hueco de más de 2 minutos.

**Qué significa en 3.1.0: modo sombra.** Se registra, no evalúa, no resta puntos y no tiene umbrales; ICdetector los
calibrará con datos de campaña. Un valor alto quieto es un dato para mirar, no una alarma. En
`NotEvaluatedHeuristics` aparece en todas las filas como `H17(sombra)`, que **no** es una abstención por falta de
datos.

**Qué pasa con H12.** Se retiró en 3.1.0 junto con la sonda de latencia. Conserva su número; `H12(retirada)`
aparece en todas las filas y no es una abstención por falta de datos. Un `H12` sin motivo en una fila de 3.0 sí lo
era y se trata como antes (docs/analysis-rules.md, COL-RULES/1). Ninguna regla de ICdetector se renumera.

Los textos de este apartado están en el catálogo (`rule.H17.name`, `rule.H17.what`, `rule.H17.meaning`,
`rule.H12.retired`, `noteval.reason.sombra`, `noteval.reason.retirada`), en español e inglés («H17 Cambio de
celdas» / «H17 Cell churn»), y se usan en el glosario de motivos, en la sección del informe y en el MANUAL.

## 2. Qué hace CellCase

Solo **describe**. El cambio de celdas no interviene en los episodios, las prioridades, la relevancia ni la
valoración del expediente (el churn no cambia las reglas de episodios), igual que el perfil local (D-055). No hay umbrales propios de
CellCase.

Con las filas que traen `ChurnCells15m` y `ChurnChanges15m` (ICdetector 3.1.0 o posterior; las anteriores los
tienen vacíos), `ChurnAnalyzer.of` calcula:

- Filas con churn, filas de 3.1.0 o posterior y filas del historial; primer y último instante.
- Por estado de movimiento (en el orden quieto, en movimiento, desconocido; una fila sin `ChurnMotion` cuenta como
  desconocido): filas, y mediana, P90 y máximo de celdas y de cambios en 15 min. Los percentiles son **por rango
  más cercano** (el valor en la posición ⌈p·n⌉ de la lista ordenada), así que siempre son valores observados.
- **Franjas con valores altos**: filas con `ChurnChanges15m` mayor o igual que el **P90 de cambios de este
  expediente**, agrupadas mientras entre una y la siguiente de ellas pasen 15 min o menos (la ventana de H17). Las
  filas intermedias por debajo del P90 no rompen la franja ni cuentan en ella. Es una referencia del propio
  historial, no un umbral de ICdetector ni de CellCase, y se dice así en el informe. Si el P90 es 0 no hay franjas.
  Se muestran hasta cinco, de más a menos cambios, cada una con su periodo, número de filas, máximo de cambios y de
  celdas, estados de movimiento y la **fila con más cambios** (archivo y línea: su procedencia).

Un valor no válido en el CSV (negativo, no entero, movimiento desconocido) se avisa con su procedencia al importar y
esa fila queda sin el dato; no se rechaza el archivo (`history-csv/1.2.0`).

## 3. Dónde aparece

- **Informe técnico completo**: sección «Cambio de celdas (H17, modo sombra, sin calibrar)» / «Cell churn (H17,
  shadow mode, not calibrated)», después del perfil local. Lleva qué mide, qué significa, el recuento, la tabla por
  movimiento, la tabla de franjas y las limitaciones. Solo aparece si alguna fila tiene churn.
- **Fuentes y calidad** (técnico): la tabla de reglas no evaluadas nombra H17 y marca H12 como retirada desde 3.1.0.
- **Metodología** (técnico): la versión `churn/1`.
- **Informe explicado**: nada. El churn no cambia ninguna conclusión y no hay umbral con el que explicarlo a quien
  no conoce la red.
- **App**: no tiene pantalla propia en 0.2.0; se ve en el PDF técnico.

## 4. Limitaciones

- H17 no tiene umbrales calibrados y depende del movimiento: en un trayecto es normal cambiar mucho de celda.
- Las filas anteriores a ICdetector 3.1.0 no tienen el dato; en un historial mixto el resumen solo describe el tramo
  de 3.1.0.
- Cuenta los cambios de servidora con la misma definición que H10 (ping-pong): coincidir con ella no es una
  confirmación independiente.
- `churn/1` solo cambiará de versión si cambia lo que se calcula o se presenta. Cuando ICdetector publique umbrales
  (3.2, según su CHANGELOG), habrá que decidir aparte si CellCase los cita.
