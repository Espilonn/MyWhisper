# Benchmark (phase 0)

But : mesurer whisper.cpp sur le Nothing Phone (3a) avant d'écrire l'app réelle.

## 1. Modèles

```bash
cd models
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base-q5_1.bin    #  57 Mo
curl -LO https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small-q5_1.bin   # 181 Mo
```

## 2. Build

whisper.cpp v1.9.5 est récupéré à la compilation :

```bash
git clone --depth 1 --branch v1.9.5 https://github.com/ggml-org/whisper.cpp.git third_party/whisper.cpp
export JAVA_HOME=/chemin/vers/jdk21
./gradlew assembleDebug
```

`local.properties` doit contenir `sdk.dir` et, si le SDK n'a pas de CMake,
`cmake.dir=/usr`.

## 3. Installer

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 4. Pousser les modèles

Après le premier lancement (le dossier n'existe qu'à ce moment) :

```bash
adb push models/ggml-small-q5_1.bin /sdcard/Android/data/dev.voxkey.bench/files/
adb push models/ggml-base-q5_1.bin  /sdcard/Android/data/dev.voxkey.bench/files/
```

L'app liste les `.bin` de ce dossier, aucun import ni permission nécessaire.

## 5. Mesurer

1. choisir le modèle ;
2. « Enregistrer », dire une phrase de ~10 s puis une de ~30 s ;
3. « Transcrire ».

L'écran affiche : durée audio, temps total, temps d'encodage, temps de
chargement du modèle, RTF (temps réel : 1.0 = aussi long que l'audio) et pic
mémoire.

Chaque run est ajouté en JSON sur une ligne :

```bash
adb pull /sdcard/Android/data/dev.voxkey.bench/files/bench.jsonl
```

## Ce qu'on veut savoir

| Question | Seuil qui change le plan |
|---|---|
| RTF de `small` sur 10 s | < 0,5 → streaming confortable ; > 1,0 → mode dynamique impossible |
| RTF sur 30 s | doit rester linéaire, sinon problème de chauffe / throttling |
| Pic mémoire `small` | > 1,2 Go → `base` obligatoire sur les modèles 8 Go |
| Qualité FR | taux d'erreur à l'œil sur une phrase connue |

Vulkan est désactivé dans `app/src/main/cpp/CMakeLists.txt` :
passer `GGML_VULKAN` à `ON` après avoir figé la référence CPU.
