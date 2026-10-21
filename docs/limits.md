# Límites de importación y memoria (`readers/2.4.0`, D-041, D-046, D-054)

CellCase procesa los archivos en el teléfono, sin servidor. Los límites rechazan lo que no cabe **antes de
acumularlo**; no se captura `OutOfMemoryError` en ningún punto.

## Corrección respecto a la versión anterior

El 2/10/2026 se dio como razonable un límite de 16 MiB por archivo **sin medirlo**. Al medirlo, un historial de
15 MiB (92.000 filas) retenía 390 MiB y agotaba un montón de 256 MiB. Se corrigió de dos formas:

1. **Filas compactas**: la cabecera se comparte entre filas, los valores repetidos se comparten (`StringPool`), las
   identidades repetidas se comparten y las expresiones regulares se compilan una vez. De ~4,2 KiB a ~1,4 KiB por
   fila de historial.
2. **Límites medidos y presupuesto por expediente** (tabla siguiente).

## Límites

| Límite | Valor | Dónde |
|---|---|---|
| Archivo | 6 MiB | `ImportLimits.MAX_FILE_BYTES` (se comprueba antes de leer) |
| Historial CSV | 16 MiB | `ImportLimits.MAX_HISTORY_FILE_BYTES` (se reconoce por la cabecera antes de leerlo; la app corta la copia de un archivo de texto al pasarlo) |
| Copia de seguridad `.icdbackup` | 512 MiB; base de datos 1 GiB | `BackupLimits` (la base se lee desde disco, no en memoria; cada exportación que se obtiene de ella tiene los límites de su archivo) |
| Filas por tabla | 60.000 | `ImportLimits.MAX_TABLE_ROWS` |
| Campo CSV | 8 KiB | `ImportLimits.MAX_FIELD_CHARS` |
| Entrada de ZIP descomprimida | 6 MiB | `ZipLimits` (tamaño declarado antes de leer y bytes reales mientras se lee) |
| ZIP descomprimido | 8 MiB | `ZipLimits` |
| Entradas por ZIP | 64 | `ZipLimits` |
| Ratio de compresión | 200 | `ZipLimits` |
| **Historiales del expediente** | 40.000 filas en total | `CaseBudget` (también el historial de una copia de seguridad) |
| **ZIP del expediente** | 8 MiB descomprimidos en total | `CaseBudget` |

El presupuesto del expediente se reserva **en orden de importación**: los primeros archivos tienen prioridad y el
que no cabe se marca «demasiado grande» con un mensaje propio, sin interpretarlo. El archivo se conserva. En la app,
el presupuesto de historiales se comprueba al importar y los dos al releer los originales. El de ZIP solo al releer,
porque el tamaño descomprimido no se guarda en la base de datos.

Referencia de tamaño: una semana de historial real ocupa unas 1.400 filas (~300 KiB con 2.10.5; con 3.0 más del doble
de bytes por fila), así que 40.000 filas son unas 28 semanas. Un caso forense real ocupa ~150 KiB.

### Por qué el presupuesto de historial cuenta filas (D-054)

Hasta `readers/2.2.0` el presupuesto eran 6 MiB de CSV. ICdetector 3.0 añade 19 columnas y escribe ~2,3 veces más
bytes por fila (~390 B frente a ~170 B), pero la memoria que ocupa una fila leída crece mucho menos: 1,84 KiB frente a
1,45 KiB (medido leyendo desde disco, 40.000 filas). Con un presupuesto en bytes, una historia de 3.0 de cuatro meses
(~24.000 filas, ~9 MiB) se habría rechazado aunque cabe en memoria. Por eso el presupuesto cuenta filas
(`CaseBudget.csvRows`: registros tras la cabecera; los saltos de línea dentro de comillas no cuentan) y el archivo de
historial tiene un límite propio de 16 MiB. Al importar, la app cuenta las filas de los historiales CSV ya guardados;
el historial de una copia de seguridad se reserva al releer el expediente, en orden de importación.

## Medición (`core/src/test/.../MemoryProbe.kt`)

JVM de escritorio (OpenJDK 21.0.12, G1), **no Android**. Lote con el peor caso permitido, sintético: historial con
todo el presupuesto (40.000 filas), segundo historial igual (rechazado por el presupuesto), caso forense (6.000
muestras, 26.000 celdas, 11.800 diagnósticos), un historial por encima de 16 MiB (rechazado sin leer) y las demás
exportaciones de muestra. Se leen de uno en uno desde disco con `processFile`, como en la app; después el análisis
conjunto y los dos informes en los dos idiomas. Medido el 20/10/2026 con `readers/2.3.0`.

| Historial | Montón máximo | Resultado | Retenido tras leer todo | Retenido al final |
|---|---|---|---|---|
| 2.10.5, 40.000 filas (6,5 MiB) | 128 MiB | Completo, sin error de memoria | 75 MiB | 76 MiB |
| 3.0, 40.000 filas (14,8 MiB) | 128 MiB | Completo, sin error de memoria | 92 MiB | 94 MiB |

Medición anterior (`readers/2.2.0`, presupuesto de 6 MiB, 37.000 filas de 2.10.5): 70 MiB retenidos al final con
128 MiB. Con 3.0 el margen hasta 128 MiB es menor (34 MiB); por eso no se sube más el presupuesto sin medir en Android.

`readers/2.4.0` (ICdetector 3.1.0) no cambia ningún límite. El historial de 3.1.0 añade tres columnas cortas
(`ChurnCells15m`, `ChurnChanges15m`, `ChurnMotion`: unos 12 bytes por fila en el CSV y tres campos más por fila
leída). **No se ha vuelto a medir** con un historial de 3.1.0; la medición de esta tabla es la de 3.0.

El «pico de montón» que imprime la sonda suma los picos de cada zona del recolector y no son simultáneos; lo
relevante es que el recorrido completo termina con 128 MiB. Con los límites de antes de D-046 (16 MiB por archivo y
sin presupuesto) fallaba con 256 MiB.

Ejecutarla: `java -Xmx128m -Dprobe.v3=true -Dprobe.entry=3700000 -cp … com.alexisgordr.cellcase.core.MemoryProbe DIR`
(`-Dprobe.v3=false` para el formato 2.10.5; `-Dprobe.rows=N` cambia las filas).

## Pendiente

- **Medir en Android**: ART y el montón real del dispositivo (`largeHeap` no se usa) difieren de la JVM. Importar el
  mismo lote en un teléfono de gama baja y vigilar la memoria con el perfilador de Android Studio.
- Un archivo válido (hasta 6 MiB; un historial, hasta 16 MiB) se sigue leyendo entero en memoria.
