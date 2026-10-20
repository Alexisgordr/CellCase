#!/usr/bin/env python3
"""
Muestras SINTÉTICAS de ICdetector 3.0.0: base de datos con esquema 25, copia de seguridad `.icdbackup` y las
exportaciones que haría ICdetector con esa misma base (historial, Radio, topología, geometría, Stable-Site y casos
forenses). Todo es inventado (PLMN 001-01, posiciones cerca de (0, 0), prefijo SYNTHETIC_).

Para qué sirve: es la REFERENCIA independiente con la que se comprueba CellCase (docs/backup-format.md §6).
- Las consultas se ejecutan con SQLite de verdad (el módulo sqlite3 de Python), con el SQL de los exportadores de
  ICdetector 3.0.0 (CellDbHelper.kt); el formato de cada campo se reproduce en Python a partir de ese código
  (D-052: el autor autoriza reproducir la lógica de exportación; las dos aplicaciones son GPL-3.0).
- CellCase reconstruye las exportaciones desde la base con su propio lector SQLite y código Kotlin; las pruebas
  comparan su resultado byte a byte con lo que escribe este script.

Se generan dos bases con los MISMOS datos:
- `fresh`: instalación nueva de 3.0.0 (columnas en el orden de onCreate).
- `upgraded`: base que viene de 2.x y se ha ido migrando: columnas añadidas con ALTER TABLE al final, filas antiguas
  sin los valores de esas columnas (SQLite devuelve el valor por defecto) y páginas libres por borrados.

Uso: python3 tools/synth/generate_icd30.py [carpeta]   (por defecto samples/synthetic/icd30)
Determinista: misma salida en cada ejecución.
"""
import hashlib
import io
import json
import os
import random
import sqlite3
import struct
import sys
import tempfile
import zipfile
from datetime import datetime, timedelta, timezone
from decimal import Decimal, ROUND_HALF_UP
from zoneinfo import ZoneInfo

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "samples", "synthetic", "icd30"))
TZ = ZoneInfo("Europe/Madrid")
APP_VERSION = "3.0.0+f5173a8"           # AppBuildId de la 3.0.0 publicada
APP_VERSION_CODE = 39
BACKUP_AT = datetime(2026, 10, 26, 11, 30, 5, 250000, tzinfo=timezone.utc)
EXPORT_AT = datetime(2026, 10, 26, 11, 32, 40, 0, tzinfo=timezone.utc)
EXPORT_DEVICE = ("SYNTHETIC Phone 3", "16 (API 36)")

# ───────────────────────────── formato Java/Kotlin/Android ─────────────────────────────

def java_double(x):
    """Double.toString() de Java."""
    if x != x:
        return "NaN"
    if x == 0:
        return "-0.0" if str(x).startswith("-") else "0.0"
    ax = abs(x)
    if 1e-3 <= ax < 1e7:
        s = repr(float(x))
        return s if ("." in s or "e" in s) else s + ".0"
    for digits in range(1, 18):
        m = f"{x:.{digits}e}"
        if float(m) == x:
            mant, exp = m.split("e")
            break
    mant = mant.rstrip("0")
    if mant.endswith("."):
        mant += "0"
    return f"{mant}E{int(exp)}"


def f32(x):
    return struct.unpack("f", struct.pack("f", x))[0]


def fmt(x, places):
    """String.format(Locale.ROOT, "%.Nf", x) de Java: redondeo HALF_UP sobre la representación decimal más corta del double."""
    q = Decimal(1).scaleb(-places)
    return str(Decimal(repr(float(x))).quantize(q, rounding=ROUND_HALF_UP))


def instant_ms(ms):
    """Instant.ofEpochMilli(ms).toString(): milisegundos solo si no son cero."""
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    base = dt.strftime("%Y-%m-%dT%H:%M:%S")
    r = ms % 1000
    return f"{base}.{r:03d}Z" if r else f"{base}Z"


def utc_iso(ms):
    """ObservationTime.utcIso: siempre con milisegundos."""
    dt = datetime.fromtimestamp(ms / 1000, tz=timezone.utc)
    return dt.strftime("%Y-%m-%dT%H:%M:%S") + f".{ms % 1000:03d}Z"


def local_text(ms):
    return datetime.fromtimestamp(ms / 1000, tz=TZ).strftime("%Y-%m-%d %H:%M:%S")


def kstr(v):
    """toString() de Kotlin para los valores que van a un CSV."""
    if v is None:
        return ""
    if isinstance(v, bool):
        return "true" if v else "false"
    if isinstance(v, float):
        return java_double(v)
    return str(v)


import re
FORMULA = set("=+-@＝＋－＠'")
PLAIN_NUMBER = re.compile(r"[+-]?(\d+(\.\d*)?|\.\d+)([eE][+-]?\d+)?")


def guard(s):
    if not s:
        return s
    if not (s[0] in FORMULA or ord(s[0]) < 0x20 or 0x7f <= ord(s[0]) <= 0x9f) or PLAIN_NUMBER.fullmatch(s):
        return s
    return "'" + s


def field(v):
    s = guard(kstr(v))
    return '"' + s.replace('"', '""') + '"' if any(c in s for c in ',"\n\r') else s


def quoted(v):
    return '"' + guard(kstr(v)).replace('"', '""') + '"'


# org.json de Android (JSONObject.toString(2))
class JNull:
    pass


NULL = JNull()


def j_num(n):
    if isinstance(n, bool):
        return "true" if n else "false"
    if isinstance(n, int):
        return str(n)
    if n == int(n) and abs(n) < 2 ** 63:
        return str(int(n))
    return java_double(n)


def j_str(s):
    out = ['"']
    for c in s:
        if c == '"': out.append('\\"')
        elif c == '\\': out.append('\\\\')
        elif c == '/': out.append('\\/')
        elif c == '\t': out.append('\\t')
        elif c == '\b': out.append('\\b')
        elif c == '\n': out.append('\\n')
        elif c == '\r': out.append('\\r')
        elif c == '\f': out.append('\\f')
        elif ord(c) <= 0x1f: out.append('\\u%04x' % ord(c))
        else: out.append(c)
    out.append('"')
    return "".join(out)


def j_dump(v, indent=2, level=0):
    pad = " " * (indent * (level + 1)); end = " " * (indent * level)
    if isinstance(v, dict):
        if not v: return "{}"
        return "{\n" + ",\n".join(pad + j_str(k) + ": " + j_dump(x, indent, level + 1) for k, x in v.items()) + "\n" + end + "}"
    if isinstance(v, list):
        if not v: return "[]"
        return "[\n" + ",\n".join(pad + j_dump(x, indent, level + 1) for x in v) + "\n" + end + "]"
    if v is None or isinstance(v, JNull): return "null"
    if isinstance(v, str): return j_str(v)
    return j_num(v)


def j_parse(text):
    """JSON con los tipos de org.json de Android: entero → int, decimal o exponente → float; orden conservado."""
    return json.loads(text, object_pairs_hook=dict, parse_float=float, parse_int=int)


def sha(b):
    return hashlib.sha256(b).hexdigest()


def sums(files):
    return ("\n".join(f"{sha(b)}  {n}" for n, b in files.items()) + "\n").encode()


def zip_bytes(entries, when_ms):
    buf = io.BytesIO()
    lt = datetime.fromtimestamp(when_ms / 1000, tz=TZ)
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            zi = zipfile.ZipInfo(name, date_time=(lt.year, lt.month, lt.day, lt.hour, lt.minute, lt.second))
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, data)
    return buf.getvalue()


def ms(dt):
    return int(round(dt.timestamp() * 1000))

# ───────────────────────────── esquema 25 ─────────────────────────────

RADIO_CTX = [("conn_status", "TEXT"), ("bandwidth_khz", "INTEGER"), ("bands", "TEXT"), ("additional_plmns", "TEXT"),
             ("csg_indicator", "INTEGER"), ("csg_identity", "INTEGER"), ("csg_name", "TEXT"), ("secondary_carriers", "TEXT"),
             ("service_state", "TEXT"), ("network_operator", "TEXT"), ("sim_operator", "TEXT"), ("network_roaming", "INTEGER")]
HISTORY_FRESH = ("CREATE TABLE IF NOT EXISTS history (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, net_type TEXT, cid TEXT, "
                 "mnc TEXT, tac TEXT, mcc TEXT, dbm INTEGER, verified TEXT, score INTEGER DEFAULT 100, failed_heuristics TEXT, lat REAL, "
                 "lon REAL, pci INTEGER, arfcn INTEGER, rsrq INTEGER, sinr INTEGER, threat_prob REAL DEFAULT 0, api_lat REAL, api_lon REAL, "
                 "ta INTEGER, ta_unit TEXT, radio TEXT, " + ", ".join(f"{n} {t}" for n, t in RADIO_CTX) + ")")
V20_HISTORY = ["ALTER TABLE history ADD COLUMN observed_at_ms INTEGER",
               "CREATE INDEX IF NOT EXISTS idx_history_observed_at ON history (observed_at_ms)",
               "ALTER TABLE history ADD COLUMN not_evaluated_heuristics TEXT",
               "ALTER TABLE history ADD COLUMN gps_accuracy_m REAL",
               "ALTER TABLE history ADD COLUMN app_version TEXT"]
