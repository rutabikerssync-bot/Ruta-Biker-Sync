#!/usr/bin/env bash
# =============================================================================
# Ruta Biker Sync — setup.sh
# Crea la estructura del proyecto, instala herramientas y deja los esqueletos
# de Android (Kotlin + Compose) y del backend (Node + TypeScript + PostgreSQL).
#
# Uso:
#   ./setup.sh [opciones]
#
# Opciones:
#   --dir <ruta>        Carpeta del proyecto (por defecto ./ruta-biker-sync)
#   --dry-run           Muestra lo que haría sin cambiar nada
#   --no-install        Solo crea carpetas y archivos (no instala nada)
#   --skip-system       No instala paquetes del sistema (git, JDK, Node)
#   --skip-android-sdk  No descarga el Android SDK
#   --skip-npm          No ejecuta npm install en el backend
#   --with-brouter      Clona BRouter (ruteo offline) en tools/brouter
#   --force             Sobrescribe archivos que ya existan
#   -h, --help          Muestra esta ayuda
#
# Funciona en Linux (Debian/Ubuntu con apt) y macOS (con Homebrew).
# En Windows: usar WSL2 con Ubuntu.
# =============================================================================
set -euo pipefail

# ---------- Opciones ----------
PROJECT_DIR="./ruta-biker-sync"
DRY=false
NO_INSTALL=false
SKIP_SYSTEM=false
SKIP_SDK=false
SKIP_NPM=false
WITH_BROUTER=false
FORCE=false
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

usage() { sed -n '2,24p' "$0" | sed 's/^# \{0,1\}//'; }

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dir) PROJECT_DIR="${2:?Falta la ruta}"; shift 2 ;;
    --dry-run) DRY=true; shift ;;
    --no-install) NO_INSTALL=true; shift ;;
    --skip-system) SKIP_SYSTEM=true; shift ;;
    --skip-android-sdk) SKIP_SDK=true; shift ;;
    --skip-npm) SKIP_NPM=true; shift ;;
    --with-brouter) WITH_BROUTER=true; shift ;;
    --force) FORCE=true; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Opción desconocida: $1"; usage; exit 1 ;;
  esac
done

if $NO_INSTALL; then SKIP_SYSTEM=true; SKIP_SDK=true; SKIP_NPM=true; fi

# ---------- Utilidades ----------
c_green=$'\033[32m'; c_yellow=$'\033[33m'; c_red=$'\033[31m'; c_off=$'\033[0m'
log()  { echo "${c_green}==>${c_off} $*"; }
warn() { echo "${c_yellow}[aviso]${c_off} $*"; }
err()  { echo "${c_red}[error]${c_off} $*" >&2; }

run() {
  if $DRY; then echo "[dry-run] $*"; else "$@"; fi
}

# Escribe stdin en un archivo (no sobrescribe salvo --force)
write_file() {
  local f="$1"
  if $DRY; then echo "[dry-run] crear $f"; cat >/dev/null; return; fi
  if [[ -e "$f" && "$FORCE" != true ]]; then cat >/dev/null; return; fi
  mkdir -p "$(dirname "$f")"
  cat > "$f"
}

have() { command -v "$1" >/dev/null 2>&1; }

OS="$(uname -s)"
case "$OS" in
  Linux)  PLATFORM="linux" ;;
  Darwin) PLATFORM="mac" ;;
  *) err "Sistema no soportado: $OS (usa WSL2 en Windows)"; exit 1 ;;
esac

if [[ "$PLATFORM" == "linux" ]]; then
  ANDROID_HOME_DEFAULT="$HOME/Android/Sdk"
else
  ANDROID_HOME_DEFAULT="$HOME/Library/Android/sdk"
fi
ANDROID_HOME="${ANDROID_HOME:-$ANDROID_HOME_DEFAULT}"

# Paquete base de la app y versiones de Android
APP_ID="com.rutabikersync.app"
APP_PATH="com/rutabikersync/app"
COMPILE_SDK=35
BUILD_TOOLS="35.0.0"
CMDLINE_TOOLS_REV="11076708"   # si la descarga falla, actualiza este número desde developer.android.com/studio
GRADLE_VERSION="8.10.2"

