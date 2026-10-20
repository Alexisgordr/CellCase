#!/usr/bin/env python3
"""
Generador de muestras SINTÉTICAS de exportaciones de ICdetector para CellCase.

Todo lo que produce es inventado. Sirve para probar lectores y validaciones; no demuestra nada
sobre la eficacia de ninguna detección.

Los formatos siguen docs/export-formats.md (que cita el código de ICdetector que escribe cada
archivo). Este script es código nuevo: no copia ni traduce código de ICdetector.

Marcas de sinteticidad (decisión D-005):
  - PLMN de pruebas 001-01 en todas las identidades.
  - Coordenadas cerca de (0, 0).
  - Nombres de archivo con prefijo SYNTHETIC_.
  - MANIFEST.json describiendo cada archivo.
  - Las claves de Stable-Site son aleatorias, NO derivadas de coordenadas.

Uso:  python3 tools/synth/generate_samples.py  [carpeta_salida]
Determinista: misma salida en cada ejecución.
"""

import hashlib
import io
import json
import os
import random
import sys
import zipfile
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

OUT = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    os.path.dirname(__file__), "..", "..", "samples", "synthetic")
OUT = os.path.abspath(OUT)
TZ = ZoneInfo("Europe/Madrid")          # zona supuesta del teléfono sintético
RNG = random.Random(20261002)
INT_MAX = 2147483647

# ───────────────────────────── utilidades de formato ─────────────────────────────

def java_double(x):
    """Representación de Double.toString() de Java/Kotlin (notación científica fuera de [1e-3, 1e7))."""
    if x is None:
        return ""
    if x == 0:
        return "0.0"
    ax = abs(x)
    if 1e-3 <= ax < 1e7:
        s = repr(float(x))
        return s if ("." in s or "e" in s) else s + ".0"
    mant, exp = f"{x:.17e}".split("e")
    # mantisa mínima que reproduce el valor
    for digits in range(1, 18):
        m = f"{x:.{digits}e}"
        if float(m) == x:
            mant, exp = m.split("e")
            break
    mant = mant.rstrip("0")
    if mant.endswith("."):
        mant += "0"
    return f"{mant}E{int(exp)}"


def instant(dt_utc):
    """Instant.toString(): milisegundos solo si no son cero."""
    dt_utc = dt_utc.astimezone(timezone.utc)
    base = dt_utc.strftime("%Y-%m-%dT%H:%M:%S")
    ms = dt_utc.microsecond // 1000
    return f"{base}.{ms:03d}Z" if ms else f"{base}Z"


def local_str(dt_utc):
    return dt_utc.astimezone(TZ).strftime("%Y-%m-%d %H:%M:%S")


def epoch_ms(dt_utc):
    return int(dt_utc.timestamp() * 1000)


def esc_min(v):
    """Comillas solo si hacen falta (historial, radio, stable-site)."""
    s = "" if v is None else str(v)
    if any(c in s for c in ',"\n\r'):
        return '"' + s.replace('"', '""') + '"'
    return s


def esc_all(v):
    """Todos los campos entre comillas (caso forense, topología, geometría)."""
    s = "" if v is None else str(v)
    return '"' + s.replace('"', '""') + '"'


def row(values, esc):
    return ",".join(esc(v) for v in values)


def b10(v):
    return "" if v is None else (1 if v else 0)


def zip_bytes(entries, when_utc):
    """ZIP plano en el orden dado; fecha de entrada = momento de exportación (como ZipOutputStream)."""
    buf = io.BytesIO()
    local = when_utc.astimezone(TZ)
    dt = (local.year, local.month, local.day, local.hour, local.minute, local.second)
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        for name, data in entries.items():
            zi = zipfile.ZipInfo(name, date_time=dt)
            zi.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(zi, data)
    return buf.getvalue()


def sha256(b):
    return hashlib.sha256(b).hexdigest()


def sums(entries):
    return ("\n".join(f"{sha256(b)}  {n}" for n, b in entries.items()) + "\n").encode()


def js(obj):
    return json.dumps(obj, indent=2, ensure_ascii=False).encode()


def U(y, mo, d, h, mi, s=0, ms=0):
    return datetime(y, mo, d, h, mi, s, ms * 1000, tzinfo=timezone.utc)

# ───────────────────────────── escenario sintético ─────────────────────────────

PLMN = ("001", "01")


class Cell:
    def __init__(self, tac, cid, radio, pci, arfcn, dbm, rsrq=-10, sinr=12, bw=20000, bands="3"):
        self.mcc, self.mnc = PLMN
        self.tac, self.cid, self.radio = str(tac), str(cid), radio
        self.pci, self.arfcn, self.dbm, self.rsrq, self.sinr = pci, arfcn, dbm, rsrq, sinr
        self.bw, self.bands = bw, bands

    @property
    def identity(self):
        return f"{self.mcc}-{self.mnc}-{self.tac}-{self.cid}-{self.radio}"


A = Cell(1001, 1000001, "LTE", 101, 1850, -88, bands="3")
B = Cell(1001, 1000002, "LTE", 102, 1850, -95, bands="3")
C = Cell(1002, 1000003, "LTE", 233, 6300, -91, bw=10000, bands="20")
NRc = Cell(1001, 5000000001, "NR", 501, 632448, -97, rsrq=-11, sinr=9, bw=None, bands="78")
X = Cell(1099, 9999001, "LTE", 101, 1850, -61, rsrq=-6, sinr=25, bands="3")   # celda «anómala» sintética

HOME = (0.0123, 0.0456)
WORK = (0.0510, 0.0302)

