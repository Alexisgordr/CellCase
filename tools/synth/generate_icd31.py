#!/usr/bin/env python3
"""
Muestras SINTÉTICAS de ICdetector 3.1.0: una base que empezó con 3.0.0 y se actualizó a 3.1.0 (esquema 26), su copia de
seguridad `.icdbackup` y las exportaciones que haría ICdetector 3.1.0 con esa base. Todo es inventado (PLMN 001-01,
posiciones cerca de (0, 0), prefijo SYNTHETIC_).

Se construye sobre generate_icd30.py (mismas filas de 2.10.x y 3.0.0) y añade lo que cambia en 3.1.0, leído del código
de ICdetector 3.1.0 (versionCode 40):
- `SchemaV26`: tres columnas al final de `history` (`churn_cells_15m`, `churn_changes_15m`, `churn_motion`), vacías en
  las filas anteriores.
- `ExportUtils.CSV_HEADER`/`csvRow`: `ChurnCells15m,ChurnChanges15m,ChurnMotion` al final (57 columnas).
- Filas de 3.1.0: `Verified` siempre `PENDING` y `ApiLat`/`ApiLon` vacías (verificación externa retirada);
  `NotEvaluatedHeuristics` con `H12(retirada)` y `H17(sombra)` en todas, y los motivos nuevos de H6 y H11.
- `ForensicRecorder.payload`: sin `latencyState` (la columna `latency_state` del ZIP queda vacía) y `verified` PENDING.

Uso: python3 tools/synth/generate_icd31.py [carpeta]   (por defecto samples/synthetic/icd31)
Determinista: misma salida en cada ejecución.
"""
import json
import os
import random
import sqlite3
import sys
import tempfile
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
_argv = sys.argv[:]
sys.argv = sys.argv[:1]          # generate_icd30 lee la carpeta de salida de argv al importarse
import generate_icd30 as g       # noqa: E402
sys.argv = _argv

OUT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(g.ROOT, "samples", "synthetic", "icd31"))
APP_VERSION_31 = "3.1.0+90627b7"      # AppBuildId de la 3.1.0 (BUILD_COMMIT del código de referencia)
APP_VERSION_CODE_31 = 40
SCHEMA_31 = 26
BACKUP_AT = datetime(2026, 10, 29, 18, 40, 11, 500000, tzinfo=timezone.utc)
EXPORT_AT = datetime(2026, 10, 29, 18, 45, 0, 0, tzinfo=timezone.utc)
EXPORT_DEVICE = ("SYNTHETIC Phone 3", "16 (API 36)")
V26 = ["ALTER TABLE history ADD COLUMN churn_cells_15m INTEGER",
       "ALTER TABLE history ADD COLUMN churn_changes_15m INTEGER",
       "ALTER TABLE history ADD COLUMN churn_motion TEXT"]
# Motivos de abstención que escribe 3.1.0 (HeuristicReport.notEvaluatedIds): H12 retirada y H17 en sombra en todas las filas
NOT_EVALUATED_31 = ["H9;H12(retirada);H17(sombra)", "H9;H12(retirada);H17(sombra)", "H6(ta_sin_verificar);H9;H12(retirada);H17(sombra)",
                    "H6(ta_stub);H9;H11(fix_impreciso);H12(retirada);H17(sombra)", "H9;H11(fix_viejo);H12(retirada);H17(sombra)"]


