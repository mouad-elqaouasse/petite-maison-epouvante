# ADR 0001 — Conserver cert-manager et les ClusterIssuers Let's Encrypt pour `app`

- Statut : acceptée
- Date : 2026-10-28
- Source des mesures : `docs/experimentations/exp1-installation-k3s-journal.md`, partie 3

## Contexte

- HTTPS fait partie de la solution de sécurité minimale demandée (`docs/sujet/consignes.md`, « 1. Travail demandé » : « HTTPS,
  authentification/autorisation, scan de vulnérabilités ») et du périmètre Must du POC (`docs/cadrage.md` §5,
  « HTTPS : Traefik + cert-manager + Let's Encrypt »).
- L'expérience 1, partie 3, a installé cert-manager v1.21.3 sur le cluster K3s du VPS, par le `HelmChart` de K3s
  (`experimentations/exp1/60-cert-manager.yaml`), avec deux `ClusterIssuer` ACME en validation HTTP-01 via Traefik
  (`letsencrypt-staging`, `letsencrypt-prod`, `experimentations/exp1/61-cluster-issuers.yaml`).
- Résultat : certificats de production émis pour `epouvante.menuly-app.com` et `auth.epouvante.menuly-app.com`
  en moins de 30 s, renouvellement planifié automatiquement (certificats de 90 jours, renouvelés à 30 jours de
  l'expiration).
- Le namespace `sandbox` est supprimé après usage (`docs/cadrage.md` §3). cert-manager, son `HelmChart`
  (`kube-system`), ses CRD, les `ClusterIssuer` et les clés des comptes ACME (namespace `cert-manager`) sont hors
  de `sandbox` : il faut décider explicitement de leur sort.
- RAM mesurée (`kubectl top`, limites de test 256Mi par pod) :

| Pod | Au repos | Après 4 émissions |
|---|---|---|
| contrôleur | 21 Mio | 42 Mio |
| cainjector | 17 Mio | 17 Mio |
| webhook | 14 Mio | 17 Mio |
| **Total** | **52 Mio** | **76 Mio** |

## Décision

1. **cert-manager reste installé** sur le cluster après l'expérience 1 et sert l'environnement `app` :
   même version figée (v1.21.3), même mode d'installation (`HelmChart` de K3s), namespace `cert-manager`.
2. **Les deux `ClusterIssuer` sont conservés.** Les Ingress de `app` utilisent `letsencrypt-prod` par l'annotation
   `cert-manager.io/cluster-issuer`. Tout nouveau nom de domaine passe d'abord par `letsencrypt-staging`.
3. **Limites mémoire ramenées** des valeurs larges du test aux valeurs suivantes, à reporter dans les valeurs du chart :

| Pod | requests mémoire | limits mémoire |
|---|---|---|
| contrôleur | 32Mi | 96Mi à 128Mi |
| cainjector | 32Mi | 64Mi |
| webhook | 32Mi | 64Mi |

   Le contrôleur reçoit la marge la plus large : sa mémoire augmente avec le nombre de certificats et de Secrets
   qu'il surveille (21 → 42 Mio après 4 émissions). La valeur définitive dans la fourchette sera fixée à
   l'écriture des manifests de `app`, après une nouvelle mesure avec les certificats de `app`.
   CPU inchangé (requests 10m).

## Alternatives étudiées

| Alternative | Pourquoi elle n'est pas retenue |
|---|---|
| Supprimer cert-manager avec le bac à sable, puis le réinstaller pour `app` | Même résultat final, avec un travail en double ; nouveaux comptes ACME ; aucun gain de RAM puisque `app` en a besoin. |
| ACME intégré à Traefik (`certResolver`), sans cert-manager | Pas de composant supplémentaire, mais les certificats sont stockés dans un fichier `acme.json` propre au pod Traefik (volume persistant à prévoir, invisible pour Kubernetes), et l'expérience menée et validée porte sur cert-manager, cité comme exemple d'expérimentation par `docs/sujet/consignes.md` §1.2 (« cert-manager pour des certificats sécurisant un Ingress »). |
| Certificat obtenu à la main (certbot sur l'hôte) et copié dans un Secret | Renouvellement hors du cluster (tâche planifiée à maintenir, Secret à mettre à jour à la main) : contraire à l'automatisation recherchée. |

## Conséquences

- **RAM** : ≈ 76 Mio au repos après émissions, au plus 256 Mio en limits cumulées (128 + 64 + 64). Coût faible au
  regard de la marge mesurée (1,4 Gio disponible avec toute la stack de la partie 2).
- **Nettoyage** : la suppression de `sandbox` ne touche pas cert-manager. Le Secret TLS de test disparaît avec
  `sandbox` ; les certificats de `app` seront réémis pour les mêmes noms, ce qui compte dans le quota de
  Let's Encrypt (5 certificats identiques par semaine) : rester loin de ce seuil en évitant les réémissions inutiles.
- **Secrets** : les clés des comptes ACME restent dans des Secrets du namespace `cert-manager`, générées sur le
  cluster, jamais dans le dépôt.
- **Réseau** : le port 80 doit rester ouvert (UFW) pour la validation HTTP-01, y compris lors des renouvellements.
  La future redirection HTTP → HTTPS ne doit pas empêcher la validation : à vérifier lors de sa mise en place.
- **Limites connues** : pas de certificat wildcard (il faudrait la validation DNS-01) ; renouvellement réel non
  observé pendant l'expérience (premier renouvellement prévu le 2026-12-27) ; mises à jour de cert-manager manuelles,
  la version étant figée dans le `HelmChart`.
- **Suite** : les manifests de cert-manager et des `ClusterIssuer` quitteront `experimentations/exp1/` pour le
  répertoire de déploiement lors de la mise en place de la livraison continue (expérience 3).
