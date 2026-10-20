#!/usr/bin/env python3
"""
Comprueba que las muestras sintéticas de ICdetector 3.0.0 (samples/synthetic/icd30) usan exactamente las cabeceras,
entradas, claves y esquema que escribe el código fuente de ICdetector v3.0.0. Lee el código como texto (no lo
ejecuta). Complementa a verify_against_source.py, que contrasta las muestras de 2.10.5 con ICdetector v2.10.5.

Uso: python3 tools/synth/verify_icd30_against_source.py <ruta a ICdetector en v3.0.0> [carpeta icd30]
Sale con código 1 si alguna comprobación falla.
"""
import csv
import glob
import io
import json
import os
import re
import sqlite3
import sys
import tempfile
import zipfile

SRC = sys.argv[1]
SAMPLES = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..", "..", "samples", "synthetic", "icd30")
IC = os.path.join(SRC, "app/src/main/java/com/alexisgordr/icdetector")
fails = []


def check(name, ok, detail=""):
    print(f"[{'OK ' if ok else 'FALLO'}] {name}" + (f" — {detail}" if detail and not ok else ""))
    if not ok:
        fails.append(name)


def read(rel):
    with open(os.path.join(IC, rel), encoding="utf-8") as f:
        return f.read()


def literals(expr):
    expr = re.sub(r"//[^\n]*", "", expr)
    return "".join(re.findall(r'"((?:[^"\\]|\\.)*)"', expr))


def kotlin_const(text, name):
    """Literal de una const val, aunque esté partida en varias líneas con + y comentarios entre ellas."""
    lines = text[text.index(f"const val {name} ="):].split("\n")
    body = [lines[0].split("=", 1)[1]]
    code = body[0]
    for line in lines[1:]:
        if not re.sub(r"//.*", "", code).rstrip().endswith("+"):
            break
        body.append(line)
        if not line.strip().startswith("//"):
            code = line
    return literals("\n".join(body))


def appendline_after(text, marker):
    """Primera cabecera escrita con appendLine(...) tras el marcador, aunque esté partida con +."""
    i = text.index(marker)
    m = re.search(r'appendLine\(((?:\s*"(?:[^"\\]|\\.)*"\s*\+?)+)\)', text[i:])
    return literals(m.group(1))


def entries_in_order(text):
    return list(dict.fromkeys(re.findall(r'files\["([^"]+)"\]', text)))


def first_line(data):
    return data.decode("utf-8").split("\n", 1)[0]


def sample(name):
    with open(os.path.join(SAMPLES, name), "rb") as f:
        return f.read()


def zentries(path):
    with zipfile.ZipFile(path) as z:
        return {i.filename: z.read(i.filename) for i in z.infolist()}


export_utils = read("utils/ExportUtils.kt")
csv_text = read("utils/CsvText.kt")
forensic = read("forensics/ForensicExporter.kt")
topology = read("forensics/TopologyExporter.kt")
geometry = read("forensics/GeometryExporter.kt")
note = read("forensics/StableSiteExportNote.kt")
manifest_src = read("backup/BackupManifest.kt")
archive_src = read("backup/BackupArchive.kt")
db_src = read("storage/CellDbHelper.kt")
storage = {os.path.basename(p): open(p, encoding="utf-8").read() for p in glob.glob(os.path.join(IC, "storage", "*.kt"))}

# --- Historial y Radio
hist_header = kotlin_const(export_utils, "CSV_HEADER")
check("Historial 3.0: 54 columnas en el código", len(hist_header.split(",")) == 54, str(len(hist_header.split(","))))
check("Historial: cabecera = ExportUtils.CSV_HEADER", first_line(sample("SYNTHETIC_icd30_history.csv")) == hist_header)
rows = list(csv.reader(io.StringIO(sample("SYNTHETIC_icd30_history.csv").decode("utf-8"))))
check("Historial: todas las filas con 54 campos", all(len(r) == 54 for r in rows), str({len(r) for r in rows}))
check("Radio: cabecera = ExportUtils.SERVICE_STATE_CSV_HEADER",
      first_line(sample("SYNTHETIC_icd30_service_state.csv")) == kotlin_const(export_utils, "SERVICE_STATE_CSV_HEADER"))
