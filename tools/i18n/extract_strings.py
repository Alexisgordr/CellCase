#!/usr/bin/env python3
"""
Herramienta de desarrollo (no forma parte de la app): extrae los literales de texto de un archivo Kotlin a un
catálogo `clave = plantilla` y sustituye cada literal por una llamada `FN("clave", arg0, arg1…)`.

- Los `${expr}` y `$ident` de una plantilla pasan a ser argumentos `{0}`, `{1}`… y se convierten de forma
  recursiva (un literal dentro de una expresión también se extrae).
- No se extraen: claves de mapas (`v["x"]`, `"x" to`), condiciones de `when` (`"X" ->`), comparaciones, patrones
  de fecha o formato, argumentos de funciones que manipulan texto, identificadores en MAYÚSCULAS.
- Plantillas idénticas comparten clave.

Uso: extract_strings.py ARCHIVO.kt PREFIJO FN CATALOGO_SALIDA [CATALOGO_EXISTENTE]
"""
import re
import sys
import unicodedata

NO_EXTRACT_CALLS = ("ofPattern", "format", "Regex", "startsWith", "endsWith", "contains", "substringBefore",
                    "substringAfter", "split", "replace", "removePrefix", "removeSuffix", "indexOf", "forLanguageTag",
                    "getColumnIndexOrThrow", "require", "trimEnd", "lastIndexOf", "matches", "equals")
WORD = re.compile(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]{2,}")


def slug(text, n=5):
    t = unicodedata.normalize("NFKD", text).encode("ascii", "ignore").decode()
    words = re.findall(r"[A-Za-z0-9]+", t.lower())
    return "_".join(words[:n]) or "txt"


class Lit:
    def __init__(self, start, end, parts):
        self.start, self.end, self.parts = start, end, parts  # parts: ("text", str) | ("expr", str)


def read_string(src, i):
    """src[i] == '"'. Devuelve (fin, partes) con fin = índice tras la comilla de cierre."""
    assert src[i] == '"'
    i += 1
    parts, buf = [], []
    while True:
        c = src[i]
        if c == '"':
            if buf:
                parts.append(("text", "".join(buf)))
            return i + 1, parts
        if c == "\\":
            n = src[i + 1]
            esc = {"n": "\n", "t": "\t", "r": "\r", '"': '"', "\\": "\\", "$": "$", "'": "'", "b": "\b"}
            if n == "u":
                buf.append(chr(int(src[i + 2:i + 6], 16)))
                i += 6
            else:
                buf.append(esc[n])
                i += 2
            continue
        if c == "$" and src[i + 1] == "{":
            if buf:
                parts.append(("text", "".join(buf)))
                buf = []
            j = skip_braces(src, i + 1)
            parts.append(("expr", src[i + 2:j - 1]))
            i = j
            continue
        if c == "$" and (src[i + 1].isalpha() or src[i + 1] == "_"):
            if buf:
                parts.append(("text", "".join(buf)))
                buf = []
            m = re.match(r"[A-Za-z_][A-Za-z0-9_]*", src[i + 1:])
            parts.append(("expr", m.group(0)))
            i += 1 + len(m.group(0))
            continue
        buf.append(c)
        i += 1


def skip_braces(src, i):
    """src[i] == '{'. Devuelve índice tras la llave de cierre, saltando cadenas y caracteres."""
    depth = 0
    while True:
        c = src[i]
        if c == '"':
            i, _ = read_string(src, i)
            continue
        if c == "'":
            i = skip_char(src, i)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1


def skip_char(src, i):
    j = i + 1
    if src[j] == "\\":
        j += 2
        while src[j] != "'":
            j += 1
        return j + 1
    return j + 2


def literals(src):
    """Literales de primer nivel (fuera de comentarios)."""
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith("//", i):
            i = src.index("\n", i)
            continue
        if src.startswith("/*", i):
            i = src.index("*/", i) + 2
            continue
        if src.startswith('"""', i):
            i = src.index('"""', i + 3) + 3
            continue
        c = src[i]
        if c == "'":
            i = skip_char(src, i)
            continue
        if c == '"':
            end, parts = read_string(src, i)
            out.append(Lit(i, end, parts))
            i = end
            continue
        i += 1
    return out


class Catalog:
    def __init__(self, prefix, existing=None):
        self.prefix = prefix
        self.by_text = {}
        self.entries = []  # (key, text)
        self.keys = set()
        if existing:
            for k, v in existing:
                self.by_text[v] = k
                self.keys.add(k)

    def key_for(self, func, text):
        if text in self.by_text:
            return self.by_text[text]
        base = f"{self.prefix}.{func}.{slug(re.sub(r'\{[0-9]+\}', ' ', text))}"
        k, n = base, 2
        while k in self.keys:
            k = f"{base}_{n}"
            n += 1
        self.keys.add(k)
        self.by_text[text] = k
        self.entries.append((k, text))
        return k