SUBTHRESHOLD = "[sub-umbral]"

HISTORY_HEADER = ("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,"
                  "PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio,"
                  "ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName,"
                  "SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming")
HISTORY_V21_COLS = 23

TA_FACTOR = {"LTE_INDEX": 78, "GSM_INDEX": 554}   # constantes 3GPP descritas en export-formats.md §1.3


def hist_row(t, cell, *, score=100, reason="OK", verified="VERIFIED", lat=None, lon=None,
             conf=0.0, api=None, ta=None, ta_unit=None, service="IN_SERVICE", net_type="4G",
             secondary=None, csg=None):
    ta_m = ""
    if ta is not None and ta >= 0 and ta_unit in TA_FACTOR:
        ta_m = ta * TA_FACTOR[ta_unit]
    vals = [
        local_str(t), net_type, cell.cid, cell.mnc, cell.tac, cell.mcc, cell.dbm, verified, score, reason,
        java_double(lat), java_double(lon), cell.pci if cell.pci is not None else "",
        cell.arfcn if cell.arfcn is not None else "",
        cell.rsrq if cell.rsrq is not None else "", cell.sinr if cell.sinr is not None else "",
        f"{conf:.1f}", java_double(api[0]) if api else "", java_double(api[1]) if api else "",
        ta if ta is not None else "", ta_unit if ta is not None else "", ta_m, cell.radio,
        "PRIMARY_SERVING", cell.bw if cell.bw else "", cell.bands, "", b10(csg), "", "",
        secondary or "", service, "00101", "00101", 0,
    ]
    return row(vals, esc_min)


def build_history():
    rows = []
    start = U(2026, 9, 25, 22, 0)   # 26/09 00:00 local
    end = U(2026, 9, 28, 21, 59)
    t = start
    while t <= end:
        local = t.astimezone(TZ)
        commute = local.weekday() < 5 and (8 <= local.hour < 9 or 18 <= local.hour < 19)
        at_work = local.weekday() < 5 and 9 <= local.hour < 18
        if at_work:
            cell, pos = C, WORK
        elif commute:
            cell, pos = B, None
        elif local.minute % 30 == 0 and local.hour % 6 == 0:
            cell, pos = NRc, HOME
        else:
            cell, pos = A, HOME
        jitter = RNG.uniform(-0.00004, 0.00004)
        lat = round(pos[0] + jitter, 6) if pos else None
        lon = round(pos[1] - jitter, 6) if pos else None
        ta = RNG.randint(2, 5) if cell.radio == "LTE" else None
        ta_unit = "LTE_INDEX" if ta is not None else None
        net_type = "5G" if cell is NRc or (cell is A and local.minute % 2 == 0) else "4G"
        rows.append((t, hist_row(t, cell, lat=lat, lon=lon, ta=ta, ta_unit=ta_unit, net_type=net_type,
                                 api=(pos[0] + 0.0011, pos[1] + 0.0007) if pos else None,
                                 secondary=f"NR:{NRc.arfcn}:{NRc.pci}" if net_type == "5G" and cell is A else None)))
        screen_on = 8 <= local.hour < 23
        t += timedelta(minutes=5 if screen_on else 15)

    # Incidente sintético 27/09 21:14 local (19:14 UTC): handover a X, alarma y vuelta.
    inc = [
        (U(2026, 9, 27, 19, 14, 0, 412), hist_row(U(2026, 9, 27, 19, 14, 0, 412), X, score=55,
            reason=f"{SUBTHRESHOLD} Desviación TAC | Salto potencia (>35dB)", verified="PENDING",
            lat=HOME[0], lon=HOME[1], conf=41.5, ta=0, ta_unit="LTE_INDEX")),
        (U(2026, 9, 27, 19, 14, 30, 507), hist_row(U(2026, 9, 27, 19, 14, 30, 507), X, score=40,
            reason="Desviación TAC | Salto potencia (>35dB) | Proximidad anómala (TA)", verified="NOT_FOUND",
            lat=HOME[0], lon=HOME[1], conf=78.0, ta=0, ta_unit="LTE_INDEX")),
        (U(2026, 9, 27, 19, 16, 30, 233), hist_row(U(2026, 9, 27, 19, 16, 30, 233), A,
            lat=HOME[0], lon=HOME[1], ta=3, ta_unit="LTE_INDEX", service="OUT_OF_SERVICE")),
    ]
    rows.extend(inc)
    rows.sort(key=lambda r: r[0], reverse=True)    # el exportador escribe del más reciente al más antiguo
    return (HISTORY_HEADER + "\n" + "\n".join(r for _, r in rows) + "\n").encode("utf-8"), len(rows)


def build_history_edge_cases():
    """Casos límite: comillas, comas, centinelas, notación científica, TA no convertible, cambio de hora."""
    header = HISTORY_HEADER
    weird = Cell(1001, 1000077, "LTE", INT_MAX, INT_MAX, -101, rsrq=None, sinr=None, bw=None, bands="")
    nr = Cell(1001, 5000000002, "NR", 777, 643334, -104, rsrq=-12, sinr=3, bw=None, bands="78")
    unk = Cell(1001, 1000078, "UNKNOWN", None, None, -99, rsrq=None, sinr=None, bw=None, bands="")
    lines = [
        hist_row(U(2026, 10, 25, 0, 30), weird, reason='Razón con "comillas", y coma', lat=5.0e-4, lon=-2.5e-4,
                 verified="ERROR", score=85, conf=12.3),
        # 02:30 local del 25/10/2026 ocurre dos veces (00:30Z y 01:30Z): hora ambigua
        hist_row(U(2026, 10, 25, 1, 30), nr, ta=412, ta_unit="NR_RAW", net_type="5G", verified="PENDING"),
        hist_row(U(2026, 10, 24, 10, 0), A, ta=0, ta_unit="STUB_ZERO", verified="REJECTED"),
        hist_row(U(2026, 10, 24, 9, 0), unk, ta=7, ta_unit="UNKNOWN", service="EMERGENCY_ONLY", csg=True),
        hist_row(U(2026, 10, 24, 8, 0), A, reason="Texto\ncon salto de línea", score=90, conf=5.0),
    ]
    return (header + "\n" + "\n".join(lines) + "\n").encode("utf-8")


