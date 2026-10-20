# Muestras SINTÉTICAS

**Todos los archivos de esta carpeta son inventados.** No proceden de ningún teléfono ni de ninguna
red. Se usan el PLMN de pruebas 001-01 y coordenadas cercanas a (0, 0). Sirven para comprobar que los
lectores y validaciones de CellCase se comportan como describe `docs/export-formats.md`. **No
demuestran que ninguna detección funcione en la realidad.**

`MANIFEST.json` describe cada archivo, su SHA-256 y el escenario. Se regeneran con
`tools/synth/generate_samples.py`.

| Archivo | Para qué sirve |
|---|---|
| `SYNTHETIC_icdetector_history.csv` | Historial v2.10.4+ de tres días con un incidente sintético |
| `SYNTHETIC_icdetector_history_edge_cases.csv` | Comillas, comas, saltos de línea, centinelas, notación científica, TA no convertible, hora ambigua |
| `SYNTHETIC_icdetector_history_v21_23cols.csv` | Detección de versión antigua (23 columnas) |
| `SYNTHETIC_icdetector_history_malformed.csv` | Rechazo de filas inválidas |
| `SYNTHETIC_icdetector_service_state.csv` | Radio: eventos de estado de servicio |
| `SYNTHETIC_icdetector_service_state_empty.csv` | Radio vacío (ambiguo: sin eventos o fallo en origen) |
| `SYNTHETIC_ICD-2026-09-27-0007.zip` | Caso forense con SHA256SUMS válido |
| `…_reexport.zip` | Mismo caso exportado otra vez (duplicado por contenido, ZIP distinto) |
| `…_tampered.zip` | Caso con `timeline.csv` modificado (el hash no coincide) |
| `SYNTHETIC_ICD-topology.zip` | Topología |
| `SYNTHETIC_ICD-geometry.zip` | Geometría exportada después de la topología |
| `SYNTHETIC_ICD-stable-site.zip` | Stable-Site (claves aleatorias, 4 por lugar) |
| `SYNTHETIC_hostile_*.zip` | Rutas que escapan, bomba de descompresión, demasiadas entradas, ZIP truncado |