REQUIRE_SPACE = False


def is_translatable(src, lit):
    text = "".join(p for t, p in lit.parts if t == "text")
    if not WORD.search(text):
        return False
    if REQUIRE_SPACE and not re.search(r"[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]{2,}[^{}]*\s+[^{}]*[A-Za-zÁÉÍÓÚÜÑáéíóúüñ]{2,}|[áéíóúñ¿¡]", text):
        return False
    whole = text if all(t == "text" for t, _ in lit.parts) else None
    before = src[:lit.start].rstrip()
    after = src[lit.end:].lstrip()
    if whole is not None:
        if re.fullmatch(r"[A-Z0-9_./:\-]+", whole):
            return False
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", whole) and (before.endswith("[") or after.startswith("to ") or after.startswith("]")):
            return False
    if after.startswith("->") or after.startswith("==") or after.startswith("!="):
        return False
    if before.endswith("==") or before.endswith("!=") or before.endswith(" in"):
        return False
    m = re.search(r"([A-Za-z_]+)\s*\(\s*$", before)
    if m and m.group(1) in NO_EXTRACT_CALLS:
        return False
    if re.search(r"const\s+val\s+\w+\s*(:\s*\w+)?\s*=\s*$", before):
        return False
    return True


def func_at(src, pos):
    m = None
    for m in re.finditer(r"\bfun\s+(?:<[^>]*>\s*)?(?:[A-Za-z_.<>]+\.)?([A-Za-z_][A-Za-z0-9_]*)\s*\(", src[:pos]):
        pass
    if m:
        return m.group(1)
    vals = list(re.finditer(r"\bval\s+([A-Za-z_][A-Za-z0-9_]*)", src[:pos]))
    return vals[-1].group(1) if vals else "top"


def convert(src, cat, fn, root_src=None, offset=0):
    """Convierte todos los literales de `src` (que puede ser un fragmento de expresión)."""
    root = root_src if root_src is not None else src
    lits = literals(src)
    out, last = [], 0
    for lit in lits:
        out.append(src[last:lit.start])
        out.append(convert_literal(src, lit, cat, fn, root, offset))
        last = lit.end
    out.append(src[last:])
    return "".join(out)


def convert_literal(src, lit, cat, fn, root, offset):
    if is_translatable(src, lit):
        text, args = [], []
        for t, p in lit.parts:
            if t == "text":
                text.append(p)
            else:
                text.append("{%d}" % len(args))
                args.append(convert(p, cat, fn, root, offset + lit.start).strip())
        template = "".join(text)
        key = cat.key_for(func_at(root, offset + lit.start), template)
        return f'{fn}("{key}"' + "".join(", " + a for a in args) + ")"
    # no se traduce: se reconstruye con las expresiones convertidas
    rebuilt = ['"']
    for t, p in lit.parts:
        if t == "text":
            rebuilt.append(kotlin_escape(p))
        else:
            rebuilt.append("${" + convert(p, cat, fn, root, offset + lit.start) + "}")
    rebuilt.append('"')
    return "".join(rebuilt)


def kotlin_escape(p):
    out = []
    for ch in p:
        if ch == "\\": out.append("\\\\")
        elif ch == '"': out.append('\\"')
        elif ch == "$": out.append("\\$")
        elif ch == "\n": out.append("\\n")
        elif ch == "\t": out.append("\\t")
        elif ch == "\r": out.append("\\r")
        elif ch == "\b": out.append("\\b")
        elif ord(ch) < 0x20: out.append("\\u%04x" % ord(ch))
        else: out.append(ch)
    return "".join(out)


def escape_value(v):
    return v.replace("\\", "\\\\").replace("\n", "\\n")


def read_catalog(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            k, v = line.split(" = ", 1)
            out.append((k, v.replace("\\n", "\n").replace("\\\\", "\\")))
    return out


def main():
    global REQUIRE_SPACE
    args = [a for a in sys.argv[1:] if a != "--require-space"]
    REQUIRE_SPACE = "--require-space" in sys.argv
    path, prefix, fn, cat_out = args[:4]
    existing = read_catalog(args[4]) if len(args) > 4 else None
    src = open(path, encoding="utf-8").read()
    cat = Catalog(prefix, existing)
    new = convert(src, cat, fn)
    open(path, "w", encoding="utf-8").write(new)
    with open(cat_out, "a", encoding="utf-8") as f:
        for k, v in cat.entries:
            f.write(f"{k} = {escape_value(v)}\n")
    print(f"{path}: {len(cat.entries)} textos nuevos")


if __name__ == "__main__":
    main()
