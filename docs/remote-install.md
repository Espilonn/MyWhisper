# Installer l'APK à distance (Tailscale)

But : installer et tester l'app sur le téléphone sans être sur le même Wi-Fi.

## Le tailnet

| Machine | IP Tailscale | Nom |
|---|---|---|
| Poste de dev (CachyOS) | `100.110.178.92` | `cachyos-desktop.tail0f829e.ts.net` |
| Téléphone (Nothing Phone 3a) | `100.102.55.89` | `nothing-phone-3a.tail0f829e.ts.net` |

MagicDNS ne résout pas localement (`accept-dns` à `false` volontairement, pour ne pas
écraser `systemd-resolved`). **Utiliser l'IP**, elle est stable.

## Ce qui ne marche pas

La découverte ADB par mDNS est locale : `adb devices` ne verra jamais le téléphone à
travers le tunnel. Il faut une connexion explicite avec un port connu.

## La contrainte à connaître

`adbd` doit écouter. Deux façons de l'obtenir :

1. **Débogage sans fil** — Options développeur → Débogage sans fil. Le téléphone affiche
   « IP:port ». Le port change à chaque activation.
2. **Port fixe** — une fois, avec un câble USB : `adb tcpip 5555`. `adbd` écoute alors sur
   `5555` jusqu'au prochain redémarrage du téléphone.

## Commandes

```bash
# 1. Connexion (port 5555 avec l'option 2, sinon le port affiché par le téléphone)
adb connect 100.102.55.89:5555

# 2. Vérification
adb devices            # doit lister 100.102.55.89:5555  device

# 3. Installation depuis le projet
cd ~/Documents/Dev/voxkey
./gradlew installDebug

# ou, pour une APK déjà construite
adb install -r app/build/outputs/apk/debug/app-debug.apk

# raccourci : reconnecte et vérifie d'un coup
./tools/connect.sh
```

## Après une réinstallation

Réinstaller l'APK désactive le service d'accessibilité. Il faut le réactiver dans les
réglages (ou par `adb`), sinon la bulle n'écrit plus dans le champ.

```bash
adb shell settings put secure enabled_accessibility_services dev.voxkey.bench/.VoxkeyAccessibilityService
adb shell settings put secure accessibility_enabled 1
```

## Dépannage

| Symptôme | Cause |
|---|---|
| `Connection refused` | `adbd` n'écoute pas : débogage sans fil désactivé après un passage en 4G. Réactiver. |
| `device offline` | Session morte : `adb disconnect 100.102.55.89:5555` puis reconnecter. |
| Le port LAN ne répond plus | Le téléphone a quitté le Wi-Fi. Normal : passer par Tailscale. |
