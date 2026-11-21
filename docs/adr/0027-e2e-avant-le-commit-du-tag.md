# ADR 0027 — Tests E2E entre le push GHCR et le commit du tag, dans la chaîne de chaque composant

- Statut : acceptée
- Date : 2026-11-21
- Complète : ADR 0009 (workflow réutilisable par composant, tag sérialisé)
- Source : ticket SQ-14 (`docs/tickets/SQ-14-e2e-compose-ci.md`), décisions D1 à D8 (validées le 21/11/2026) ;
  `docs/cadrage.md` §1.3.12

## Contexte

- Le cadrage (§1.3.12) demande des tests E2E dans la CI, **avant publication** : toute l'application dans Docker Compose,
  Playwright et axe-core, deux scénarios (publier une annonce ; un abonné voit la notification).
- Chaque composant a son workflow, filtré par chemin, qui construit, teste, scanne, pousse l'image sur GHCR (tag SHA) et
  livre (ADR 0009). **Le déploiement se fait par le commit `newTag`** dans `deploy/app/<composant>/`, que Flux applique ;
  une image poussée sur GHCR mais jamais citée par un tag ne tourne nulle part.
- L'image du front a l'URL publique de Keycloak inscrite au build, et sa CSP n'autorise que cette adresse.

## Décision

1. **Un workflow par composant, sans refonte** (D1). Chaîne : build → tests → image → Trivy → push GHCR (tag SHA, sur
   `main`) → **E2E** (`_e2e.yml`, réutilisable) → commit `newTag` **seulement si l'E2E est vert**. L'image testée est
   celle qui est déployée.
2. `_e2e.yml` lance `e2e/compose.yaml` : **le composant testé avec son image**, les deux autres **à leur tag déployé**
   (lu dans `deploy/app/<composant>/kustomization.yaml`). Sur `main`, l'image est tirée de GHCR ; sur une PR (rien n'est
   poussé), elle arrive par un artefact du run (`docker save`, conservé 1 jour).
3. Un workflow `e2e.yml` teste les trois composants à leur tag déployé quand seuls `e2e/` ou `_e2e.yml` changent
   (rien n'est construit ni livré).
4. **Adresses de la prod dans le navigateur** (D2) : une passerelle Traefik porte `epouvante.menuly-app.com` et
   `auth.epouvante.menuly-app.com` sur le réseau de la pile (alias réseau), avec un certificat auto-signé et le routage
   des Ingress (ADR 0006). L'image du front testée est l'image publiée, CSP comprise.
5. **Realm de test** (D3) généré par `e2e/preparer.py` à partir de `deploy/app/keycloak/realm-epouvante.json` (mêmes
   clients et mappers), comptes `e2e-auteur` et `e2e-abonne` ; jamais les comptes de démonstration. Définitions
   RabbitMQ tirées de `deploy/scripts/rabbitmq-definitions.json` (ADR 0026).
6. **Photos** (D4) : `troc-service` en profil `photo-factice`, base des URL réglable
   (`app.photo-factice.url-base`, par défaut `https://photos.invalid/`) ; l'E2E la règle sur `res.cloudinary.com`, seul
   hôte d'images de la CSP, et une route Playwright sert ces URL. Aucun appel au vrai Cloudinary.
7. Parcours (D5) à 375 et 1280 px, axe-core bloquant (0 violation grave ou critique), mesuré après la fin des
   animations ; captures, rapports axe-core et rapport Playwright en artefact du workflow (14 jours).
8. L'E2E tourne sur chaque PR qui touche un composant, `e2e/` ou `_e2e.yml`, et sur `main` avant le tag (D7).

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Workflow unique qui construit les trois images, teste, puis publie | refonte de l'ADR 0009 et des contrôles ; non retenue (D1) |
| `e2e.yml` séparé qui reconstruit les images | image testée différente de l'image déployée ; ne bloque pas la livraison |
| Image du front propre à l'E2E (autre URL de Keycloak) | ne teste pas l'image publiée ni sa CSP |
| `--host-resolver-rules` de Chromium et ports publiés sur l'hôte | même effet que les alias réseau, mais dépend du port 443 libre sur le poste ou le runner |

## Conséquences

- **Changements entre composants** : chaque composant est testé avec les **versions déployées** des autres. Un
  changement qui touche deux composants doit rester compatible avec la version déployée de l'autre (par exemple, le
  back avant le front : nouvelle route d'API livrée d'abord, puis l'écran qui l'utilise) ; sinon l'E2E du composant
  qui dépend de l'autre échoue tant que l'autre n'est pas livré. Règle à appliquer au découpage des PR.
- Sur `main`, une image poussée dont l'E2E échoue reste sur GHCR sans être déployée (aucun tag ne la cite).
- Durée ajoutée à chaque livraison : la pile et les parcours (mesurée dans SQ-14).
- Le contrôle requis par la protection de `main` arrive avec SQ-11, après la migration du dépôt (D6).
- axe-core ne couvre qu'une partie des critères RGAA (audit manuel au plan de remédiation, `cadrage.md` §8).
