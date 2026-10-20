#!/usr/bin/env python3
"""
Comprueba que las muestras sintéticas usan exactamente las cabeceras y entradas que escribe el
código fuente de ICdetector. Lee el código como texto (no lo ejecuta ni lo reutiliza).

Uso: python3 tools/synth/verify_against_source.py <ruta a ICdetector-main> [carpeta de muestras]
Sale con código 1 si alguna comprobación falla.
"""
import csv
import hashlib
import io
import os
import re
import sys
import zipfile

SRC = sys.argv[1]
SAMPLES = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..", "..", "samples", "synthetic")
IC = os.path.join(SRC, "app/src/main/java/com/alexisgordr/icdetector")
fails = []


def check(name, ok, detail=""):
    print(f"[{'OK ' if ok else 'FALLO'}] {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        fails.append(name)


def read(rel):
    with open(os.path.join(IC, rel), encoding="utf-8") as f:
        return f.read()


def kotlin_const(text, name):
    """Concatena los literales de una const val definida con + en varias líneas."""
    m = re.search(rf"const val {name} = (.*?)\n\s*\n", text, re.S)
    body = m.group(1)
    body = re.sub(r"//[^\n]*", "", body)
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', body))


def appendline_after(text, marker):
    i = text.index(marker)
    m = re.search(r'appendLine\("([^"]+)"\)', text[i:])
    return m.group(1)


def query_header(text, filename):
    m = re.search(rf'query\("{re.escape(filename)}","([^"]+)"', text)
    return m.group(1)


def first_line(data):
    return data.decode("utf-8").split("\n", 1)[0]


def sample(name):
    with open(os.path.join(SAMPLES, name), "rb") as f:
        return f.read()


def zentries(name):
    with zipfile.ZipFile(io.BytesIO(sample(name))) as z:
        return {i.filename: z.read(i.filename) for i in z.infolist()}


export_utils = read("utils/ExportUtils.kt")
forensic = read("forensics/ForensicExporter.kt")
topology = read("forensics/TopologyExporter.kt")
geometry = read("forensics/GeometryExporter.kt")
db = read("storage/CellDbHelper.kt")

# Historial y radio
check("Historial: cabecera = ExportUtils.CSV_HEADER",
      first_line(sample("SYNTHETIC_icdetector_history.csv")) == kotlin_const(export_utils, "CSV_HEADER"))
check("Radio: cabecera = ExportUtils.SERVICE_STATE_CSV_HEADER",
      first_line(sample("SYNTHETIC_icdetector_service_state.csv")) == kotlin_const(export_utils, "SERVICE_STATE_CSV_HEADER"))
check("Historial v2.1: 23 primeras columnas", first_line(sample("SYNTHETIC_icdetector_history_v21_23cols.csv")).split(",")
      == kotlin_const(export_utils, "CSV_HEADER").split(",")[:23])
hdr = kotlin_const(export_utils, "CSV_HEADER").split(",")
rows = list(csv.reader(io.StringIO(sample("SYNTHETIC_icdetector_history.csv").decode())))
check("Historial: todas las filas con 35 campos", all(len(r) == len(hdr) for r in rows), str({len(r) for r in rows}))
rows = list(csv.reader(io.StringIO(sample("SYNTHETIC_icdetector_history_edge_cases.csv").decode())))
check("Historial casos límite: parseable RFC 4180 con 35 campos", all(len(r) == len(hdr) for r in rows))
check("csvRow escribe 35 valores (recuento en el código)",
      export_utils[export_utils.index("fun csvRow"):export_utils.index("fun serviceStateCsvRow")].count("item.") >= 35)

# Caso forense
fe = zentries("SYNTHETIC_ICD-2026-09-27-0007.zip")
expected_order = re.findall(r'files\["([^"]+)"\]', forensic)
check("Forense: entradas y orden = ForensicExporter", list(fe) == list(dict.fromkeys(expected_order)), f"{list(fe)} vs {expected_order}")
for f in ["timeline.csv", "cells.csv", "heuristics.csv"]:
    check(f"Forense: cabecera {f}", first_line(fe[f]) == appendline_after(forensic, f'files["{f}"]'))
import json
case = json.loads(fe["case.json"])
keys_code = re.findall(r'put\("(\w+)"', forensic[forensic.index('files["case.json"]'):forensic.index('files["timeline.csv"]')])
check("Forense: claves de case.json = código", sorted(case) == sorted(keys_code), f"{sorted(case)} vs {sorted(keys_code)}")


def sums_ok(entries):
    lines = entries["SHA256SUMS.txt"].decode().strip().split("\n")
    return all(hashlib.sha256(entries[n]).hexdigest() == h for h, n in (l.split("  ", 1) for l in lines))


check("Forense: SHA256SUMS válido", sums_ok(fe))
check("Forense manipulado: SHA256SUMS detecta el cambio", not sums_ok(zentries("SYNTHETIC_ICD-2026-09-27-0007_tampered.zip")))
re_ = zentries("SYNTHETIC_ICD-2026-09-27-0007_reexport.zip")
check("Reexportación: entradas idénticas", re_ == fe)
check("Reexportación: bytes del ZIP distintos",
      sample("SYNTHETIC_ICD-2026-09-27-0007.zip") != sample("SYNTHETIC_ICD-2026-09-27-0007_reexport.zip"))

# Topología
te = zentries("SYNTHETIC_ICD-topology.zip")
check("Topología: entradas y orden", list(te) == list(dict.fromkeys(re.findall(r'files\["([^"]+)"\]', topology))))
for f in ["cells.csv", "transitions.csv"]:
    check(f"Topología: cabecera {f}", first_line(te[f]) == appendline_after(topology, f'files["{f}"]'))
check("Topología: SHA256SUMS válido", sums_ok(te))
meta_keys = re.findall(r'put\("(\w+)"', topology[topology.index('files["metadata.json"]'):])
check("Topología: claves de metadata.json", sorted(json.loads(te["metadata.json"])) == sorted(meta_keys))

# Geometría
ge = zentries("SYNTHETIC_ICD-geometry.zip")
check("Geometría: entradas y orden", list(ge) == list(dict.fromkeys(re.findall(r'files\["([^"]+)"\]', geometry))))
for f in ["geometry_cells.csv", "geometry_edges.csv", "mobility_trips.csv"]:
    check(f"Geometría: cabecera {f}", first_line(ge[f]) == appendline_after(geometry, f'files["{f}"]'))
gkeys = re.findall(r'put\("(\w+)"', geometry[geometry.index('files["metadata.json"]'):])
check("Geometría: claves de metadata.json", sorted(json.loads(ge["metadata.json"])) == sorted(gkeys),
      f"{sorted(json.loads(ge['metadata.json']))} vs {sorted(gkeys)}")
check("Geometría: sin SHA256SUMS (como el código)", "SHA256SUMS.txt" not in ge)

# Stable-Site
se = zentries("SYNTHETIC_ICD-stable-site.zip")
fn = db[db.index("fun getStableSiteExportFiles"):db.index("private fun createForensicTables")]
expected = ["sites.csv"] + re.findall(r'query\("([^"]+)"', fn)
check("Stable-Site: entradas y orden", list(se) == expected, f"{list(se)} vs {expected}")
check("Stable-Site: cabecera sites.csv", first_line(se["sites.csv"]) == appendline_after(fn, 'files["sites.csv"]'))
for f in expected[1:]:
    check(f"Stable-Site: cabecera {f}", first_line(se[f]) == query_header(fn, f))

print(f"\n{len(fails)} fallos")
sys.exit(1 if fails else 0)
