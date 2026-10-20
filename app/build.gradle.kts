plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

/** `./gradlew :app:assembleRelease -Pcellcase.sinOptimizar` compila la release sin R8 (comparación de tamaños). */
val sinOptimizar = providers.gradleProperty("cellcase.sinOptimizar").isPresent
// Raíz del repositorio como texto fijo (ver core/build.gradle.kts): compatible con la caché de configuración.
val cellcaseRoot = rootProject.layout.projectDirectory.asFile.absolutePath

android {
    namespace = "com.alexisgordr.cellcase"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.alexisgordr.cellcase"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Las pruebas instrumentadas usan las muestras sintéticas de ICdetector 3.0 como recursos (no van en la APK).
    sourceSets["androidTest"].assets.directories.add("../samples/synthetic/icd30")

    testOptions {
        unitTests.all { it.systemProperty("cellcase.root", cellcaseRoot) }
    }

    buildTypes {
        release {
            // Tamaño (D-049, docs/apk-size.md): R8 elimina y optimiza el código no usado de Compose, AppCompat,
            // corrutinas y Kotlin; isShrinkResources quita los recursos de las bibliotecas que nada referencia.
            // Con -Pcellcase.sinOptimizar se compila la misma release sin optimizar, solo para comparar tamaños.
            isMinifyEnabled = !sinOptimizar
            isShrinkResources = !sinOptimizar
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    androidResources {
        // La interfaz existe en inglés (predeterminado) y español (D-045). Las bibliotecas traen traducciones de
        // sus propios textos en unas 80 lenguas que CellCase nunca muestra: se conservan solo en y es. Las variantes
        // regionales de las bibliotecas (es-US…) también se quitan; Android usa entonces «es» o el predeterminado.
        // No cambia los idiomas ni la accesibilidad de la app (medido: 84 → 1 idioma con calificador, «es»).
        localeFilters += listOf("en", "es")
    }

    packaging {
        resources {
            // Metadatos que no se usan en ejecución: agente de depuración de corrutinas, descripciones de
            // kotlin-reflect (CellCase no usa reflexión de Kotlin) y archivos de versión de AndroidX.
            // Las licencias (META-INF/**/LICENSE*) se conservan.
            excludes += listOf("DebugProbesKt.bin", "kotlin/**.kotlin_builtins", "META-INF/*.version")
        }
        jniLibs {
            // La única librería nativa (libandroidx.graphics.path.so, de Compose) ya viene sin símbolos de depuración.
            // Sin esto, Gradle intenta quitárselos con el NDK y, si no está instalado, avisa en cada compilación.
            // Declararla aquí deja la APK idéntica y quita el aviso; no hace falta instalar el NDK.
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
        }
    }

    lint {
        // UseKtx propone androidx.core:core-ktx (SQLiteDatabase.transaction {}) en vez de
        // beginTransaction/setTransactionSuccessful/endTransaction. Las transacciones de CaseStore ya son correctas
        // y añadir core-ktx solo para eso engorda la APK y la lista de licencias. Es un aviso de productividad, no de corrección.
        disable += "UseKtx"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // F-Droid: sin bloque de metadatos de dependencias (cifrado para Google) en la APK ni en el bundle,
    // para que la compilación sea reproducible y verificable.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.appcompat)

    testImplementation(libs.junit)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
