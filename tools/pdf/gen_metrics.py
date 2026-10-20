#!/usr/bin/env python3
"""Regenera core/.../pdf/StandardFontMetrics.kt. Requiere `pip install reportlab` (BSD).
Solo se usa en desarrollo: la app no depende de reportlab."""
import os
import reportlab
from reportlab.pdfbase.pdfmetrics import getFont

UNDEFINED = (0x81, 0x8D, 0x8F, 0x90, 0x9D)
DEST = os.path.join(os.path.dirname(__file__), "..", "..", "core", "src", "main", "kotlin",
                    "com", "alexisgordr", "cellcase", "core", "pdf", "StandardFontMetrics.kt")

out = ["package com.alexisgordr.cellcase.core.pdf\n",
       "/**\n * Anchos de glifo (unidades de 1/1000 em) de las fuentes estándar PDF Helvetica y Helvetica-Bold\n"
       " * para los códigos WinAnsi 32..255. Generado por tools/pdf/gen_metrics.py a partir de las métricas\n"
       f" * AFM Core14 de Adobe, leídas mediante reportlab {reportlab.Version} (licencia BSD). Las métricas AFM de Adobe\n"
       " * permiten su uso y redistribución. Courier es monoespaciada (600) y no necesita tabla.\n"
       " * Códigos sin glifo en WinAnsi (0x81, 0x8D, 0x8F, 0x90, 0x9D) valen 0: nunca se emiten.\n */\n"
       "internal object StandardFontMetrics {\n"]
for name, var in [("Helvetica", "HELVETICA"), ("Helvetica-Bold", "HELVETICA_BOLD")]:
    f = getFont(name)
    w = [0 if c in UNDEFINED else int(round(f.stringWidth(bytes([c]).decode("cp1252"), 1000)))
         for c in range(32, 256)]
    rows = [", ".join(str(x) for x in w[i:i + 16]) for i in range(0, len(w), 16)]
    out.append("    val %s: IntArray = intArrayOf(\n        %s\n    )\n" % (var, ",\n        ".join(rows)))
out.append("}\n")
with open(DEST, "w", encoding="utf-8") as fh:
    fh.write("\n".join(out))
print("escrito", os.path.abspath(DEST))
