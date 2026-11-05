# ADR 0021 — Photos sur Cloudinary dès US1a, fausse implémentation réservée aux tests

- Statut : acceptée
- Date : 2026-11-05
- Source : décision du Lead Developer du 05/11/2026 (Cloudinary intégré dans US1a, US1b fusionnée) ; décisions D5 à D9
  validées le même jour ; `docs/cadrage.md` §1.1 point 3, §1.3.12, §1.4.14 et §1.4.15 ; rapport exp. 4

## Contexte

- Le plan initial gardait les photos en mémoire en production jusqu'à US1b (`cadrage.md` §5, repli Should) : photos
  perdues au redémarrage, propres à chaque pod, coûteuses pour une JVM de 384 Mio.
- L'expérience 4 a validé l'upload signé, l'URL publique et la suppression depuis un pod, avec des identifiants lus dans un
  Secret, et relevé les réponses d'erreur (dont « Invalid api_key » qui renvoie la clé envoyée).
- Aucun test de la CI ne doit appeler le vrai Cloudinary (`cadrage.md` §1.3.12).

## Décision

1. **Implémentation de production** `StockagePhotoCloudinary`, active quand aucun profil n'est actif : API d'upload
   appelée par `RestClient` (client HTTP du JDK, HTTP/1.1), **sans le SDK Cloudinary** ; upload et `destroy`
   (`invalidate=true`) signés en SHA-1 comme à l'exp. 4 ; dossier `epouvante` ; `public_id` et `secure_url` renvoyés
   par Cloudinary enregistrés avec l'annonce. Délais : 2 s pour la connexion, 10 s pour la lecture.
2. **Ordre** : photo vérifiée (2 Mo, JPEG/PNG/WebP lus dans les premiers octets) → envoi → enregistrement ; si
   l'enregistrement échoue, `destroy` de la photo. Erreur ou lenteur de Cloudinary → **502**, aucune annonce enregistrée.
3. **Rien de Cloudinary ne sort de l'adaptateur** : le corps des réponses d'erreur n'est jamais lu ; seuls le code HTTP
   ou le type d'erreur sont journalisés ; la réponse au client est un message générique.
4. **Fausse implémentation** `StockagePhotoFactice` (profil Spring `photo-factice`) pour les tests et les campagnes de
   charge seulement ; un test vérifie qu'elle est inactive par défaut. L'adaptateur est testé seul contre WireMock.
5. **Secret `cloudinary`** (clés `cloud-name`, `api-key`, `api-secret`) créé à la main par
   `deploy/scripts/cloudinary-secrets.sh` (saisie sans écho, valeurs jamais affichées ni passées en argument), lu par
   `troc-service` via `APP_CLOUDINARY_*`.
6. **Sortie réseau** : NetworkPolicy `troc-sortie-https`, `troc-service` seul, TCP 443 vers Internet, réseaux privés,
   lien local, boucle locale et IP du nœud exclus.
7. **CSP du front** : `img-src 'self' https://res.cloudinary.com blob:` (sans le nom du compte). `blob:` ajouté le
   05/11/2026 (branche `us1a-ui`) pour la miniature de la photo choisie dans le formulaire : il ne désigne que des fichiers
   ouverts par la page elle-même, et chaque URL est libérée (`revokeObjectURL`) au changement de photo ou à la sortie
   de l'écran.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Photos en mémoire jusqu'à US1b (plan initial, D1) | remplacée par la décision du Lead Developer : démonstration avec le vrai hébergeur dès la première story |
| SDK Java de Cloudinary | dépendances supplémentaires (surface Trivy), client HTTP imposé ; deux appels seulement à écrire |
| Signature SHA-256 | possible chez Cloudinary mais non testée à l'exp. 4 ; à évaluer au plan de remédiation |
| Filtrage de la sortie par nom de domaine | impossible avec les NetworkPolicies (exp. 4) ; nécessiterait un proxy de sortie (RAM) |
| 500 pour une erreur de Cloudinary (`cadrage.md` §2, US1b) | 502 (*Bad Gateway*) désigne précisément la défaillance d'un service amont ; toujours une 5xx |

## Conséquences

- La latence de `POST /annonces` inclut l'upload (≈ 1,5 s depuis le VPS, exp. 4) : le seuil de 500 ms de l'indicateur 3
  vaut pour les campagnes en profil `photo-factice` ; une courte campagne mesure le vrai Cloudinary (`cadrage.md` §1.3.11).
- Le Secret doit exister **avant** le déploiement : sans lui, le pod `troc-service` ne démarre pas.
- Toute l'adresse 443 d'Internet est joignable depuis `troc-service` (limite connue, même que Flux).
- Le nom du compte Cloudinary apparaît dans l'URL publique de chaque photo (format Cloudinary) ; la clé et le secret
  jamais.
- `outils/sq-08/tester-flux.sh` vérifie le nouveau flux et ses interdits (HTTP 80, IP du nœud, autres pods).
