# SQ-07 — Prometheus, kube-state-metrics et Grafana

| | |
|---|---|
| Dépend de | SQ-05 (cible RabbitMQ) ; SQ-02 |
| Sources | `cadrage.md` §1.4.16 (stack légère, plugins Grafana désactivés, replicas lus dans kube-state-metrics), §1.4.18 ; rapport exp. 1 §8-9 ; journal exp. 1, « À reprendre au déploiement de `app` » et partie 5, étape A1 bis (« Démo HPA ») |

## But

Toutes les métriques du squelette sont collectées et visibles dans Grafana, par tunnel.

## Contenu

- `deploy/app/prometheus/` : Prometheus 3.15.1 (version de l'exp. 1), 64/256 Mio, collecte toutes les 30 s,
  rétention de 2 jours plafonnée à 200 Mo, sur un `emptyDir`.
  Cibles : actuator des deux services (8081, un pod par cible, découverts par annotations `prometheus.io/*`),
  RabbitMQ (15692, `/metrics` et `/metrics/detailed` pour les messages par file), Keycloak (9000), kube-state-metrics,
  cAdvisor (CPU et RAM des conteneurs de `app`, RAM du nœud), Prometheus lui-même.
  Droits : `Role` dans `app` pour les pods ; `ClusterRole` minimal pour cAdvisor (ADR 0015).
- `deploy/app/kube-state-metrics/` : v2.20.0, 32/64 Mio, limité au namespace `app` et à quatre ressources (`Role`).
- `deploy/app/grafana/` : Grafana 13.3.0 (version de l'exp. 1), 128/320 Mio, plugins préinstallés désactivés ;
  source Prometheus et tableaux de bord « Santé » et « Montée en charge » provisionnés depuis le dépôt ;
  mot de passe administrateur généré sur le VPS (`deploy/scripts/grafana-secrets.sh`), jamais affiché ;
  non exposé par l'Ingress (`ssh -t` + `kubectl port-forward`).
- `deploy/scripts/observabilite-verifier.sh` : vérification des critères d'acceptation, à lancer sur le VPS.
- Les 3 alertes restent pour une phase suivante.

## Décisions (03/11/2026)

| # | Décision | Raison |
|---|---|---|
| 1 | Tableaux de bord « Santé » et « Montée en charge » (`cadrage.md` §1.4.16) à la place de « Squelette », en version squelette : seulement les métriques qui existent déjà. | éviter un tableau de bord jetable ; « Montée en charge » repart de « Démo HPA » (exp. 1). Restent pour les US : latence p95 de `POST /annonces` (indicateur 3, histogrammes à activer dans Micrometer). |
| 2 | cAdvisor collecté par un `ClusterRole` minimal, séries filtrées sur `app` et sur le nœud entier (ADR 0015). | les nœuds ne sont pas des objets d'un namespace : aucun `Role` ne peut y donner accès. |
| 3 | Limite mémoire de Grafana à **320 Mio** au lieu de 256. | 208 Mio de mémoire propre en exp. 1, 286 Mio après quelques heures : risque d'OOM pendant la charge ou la démo. |
| 4 | Keycloak et RabbitMQ collectés par leur Service, les deux services pod par pod (annotations). | un seul pod chacun : pas de redémarrage de Keycloak (≈ 2 min) ni de RabbitMQ (messages en `emptyDir`) pour poser des annotations ; `troc-service` aura jusqu'à 3 replicas, chacun doit être collecté. |

## Limites

- **Historique perdu au redémarrage** : Prometheus stocke ses données dans un `emptyDir` ; toute recréation du pod
  (redémarrage, changement de configuration, mise à jour) efface les séries. Limite assumée du POC (pas de volume
  persistant, RAM et disque du VPS). Conséquence : exporter les graphiques d'une campagne de charge avant tout
  déploiement qui touche Prometheus.
- Base interne de Grafana en `emptyDir` : sans effet, tout est provisionné depuis le dépôt.
- Le front renvoie `index.html` (200) pour toute adresse inconnue : la vérification de `/actuator/prometheus`
  depuis Internet porte sur le contenu (aucune métrique), pas sur le code HTTP.

## Critères d'acceptation

- Toutes les cibles `UP` dans Prometheus.
- Les tableaux de bord « Santé » et « Montée en charge » s'affichent après redémarrage de Grafana, sans configuration manuelle.
- `/actuator/prometheus` toujours inaccessible depuis Internet.
- **Bilan RAM de toute la stack au repos**, comparé au budget de `cadrage.md` §1.4.18 et consigné dans ce ticket.

## Bilan RAM

Avant le déploiement (03/11/2026, `free -m`, `kubectl top`) : 1 049 Mio disponibles, swap 92 Mio ; nœud à 2 559 Mio.

Après le déploiement (03/11/2026, 14:31 UTC, environ 10 min après le démarrage des pods) :

| | Avant | Après |
|---|---|---|
| Disponible (`free -m`) | 1 049 Mio | 803 Mio |
| Nœud (`kubectl top nodes`) | 2 559 Mio | 2 928 Mio |
| Swap utilisé | 92 Mio | 154 Mio |

| Composant | `kubectl top` | Mémoire propre (`anon`) | Pic (`memory.peak`) | Budget au pic (`cadrage.md` §1.4.18) |
|---|---|---|---|---|
| Prometheus (6 469 séries) | 72 Mio | 46 Mio | 117 Mio (démarrage) | 100 Mio |
| Grafana | 262 Mio | 211 Mio | 320 Mio (= limite) | 220 Mio |
| kube-state-metrics | 12 Mio | — (image sans shell) | — | 30 Mio |

- Aucun OOM (`oom_kill 0`) ; dossier des plugins de Grafana vide (4 Ko) : la correction de l'exp. 1 tient.
- **Grafana dépasse son estimation** (262 contre 220 Mio) et son pic atteint la limite de 320 Mio : le pic compte le
  cache de fichiers, récupéré par le noyau sans arrêt du pod. Avec 256 Mio, le risque d'OOM était réel : la décision 3
  est confirmée. À surveiller pendant les campagnes de charge (panneau « RAM par conteneur » de « Santé »).
- Prometheus et kube-state-metrics restent sous leur estimation. Au total, la stack d'observabilité coûte ≈ 350 Mio
  (`kubectl top`), dans le budget de 350 Mio au pic (100 + 220 + 30).

## Vérification (03/11/2026)

- `deploy/scripts/observabilite-verifier.sh` : 0 écart. 8 cibles UP (prometheus, kubernetes-cadvisor,
  kube-state-metrics, troc-service, notification-service, keycloak, rabbitmq, rabbitmq-files) ; tableaux de bord
  `sante` et `montee-en-charge` présents ; aucune métrique lisible depuis Internet (hôte public : page du front ;
  `/api/troc/…` et `/api/notifications/…` : 401).
- Après redémarrage de Grafana : source de données et tableaux de bord provisionnés au démarrage
  (`inserting datasource from configuration`, `finished to provision dashboards`), sans configuration manuelle.
- Requêtes des panneaux contrôlées dans Prometheus : toutes renvoient des données (DLQ à 0, replicas par
  kube-state-metrics, CPU sur `[2m]`, heap, RAM du nœud, redémarrages).
- Corrections après le premier passage : le script lisait mal le code HTTP de l'API de Grafana (`$2` remplacé par le
  shell du pod) ; le panneau « Heap JVM » incluait Keycloak (filtre sur les deux services ajouté).
- Constat : `kubectl rollout restart` est annulé par Flux (l'annotation n'est pas dans le dépôt, correction de dérive) :
  le pod redémarre deux fois. Sous Flux, redémarrer un pod par `kubectl delete pod`.
