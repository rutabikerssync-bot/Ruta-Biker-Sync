plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.rutabikersync.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rutabikersync.app"
        minSdk = 26          // Android 8.0; BLE extendido requiere 26+ (se comprueba en ejecución)
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    flavorDimensions += "mode"
    productFlavors {
        create("standard") {
            dimension = "mode"
            buildConfigField("boolean", "FIELD_TEST_MODE", "false")
        }
        create("fieldTest") {
            dimension = "mode"
            applicationIdSuffix = ".fieldtest"
            buildConfigField("boolean", "FIELD_TEST_MODE", "true")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "true")
        }
    }

    buildTypes {
        debug {
            buildConfigField("boolean", "DEV_PANEL_ENABLED", "true")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "true")
        }
        release {
            isMinifyEnabled = true       // ofuscación R8 (app de código cerrado)
            isShrinkResources = true
            buildConfigField("boolean", "DEV_PANEL_ENABLED", "false")
            buildConfigField("boolean", "ALLOW_DEFAULT_TEST_RIDE_KEY", "false")
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
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext)
}
