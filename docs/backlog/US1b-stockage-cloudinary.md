# US1b — Stocker les photos sur Cloudinary

| | |
|---|---|
| Statut | **Fusionnée dans US1a le 05/11/2026** (décision du Lead Developer : Cloudinary intégré dès la première story) |
| Service | `troc-service` |
| Voir | `docs/backlog/US1a-publier-annonce.md`, section « Stockage sur Cloudinary » ; ADR 0021 et 0022 |

## Ce qui a changé

- Les scénarios de cette story (stockage, octets envoyés tels quels, photo invalide jamais envoyée, erreur et lenteur de
  Cloudinary, suppression si l'enregistrement échoue, clé lue depuis le secret, mode factice désactivé par défaut) sont
  repris **à l'identique** dans US1a, avec les mêmes noms de tests.
- Le repli « `StockagePhoto` en mémoire en production » (`cadrage.md` §5, Should) disparaît : la fausse implémentation
  (profil Spring `photo-factice`) ne sert qu'aux tests et aux campagnes de charge.
- Stratégie de test inchangée (décision du 27/10/2026) : fausse implémentation dans tous les tests de l'application,
  adaptateur testé seul contre WireMock, **aucun appel au vrai Cloudinary en CI**.

## Reste pour le plan de remédiation

Photos hébergées hors UE (ADR 0022), métadonnées EXIF non supprimées, dépendance externe (quota, latence,
disponibilité) — `cadrage.md` §8.