def rows_31():
    """Filas grabadas por 3.1.0 tras actualizar (27-29/10), con el churn de H17 y una franja con muchos cambios."""
    rng = random.Random(20261021)
    out = []
    t = datetime(2026, 10, 27, 7, 0, 4, 250000, tzinfo=timezone.utc)
    end = datetime(2026, 10, 29, 18, 0, tzinfo=timezone.utc)
    day = ["1000980", "1000071", "1000542", "1000544", "1000069"]
    i = 0
    while t < end:
        lh = t.astimezone(g.TZ).hour
        # 28/10 de 17:00 a 17:40 (hora local): trayecto con muchos cambios de servidora
        trip = t.astimezone(g.TZ).date().day == 28 and 17 <= lh < 18 and t.astimezone(g.TZ).minute < 40
        cid = (day[i % len(day)] if trip else "1000070" if (lh < 8 or lh >= 21 or rng.random() < 0.45) else rng.choice(day))
        tac, e, p0, d0, _ = g.CELLS[cid]
        gnss = rng.random() < 0.9
        motion = "MOVING" if trip else ("UNKNOWN" if not gnss else rng.choice(["STILL", "STILL", "STILL", "MOVING"]))
        cells = rng.randint(4, 7) if trip else rng.randint(1, 2) if motion == "STILL" else rng.randint(1, 4)
        changes = cells - 1 + (rng.randint(2, 5) if trip else rng.randint(0, 1))
        reason = "OK"
        if i == 25:
            reason = g.SUB + "Potencia anómala vs historial (+21dB)"
        r = dict(timestamp=g.local_text(g.ms(t)), net_type="4G", cid=cid, mnc="01", tac=tac, mcc="001", dbm=(-79 if i == 25 else d0 + rng.randint(-6, 6)),
                 verified="PENDING", score=100 if reason == "OK" else 95, failed_heuristics=reason,
                 lat=round(0.010 + rng.uniform(-0.002, 0.002), 6) if gnss else None, lon=round(0.020 + rng.uniform(-0.002, 0.002), 6) if gnss else None,
                 pci=p0, arfcn=e, rsrq=rng.randint(-14, -7), sinr=rng.randint(0, 20), threat_prob=0.0 if reason == "OK" else round(rng.uniform(1, 4), 1),
                 api_lat=None, api_lon=None, ta=0, ta_unit="STUB_ZERO", radio="LTE", conn_status="PRIMARY_SERVING",
                 bandwidth_khz=10000 if e == 6300 else 20000, bands=g.BAND.get(e, ""), additional_plmns=None, csg_indicator=None, csg_identity=None,
                 csg_name=None, secondary_carriers=None, service_state="IN_SERVICE", network_operator="00101", sim_operator="00101", network_roaming=0,
                 observed_at_ms=g.ms(t), not_evaluated_heuristics=NOT_EVALUATED_31[i % len(NOT_EVALUATED_31)],
                 gps_accuracy_m=round(rng.uniform(3, 140), 1) if gnss else None, app_version=APP_VERSION_31, location_mode="INTELLIGENT",
                 observation_id=g.uuid(rng), churn_cells_15m=cells, churn_changes_15m=changes, churn_motion=motion)
        out.append(r)
        i += 1
        t += timedelta(minutes=rng.choice([2, 3]) if trip else rng.choice([10, 15, 20, 30]), seconds=rng.randint(0, 50), milliseconds=rng.randint(0, 999))
    return out


def forensic_31(db, rng):
    """Caso forense de 3.1.0: muestras sin `latencyState` y con `verified` PENDING (ForensicRecorder 3.1.0)."""
    start = g.ms(datetime(2026, 10, 28, 15, 12, 0, tzinfo=timezone.utc))
    idn = g.ident("1000542")
    cur = db.execute("INSERT INTO forensic_cases (case_code, created_at, updated_at, closed_at, state, cell_identity, highest_phase, confirmed, created_at_ms, updated_at_ms) "
                     "VALUES (?,?,?,?,?,?,?,?,?,?)", ("ICD-2026-10-28-0004", g.local_text(start), g.local_text(start + 120_000), g.local_text(start + 150_000),
                                                     "READY", idn, 2, 0, start, start + 120_000))
    case_id = cur.lastrowid
    for k in range(4):
        p = json.loads(g.payload(rng, start + 30_000 * k, "1000542", min(k + 1, 2), [f"15:1{k}:0{s} ciclo {s}" for s in range(5)], secondary=False, trust=True, gps=True))
        del p["latencyState"]
        p["verified"] = "PENDING"
        p["reason"] = g.SUB + "Downgrade de banda forzado (2620MHz→925MHz, B7→B8)"
        p["heuristics"] = "H1=PASSED;H12=NOT_EVALUATED(retirada);H17=NOT_EVALUATED(sombra)"
        p["diagnostics"] = [dict(id=14, name="Banda", status="FAILED", explanation="B7→B8 sin cambio de estación base"),
                            dict(id=12, name="Latencia + RF", status="NOT_EVALUATED", explanation="regla retirada en 3.1.0")]
        db.execute("INSERT INTO forensic_samples (case_id, wall_time_ms, elapsed_time_ms, event, payload_json) VALUES (?,?,?,?,?)",
                   (case_id, start + 30_000 * k, 5_000_000 + 30_000 * k, "SAMPLE" if k else "OPEN", json.dumps(p, ensure_ascii=False)))


