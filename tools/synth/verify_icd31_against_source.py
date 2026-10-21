#!/usr/bin/env python3
"""
Comprueba que las muestras sintéticas de ICdetector 3.1.0 (samples/synthetic/icd31) usan exactamente las cabeceras,
entradas, claves, esquema y códigos que escribe el código fuente de ICdetector 3.1.0 (versionCode 40). Lee el código
como texto (no lo ejecuta). Igual que verify_icd30_against_source.py, más lo propio de 3.1.0: esquema 26 (churn de H17),
57 columnas del historial, verificación externa retirada, motivos de «no evaluada» y forense sin latencia.

Uso: python3 tools/synth/verify_icd31_against_source.py <ruta a ICdetector 3.1.0> [carpeta icd31]
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
SAMPLES = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(__file__), "..", "..", "samples", "synthetic", "icd31")
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
check("Historial 3.1.0: 57 columnas en el código", len(hist_header.split(",")) == 57, str(len(hist_header.split(","))))
check("Historial: el churn va al final", hist_header.split(",")[-3:] == ["ChurnCells15m", "ChurnChanges15m", "ChurnMotion"])
check("Historial: cabecera = ExportUtils.CSV_HEADER", first_line(sample("SYNTHETIC_icd31_history.csv")) == hist_header)
rows = list(csv.reader(io.StringIO(sample("SYNTHETIC_icd31_history.csv").decode("utf-8"))))
check("Historial: todas las filas con 57 campos", all(len(r) == 57 for r in rows), str({len(r) for r in rows}))
check("Radio: cabecera = ExportUtils.SERVICE_STATE_CSV_HEADER",
      first_line(sample("SYNTHETIC_icd31_service_state.csv")) == kotlin_const(export_utils, "SERVICE_STATE_CSV_HEADER"))
H = rows[0]
build_commit = open(os.path.join(SRC, "BUILD_COMMIT"), encoding="utf-8").read().strip()
r31 = [r for r in rows[1:] if r[H.index("AppVersion")].startswith("3.1.0")]
older = [r for r in rows[1:] if not r[H.index("AppVersion")].startswith("3.1.0")]
check("Muestra: filas de 3.1.0 y anteriores en el mismo historial", len(r31) > 0 and len(older) > 0, f"{len(r31)} / {len(older)}")
check("Muestra: AppVersion de 3.1.0 = versionName+BUILD_COMMIT", all(r[H.index("AppVersion")] == f"3.1.0+{build_commit}" for r in r31))
motions = set(re.findall(r"enum class ChurnMotion \{ ([A-Z, ]+) \}", read("core/CellChurn.kt"))[0].replace(" ", "").split(","))
check("Churn: ChurnMotion de las filas 3.1.0 en el enum ChurnMotion", all(r[H.index("ChurnMotion")] in motions for r in r31), str(motions))
check("Churn: enteros ≥ 0 en las filas 3.1.0", all(r[H.index(c)].isdigit() for r in r31 for c in ("ChurnCells15m", "ChurnChanges15m")))
check("Churn: vacío en las filas anteriores a 3.1.0", all(r[H.index(c)] == "" for r in older for c in ("ChurnCells15m", "ChurnChanges15m", "ChurnMotion")))
check("Verificación retirada: Verified = PENDING y ApiLat/ApiLon vacías en 3.1.0",
      all(r[H.index("Verified")] == "PENDING" and r[H.index("ApiLat")] == "" and r[H.index("ApiLon")] == "" for r in r31))
hr = read("models/HeuristicReport.kt")
codes = set(re.findall(r'const val \w+_CODE = "([a-z_]+)"', hr)) | set(re.findall(r'^\s*\w+\("([a-z_]+)", "', read("models/MccAbstention.kt"), re.M))
check("NotEvaluatedHeuristics: H12(retirada) y H17(sombra) en el código", {"retirada", "sombra", "ta_gsm", "ta_stub", "ta_sin_verificar", "fix_impreciso"} <= codes, str(sorted(codes)))
parts = [p for r in r31 for p in r[H.index("NotEvaluatedHeuristics")].split(";")]
check("NotEvaluatedHeuristics: todas las filas 3.1.0 con H12(retirada) y H17(sombra)",
      all({"H12(retirada)", "H17(sombra)"} <= set(r[H.index("NotEvaluatedHeuristics")].split(";")) for r in r31))
bad = [p for p in parts if re.fullmatch(r"H\d{1,2}(\(([a-z_]+)\))?", p) is None or (("(" in p) and p[p.index("(") + 1:-1] not in codes)]
check("NotEvaluatedHeuristics: formato Hn / Hn(código) con códigos del código", not bad, str(bad[:5]))
check("CsvText: protección con apóstrofo en el código", "'" in csv_text and "fun guard" in csv_text)
guarded = [v for r in rows[1:] for v in r if v.startswith("'")]
check("Historial: la muestra incluye textos protegidos", len(guarded) > 0)

# --- Casos forenses
for path in sorted(glob.glob(os.path.join(SAMPLES, "SYNTHETIC_icd31_ICD-*.zip"))):
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
te = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd31_topology.zip"))
check("Topología: entradas y orden", list(te) == entries_in_order(topology), f"{list(te)}")
for f in ["cells.csv", "transitions.csv"]:
    check(f"Topología: cabecera {f}", first_line(te[f]) == appendline_after(topology, f'files["{f}"]'))
tkeys = set(re.findall(r'put\("(\w+)"', topology[topology.index('files["metadata.json"]'):]))
check("Topología: claves de metadata.json", set(json.loads(te["metadata.json"])) == tkeys,
      f"{sorted(json.loads(te['metadata.json']))} vs {sorted(tkeys)}")
ge = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd31_geometry.zip"))
check("Geometría: entradas y orden", list(ge) == entries_in_order(geometry), f"{list(ge)}")
for f in ["geometry_cells.csv", "geometry_edges.csv", "mobility_trips.csv"]:
    check(f"Geometría: cabecera {f}", first_line(ge[f]) == appendline_after(geometry, f'files["{f}"]'))
gkeys = set(re.findall(r'put\("(\w+)"', geometry[geometry.index('files["metadata.json"]'):]))
check("Geometría: claves de metadata.json", set(json.loads(ge["metadata.json"])) == gkeys,
      f"{sorted(json.loads(ge['metadata.json']))} vs {sorted(gkeys)}")

# --- Stable-Site
se = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd31_stable_site.zip"))
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
version = int(re.search(r"const val VERSION\s*=\s*(\d+)", storage["SchemaV26.kt"]).group(1))
check("Esquema: SchemaV26.VERSION = 26", version == 26, str(version))
check("Esquema: CellDbHelper usa el esquema 26", "DATABASE_VERSION = SchemaV26.VERSION" in db_src)
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

v26 = [(t, c) for t, c in re.findall(r"ALTER TABLE (\w+) ADD COLUMN \$(\w+)", storage["SchemaV26.kt"])]
v26 = [(t, consts.get(c, c)) for t, c in v26]
check("Esquema 26: tres columnas de churn en history", sorted(v26) == sorted([("history", "churn_cells_15m"), ("history", "churn_changes_15m"), ("history", "churn_motion")]), str(v26))
adds |= set(v26)
for kind in ["icd31"]:
    z = zentries(os.path.join(SAMPLES, f"SYNTHETIC_{kind}.icdbackup"))
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
    if kind == "icd31":
        rec = read("forensics/ForensicRecorder.kt")
        check("Forense 3.1.0: ForensicRecorder ya no escribe latencyState", 'put("latencyState"' not in rec)
        payloads = [p for (p,) in con.execute("SELECT s.payload_json FROM forensic_samples s JOIN forensic_cases c ON c.id=s.case_id WHERE c.case_code='ICD-2026-10-28-0004'")]
        check("Forense 3.1.0: muestras sin latencyState y con verified PENDING",
              payloads and all("latencyState" not in json.loads(p) and json.loads(p)["verified"] == "PENDING" for p in payloads))
        tl = zentries(os.path.join(SAMPLES, "SYNTHETIC_icd31_ICD-2026-10-28-0004.zip"))["timeline.csv"].decode()
        tr = list(csv.reader(io.StringIO(tl)))
        check("Forense 3.1.0: latency_state vacía en timeline.csv", all(r[tr[0].index("latency_state")] == "" for r in tr[1:]))
        man_ok = man.get("appVersion") == f"3.1.0+{build_commit}"
        check("Copia: appVersion del manifiesto = 3.1.0+BUILD_COMMIT", man_ok, str(man.get("appVersion")))
    con.close()
    os.unlink(t.name)

if unresolved:
    print(f"(aviso) nombres sin resolver en el código, no comprobados: {len(unresolved)}")
print(f"\n{len(fails)} fallos")
sys.exit(1 if fails else 0)
