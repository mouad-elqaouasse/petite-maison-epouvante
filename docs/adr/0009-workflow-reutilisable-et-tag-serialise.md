# ADR 0009 — Workflow réutilisable par composant et mise à jour sérialisée du tag

- Statut : acceptée
- Date : 2026-10-30
- Source : `docs/tickets/README.md` (décision D4), tickets SQ-01 et SQ-02 ; rapport exp. 3 §8-9

## Contexte

- Monorepo, workflows filtrés par chemin, images taguées avec le SHA (`docs/cadrage.md` §1.4.19).
- La CI écrit le tag dans `deploy/` et le commite ; Flux lit le dépôt (exp. 3, `docs/cadrage.md` §1.4.19).
- Avec plusieurs composants, deux workflows peuvent se terminer en même temps et pousser chacun un commit de tag :
  le second push est refusé (branche en retard).
- `troc-service` et `notification-service` ont la même chaîne (Maven Wrapper, Java 25, image distroless).

## Décision

1. Un **workflow réutilisable** `_service-java.yml` (`workflow_call`) porte la chaîne des services Java :
   tests, Trivy fs, build de l'image, Trivy image, publication GHCR (sur `main`), mise à jour du tag, vérification en ligne.
   Il reçoit le nom du composant et l'URL de vérification en paramètres.
2. Un workflow par composant (`troc-service.yml`, `notification-service.yml`, `front.yml`), filtré sur son répertoire.
3. Le job qui écrit le tag appartient à un **groupe de concurrence commun** (`concurrency: deploy-tag`, sans annulation) :
   un seul s'exécute à la fois ; il fait `git pull --rebase` juste avant de commiter et de pousser.
4. Le commit du tag ne modifie que `deploy/app/<composant>/kustomization.yaml` (`images[].newTag`).

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Un workflow complet copié par composant | duplication ; une correction de sécurité (version de Trivy épinglée) à reporter à plusieurs endroits |
| Un seul workflow pour tout le dépôt | reconstruit et redéploie tous les composants à chaque commit ; plus long, plus de commits de tag |
| Réessai du push en boucle sans sérialisation | fonctionne le plus souvent, mais comportement non déterministe et difficile à expliquer |

## Conséquences

- Deux livraisons simultanées sont livrées l'une après l'autre (critère d'acceptation de SQ-02).
- Le front a sa propre chaîne (Node), mais réutilise le même job de tag sérialisé.
- Si `main` ne peut pas recevoir le commit du bot une fois protégée, la cible du tag change (ticket SQ-11, option
  « branche `deploy` ») sans changer ce découpage.
