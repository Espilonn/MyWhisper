#!/usr/bin/env bash
# Installe l'APK en gardant les droits et la bulle active.
# `installDebug` met à jour sur place : jamais de désinstallation, jamais de force-stop.
# Après l'installation, réattache l'accessibilité (elle peut rester « listée mais non liée »
# après un arrêt forcé ou un banc instrumenté) et vérifie que le service bulle tourne.
# Usage: ./tools/install.sh [serial-adb]
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=dev.voxkey.bench
SVC="$PKG/$PKG.VoxkeyAccessibilityService"
if [[ $# -gt 0 ]]; then ADB=(adb -s "$1"); else ADB=(adb); fi

export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"
"${ADB[@]}" get-state >/dev/null # échoue net si aucun téléphone

./gradlew -q :app:installDebug

# Droits runtime : accordés une fois, conservés par les mises à jour sur place.
# (Seule une désinstallation les efface — d'où l'interdiction de désinstaller.)
for P in android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS; do
  "${ADB[@]}" shell pm grant "$PKG" "$P" 2>/dev/null || true
done
"${ADB[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"${ADB[@]}" shell appops set "$PKG" CREATE_ACCESSIBILITY_OVERLAY allow

# Si l'accessibilité est perdue (« listée mais non liée » après un arrêt forcé ou un banc
# instrumenté), on la réattache. Réécrire la même valeur ne relance pas le service : on retire
# notre composant puis on le remet, en gardant les autres services actifs. Inutile quand elle
# est déjà liée, et le basculement détruirait la surface de la bulle pour rien.
BOUND=$("${ADB[@]}" shell dumpsys activity services "$PKG" | grep -c "VoxkeyAccessibilityService" || true)
if [[ "$BOUND" == "0" ]]; then
  LIST=$("${ADB[@]}" shell settings get secure enabled_accessibility_services | tr -d '\r')
  KEEP=$(printf '%s' "$LIST" | tr ':' '\n' | sed '/^$/d' | grep -vxF "$SVC" || true)
  "${ADB[@]}" shell settings put secure enabled_accessibility_services "$KEEP"
  sleep 0.4
  "${ADB[@]}" shell settings put secure enabled_accessibility_services "${KEEP:+$KEEP:}$SVC"
  "${ADB[@]}" shell settings put secure accessibility_enabled 1
  # Une surface composée meurt avec la connexion d'accessibilité qui l'a créée ; le service,
  # lui, survit et garde la sienne, donc plus rien ne s'affiche. Réinstaller sur place redémarre
  # le processus et repart d'une surface neuve (jamais de force-stop).
  ./gradlew -q :app:installDebug
  echo "Accessibilité réattachée (elle n'était pas liée), processus redémarré"
fi

# Laisse le système relier le service, puis vérifie que la bulle tourne vraiment.
for _ in $(seq 1 20); do
  S=$("${ADB[@]}" shell dumpsys activity services "$PKG")
  [[ "$S" == *VoxkeyAccessibilityService* && "$S" == *OverlayService* ]] && break
  sleep 0.25
done
if [[ "$S" == *VoxkeyAccessibilityService* && "$S" == *OverlayService* ]]; then
  echo "OK : mise à jour sur place, accessibilité liée, service bulle actif"
else
  echo "ÉCHEC : accessibilité ou bulle non démarrée — activer l'accessibilité dans les Réglages système" >&2
  exit 1
fi
