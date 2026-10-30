# ADR 0010 — Comptes et topologie RabbitMQ chargés au démarrage du broker

- Statut : acceptée
- Date : 2026-10-30
- Source : `docs/tickets/README.md` (décision D5), ticket SQ-05 ; rapport exp. 2 §7-8

## Contexte

- Un compte par service au moindre privilège (`docs/cadrage.md` §1.2.10) ; file quorum avec `x-delivery-limit=3` et DLQ
  (§1.2.8).
- Expérience 2 : comptes et topologie créés par un script et un Job ; stockage `emptyDir`, donc perdus si le pod
  RabbitMQ est recréé (rapport exp. 2, Limites).
- Les services ne doivent pas avoir le droit de créer la topologie (moindre privilège).
- Les mots de passe ne vont jamais dans le dépôt (`docs/cadrage.md` §1.4.15).

## Décision

1. RabbitMQ charge au démarrage un fichier de définitions (`load_definitions`) : vhost, exchange `annonces`,
   file quorum `notification.annonce-publiee` (`x-delivery-limit=3`, dead-letter vers la DLQ), DLQ, comptes `troc`
   et `notification` avec leurs permissions ; pas de compte `guest`.
2. Le fichier contient les **hachés** des mots de passe : il est produit **sur le VPS** par un script du dépôt
   (`deploy/scripts/`), à partir de mots de passe générés sur place, et placé dans un Secret monté par le pod.
3. Les mots de passe en clair des services sont dans `troc-rabbitmq` et `notification-rabbitmq`, lus par les services.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Job ou script lancé après chaque démarrage (comme l'exp. 2) | à relancer après chaque redémarrage du pod ; fenêtre pendant laquelle les services ne peuvent pas se connecter |
| Topologie déclarée par les services (Spring AMQP) | les comptes des services auraient le droit `configure` : contraire au moindre privilège |
| Volume persistant pour RabbitMQ | garde les comptes, mais ne rend pas la topologie déclarative ; RAM et disque en plus |

## Conséquences

- Après une recréation du pod, comptes et topologie sont identiques, sans geste manuel (critère d'acceptation de SQ-05).
- La structure des définitions (sans les hachés) est versionnée et relue en PR ; le Secret est régénéré si elle change.
- Les messages restent perdus si le pod est recréé (stockage `emptyDir`) : limite inchangée, au plan de remédiation.
