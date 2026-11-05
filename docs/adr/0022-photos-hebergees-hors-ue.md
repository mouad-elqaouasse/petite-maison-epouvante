# ADR 0022 — Photos hébergées hors de l'UE (Cloudinary) : écart assumé pour le POC

- Statut : acceptée
- Date : 2026-11-05
- Source : `docs/sujet/contexte.md` §2 ; `docs/cadrage.md` §4 (livrable « ADR, dont l'écart Cloudinary hors UE ») et §8 ;
  ADR 0021

## Contexte

- Les photos des annonces sont stockées chez Cloudinary dès US1a (ADR 0021). L'hébergement du compte n'est pas garanti
  dans l'Union européenne ; le VPS, lui, est en Europe (`CLAUDE.md`, Stack).
- Une photo peut contenir des données personnelles : visage, lieu, métadonnées EXIF (dont la position GPS), non
  supprimées en V1 (`cadrage.md` §6).

## Décision

Pour le POC, **Cloudinary est conservé** malgré l'hébergement hors UE. L'écart est documenté ici et repris au plan de
remédiation ; aucune autre donnée de l'application (annonces, comptes, notifications) ne quitte le VPS.

## Alternatives écartées

| Alternative | Raison |
|---|---|
| Stockage sur le VPS (volume persistant) | disque et RAM limités (4 Go), sauvegarde et service des images à construire ; ne démontre pas l'intégration d'un service externe |
| Stockage objet hébergé dans l'UE (S3 compatible) | nouvelle expérimentation et nouveau compte, hors du temps du POC ; option retenue pour la cible |
| Suppression des métadonnées EXIF avant l'envoi | réencodage hors périmètre V1 (`cadrage.md` §6) ; première mesure de remédiation |

## Conséquences

- Plan de remédiation : hébergeur d'images dans l'UE (ou contrat avec garanties de transfert), suppression des EXIF avant
  l'envoi, information des membres sur le traitement de leurs photos.
- Comptes de démonstration seulement en production du POC (`docs/tickets/README.md`) : pas de photos de vrais membres.
