# Décisions

## 1. Pas d'intégration Gboard — impossible

Gboard est un APK signé par Google, aucune API d'extension. Personne ne peut y ajouter
un bouton, fabricant compris.

Référence : **Nothing Essential Voice** (Nothing OS 4.1) a une icône en bas à gauche
« du clavier », mais elle est **superposée** au-dessus de la zone du clavier — la
fonction marche « avec n'importe quel clavier, y compris ceux de tiers », ce qui exclut
une intégration Gboard. Nothing y arrive en tant que fabricant : touche matérielle
dédiée + overlay système sans permission.

De plus Essential Voice est **cloud** (Gemini 3 Flash) et absent du 3a. Rien à copier
côté technique, seulement l'UX.

## 2. Mode retenu : overlay (bulle par-dessus l'app active)

- Bulle via `TYPE_APPLICATION_OVERLAY` + permission `SYSTEM_ALERT_WINDOW`.
- Positionnée en bas à gauche, au-dessus du clavier.
- Insertion du texte via `AccessibilityService` (`ACTION_SET_TEXT` sur le nœud focalisé,
  repli presse-papier + `ACTION_PASTE`).
- L'IME maison reste l'alternative (plus fiable, zéro permission) mais n'est **pas** le
  chemin retenu.

### Conséquences assumées

| Sujet | Conséquence |
|---|---|
| Insertion | Fragile sur champs custom / WebView. OK sur la majorité des apps. |
| Texte instable | Impossible dans le champ (pas d'état `composing` hors IME). Le texte vivant s'affiche **dans la bulle** ; le champ reçoit les segments stabilisés par paliers de 2–3 s. |
| Distribution | Hors Play Store (les règles Play limitent l'usage de l'API accessibilité à des fins non-accessibilité). |
| Batterie | Événements d'accessibilité poussés par le système, pas de polling. Bulle visible seulement quand un champ a le focus. Au repos : zéro. |

## 3. Langage

**Kotlin** pour l'app, **C++** seulement pour la colle JNI vers whisper.cpp. Pas de Rust :
`InputMethodService`/service/audio sont du framework Java, et le moteur d'inférence est
déjà écrit en C/C++.

## 4. Modèle

`small` q5_1 (~180 Mo) par défaut, `base` q5 (~80 Mo) en repli. `medium` exclu.
Chemin + taille centralisés dans **une seule constante**.

## 5. Mode dynamique (streaming)

Whisper n'est pas streaming. Fenêtre glissante : re-décodage des ~4 dernières secondes
toutes les ~600 ms, chevauchement, préfixe stable validé, queue instable affichée en gris
(dans la bulle). Si `small` est trop lent sur le 7s Gen 3 : partiels avec `base`,
texte final avec `small`.
