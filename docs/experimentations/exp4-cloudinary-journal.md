# Expérience 4 — Cloudinary depuis un pod : journal

- Date : 2026-10-30 ; namespace `sandbox` ; budget : une heure maximum
- Fichiers : `experimentations/exp4/`
- Décisions testées : photo envoyée à Cloudinary derrière `StockagePhoto`, `photoPublicId`/`photoUrl`, suppression
  (`docs/cadrage.md` §1.3) ; clé lue dans le Secret (§5, point 4) ; règle `troc-service` → Internet (§1.4.14)

## Protocole (validé le 2026-10-30)
- Secret `cloudinary-exp4` créé par le propriétaire du dépôt (saisie sans écho) ; valeurs jamais affichées : le script
  masque `cloud_name`, `api_key` et `api_secret` dans toute sa sortie (y compris dans les URL).
- Pod unique `curlimages/curl:8.11.1`, non-root, système de fichiers en lecture seule ; upload signé (SHA-1 calculé dans
  le pod) dans le dossier `epouvante-test` ; NetworkPolicy de sortie 443 hors du cluster pour ce seul pod.
- Cas : N nominal ; V accès public, suppression (`invalidate=true`), vérification par l'URL et l'Admin API ;
  E1 mauvais `api_secret`, `api_key` inventée ; E2 BMP de 3 Mo et 12 Mo ; F état final du dossier, suppression du dossier.
- Étapes : 0 poste ; 0 bis état initial ; 1 Secret ; 2 à 4 lancement du pod ; 5 nettoyage ; 6 rapport.

## Étape 0 — Vérifications et fichiers (poste, rien sur le VPS)
- `curlimages/curl:8.11.1` : UID 100, `sha1sum`, `sed`, `od`, `head`, `date` présents (busybox).
- Photo de test : `photo-test.jpg`, 640x480, 12 083 octets, premiers octets `ff d8 ff` (générée par `00-generer-photo.jsh`).
- Script vérifié dans l'image : syntaxe, BMP 1024x1024 de 3 145 782 octets (en-tête correct), signature identique à
  `sha1sum`, extraction des champs JSON sur une réponse fictive. Aucun appel à Cloudinary depuis le poste.

## Étape 0 bis — État initial du VPS (13:01:36 UTC)
```bash
ssh ubuntu@51.178.39.218 'date -u && free -h && kubectl top nodes && kubectl get ns && kubectl -n sandbox get all,cm,secret && kubectl -n sandbox get networkpolicy'
```
- RAM : 1,3 Gio disponibles sur 3,7 Gio ; nœud 2 354 Mio, 443m de CPU. Pas de namespace `app`.
- `sandbox` : pile de l'expérience 1 toujours en place (PostgreSQL, Keycloak, RabbitMQ, Prometheus, Grafana, whoami).
- NetworkPolicies : `refus-total` et `sortie-dns` présentes.
- Secret `cloudinary-exp4` déjà présent (3 clés, créé 5 min avant).

## Étape 1 — Secret créé à la main
Créé par le propriétaire du dépôt (saisie sans écho). Vérification sans les valeurs :
```bash
ssh ubuntu@51.178.39.218 'kubectl -n sandbox describe secret cloudinary-exp4'
```
- `CLOUDINARY_API_KEY` 15 octets, `CLOUDINARY_API_SECRET` 27 octets, `CLOUDINARY_CLOUD_NAME` 9 octets : noms conformes au pod.

## Étapes 2 à 4 — Pod de test (13:03:41 → 13:03:53 UTC)
```bash
scp -r experimentations/exp4 ubuntu@51.178.39.218:~/exp4
ssh ubuntu@51.178.39.218 'cd ~/exp4 && kubectl -n sandbox create configmap exp4-fichiers --from-file=20-cloudinary.sh --from-file=photo-test.jpg && kubectl apply -f 10-np-exp4.yaml -f 30-pod.yaml && kubectl -n sandbox wait --for=jsonpath="{.status.phase}"=Succeeded pod/exp4-cloudinary --timeout=300s; kubectl -n sandbox get pod exp4-cloudinary && kubectl -n sandbox logs exp4-cloudinary'
```
Pod `Completed` en 17 s, 0 redémarrage ; logs sans aucune valeur du Secret (`cloud_name` masqué dans l'URL).

| Cas | Résultat |
|---|---|
| N : upload JPEG 12 083 octets | 200 en 1,52 s ; `public_id=epouvante-test/exp4-nominal`, `asset_folder=epouvante-test` (compte en dossiers dynamiques) |
| V : GET de l'URL | 200, `image/jpeg`, 12 083 octets (accès public sans authentification) |
| V : `destroy` (`invalidate=true`) | 200, `result=ok` ; URL en 404 dès le premier essai ; Admin API 404 « Resource not found » |
| E1a : mauvais `api_secret` | 401 en 0,38 s, « Invalid Signature … String to sign - 'folder=…&public_id=…&timestamp=…' » (aucun secret dans le message) |
| E1b : `api_key` inventée | 401 en 0,37 s, « Invalid api_key 000000000000000 » (la clé envoyée est renvoyée dans le message) |
| E2 : BMP 3 145 782 octets | **200, accepté** en 1,44 s, puis supprimé (`result=ok`) |
| E2 : BMP 12 582 966 octets | 400 en 1,41 s, « File size too large. Got 12582966. Maximum is 10485760. » |
| F : dossier `epouvante-test` | 0 ressource (par préfixe et par `asset_folder`) ; dossier supprimé (`{"deleted":["epouvante-test"]}`) |

Écart avec l'hypothèse 4b : aucun. La limite de Cloudinary (plan du compte) est de 10 Mio par image : la limite de 2 Mo
du cadrage doit être appliquée par `troc-service`, Cloudinary ne la fera pas respecter.

## Étape 5 — Nettoyage (13:04:43 UTC)
```bash
ssh ubuntu@51.178.39.218 'kubectl -n sandbox delete pod exp4-cloudinary --wait && kubectl -n sandbox delete configmap exp4-fichiers && kubectl -n sandbox delete networkpolicy exp4-cloudinary-sortie-https && kubectl -n sandbox delete secret cloudinary-exp4 && rm -rf ~/exp4 && kubectl -n sandbox get pod,cm,secret,networkpolicy | grep -c exp4; free -h; date -u'
```
- Pod, ConfigMap, NetworkPolicy et Secret supprimés ; 0 objet `exp4` restant ; fichiers retirés du VPS.
- Images et dossier `epouvante-test` déjà supprimés par le pod (cas F). RAM disponible : 1,4 Gio (1,3 avant).
- Clé d'API Cloudinary : révocation à faire par le propriétaire du compte si une clé dédiée a été créée.

## Rapport final
`docs/experimentations/exp4-cloudinary-rapport.md`