OTHER = [
    "CREATE INDEX IF NOT EXISTS idx_cell_identity ON history (cid, mnc, tac, mcc)",
    "CREATE INDEX IF NOT EXISTS idx_timestamp ON history (timestamp)",
    "CREATE TABLE IF NOT EXISTS incidents (id INTEGER PRIMARY KEY AUTOINCREMENT, started_at TEXT NOT NULL, updated_at TEXT NOT NULL, ended_at TEXT, "
    "identity TEXT NOT NULL, cid TEXT NOT NULL, radio TEXT NOT NULL, state TEXT NOT NULL, highest_phase INTEGER NOT NULL, required_phases INTEGER NOT NULL, "
    "score INTEGER NOT NULL, anomaly_confidence REAL NOT NULL, reason TEXT NOT NULL, heuristic_snapshot TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS forensic_cases (id INTEGER PRIMARY KEY AUTOINCREMENT, case_code TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL, "
    "closed_at TEXT, state TEXT NOT NULL, cell_identity TEXT NOT NULL, highest_phase INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS forensic_samples (id INTEGER PRIMARY KEY AUTOINCREMENT, case_id INTEGER NOT NULL, wall_time_ms INTEGER NOT NULL, "
    "elapsed_time_ms INTEGER NOT NULL, event TEXT NOT NULL, payload_json TEXT NOT NULL, FOREIGN KEY(case_id) REFERENCES forensic_cases(id) ON DELETE CASCADE)",
    "CREATE INDEX IF NOT EXISTS idx_forensic_samples_case ON forensic_samples (case_id, id)",
    "CREATE TABLE IF NOT EXISTS cell_transitions (from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, observations INTEGER NOT NULL DEFAULT 0, "
    "trusted_observations INTEGER NOT NULL DEFAULT 0, last_status TEXT NOT NULL, last_seen_ms INTEGER NOT NULL, trip_count INTEGER NOT NULL DEFAULT 0, "
    "last_trip_id TEXT, mobility_first_seen_ms INTEGER, mobility_last_seen_ms INTEGER, PRIMARY KEY(from_identity, to_identity))",
    "CREATE INDEX IF NOT EXISTS idx_cell_transitions_seen ON cell_transitions(last_seen_ms)",
    "CREATE TABLE IF NOT EXISTS site_cell_evidence (site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, role TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, "
    "last_seen_ms INTEGER NOT NULL, observations INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(site_key,cell_identity,role))",
    "CREATE TABLE IF NOT EXISTS site_cell_days (site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, role TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(site_key,cell_identity,role,day))",
    "CREATE TABLE IF NOT EXISTS site_cell_events (event_key TEXT NOT NULL, site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, role TEXT NOT NULL, seen_ms INTEGER NOT NULL, PRIMARY KEY(event_key,cell_identity,role))",
    "CREATE TABLE IF NOT EXISTS site_motion_days (site_key TEXT NOT NULL, day TEXT NOT NULL, state TEXT NOT NULL, accuracy_band_m INTEGER, duration_s INTEGER NOT NULL, displacement_band_m INTEGER, PRIMARY KEY(site_key,day,state))",
    "CREATE TABLE IF NOT EXISTS site_holds (episode_id TEXT PRIMARY KEY NOT NULL, site_key TEXT NOT NULL, cell_identity TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, active INTEGER NOT NULL DEFAULT 1, forensic_opened_ms INTEGER)",
    "CREATE TABLE IF NOT EXISTS site_shadow_triggers (episode_id TEXT PRIMARY KEY NOT NULL, site_key TEXT, previous_identity TEXT, candidate_identity TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, "
    "last_seen_ms INTEGER NOT NULL, evaluated_ms INTEGER, closed_ms INTEGER, shadow_recorded INTEGER NOT NULL DEFAULT 0, reason TEXT, motion_state TEXT, feature_state TEXT, globally_known INTEGER, "
    "known_at_site INTEGER, seen_as_neighbour INTEGER, serving_days INTEGER, neighbour_days INTEGER, corroborated INTEGER NOT NULL DEFAULT 0)",
    "CREATE INDEX IF NOT EXISTS idx_site_events_seen ON site_cell_events(seen_ms)",
    "CREATE INDEX IF NOT EXISTS idx_site_cells_seen ON site_cell_evidence(last_seen_ms)",
    "CREATE TABLE IF NOT EXISTS site_rf_neighbours (site_key TEXT NOT NULL, fingerprint TEXT NOT NULL, radio TEXT NOT NULL, arfcn INTEGER NOT NULL, pci INTEGER NOT NULL, mcc TEXT, mnc TEXT, tac TEXT, "
    "first_seen_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, observations INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(site_key,fingerprint))",
    "CREATE TABLE IF NOT EXISTS site_rf_neighbour_days (site_key TEXT NOT NULL, fingerprint TEXT NOT NULL, day TEXT NOT NULL, PRIMARY KEY(site_key,fingerprint,day))",
    "CREATE INDEX IF NOT EXISTS idx_site_rf_neighbours_seen ON site_rf_neighbours(last_seen_ms)",
    "CREATE TABLE IF NOT EXISTS mobility_trips (trip_id TEXT PRIMARY KEY NOT NULL, started_at_ms INTEGER NOT NULL, last_seen_ms INTEGER NOT NULL, has_moving INTEGER NOT NULL DEFAULT 0, "
    "last_serving TEXT, same_serving_since_ms INTEGER NOT NULL, static_since_ms INTEGER, state TEXT NOT NULL, close_reason TEXT, closed_at_ms INTEGER)",
    "CREATE TABLE IF NOT EXISTS mobility_trip_cells (trip_id TEXT NOT NULL, cell_identity TEXT NOT NULL, PRIMARY KEY(trip_id,cell_identity), FOREIGN KEY(trip_id) REFERENCES mobility_trips(trip_id) ON DELETE CASCADE)",
    "CREATE TABLE IF NOT EXISTS mobility_trip_edges (trip_id TEXT NOT NULL, from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, PRIMARY KEY(trip_id,from_identity,to_identity), FOREIGN KEY(trip_id) REFERENCES mobility_trips(trip_id) ON DELETE CASCADE)",
    "CREATE UNIQUE INDEX IF NOT EXISTS idx_one_open_mobility_trip ON mobility_trips(state) WHERE state='OPEN'",
    "CREATE INDEX IF NOT EXISTS idx_mobility_trips_closed ON mobility_trips(state,closed_at_ms)",
    "CREATE TABLE IF NOT EXISTS service_state_events (id INTEGER PRIMARY KEY AUTOINCREMENT, ts_ms INTEGER NOT NULL, state TEXT NOT NULL, data_registered INTEGER, voice_registered INTEGER, "
    "searching INTEGER, roaming INTEGER NOT NULL DEFAULT 0, operator_numeric TEXT, operator_alpha TEXT, sim_operator TEXT, manual_selection INTEGER NOT NULL DEFAULT 0, channel_number INTEGER, "
    "cell_bandwidths TEXT, data_network TEXT, voice_network TEXT, source TEXT NOT NULL)",
    "CREATE INDEX IF NOT EXISTS idx_service_state_ts ON service_state_events(ts_ms)",
]
V20_OTHER = ["ALTER TABLE incidents ADD COLUMN started_at_ms INTEGER", "ALTER TABLE incidents ADD COLUMN updated_at_ms INTEGER",
             "ALTER TABLE forensic_cases ADD COLUMN created_at_ms INTEGER", "ALTER TABLE forensic_cases ADD COLUMN updated_at_ms INTEGER"]
V21 = ["ALTER TABLE history ADD COLUMN location_mode TEXT"]
V22 = [
    "CREATE TABLE IF NOT EXISTS local_profile (context_key TEXT NOT NULL, metric TEXT NOT NULL, radio TEXT NOT NULL, device_model TEXT NOT NULL, algorithm_version INTEGER NOT NULL, "
    "revision INTEGER NOT NULL DEFAULT 0, mean REAL, variance REAL, effective_samples INTEGER NOT NULL DEFAULT 0, bootstrap_samples INTEGER NOT NULL DEFAULT 0, live_samples INTEGER NOT NULL DEFAULT 0, "
    "quarantined_samples INTEGER NOT NULL DEFAULT 0, distinct_days INTEGER NOT NULL DEFAULT 0, last_day TEXT, day_samples INTEGER NOT NULL DEFAULT 0, day_start_mean REAL, first_evidence_ms INTEGER, "
    "last_evidence_ms INTEGER, last_sample_ms INTEGER, maturity TEXT NOT NULL, abstain_reason TEXT, updated_at_ms INTEGER NOT NULL, PRIMARY KEY (context_key, metric))",
    "CREATE TABLE IF NOT EXISTS profile_evaluations (id INTEGER PRIMARY KEY AUTOINCREMENT, history_id INTEGER NOT NULL UNIQUE REFERENCES history(id) ON DELETE CASCADE, "
    "evaluated_at_ms INTEGER NOT NULL, algorithm_version INTEGER NOT NULL, profile_revision TEXT, context_key TEXT NOT NULL, mode TEXT NOT NULL, deviation_level TEXT NOT NULL, "
    "deviation_score REAL, metrics_evaluated INTEGER NOT NULL, metrics_total INTEGER NOT NULL, evidence TEXT, abstain_reason TEXT, learned INTEGER NOT NULL DEFAULT 0, holdout INTEGER NOT NULL DEFAULT 0)",
    "CREATE INDEX IF NOT EXISTS idx_profile_eval_time ON profile_evaluations(evaluated_at_ms)",
    "CREATE TABLE IF NOT EXISTS profile_mode_events (id INTEGER PRIMARY KEY AUTOINCREMENT, at_ms INTEGER NOT NULL, mode TEXT NOT NULL, app_version TEXT)",
]
V23 = ["ALTER TABLE local_profile ADD COLUMN generation TEXT", "ALTER TABLE local_profile ADD COLUMN day_start_variance REAL",
       "ALTER TABLE local_profile ADD COLUMN last_seen_ms INTEGER"]
V24 = ["ALTER TABLE history ADD COLUMN observation_id TEXT", "ALTER TABLE profile_evaluations ADD COLUMN observation_id TEXT",
       "CREATE UNIQUE INDEX IF NOT EXISTS idx_profile_eval_observation ON profile_evaluations(observation_id)",
       "CREATE INDEX IF NOT EXISTS idx_history_observation ON history(observation_id)"]
V25 = ["ALTER TABLE cell_transitions ADD COLUMN last_evaluated_status TEXT", "ALTER TABLE cell_transitions ADD COLUMN last_evaluated_ms INTEGER",
       "ALTER TABLE cell_transitions ADD COLUMN last_explanation TEXT", "ALTER TABLE cell_transitions ADD COLUMN failed_count INTEGER NOT NULL DEFAULT 0",
       "ALTER TABLE cell_transitions ADD COLUMN failure_history TEXT"]

# Base de 2.x antigua: historial con pocas columnas; el resto llegó con migraciones (CellDbHelper.onUpgrade).
OLD_HISTORY = "CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT, net_type TEXT, cid TEXT, mnc TEXT, tac TEXT, dbm INTEGER)"
OLD_UPGRADES = [
    "ALTER TABLE history ADD COLUMN verified TEXT DEFAULT 'PENDING'",
    "ALTER TABLE history ADD COLUMN mcc TEXT DEFAULT 'N/A'",
    "ALTER TABLE history ADD COLUMN score INTEGER DEFAULT 100",
    "ALTER TABLE history ADD COLUMN failed_heuristics TEXT DEFAULT ''",
    "ALTER TABLE history ADD COLUMN lat REAL", "ALTER TABLE history ADD COLUMN lon REAL",
    "ALTER TABLE history ADD COLUMN pci INTEGER", "ALTER TABLE history ADD COLUMN arfcn INTEGER",
    "ALTER TABLE history ADD COLUMN rsrq INTEGER", "ALTER TABLE history ADD COLUMN sinr INTEGER",
    "ALTER TABLE history ADD COLUMN threat_prob REAL DEFAULT 0", "ALTER TABLE history ADD COLUMN api_lat REAL",
    "ALTER TABLE history ADD COLUMN api_lon REAL", "ALTER TABLE history ADD COLUMN ta INTEGER",
    "ALTER TABLE history ADD COLUMN ta_unit TEXT", "ALTER TABLE history ADD COLUMN radio TEXT",
] + [f"ALTER TABLE history ADD COLUMN {n} {t}" for n, t in RADIO_CTX]
OLD_TRANSITIONS = ("CREATE TABLE cell_transitions (from_identity TEXT NOT NULL, to_identity TEXT NOT NULL, observations INTEGER NOT NULL DEFAULT 0, "
                   "trusted_observations INTEGER NOT NULL DEFAULT 0, last_status TEXT NOT NULL, last_seen_ms INTEGER NOT NULL, PRIMARY KEY(from_identity, to_identity))")
OLD_TRANSITIONS_UP = ["ALTER TABLE cell_transitions ADD COLUMN trip_count INTEGER NOT NULL DEFAULT 0", "ALTER TABLE cell_transitions ADD COLUMN last_trip_id TEXT",
                      "ALTER TABLE cell_transitions ADD COLUMN mobility_first_seen_ms INTEGER", "ALTER TABLE cell_transitions ADD COLUMN mobility_last_seen_ms INTEGER",
                      "CREATE INDEX IF NOT EXISTS idx_cell_transitions_seen ON cell_transitions(last_seen_ms)"]

# ───────────────────────────── escenario sintético ─────────────────────────────

RNG = random.Random(20261020)
CELLS = {   # cid → (tac, earfcn, pci, dBm típico, verificación)
    "1000070": ("3001", 6300, 70, -100, "NOT_FOUND"), "1000980": ("3001", 1300, 98, -95, "VERIFIED"),
    "1000069": ("3001", 6300, 69, -92, "NOT_FOUND"), "1000071": ("3001", 1300, 71, -97, "VERIFIED"),
    "1000542": ("3002", 3100, 42, -90, "VERIFIED"), "1000543": ("3002", 6300, 43, -85, "VERIFIED"),
    "1000544": ("3002", 3100, 44, -95, "PENDING"), "1000545": ("3002", 3600, 45, -88, "NOT_FOUND"),
    "1000953": ("3003", 1300, 101, -108, "NOT_FOUND"),
}
BAND = {1300: "3", 3100: "7", 3600: "8", 6300: "20"}
SUB = "[sub-umbral] "


