# MyWisper

Dictée vocale **100 % hors ligne** sur Android. Pas de clavier à installer : une bulle se
colle au-dessus de la barre d'espace de Gboard et écrit le texte dicté directement dans le
champ actif.

Aucun audio ne quitte le téléphone : la transcription tourne en local sur le CPU, avec
[NVIDIA Parakeet TDT 0.6B v3](https://huggingface.co/ggml-org/parakeet-GGUF) quantisé.

> État : `0.1.0`, en développement actif. Les identifiants internes (`dev.voxkey.bench`,
> base `voxkey.db`, préférences `voxkey`) datent du nom de travail « voxkey » et n'ont pas
> encore été migrés — les renommer créerait une seconde application et imposerait de
> retélécharger les modèles.

## Ce que ça fait

- **Bulle accrochée au clavier** — appui pour dicter, appui pour arrêter. La bulle suit la
  fenêtre de Gboard (`SurfaceControlViewHost`, API 34+ ; repli `WindowManager` sur les
  versions antérieures).
- **Écriture dans n'importe quelle application** — via un service d'accessibilité, qui
  insère le texte dans le champ qui a le focus.
- **Mots-clés** — une abréviation (`auj`) devient son texte complet (`aujourd'hui`) à
  l'insertion.
- **Dictionnaire** — vocabulaire personnel (noms propres, termes techniques) ajouté à la
  main. Il n'est pas encore transmis au moteur : c'est une liste, pas une correction.
- **Historique** — dictées enregistrées, avec leur nombre de mots, consultation et
  suppression.
- **Accueil** — total de mots, comparaison avec la période précédente, et une grille de
  jours façon contributions GitHub (plus le carré est sombre, plus la journée a produit de
  mots) ; un appui sur un carré donne le détail du jour.
- **Couleur d'accent réglable** — jaune par défaut, au choix dans les réglages ; la teinte
  s'applique à toute l'application et à la bulle.

## Comment ça marche

```
Gboard (clavier système)
  └─ bulle MyWisper, ancrée à la fenêtre du clavier
        │  appui
        ▼
OverlayService (service de premier plan, type microphone)
        │  AudioRecord 16 kHz mono → PCM flottant
        ▼
ParakeetEngine (JNI → ggml / whisper.cpp)
        │  texte
        ▼
VoxkeyAccessibilityService
        └─ insertion dans le champ actif
```

Le fichier de modèle choisi dans les réglages décide du moteur : un fichier Parakeet est
traité par `ParakeetEngine`, un fichier Whisper (ggml) par `WhisperEngine`. Seuls les
modèles Parakeet sont proposés au téléchargement.

Le modèle est chargé au début de la dictée et libéré 60 s après la fin : il ne reste pas en
mémoire quand l'application n'est pas utilisée.

## Modèles

Téléchargés depuis Hugging Face vers le stockage privé de l'application, avec reprise par
fichier `.part` :

| Fichier | Taille | Usage |
|---|---|---|
| `ggml-parakeet-tdt-0.6b-v3-q4_k.bin` | 415 Mo | par défaut, le plus rapide |
| `ggml-parakeet-tdt-0.6b-v3-q8_0.bin` | 668 Mo | plus précis, plus lourd |

## Performance mesurée

Sur Nothing Phone (3a), Snapdragon 7s Gen 3, 4 threads :

- 21,7 s d'audio → transcription en ~24 s, dont ~17,8 s pour l'encodeur ;
- décodage quasi gratuit (~22 ms) ;
- chargement du modèle Q4_K ~1,3 s, dont l'essentiel est le réarrangement des poids
  quantifiés (mono-thread dans ggml).

Détail des mesures et pistes écartées : [docs/benchmark.md](docs/benchmark.md),
[docs/decisions.md](docs/decisions.md).

## Construire

Prérequis : JDK 21, Android SDK (compileSdk 37), NDK `28.2.13676358`, et le sous-module.

```bash
git clone --recursive https://github.com/EspiIon/MyWisper.git
cd MyWisper
export JAVA_HOME=~/.jdks/jbr-21.0.11
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- Kotlin, **Views programmatiques** (pas de Compose) et **aucune dépendance de production** :
  les seules dépendances sont de test (`junit`, `androidx.test`).
- Native : `app/src/main/cpp` (CMake) au-dessus du sous-module `third_party/whisper.cpp`,
  épinglé sur `v1.9.5`. Une seule ABI : `arm64-v8a`.
- `VOXKEY_VULKAN=OFF` dans `app/build.gradle.kts` : le backend Vulkan s'est révélé plus lent
  que le CPU sur Adreno.

## Installer sur le téléphone

1. Ouvrir **MyWisper**, télécharger un modèle (415 Mo pour Q4_K).
2. Autoriser la superposition, le micro et les notifications.
3. Activer le service d'accessibilité MyWisper (sans lui, le texte ne peut pas être écrit
   dans les autres applications).
4. Ouvrir un champ de texte : la bulle apparaît au-dessus du clavier.

Installation à distance (appareil sur le même tailnet) :
[docs/remote-install.md](docs/remote-install.md).

## Limites connues

- **Google Play** : l'écriture dans les autres applications par un service d'accessibilité
  y est interdite. La distribution passerait par un APK, F-Droid ou Obtainium — ou par un
  véritable `InputMethodService`, qui n'est pas encore écrit.
- **Android 12+** requis (`minSdk 31`).
- La bulle s'ancre sur la fenêtre de Gboard ; un autre clavier n'a pas été validé.
- Latence supérieure au temps réel sur les dictées longues (~24 s pour 21,7 s d'audio).

## Structure

```
app/src/main/java/dev/voxkey/bench/
  OverlayService.kt            bulle, enregistrement, cycle du modèle
  VoxkeyAccessibilityService.kt  insertion dans le champ actif
  ParakeetEngine.kt            moteur principal (GGUF, JNI)
  WhisperEngine.kt             moteur Whisper (ggml)
  ModelCatalog.kt              téléchargement des modèles
  Store.kt                     SQLite : dictées, vocabulaire, mots-clés
  MainActivity.kt / *Screen.kt  navigation et écrans
  Ui.kt / Accent.kt            système visuel et couleur d'accent
  DayGrid.kt                   grille de jours façon contributions
app/src/main/cpp/              JNI + CMake vers whisper.cpp
docs/                          décisions, mesures, installation à distance
```

## Licence

Pas encore de licence : le code est public mais tous droits réservés. Le sous-module
`third_party/whisper.cpp` conserve sa licence MIT.
