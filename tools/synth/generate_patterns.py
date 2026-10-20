#!/usr/bin/env python3
"""
Genera una muestra SINTÉTICA de historial y Radio que reproduce los PATRONES (no los datos) de un expediente
real de prueba: cambios de banda LTE sub-umbral repetidos en dos celdas y en dos días, una alternancia de PCI,
una desviación de potencia y episodios próximos en el tiempo.

- Identidades de red de pruebas (MCC 001, MNC 01), posiciones cerca de (0,0) y prefijo SYNTHETIC_.
- Ninguna coordenada, identidad ni hora procede del expediente real: solo la forma de los patrones.
- Determinista (semilla fija).

Uso: python3 tools/synth/generate_patterns.py  → samples/synthetic/SYNTHETIC_patrones_*.csv
"""
import csv, os, random
from datetime import datetime, timedelta, timezone
from zoneinfo import ZoneInfo

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "samples", "synthetic")
MADRID = ZoneInfo("Europe/Madrid")
rnd = random.Random(20261002)

HEADER = ("Timestamp,NetType,CID,MNC,TAC,MCC,DBM,Verified,SecurityScore,FailedHeuristics,Lat,Lon,PCI,ARFCN,RSRQ,SINR,AnomalyConfidence,"
          "ApiLat,ApiLon,TA,TAUnit,TAMeters,Radio,ServingConnection,BandwidthKHz,Bands,AdditionalPlmns,CsgIndicator,CsgIdentity,CsgName,"
          "SecondaryCarriers,ServiceState,NetworkOperator,SimOperator,NetworkRoaming").split(",")

# celdas sintéticas: cid → (tac, earfcn por defecto, pci, dBm típico, verificación)
CELLS = {
    "1000070": ("3001", 6300, 70, -100, "NOT_FOUND"),   # habitual (casa), B20
    "1000980": ("3001", 1300, 98, -95, "VERIFIED"),     # B3
    "1000069": ("3001", 6300, 69, -92, "NOT_FOUND"),
    "1000071": ("3001", 1300, 71, -97, "VERIFIED"),
    "1000542": ("3002", 3100, 42, -90, "VERIFIED"),     # B7
    "1000543": ("3002", 6300, 43, -85, "VERIFIED"),
    "1000544": ("3002", 3100, 44, -95, "PENDING"),
    "1000545": ("3002", 3600, 45, -88, "NOT_FOUND"),    # B8
    "1000060": ("3002", 1300, 60, -96, "VERIFIED"),
    "1000061": ("3002", 6300, 61, -90, "NOT_FOUND"),
    "1000953": ("3003", 1300, 101, -108, "NOT_FOUND"),
    "1000989": ("3003", 1300, 89, -112, "VERIFIED"),
}
BAND = {1300: "3", 3100: "7", 3600: "8", 6300: "20"}

rows = []

def add(local_dt, cid, reason="OK", earfcn=None, pci=None, dbm=None, lat=0.010, lon=0.020):
    tac, e0, p0, d0, ver = CELLS[cid]
    e = earfcn if earfcn is not None else e0
    d = dbm if dbm is not None else d0 + rnd.randint(-6, 6)
    sub = reason.startswith("[sub-umbral]")
    gnss = rnd.random() < 0.92
    rows.append({
        "Timestamp": local_dt.strftime("%Y-%m-%d %H:%M:%S"), "NetType": "4G", "CID": cid, "MNC": "01", "TAC": tac, "MCC": "001",
        "DBM": str(d), "Verified": ver, "SecurityScore": "100" if reason == "OK" else "95", "FailedHeuristics": reason,
        "Lat": f"{lat + rnd.uniform(-0.002, 0.002):.6f}" if gnss else "", "Lon": f"{lon + rnd.uniform(-0.002, 0.002):.6f}" if gnss else "",
        "PCI": str(pci if pci is not None else p0), "ARFCN": str(e), "RSRQ": str(rnd.randint(-14, -7)), "SINR": str(rnd.randint(0, 20)),
        "AnomalyConfidence": "0.0" if reason == "OK" else f"{rnd.uniform(4, 11):.1f}", "ApiLat": "", "ApiLon": "",
        "TA": "0", "TAUnit": "STUB_ZERO", "TAMeters": "", "Radio": "LTE", "ServingConnection": "PRIMARY_SERVING",
        "BandwidthKHz": "10000" if e == 6300 else "20000", "Bands": BAND.get(e, ""), "AdditionalPlmns": "", "CsgIndicator": "",
        "CsgIdentity": "", "CsgName": "", "SecondaryCarriers": "", "ServiceState": "IN_SERVICE", "NetworkOperator": "00101",
        "SimOperator": "00101", "NetworkRoaming": "0",
    })

def L(s):  # hora local del teléfono
    return datetime.strptime(s, "%Y-%m-%d %H:%M:%S")

# Fondo: muestreo cada 5–15 min, celda habitual de noche, varias de día.
t = L("2026-09-24 23:46:33"); end = L("2026-10-02 03:13:13")
day_cells = ["1000980", "1000071", "1000542", "1000544", "1000060", "1000989", "1000069"]
while t < end:
    cid = "1000070" if (t.hour < 8 or t.hour >= 21 or rnd.random() < 0.45) else rnd.choice(day_cells)
    add(t, cid)
    t += timedelta(minutes=rnd.choice([5, 5, 5, 10, 15]), seconds=rnd.randint(0, 50))