def ident(cid):
    return f"001-01-{CELLS[cid][0]}-{cid}-LTE"


def uuid(rng):
    h = "%032x" % rng.getrandbits(128)
    return f"{h[:8]}-{h[8:12]}-4{h[13:16]}-a{h[17:20]}-{h[20:32]}"


def build_rows():
    """Filas del historial en orden de inserción (id creciente). Las de 2.10.x no tienen instante UTC ni perfil."""
    RNG.seed(20261020)
    rows = []
    def add(t_utc, cid, reason="OK", earfcn=None, pci=None, dbm=None, lat=0.010, lon=0.020, legacy=False, csg_name=None, rsrq=None):
        tac, e0, p0, d0, ver = CELLS[cid]
        e = earfcn if earfcn is not None else e0
        d = dbm if dbm is not None else d0 + RNG.randint(-6, 6)
        gnss = RNG.random() < 0.9
        r = dict(timestamp=local_text(ms(t_utc)), net_type="4G", cid=cid, mnc="01", tac=tac, mcc="001", dbm=d, verified=ver,
                 score=100 if reason == "OK" else 95, failed_heuristics=reason,
                 lat=round(lat + RNG.uniform(-0.002, 0.002), 6) if gnss else None, lon=round(lon + RNG.uniform(-0.002, 0.002), 6) if gnss else None,
                 pci=pci if pci is not None else p0, arfcn=e, rsrq=rsrq if rsrq is not None else RNG.randint(-14, -7), sinr=RNG.randint(0, 20),
                 threat_prob=0.0 if reason == "OK" else round(RNG.uniform(4, 11), 1), api_lat=None, api_lon=None,
                 ta=0, ta_unit="STUB_ZERO", radio="LTE", conn_status="PRIMARY_SERVING", bandwidth_khz=10000 if e == 6300 else 20000,
                 bands=BAND.get(e, ""), additional_plmns=None, csg_indicator=None, csg_identity=None, csg_name=csg_name,
                 secondary_carriers=None, service_state="IN_SERVICE", network_operator="00101", sim_operator="00101", network_roaming=0,
                 observed_at_ms=None if legacy else ms(t_utc), not_evaluated_heuristics=None if legacy else RNG.choice(["NONE", "NONE", "H9", "H1;H9"]),
                 gps_accuracy_m=None if (legacy or not gnss) else round(RNG.uniform(3, 40), 1), app_version=None if legacy else APP_VERSION,
                 location_mode=None if legacy else "INTELLIGENT", observation_id=None if legacy else uuid(RNG), _legacy=legacy)
        if r["csg_name"]:
            r["csg_indicator"] = 1; r["csg_identity"] = 4321
        rows.append(r)
        return r
    # 2.10.x (antes de actualizar a 3.0): sin observed_at_ms
    t = datetime(2026, 10, 15, 6, 0, 3, tzinfo=timezone.utc)
    while t < datetime(2026, 10, 18, 9, 0, tzinfo=timezone.utc):
        add(t, "1000070" if RNG.random() < 0.55 else RNG.choice(["1000980", "1000071", "1000069"]), legacy=True)
        t += timedelta(minutes=RNG.choice([10, 15, 20]), seconds=RNG.randint(0, 50))
    # 3.0: instante UTC con milisegundos; pasa por el cambio de hora del 25/10 (03:00 CEST → 02:00 CET)
    t = datetime(2026, 10, 18, 9, 5, 7, 123000, tzinfo=timezone.utc)
    end = datetime(2026, 10, 26, 11, 0, tzinfo=timezone.utc)
    day = ["1000980", "1000071", "1000542", "1000544", "1000069"]
    while t < end:
        lh = t.astimezone(TZ).hour
        cid = "1000070" if (lh < 8 or lh >= 21 or RNG.random() < 0.45) else RNG.choice(day)
        add(t, cid)
        t += timedelta(minutes=RNG.choice([10, 15, 20, 30]), seconds=RNG.randint(0, 50), milliseconds=RNG.randint(0, 999))
    # hora repetida del cambio de hora: dos filas con el mismo texto local y distinto instante
    a = add(datetime(2026, 10, 25, 0, 30, 0, 500000, tzinfo=timezone.utc), "1000070")
    b = add(datetime(2026, 10, 25, 1, 30, 0, 500000, tzinfo=timezone.utc), "1000070")
    assert a["timestamp"] == b["timestamp"]
    # patrones (como generate_patterns.py)
    def band_change(at, prev, pe, cid, e, f1, f2, b1, b2):
        add(at - timedelta(seconds=25), prev, earfcn=pe)
        return add(at, cid, SUB + f"Downgrade de banda forzado ({f1}MHz→{f2}MHz, B{b1}→B{b2})", earfcn=e)
    band_change(datetime(2026, 10, 19, 5, 23, 20, 40000, tzinfo=timezone.utc), "1000980", 1300, "1000069", 6300, 1805, 791, 3, 20)
    band_change(datetime(2026, 10, 21, 10, 2, 12, 900000, tzinfo=timezone.utc), "1000544", 3100, "1000545", 3600, 2620, 925, 7, 8)
    band_change(datetime(2026, 10, 21, 10, 33, 7, tzinfo=timezone.utc), "1000542", 3100, "1000543", 6300, 2620, 791, 7, 20)
    p = add(datetime(2026, 10, 22, 14, 48, 53, 310000, tzinfo=timezone.utc), "1000070", SUB + "Potencia anómala vs historial (+19dB)", dbm=-81)
    # misma lectura guardada dos veces (fila periódica + alarma confirmada): comparten observation_id
    dup = dict(p); dup["failed_heuristics"] = SUB + "Potencia anómala vs historial (+19dB) [confirmada 3/3]"; rows.append(dup)
    for i, (s, pci) in enumerate([(20, 101), (44, 102), (70, 101), (96, 102)]):
        add(datetime(2026, 10, 23, 11, 53, 0, tzinfo=timezone.utc) + timedelta(seconds=s), "1000953",
            "OK" if pci == 101 else SUB + "Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)", pci=pci, lat=0.03, lon=0.04)
    # textos que ICdetector 3.0 protege con apóstrofo (CsvText)
    add(datetime(2026, 10, 24, 9, 1, 2, tzinfo=timezone.utc), "1000071", csg_name="=Femto,\"casa\"")
    add(datetime(2026, 10, 24, 9, 11, 2, tzinfo=timezone.utc), "1000071", csg_name="-Femto")
    add(datetime(2026, 10, 24, 9, 21, 2, tzinfo=timezone.utc), "1000071", csg_name="'ya protegido")
    return rows


def profile_for(r, rng, idx):
    """Evaluación del perfil local (modo sombra) de una fila de 3.0, o None."""
    if r["_legacy"] or rng.random() < 0.05:
        return None
    if idx < 40:
        return dict(mode="SHADOW", deviation_level="ABSTAIN", deviation_score=None, metrics_evaluated=0, metrics_total=2,
                    abstain_reason=rng.choice(["NO_PROFILE", "PROFILE_IMMATURE"]), evidence=None, profile_revision=None)
    mean = CELLS[r["cid"]][3]
    z = abs(r["dbm"] - mean) / 3.0
    if "Potencia" in r["failed_heuristics"]:
        z = abs(r["dbm"] - mean) / 3.0
    lvl = "NONE" if z < 2 else "LOW" if z < 3 else "MODERATE" if z < 4 else "HIGH"
    ev = f"SIGNAL_DBM {r['dbm']} vs {mean:.3f}±3.000 z={z:.3f}; SIGNAL_RSRQ {r['rsrq']} vs -10.500±1.500 z={abs(r['rsrq'] + 10.5) / 1.5:.3f}"
    return dict(mode="SHADOW", deviation_level=lvl, deviation_score=round(z * 100) / 100, metrics_evaluated=2, metrics_total=2, abstain_reason=None,
                evidence=ev, profile_revision=f"SIGNAL_DBM@g1790000000000:{idx};SIGNAL_RSRQ@g1790000000000:{idx - 3}")


def payload(rng, t_ms, cid, phase, logs, secondary=True, trust=True, gps=True):
    """Muestra como la escribe ForensicRecorder.payload de ICdetector 3.0.0 (mismas claves y nulos explícitos)."""
    tac, e, pci, d, _ = CELLS[cid]
    def cell(registered, idn, mcc, mnc, tac_, cid_, radio, nt, dbm, pci_, arfcn, band, rsrq, sinr, ta, unit, conn, bw, bands, secondary_=None):
        c = dict(registered=registered, identity=idn, mcc=mcc, mnc=mnc, tac=tac_, cid=cid_, radio=radio, networkType=nt, dbm=dbm, pci=pci_,
                 arfcn=arfcn, band=band, rsrq=rsrq, sinr=sinr, ta=ta, taUnit=unit, connectionState=conn, bandwidthKhz=bw, bands=bands, csgIndicator=None)
        if secondary_:
            c["secondaryCarriers"] = secondary_
        return c
    serving = cell(True, ident(cid), "001", "01", tac, cid, "LTE", "LTE", d, pci, e, int(BAND[e]), -11, 12, 3, "LTE_INDEX", "PRIMARY_SERVING", 20000,
                   [int(BAND[e])], ["NR:632628:301"] if secondary else None)
    neigh = [cell(False, ident("1000071"), "001", "01", "3001", "1000071", "LTE", "LTE", -104, 71, 1300, 3, -14, None, None, "UNKNOWN", "NONE", None, [3])]
    if secondary:
        neigh.append(cell(False, "N/A-N/A-N/A-N/A-NR", "N/A", "N/A", "N/A", "N/A", "NR", "NR", -95, 301, 632628, None, -12, None, None, "UNKNOWN",
                          "SECONDARY_SERVING", None, []))
    p = dict(schemaVersion=1, device="SYNTHETIC Phone 3", android="16", serving=serving, neighbors=neigh,
             gps=dict(lat=0.0101, lon=0.0202, accuracyM=8.5) if gps else None, latencyState="NORMAL", phase=phase, requiredPhases=3,
             score=95 - 5 * phase, anomalyConfidence=6.5 + phase, verified="NOT_FOUND", reason=SUB + "Potencia anómala vs historial (+19dB)",
             dataQualityReason=None if phase != 2 else "H8: sin RSRQ en 2 de 5 lecturas",
             localTrust=dict(state="CONTRADICTED" if trust else "TRUSTED", confidencePercent=62 if trust else 91,
                             contradictions=["SIGNAL_JUMP", "PCI_FLAP"] if trust else []),
             stableSite=dict(siteKey="a1b2c3d4e5f60718293a" if trust else None, featureState="SHADOW_READY", novelty="KNOWN_CELL", wouldTrigger=False,
                             enforced=False, reason="NOT_NOVEL", staticDurationSeconds=420, locationAccuracyM=12.5 if trust else None, servingDays=6, neighbourDays=2),
             heuristics="H1=PASSED;H3=FAILED;H9=NOT_EVALUATED(sin datos)",
             diagnostics=[dict(id=3, name="Potencia", status="FAILED", explanation="+19 dB sobre la referencia, \"sin\" contexto"),
                          dict(id=9, name="Latencia", status="NOT_EVALUATED", explanation="Sin datos móviles")],
             capabilities=dict(timingAdvance=True, rsrq=True, sinr=True, arfcn=True, gps=gps, hardwareCiphering=False),
             logs=logs)
    return json.dumps(p, ensure_ascii=False)