check("CsvText: protección con apóstrofo en el código", "'" in csv_text and "fun guard" in csv_text)
guarded = [v for r in rows[1:] for v in r if v.startswith("'")]
check("Historial: la muestra incluye textos protegidos", len(guarded) > 0)

# --- Casos forenses
for path in sorted(glob.glob(os.path.join(SAMPLES, "SYNTHETIC_icd30_ICD-*.zip"))):
    n = os.path.basename(path)
    fe = zentries(path)
    order = entries_in_order(forensic)
    check(f"{n}: entradas y orden = ForensicExporter", list(fe) == order, f"{list(fe)} vs {order}")
    for f in ["timeline.csv", "cells.csv", "heuristics.csv", "trust_site.csv"]:
        if f in fe:
            check(f"{n}: cabecera {f}", first_line(fe[f]) == appendline_after(forensic, f'files["{f}"]'))
    case = json.loads(fe["case.json"])
    keys = set(re.findall(r'put\("(\w+)"', forensic[forensic.index('files["case.json"]'):forensic.index('files["timeline.csv"]')]))
    check(f"{n}: claves de case.json ⊆ código", set(case) <= keys, str(set(case) - keys))
    check(f"{n}: sampleSchemaVersion presente", "sampleSchemaVersion" in case and "sampleSchemaVersion" in keys)

# --- Topología y Geometría
te = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd30_topology.zip"))
check("Topología: entradas y orden", list(te) == entries_in_order(topology), f"{list(te)}")
for f in ["cells.csv", "transitions.csv"]:
    check(f"Topología: cabecera {f}", first_line(te[f]) == appendline_after(topology, f'files["{f}"]'))
tkeys = set(re.findall(r'put\("(\w+)"', topology[topology.index('files["metadata.json"]'):]))
check("Topología: claves de metadata.json", set(json.loads(te["metadata.json"])) == tkeys,
      f"{sorted(json.loads(te['metadata.json']))} vs {sorted(tkeys)}")
ge = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd30_geometry.zip"))
check("Geometría: entradas y orden", list(ge) == entries_in_order(geometry), f"{list(ge)}")
for f in ["geometry_cells.csv", "geometry_edges.csv", "mobility_trips.csv"]:
    check(f"Geometría: cabecera {f}", first_line(ge[f]) == appendline_after(geometry, f'files["{f}"]'))
gkeys = set(re.findall(r'put\("(\w+)"', geometry[geometry.index('files["metadata.json"]'):]))
check("Geometría: claves de metadata.json", set(json.loads(ge["metadata.json"])) == gkeys,
      f"{sorted(json.loads(ge['metadata.json']))} vs {sorted(gkeys)}")

# --- Stable-Site
se = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd30_stable_site.zip"))
fn = db_src[db_src.index("fun getStableSiteExportFiles"):]
fn = fn[:fn.index("\n    }\n") if "\n    }\n" in fn else len(fn)]
expected = ["PRIVACY.txt", "sites.csv"] + re.findall(r'query\("([^"]+)"', fn)
check("Stable-Site: entradas y orden (nota primero)", list(se) == expected, f"{list(se)} vs {expected}")
note_text = re.search(r'val PRIVACY_NOTE = """(.*?)"""', note, re.S).group(1)
import textwrap
check("Stable-Site: PRIVACY.txt = StableSiteExportNote", se["PRIVACY.txt"].decode() == textwrap.dedent(note_text).strip("\n") + "\n")
check("Stable-Site: cabecera sites.csv", first_line(se["sites.csv"]) == appendline_after(fn, 'files["sites.csv"]'))
for f in expected[2:]:
    m = re.search(rf'query\("{re.escape(f)}",\s*"([^"]+)"', fn)
    check(f"Stable-Site: cabecera {f}", m is not None and first_line(se[f]) == m.group(1))

