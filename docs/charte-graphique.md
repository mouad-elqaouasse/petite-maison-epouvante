# Charte graphique du front

Statut : validée le 30/10/2026 (ticket SQ-03, variante B « affiche de film d'horreur rétro », retenue parmi trois).
S'applique à tous les écrans Angular. Source dans le code : `front/src/styles.css` (variables `:root`).

## Intention

L'univers des affiches de films d'horreur des années 70 : fond noir, titres condensés en rouge sang, texte crème.
Une seule audace par écran (le titre de la maison) ; le reste est sobre, c'est une application communautaire.

## Couleurs

| Variable | Valeur | Usage |
|---|---|---|
| `--nuit` | `#080605` | fond de page |
| `--creme` | `#f1e3c6` | texte courant, valeurs importantes, contour de focus |
| `--sang` | `#e3242c` | **grands titres uniquement** (contraste 3,7:1, insuffisant en petit corps) |
| `--sang-texte` | `#ff5a5f` | rouge lisible en petit corps : erreurs, états « indisponible » |
| `--texte-secondaire` | `#b9ad96` | aides, libellés secondaires, état de chargement |
| `--filet` | `#4a3f33` | filets de séparation |

Fond : halo rouge discret en haut de page (`radial-gradient` sur `body`), aucune autre décoration.

## Typographie

| Police | Rôle |
|---|---|
| League Gothic (condensée) | titre de la maison, titres de section, valeurs mises en avant ; en capitales |
| Atkinson Hyperlegible Next (400, 700) | tout le texte courant, libellés, champs, messages |

- Polices **auto-hébergées** (paquets `@fontsource`, versions exactes) : la CSP limite `font-src` à `'self'`.
- Les capitales sont un effet CSS (`text-transform`) : le texte du DOM reste en casse normale pour les lecteurs d'écran.
- Pas de petit texte en League Gothic : elle est réservée aux tailles ≥ 2 rem.

## Mise en page

- Colonne centrée de 44 rem maximum, marges latérales de 1 rem ; composition centrée, comme une affiche.
- Listes de valeurs présentées comme le générique d'une affiche : libellé en petit sous la valeur en grand.
- Mobile d'abord : tout fonctionne à 375 px, sans défilement horizontal.

## Composants

- **Bouton principal** : fond `--sang`, texte `--creme` en Atkinson 700, coins de 2 px, libellé à l'infinitif
  (« Publier l'annonce ») ; le même verbe dans le message de succès (« Annonce publiée »).
- **Bouton secondaire** : fond transparent, contour 1 px `--creme`.
- **Focus** : contour de 3 px `--creme`, décalé de 2 px, toujours visible.
- **Champs** : libellé visible au-dessus de chaque champ, fond `--nuit`, bordure `--filet`, bordure `--creme` au focus.
- **Erreurs** : en `--sang-texte`, liées au champ (`aria-describedby`), annoncées (`aria-live`).

## Règles techniques

- **Pas de styles de composant** (`styleUrl`) : Angular les injecterait dans des balises `<style>`, bloquées par la
  CSP (`style-src 'self'`). Tous les styles vont dans `front/src/styles.css`, sous les classes de l'écran.
- `inlineCritical` désactivé dans `angular.json` pour la même raison.
- Contrastes vérifiés par axe-core (0 violation grave) à chaque écran, à 375 et 1280 px.
