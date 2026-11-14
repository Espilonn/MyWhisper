# Règles (voxkey)

- **Ne pas casser ce qui marche.** Toute modification d'un comportement fonctionnel (bulle, position, interface, icône, logo) demande une demande explicite de l'utilisateur, et part d'un fichier committé, jamais d'un réglage non committé laissé en place.
- **Ne pas piloter l'écran du téléphone.** Pas de `adb shell input tap/swipe/keyevent`. L'utilisateur teste lui-même et rapporte.
- Annoncer avant de lancer ce qui touche à l'app : ce qui change, où, et comment revenir en arrière.
- Une chose à la fois : pas de build + tests + fenêtres en parallèle. Commandes longues détachées, jamais au premier plan.
- Tout réglage visuel essayé et abandonné repart de `git checkout`, pas d'ajustement empilé.
- **Le banc instrumenté (`am instrument`) force l'arrêt de l'app et laisse son service d'accessibilité en état « crashed » : la bulle disparaît.** Après chaque exécution, réactiver en réécrivant `enabled_accessibility_services` (retirer puis remettre `dev.voxkey.bench/...VoxkeyAccessibilityService`), sinon l'utilisateur croit avoir perdu la bulle.

# Build

- `JAVA_HOME=/home/loic/.jdks/jbr-21.0.11 ./gradlew :app:assembleDebug`
- Téléphone : Tailscale `100.102.55.89`, port de débogage sans fil variable (`adb connect 100.102.55.89:<port>`); port fixe `5555` seulement après `adb tcpip 5555`.
