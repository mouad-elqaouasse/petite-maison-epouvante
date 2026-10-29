# ADR 0005 — Budget RAM et HPA : troc-service de 1 à 3 replicas, notification-service fixé à 1

- Statut : acceptée
- Date : 2026-10-29
- Source des mesures : `docs/experimentations/exp1-socle-k3s-rapport.md` (parties 1, 2, 5A et §9) ;
  RabbitMQ : `docs/experimentations/exp2-rabbitmq-rapport.md` (§5, §9 ; mise à jour du 2026-10-29) ;
  Flux : `docs/experimentations/exp3-livraison-continue-rapport.md` (§5, §9 ; mise à jour du 2026-10-29)

## Contexte

- La montée en charge doit être démontrée (`docs/sujet/consignes.md` §1.2.3 ; `docs/sujet/grille.md`, critère P3).
- Le cadrage prévoyait `troc-service` 1 → 3 et `notification-service` 1 → 2, pour ≈ 600 Mio de système + K3s
  (`docs/cadrage.md` §1.4.17 et §1.4.18, version du 27/10/2026).
- Expérience 1 : système + K3s ≈ 1 230 Mio ; Keycloak 549 à 590 Mio ; Grafana 208 Mio de mémoire propre ; 1,4 à 1,6 Gio
  disponibles avec toute la stack au repos, pour 3 788 Mio utilisables. Les deux services ne peuvent pas être au maximum
  en même temps.

## Décision

1. **HPA de `troc-service` : 1 → 3 replicas** (le service exposé à la charge de la démonstration).
2. **`notification-service` fixé à 1 replica**, sans HPA.
3. Budget au pic estimé à ≈ 3 600 Mio (≈ 3 700 Mio avant l'expérience 3), détaillé dans `docs/cadrage.md` §1.4.18 ;
   requests et limits fixées pour chaque pod. **Flux : 2 contrôleurs à 64 / 256 Mio, 100 Mio au pic** au lieu de 200 Mio
   estimés (mise à jour du 2026-10-29, expérience 3 : ≈ 50 Mio au repos).
   **RabbitMQ : 128 / 256 Mio, 130 Mio au pic, sonde de disponibilité `tcpSocket` sur le port 5672** (mise à jour du
   2026-10-29, expérience 2) : la sonde `rabbitmq-diagnostics` lance une seconde VM Erlang dans le conteneur
   (+60 Mio toutes les 20 s, pic observé à 270 Mio avec une commande `rabbitmqctl`), ce qui dépasserait la limite.
   `rabbitmqctl` est réservé au diagnostic ponctuel.
4. **Plan B** : si la marge mesurée pendant les tests de charge est insuffisante (swap qui monte, ou pod tué par manque de
   mémoire, `OOMKilled`), le HPA de `troc-service` passe à **2 replicas maximum**.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Garder `notification-service` 1 → 2 | ≈ 250 Mio de plus au pic : dépasse la RAM du VPS |
| HPA plafonné à 2 dès le départ | montée en charge moins visible en soutenance ; gardé en plan B |
| VPS plus grand | coût ; la contrainte de 4 Go fait partie du cadrage |

## Conséquences

- Marge d'environ 190 Mio au pic (90 Mio avant l'expérience 3), le swap de 2 Go servant de tampon : la RAM disponible et le swap sont suivis dans Grafana
  pendant chaque campagne de charge.
- La consommation des notifications ne monte pas en charge : la file RabbitMQ peut s'allonger sous charge ; c'est sa
  vidange qui est montrée en soutenance (`docs/cadrage.md` §1.4.17).
- Estimation à confirmer : JVM Spring Boot (≈ 250 Mio par replica). RabbitMQ confirmé par l'expérience 2 (95 Mio au
  repos, 126 Mio avec 10 000 messages en attente) ; Flux par l'expérience 3 (≈ 50 Mio au repos, ≤ 100 Mio au pic, à
  remesurer avec les manifests de `app`).
- Une sonde `tcpSocket` vérifie seulement que le port AMQP répond, pas la santé interne du broker : écart accepté pour
  le POC, la santé du broker restant suivie par son plugin Prometheus.
