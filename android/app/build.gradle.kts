import java.io.ByteArrayOutputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.rutabikersync.app"
    compileSdk = 35

    val gitCommit: String = runCatching {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "rev-parse", "--short", "HEAD")
            standardOutput = stdout
        }
        stdout.toString().trim()
    }.getOrDefault("unknown")

    val gitDirty: Boolean = runCatching {
        val stdout = ByteArrayOutputStream()
        project.exec {
            commandLine("git", "status", "--porcelain")
            standardOutput = stdout
        }
        stdout.toString().trim().isNotEmpty()
    }.getOrDefault(false)

    val localProperties = Properties().apply {
        val localPropertiesFile = rootProject.file("local.properties")
        if (localPropertiesFile.exists()) {
            load(localPropertiesFile.inputStream())
        }
    }
    val mapboxToken = localProperties.getProperty("mapbox.token") ?: System.getenv("MAPBOX_TOKEN") ?: ""

    defaultConfig {
        applicationId = "com.rutabikersync.app"
        minSdk = 26          // Android 8.0; BLE extendido requiere 26+ (se comprueba en ejecución)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
        buildConfigField("boolean", "GIT_DIRTY", "$gitDirty")
        buildConfigField("boolean", "UX_TEST_MODE", "false")
        buildConfigField("String", "MAPBOX_ACCESS_TOKEN", "\"$mapboxToken\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            buildConfigField("boolean", "FIELD_TEST_MODE", "false")
            buildConfigField("boolean", "UX_TEST_MODE", "false")
        }
        create("uxTest") {
            dimension = "mode"
            applicationIdSuffix = ".uxtest"
            buildConfigField("boolean", "FIELD_TEST_MODE", "false")
            buildConfigField("boolean", "UX_TEST_MODE", "true")
            signingConfig = signingConfigs.getByName("debug")
        }
        create("fieldTest") {
            dimension = "mode"
            applicationIdSuffix = ".fieldtest"
            buildConfigField("boolean", "FIELD_TEST_MODE", "true")
            buildConfigField("boolean", "UX_TEST_MODE", "false")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "true")
        }
    }

    sourceSets {
        getByName("uxTest") {
            java.srcDirs("src/standard/java")
        }
    }

    applicationVariants.all {
        if (flavorName == "uxTest") {
            buildConfigField("boolean", "DEV_PANEL_ENABLED", "false")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "true")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_PANEL_ENABLED", "true")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "true")
            // T-02 (B1-4): localhost solo en debug (overlay cleartext habilitado en src/debug).
            buildConfigField("String", "BASE_URL", "\"http://127.0.0.1:3000\"")
        }
        release {
            isMinifyEnabled = true       // ofuscación R8 (app de código cerrado)
            isShrinkResources = true
            buildConfigField("boolean", "DEV_PANEL_ENABLED", "false")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "false")
            // T-02 (B1-4): HTTPS obligatorio en release. TODO_EXTERNAL:URL_PROD - reemplazar por endpoint real prod/staging.
            buildConfigField("String", "BASE_URL", "\"https://TODO_EXTERNAL_URL_PROD.example.invalid\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    beforeVariants { variantBuilder ->
        // Deshabilitar uxTestRelease para que uxTest con ALLOW_DEFAULT_TEST_RIDE_KEY=true solo exista en Debug
        if (variantBuilder.productFlavors.any { it.second == "uxTest" } && variantBuilder.buildType == "release") {
            variantBuilder.enable = false
        }
    }
}

val verifyStandardReleaseSecurity = tasks.register("verifyStandardReleaseSecurity") {
    group = "verification"
    description = "Garantiza que standardRelease tiene ALLOW_DEFAULT_TEST_RIDE_KEY=false y DEV_PANEL_ENABLED=false"
    dependsOn("generateStandardReleaseBuildConfig")
    doLast {
        val buildConfigFile = file("build/generated/source/buildConfig/standard/release/com/rutabikersync/app/BuildConfig.java")
        check(buildConfigFile.exists()) { "BuildConfig.java para standardRelease no fue generado en ${buildConfigFile.absolutePath}" }
        val text = buildConfigFile.readText()
        check(text.contains("boolean ALLOW_DEFAULT_TEST_RIDE_KEY = false;")) {
            "FALLO DE SEGURIDAD: ALLOW_DEFAULT_TEST_RIDE_KEY debe ser false en standardRelease"
        }
        check(text.contains("boolean DEV_PANEL_ENABLED = false;")) {
            "FALLO DE SEGURIDAD: DEV_PANEL_ENABLED debe ser false en standardRelease"
        }
        logger.lifecycle("✓ Verificación de seguridad standardRelease superada: ALLOW_DEFAULT_TEST_RIDE_KEY=false y DEV_PANEL_ENABLED=false")
    }
}

// Enlazar como dependencia obligatoria para que nunca se ensamble ni se genere bundle release sin la verificación
tasks.matching { it.name in listOf("assembleStandardRelease", "bundleStandardRelease") }.configureEach {
    dependsOn(verifyStandardReleaseSecurity)
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.work)

    implementation(libs.play.billing)
    implementation(libs.play.nearby)
    implementation(libs.play.integrity)
    implementation(libs.maplibre)
    implementation(libs.tink)
    implementation(libs.concentus)
    // 5.2: Autenticación biométrica (Fase 5 — VaultBiometricGate)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    // 6.0: Google Sign-In via Credential Manager
    implementation(libs.credentials)
    implementation(libs.credentials.play.services)
    implementation(libs.google.id)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // 7.1.2: Notificaciones Push FCM
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303")
    androidTestImplementation(libs.androidx.test.ext)
}
