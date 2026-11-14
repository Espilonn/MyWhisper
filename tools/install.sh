#!/usr/bin/env bash
# Installe l'APK puis réactive accessibilité + superposition (la réinstallation les coupe).
# Usage: ./tools/install.sh [ip:port]   (défaut : 10.27.4.154:5555)
set -euo pipefail
cd "$(dirname "$0")/.."
DEV="${1:-10.27.4.154:5555}"
PKG=dev.voxkey.bench
SVC=$PKG/$PKG.VoxkeyAccessibilityService

export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"
adb connect "$DEV" >/dev/null
./gradlew -q installDebug

# Ne jamais faire `am force-stop` ici : sur ce téléphone il efface le réglage d'accessibilité.
# Réactive seulement si le réglage a été perdu (ex. après désinstallation).
A11Y=$(adb -s "$DEV" shell settings get secure enabled_accessibility_services | tr -d '\r')
if [[ "$A11Y" != *"$SVC"* ]]; then
  adb -s "$DEV" shell settings put secure enabled_accessibility_services "$SVC"
  adb -s "$DEV" shell settings put secure accessibility_enabled 1
  echo "Accessibilité réactivée (elle était désactivée)"
fi
adb -s "$DEV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
adb -s "$DEV" shell appops set "$PKG" CREATE_ACCESSIBILITY_OVERLAY allow
echo "Installé sur $DEV, accessibilité active"
