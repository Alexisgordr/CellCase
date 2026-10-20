// Núcleo de CellCase en Kotlin/JVM puro: sin dependencias de Android (D-023).
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    // Bytecode Java 17 con cualquier JDK ≥ 17 instalado (sin descargar toolchains).
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation(libs.junit)
}

// Raíz del repositorio como texto fijo: la caché de configuración no permite usar `rootProject` dentro de una tarea.
val cellcaseRoot = rootProject.layout.projectDirectory.asFile.absolutePath

tasks.test {
    systemProperty("cellcase.root", cellcaseRoot)
}

// Genera samples/reports/SYNTHETIC_informe_expediente.pdf con el mismo código que la app.
tasks.register<JavaExec>("sampleReport") {
    group = "verification"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.alexisgordr.cellcase.core.GenerateSampleReport")
    systemProperty("cellcase.root", cellcaseRoot)
    args("$cellcaseRoot/samples/reports/SYNTHETIC_informe_expediente.pdf")
}