SUB = "[sub-umbral] "
def band_change(at, prev_cid, prev_earfcn, cid, earfcn, f_from, f_to, b_from, b_to, gap=25):
    add(at - timedelta(seconds=gap), prev_cid, earfcn=prev_earfcn)
    add(at, cid, SUB + f"Downgrade de banda forzado ({f_from}MHz→{f_to}MHz, B{b_from}→B{b_to})", earfcn=earfcn)

# 25/09
band_change(L("2026-09-25 06:23:20"), "1000980", 1300, "1000069", 6300, 1805, 791, 3, 20)
band_change(L("2026-09-25 12:02:12"), "1000544", 3100, "1000545", 3600, 2620, 925, 7, 8)
band_change(L("2026-09-25 12:33:07"), "1000542", 3100, "1000543", 6300, 2620, 791, 7, 20)
# 27/09: potencia +19 dB en la celda habitual
add(L("2026-09-27 16:48:53"), "1000070", SUB + "Potencia anómala vs historial (+19dB)", dbm=-81)
# 29/09: muestreo denso entre 13:30 y 14:40 (desplazamiento)
t = L("2026-09-29 13:30:00")
while t < L("2026-09-29 14:40:00"):
    add(t, rnd.choice(["1000542", "1000544", "1000060", "1000989"]), lat=0.03, lon=0.04)
    t += timedelta(seconds=rnd.randint(20, 90))
# alternancia de PCI en 1000953 (misma portadora 1300)
add(L("2026-09-29 13:53:20"), "1000953", pci=101, lat=0.03, lon=0.04)
add(L("2026-09-29 13:53:44"), "1000953", SUB + "Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)", pci=102, lat=0.03, lon=0.04)
add(L("2026-09-29 13:54:10"), "1000953", pci=101, lat=0.03, lon=0.04)
add(L("2026-09-29 13:54:36"), "1000953", SUB + "Identidad RF inestable: misma Cell ID alternando PCI en la misma portadora (posible clon)", pci=102, lat=0.03, lon=0.04)
band_change(L("2026-09-29 14:00:42"), "1000060", 1300, "1000061", 6300, 1805, 791, 3, 20)
band_change(L("2026-09-29 14:05:57"), "1000542", 3100, "1000545", 3600, 2620, 925, 7, 8)
band_change(L("2026-09-29 14:17:50"), "1000544", 3100, "1000543", 6300, 2620, 791, 7, 20)
band_change(L("2026-09-29 14:18:24"), "1000060", 1300, "1000545", 3600, 1805, 925, 3, 8, gap=15)
band_change(L("2026-09-29 14:26:58"), "1000060", 1300, "1000545", 3600, 1805, 925, 3, 8)
band_change(L("2026-09-29 14:34:03"), "1000060", 1300, "1000543", 6300, 1805, 791, 3, 20)

# Muestras de la celda habitual para la referencia de potencia: dBm en torno a -100
rows.sort(key=lambda r: r["Timestamp"])
# ICdetector exporta de más reciente a más antiguo
rows.reverse()
os.makedirs(OUT, exist_ok=True)
with open(os.path.join(OUT, "SYNTHETIC_patrones_historial.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.DictWriter(f, fieldnames=HEADER, lineterminator="\n")
    w.writeheader(); w.writerows(rows)

# Radio: 18 cambios de estado, ninguno cerca de los episodios
RH = "TimestampUtc,State,DataRegistered,VoiceRegistered,Searching,Roaming,NetworkOperator,NetworkName,SimOperator,ManualSelection,ChannelNumber,CellBandwidthsKHz,DataNetwork,VoiceNetwork,Source".split(",")
ev = [("2026-09-24 23:46:33", "IN_SERVICE")]
for d, h in [("2026-09-29", "19:20"), ("2026-09-29", "23:41"), ("2026-09-30", "08:05"), ("2026-09-30", "13:02"), ("2026-09-30", "18:44"),
             ("2026-10-01", "07:12"), ("2026-10-01", "12:30"), ("2026-10-01", "19:19")]:
    t0 = L(f"{d} {h}:00")
    ev += [(t0.strftime("%Y-%m-%d %H:%M:%S"), "OUT_OF_SERVICE"), ((t0 + timedelta(minutes=2)).strftime("%Y-%m-%d %H:%M:%S"), "IN_SERVICE" if rnd.random() < 0.6 else "POWER_OFF")]
ev.append(("2026-10-01 19:25:00", "IN_SERVICE"))
with open(os.path.join(OUT, "SYNTHETIC_patrones_radio.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f, lineterminator="\n"); w.writerow(RH)
    for ts, st in ev:
        utc = L(ts).replace(tzinfo=MADRID).astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        w.writerow([utc, st, int(st == "IN_SERVICE"), int(st == "IN_SERVICE"), 0, 0, "00101", "Red de Pruebas", "00101", 0, 1300, 20000, "LTE", "LTE", "CALLBACK"])
print(f"{len(rows)} filas de historial, {len(ev)} eventos de Radio")