def fill(db, upgraded):
    """Inserta los mismos datos en las dos variantes. En `upgraded`, parte se inserta antes de algunas migraciones."""
    rng = random.Random(7)
    rows = build_rows()
    legacy = [r for r in rows if r["_legacy"]]
    modern = [r for r in rows if not r["_legacy"]]
    cols_all = [c for c in rows[0] if not c.startswith("_")]

    def insert(r, cols):
        db.execute(f"INSERT INTO history ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})", [r[c] for c in cols])

    if upgraded:
        # tabla de una versión anterior que se borró después: deja páginas en la lista de libres
        db.execute("CREATE TABLE legacy_scratch (x TEXT)")
        db.executemany("INSERT INTO legacy_scratch VALUES (?)", [("z" * 300,)] * 400)
        old = ["timestamp", "net_type", "cid", "mnc", "tac", "dbm"]
        # 1) filas de 2.0: solo las columnas originales; verified/mcc/score/... toman el valor por defecto de su ALTER
        for r in legacy[:30]:
            insert(r, old)
            r.update(verified="PENDING", mcc="N/A", score=100, failed_heuristics="", threat_prob=0.0)
            for k in ("lat", "lon", "pci", "arfcn", "rsrq", "sinr", "api_lat", "api_lon", "ta", "ta_unit", "radio") + tuple(n for n, _ in RADIO_CTX):
                r[k] = None
        for s in OLD_UPGRADES:
            db.execute(s)
        legacy_cols = [c for c in cols_all if c not in ("observed_at_ms", "not_evaluated_heuristics", "gps_accuracy_m", "app_version", "location_mode", "observation_id")]
        for r in legacy[30:]:
            insert(r, legacy_cols)
        # borrado de filas antiguas (retención): deja páginas libres
        db.execute("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY id LIMIT 12 OFFSET 40)")
        for i in range(12):
            legacy[40 + i]["_deleted"] = True
        for s in V20_HISTORY + V21 + V24[:1]:
            db.execute(s)
    else:
        for r in legacy:
            insert(r, cols_all)
        db.execute("DELETE FROM history WHERE id IN (SELECT id FROM history ORDER BY id LIMIT 12 OFFSET 40)")
        for i in range(12):
            legacy[40 + i]["_deleted"] = True
    for r in modern:
        insert(r, cols_all)
    # perfil local: una evaluación por observación, ligada a la primera fila de la lectura
    hist = db.execute("SELECT id, observation_id FROM history ORDER BY id").fetchall()
    by_id = {}
    live = [r for r in rows if not r.get("_deleted")]
    seen_obs = set()
    n_eval = 0
    for (hid, obs), r in zip(hist, live):
        by_id[hid] = r
        if obs is None or obs in seen_obs:
            continue
        seen_obs.add(obs)
        ev = profile_for(r, rng, n_eval)
        if ev is None:
            continue
        n_eval += 1
        holdout = 1 if rng.random() < 0.1 else 0
        db.execute("INSERT INTO profile_evaluations (history_id, evaluated_at_ms, algorithm_version, profile_revision, context_key, mode, deviation_level, "
                   "deviation_score, metrics_evaluated, metrics_total, evidence, abstain_reason, learned, holdout, observation_id) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (hid, r["observed_at_ms"] + 35, 2, ev["profile_revision"], ident(r["cid"]), ev["mode"], ev["deviation_level"], ev["deviation_score"],
                    ev["metrics_evaluated"], ev["metrics_total"], ev["evidence"], ev["abstain_reason"], 0 if holdout else 1, holdout, obs))
    db.execute("INSERT INTO profile_mode_events (at_ms, mode, app_version) VALUES (?,?,?)", (ms(datetime(2026, 10, 18, 9, 0, tzinfo=timezone.utc)), "SHADOW", APP_VERSION))
    for cid in ("1000070", "1000980", "1000071"):
        for metric, mean in (("SIGNAL_DBM", CELLS[cid][3]), ("SIGNAL_RSRQ", -10.5)):
            db.execute("INSERT INTO local_profile (context_key, metric, radio, device_model, algorithm_version, revision, mean, variance, effective_samples, "
                       "bootstrap_samples, live_samples, quarantined_samples, distinct_days, last_day, day_samples, day_start_mean, first_evidence_ms, last_evidence_ms, "
                       "last_sample_ms, maturity, abstain_reason, updated_at_ms, generation, day_start_variance, last_seen_ms) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                       (ident(cid), metric, "LTE", "synthetic/phone 3", 2, 60, float(mean), 9.0, 120, 40, 80, 3, 7, "2026-10-26", 12, float(mean), ms(datetime(2026, 10, 18, 9, 0, tzinfo=timezone.utc)),
                        ms(datetime(2026, 10, 26, 10, 0, tzinfo=timezone.utc)), ms(datetime(2026, 10, 26, 10, 0, tzinfo=timezone.utc)), "MATURE", None,
                        ms(datetime(2026, 10, 26, 10, 0, tzinfo=timezone.utc)), "g1790000000000", 8.5, ms(datetime(2026, 10, 26, 10, 0, tzinfo=timezone.utc))))

    # Radio
    t0 = datetime(2026, 10, 18, 9, 0, 0, 250000, tzinfo=timezone.utc)
    events = [(t0, "IN_SERVICE", "Red de Pruebas", "CALLBACK")]
    for d in (1, 3, 4, 6):
        a = t0 + timedelta(days=d, hours=3, minutes=7 * d)
        events += [(a, "OUT_OF_SERVICE", None, "CALLBACK"), (a + timedelta(minutes=2), "IN_SERVICE", "-Red de Pruebas-", "POLL")]
    events.append((t0 + timedelta(days=5), "POWER_OFF", "=Red", "CALLBACK"))
    events.append((t0 + timedelta(days=5), "IN_SERVICE", "Red de Pruebas", "CALLBACK"))   # misma hora: orden por id
    for ts, st, alpha, src in events:
        ok = st == "IN_SERVICE"
        db.execute("INSERT INTO service_state_events (ts_ms, state, data_registered, voice_registered, searching, roaming, operator_numeric, operator_alpha, "
                   "sim_operator, manual_selection, channel_number, cell_bandwidths, data_network, voice_network, source) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (ms(ts), st, int(ok), int(ok), None if st == "POWER_OFF" else 0, 0, "00101" if ok else None, alpha, "00101", 0, 1300 if ok else None,
                    "20000;10000" if ok else "", "LTE" if ok else None, "LTE" if ok else None, src))

    # Topología: rutas a partir del historial de 3.0
    trans = {}
    prev = None
    for r in [r for r in modern]:
        idn = ident(r["cid"])
        if prev is not None and prev != idn:
            k = (prev, idn)
            o = trans.setdefault(k, dict(obs=0, trusted=0, status="PASSED", seen=0))
            o["obs"] += 1
            st = rng.choice(["PASSED", "PASSED", "PASSED", "FAILED", "NOT_EVALUATED"])
            o["trusted"] += 1 if st == "PASSED" else 0
            o["status"] = st; o["seen"] = r["observed_at_ms"]
            o["failed"] = o.get("failed", 0) + (1 if st == "FAILED" else 0)
        prev = idn
    if upgraded:
        db.execute("DROP TABLE cell_transitions")
        db.execute(OLD_TRANSITIONS)
        items = list(trans.items())
        for (f, t_), o in items[:6]:     # rutas de 2.x: sin columnas de movilidad ni de esquema 25
            db.execute("INSERT INTO cell_transitions (from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms) VALUES (?,?,?,?,?,?)",
                       (f, t_, o["obs"], o["trusted"], o["status"], o["seen"]))
        for s in OLD_TRANSITIONS_UP:
            db.execute(s)
        rest = items[6:]
    else:
        rest = list(trans.items())
    for (f, t_), o in rest:
        db.execute("INSERT INTO cell_transitions (from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms) VALUES (?,?,?,?,?,?)",
                   (f, t_, o["obs"], o["trusted"], o["status"], o["seen"]))
    # misma hora de última observación en dos rutas (orden de empate)
    two = list(trans)[:2]
    db.execute("UPDATE cell_transitions SET last_seen_ms=? WHERE (from_identity=? AND to_identity=?) OR (from_identity=? AND to_identity=?)",
               (trans[two[0]]["seen"], two[0][0], two[0][1], two[1][0], two[1][1]))
    if upgraded:
        for s in V25:
            db.execute(s)
    else:
        for s in V25:
            db.execute(s)
    db.execute("UPDATE cell_transitions SET last_evaluated_status=last_status, last_evaluated_ms=last_seen_ms WHERE last_status IN ('PASSED','FAILED')")
    db.execute("UPDATE cell_transitions SET failure_history=CASE WHEN last_status='FAILED' THEN 'LEGACY_FAILED' ELSE 'LEGACY_UNKNOWN' END WHERE observations>0")

    # Geometría: viajes
    idents = sorted({ident(c) for c in CELLS})
    keys = list(trans)
    trips = []
    base = datetime(2026, 10, 19, 7, 0, tzinfo=timezone.utc)
    for i in range(6):
        tid = f"trip-{i:02d}-{uuid(rng)[:8]}"
        st = base + timedelta(days=i, minutes=13 * i)
        state = "OPEN" if i == 5 else "CLOSED"
        cells = [k[0] for k in keys[i:i + 3]] + [keys[i + 2][1]] if i != 3 else [keys[3][0]]
        edges = keys[i:i + 3] if i != 3 else []
        has_moving = 0 if i == 3 else 1
        db.execute("INSERT INTO mobility_trips (trip_id, started_at_ms, last_seen_ms, has_moving, last_serving, same_serving_since_ms, static_since_ms, state, close_reason, closed_at_ms) "
                   "VALUES (?,?,?,?,?,?,?,?,?,?)", (tid, ms(st), ms(st + timedelta(minutes=40)), has_moving, cells[-1], ms(st + timedelta(minutes=30)), None,
                                                     state, None if state == "OPEN" else ("STATIC_CONFIRMED" if i != 3 else "ABANDONED"),
                                                     None if state == "OPEN" else ms(st + timedelta(minutes=41))))
        for c in dict.fromkeys(cells):
            db.execute("INSERT INTO mobility_trip_cells (trip_id, cell_identity) VALUES (?,?)", (tid, c))
        for (f, t_) in edges:
            db.execute("INSERT INTO mobility_trip_edges (trip_id, from_identity, to_identity) VALUES (?,?,?)", (tid, f, t_))
        trips.append((tid, state, edges, st, i))
    # el viaje abierto añade una arista que aún no existe como ruta
    open_trip = trips[-1][0]
    db.execute("INSERT INTO mobility_trip_edges (trip_id, from_identity, to_identity) VALUES (?,?,?)", (open_trip, ident("1000545"), ident("1000953")))
    db.execute("INSERT INTO mobility_trip_cells (trip_id, cell_identity) VALUES (?,?)", (open_trip, ident("1000953")))
    db.execute("UPDATE mobility_trips SET last_serving=? WHERE trip_id=?", (ident("1000953"), open_trip))
    # viajes confirmados: trip_count de las rutas recorridas
    for tid, state, edges, st, i in trips:
        if state != "CLOSED" or not edges:
            continue
        for (f, t_) in edges:
            db.execute("UPDATE cell_transitions SET trip_count=trip_count+1,last_trip_id=?,mobility_first_seen_ms=COALESCE(mobility_first_seen_ms,?),mobility_last_seen_ms=? "
                       "WHERE from_identity=? AND to_identity=?", (tid, ms(st), ms(st + timedelta(minutes=41)), f, t_))
    # tres viajes más por la misma ruta → KNOWN_ON_ROUTE en sus celdas
    for (f, t_) in keys[:3]:
        db.execute("UPDATE cell_transitions SET trip_count=trip_count+3 WHERE from_identity=? AND to_identity=?", (f, t_))

    # Stable-Site
    sk = ["a1b2c3d4e5f60718293a", "0f1e2d3c4b5a69788796", "ffeeddccbbaa99887766"]
    days = [f"2026-10-{d:02d}" for d in range(18, 27)]
    for si, key in enumerate(sk):
        for ci, cid in enumerate(["1000070", "1000980", "1000071"][: 3 - si]):
            for role in ("SERVING", "NEIGHBOUR"):
                if role == "NEIGHBOUR" and si == 2:
                    continue
                f_ = ms(datetime(2026, 10, 18 + ci, 8, tzinfo=timezone.utc)); l_ = ms(datetime(2026, 10, 25, 20 - ci, tzinfo=timezone.utc))
                db.execute("INSERT INTO site_cell_evidence VALUES (?,?,?,?,?,?)", (key, ident(cid), role, f_, l_, 40 - 10 * ci - 5 * si if role == "SERVING" else 7))
                for d in days[: 8 - 2 * si - ci]:
                    db.execute("INSERT INTO site_cell_days VALUES (?,?,?,?)", (key, ident(cid), role, d))
        for d in days[: 6 - 2 * si]:
            db.execute("INSERT INTO site_motion_days VALUES (?,?,?,?,?,?)", (key, d, "STATIC_CONFIRMED", 20, 3600 * (2 + si), 50))
        db.execute("INSERT INTO site_motion_days VALUES (?,?,?,?,?,?)", (key, days[0], "MOVING", None, 600, None))
        if si < 2:
            fp = f"LTE:1300:{101 + si}"
            db.execute("INSERT INTO site_rf_neighbours VALUES (?,?,?,?,?,?,?,?,?,?,?)", (key, fp, "LTE", 1300, 101 + si, "001", "01", None,
                                                                                        ms(datetime(2026, 10, 19, tzinfo=timezone.utc)), ms(datetime(2026, 10, 24, tzinfo=timezone.utc)), 9 - si))
            for d in days[: 4 - si]:
                db.execute("INSERT INTO site_rf_neighbour_days VALUES (?,?,?)", (key, fp, d))
        db.execute("INSERT INTO site_cell_events VALUES (?,?,?,?,?)", (f"ev{si}", key, ident("1000070"), "SERVING", ms(datetime(2026, 10, 25, tzinfo=timezone.utc))))
    db.execute("INSERT INTO site_holds VALUES (?,?,?,?,?,?,?)", ("ep-hold-1", sk[0], ident("1000069"), ms(datetime(2026, 10, 19, 5, tzinfo=timezone.utc)),
                                                               ms(datetime(2026, 10, 19, 5, 30, tzinfo=timezone.utc)), 0, None))
    for i, r in enumerate([r for r in modern if "Downgrade" in r["failed_heuristics"]] + [modern[100], modern[200]]):
        f_ = r["observed_at_ms"]
        db.execute("INSERT INTO site_shadow_triggers VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                   (f"ep-{i:03d}", sk[i % 2] if i != 4 else None, ident("1000980") if i % 2 == 0 else None, ident(r["cid"]), f_, f_ + 40_000 + 1000 * i,
                    f_ + 5000 if i != 3 else None, f_ + 60_000 if i != 2 else None, 1 if i % 3 else 0, "NOVEL_CELL" if i % 2 == 0 else "KNOWN_CELL",
                    "STATIC_CONFIRMED" if i % 2 else "MOVING", "SHADOW_READY", i % 2, 0, 1 if i == 1 else None, 6 - i, 2, 1 if i == 0 else 0))

    # Incidentes
    p = next(r for r in modern if "Potencia" in r["failed_heuristics"])
    db.execute("INSERT INTO incidents (started_at, updated_at, ended_at, identity, cid, radio, state, highest_phase, required_phases, score, anomaly_confidence, reason, heuristic_snapshot, started_at_ms, updated_at_ms) "
               "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)", (p["timestamp"], p["timestamp"], p["timestamp"], ident("1000070"), "1000070", "LTE", "CONFIRMED", 3, 3, 80, 8.5,
                                                         "Potencia anómala", "{}", p["observed_at_ms"], p["observed_at_ms"]))

    # Casos forenses: muestras con ventanas de terminal que se solapan (y líneas repetidas)
    def window(i):
        return [f"12:0{i}:{s:02d} H3 FAIL" if s % 3 == 0 else f"12:0{i}:{s:02d} ciclo {s} servidora 001-01-3001-1000070-LTE dBm -97 RSRQ -11 SINR 12 vecinas 2 latencia normal" for s in range(0, 40)] + ["watchdog ok", "watchdog ok"]
    cases = [("ICD-2026-10-22-0001", "READY", ident("1000070"), 3, 1, p["observed_at_ms"], 5),
             ("ICD-OBS-2026-10-23-0002", "INTERRUPTED", ident("1000953"), 1, 0, ms(datetime(2026, 10, 23, 11, 53, 44, tzinfo=timezone.utc)), 2)]
    for code, state, idn, phase, conf, start, n in cases:
        created = local_text(start)
        cur = db.execute("INSERT INTO forensic_cases (case_code, created_at, updated_at, closed_at, state, cell_identity, highest_phase, confirmed, created_at_ms, updated_at_ms) "
                         "VALUES (?,?,?,?,?,?,?,?,?,?)", (code, created, local_text(start + 300_000), local_text(start + 360_000), state, idn, phase, conf, start, start + 300_000))
        cid_ = cur.lastrowid
        prev_logs = []
        for k in range(n):
            logs = (prev_logs[10:] if prev_logs else []) + window(k)[:40 - (30 if prev_logs else 0)] if k else window(0)
            prev_logs = logs
            db.execute("INSERT INTO forensic_samples (case_id, wall_time_ms, elapsed_time_ms, event, payload_json) VALUES (?,?,?,?,?)",
                       (cid_, start + 30_000 * k, 1_000_000 + 30_000 * k, "SAMPLE" if k else "OPEN",
                        payload(rng, start + 30_000 * k, idn.split("-")[3], min(k + 1, 3), logs, secondary=(k % 2 == 0), trust=(code.startswith("ICD-OBS") or k > 0), gps=(k != 3))))
    # caso abierto y vacío (sin muestras): ICdetector lo exporta igual
    db.execute("INSERT INTO forensic_cases (case_code, created_at, updated_at, closed_at, state, cell_identity, highest_phase, confirmed, created_at_ms, updated_at_ms) VALUES (?,?,?,?,?,?,?,?,?,?)",
               ("ICD-2026-10-26-0003", local_text(ms(BACKUP_AT) - 60_000), local_text(ms(BACKUP_AT) - 60_000), None, "CAPTURING", ident("1000980"), 1, 0, ms(BACKUP_AT) - 60_000, ms(BACKUP_AT) - 60_000))
    return rows