# ---------- 1. Herramientas del sistema ----------
install_system_tools() {
  log "Revisando herramientas del sistema..."
  local missing=()
  have git   || missing+=(git)
  have curl  || missing+=(curl)
  have unzip || missing+=(unzip)
  have java  || missing+=(jdk)

  if [[ ${#missing[@]} -gt 0 ]]; then
    log "Faltan: ${missing[*]}"
    if [[ "$PLATFORM" == "linux" ]] && have apt-get; then
      run sudo apt-get update
      run sudo apt-get install -y git curl unzip zip openjdk-17-jdk
    elif [[ "$PLATFORM" == "mac" ]] && have brew; then
      run brew install git curl unzip openjdk@17
      warn "En macOS, enlaza el JDK: sudo ln -sfn \$(brew --prefix openjdk@17)/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-17.jdk"
    else
      warn "No encontré apt-get ni brew. Instala manualmente: git, curl, unzip y JDK 17."
    fi
  else
    log "git, curl, unzip y Java ya están instalados."
  fi

  # Versión de Java
  if have java; then
    local jv
    # Se ignoran líneas como "Picked up JAVA_TOOL_OPTIONS..." que algunos entornos imprimen antes
    jv="$(java -version 2>&1 | grep -m1 ' version ' || true)"
    log "Java detectado: ${jv:-versión desconocida}"
  fi

  # Node.js >= 20
  local need_node=true
  if have node; then
    local major
    major="$(node -p 'process.versions.node.split(".")[0]')"
    if [[ "$major" -ge 20 ]]; then need_node=false; log "Node $(node -v) OK."; fi
  fi
  if $need_node; then
    log "Instalando Node.js LTS con nvm..."
    run bash -c 'curl -fsSL https://raw.githubusercontent.com/nvm-sh/nvm/v0.40.1/install.sh | bash'
    if ! $DRY; then
      export NVM_DIR="${NVM_DIR:-$HOME/.nvm}"
      # shellcheck disable=SC1091
      [[ -s "$NVM_DIR/nvm.sh" ]] && . "$NVM_DIR/nvm.sh"
      have nvm && nvm install --lts || warn "Abre una terminal nueva y ejecuta: nvm install --lts"
    fi
  fi

  # Docker (solo se comprueba; no se instala automáticamente)
  if have docker; then
    log "Docker detectado."
  else
    warn "Docker no está instalado. Lo necesitas para PostgreSQL local (docker compose). Instálalo desde docs.docker.com/get-docker o usa un PostgreSQL propio."
  fi
}

# ---------- 2. Android SDK (línea de comandos) ----------
install_android_sdk() {
  if ! have java; then warn "Java no está disponible; se omite el Android SDK."; return; fi
  local sdkmanager="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

  if [[ ! -x "$sdkmanager" ]]; then
    log "Descargando herramientas de línea de comandos de Android..."
    local zip="commandlinetools-${PLATFORM}-${CMDLINE_TOOLS_REV}_latest.zip"
    local url="https://dl.google.com/android/repository/${zip}"
    local tmp
    tmp="$(mktemp -d)"
    run mkdir -p "$ANDROID_HOME/cmdline-tools"
    run curl -fL "$url" -o "$tmp/$zip"
    run unzip -q "$tmp/$zip" -d "$tmp"
    if ! $DRY; then
      rm -rf "$ANDROID_HOME/cmdline-tools/latest"
      mv "$tmp/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
    fi
  else
    log "Herramientas de línea de comandos de Android ya instaladas."
  fi

  if ! $DRY; then
    log "Aceptando licencias e instalando paquetes del SDK..."
    set +o pipefail
    yes | "$sdkmanager" --licenses >/dev/null 2>&1 || true
    set -o pipefail
    "$sdkmanager" "platform-tools" "platforms;android-${COMPILE_SDK}" "build-tools;${BUILD_TOOLS}"
  else
    echo "[dry-run] sdkmanager platform-tools platforms;android-${COMPILE_SDK} build-tools;${BUILD_TOOLS}"
  fi
}

# ---------- 3. Estructura de carpetas ----------
create_structure() {
  log "Creando estructura de carpetas en $PROJECT_DIR ..."
  local root="$PROJECT_DIR"
  local a="$root/android/app/src/main/java/$APP_PATH"

  local dirs=(
    # Documentación y gobernanza
    "$root/docs"
    # --- Android: núcleo ---
    "$a/core/transport"        # Transport (interfaz), BleAdvTransport, NearbyTransport
    "$a/core/protocol"         # mensajes, TTL, prioridad, deduplicación
    "$a/core/mesh"             # reenvío, heartbeat, anunciador único
    "$a/core/crypto"           # credenciales, firmas, cifrado sellado (Tink)
    "$a/core/location"         # GPS adaptativo, servicio en primer plano
    "$a/core/audio"            # clips Opus, cola de reproducción, TTS
    "$a/core/db"               # Room (DAOs, entidades)
    "$a/core/network"          # cliente HTTP al backend
    "$a/core/util"
    # --- Android: funciones ---
    "$a/feature/auth"
    "$a/feature/groups"        # grupo, líderes, cargos, estatus, semáforo
    "$a/feature/invitations"
    "$a/feature/rides"         # rodadas, manifiesto, roles
    "$a/feature/map"           # MapLibre, avatares en vivo
    "$a/feature/routes"        # BRouter, GPX, corredor offline, desvío
    "$a/feature/voice"         # PTT por clips
    "$a/feature/sos"           # SOS, caída, paquete de emergencia
    "$a/feature/profile"       # avatar, ficha de emergencia
    "$a/feature/vault"         # bóveda de documentos y alarmas
    "$a/feature/badges"        # rangos, logros, honor
    "$a/feature/billing"       # Play Billing, suscripciones
    "$a/feature/moderation"
    "$a/feature/onboarding"
    "$a/ui/theme"
    "$a/ui/components"
    "$a/ui/navigation"
    "$root/android/app/src/main/res/values"
    "$root/android/app/src/main/res/values-en"
    "$root/android/app/src/main/res/values-pt"
    "$root/android/app/src/main/assets/tiles"
    "$root/android/app/src/test/java/$APP_PATH"
    "$root/android/app/src/androidTest/java/$APP_PATH"
    "$root/android/gradle"
    # --- Backend ---
    "$root/backend/src/config"
    "$root/backend/src/lib"
    "$root/backend/src/db/migrations"
    "$root/backend/src/jobs"
    "$root/backend/src/modules/auth"
    "$root/backend/src/modules/subscriptions"
    "$root/backend/src/modules/play"
    "$root/backend/src/modules/groups"
    "$root/backend/src/modules/invitations"
    "$root/backend/src/modules/rides"
    "$root/backend/src/modules/credentials"
    "$root/backend/src/modules/consents"
    "$root/backend/src/modules/moderation"
    "$root/backend/src/modules/badges"
    "$root/backend/src/modules/transfers"
    "$root/backend/test"
    # --- Infra y herramientas ---
    "$root/infra"
    "$root/tools"
    "$root/field-tests"        # registros de pruebas de campo BLE
    "$root/.github/workflows"
  )

  local d
  for d in "${dirs[@]}"; do
    run mkdir -p "$d"
    if ! $DRY; then
      # .gitkeep solo en carpetas hoja vacías (se limpia después si se añaden archivos)
      : > "$d/.gitkeep"
    fi
  done

  # Copiar los documentos de gobernanza si están junto al script
  local f
  for f in PROJECT BUSINESS_MODEL DESIGN MEMORY ROADMAP RULES TASKS CHECKLIST BACKEND_LICENSING; do
    if [[ -f "$SCRIPT_DIR/$f.md" && ! -f "$root/docs/$f.md" ]]; then
      run cp "$SCRIPT_DIR/$f.md" "$root/docs/$f.md"
    fi
  done
}

# ---------- 4. Archivos raíz ----------
create_root_files() {
  local root="$PROJECT_DIR"

  write_file "$root/.gitignore" <<'EOF'
# Android
*.iml
.gradle/
build/
local.properties
.idea/
captures/
.externalNativeBuild/
.cxx/
*.apk
*.aab
*.keystore
*.jks
keystore.properties
google-services.json

# Node
node_modules/
dist/
coverage/

# Entorno y secretos
.env
.env.*
!.env.example
*.pem
signing-keys/

# Sistema
.DS_Store
Thumbs.db

# Herramientas descargadas
tools/brouter/
tools/gradle-*/
EOF

  write_file "$root/README.md" <<'EOF'
# Ruta Biker Sync

App Android para grupos de moteros con mesh Bluetooth, mapa offline, voz por clips y suscripciones.

- `android/`  — app Kotlin + Jetpack Compose
- `backend/`  — API de licencias y cobros (Node + TypeScript + PostgreSQL)
- `infra/`    — docker-compose y despliegue
- `docs/`     — documentos de gobernanza (RULES.md es la fuente de verdad)
- `field-tests/` — resultados de pruebas de campo BLE
- `tools/`    — utilidades (BRouter opcional)

## Primeros pasos
1. `cd infra && docker compose up -d` (PostgreSQL local)
2. `cd backend && cp .env.example .env && npm run dev`
3. Abrir `android/` en Android Studio, o `cd android && ./gradlew assembleDebug`

Licencia: código cerrado, todos los derechos reservados.
EOF

  write_file "$root/infra/docker-compose.yml" <<'EOF'
services:
  postgres:
    image: postgres:16
    restart: unless-stopped
    environment:
      POSTGRES_USER: rbs
      POSTGRES_PASSWORD: rbs_dev_password
      POSTGRES_DB: rbs
    ports:
      - "5432:5432"
    volumes:
      - rbs_pgdata:/var/lib/postgresql/data
volumes:
  rbs_pgdata:
EOF

  write_file "$root/.github/workflows/ci.yml" <<'EOF'
name: CI
on:
  push:
  pull_request:

jobs:
  backend:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: backend
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-node@v4
        with:
          node-version: 22
          cache: npm
          cache-dependency-path: backend/package-lock.json
      - run: npm ci
      - run: npm run typecheck
      - run: npm test --if-present

  android:
    runs-on: ubuntu-latest
    defaults:
      run:
        working-directory: android
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17
      - uses: gradle/actions/setup-gradle@v4
      - run: ./gradlew assembleDebug testDebugUnitTest
EOF
}

# ---------- 5. Esqueleto Android ----------
create_android_files() {
  local an="$PROJECT_DIR/android"
  local a="$an/app/src/main/java/$APP_PATH"

  write_file "$an/settings.gradle.kts" <<'EOF'
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "RutaBikerSync"
include(":app")
EOF

  write_file "$an/build.gradle.kts" <<'EOF'
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
EOF

  write_file "$an/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
org.gradle.caching=true
org.gradle.parallel=true
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
EOF

  # NOTA: las versiones son un punto de partida. Actualízalas a las vigentes
  # (Android Studio > Refactor > "Update dependencies", o mavenCentral/google maven).
  write_file "$an/gradle/libs.versions.toml" <<'EOF'
[versions]
agp = "8.7.2"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.25"
composeBom = "2024.10.01"
activityCompose = "1.9.3"
lifecycle = "2.8.7"
navigation = "2.8.3"
room = "2.6.1"
coroutines = "1.9.0"
serialization = "1.7.3"
datastore = "1.1.1"
workmanager = "2.9.1"
billing = "7.1.1"
nearby = "19.3.0"
maplibre = "11.5.2"
tink = "1.15.0"
mlkitBarcode = "17.3.0"
zxing = "3.5.3"
playIntegrity = "1.4.0"
junit = "4.13.2"
androidxTest = "1.2.1"

[libraries]
androidx-core-ktx = { module = "androidx.core:core-ktx", version = "1.13.1" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime = { module = "androidx.lifecycle:lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
compose-ui = { module = "androidx.compose.ui:ui" }
compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
compose-material3 = { module = "androidx.compose.material3:material3" }
compose-material-icons = { module = "androidx.compose.material:material-icons-extended" }
room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
kotlinx-serialization-cbor = { module = "org.jetbrains.kotlinx:kotlinx-serialization-cbor", version.ref = "serialization" }
androidx-datastore = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
androidx-work = { module = "androidx.work:work-runtime-ktx", version.ref = "workmanager" }
play-billing = { module = "com.android.billingclient:billing-ktx", version.ref = "billing" }
play-nearby = { module = "com.google.android.gms:play-services-nearby", version.ref = "nearby" }
play-integrity = { module = "com.google.android.play:integrity", version.ref = "playIntegrity" }
maplibre = { module = "org.maplibre.gl:android-sdk", version.ref = "maplibre" }
tink = { module = "com.google.crypto.tink:tink-android", version.ref = "tink" }
mlkit-barcode = { module = "com.google.mlkit:barcode-scanning", version.ref = "mlkitBarcode" }
zxing-core = { module = "com.google.zxing:core", version.ref = "zxing" }
junit = { module = "junit:junit", version.ref = "junit" }
androidx-test-ext = { module = "androidx.test.ext:junit", version.ref = "androidxTest" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
kotlin-serialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
EOF

  write_file "$an/app/build.gradle.kts" <<'EOF'
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

    buildTypes {
        release {
            isMinifyEnabled = true       // ofuscación R8 (app de código cerrado)
            isShrinkResources = true
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
    buildFeatures { compose = true }
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
    androidTestImplementation(libs.androidx.test.ext)
}
EOF

  write_file "$an/app/proguard-rules.pro" <<'EOF'
# Reglas R8 / ProGuard de Ruta Biker Sync
-keepattributes *Annotation*,Signature
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class **$$serializer { *; }
EOF

  write_file "$an/app/src/main/AndroidManifest.xml" <<'EOF'
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!-- Red -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />

    <!-- Bluetooth (Android 12+) -->
    <uses-permission android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!-- Bluetooth (Android 11 e inferior) -->
    <uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30" />
    <uses-permission android:name="android.permission.BLUETOOTH_ADMIN" android:maxSdkVersion="30" />

    <!-- Ubicación (mapa y rodada) -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
    <uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
    <!-- Segundo plano: requiere justificación en la ficha de Play -->
    <uses-permission android:name="android.permission.ACCESS_BACKGROUND_LOCATION" />

    <!-- Nearby Connections / Wi-Fi -->
    <uses-permission android:name="android.permission.NEARBY_WIFI_DEVICES"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE" />
    <uses-permission android:name="android.permission.CHANGE_WIFI_STATE" />

    <!-- Servicio en primer plano -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_LOCATION" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <!-- Voz, avisos y vibración -->
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
    <uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.VIBRATE" />
    <uses-permission android:name="android.permission.USE_BIOMETRIC" />

    <uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
    <uses-feature android:name="android.hardware.location.gps" android:required="false" />

    <application
        android:name=".App"
        android:allowBackup="false"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@android:style/Theme.Material.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <!-- Servicio de rodada (mesh + GPS). Se implementa en core/mesh y core/location.
        <service
            android:name=".core.mesh.RideService"
            android:exported="false"
            android:foregroundServiceType="location|connectedDevice" />
        -->
    </application>
</manifest>
EOF

  write_file "$an/app/src/main/res/values/strings.xml" <<'EOF'
<resources>
    <string name="app_name">Ruta Biker Sync</string>
</resources>
EOF
  write_file "$an/app/src/main/res/values-en/strings.xml" <<'EOF'
<resources>
    <string name="app_name">Ruta Biker Sync</string>
</resources>
EOF
  write_file "$an/app/src/main/res/values-pt/strings.xml" <<'EOF'
<resources>
    <string name="app_name">Ruta Biker Sync</string>
</resources>
EOF

  write_file "$a/App.kt" <<'EOF'
package com.rutabikersync.app

import android.app.Application

class App : Application()
EOF

  write_file "$a/MainActivity.kt" <<'EOF'
package com.rutabikersync.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Text("Ruta Biker Sync")
            }
        }
    }
}
EOF

  # Interfaz de transporte: el contrato que desacopla el protocolo del medio (BLE, Nearby, LoRa)
  write_file "$a/core/transport/Transport.kt" <<'EOF'
package com.rutabikersync.app.core.transport

import kotlinx.coroutines.flow.Flow

/** Medio de transporte intercambiable (BLE advertising, Nearby, LoRa en el futuro). */
interface Transport {
    val name: String
    suspend fun start()
    suspend fun stop()
    suspend fun send(payload: ByteArray, priority: Priority)
    val incoming: Flow<ByteArray>

    enum class Priority { SOS, PEER_EVENT, POSITION, ROUTE, VOICE, STATUS }
}
EOF

  if ! $DRY && [[ -d "$an" ]] && [[ -d "$ANDROID_HOME" ]]; then
    printf 'sdk.dir=%s\n' "$ANDROID_HOME" > "$an/local.properties"
  fi
}

# ---------- 6. Esqueleto del backend ----------
create_backend_files() {
  local b="$PROJECT_DIR/backend"

  write_file "$b/package.json" <<'EOF'
{
  "name": "ruta-biker-sync-backend",
  "version": "0.1.0",
  "private": true,
  "type": "module",
  "engines": { "node": ">=20" },
  "scripts": {
    "dev": "tsx watch src/index.ts",
    "build": "tsc -p tsconfig.json",
    "start": "node dist/index.js",
    "typecheck": "tsc --noEmit",
    "test": "vitest run",
    "migrate": "node-pg-migrate -m src/db/migrations --migration-file-language sql",
    "lint": "eslint src",
    "format": "prettier --write src"
  }
}
EOF

  write_file "$b/tsconfig.json" <<'EOF'
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "NodeNext",
    "moduleResolution": "NodeNext",
    "outDir": "dist",
    "rootDir": "src",
    "strict": true,
    "esModuleInterop": true,
    "skipLibCheck": true,
    "resolveJsonModule": true
  },
  "include": ["src"]
}
EOF

  write_file "$b/.env.example" <<'EOF'
NODE_ENV=development
PORT=3000
DATABASE_URL=postgres://rbs:rbs_dev_password@localhost:5432/rbs

# Clave de firma de credenciales (Ed25519, base64). En producción: KMS, nunca en archivo.
CREDENTIAL_SIGNING_KEY_ID=dev-1
CREDENTIAL_SIGNING_PRIVATE_KEY=

# Google Play Developer API (cuenta de servicio) y RTDN
GOOGLE_APPLICATION_CREDENTIALS=./signing-keys/play-service-account.json
PLAY_PACKAGE_NAME=com.rutabikersync.app
PLAY_PUBSUB_AUDIENCE=

# Credenciales: vigencia en días y gracia de pago en días
CREDENTIAL_TTL_DAYS=7
PAYMENT_GRACE_DAYS=3
EOF

  write_file "$b/src/index.ts" <<'EOF'
import Fastify from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import rateLimit from '@fastify/rate-limit';
import 'dotenv/config';

const app = Fastify({ logger: true });

await app.register(helmet);
await app.register(cors, { origin: false });
await app.register(rateLimit, { max: 100, timeWindow: '1 minute' });

app.get('/health', async () => ({ status: 'ok' }));

const port = Number(process.env.PORT ?? 3000);
await app.listen({ port, host: '0.0.0.0' });
EOF

  write_file "$b/test/health.test.ts" <<'EOF'
import { describe, it, expect } from 'vitest';

describe('sanity', () => {
  it('runs', () => {
    expect(1 + 1).toBe(2);
  });
});
EOF

  write_file "$b/src/db/migrations/0001_init.sql" <<'EOF'
-- Up Migration
-- Esquema inicial: ver docs/BACKEND_LICENSING.md, sección 4.
-- Pega aquí las tablas users, subscriptions, groups, group_leaders, memberships, ...
CREATE EXTENSION IF NOT EXISTS citext;
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Down Migration
EOF
}

install_backend_deps() {
  local b="$PROJECT_DIR/backend"
  if ! have npm; then warn "npm no está disponible; se omiten las dependencias del backend."; return; fi
  log "Instalando dependencias del backend (versiones vigentes de npm)..."
  if $DRY; then
    echo "[dry-run] (cd $b && npm install fastify @fastify/cors @fastify/helmet @fastify/rate-limit pg zod pino googleapis @noble/ed25519 node-pg-migrate dotenv)"
    echo "[dry-run] (cd $b && npm install -D typescript tsx vitest @types/node @types/pg eslint prettier)"
    return
  fi
  ( cd "$b" \
    && npm install fastify @fastify/cors @fastify/helmet @fastify/rate-limit pg zod pino googleapis @noble/ed25519 node-pg-migrate dotenv \
    && npm install -D typescript tsx vitest @types/node @types/pg eslint prettier )
}

# ---------- 7. Gradle wrapper ----------
setup_gradle_wrapper() {
  local an="$PROJECT_DIR/android"
  if [[ -f "$an/gradlew" ]]; then log "Gradle wrapper ya existe."; return; fi
  if ! have java; then warn "Sin Java no se puede generar el wrapper de Gradle."; return; fi

  local gradle_bin=""
  if have gradle; then
    gradle_bin="gradle"
  else
    log "Descargando Gradle ${GRADLE_VERSION} para generar el wrapper..."
    local tools="$PROJECT_DIR/tools"
    run curl -fL "https://services.gradle.org/distributions/gradle-${GRADLE_VERSION}-bin.zip" -o "$tools/gradle.zip"
    run unzip -q -o "$tools/gradle.zip" -d "$tools"
    run rm -f "$tools/gradle.zip"
    gradle_bin="$tools/gradle-${GRADLE_VERSION}/bin/gradle"
  fi
  if $DRY; then echo "[dry-run] (cd $an && $gradle_bin wrapper --gradle-version $GRADLE_VERSION)"; return; fi
  ( cd "$an" && "$gradle_bin" wrapper --gradle-version "$GRADLE_VERSION" ) \
    || warn "No se pudo generar el wrapper. Abre android/ en Android Studio, que lo creará."
}

# ---------- 8. BRouter (opcional) ----------
fetch_brouter() {
  if ! $WITH_BROUTER; then return; fi
  if [[ -d "$PROJECT_DIR/tools/brouter/.git" ]]; then log "BRouter ya clonado."; return; fi
  log "Clonando BRouter (ruteo offline)..."
  run git clone --depth 1 https://github.com/abrensch/brouter.git "$PROJECT_DIR/tools/brouter"
  warn "BRouter se integra después en la app; revisa su licencia y la de los datos de OpenStreetMap (ODbL)."
}

# ---------- 9. Git ----------
init_git() {
  if have git && [[ ! -d "$PROJECT_DIR/.git" ]]; then
    log "Inicializando repositorio git..."
    run git -C "$PROJECT_DIR" init -q
  fi
}

# ---------- Ejecución ----------
log "Proyecto: $PROJECT_DIR  |  Plataforma: $PLATFORM  |  dry-run: $DRY"

if ! $SKIP_SYSTEM; then install_system_tools; fi
run mkdir -p "$PROJECT_DIR"
create_structure
create_root_files
create_android_files
create_backend_files
if ! $SKIP_SDK; then install_android_sdk; fi
if ! $SKIP_NPM; then install_backend_deps; fi
if ! $NO_INSTALL; then setup_gradle_wrapper; fi
fetch_brouter
init_git

cat <<EOF

${c_green}Listo.${c_off} Siguientes pasos:

  1. Añade estas líneas a tu ~/.bashrc o ~/.zshrc:
       export ANDROID_HOME="$ANDROID_HOME"
       export PATH="\$PATH:\$ANDROID_HOME/cmdline-tools/latest/bin:\$ANDROID_HOME/platform-tools"

  2. Base de datos local:   cd $PROJECT_DIR/infra && docker compose up -d
  3. Backend:               cd $PROJECT_DIR/backend && cp .env.example .env && npm run dev
  4. Android:               abre $PROJECT_DIR/android en Android Studio,
                            o: cd $PROJECT_DIR/android && ./gradlew assembleDebug

  Importante:
  - Las versiones de libs.versions.toml son un punto de partida: actualízalas a las vigentes.
  - Antes de programar la Fase 2, valida el cobro de cupos múltiples en Google Play Console.
  - Documentos de gobernanza en $PROJECT_DIR/docs (RULES.md es la fuente de verdad).
EOF
