# Benchmark (phase 0)

But : mesurer whisper.cpp sur le Nothing Phone (3a) avant d'écrire l'app réelle.
App minimale, un écran, package `dev.voxkey.bench`, arm64-v8a seulement.

## 1. Modèles

```bash
cd models
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin    #  57 Mo
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin   # 181 Mo
```

## 2. Récupérer whisper.cpp puis compiler

whisper.cpp est un clone gitignoré, épinglé sur un tag :

```bash
./scripts/fetch-whisper.sh          # tag v1.9.5 (surchargeable : WHISPER_VERSION=...)
export JAVA_HOME=~/.jdks/jbr-21.0.11
./gradlew assembleDebug
```

`local.properties` doit contenir `sdk.dir` et, si le SDK n'a pas de CMake
(c'est le cas ici), `cmake.dir=/usr`.

Build CPU par défaut. Le seul interrupteur Vulkan est `VOXKEY_VULKAN`
(`app/src/main/cpp/CMakeLists.txt`, ou `-DVOXKEY_VULKAN=ON` dans les
`arguments` cmake de `app/build.gradle.kts`) : à activer seulement après avoir
figé la référence CPU. Le backend compilé est affiché en haut de l'écran
(`CPU` / `Vulkan`).

## 3. Installer

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Pousser les modèles

Sans import depuis le téléphone, en adb (après le premier lancement) :

```bash
adb push models/ggml-small-q5_1.bin /sdcard/Android/data/dev.voxkey.bench/files/models/
adb push models/ggml-base-q5_1.bin  /sdcard/Android/data/dev.voxkey.bench/files/models/
```

L'app liste les `.bin` de ce dossier, aucun permission nécessaire. Un bouton
« Import model » (SAF) copie aussi un `.bin` depuis n'importe où (Download,
Drive…) vers ce même dossier.

## 5. Mesurer

1. choisir le modèle ;
2. « Record » (10 s ou 30 s) ou « Pick WAV » (WAV 16 kHz mono PCM16
   uniquement ; les autres formats sont refusés) ;
3. « Transcribe ».

L'écran affiche : durée audio, temps total, temps d'encodage / décodage, RTF
(temps total / durée audio ; 1.0 = aussi long que l'audio), pic RSS
(`VmHWM`) et tas natif alloué, plus le backend et le nombre de threads.

> Le découpage encodage/décodage vient du callback de progression de whisper :
> `encodeMs` = jusqu'à 100 %, `decodeMs` = le reste (échantillonnage +
> décodage). Le temps de chargement du modèle n'est pas inclus.

Chaque run est ajouté en JSON sur une ligne (`bench.jsonl`) :

```bash
adb pull /sdcard/Android/data/dev.voxkey.bench/files/bench.jsonl
```

Une ligne = `{ts, model, lang, threads, backend, audioSec, wallMs, encodeMs,
decodeMs, rtf, peakRssKb, chars, text}`.

## Ce qu'on veut savoir

| Question | Seuil qui change le plan |
|---|---|
| RTF de `small` sur 10 s | < 0,5 → streaming confortable ; > 1,0 → mode dynamique impossible |
| RTF sur 30 s | doit rester linéaire, sinon problème de chauffe / throttling |
| Pic mémoire `small` | > 1,2 Go → `base` obligatoire sur les modèles 8 Go |
| Qualité FR | taux d'erreur à l'œil sur une phrase connue |

## 6. Candidats à comparer (non mesurés)

Chiffres des fiches, pas de mesure sur téléphone. Même jeu de test FR à faire avant de trancher.

| Modèle | Taille | Runtime Android | FR (source) | Statut |
|---|---|---|---|---|
| Whisper small q5_1 (actuel) | 181 Mo | whisper.cpp | MLS-FR 16,2 (papier Whisper) | référence |
| QVAC TranscribePsy-250M-EU-sub Q8 GGUF | 305 Mo | GGML, C++ à intégrer | MLS-FR 4,06 (Tether) | à tester |
| Parakeet TDT 0.6B v3 Q4_K (GGUF) | 467 Mo | parakeet-main (CrispASR), C++ à intégrer | non mesuré | à tester |
| Parakeet TDT 0.6B v3 int8 (sherpa-onnx) | ~670 Mo | sherpa-onnx ONNX | FLEURS-FR 8,69 (sherpa) | référence qualité, à tester |
| Parakeet TDT 0.6B v3 Q8_0 (GGUF) | 711 Mo | parakeet-main (CrispASR) | non mesuré | quasi sans perte, à tester si Q4_K déçoit |
| Parakeet TDT 0.6B v3 full | ~2,5 Go | — | — | écarté (trop lourd) |

Source GGUF : https://huggingface.co/cstr/parakeet-tdt-0.6b-v3-GGUF (Q4_K recommandé par l'auteur, licence CC-BY-4.0).

Notes : Whisper small MLS-FR vaut 10,94 dans le run Tether, contre 16,2 ici : les setups diffèrent, relancer les deux sur le même jeu.
TranscribePsy : 8 langues seulement, pas de streaming, RAM pic ~530 Mo (fiche Tether, non vérifiée).
Parakeet v3 : 25 langues européennes, RAM « au moins 2 Go » (fiche NVIDIA), découpe en segments de 15-20 s.