def create_db(path, upgraded):
    if os.path.exists(path):
        os.remove(path)
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("PRAGMA page_size=4096")
    db.execute("PRAGMA journal_mode=DELETE")
    db.execute("BEGIN")
    if upgraded:
        db.execute(OLD_HISTORY)
    else:
        db.execute(HISTORY_FRESH)
    # resto de tablas (en upgraded, el historial todavía no tiene todas sus columnas: los índices se crean después)
    for s in OTHER:
        if upgraded and "ON history" in s:
            continue
        db.execute(s)
    for s in V20_OTHER + V22 + V23:
        db.execute(s)
    db.execute(V24[1]); db.execute(V24[2])
    if not upgraded:
        for s in V20_HISTORY + V21 + [V24[0], V24[3]]:
            db.execute(s)
    rows = fill(db, upgraded)
    if upgraded:
        for s in OTHER:
            if "ON history" in s:
                db.execute(s)
        db.execute(V24[3])
        db.execute("DROP TABLE legacy_scratch")
    db.execute("COMMIT")
    db.execute("PRAGMA user_version=25")
    db.close()
    return rows

# ───────────────────────────── exportadores de ICdetector 3.0.0 ─────────────────────────────

VERIFIED = {"PENDING", "VERIFIED", "NOT_FOUND", "REJECTED", "ERROR"}
TA_UNITS = {"LTE_INDEX", "GSM_INDEX", "NR_RAW", "STUB_ZERO", "UNKNOWN"}
RADIOS = {"GSM", "UMTS", "LTE", "NR", "UNKNOWN"}
CONN = {"PRIMARY_SERVING", "SECONDARY_SERVING", "NONE", "UNKNOWN"}
HEUR = {"PASSED", "FAILED", "NOT_EVALUATED"}
SERVICE = {"IN_SERVICE", "OUT_OF_SERVICE", "EMERGENCY_ONLY", "POWER_OFF", "UNKNOWN"}
SOURCE = {"CALLBACK", "POLL"}


def get_int(v):
    """Cursor.getInt de Android: NULL → 0, texto → número inicial, real → truncado, 32 bits."""
    if v is None:
        return 0
    if isinstance(v, str):
        m = re.match(r"\s*[+-]?\d+", v)
        v = int(m.group(0)) if m else 0
    v = int(v)
    v &= 0xFFFFFFFF
    return v - (1 << 32) if v >= 1 << 31 else v


def get_long(v):
    if v is None:
        return 0
    if isinstance(v, str):
        m = re.match(r"\s*[+-]?\d+", v)
        return int(m.group(0)) if m else 0
    return int(v)


def get_string(v):
    """Cursor.getString: entero → decimal; real → %g de C (CursorWindow); texto tal cual."""
    if v is None:
        return None
    if isinstance(v, float):
        return c_g(v)
    return str(v)


def c_g(x):
    s = "%g" % x
    return s


def opt_int(row, k):
    v = row.get(k)
    return None if v is None else get_int(v)


def history_csv(con, device):
    header = ("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,"
              "ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio,ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName,"
              "SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming,ObservedAtUtc,NotEvaluatedHeuristics,GpsAccuracyM,"
              "AppVersion,ExportDevice,ExportAndroid,LocationMode,ProfileMode,ProfileLevel,ProfileScore,ProfileCoverage,ProfileAbstainReason,"
              "ProfileAlgorithm,ProfileRevision,ProfileEvidence,ProfileLearned,ProfileHoldout,RowId,ObservationId")
    con.row_factory = sqlite3.Row
    q = ("SELECT h.*, e.mode AS p_mode, e.deviation_level AS p_level, e.deviation_score AS p_score, e.metrics_evaluated AS p_evaluated, "
         "e.metrics_total AS p_total, e.abstain_reason AS p_abstain, e.algorithm_version AS p_algorithm, e.profile_revision AS p_revision, "
         "e.evidence AS p_evidence, e.learned AS p_learned, e.holdout AS p_holdout FROM history h LEFT JOIN profile_evaluations e ON e.history_id = h.id "
         "ORDER BY h.observed_at_ms DESC, h.id DESC")
    out = [header]
    for c in con.execute(q):
        r = dict(c)
        ta = r.get("ta")
        ta = None if ta is None else get_int(ta)
        unit = r.get("ta_unit") if r.get("ta_unit") in TA_UNITS else "UNKNOWN"
        meters = None
        if ta is not None and ta >= 0:
            meters = ta * 78 if unit == "LTE_INDEX" and ta <= 1282 else ta * 554 if unit == "GSM_INDEX" and ta <= 219 else None
        tp = r.get("threat_prob")
        conf = f32(float(tp)) if tp is not None else 0.0
        acc = r.get("gps_accuracy_m")
        prof = None if r["p_level"] is None else r
        vals = [
            get_string(r["timestamp"]), get_string(r["net_type"]), get_string(r["cid"]), get_string(r["mnc"]), get_string(r["tac"]), get_string(r["mcc"]),
            get_int(r["dbm"]), r["verified"] if r["verified"] in VERIFIED else "PENDING", get_int(r["score"]), get_string(r["failed_heuristics"]) or "",
            None if r["lat"] is None else float(r["lat"]), None if r["lon"] is None else float(r["lon"]),
            opt_int(r, "pci"), opt_int(r, "arfcn"), opt_int(r, "rsrq"), opt_int(r, "sinr"), fmt(conf, 1),
            None if r["api_lat"] is None else float(r["api_lat"]), None if r["api_lon"] is None else float(r["api_lon"]),
            ta, unit if ta is not None else "", meters, r["radio"] if r["radio"] in RADIOS else "UNKNOWN",
            r["conn_status"] if r["conn_status"] in CONN else "", opt_int(r, "bandwidth_khz"), get_string(r["bands"]), get_string(r["additional_plmns"]),
            None if r["csg_indicator"] is None else (1 if get_int(r["csg_indicator"]) != 0 else 0), opt_int(r, "csg_identity"), get_string(r["csg_name"]),
            get_string(r["secondary_carriers"]), get_string(r["service_state"]), get_string(r["network_operator"]), get_string(r["sim_operator"]),
            None if r["network_roaming"] is None else (1 if get_int(r["network_roaming"]) != 0 else 0),
            None if r.get("observed_at_ms") is None else utc_iso(get_long(r["observed_at_ms"])),
            get_string(r.get("not_evaluated_heuristics")), None if acc is None else fmt(f32(float(acc)), 1), get_string(r.get("app_version")),
            device[0], device[1], get_string(r.get("location_mode")),
            prof and (get_string(prof["p_mode"]) or ""), prof and prof["p_level"],
            prof and (None if prof["p_score"] is None else fmt(float(prof["p_score"]), 2)),
            prof and f"{get_int(prof['p_evaluated'])}/{get_int(prof['p_total'])}", prof and get_string(prof["p_abstain"]),
            prof and get_int(prof["p_algorithm"]), prof and get_string(prof["p_revision"]), prof and get_string(prof["p_evidence"]),
            prof and (1 if opt_int(prof, "p_learned") == 1 else 0), prof and (1 if opt_int(prof, "p_holdout") == 1 else 0),
            get_long(r["id"]), get_string(r.get("observation_id")),
        ]
        out.append(",".join(field(v) for v in vals))
    con.row_factory = None
    return ("\n".join(out) + "\n").encode()