# --- Copias de seguridad: manifiesto y esquema
consts = {}
for text in storage.values():
    for k, v in re.findall(r'const val (\w+)\s*=\s*"([^"]*)"', text):
        consts.setdefault(k, v)
version = int(re.search(r"const val VERSION\s*=\s*(\d+)", storage["SchemaV25.kt"]).group(1))
check("Esquema: SchemaV25.VERSION = 25", version == 25, str(version))
mkeys = set(re.findall(r'put\("(\w+)"', manifest_src)) | set(re.findall(r'"(\w+)"\s*to\b', manifest_src))
backup_src = "".join(open(p, encoding="utf-8").read() for p in glob.glob(os.path.join(IC, "backup", "*.kt")))
names_code = set(re.findall(r'"([a-z_]+\.(?:json|db))"', backup_src))


def resolve(s):
    return re.sub(r"\$\{?(\w+)\}?", lambda m: consts.get(m.group(1), m.group(0)), s)


creates = set()
adds = set()
for text in storage.values():
    for m in re.finditer(r'CREATE TABLE IF NOT EXISTS (\$?\{?\w+\}?)', text):
        creates.add(resolve(m.group(1)))
    for m in re.finditer(r'ALTER TABLE (\$?\{?\w+\}?) ADD COLUMN (\$?\{?\w+\}?)', text):
        adds.add((resolve(m.group(1)), resolve(m.group(2))))
unresolved = [t for t in creates if "$" in t] + [f"{t}.{c}" for t, c in adds if "$" in t or "$" in c]

for kind in ["fresh", "upgraded", "empty"]:
    z = zentries(os.path.join(SAMPLES, f"SYNTHETIC_icd30_{kind}.icdbackup"))
    check(f"Copia {kind}: entradas", sorted(z) == ["icdetector_history.db", "manifest.json", "settings.json"], str(sorted(z)))
    man = json.loads(z["manifest.json"])
    check(f"Copia {kind}: claves del manifiesto ⊆ código", not mkeys or set(man) <= mkeys, f"{set(man) - mkeys}")
    check(f"Copia {kind}: nombres de entrada en el código", {"manifest.json", "icdetector_history.db", "settings.json"} <= names_code,
          str(names_code))
    with tempfile.NamedTemporaryFile(suffix=".db", delete=False) as t:
        t.write(z["icdetector_history.db"])
    con = sqlite3.connect(t.name)
    uv = con.execute("PRAGMA user_version").fetchone()[0]
    check(f"Copia {kind}: user_version = {version} = manifiesto", uv == version == man.get("schemaVersion"), f"{uv}, {man.get('schemaVersion')}")
    check(f"Copia {kind}: modo de diario DELETE (sin WAL)", con.execute("PRAGMA journal_mode").fetchone()[0] == "delete")
    tables = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    missing = sorted(c for c in creates if "$" not in c and c not in tables)
    check(f"Copia {kind}: tablas creadas por el código presentes", not missing, str(missing))
    cols_missing = []
    for tb, col in adds:
        if "$" in tb or "$" in col or tb not in tables:
            continue
        cols = {r[1] for r in con.execute(f'PRAGMA table_info("{tb}")')}
        if col not in cols:
            cols_missing.append(f"{tb}.{col}")
    check(f"Copia {kind}: columnas añadidas por migraciones presentes", not cols_missing, str(cols_missing))
    con.close()
    os.unlink(t.name)

if unresolved:
    print(f"(aviso) nombres sin resolver en el código, no comprobados: {len(unresolved)}")
print(f"\n{len(fails)} fallos")
sys.exit(1 if fails else 0)
