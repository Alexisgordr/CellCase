#!/usr/bin/env bash
# Compila y ejecuta en la JVM la prueba de recursos de la interfaz (app/src/test/.../StringResourcesTest.kt)
# sin Gradle ni SDK de Android: solo lee los XML. Uso: tools/local/check-app-resources.sh [GRADLE_HOME]
set -euo pipefail
GH="${1:-/opt/gradle}"; L="$GH/lib"; ROOT="$(cd "$(dirname "$0")/../.." && pwd)"; O="$ROOT/build-local/apptest"
jar() { ls "$L"/$1 2>/dev/null | head -1; }
STD="$(jar 'kotlin-stdlib-[0-9]*.jar')"; JUNIT="$(jar 'junit-4*.jar'):$(jar 'hamcrest-core-*.jar')"
CP="$(jar 'kotlin-compiler-embeddable-*.jar'):$STD:$(jar 'kotlinx-coroutines-core-jvm-*.jar'):$(jar 'kotlin-script-runtime-*.jar'):$(jar 'kotlin-reflect-*.jar'):$(jar 'kotlin-daemon-embeddable-*.jar'):$(jar 'annotations-*.jar'):$(jar 'trove4j-*.jar')"
rm -rf "$O"; mkdir -p "$O"
java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect -jvm-target 17 -cp "$STD:$JUNIT" -d "$O" "$ROOT/app/src/test/kotlin/com/alexisgordr/cellcase/StringResourcesTest.kt"
java -cp "$STD:$JUNIT:$O" -Dcellcase.root="$ROOT" org.junit.runner.JUnitCore com.alexisgordr.cellcase.StringResourcesTest
