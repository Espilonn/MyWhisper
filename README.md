# voxkey

Clavier Android avec dictée vocale **hors ligne** (Whisper local).

Nom provisoire. Tout est dans `docs/decisions.md` pour le détail.

## Cible

- **Nothing Phone (3a)** — Snapdragon 7s Gen 3, Adreno 710, 8 ou 12 Go de RAM.
- Android 15 (Nothing OS 3.1).

## Stack décidée

| Brique | Choix | Pourquoi |
|---|---|---|
| App / IME | **Kotlin** | `InputMethodService` est une API framework : Kotlin est la langue maternelle d'Android. |
| Inférence | **whisper.cpp** (ggml), backend Vulkan | Déjà écrit en C/C++, tourne sur GPU Adreno, quantisé q5/q8. |
| Liaison | JNI + CMake/NDK | Le seul endroit où on écrit du C++. |
| Rust | **non** | Il faudrait quand même Kotlin pour l'IME, donc Rust = Kotlin + FFI + Rust. Aucun gain ici : on n'écrit pas de moteur d'inférence. |

## Modèle

- `small` q5_1 (~180 Mo) par défaut, `base` q5 (~80 Mo) en repli.
- Chemin et taille du modèle : **une seule constante** dans le code, pour basculer sans réécrire.
- `medium` : exclu sur ce téléphone.

## Architecture (prévue)

```
IME (Kotlin)            → bouton micro, commitText()
  ↓
Service foreground      → détient le modèle whisper (le mettre dans l'IME = tué par Android)
  ↓
JNI → whisper.cpp       → 16 kHz mono PCM → texte
```

Pas de plugin Gboard : Gboard est fermé. On livre un clavier, l'utilisateur bascule.

## Ordre de travail

- [ ] **0. Benchmark sur l'appareil** — `small` q5_1 vs `base` q5, phrase de 10 s et 30 s en français. Mesure : latence, RAM, chauffe, qualité.
- [ ] 1. Squelette IME + bouton micro + capture WAV 16 kHz.
- [ ] 2. Service foreground : audio → whisper → `commitText()`.
- [ ] 3. Téléchargement du modèle au premier lancement.
- [ ] 4. Layout clavier, insertion partielle, correction.

## Environnement local

- SDK : `~/Android/Sdk` (NDK et build-tools présents)
- JDK 21 : `~/.jdks/jbr-21.0.11`
- `adb` : `/usr/bin/adb` — **aucun appareil branché actuellement**

## Utilisation

1. Ouvrir **voxkey**.
2. Autoriser la superposition, activer le service d'accessibilité, autoriser le micro.
3. La bulle apparaît automatiquement dès qu'un champ de texte a le focus.
4. Appui sur la bulle : dictée. Nouvel appui : arrêt.
5. Glisser la bulle pour la déplacer (position mémorisée).

Le texte s'écrit dans le champ au fil de la dictée. Les partiels utilisent le plus
petit modèle disponible, la transcription finale le modèle choisi dans l'app.

## Installation à distance

Le téléphone est dans le même tailnet que le poste : voir [docs/remote-install.md](docs/remote-install.md).
`adb connect 100.102.55.89:5555`, puis `./gradlew installDebug`.
