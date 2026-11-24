# Campagnes de charge (JMeter)

Plan validé le 24/11/2026 : campagnes C0 à C3 sur l'environnement `app`, depuis le poste de dev
(`docs/cadrage.md` §1.4.17 ; décisions D2 à D8 et D10). Le p95 se lit dans le rapport HTML de JMeter.

| Fichier | Rôle |
|---|---|
| `plan-charge.jmx` | 80 % `GET /api/troc/annonces`, 20 % `POST /api/troc/annonces` avec photo, temps de réflexion de 1 à 3 s |
| `photo-charge.png` | photo de test de 200 Ko (bruit, aucune donnée personnelle), régénérée par `node load/generer-photo.js` |
| `lancer.sh` | une campagne en mode non graphique et son rapport HTML dans `load/resultats/` (ignoré par git) |
| `keycloak-client-charge.sh` | client Keycloak temporaire `charge-jmeter` (D5) : `creer`, `verifier`, `supprimer` |
| `donnees-charge.sh` | sauvegarde, 20 abonnés fictifs par catégorie, comptage, nettoyage (D7) |

## Déroulé

Chaque étape qui modifie le VPS est validée par le Lead Developer avant d'être lancée.

1. **Sauvegarde, puis abonnés fictifs** (D7) :
   `ssh ubuntu@51.178.39.218 'bash -s -- sauvegarder' < load/donnees-charge.sh`, puis `… -- semer`.
2. **Client Keycloak** (D5) : `ssh ubuntu@51.178.39.218 'bash -s -- creer' < load/keycloak-client-charge.sh`.
3. **Secret du client sur le poste**, sans l'afficher (fichier ignoré par git) :
   ```bash
   printf 'client_secret=%s\n' "$(ssh ubuntu@51.178.39.218 "kubectl -n app get secret charge-jmeter -o jsonpath='{.data.client-secret}' | base64 -d")" > load/charge.properties
   ```
4. **Faux Cloudinary** (D6) : PR qui active le profil `photo-factice` sur `troc-service`, fusionnée puis vérifiée
   (Flux, pod prêt).
5. **Campagnes** (ne jamais lancer JMeter sur le VPS) :
   - C0, test de fumée : `load/lancer.sh 1 60 0` → aucune erreur, annonces et notifications créées (`… -- compter`) ;
   - C1, paliers : `load/lancer.sh 5`, puis 10, 20, 40 ; N = dernier palier conforme (p95 < 500 ms, 5xx < 1 %,
     DLQ à 0, RAM disponible ≥ 250 Mio) ;
   - C2, HPA : palier qui fait monter `troc-service`, puis arrêt : montée, descente après 300 s, file vidée ;
   - C3, suppression d'un pod `troc-service` sous charge, avec au moins 2 replicas (jamais PostgreSQL).
   Pendant chaque campagne, dans un autre terminal : `ssh ubuntu@51.178.39.218 'watch -n 5 free -m'`.
   **Arrêt immédiat** (D2) si la RAM disponible passe sous 250 Mio, si le swap augmente de plus de 100 Mio ou si un pod
   est `OOMKilled` ; plan B : HPA à 2 replicas au maximum (ADR 0005).
   Captures Grafana « Montée en charge » et « Santé » juste après chaque campagne (historique en `emptyDir`).
6. **Retour à la normale** : PR qui retire le profil `photo-factice` ; `CONFIRMER=oui … -- nettoyer`
   (`donnees-charge.sh`, irréversible, avec l'accord du Lead Developer), puis `… -- supprimer`
   (`keycloak-client-charge.sh`) ; supprimer `load/charge.properties`.

## Limites

- Annonces de charge visibles dans la liste publique pendant les campagnes, avec une photo cassée
  (URL `photos.invalid`, bloquée par la CSP) ; les membres abonnés de démonstration reçoivent aussi des alertes.
  Tout est supprimé au nettoyage.
- Un seul compte (le compte de service) publie toutes les annonces : la charge porte sur l'API, pas sur Keycloak.
- Latence mesurée depuis le poste : elle inclut le réseau domestique et TLS (Transport Layer Security).