def build_history_v21():
    cols = HISTORY_HEADER.split(",")[:HISTORY_V21_COLS]
    lines = []
    for i in range(3):
        t = U(2026, 6, 1, 10, 0) + timedelta(minutes=15 * i)
        full = hist_row(t, A, lat=HOME[0], lon=HOME[1], ta=3, ta_unit="LTE_INDEX")
        lines.append(",".join(full.split(",")[:HISTORY_V21_COLS]))
    lines.reverse()
    return (",".join(cols) + "\n" + "\n".join(lines) + "\n").encode("utf-8")


def build_history_malformed():
    good = build_history_v21().decode().splitlines()
    full_header = HISTORY_HEADER
    bad = [full_header,
           "2026-09-26 10:00:00,4G,1000001,01,1001,001,-88",          # pocas columnas
           "fecha-no-valida,4G,1000001,01,1001,001,abc" + ",x" * 28,  # tipos incorrectos
           '"comillas sin cerrar,4G']
    return ("\n".join(bad) + "\n").encode("utf-8"), good


SERVICE_HEADER = ("TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,"
                  "NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,"
                  "VoiceNetwork,Source")


def build_service_state():
    ev = [
        (U(2026, 9, 25, 22, 0, 3, 120), "IN_SERVICE", 1, 1, 0, "LTE", "LTE", "POLL", 1850, "20000"),
        (U(2026, 9, 26, 4, 30, 1, 0), "IN_SERVICE", 1, 1, 0, "NR", "LTE", "CALLBACK", 632448, "20000;100000"),
        (U(2026, 9, 26, 4, 31, 0, 455), "IN_SERVICE", 1, 1, 0, "LTE", "LTE", "CALLBACK", 1850, "20000"),
        (U(2026, 9, 27, 19, 14, 1, 10), "IN_SERVICE", 1, 0, 0, "LTE", None, "CALLBACK", 1850, "20000"),
        (U(2026, 9, 27, 19, 16, 29, 880), "OUT_OF_SERVICE", 0, 0, 1, None, None, "CALLBACK", None, ""),
        (U(2026, 9, 27, 19, 16, 52, 301), "IN_SERVICE", 1, 1, 0, "LTE", "LTE", "CALLBACK", 1850, "20000"),
        (U(2026, 9, 28, 6, 0, 0, 0), "IN_SERVICE", None, None, None, "LTE", "LTE", "POLL", 1850, "20000"),
    ]
    lines = [SERVICE_HEADER]
    for t, st, d, v, s, dn, vn, src, ch, bws in sorted(ev, key=lambda e: e[0]):
        op = "00101" if st != "OUT_OF_SERVICE" else ""
        name = "Red de Pruebas, S.A." if op else ""
        lines.append(row([instant(t), st, b10(d), b10(v), b10(s), 0, op, name, "00101", 0,
                          ch if ch is not None else "", bws, dn or "", vn or "", src], esc_min))
    return ("\n".join(lines) + "\n").encode("utf-8"), len(ev)

# ───────────────────────────── caso forense ─────────────────────────────

DIAG_NAMES = ["Celda aislada", "Estabilidad de potencia", "Consistencia MCC", "Límite MNC",
              "Validación regional TAC", "Coherencia geométrica (TA)", "Espectro de vecinos",
              "Sanidad ARFCN", "Cifrado hardware", "Anti ping-pong", "Consistencia geográfica",
              "Correlación latencia + RF", "Baseline y huella RF", "Downgrade de banda",
              "Estabilidad de identidad RF", "Coherencia de transición celular"]


def cell_csv_values(ts, role, c, ta=None, ta_unit="UNKNOWN", na_identity=False, band=None):
    if na_identity:
        ident = f"N/A-N/A-N/A-N/A-{c.radio}"
        mcc = mnc = tac = cid = "N/A"
    else:
        ident, mcc, mnc, tac, cid = c.identity, c.mcc, c.mnc, c.tac, c.cid
    return [instant(ts), role, ident, mcc, mnc, tac, cid, c.radio, "LTE" if c.radio == "LTE" else "NR",
            c.dbm, "" if c.pci is None else c.pci, "" if c.arfcn is None else c.arfcn,
            "" if band is None else band, "" if c.rsrq is None else c.rsrq,
            "" if c.sinr is None else c.sinr, "" if ta is None else ta, ta_unit,
            "PRIMARY_SERVING" if role == "SERVING" else "NONE",
            c.bw if (role == "SERVING" and c.bw) else ""]


