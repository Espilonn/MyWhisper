#!/usr/bin/env bash
# Installe l'APK en gardant les droits et la bulle active.
# `installDebug` met à jour sur place : jamais de désinstallation, jamais de force-stop.
# Après l'installation, vérifie que l'accessibilité est réellement liée (elle peut rester
# « listée mais non liée » après un arrêt forcé ou un banc instrumenté) et la réattache si besoin.
# Usage: ./tools/install.sh [serial-adb]
set -euo pipefail
cd "$(dirname "$0")/.."

PKG=dev.voxkey.bench
SVC="$PKG/$PKG.VoxkeyAccessibilityService"
if [[ $# -gt 0 ]]; then ADB=(adb -s "$1"); else ADB=(adb); fi

export JAVA_HOME="${JAVA_HOME:-$HOME/.jdks/jbr-21.0.11}"
"${ADB[@]}" get-state >/dev/null # échoue net si aucun téléphone

services() { "${ADB[@]}" shell dumpsys activity services "$PKG"; }

# Le service est lié si le système a un ServiceRecord pour lui : figurer dans les réglages ne
# suffit pas (après un arrêt forcé, il reste listé mais mort).
bound() { services | grep -q VoxkeyAccessibilityService; }

# La liaison n'est pas immédiate après une installation : la juger trop tôt déclenchait une
# réparation inutile, donc un basculement d'accessibilité et une seconde installation.
wait_bound() {
  for _ in $(seq 1 20); do
    bound && return 0
    sleep 0.25
  done
  return 1
}

./gradlew -q :app:installDebug

# Droits runtime : accordés une fois, conservés par les mises à jour sur place.
# (Seule une désinstallation les efface — d'où l'interdiction de désinstaller.)
for P in android.permission.RECORD_AUDIO android.permission.POST_NOTIFICATIONS; do
  "${ADB[@]}" shell pm grant "$PKG" "$P" 2>/dev/null || true
done
"${ADB[@]}" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
"${ADB[@]}" shell appops set "$PKG" CREATE_ACCESSIBILITY_OVERLAY allow

if ! wait_bound; then
  # Réécrire la même valeur ne relance pas le service : on retire notre composant puis on le
  # remet, en gardant les autres services actifs.
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
  wait_bound || true
  echo "Accessibilité réattachée (elle n'était pas liée), processus redémarré"
fi

if ! bound; then
  echo "ÉCHEC : accessibilité non liée — à activer dans Réglages > Accessibilité > MyWhisper" >&2
  exit 1
fi

# La bulle n'est créée qu'à l'ouverture du clavier : on ne peut vérifier ici que le service
# qui la porte, pas la surface elle-même.
if services | grep -q OverlayService; then
  echo "OK : mise à jour sur place, accessibilité liée, service bulle démarré"
else
  echo "OK : mise à jour sur place, accessibilité liée — service bulle arrêté (bulle désactivée dans les Réglages de l'app ?)"
fi
