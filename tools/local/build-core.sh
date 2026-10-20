#!/usr/bin/env bash
# Compila y prueba el módulo :core SIN Gradle ni repositorios remotos, usando el compilador de Kotlin
# y JUnit 4 que trae una instalación local de Gradle (aquí /opt/gradle). Pensado para entornos sin
# acceso a Maven Central / Google Maven. En un entorno normal usa ./gradlew :core:test.
#
# Uso: tools/local/build-core.sh [GRADLE_HOME]
set -euo pipefail
GH="${1:-/opt/gradle}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/build-local"
LIB="$GH/lib"
jar() { ls "$1"/$2 2>/dev/null | head -1; }

STDLIB="$(jar "$LIB" 'kotlin-stdlib-[0-9]*.jar')"
COMPILER_CP="$(jar "$LIB" 'kotlin-compiler-embeddable-*.jar'):$STDLIB:$(jar "$LIB" 'kotlin-script-runtime-*.jar'):$(jar "$LIB" 'kotlin-reflect-*.jar'):$(jar "$LIB" 'kotlin-daemon-embeddable-*.jar'):$(jar "$LIB" 'annotations-*.jar'):$(jar "$LIB" 'kotlinx-coroutines-core-jvm-*.jar'):$(jar "$LIB" 'trove4j-*.jar')"
JUNIT="$(jar "$LIB" 'junit-4*.jar'):$(jar "$LIB" 'hamcrest-core-*.jar')"
COROUTINES="$(jar "$LIB" 'kotlinx-coroutines-core-jvm-*.jar')"

kotlinc() { java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 "$@"; }

rm -rf "$OUT"; mkdir -p "$OUT/main" "$OUT/test"
echo "== Compilando core/src/main"
kotlinc -cp "$STDLIB" -d "$OUT/main" $(find "$ROOT/core/src/main/kotlin" -name '*.kt')
echo "== Compilando core/src/test"
kotlinc -cp "$STDLIB:$OUT/main:$JUNIT" -d "$OUT/test" $(find "$ROOT/core/src/test/kotlin" -name '*.kt')
cp -r "$ROOT/core/src/test/resources/." "$OUT/test/" 2>/dev/null || true
cp -r "$ROOT/core/src/main/resources/." "$OUT/main/" 2>/dev/null || true
echo "== Ejecutando pruebas"
CLASSES=$(cd "$OUT/test" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
java -cp "$STDLIB:$OUT/main:$OUT/test:$JUNIT" -Dcellcase.root="$ROOT" org.junit.runner.JUnitCore $CLASSES
echo "== Pruebas de JVM de la app (recursos de texto; no necesitan Android)"
mkdir -p "$OUT/apptest"
# La lógica de la app sin Android (app/src/main/java/.../logic) se compila con el núcleo y se prueba aquí.
kotlinc -cp "$STDLIB:$OUT/main:$JUNIT:$COROUTINES" -d "$OUT/apptest" $(find "$ROOT/app/src/main/java/com/alexisgordr/cellcase/logic" -name '*.kt') $(find "$ROOT/app/src/test" -name '*.kt')
APPCLASSES=$(cd "$OUT/apptest" && find . -name '*Test.class' | sed 's#^\./##; s#\.class$##; s#/#.#g' | sort)
java -cp "$STDLIB:$OUT/main:$OUT/apptest:$JUNIT:$COROUTINES" -Dcellcase.root="$ROOT" org.junit.runner.JUnitCore $APPCLASSES
echo "== Generando PDF de muestra sintéticos"
GEN() { java -cp "$STDLIB:$OUT/main:$OUT/test:$JUNIT" -Dcellcase.root="$ROOT" com.alexisgordr.cellcase.core.GenerateSampleReport "$@"; }
GEN "$ROOT/samples/reports/SYNTHETIC_patrones_explicado.pdf" PATRONES --mode=EXPLAINED --coords=ROUND_3
GEN "$ROOT/samples/reports/SYNTHETIC_patrones_completo.pdf" PATRONES --mode=FULL --coords=ROUND_3
GEN "$ROOT/samples/reports/SYNTHETIC_patterns_plain_en.pdf" PATRONES --mode=EXPLAINED --coords=ROUND_3 --lang=en
GEN "$ROOT/samples/reports/SYNTHETIC_patterns_full_en.pdf" PATRONES --mode=FULL --coords=ROUND_3 --lang=en
GEN "$ROOT/samples/reports/SYNTHETIC_copia30_explicado.pdf" COPIA30 --mode=EXPLAINED --coords=ROUND_3
GEN "$ROOT/samples/reports/SYNTHETIC_copia30_completo.pdf" COPIA30 --mode=FULL --coords=ROUND_3
GEN "$ROOT/samples/reports/SYNTHETIC_backup30_plain_en.pdf" COPIA30 --mode=EXPLAINED --coords=ROUND_3 --lang=en
GEN "$ROOT/samples/reports/SYNTHETIC_backup30_full_en.pdf" COPIA30 --mode=FULL --coords=ROUND_3 --lang=en
GEN "$ROOT/samples/reports/SYNTHETIC_informe_expediente.pdf" --mode=FULL
GEN "$ROOT/samples/reports/SYNTHETIC_informe_casos_limite.pdf" SYNTHETIC_icdetector_history_edge_cases.csv --masked --coords=ROUND_3 --mode=EXPLAINED
