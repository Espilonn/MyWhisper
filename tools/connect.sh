#!/usr/bin/env bash
# Reconnecte ADB au téléphone via Tailscale (port fixe 5555, posé par `adb tcpip 5555`).
# À relancer si `adb devices` ne montre plus 100.102.55.89:5555.
set -u
TS_IP=${TS_IP:-100.102.55.89}
PORT=${PORT:-5555}
TARGET="$TS_IP:$PORT"

adb connect "$TARGET" >/dev/null 2>&1 || true
if adb -s "$TARGET" shell true >/dev/null 2>&1; then
    echo "ADB OK sur $TARGET"
    exit 0
fi
echo "Échec sur $TARGET."
echo "Après un redémarrage du téléphone, adbd ne réécoute plus : rebrancher en USB une fois, puis 'adb tcpip $PORT'."
exit 1