def build_forensic(case_seq=7, export_at=U(2026, 9, 27, 19, 30, 12), extra_tail=False):
    t0 = U(2026, 9, 27, 19, 13, 0)
    rng = random.Random(case_seq)   # semilla propia: una reexportación debe dar el mismo contenido
    samples = []
    phase = 0
    t = t0
    n1 = Cell(1001, None, "LTE", 245, 1850, -104)
    n2 = Cell(1001, None, "LTE", INT_MAX, 6300, INT_MAX)     # vecina con centinelas
    end = U(2026, 9, 27, 19, 17, 30) + (timedelta(seconds=30) if extra_tail else timedelta(0))
    k = 0
    while t <= end:
        serving = X if U(2026, 9, 27, 19, 14, 0) <= t < U(2026, 9, 27, 19, 16, 30) else A
        prev = phase
        if serving is X:
            phase = min(3, 1 + int((t - U(2026, 9, 27, 19, 14, 0)).total_seconds() // 15))
        else:
            phase = 0
        if phase > 0 and prev == 0:
            ev = "ANOMALY_STARTED"
        elif phase > prev:
            ev = "CONFIRMED" if phase >= 3 else "PHASE_CHANGED"
        elif phase == 0 and prev > 0:
            ev = "RECOVERY_STARTED"
        else:
            ev = "OBSERVATION"
        score = {0: 100, 1: 55, 2: 45, 3: 40}[phase]
        conf = {0: 0.0, 1: 41.5, 2: 63.0, 3: 78.0}[phase]
        reason = "" if phase == 0 else "Desviación TAC | Salto potencia (>35dB)" + (" | Proximidad anómala (TA)" if phase >= 2 else "")
        gps = None if k % 9 == 4 else (HOME[0] + 0.00002 * (k % 3), HOME[1], 12.5 if k % 2 else 8.0)
        samples.append(dict(t=t, elapsed=987_654_321 + int((t - t0).total_seconds() * 1000), event=ev,
                            phase=phase, score=score, conf=conf, serving=serving, gps=gps, reason=reason,
                            neighbors=[B, n1, n2] if serving is A else [A, B, n1]))
        t += timedelta(seconds=5, milliseconds=rng.randint(0, 40))
        k += 1

    timeline = ["timestamp_utc,elapsed_ms,event,phase,score,anomaly_confidence,cell_identity,verified,latency_state,gps_lat,gps_lon,gps_accuracy_m,reason"]
    cells = ["timestamp_utc,role,identity,mcc,mnc,tac,cid,radio,network_type,dbm,pci,arfcn,band,rsrq,sinr,ta,ta_unit,connection_state,bandwidth_khz"]
    heur = ["timestamp_utc,heuristic_id,name,status,explanation"]
    caps = []
    logs = []
    for s in samples:
        sv = s["serving"]
        g = s["gps"]
        timeline.append(row([instant(s["t"]), s["elapsed"], s["event"], s["phase"], s["score"],
                             java_double(s["conf"]), sv.identity,
                             "PENDING" if sv is X else "VERIFIED", "OK",
                             java_double(g[0]) if g else None, java_double(g[1]) if g else None,
                             java_double(g[2]) if g else None, s["reason"]], esc_all))
        ta = 0 if sv is X else 3
        cells.append(row(cell_csv_values(s["t"], "SERVING", sv, ta=ta, ta_unit="LTE_INDEX",
                                         band=3 if sv.arfcn == 1850 else 20), esc_all))
        for n in s["neighbors"]:
            na = n.cid == "None"
            cells.append(row(cell_csv_values(s["t"], "NEIGHBOR", n, na_identity=na,
                                             band=(3 if n.arfcn == 1850 else 20) if n.arfcn != INT_MAX else None),
                             esc_all))
        for i, name in enumerate(DIAG_NAMES, start=1):
            if sv is X and i in (5, 2):
                st, ex = "FAILED", "Sintético: valor fuera del patrón aprendido."
            elif i in (9, 12):
                st, ex = "NOT_EVALUATED", "Sintético: dato no disponible en este dispositivo."
            else:
                st, ex = "PASSED", "Sintético: sin observaciones anómalas."
            if sv is X and i == 6 and s["phase"] >= 2:
                st, ex = "FAILED", "Sintético: TA=0 con potencia alta, \"proximidad\" según el colector."
            heur.append(row([instant(s["t"]), i, name, st, ex], esc_all))
        caps.append({"timestamp": instant(s["t"]), "values": {
            "timingAdvance": True, "rsrq": True, "sinr": True, "arfcn": True,
            "gps": g is not None, "hardwareCiphering": False}})
        hhmmss = s["t"].astimezone(TZ).strftime("%H:%M:%S.") + f"{s['t'].microsecond // 1000:03d}"
        logs.append(f"[{hhmmss}] [CICLO] servidora={sv.identity} fase={s['phase']} score={s['score']}")

    created = samples[0]["t"]
    closed = samples[-1]["t"]
    code = f"ICD-{created.astimezone(TZ).strftime('%Y-%m-%d')}-{case_seq:04d}"
    entries = {}
    entries["case.json"] = js({
        "schemaVersion": 1, "caseId": code, "state": "READY",
        "createdAt": local_str(U(2026, 9, 27, 19, 14, 0, 300)),
        "captureOrigin": "ALARM", "updatedAt": local_str(closed), "closedAt": local_str(closed),
        "cellIdentity": X.identity, "highestPhase": 3, "confirmed": True, "sampleCount": len(samples),
        "appVersion": "2.10.5", "appVersionCode": 33, "device": "SYNTHETIC Phone", "android": "16",
        "integrityNote": "SHA-256 detects later modification; it is not a legal chain-of-custody signature.",
    })
    entries["timeline.csv"] = ("\n".join(timeline) + "\n").encode()
    entries["cells.csv"] = ("\n".join(cells) + "\n").encode()
    entries["heuristics.csv"] = ("\n".join(heur) + "\n").encode()
    entries["capabilities.json"] = js({"schemaVersion": 1, "observations": caps})
    seen = []
    for line in logs:
        if line not in seen:
            seen.append(line)
    entries["terminal.log"] = ("\n".join(seen) + "\n").encode()
    entries["SHA256SUMS.txt"] = sums(entries)
    return code, zip_bytes(entries, export_at), entries, len(samples)

# ───────────────────────────── topología y geometría ─────────────────────────────

ROUTES = [  # from, to, observations, trusted, last_status, last_seen
    (A, B, 41, 40, "PASSED", U(2026, 9, 28, 6, 0, 5)),
    (B, C, 37, 37, "PASSED", U(2026, 9, 28, 6, 25, 40)),
    (C, B, 36, 35, "PASSED", U(2026, 9, 27, 16, 2, 11)),
    (B, A, 40, 39, "PASSED", U(2026, 9, 27, 16, 31, 9)),
    (A, NRc, 18, 18, "PASSED", U(2026, 9, 28, 4, 0, 0)),
    (NRc, A, 18, 17, "PASSED", U(2026, 9, 28, 4, 30, 0)),
    (A, X, 1, 0, "FAILED", U(2026, 9, 27, 19, 14, 0, 412)),
    (X, A, 1, 0, "NOT_EVALUATED", U(2026, 9, 27, 19, 16, 30, 233)),
]


def build_topology(export_at, routes):
    ids = sorted({r[0].identity for r in routes} | {r[1].identity for r in routes})
    cells = ["identity,incoming_routes,outgoing_routes,incoming_handovers,outgoing_handovers,trusted_incoming,trusted_outgoing"]
    for i in ids:
        inc = [r for r in routes if r[1].identity == i]
        out = [r for r in routes if r[0].identity == i]
        cells.append(row([i, len(inc), len(out), sum(r[2] for r in inc), sum(r[2] for r in out),
                          sum(r[3] for r in inc), sum(r[3] for r in out)], esc_all))
    trans = ["from_identity,to_identity,observations,trusted_observations,trust_ratio,last_status,last_seen_utc"]
    for f, t, o, tr, st, ls in sorted(routes, key=lambda r: (r[0].identity, r[1].identity)):
        trans.append(row([f.identity, t.identity, o, tr, f"{(tr / o if o else 0):.4f}", st, instant(ls)], esc_all))
    nid = {i: f"n{k}" for k, i in enumerate(ids)}
    g = ['<?xml version="1.0" encoding="UTF-8"?>', '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">',
         '  <key id="identity" for="node" attr.name="identity" attr.type="string"/>',
         '  <key id="observations" for="edge" attr.name="observations" attr.type="int"/>',
         '  <key id="trusted" for="edge" attr.name="trusted_observations" attr.type="int"/>',
         '  <key id="status" for="edge" attr.name="last_status" attr.type="string"/>',
         '  <key id="last_seen" for="edge" attr.name="last_seen_utc" attr.type="string"/>',
         '  <graph id="ICdetectionTopology" edgedefault="directed">']
    g += [f'    <node id="{nid[i]}"><data key="identity">{i}</data></node>' for i in ids]
    for k, (f, t, o, tr, st, ls) in enumerate(routes):
        g += [f'    <edge id="e{k}" source="{nid[f.identity]}" target="{nid[t.identity]}">',
              f'      <data key="observations">{o}</data>', f'      <data key="trusted">{tr}</data>',
              f'      <data key="status">{st}</data>', f'      <data key="last_seen">{instant(ls)}</data>',
              '    </edge>']
    g += ["  </graph>", "</graphml>"]
    e = {"cells.csv": ("\n".join(cells) + "\n").encode(), "transitions.csv": ("\n".join(trans) + "\n").encode(),
         "topology.graphml": ("\n".join(g) + "\n").encode(),
         "metadata.json": js({"schemaVersion": 1, "generatedAtUtc": instant(export_at), "appVersion": "2.10.5",
                              "appVersionCode": 33, "directedGraph": True, "cellCount": len(ids),
                              "routeCount": len(routes), "handoverCount": sum(r[2] for r in routes),
                              "privacyNote": "Cell identities and handover routes can reveal habitual movement patterns.",
                              "integrityNote": "SHA-256 detects later modification; it is not a legal chain-of-custody signature."})}
    e["SHA256SUMS.txt"] = sums(e)
    return zip_bytes(e, export_at), e


def build_geometry(export_at):
    # Exportación posterior (29/09): contadores >= que en topología (28/09) y un handover más A->B.
    routes = [(f, t, o + (1 if (f, t) == (A, B) else 0), tr + (1 if (f, t) == (A, B) else 0), st,
               U(2026, 9, 29, 6, 1, 2) if (f, t) == (A, B) else ls) for f, t, o, tr, st, ls in ROUTES]
    trips_per_edge = {(A, B): 9, (B, C): 9, (C, B): 8, (B, A): 8, (A, NRc): 0, (NRc, A): 0, (A, X): 0, (X, A): 0}
    trips = [
        ("9b1c0d2e-0000-4000-8000-000000000001", U(2026, 9, 28, 6, 0, 0), U(2026, 9, 28, 6, 40, 0), "CLOSED", "STATIC_CONFIRMED", True, 3, 2),
        ("9b1c0d2e-0000-4000-8000-000000000002", U(2026, 9, 28, 16, 0, 0), U(2026, 9, 28, 16, 38, 0), "CLOSED", "STATIC_CONFIRMED", True, 3, 2),
        ("9b1c0d2e-0000-4000-8000-000000000003", U(2026, 9, 27, 19, 13, 0), U(2026, 9, 27, 19, 30, 0), "CLOSED", "SAME_SERVING_TIMEOUT", False, 2, 2),
        ("9b1c0d2e-0000-4000-8000-000000000004", U(2026, 9, 29, 6, 0, 0), None, "OPEN", None, True, 2, 1),
    ]
    pending = {(A, B)}
    ids = sorted({r[0].identity for r in routes} | {r[1].identity for r in routes})
    gc = ["identity,radio_tech,local_trust_state,mobility_familiarity,mobility_good_edges,mobility_relevant_trip_count,incoming_routes,outgoing_routes,incoming_transitions,outgoing_transitions,trusted_incoming,trusted_outgoing,first_mobility_seen_utc,last_mobility_seen_utc"]
    nodes = {}
    for i in ids:
        inc = [r for r in routes if r[1].identity == i]
        out = [r for r in routes if r[0].identity == i]
        incident = inc + out
        tc = [trips_per_edge[(r[0], r[1])] for r in incident]
        good = sum(1 for x in tc if x >= 3)
        fam = "KNOWN_ON_ROUTE" if good >= 2 else ("OBSERVED_ON_ROUTE" if any(x > 0 for x in tc) else "UNKNOWN_ON_ROUTE")
        first = U(2026, 8, 3, 6, 0, 0) if any(x > 0 for x in tc) else None
        last = max((r[5] for r in incident if trips_per_edge[(r[0], r[1])] > 0), default=None)
        nodes[i] = (i.rsplit("-", 1)[1], fam, good, max(tc, default=0))
        gc.append(row([i, i.rsplit("-", 1)[1], None, fam, good, max(tc, default=0), len(inc), len(out),
                       sum(r[2] for r in inc), sum(r[2] for r in out), sum(r[3] for r in inc),
                       sum(r[3] for r in out), instant(first) if first else None,
                       instant(last) if last else None], esc_all))
    ge = ["from_identity,to_identity,transition_count,trusted_transition_count,trip_count,pending_current_trip,projected_trip_count_after_valid_close,mobility_first_seen_utc,mobility_last_seen_utc,last_trip_id"]
    open_valid = False   # viaje abierto con 2 celdas: no alcanzaría el mínimo de 3
    for f, t, o, tr, st, ls in routes:
        tc = trips_per_edge[(f, t)]
        p = (f, t) in pending
        ge.append(row([f.identity, t.identity, o, tr, tc, "true" if p else "false",
                       tc + 1 if (p and open_valid) else tc,
                       instant(U(2026, 8, 3, 6, 0, 0)) if tc else None, instant(ls) if tc else None,
                       "9b1c0d2e-0000-4000-8000-000000000001" if tc else None], esc_all))
    mt = ["trip_id,started_at_utc,ended_at_utc,state,close_reason,had_moving,distinct_cells,edge_count"]
    for tid, s, e_, st, cr, hm, dc, ec in trips:
        mt.append(row([tid, instant(s), instant(e_) if e_ else None, st, cr, "true" if hm else "false", dc, ec], esc_all))
    nid = {i: f"n{k}" for k, i in enumerate(ids)}
    g = ['<?xml version="1.0" encoding="UTF-8"?>', '<graphml xmlns="http://graphml.graphdrawing.org/xmlns">']
    for k in ["identity", "radio_tech", "local_trust_state", "mobility_familiarity", "mobility_good_edges", "mobility_relevant_trip_count"]:
        g.append(f'  <key id="{k}" for="node" attr.name="{k}" attr.type="string"/>')
    for k in ["transition_count", "trusted_transition_count", "trip_count"]:
        g.append(f'  <key id="{k}" for="edge" attr.name="{k}" attr.type="int"/>')
    g.append('  <graph id="ICdetectionGeometry" edgedefault="directed">')
    for i in ids:
        rt, fam, good, rel = nodes[i]
        g.append(f'    <node id="{nid[i]}"><data key="identity">{i}</data><data key="radio_tech">{rt}</data><data key="local_trust_state">UNAVAILABLE</data><data key="mobility_familiarity">{fam}</data><data key="mobility_good_edges">{good}</data><data key="mobility_relevant_trip_count">{rel}</data></node>')
    for k, (f, t, o, tr, st, ls) in enumerate(routes):
        g.append(f'    <edge id="e{k}" source="{nid[f.identity]}" target="{nid[t.identity]}"><data key="transition_count">{o}</data><data key="trusted_transition_count">{tr}</data><data key="trip_count">{trips_per_edge[(f, t)]}</data></edge>')
    g += ["  </graph>", "</graphml>"]
    e = {"geometry_cells.csv": ("\n".join(gc) + "\n").encode(), "geometry_edges.csv": ("\n".join(ge) + "\n").encode(),
         "mobility_trips.csv": ("\n".join(mt) + "\n").encode(), "geometry.graphml": ("\n".join(g) + "\n").encode(),
         "metadata.json": js({"schemaVersion": 17, "appVersion": "2.10.5", "appVersionCode": 33,
                              "exportTimeUtc": instant(export_at), "feature": "MOBILITY_FAMILIARITY",
                              "featureMode": "CONTEXT_ONLY", "K_PRIOR_TRIPS": 3, "R_GOOD_EDGES": 2,
                              "MIN_DISTINCT_CELLS": 3, "STATIC_CLOSE_DURATION_MS": 180000,
                              "SAME_SERVING_CLOSE_DURATION_MS": 600000, "MAX_TRIP_DURATION_MS": 21600000,
                              "semanticNote": "Mobility Familiarity is contextual route memory. KNOWN_ON_ROUTE does not mean trusted, verified or legitimate.",
                              "privacyNote": "This export can reveal cell identities and patterns associated with frequently visited places or routes. Review it before sharing publicly.",
                              "coordinatesIncluded": False,
                              "localTrustStateAvailability": "Unavailable in schema 17; left empty rather than inferred.",
                              "openTripContributionIncludedInFamiliarity": False})}
    return zip_bytes(e, export_at), e

# ───────────────────────────── Stable-Site ─────────────────────────────

def rand_key():
    return "".join(RNG.choice("0123456789abcdef") for _ in range(20))


def build_stable_site(export_at):
    home = [rand_key() for _ in range(4)]   # aleatorias: NO derivadas de coordenadas
    work = [rand_key() for _ in range(4)]
    first = epoch_ms(U(2026, 8, 3, 18, 0, 0))
    last_home = epoch_ms(U(2026, 9, 28, 21, 55, 0))
    last_work = epoch_ms(U(2026, 9, 28, 15, 55, 0))
    sites = ["site_key,feature_state,neighbour_capability,maturity_reason,serving_observations,serving_distinct_days,static_distinct_days,full_neighbour_observations,full_neighbour_distinct_days,rf_neighbour_observations,rf_neighbour_distinct_days,first_seen_ms,last_seen_ms"]
    for k in home:
        sites.append(row([k, "ACTIVE", "FULL_NEIGHBOUR_IDENTITY", "MATURE_FULL_NEIGHBOUR_IDENTITY", 2140, 56, 54, 1980, 55, 3100, 56, first, last_home], esc_min))
    for k in work:
        sites.append(row([k, "SHADOW_READY", "RF_NEIGHBOUR_ONLY", "NEEDS_RF_NEIGHBOUR_DAYS", 610, 6, 6, 0, 0, 44, 2, first, last_work], esc_min))
    sc = ["site_key,cell_identity,serving_observations,serving_distinct_days,neighbour_observations,neighbour_distinct_days,first_serving_ms,last_serving_ms,first_neighbour_ms,last_neighbour_ms"]
    for k in home:
        sc.append(row([k, A.identity, 2100, 56, 0, 0, first, last_home, None, None], esc_min))
        sc.append(row([k, B.identity, 38, 20, 1980, 55, first, last_home - 3600_000, first, last_home], esc_min))
        sc.append(row([k, X.identity, 2, 1, 0, 0, epoch_ms(U(2026, 9, 27, 19, 14, 0)), epoch_ms(U(2026, 9, 27, 19, 15, 0)), None, None], esc_min))
    for k in work:
        sc.append(row([k, C.identity, 610, 6, 0, 0, first, last_work, None, None], esc_min))
    rf = ["site_key,rf_fingerprint,evidence_type,radio,arfcn,pci,mcc_metadata,mnc_metadata,tac_metadata,observations,distinct_days,first_seen_ms,last_seen_ms"]
    for k in home:
        rf.append(row([k, "RFCTX:v1:LTE:1850:245", "RF_CONTEXT", "LTE", 1850, 245, None, None, None, 3100, 56, first, last_home], esc_min))
    for k in work:
        rf.append(row([k, "RFCTX:v1:LTE:6300:17", "RF_CONTEXT", "LTE", 6300, 17, None, None, None, 44, 2, first, last_work], esc_min))
    mo = ["site_key,day,state,accuracy_band_m,duration_s,displacement_band_m"]
    for day in ["2026-09-26", "2026-09-27", "2026-09-28"]:
        for k in home:
            mo.append(row([k, day, "STATIC_CONFIRMED", 10, 3600, 0], esc_min))
    for k in work:
        mo.append(row([k, "2026-09-28", "MOVING", 20, 40, 90], esc_min))
    ep = ["episode_id,site_key,previous_identity,candidate_identity,first_seen_ms,last_seen_ms,duration_ms,evaluated_ms,closed_ms,shadow_recorded,reason,motion_state,feature_state,globally_known,known_at_site,seen_as_neighbour,serving_days,neighbour_days,corroborated"]
    f0 = epoch_ms(U(2026, 9, 27, 19, 14, 0, 412))
    l0 = epoch_ms(U(2026, 9, 27, 19, 16, 30, 0))
    ep.append(row(["5e0a7c1d-0000-4000-8000-00000000000a", home[2], A.identity, X.identity, f0, l0, l0 - f0,
                   l0 - 5000, epoch_ms(U(2026, 9, 27, 19, 16, 30, 233)), 1, "NEW_SERVING_WHILE_STATIC_AT_MATURE_SITE",
                   "STATIC_CONFIRMED", "ACTIVE", 0, 0, 0, 1, 0, 0], esc_min))
    ep.append(row(["5e0a7c1d-0000-4000-8000-00000000000b", None, B.identity, C.identity,
                   epoch_ms(U(2026, 9, 28, 6, 25, 40)), epoch_ms(U(2026, 9, 28, 6, 26, 0)), 20000,
                   None, None, 0, None, None, None, 0, 0, 0, 0, 0, 0], esc_min))
    e = {"sites.csv": ("\n".join(sites) + "\n").encode(), "site_cells.csv": ("\n".join(sc) + "\n").encode(),
         "site_rf_neighbours.csv": ("\n".join(rf) + "\n").encode(), "motion.csv": ("\n".join(mo) + "\n").encode(),
         "shadow_episodes.csv": ("\n".join(ep) + "\n").encode()}
    return zip_bytes(e, export_at), e

# ───────────────────────────── ZIP hostiles (para pruebas de seguridad) ─────────────────────────────

def build_hostile_zips():
    out = {}
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        z.writestr("case.json", b"{}")
        z.writestr("../../escape.txt", b"no deberia salir de la carpeta")
        z.writestr("/abs/path.txt", b"ruta absoluta")
    out["SYNTHETIC_hostile_path_traversal.zip"] = buf.getvalue()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("timeline.csv", b"0" * (64 * 1024 * 1024))   # 64 MiB de ceros, comprime a ~64 KiB
    out["SYNTHETIC_hostile_zip_bomb_64MiB.zip"] = buf.getvalue()
    buf = io.BytesIO()
    with zipfile.ZipFile(buf, "w") as z:
        for i in range(5000):
            z.writestr(f"e{i}.txt", b"x")
    out["SYNTHETIC_hostile_many_entries.zip"] = buf.getvalue()
    out["SYNTHETIC_hostile_truncated.zip"] = build_topology(U(2026, 9, 28, 20, 0, 0), ROUTES)[0][:200]
    return out

# ───────────────────────────── principal ─────────────────────────────

def main():
    os.makedirs(OUT, exist_ok=True)
    manifest = {"synthetic": True,
                "warning": "Datos inventados para pruebas. No proceden de ningún dispositivo ni red reales.",
                "generator": "tools/synth/generate_samples.py",
                "assumedDeviceTimeZone": "Europe/Madrid",
                "plmn": "001-01 (red de pruebas)",
                "scenario": ("Tres días (26-28/09/2026, hora de Madrid). Domicilio en celda A, trabajo en C, "
                             "trayectos por B. El 27/09 a las 21:14 locales (19:14 UTC) la servidora pasa "
                             "durante 2,5 min a X (TAC 1099, potencia alta, TA=0), con alarma confirmada del "
                             "colector, breve OUT_OF_SERVICE al volver, caso forense ICD-2026-09-27-0007 y "
                             "episodio de Stable-Site."),
                "files": {}}

    def put(name, data, kind, note, **extra):
        with open(os.path.join(OUT, name), "wb") as f:
            f.write(data)
        manifest["files"][name] = dict(kind=kind, sha256=sha256(data), bytes=len(data), note=note, **extra)

    h, n = build_history()
    put("SYNTHETIC_icdetector_history.csv", h, "history", "Historial v2.10.4+ (35 columnas).", rows=n)
    put("SYNTHETIC_icdetector_history_edge_cases.csv", build_history_edge_cases(), "history",
        "Casos límite: comillas, comas, salto de línea, centinela 2147483647, notación científica, NR_RAW, "
        "STUB_ZERO, Radio UNKNOWN, hora ambigua del 25/10/2026 02:30.")
    put("SYNTHETIC_icdetector_history_v21_23cols.csv", build_history_v21(), "history",
        "Historial con las 23 columnas de v2.1-v2.10.3.")
    bad, _ = build_history_malformed()
    put("SYNTHETIC_icdetector_history_malformed.csv", bad, "history-invalid",
        "Filas con columnas de menos, tipos incorrectos y comillas sin cerrar.")
    s, n = build_service_state()
    put("SYNTHETIC_icdetector_service_state.csv", s, "radio", "Eventos de estado de servicio.", rows=n)
    put("SYNTHETIC_icdetector_service_state_empty.csv", (SERVICE_HEADER + "\n").encode(), "radio",
        "Solo cabecera: puede significar fallo de lectura en origen (export-formats.md §2.2).", rows=0)

    code, z, entries, n = build_forensic()
    put(f"SYNTHETIC_{code}.zip", z, "forensic-case", "Caso READY de origen ALARM.", samples=n,
        entrySha256={k: sha256(v) for k, v in entries.items()})
    _, z2, _, _ = build_forensic(export_at=U(2026, 9, 28, 8, 0, 0))
    put(f"SYNTHETIC_{code}_reexport.zip", z2, "forensic-case",
        "Mismo caso exportado otra vez: ZIP con bytes distintos, entradas idénticas (duplicado exacto por contenido).")
    tampered = dict(entries)
    tampered["timeline.csv"] = entries["timeline.csv"].replace(b'"40"', b'"95"', 1)
    put(f"SYNTHETIC_{code}_tampered.zip", zip_bytes(tampered, U(2026, 9, 27, 19, 30, 12)), "forensic-case",
        "timeline.csv modificado después de exportar: su SHA-256 ya no coincide con SHA256SUMS.txt.")

    z, e = build_topology(U(2026, 9, 28, 20, 0, 0), ROUTES)
    put("SYNTHETIC_ICD-topology.zip", z, "topology", "Agregado de 8 rutas exportado el 28/09.",
        entrySha256={k: sha256(v) for k, v in e.items()})
    z, e = build_geometry(U(2026, 9, 29, 7, 0, 0))
    put("SYNTHETIC_ICD-geometry.zip", z, "geometry",
        "Exportado el 29/09, después de la topología: A->B tiene un handover más. Incluye un viaje abierto y uno descartado (CLOSED con had_moving=false).")
    z, e = build_stable_site(U(2026, 9, 28, 20, 5, 0))
    put("SYNTHETIC_ICD-stable-site.zip", z, "stable-site",
        "Claves de sitio aleatorias (no derivadas de coordenadas). Cada lugar aparece con 4 claves, como en origen.")
    for name, data in build_hostile_zips().items():
        put(name, data, "hostile", "Archivo malicioso o dañado para pruebas de seguridad del importador.")

    with open(os.path.join(OUT, "MANIFEST.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, ensure_ascii=False)
    print(f"{len(manifest['files'])} archivos en {OUT}")


if __name__ == "__main__":
    main()
