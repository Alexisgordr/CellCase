# Perfil local de ICdetector 3.0 en CellCase (`profile/1`, D-055)

## 1. Qué hace ICdetector

ICdetector 3.0 aprende en cada teléfono cómo suele verse la señal servidora de cada celda (nivel en dBm y, en LTE y
NR, calidad RSRQ). Para cada fila del historial anota cuánto se aparta la lectura de lo habitual:
desviación = |valor − media| / dispersión. La mayor desviación entre las métricas evaluadas da el nivel:

| Desviación | Nivel |
|---|---|
| < 2 | `NONE` |
| < 3 | `LOW` |
| < 4 | `MODERATE` |
| ≥ 4 | `HIGH` |
| no se puede comparar | `ABSTAIN`, con motivo (`NO_PROFILE`, `PROFILE_IMMATURE`, otro modelo de teléfono, otra tecnología…) |

ICdetector lo ejecuta **en modo sombra** (`SHADOW`; `EXPERIMENTAL_DISPLAY` si el usuario activa la vista
experimental) y advierte que los umbrales no están calibrados, que una desviación no es una probabilidad y que
`HIGH` no es una alerta. Algunas filas se reservan para evaluar y no se aprende de ellas (`ProfileHoldout`).

## 2. De dónde lo lee CellCase

- **Historial CSV de 3.0**: diez columnas por fila (`ProfileMode`, `ProfileLevel`, `ProfileScore`, `ProfileCoverage`,
  `ProfileAbstainReason`, `ProfileAlgorithm`, `ProfileRevision`, `ProfileEvidence`, `ProfileLearned`,
  `ProfileHoldout`). Los valores no reconocidos se conservan y se avisa; una cobertura imposible (evaluadas > total)
  es una incidencia.
- **Copia de seguridad**: además, las referencias aprendidas (`local_profile`) por madurez y los cambios de modo
  (`profile_mode_events`). Ninguna exportación los incluye.
- Las filas grabadas antes de 3.0 no tienen perfil; se cuentan aparte (filas con columnas de 3.0).
- Al fusionar historiales (`merge/4` y posteriores), las columnas del perfil son reescribibles: si una exportación tiene la fila
  evaluada y otra no, se conserva la evaluada.

## 3. Qué se presenta

`LocalProfileAnalyzer` solo **resume**; no tiene umbrales propios ni recalcula nada:

- filas evaluadas por nivel, motivos de abstención, modos, versiones del algoritmo y filas reservadas;
- por episodio (filas del intervalo del episodio): evaluadas, niveles y la lectura más desviada, con su evidencia
  (`SIGNAL_DBM -81 vs …`) y su referencia a archivo y fila;
- lecturas `MODERATE`/`HIGH` fuera de todo episodio, con la más desviada;
- con copia de seguridad, las referencias por madurez.

Si hay versiones distintas del algoritmo se avisa: ICdetector indica que no se comparen entre sí.

Informes:

| Informe | Qué muestra |
|---|---|
| Técnico (es/en) | Sección propia tras los cruces: explicación, contexto, resumen, tabla por episodio, lecturas fuera de episodios y limitaciones; una limitación en el apartado general; versión `profile/1` en la metodología |
| Explicado (es/en) | Una fila «Perfil local» en la observación del episodio, sin lenguaje de alarma, y una advertencia en los límites |
| App | Apartado «Perfil local» en el expediente |

Si el historial es de 3.0 pero ninguna fila está evaluada, el técnico lo dice y el explicado no muestra la fila.
Sin columnas de 3.0 ni copia de seguridad, no hay sección.

## 4. Por qué no cambia la valoración

El perfil es contexto en ICdetector y lo es en CellCase: está sin calibrar, en modo sombra, y mira la misma señal que
la regla de potencia (H13), así que coincidir con ella no es una confirmación independiente. **No interviene en
episodios, prioridades, cruces ni valoración.** La prueba `profileNeverChangesEpisodesPrioritiesOrAssessment`
analiza el mismo historial con y sin columnas del perfil y comprueba que todo eso es idéntico.

## 5. Pruebas

`LocalProfileTest`: recuentos, perfil por episodio con la lectura más desviada, independencia de la valoración,
historiales anteriores a 3.0 sin sección, los cuatro informes, historial sin evaluaciones, algoritmos mezclados y
referencias desde la copia. En la app, `StringResourcesTest` comprueba los textos y plurales nuevos en los dos idiomas.
