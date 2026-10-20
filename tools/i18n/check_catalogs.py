#!/usr/bin/env python3
"""Comprueba que los catálogos tienen las mismas claves, los mismos marcadores y plurales bien formados."""
import re, sys
def load(p):
    out = {}
    for n, l in enumerate(open(p, encoding="utf-8"), 1):
        l = l.rstrip("\n")
        if not l or l.startswith("#"): continue
        k, v = l.split(" = ", 1)
        if k in out: print(f"{p}:{n}: clave repetida {k}")
        out[k] = v
    return out
def marks(v): return sorted(set(re.findall(r"\{(\d+)", v)))
def plurals_ok(v):
    for m in re.finditer(r"\{\d+,plural,", v):
        i, depth = m.end(), 1
        forms = ""
        while depth and i < len(v):
            c = v[i]; depth += (c == "{") - (c == "}"); forms += c; i += 1
        if depth or "one{" not in forms or "other{" not in forms: return False
    return True
es, en = load(sys.argv[1]), load(sys.argv[2])
bad = 0
for k in sorted(set(es) - set(en)): print("falta en inglés:", k); bad += 1
for k in sorted(set(en) - set(es)): print("sobra en inglés:", k); bad += 1
for k in sorted(set(es) & set(en)):
    if marks(es[k]) != marks(en[k]): print("marcadores distintos:", k, marks(es[k]), marks(en[k])); bad += 1
    for lang, v in (("es", es[k]), ("en", en[k])):
        if not plurals_ok(v): print("plural mal formado:", lang, k); bad += 1
    if es[k].startswith(" ") != en[k].startswith(" ") or es[k].endswith(" ") != en[k].endswith(" "):
        print("espacios de borde distintos:", k); bad += 1
print(f"{len(es)} claves; {bad} problemas")
sys.exit(1 if bad else 0)
