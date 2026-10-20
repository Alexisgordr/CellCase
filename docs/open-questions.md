# Registro de dudas abiertas

Estado: `ABIERTA` (sin resolver), `PROPUESTA` (hay una propuesta a la espera de tu decisión),
`CERRADA` (resuelta, con referencia a la decisión en `decisions.md`).

Las dudas Q-01…Q-09 se citan desde `export-formats.md`.

## Formatos

### Q-01 · Historiales anteriores a v2.1 — ABIERTA
`tools/check_export.py:162,286` alude a una columna `ThreatProb` y a historiales sin
`AnomalyConfidence`/`ApiLat`/`ApiLon`, pero el repositorio no conserva aquel exportador. No se puede
saber el orden ni el significado exacto de sus columnas.
**Propuesta:** en la primera versión, rechazar como «versión no soportada» cualquier historial que no
tenga al menos las 23 columnas de v2.1, explicando el motivo. Si tienes algún CSV antiguo, con
anonimizarlo bastaría para añadirlo.

### Q-02 · Topología truncada a 250 rutas — CERRADA (D-014)
`HistoryScreen.kt:94,106` usa `getCellTransitions()` sin límite explícito → `LIMIT 250`. El ZIP no
indica si había más rutas.
**Propuesta:** el informe dirá siempre «la exportación contiene como máximo las 250 rutas vistas
más recientemente; si `routeCount` = 250 es probable que falten rutas». Si `routeCount` < 250 se
podrá afirmar que no hubo truncado (salvo poda por 120 días). En Geometría el límite es 1 000.
(Es un hallazgo sobre ICdetector que quizá quieras corregir allí; CellCase no lo modifica.)

### Q-03 · Zona horaria de las horas locales — CERRADA (D-009, D-010)
El historial (`Timestamp`), `case.json` (`createdAt`…) y Stable-Site (`motion.day`) usan la hora
local del teléfono sin zona.
**Propuesta:** al crear el expediente se pide la zona horaria del dispositivo de captura
(por defecto `Europe/Madrid`, editable) y CellCase la guarda como **supuesto declarado**. Cada hora
convertida conserva el texto original. Las horas que caen en el cambio de octubre se marcan como
ambiguas y las de marzo como inexistentes; nunca se resuelven en silencio.
Si un caso forense y el historial cubren el mismo periodo, CellCase puede **comprobar** la
suposición comparando las filas del historial con las muestras del caso (mismo identidad y dBm
con desfase de horas enteras) y avisar si la zona declarada no encaja.

### Q-04 · Casos forenses en estado `CAPTURING`/`POST_CAPTURE` — CERRADA (D-013)
La interfaz solo exporta casos `READY`/`INTERRUPTED`, pero el exportador no lo comprueba.
**Propuesta:** aceptarlos con advertencia «caso incompleto en el momento de exportar».

### Q-05 · Prefijo `ICD-OBS-` con origen `ALARM` — CERRADA (D-013)
Un caso de observación promocionado conserva el código `ICD-OBS-…` pero `captureOrigin` pasa a
`ALARM`. **Propuesta:** mostrar ambos datos tal cual y explicar que el caso empezó como observación
y después coincidió con un episodio del detector.

### Q-06 · Vocabulario de `latency_state` — CERRADA (informativa)
Valores en el código: `N/A`, `OK`, `ANOMALA`. Cualquier otro valor se conserva y se trata como
desconocido.

### Q-07 · Significado de `DBM` según tecnología — CERRADA (D-016)
LTE: `getDbm()` (RSRP en las versiones actuales de Android); NR: `ssRsrp`, o `getDbm()` si no
hay RSRP; UMTS/GSM: `getDbm()` (RSCP/RSSI según el módem). La unidad es la misma, pero la magnitud
no. **Propuesta:** no comparar ni promediar `DBM` entre tecnologías; las estadísticas de potencia
se separarán siempre por `Radio`. Confirmar con documentación oficial de Android en la fase 5.

### Q-08 · Varios teléfonos en un expediente — CERRADA (D-011: un dispositivo por expediente)
Historial, Radio, Topología, Geometría y Stable-Site no identifican el dispositivo. El caso forense
sí (`device`, `android`).
**Pregunta para ti:** ¿un expediente puede mezclar exportaciones de varios teléfonos? Propuesta:
al importar se asigna cada archivo a un «dispositivo de origen» declarado por el usuario (por defecto
uno solo). Sin esa declaración no se mezclan.