def service_csv(con):
    header = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source"
    con.row_factory = sqlite3.Row
    evs = [dict(r) for r in con.execute("SELECT * FROM service_state_events ORDER BY ts_ms DESC, id DESC")]
    con.row_factory = None
    evs.sort(key=lambda e: get_long(e["ts_ms"]))   # sortedBy: estable
    out = [header]
    def b(v):
        return None if v is None else (1 if get_int(v) != 0 else 0)
    for e in evs:
        bws = [x for x in (get_string(e["cell_bandwidths"]) or "").split(";")] if e["cell_bandwidths"] is not None else []
        bws = [str(int(x)) for x in bws if re.fullmatch(r"[+-]?\d+", x)]
        vals = [instant_ms(get_long(e["ts_ms"])), e["state"] if e["state"] in SERVICE else "UNKNOWN", b(e["data_registered"]), b(e["voice_registered"]),
                b(e["searching"]), 1 if (b(e["roaming"]) or 0) else 0, get_string(e["operator_numeric"]) or "", get_string(e["operator_alpha"]) or "",
                get_string(e["sim_operator"]) or "", 1 if (b(e["manual_selection"]) or 0) else 0, opt_int(e, "channel_number"), ";".join(bws),
                get_string(e["data_network"]) or "", get_string(e["voice_network"]) or "", e["source"] if e["source"] in SOURCE else "POLL"]
        out.append(",".join(field(v) for v in vals))
    return ("\n".join(out) + "\n").encode()


def all_transitions(con):
    q = ("SELECT from_identity,to_identity,observations,trusted_observations,last_status,last_seen_ms,trip_count,last_trip_id,"
         "mobility_first_seen_ms,mobility_last_seen_ms FROM cell_transitions ORDER BY last_seen_ms DESC")
    out = []
    for c in con.execute(q):
        out.append(dict(f=get_string(c[0]), t=get_string(c[1]), obs=get_int(c[2]), trusted=get_int(c[3]),
                        status=c[4] if c[4] in HEUR else "NOT_EVALUATED", seen=get_long(c[5]), trips=get_int(c[6]), last_trip=get_string(c[7]),
                        mf=None if c[8] is None else get_long(c[8]), ml=None if c[9] is None else get_long(c[9])))
    return out


def xml(v):
    return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace('"', "&quot;").replace("'", "&apos;")


def line(vals, esc=quoted):
    return ",".join(esc(v) for v in vals) + "\n"


def topology(con, app_version, code, at_ms):
    tr = all_transitions(con)
    ids = sorted({x["f"] for x in tr} | {x["t"] for x in tr})
    files = {}
    inc = {}; outg = {}
    for x in tr:
        inc.setdefault(x["t"], []).append(x); outg.setdefault(x["f"], []).append(x)
    s = "identity,incoming_routes,outgoing_routes,incoming_handovers,outgoing_handovers,trusted_incoming,trusted_outgoing\n"
    for i in ids:
        a = inc.get(i, []); b = outg.get(i, [])
        s += line([i, len(a), len(b), sum(x["obs"] for x in a), sum(x["obs"] for x in b), sum(x["trusted"] for x in a), sum(x["trusted"] for x in b)])
    files["cells.csv"] = s.encode()
    s = "from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc\n"
    for x in sorted(tr, key=lambda x: (x["f"], x["t"])):
        ratio = f32(x["trusted"] / x["obs"]) if x["obs"] > 0 else 0.0   # Float de Kotlin
        s += line([x["f"], x["t"], x["obs"], x["trusted"], fmt(ratio, 4), x["status"], instant_ms(x["seen"])])
    files["transitions.csv"] = s.encode()
    node = {i: f"n{k}" for k, i in enumerate(ids)}
    g = ['<?xml version="1.0" encoding="UTF-8"?>', '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
         '  <key id="identity" for="node" attr.name="identity" attr.type="string"/>',
         '  <key id="observations" for="edge" attr.name="observations" attr.type="int"/>',
         '  <key id="trusted" for="edge" attr.name="trusted_observations" attr.type="int"/>',
         '  <key id="status" for="edge" attr.name="last_status" attr.type="string"/>',
         '  <key id="last_seen" for="edge" attr.name="last_seen_utc" attr.type="string"/>',
         '  <graph id="ICdetectionTopology" edgedefault="directed">']
    for i in ids:
        g.append(f'    <node id="{node[i]}"><data key="identity">{xml(i)}</data></node>')
    for k, x in enumerate(tr):
        g += [f'    <edge id="e{k}" source="{node[x["f"]]}" target="{node[x["t"]]}">', f'      <data key="observations">{x["obs"]}</data>',
              f'      <data key="trusted">{x["trusted"]}</data>', f'      <data key="status">{x["status"]}</data>',
              f'      <data key="last_seen">{instant_ms(x["seen"])}</data>', '    </edge>']
    g += ["  </graph>", "</graphml>"]
    files["topology.graphml"] = ("\n".join(g) + "\n").encode()
    meta = dict(schemaVersion=1, generatedAtUtc=instant_ms(at_ms), appVersion=app_version, appVersionCode=code, directedGraph=True,
                cellCount=len(ids), routeCount=len(tr), handoverCount=sum(x["obs"] for x in tr),
                privacyNote="Cell identities and handover routes can reveal habitual movement patterns.",
                integrityNote="SHA-256 detects later modification; it is not a legal chain-of-custody signature.")
    files["metadata.json"] = j_dump(meta).encode()
    files["SHA256SUMS.txt"] = sums(files)
    return files


def familiarity(counts):
    good = sum(1 for c in counts if c >= 3)
    fam = "KNOWN_ON_ROUTE" if good >= 2 else "OBSERVED_ON_ROUTE" if any(c > 0 for c in counts) else "UNKNOWN_ON_ROUTE"
    return fam, good


def geometry(con, app_version, code, at_ms):
    tr = all_transitions(con)
    trips = []
    q = ("SELECT t.trip_id,t.started_at_ms,t.closed_at_ms,t.state,t.close_reason,t.has_moving,COUNT(DISTINCT c.cell_identity),"
         "COUNT(DISTINCT e.from_identity||char(0)||e.to_identity),t.last_serving FROM mobility_trips t LEFT JOIN mobility_trip_cells c ON c.trip_id=t.trip_id "
         "LEFT JOIN mobility_trip_edges e ON e.trip_id=t.trip_id GROUP BY t.trip_id ORDER BY t.started_at_ms DESC")
    for c in con.execute(q):
        trips.append(dict(id=get_string(c[0]), start=get_long(c[1]), end=None if c[2] is None else get_long(c[2]), state=get_string(c[3]),
                          reason=get_string(c[4]), moving=get_int(c[5]) != 0, cells=get_int(c[6]), edges=get_int(c[7]), last=get_string(c[8])))
    open_ = next((t for t in trips if t["state"] == "OPEN"), None)
    pending = set()
    if open_:
        pending = {(get_string(a), get_string(b)) for a, b in con.execute("SELECT from_identity,to_identity FROM mobility_trip_edges WHERE trip_id=?", (open_["id"],))}
    ids = {x["f"] for x in tr} | {x["t"] for x in tr} | {a for a, _ in pending} | {b for _, b in pending}
    if open_ and open_["last"] is not None:
        ids.add(open_["last"])
    inc = {}; outg = {}
    for x in tr:
        inc.setdefault(x["t"], []).append(x); outg.setdefault(x["f"], []).append(x)
    cells = []
    for i in sorted(ids):
        a = inc.get(i, []); b = outg.get(i, []); inc_all = a + b
        fam, good = familiarity([x["trips"] for x in inc_all])
        fs = [x["mf"] for x in inc_all if x["mf"] is not None]; ls = [x["ml"] for x in inc_all if x["ml"] is not None]
        cells.append([i, i.rsplit("-", 1)[1] if "-" in i else "UNKNOWN", None, fam, good, max((x["trips"] for x in inc_all), default=0),
                      len(a), len(b), sum(x["obs"] for x in a), sum(x["obs"] for x in b), sum(x["trusted"] for x in a), sum(x["trusted"] for x in b),
                      None if not fs else instant_ms(min(fs)), None if not ls else instant_ms(max(ls))])
    valid_open = open_ is not None and open_["moving"] and open_["cells"] >= 3
    files = {}
    s = ("identity,radio_tech,local_trust_state,mobility_familiarity,mobility_good_edges,mobility_relevant_trip_count,incoming_routes,outgoing_routes,"
         "incoming_transitions,outgoing_transitions,trusted_incoming,trusted_outgoing,first_mobility_seen_utc,last_mobility_seen_utc\n")
    for c in cells:
        s += line(c)
    files["geometry_cells.csv"] = s.encode()
    s = ("from_identity,to_identity,transition_count,trusted_transition_count,trip_count,pending_current_trip,projected_trip_count_after_valid_close,"
         "mobility_first_seen_utc,mobility_last_seen_utc,last_trip_id\n")
    for x in tr:
        p = (x["f"], x["t"]) in pending
        s += line([x["f"], x["t"], x["obs"], x["trusted"], x["trips"], p, x["trips"] + 1 if p and valid_open else x["trips"],
                   None if x["mf"] is None else instant_ms(x["mf"]), None if x["ml"] is None else instant_ms(x["ml"]), x["last_trip"]])
    files["geometry_edges.csv"] = s.encode()
    s = "trip_id,started_at_utc,ended_at_utc,state,close_reason,had_moving,distinct_cells,edge_count\n"
    for t in trips:
        s += line([t["id"], instant_ms(t["start"]), None if t["end"] is None else instant_ms(t["end"]), t["state"], t["reason"], t["moving"], t["cells"], t["edges"]])
    files["mobility_trips.csv"] = s.encode()
    node = {c[0]: f"n{k}" for k, c in enumerate(cells)}
    g = ['<?xml version="1.0" encoding="UTF-8"?>', '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">']
    for k in ["identity", "radio_tech", "local_trust_state", "mobility_familiarity", "mobility_good_edges", "mobility_relevant_trip_count"]:
        g.append(f'  <key id="{k}" for="node" attr.name="{k}" attr.type="string"/>')
    for k in ["transition_count", "trusted_transition_count", "trip_count"]:
        g.append(f'  <key id="{k}" for="edge" attr.name="{k}" attr.type="int"/>')
    g.append('  <graph id="ICdetectionGeometry" edgedefault="directed">')
    for c in cells:
        g.append(f'    <node id="{node[c[0]]}"><data key="identity">{xml(c[0])}</data><data key="radio_tech">{xml(c[1])}</data><data key="local_trust_state">UNAVAILABLE</data>'
                 f'<data key="mobility_familiarity">{c[3]}</data><data key="mobility_good_edges">{c[4]}</data><data key="mobility_relevant_trip_count">{c[5]}</data></node>')
    for k, x in enumerate(tr):
        g.append(f'    <edge id="e{k}" source="{node[x["f"]]}" target="{node[x["t"]]}"><data key="transition_count">{x["obs"]}</data>'
                 f'<data key="trusted_transition_count">{x["trusted"]}</data><data key="trip_count">{x["trips"]}</data></edge>')
    g += ["  </graph>", "</graphml>"]
    files["geometry.graphml"] = ("\n".join(g) + "\n").encode()
    meta = dict(schemaVersion=17, appVersion=app_version, appVersionCode=code, exportTimeUtc=instant_ms(at_ms), feature="MOBILITY_FAMILIARITY",
                featureMode="CONTEXT_ONLY", K_PRIOR_TRIPS=3, R_GOOD_EDGES=2, MIN_DISTINCT_CELLS=3, STATIC_CLOSE_DURATION_MS=180000,
                SAME_SERVING_CLOSE_DURATION_MS=600000, MAX_TRIP_DURATION_MS=21600000,
                semanticNote="Mobility Familiarity is contextual route memory. KNOWN_ON_ROUTE does not mean trusted, verified or legitimate.",
                privacyNote="This export can reveal cell identities and patterns associated with frequently visited places or routes. Review it before sharing publicly.",
                coordinatesIncluded=False, localTrustStateAvailability="Unavailable in schema 17; left empty rather than inferred.",
                openTripContributionIncludedInFamiliarity=False)
    files["metadata.json"] = j_dump(meta).encode()
    return files


