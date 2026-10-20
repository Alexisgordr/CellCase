#!/usr/bin/env python3
"""
CellCase · desglose del tamaño de una APK y comparación entre dos (docs/apk-size.md). Solo biblioteca estándar.

    python3 tools/apk_size.py APK                    desglose de una APK
    python3 tools/apk_size.py ANTES.apk DESPUES.apk  comparación por categorías

Mide el tamaño del ARCHIVO (comprimido) y el descomprimido de cada categoría. El espacio instalado en el
teléfono es otra cosa (APK + código compilado por ART + datos): se mira en Ajustes › Aplicaciones › CellCase.
El reparto del código por paquete es aproximado: cuenta las instrucciones y cabeceras de cada método de cada
clase (no las tablas de cadenas y tipos que comparten todas las clases).
"""
import collections
import struct
import sys
import zipfile


def uleb(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        s += 7
        if x < 0x80:
            return r, o


def dex_classes(b):
    """(nombre de clase, bytes de código) de cada clase de un DEX."""
    string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from('<IIII', b, 56)
    class_defs_size, class_defs_off = struct.unpack_from('<II', b, 96)

    def string(i):
        off = struct.unpack_from('<I', b, string_ids_off + 4 * i)[0]
        _, o = uleb(b, off)
        return b[o:b.index(0, o)].decode('utf-8', 'replace')

    def typ(i):
        return string(struct.unpack_from('<I', b, type_ids_off + 4 * i)[0])

    out = []
    for k in range(class_defs_size):
        cls, _, _, _, _, _, cdata, _ = struct.unpack_from('<8I', b, class_defs_off + 32 * k)
        size = 0
        if cdata:
            o = cdata
            sf, o = uleb(b, o)
            inf, o = uleb(b, o)
            dm, o = uleb(b, o)
            vm, o = uleb(b, o)
            for _ in range(sf + inf):
                _, o = uleb(b, o)
                _, o = uleb(b, o)
            for _ in range(dm + vm):
                _, o = uleb(b, o)
                _, o = uleb(b, o)
                code, o = uleb(b, o)
                if code:
                    _, _, _, tries, _, n = struct.unpack_from('<HHHHII', b, code)
                    size += 16 + 2 * n + tries * 8
        out.append((typ(cls), size))
    return out


def arsc_locales(b):
    """Idiomas (configuraciones) presentes en resources.arsc."""
    locs = set()
    o = struct.unpack_from('<HHI', b, 0)[1]  # cabecera de ResTable
    end = len(b)
    stack = [(o, end)]
    while stack:
        o, end = stack.pop()
        while o + 8 <= end:
            ctype, hsize, csize = struct.unpack_from('<HHI', b, o)
            if csize == 0:
                break
            if ctype == 0x0200:  # paquete: sus hijos empiezan tras la cabecera
                stack.append((o + hsize, o + csize))
            elif ctype == 0x0201:  # tipo: ResTable_config en la cabecera
                cfg = o + 20
                lang = b[cfg + 8:cfg + 10]
                country = b[cfg + 10:cfg + 12]
                if lang != b'\x00\x00':
                    l = lang.decode('latin-1') if lang[0] < 0x80 else 'packed'
                    c = country.decode('latin-1') if country != b'\x00\x00' and country[0] < 0x80 else ''
                    locs.add(l + ('-' + c if c else ''))
            o += csize
    return sorted(locs)


def category(n):
    if n.endswith('.dex'):
        return 'Código (DEX)'
    if n.startswith('lib/'):
        return 'Nativo ' + n.split('/')[1]
    if n == 'resources.arsc':
        return 'resources.arsc'
    if n.startswith('res/'):
        return 'Recursos res/ (imágenes, XML)'
    if n.startswith('assets/'):
        return 'assets/ (' + '/'.join(n.split('/')[1:2]) + ')'
    if n.startswith('cellcase/'):
        return 'Catálogos de textos (cellcase/i18n)'
    if n.startswith('META-INF/'):
        return 'META-INF (licencias, servicios, firma)'
    if n.startswith('kotlin/'):
        return 'Metadatos kotlin/*.kotlin_builtins'
    if n == 'AndroidManifest.xml':
        return 'AndroidManifest.xml'
    return 'Otros: ' + n


def analyse(path):
    z = zipfile.ZipFile(path)
    cats = collections.defaultdict(lambda: [0, 0, 0])
    for i in z.infolist():
        c = cats[category(i.filename)]
        c[0] += i.compress_size
        c[1] += i.file_size
        c[2] += 1
    pk = collections.Counter()
    classes = 0
    for n in z.namelist():
        if n.endswith('.dex'):
            for name, size in dex_classes(z.read(n)):
                p = name[1:].split(';')[0].split('/')[:-1]
                pk['.'.join(p[:3]) or '(raíz)'] += size
                classes += 1
    locales = arsc_locales(z.read('resources.arsc')) if 'resources.arsc' in z.namelist() else []
    import os
    return {'file': os.path.getsize(path), 'cats': cats, 'pkgs': pk, 'classes': classes, 'locales': locales,
            'entries': len(z.infolist()), 'dex': sorted(n for n in z.namelist() if n.endswith('.dex')),
            'test': [n for n in z.namelist() if any(t in n.lower() for t in ('synthetic', 'sample', '/test', 'junit', '.pdf'))]}


def kb(v):
    return f'{v / 1024:9.1f} KiB'


def report(path, a):
    print(f'\n# {path}\nArchivo: {a["file"] / 1048576:.2f} MiB ({a["file"]:,} bytes) · {a["entries"]} entradas · {a["classes"]} clases en {len(a["dex"])} DEX')
    print('\nCategoría                                       comprimido   descomprimido  entradas')
    for k, (c, u, n) in sorted(a['cats'].items(), key=lambda x: -x[1][0]):
        print(f'{k:45s} {kb(c)}   {kb(u)}  {n:6d}')
    print('\nCódigo por paquete (aprox., bytes de métodos):')
    for k, v in a['pkgs'].most_common(15):
        print(f'  {kb(v)}  {k}')
    print(f'\nIdiomas en resources.arsc ({len(a["locales"])}): {", ".join(a["locales"]) or "ninguno"}')
    print('Muestras, informes o archivos de prueba en la APK: ' + (', '.join(a['test']) if a['test'] else 'ninguno'))


def compare(pa, a, pb, b):
    print(f'\n# Comparación\n  antes:   {pa}  {a["file"] / 1048576:.2f} MiB\n  después: {pb}  {b["file"] / 1048576:.2f} MiB')
    d = b['file'] - a['file']
    print(f'  diferencia del archivo: {d / 1048576:+.2f} MiB ({100 * d / a["file"]:+.1f} %)\n')
    print('Categoría                                       antes (comp.)  después (comp.)   diferencia')
    for k in sorted(set(a['cats']) | set(b['cats']), key=lambda k: -a['cats'].get(k, [0])[0]):
        x = a['cats'].get(k, [0, 0, 0])[0]
        y = b['cats'].get(k, [0, 0, 0])[0]
        print(f'{k:45s} {kb(x)}   {kb(y)}   {kb(y - x)}')
    print(f'\nClases: {a["classes"]} → {b["classes"]} · idiomas en resources.arsc: {len(a["locales"])} → {len(b["locales"])}')


if __name__ == '__main__':
    if len(sys.argv) not in (2, 3):
        print(__doc__)
        sys.exit(2)
    res = [analyse(p) for p in sys.argv[1:]]
    for p, r in zip(sys.argv[1:], res):
        report(p, r)
    if len(res) == 2:
        compare(sys.argv[1], res[0], sys.argv[2], res[1])
