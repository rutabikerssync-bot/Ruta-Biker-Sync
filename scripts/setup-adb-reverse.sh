#!/usr/bin/env bash
# Ruta Biker Sync - Script de configuración de túnel ADB Reverse (Bash)
# Mapea el puerto 3000 (Backend Fastify) a todos los dispositivos Android conectados

ADB_CMD="adb"
if ! command -v adb &> /dev/null; then
    if [ -f "/mnt/c/Users/ozmar/AppData/Local/Android/Sdk/platform-tools/adb.exe" ]; then
        ADB_CMD="/mnt/c/Users/ozmar/AppData/Local/Android/Sdk/platform-tools/adb.exe"
    fi
fi

echo "🔍 Detectando dispositivos Android conectados..."
DEVICES=$($ADB_CMD devices | grep -w "device" | awk '{print $1}')

if [ -z "$DEVICES" ]; then
    echo "⚠️ No se detectaron dispositivos conectados por ADB."
    exit 0
fi

for DEV in $DEVICES; do
    echo "⚙️ Configurando túnel 'adb reverse tcp:3000 tcp:3000' en dispositivo $DEV..."
    $ADB_CMD -s "$DEV" reverse tcp:3000 tcp:3000
    if [ $? -eq 0 ]; then
        echo "  ✅ Puerto 3000 enrutado con éxito a $DEV"
    else
        echo "  ❌ Error enrutando puerto en $DEV"
    fi
done
echo "🚀 Enrutamiento ADB Reverse completado."