PRIVACY = """ICdetection - Stable-Site export: privacy note

Site keys (site_key) are not anonymous.
- Each key is a deterministic, unsalted SHA-256 hash (first 10 bytes) of a cell of a fixed
  ~500 m grid. Four overlapping grids are used, so one place can have up to four keys.
- The same place gives the same key on every phone and in every export.
- Anyone can compute the keys of every grid cell in an area (a candidate search) and match
  them, so a key can be linked to an approximate location (about 500 m).

The rest of the files can also reveal habits:
- site_cells.csv and shadow_episodes.csv contain complete cell identities;
- first/last-seen times, days, observation counts and motion bands show when and how often
  you were at each place.

No exact coordinates, IMSI, IMEI, phone number or credentials are included.
Treat this ZIP as sensitive location and routine data and review it before sharing it.
"""


def maturity(serving_days, static_days, full_days, rf_days, serving_obs):
    cap = ("FULL_NEIGHBOUR_IDENTITY" if full_days >= 3 else "RF_NEIGHBOUR_ONLY" if rf_days > 0 else
           "FULL_NEIGHBOUR_IDENTITY" if full_days > 0 else "NO_NEIGHBOUR_DATA")
    ready = full_days >= 3 or rf_days >= 3
    if serving_days == 0: st = "BOOTSTRAP"
    elif serving_days < 5 or static_days < 2: st = "LEARNING"
    elif serving_days < 7 or static_days < 3 or not ready or serving_obs < 30: st = "SHADOW_READY"
    else: st = "ACTIVE"
    if st == "ACTIVE": reason = f"MATURE_{cap}"
    elif serving_days < 7: reason = "NEEDS_SERVING_DAYS"
    elif static_days < 3: reason = "NEEDS_STATIC_DAYS"
    elif serving_obs < 30: reason = "NEEDS_SERVING_OBSERVATIONS"
    elif 1 <= full_days < 3: reason = "NEEDS_FULL_NEIGHBOUR_DAYS"
    elif 1 <= rf_days < 3: reason = "NEEDS_RF_NEIGHBOUR_DAYS"
    elif not ready and cap == "NO_NEIGHBOUR_DATA": reason = "NO_USABLE_NEIGHBOUR_DATA"
    else: reason = "MATURE"
    return st, cap, reason


def stable_site(con):
    def query(header, sql):
        s = header + "\n"
        for r in con.execute(sql):
            s += ",".join(field(get_string(v)) for v in r) + "\n"
        return s
    files = {"PRIVACY.txt": PRIVACY}
    stats = ("SELECT s.site_key AS site_key,SUM(CASE WHEN s.role='SERVING' THEN s.observations ELSE 0 END) AS serving_observations,"
             "(SELECT COUNT(DISTINCT d.day) FROM site_cell_days d WHERE d.site_key=s.site_key AND d.role='SERVING') AS serving_days,"
             "(SELECT COUNT(DISTINCT m.day) FROM site_motion_days m WHERE m.site_key=s.site_key AND m.state='STATIC_CONFIRMED') AS static_days,"
             "(SELECT COUNT(DISTINCT d.day) FROM site_cell_days d WHERE d.site_key=s.site_key AND d.role='NEIGHBOUR') AS full_neighbour_days,"
             "(SELECT COALESCE(SUM(c.observations),0) FROM site_cell_evidence c WHERE c.site_key=s.site_key AND c.role='NEIGHBOUR') AS full_neighbour_observations,"
             "(SELECT COUNT(DISTINCT r.day) FROM site_rf_neighbour_days r WHERE r.site_key=s.site_key) AS rf_neighbour_days,"
             "(SELECT COALESCE(SUM(r.observations),0) FROM site_rf_neighbours r WHERE r.site_key=s.site_key) AS rf_neighbour_observations,"
             "MIN(s.first_seen_ms) AS first_seen,MAX(s.last_seen_ms) AS last_seen FROM site_cell_evidence s GROUP BY s.site_key")
    s = ("site_key,feature_state,neighbour_capability,maturity_reason,serving_observations,serving_distinct_days,static_distinct_days,full_neighbour_observations,"
         "full_neighbour_distinct_days,rf_neighbour_observations,rf_neighbour_distinct_days,first_seen_ms,last_seen_ms\n")
    for c in con.execute(f"SELECT site_key,serving_observations,serving_days,static_days,full_neighbour_observations,full_neighbour_days,rf_neighbour_observations,rf_neighbour_days,first_seen,last_seen FROM ({stats})"):
        st, cap, reason = maturity(get_int(c[2]), get_int(c[3]), get_int(c[5]), get_int(c[7]), get_int(c[1]))
        s += ",".join(field(v) for v in [get_string(c[0]), st, cap, reason, get_long(c[1]), get_int(c[2]), get_int(c[3]), get_long(c[4]), get_int(c[5]),
                                         get_long(c[6]), get_int(c[7]), None if c[8] is None else get_long(c[8]), None if c[9] is None else get_long(c[9])]) + "\n"
    files["sites.csv"] = s
    files["site_cells.csv"] = query("site_key,cell_identity,serving_observations,serving_distinct_days,neighbour_observations,neighbour_distinct_days,first_serving_ms,last_serving_ms,first_neighbour_ms,last_neighbour_ms",
        "SELECT s.site_key,s.cell_identity,SUM(CASE WHEN s.role='SERVING' THEN s.observations ELSE 0 END),(SELECT COUNT(*) FROM site_cell_days d WHERE d.site_key=s.site_key AND d.cell_identity=s.cell_identity AND d.role='SERVING'),"
        "SUM(CASE WHEN s.role='NEIGHBOUR' THEN s.observations ELSE 0 END),(SELECT COUNT(*) FROM site_cell_days d WHERE d.site_key=s.site_key AND d.cell_identity=s.cell_identity AND d.role='NEIGHBOUR'),"
        "MIN(CASE WHEN s.role='SERVING' THEN s.first_seen_ms END),MAX(CASE WHEN s.role='SERVING' THEN s.last_seen_ms END),MIN(CASE WHEN s.role='NEIGHBOUR' THEN s.first_seen_ms END),MAX(CASE WHEN s.role='NEIGHBOUR' THEN s.last_seen_ms END) FROM site_cell_evidence s GROUP BY s.site_key,s.cell_identity")
    files["site_rf_neighbours.csv"] = query("site_key,rf_fingerprint,evidence_type,radio,arfcn,pci,mcc_metadata,mnc_metadata,tac_metadata,observations,distinct_days,first_seen_ms,last_seen_ms",
        "SELECT r.site_key,r.fingerprint,'RF_CONTEXT',r.radio,r.arfcn,r.pci,r.mcc,r.mnc,r.tac,r.observations,(SELECT COUNT(*) FROM site_rf_neighbour_days d WHERE d.site_key=r.site_key AND d.fingerprint=r.fingerprint),r.first_seen_ms,r.last_seen_ms FROM site_rf_neighbours r ORDER BY r.site_key,r.fingerprint")
    files["motion.csv"] = query("site_key,day,state,accuracy_band_m,duration_s,displacement_band_m", "SELECT site_key,day,state,accuracy_band_m,duration_s,displacement_band_m FROM site_motion_days ORDER BY day,site_key")
    files["shadow_episodes.csv"] = query("episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,duration_ms,evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated",
        "SELECT episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,(last_seen_ms-first_seen_ms),evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated FROM site_shadow_triggers ORDER BY first_seen_ms")
    return {k: v.encode() for k, v in files.items()}


def j_opt_string(o, k):
    if o is None or k not in o:
        return ""
    v = o[k]
    if v is None: return "null"
    if isinstance(v, str): return v
    if isinstance(v, bool): return "true" if v else "false"
    if isinstance(v, (dict, list)): return j_compact(v)
    return j_tostring(v)


def j_tostring(v):
    """String.valueOf de un número de org.json (Integer, Long o Double)."""
    if isinstance(v, bool): return "true" if v else "false"
    if isinstance(v, int): return str(v)
    return java_double(v)


def j_compact(v):
    if isinstance(v, dict): return "{" + ",".join(j_str(k) + ":" + j_compact(x) for k, x in v.items()) + "}"
    if isinstance(v, list): return "[" + ",".join(j_compact(x) for x in v) + "]"
    if v is None: return "null"
    if isinstance(v, str): return j_str(v)
    return j_num(v)


def j_opt_int(o, k):
    if o is None or k not in o or o[k] is None: return 0
    v = o[k]
    if isinstance(v, bool): return 0
    if isinstance(v, (int, float)): return int(v)
    try: return int(float(v))
    except ValueError: return 0


def j_opt_double(o, k):
    if o is None or k not in o or o[k] is None: return float("nan")
    v = o[k]
    if isinstance(v, bool): return float("nan")
    if isinstance(v, (int, float)): return float(v)
    try: return float(v)
    except ValueError: return float("nan")


def j_nullable(o, k):
    if o is None or k not in o or o[k] is None: return ""
    v = o[k]
    if isinstance(v, (dict, list)): return j_compact(v)
    return v


def csv_any(v):
    """csv() del exportador: toString de Kotlin del valor (Integer, Double, Boolean, String, Instant…)."""
    if isinstance(v, float): return quoted(java_double(v))
    if isinstance(v, bool): return quoted("true" if v else "false")
    return quoted(v)


def joined(arr):
    if arr is None: return ""
    return ";".join(j_opt_string({"x": x}, "x") for x in arr)


