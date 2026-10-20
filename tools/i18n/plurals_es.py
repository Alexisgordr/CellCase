#!/usr/bin/env python3
"""Convierte «{n} palabra(s) adjetivo(s)» en `{n,plural,one{# palabra adjetivo}other{# palabras adjetivos}}`.
El tramo va desde el marcador hasta la última palabra con (s)/(es) antes de otro marcador o de ; . : — ("""
import re, sys
path = sys.argv[1]
WORD = r"[^\s{}();:.,—]*"
def conv(v):
    out, i = [], 0
    for m in re.finditer(r"\{(\d+)\}", v):
        out.append(v[i:m.start()])
        rest = v[m.end():]
        stop = re.search(r"[;.:—(]\s|[;.:]$|\{|\(\D", rest)
        # tramo candidato: hasta el corte
        seg_end = len(rest)
        for mm in re.finditer(r"\{\d+\}|[;:—]|\. |\.$| \((?!s\)|es\))", rest):
            seg_end = mm.start(); break
        seg = rest[:seg_end]
        last = None
        for mm in re.finditer(WORD + r"\((?:s|es)\)", seg):
            last = mm
        if last is None:
            out.append(m.group(0)); i = m.end(); continue
        span = seg[:last.end()]
        one = re.sub(r"\((?:s|es)\)", "", span)
        other = re.sub(r"\((s|es)\)", lambda x: x.group(1), span)
        out.append("{%s,plural,one{#%s}other{#%s}}" % (m.group(1), one, other))
        i = m.end() + last.end()
    out.append(v[i:])
    return "".join(out)
lines = open(path, encoding="utf-8").read().splitlines()
res = []
for l in lines:
    if " = " in l and not l.startswith("#") and re.search(r"\((?:s|es)\)", l):
        k, v = l.split(" = ", 1)
        nv = conv(v)
        print(f"{k}\n   {v}\n-> {nv}")
        l = f"{k} = {nv}"
    res.append(l)
open(path, "w", encoding="utf-8").write("\n".join(res) + "\n")