def create_db31(path):
    """Base de 3.0.0 (la `fresh` de generate_icd30) actualizada a 3.1.0: ALTER del esquema 26 y filas nuevas."""
    g.create_db(path, upgraded=False)
    db = sqlite3.connect(path, isolation_level=None)
    db.execute("BEGIN")
    for s in V26:
        db.execute(s)
    rows = rows_31()
    cols = list(rows[0])
    for r in rows:
        db.execute(f"INSERT INTO history ({','.join(cols)}) VALUES ({','.join('?' * len(cols))})", [r[c] for c in cols])
    forensic_31(db, random.Random(31))
    db.execute("INSERT INTO profile_mode_events (at_ms, mode, app_version) VALUES (?,?,?)",
               (g.ms(datetime(2026, 10, 27, 6, 59, tzinfo=timezone.utc)), "SHADOW", APP_VERSION_31))
    db.execute("COMMIT")
    db.execute(f"PRAGMA user_version={SCHEMA_31}")
    db.close()


def history_csv31(con, device):
    """`ExportUtils.csvRow` de 3.1.0: las 54 columnas de 3.0.0 y el churn al final (`item.churnCells15m ?: ""`…)."""
    base = g.history_csv(con, device).decode().split("\n")
    assert base[-1] == ""
    ids = [r[0] for r in con.execute("SELECT id FROM history ORDER BY observed_at_ms DESC, id DESC")]
    churn = {r[0]: r[1:] for r in con.execute("SELECT id, churn_cells_15m, churn_changes_15m, churn_motion FROM history")}
    out = [base[0] + ",ChurnCells15m,ChurnChanges15m,ChurnMotion"]
    for line, hid in zip(base[1:-1], ids):
        c, ch, m = churn[hid]
        out.append(line + "," + ",".join(g.field(v) for v in (None if c is None else g.get_int(c), None if ch is None else g.get_int(ch), g.get_string(m))))
    assert len(out) == len(base) - 1
    return ("\n".join(out) + "\n").encode()


def export_all31(db_path, device, at_ms):
    con = sqlite3.connect(db_path)
    try:
        return dict(history=history_csv31(con, device), service=g.service_csv(con),
                    topology=g.topology(con, APP_VERSION_31, APP_VERSION_CODE_31, at_ms),
                    geometry=g.geometry(con, APP_VERSION_31, APP_VERSION_CODE_31, at_ms), stable_site=g.stable_site(con),
                    forensic=g.forensic(con, APP_VERSION_31, APP_VERSION_CODE_31))
    finally:
        con.close()


def backup31(db_path):
    data = open(db_path, "rb").read()
    manifest = dict(format=1, appId="com.alexisgordr.icdetector", appVersion=APP_VERSION_31, schemaVersion=SCHEMA_31,
                    createdAtUtc=g.instant_ms(g.ms(BACKUP_AT)), databaseSha256=g.sha(data), databaseBytes=len(data))
    # 3.1.0 ya no guarda los ajustes de latencia ni de proxy (BackupSettings)
    settings = dict(app_language="es", location_mode="INTELLIGENT", legacy_network_alert_mode="NOTIFY", stable_site_silent_notices=True,
                    resume_after_boot=False)
    return g.zip_bytes({"manifest.json": g.j_dump(manifest).encode(), "icdetector_history.db": data, "settings.json": g.j_dump(settings).encode()}, g.ms(BACKUP_AT))


def main():
    os.makedirs(OUT, exist_ok=True)
    tmp = tempfile.mkdtemp()
    db = os.path.join(tmp, "icd31.db")
    create_db31(db)
    g.write(os.path.join(OUT, "SYNTHETIC_icd31.icdbackup"), backup31(db))
    # Lo que CellCase debe reconstruir desde la copia: exportación en el momento de la copia, sin teléfono de exportación
    g.write_expected(os.path.join(OUT, "expected"), export_all31(db, ("", ""), g.ms(BACKUP_AT)))
    g.write_exports(os.path.join(OUT, "SYNTHETIC_icd31"), export_all31(db, EXPORT_DEVICE, g.ms(EXPORT_AT)), g.ms(EXPORT_AT))
    print("Muestras de ICdetector 3.1.0 escritas en", OUT)


if __name__ == "__main__":
    main()
