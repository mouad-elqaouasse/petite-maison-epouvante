# ADR 0007 — Actuator sur un port de gestion séparé

- Statut : acceptée
- Date : 2026-10-30
- Source : `docs/tickets/README.md` (décision D2), ticket SQ-01

## Contexte

- `/actuator/prometheus` ne doit pas être exposé par l'Ingress (`docs/cadrage.md` §1.4.16).
- Les sondes Kubernetes et Prometheus doivent pourtant joindre l'actuator de chaque pod.
- Les NetworkPolicies ouvriront les flux par port (ADR 0004).

## Décision

Dans `troc-service` et `notification-service` :

- API sur le port 8080, seul port cité par les Ingress ;
- actuator sur le port **8081** (`management.server.port`) : `health` (groupes `liveness`, `readiness`), `info`, `prometheus` ;
- sondes Kubernetes et collecte Prometheus sur 8081 ; ce port n'est cité par aucun Ingress.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Actuator sur le port de l'API, filtré dans Traefik | une erreur de règle suffit à exposer les métriques ; filtrage à maintenir pour chaque Ingress |
| Actuator sur le même port, protégé par Spring Security | les sondes et Prometheus auraient besoin d'identifiants ; configuration de sécurité plus complexe |

## Conséquences

- Exposition impossible par erreur de chemin : seul le port 8080 est atteignable depuis Traefik.
- La NetworkPolicy « Traefik → services » n'ouvre que 8080 ; « Prometheus → cibles » n'ouvre que 8081.
- Les tests d'intégration démarrent le serveur de gestion sur un port aléatoire distinct.