### Q-09 · Recalcular `site_key` desde el GPS del historial — CERRADA (D-008: no se hace)
Es técnicamente posible: la clave es una función determinista y sin sal de la posición
(`StableSite.kt:176-190`). Permitiría relacionar Stable-Site con el historial.
Tiene dos problemas:
1. **Licencia.** Habría que reimplementar exactamente la misma fórmula (proyección, rejillas,
   desplazamientos, prefijo `v2:`, truncado a 10 bytes). Es poca lógica, pero sigue siendo
   reproducir un algoritmo concreto de código GPL-3.0. No lo haré sin tu decisión.
2. **Privacidad.** Esa relación vincularía claves «anonimizadas» con coordenadas reales.
**Propuesta:** no hacerlo en la primera versión; Stable-Site se relacionará solo por identidad
celular y por tiempo.

### Q-10 · Centinela `2147483647` en PCI/ARFCN/dBm — CERRADA (D-016)
**Propuesta:** normalizarlo a «ausente» guardando el valor crudo y contar cuántas veces aparece
como indicador de calidad del dato.

### Q-11 · Cadenas `N/A` en identidades de vecinas — CERRADA (D-016)
**Propuesta:** una identidad que contiene `N/A` no se usa nunca como clave de unión; las vecinas
sin identidad se describen solo como contexto RF `(radio, arfcn, pci)`.

## Producto

### Q-12 · Exportaciones reales — ABIERTA (no bloquea; pendiente antes de vender, D-020)
No dispongo de ninguna exportación real. Las muestras de `samples/synthetic/` las he generado yo a
partir del código. Si puedes aportar al menos un historial y un caso forense reales (anonimizados
si quieres: vaciar `Lat`, `Lon`, `ApiLat`, `ApiLon`, `gps_lat`, `gps_lon`), servirán para
comprobar que los lectores funcionan con datos de verdad. No las subiré a ningún sitio.

### Q-13 · «Radio» = CSV de estado de servicio — CERRADA (confirmado, D-006)
Entiendo que «Radio» es la exportación de la pestaña RADIO (`icdetector_service_state_*.csv`),
la única exportación de esa pantalla. Corrígeme si te referías a otra cosa.

### Q-14 · Licencia de CellCase — CERRADA (D-052: GPL-3.0, como ICdetector; sustituye a D-007)
Ver `docs/licensing.md`.

### Q-15 · Idioma del informe — CERRADA (D-018)
**Propuesta:** español en la primera versión, con las plantillas en archivos separados para poder
añadir inglés.

### Q-16 · Periodo del incidente — CERRADA (D-019)
El historial, Radio y los agregados son acumulados. **Propuesta:** el expediente tiene un «periodo
de interés» opcional. Si hay casos forenses, se propone la unión de sus ventanas y el usuario lo
confirma. Los datos fuera del periodo aparecen como contexto, nunca como parte del incidente.

## Nuevas (fase 2)

### Q-17 · Varios historiales en un expediente — ABIERTA
Combinarlos exige detectar filas repetidas entre exportaciones del mismo teléfono (fase 4, D-017).
Mientras tanto la app analiza solo el más reciente y lo indica en la pantalla y en el informe.

### Q-18 · Rango de potencia válido — PROPUESTA
CellCase descarta como «fuera de rango» valores de DBM fuera de −160…−20 dBm y de SINR fuera de −30…50 dB.
Son márgenes amplios propios, no normativos. Revisar con exportaciones reales.

### Q-19 · Celdas de UMTS/GSM sin PCI — INFORMATIVA
En GSM la columna PCI no se rellena; en UMTS contiene el PSC. CellCase aplica rangos por tecnología y no
interpreta PCI en GSM.

### Q-20 · GPL-3.0 «solo» o «o posterior» — ABIERTA
ICdetector dice «GPL-3.0» sin precisar si admite versiones posteriores de la licencia. CellCase usa el mismo texto
(`LICENSE`). Decide el autor; hasta entonces no se añade ninguna cabecera SPDX.
