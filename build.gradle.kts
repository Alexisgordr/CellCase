// AGP 9 trae Kotlin integrado (sin el plugin kotlin-android). Declarar aquí el plugin de Kotlin JVM
// fija la versión de KGP del proyecto (2.4.10), la misma que el plugin del compilador de Compose.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.compose.compiler) apply false
}