def merge_logs(windows):
    merged = []
    for w in windows:
        overlap = 0
        for k in range(min(len(merged), len(w)), -1, -1):
            if k == 0 or merged[len(merged) - k:] == w[:k]:
                overlap = k; break
        merged += w[overlap:]
    return merged


def forensic(con, app_version, code):
    out = {}
    cases = con.execute("SELECT c.id, c.case_code, c.created_at, c.updated_at, c.closed_at, c.state, c.cell_identity, c.highest_phase, c.confirmed, "
                        "(SELECT COUNT(*) FROM forensic_samples s WHERE s.case_id=c.id) FROM forensic_cases c ORDER BY c.id DESC").fetchall()
    states = {"CAPTURING", "POST_CAPTURE", "READY", "INTERRUPTED"}
    for cid, case_code, created, updated, closed, state, idn, phase, conf, n in cases:
        samples = con.execute("SELECT * FROM forensic_samples WHERE case_id=? ORDER BY id", (cid,)).fetchall()
        parsed = [(s, j_parse(s[5])) for s in samples]
        first = parsed[0][1] if parsed else None
        files = {}
        origin = "TRUST_CONTRADICTION" if case_code.startswith("ICD-OBS-") else "ALARM"
        meta = dict(schemaVersion=1, caseId=case_code, state=state if state in states else "INTERRUPTED", createdAt=created, captureOrigin=origin,
                    updatedAt=updated, closedAt=NULL if closed is None else closed, cellIdentity=idn, highestPhase=get_int(phase), confirmed=get_int(conf) != 0,
                    sampleCount=len(samples), appVersion=app_version, appVersionCode=code,
                    device=(j_opt_string(first, "device") if first is not None else "unknown"), android=(j_opt_string(first, "android") if first is not None else "unknown"),
                    sampleSchemaVersion=(j_opt_int(first, "schemaVersion") if first is not None else 0),
                    integrityNote="SHA-256 detects later modification; it is not a legal chain-of-custody signature.")
        files["case.json"] = j_dump(meta).encode()
        s = ("timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason,"
             "required_phases,data_quality_reason,heuristics\n")
        for smp, j in parsed:
            serving = j["serving"]; gps = j.get("gps")
            vals = [instant_ms(smp[2]), smp[3], smp[4], j_opt_int(j, "phase"), j_opt_int(j, "score"), j_opt_double(j, "anomalyConfidence"),
                    j_opt_string(serving, "identity"), j_opt_string(j, "verified"), j_opt_string(j, "latencyState"),
                    None if gps is None else j_opt_double(gps, "lat"), None if gps is None else j_opt_double(gps, "lon"),
                    None if gps is None else j_opt_double(gps, "accuracyM"), j_opt_string(j, "reason"),
                    j_nullable(j, "requiredPhases"), j_nullable(j, "dataQualityReason"), j_opt_string(j, "heuristics")]
            s += ",".join(csv_any(v) for v in vals) + "\n"
        files["timeline.csv"] = s.encode()
        s = ("timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit,connection_state,bandwidth_khz,"
             "registered,bands,csg_indicator,secondary_carriers\n")
        for smp, j in parsed:
            def crow(role, c):
                return ",".join(csv_any(v) for v in [instant_ms(smp[2]), role, j_opt_string(c, "identity"), j_opt_string(c, "mcc"), j_opt_string(c, "mnc"),
                                                     j_opt_string(c, "tac"), j_opt_string(c, "cid"), j_opt_string(c, "radio"), j_opt_string(c, "networkType"),
                                                     j_nullable(c, "dbm"), j_nullable(c, "pci"), j_nullable(c, "arfcn"), j_nullable(c, "band"), j_nullable(c, "rsrq"),
                                                     j_nullable(c, "sinr"), j_nullable(c, "ta"), j_opt_string(c, "taUnit"), j_opt_string(c, "connectionState"),
                                                     j_nullable(c, "bandwidthKhz"), j_nullable(c, "registered"), joined(c.get("bands") if isinstance(c.get("bands"), list) else None),
                                                     j_nullable(c, "csgIndicator"), joined(c.get("secondaryCarriers") if isinstance(c.get("secondaryCarriers"), list) else None)]) + "\n"
            s += crow("SERVING", j["serving"])
            for c in (j.get("neighbors") or []):
                s += crow("SECONDARY" if j_opt_string(c, "connectionState") == "SECONDARY_SERVING" else "NEIGHBOR", c)
        files["cells.csv"] = s.encode()
        s = "timestamp_utc,heuristic_id,name,status,explanation\n"
        for smp, j in parsed:
            for x in (j.get("diagnostics") or []):
                s += ",".join(csv_any(v) for v in [instant_ms(smp[2]), j_opt_int(x, "id"), j_opt_string(x, "name"), j_opt_string(x, "status"), j_opt_string(x, "explanation")]) + "\n"
        files["heuristics.csv"] = s.encode()
        s = ("timestamp_utc,local_trust_state,local_trust_confidence_percent,local_trust_contradictions,site_key,site_feature_state,site_novelty,site_would_trigger,"
             "site_enforced,site_reason,site_static_duration_s,site_location_accuracy_m,site_serving_days,site_neighbour_days\n")
        for smp, j in parsed:
            tr = j.get("localTrust") if isinstance(j.get("localTrust"), dict) else {}
            st = j.get("stableSite") if isinstance(j.get("stableSite"), dict) else {}
            s += ",".join(csv_any(v) for v in [instant_ms(smp[2]), j_opt_string(tr, "state"), j_nullable(tr, "confidencePercent"),
                                                joined(tr.get("contradictions") if isinstance(tr.get("contradictions"), list) else None),
                                                j_nullable(st, "siteKey"), j_opt_string(st, "featureState"), j_opt_string(st, "novelty"), j_nullable(st, "wouldTrigger"),
                                                j_nullable(st, "enforced"), j_opt_string(st, "reason"), j_nullable(st, "staticDurationSeconds"),
                                                j_nullable(st, "locationAccuracyM"), j_nullable(st, "servingDays"), j_nullable(st, "neighbourDays")]) + "\n"
        files["trust_site.csv"] = s.encode()
        caps = dict(schemaVersion=1, observations=[dict(timestamp=instant_ms(smp[2]), values=j.get("capabilities") if isinstance(j.get("capabilities"), dict) else {}) for smp, j in parsed])
        files["capabilities.json"] = j_dump(caps).encode()
        logs = merge_logs([[j_opt_string({"x": x}, "x") for x in (j.get("logs") or [])] for _, j in parsed])
        files["terminal.log"] = "".join(l + "\n" for l in logs).encode()
        files["SHA256SUMS.txt"] = sums(files)
        out[case_code] = files
    return out


def export_all(db_path, device, at_ms, app_version, code):
    con = sqlite3.connect(db_path)
    try:
        return dict(history=history_csv(con, device), service=service_csv(con), topology=topology(con, app_version, code, at_ms),
                    geometry=geometry(con, app_version, code, at_ms), stable_site=stable_site(con), forensic=forensic(con, app_version, code))
    finally:
        con.close()


def backup(db_path):
    data = open(db_path, "rb").read()
    manifest = dict(format=1, appId="com.alexisgordr.icdetector", appVersion=APP_VERSION, schemaVersion=25, createdAtUtc=instant_ms(ms(BACKUP_AT)),
                    databaseSha256=sha(data), databaseBytes=len(data))
    settings = dict(app_language="es", location_mode="INTELLIGENT", proxy_enabled=False, latency_detection_enabled=True,
                    legacy_network_alert_mode="NOTIFY", stable_site_silent_notices=True)
    return zip_bytes({"manifest.json": j_dump(manifest).encode(), "icdetector_history.db": data, "settings.json": j_dump(settings).encode()}, ms(BACKUP_AT))


def write(path, b):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(b)


def write_exports(prefix, ex, at_ms):
    """Exportaciones como las guarda ICdetector (un CSV o un ZIP por exportación)."""
    write(f"{prefix}_history.csv", ex["history"])
    write(f"{prefix}_service_state.csv", ex["service"])
    write(f"{prefix}_topology.zip", zip_bytes(ex["topology"], at_ms))
    write(f"{prefix}_geometry.zip", zip_bytes(ex["geometry"], at_ms))
    write(f"{prefix}_stable_site.zip", zip_bytes(ex["stable_site"], at_ms))
    for code_, files in ex["forensic"].items():
        write(f"{prefix}_{code_}.zip", zip_bytes(files, at_ms))


def write_expected(folder, ex):
    """Lo que CellCase debe reconstruir desde la copia, entrada a entrada (para comparar byte a byte)."""
    write(os.path.join(folder, "history.csv"), ex["history"])
    write(os.path.join(folder, "service_state.csv"), ex["service"])
    for part in ("topology", "geometry", "stable_site"):
        for n, b in ex[part].items():
            write(os.path.join(folder, part, n), b)
    for code_, files in ex["forensic"].items():
        for n, b in files.items():
            write(os.path.join(folder, "forensic", code_, n), b)


def main():
    os.makedirs(OUT, exist_ok=True)
    tmp = tempfile.mkdtemp()
    for variant in ("fresh", "upgraded"):
        db = os.path.join(tmp, f"{variant}.db")
        create_db(db, variant == "upgraded")
        write(os.path.join(OUT, f"SYNTHETIC_icd30_{variant}.icdbackup"), backup(db))
        # Lo que CellCase debe reconstruir: exportación en el momento de la copia, sin teléfono de exportación
        write_expected(os.path.join(OUT, "expected", variant), export_all(db, ("", ""), ms(BACKUP_AT), APP_VERSION, APP_VERSION_CODE))
        if variant == "fresh":
            write_exports(os.path.join(OUT, "SYNTHETIC_icd30"), export_all(db, EXPORT_DEVICE, ms(EXPORT_AT), APP_VERSION, APP_VERSION_CODE), ms(EXPORT_AT))
            write(os.path.join(tmp, "keep"), b"")
    # Base vacía (3.0 recién instalada): exporta solo cabeceras
    db = os.path.join(tmp, "empty.db")
    con = sqlite3.connect(db, isolation_level=None)
    con.execute("PRAGMA journal_mode=DELETE")
    for s in [HISTORY_FRESH] + OTHER + V20_OTHER + V22 + V23 + V20_HISTORY + V21 + [V24[0], V24[1], V24[2], V24[3]] + V25:
        con.execute(s)
    con.execute("PRAGMA user_version=25"); con.close()
    write(os.path.join(OUT, "SYNTHETIC_icd30_empty.icdbackup"), backup(db))
    write_expected(os.path.join(OUT, "expected", "empty"), export_all(db, ("", ""), ms(BACKUP_AT), APP_VERSION, APP_VERSION_CODE))
    # Bases mínimas para las pruebas del lector SQLite: texto en UTF-16 y una tabla WITHOUT ROWID (no admitida)
    fx = os.path.join(OUT, "fixtures"); os.makedirs(fx, exist_ok=True)
    for name, setup in (("utf16le.db", ["PRAGMA encoding='UTF-16le'", "CREATE TABLE t (id INTEGER PRIMARY KEY, txt TEXT, n REAL)",
                                         "INSERT INTO t (txt, n) VALUES ('ñandú ☂ €', 1.5)", "INSERT INTO t (txt, n) VALUES (NULL, -2.25)"]),
                        ("without_rowid.db", ["CREATE TABLE w (k TEXT PRIMARY KEY, v INTEGER) WITHOUT ROWID", "INSERT INTO w VALUES ('a', 1)"])):
        path = os.path.join(fx, name)
        if os.path.exists(path): os.remove(path)
        con = sqlite3.connect(path, isolation_level=None)
        for st in setup: con.execute(st)
        con.close()
    print("Muestras de ICdetector 3.0 escritas en", OUT)


if __name__ == "__main__":
    main()
