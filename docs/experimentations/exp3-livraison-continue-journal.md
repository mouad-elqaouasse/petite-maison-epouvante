# Expérience 3 — Livraison continue (GitHub Actions → GHCR → Flux) : journal

- Date : 2026-10-29 ; namespace `sandbox` (Flux dans `flux-system`) ; budget : une journée maximum
- Fichiers : `experimentations/exp3/`, `.github/workflows/exp3-hello.yml`
- Décisions testées : pipeline et règle « on construit une fois » (`docs/cadrage.md` §1.3.12, §1.3.13), secrets (§1.4.15),
  mode de suivi des images par Flux (§7), RAM de Flux (§1.4.18)

## Protocole (validé le 2026-10-29)
- Application de test : Spring Boot minimal, `GET /` (page « hello » avec le SHA) et `GET /version`
  (`sha`, `secretPresent`) ; un test JUnit ; image non-root étiquetée avec le SHA complet du commit.
- Suivi des images : la CI met à jour le tag dans `experimentations/exp3/deploy/` et commite sur `main` ; Flux lit le dépôt
  public en HTTPS anonyme (aucune clé dans le cluster). Contrôleurs installés : `source-controller`, `kustomize-controller`.
- Pas de boucle : le commit du bot est poussé avec `GITHUB_TOKEN` (ne déclenche aucun workflow) et le filtre de chemins
  exclut `experimentations/exp3/deploy/**` ; vérifié à l'étape 6.
- Permissions du workflow : `contents: write` et `packages: write` seulement.
- Flux dans `flux-system`, refus total par défaut et règles propres (ses NetworkPolicies d'origine remplacées).
- Adresse : `epouvante.menuly-app.com` (certificat de production existant), Ingress de `whoami` retiré, redirection HTTPS + HSTS.
- Paquet GHCR rendu public par le propriétaire du dépôt après le premier push.
- Étapes : 0 vérifications locales ; 1 application (TDD) ; 2 workflow ; 3 Secret ; 4 NetworkPolicies ; 5 Flux ;
  6 bout en bout ; 7 RAM de Flux ; 8 vérification des secrets ; 9 nettoyage ; 10 rapport.

## Étape 0 — Vérifications locales (poste, rien sur le VPS)
Résultat :
```
JDK local 17.0.12 (Temurin) ; Maven, gh, flux, trivy, gitleaks absents ; Docker 29.5.3
Dépôt public, branche main non protégée, aucun workflow existant
Versions retenues : Flux v2.9.6 (v2.10.0 publiée le 26/10, trop récente) ; Spring Boot 4.1.2 ; Java 25
Actions épinglées par SHA : checkout v7.0.1 3d3c42e5…, setup-java v6.1.0 b843ebf9…, login-action v4.7.0 81c6f93d…,
  setup-buildx-action v4.4.1 f87e5991…, build-push-action v7.4.0 c3c9e263…, trivy-action v0.36.0 ed142fd0…
Images d'outillage disponibles : maven:3.9.11-eclipse-temurin-25, gcr.io/distroless/java25-debian13:nonroot,
  ghcr.io/fluxcd/flux-cli:v2.9.6, aquasec/trivy:0.76.1, ghcr.io/gitleaks/gitleaks:v8.30.1
```
Constats :
- Pas de Maven ni de JDK 25 en local : build et tests dans un conteneur (voir étape 1).
- Outils (flux, trivy, gitleaks) lancés par leurs images Docker officielles, sans installation sur le poste.
- `main` non protégée : le commit du bot passe sans contournement ; à revoir quand `main` sera protégée (`docs/cadrage.md` §1.2.19).
- Java 25 retenu aussi pour les services (décision du 2026-10-29, `docs/cadrage.md` §1.4.20) ; Maven Wrapper dans chaque projet.

## Étape 1 — Application de test (TDD), image et scan (poste)
Commandes (Git Bash, `MSYS_NO_PATHCONV=1`, depuis `experimentations/exp3/hello`) :
```bash
docker run --rm -v "$(pwd -W)":/src -v exp3-m2:/root/.m2 -w /src maven:3.9.11-eclipse-temurin-25 \
  mvn -B -q org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dtype=only-script -Dmaven=3.9.11
printf 'FROM eclipse-temurin:25-jdk\nRUN apt-get update -qq && apt-get install -y -qq --no-install-recommends unzip curl && rm -rf /var/lib/apt/lists/*\n' \
  | docker build -q -t exp3-jdk25-local -
docker run --rm -v "$(pwd -W)":/src -v exp3-m2:/root/.m2 -w /src exp3-jdk25-local ./mvnw -B verify
docker build --build-arg GIT_SHA=local-test -t exp3-hello:local .
docker run -d --rm --name exp3-hello -p 18080:8080 -e DEMO_SECRET=xyz --read-only --tmpfs /tmp --memory 256m \
  --cap-drop ALL --security-opt no-new-privileges exp3-hello:local
docker run --rm -v //var/run/docker.sock:/var/run/docker.sock aquasec/trivy:0.76.1 image --severity CRITICAL,HIGH \
  --ignore-unfixed --exit-code 1 exp3-hello:local
```
Résultat :
```
Test d'abord rouge : 2 échecs sur le code HTTP attendu (pas encore de contrôleur) ; puis vert : Tests run: 2, Failures: 0 ; BUILD SUCCESS
/version → {"sha":"local-test","secretPresent":true} ; / → page hello avec le SHA ; valeur du secret absente des réponses
Conteneur en lecture seule, UID 65532, sans capacités : démarrage en 1,6 s, 116 Mio (limite 256 Mio)
Image distroless Debian 13 : 91 Mo ; Trivy : 0 vulnérabilité critique/haute (OS et jar), code de sortie 0
```
Constats :
- **Écart — empreinte du Maven Wrapper** : `mvnw` n'accepte qu'une URL `*-bin.zip`, mais télécharge le `.tar.gz` quand
  `unzip` manque (images `eclipse-temurin` et `maven`). L'empreinte `distributionSha256Sum` (celle du zip, recoupée avec le
  SHA-512 officiel) échoue alors. Solution : garder le zip et son empreinte ; les runners GitHub ont `unzip` ; en local,
  petite image `exp3-jdk25-local` (JDK 25 + `unzip` + `curl`). `eclipse-temurin:25-jdk` seule n'a ni `curl` ni `wget`.
- Le Dockerfile ne compile rien : il copie le jar testé par `./mvnw verify` (« on construit une fois », `docs/cadrage.md` §1.3.12).
- `mvnw` doit être exécutable dans git (`git update-index --chmod=+x`), le poste étant sous Windows.

## Étape 2 — Workflow GitHub Actions et manifests de déploiement (poste)
Fichiers : `.github/workflows/exp3-hello.yml` ; `experimentations/exp3/deploy/` (`kustomization.yaml`, `hello.yaml`,
`ingress.yaml`, `np-hello.yaml`).
Contenu du workflow (un seul job, l'image n'est construite qu'une fois) : `./mvnw verify` → `docker build` (chargée en local)
→ Trivy (`CRITICAL,HIGH`, `--ignore-unfixed`, bloquant) → `docker push` de cette même image (tag = SHA complet) →
`newTag` remplacé dans `deploy/kustomization.yaml`, commit du bot et push → boucle de 10 min sur `https://…/version`.
Vérifications locales :
```bash
docker run --rm -v "$(pwd -W)":/repo -w /repo rhysd/actionlint:1.7.8 -no-color
docker run --rm -v "<copie de deploy/ avec un faux SHA>":/k registry.k8s.io/kustomize/kustomize:v5.7.1 build /k
```
Résultat :
```
actionlint : 1 avertissement shellcheck (SC2034, variable de boucle inutilisée), corrigé ; puis code 0
kustomize : Service, Deployment, Ingress, NetworkPolicy, 2 Middleware, tous dans sandbox ; image …/exp3-hello:<SHA>
```
Constats :
- Permissions : `contents: write` et `packages: write` seulement (les autres passent à `none`) ; aucun secret ajouté,
  GHCR utilise `GITHUB_TOKEN`.
- Pas de boucle, deux protections : push du bot avec `GITHUB_TOKEN` (aucun workflow déclenché par GitHub) et filtre de
  chemins sans `deploy/**` ; à vérifier à l'étape 6 (aucun run après le commit du bot).
- Premier run attendu en échec à la vérification HTTPS : ni Flux ni paquet public à ce moment-là.

### Premier run (push de 4 commits, 15:47 UTC)
Résultat (run 40905115275, `113f355`) :
```
Build et tests 22 s ; image 9 s ; Trivy 32 s (0 critique/haute) ; push GHCR 11 s → étapes réussies jusqu'au push
Commit du bot f5f118d « chore: déploie exp3-hello 113f355 » (github-actions[bot]), seul kustomization.yaml modifié
Vérification HTTPS : échec après 10 min (attendu, Flux absent) → run en échec (15:59:26 UTC)
Runs du dépôt : 1 seul → le commit du bot n'a déclenché aucun workflow (pas de boucle)
Image téléchargeable sans authentification (manifest 200) : paquet déjà public
```
Constats :
- Paquet GHCR public d'emblée : relié au dépôt public par le label `org.opencontainers.image.source`, il en hérite la
  visibilité ; aucune action manuelle nécessaire, donc aucun `imagePullSecret`.
- Chaîne build → push en 1 min 22 s ; la boucle de vérification fixe la durée d'un run en échec (10 min).

## Préparation de Flux (poste)
Fichiers : `experimentations/exp3/flux/` — `10-flux-composants.yaml` (généré par
`flux install --export --version=v2.9.6 --components=source-controller,kustomize-controller --network-policy=false`,
image `ghcr.io/fluxcd/flux-cli:v2.9.6`), `20-np-flux.yaml`, `30-synchro.yaml`, `kustomization.yaml`.
Constats :
- Limites par défaut de Flux : 1 Gio et 1 CPU par contrôleur ; ramenées à 256 Mio / 500m par un patch kustomize
  (`GOMEMLIMIT` suit la limite).
- 6 NetworkPolicies : refus total, DNS, API K3s (IP du nœud, 6443), source-controller → 443 hors cluster,
  kustomize-controller ↔ source-controller (9090). Aucune règle vers GHCR (inutile avec ce mode de suivi).

## Étape 0 bis — État initial du VPS (16:02:54 UTC)
Commande (lecture seule) : `kubectl -n sandbox get pods,ingress,certificate ; kubectl get ns flux-system ; kubectl top … ; free -h`
Résultat :
```
6 pods Running (grafana 298Mi, keycloak 511Mi, postgres 43Mi, prometheus 55Mi, rabbitmq 99Mi, whoami 4Mi)
Ingress keycloak (auth.) et whoami-epouvante (epouvante.) ; 2 certificats Ready ; pas de namespace flux-system
nœud : 2015Mi (52 %) ; free : 1,5 Gio disponibles, swap 81 Mio
```

## Étape 3 — Secret créé à la main
Commande :
```bash
ssh ubuntu@51.178.39.218 'umask 077 && printf "DEMO_SECRET=%s\n" "$(openssl rand -hex 16)" > ~/.env.exp3 && kubectl -n sandbox create secret generic exp3-hello --from-env-file=$HOME/.env.exp3 && rm ~/.env.exp3 && kubectl -n sandbox describe secret exp3-hello'
```
Résultat : `secret/exp3-hello created` ; `DEMO_SECRET: 32 bytes`.
Constats : valeur générée sur le VPS, jamais affichée ni copiée sur le poste ; `.env` supprimé ; le dépôt ne contient
que le nom `exp3-hello` et la clé `DEMO_SECRET` (`deploy/hello.yaml`).

## Étapes 4 et 5 — Retrait de whoami-epouvante, installation de Flux, première synchronisation
Commandes :
```bash
ssh ubuntu@51.178.39.218 'kubectl -n sandbox delete ingress whoami-epouvante'
scp -r experimentations/exp3/flux ubuntu@51.178.39.218:~/exp3-flux
ssh ubuntu@51.178.39.218 'cd ~/exp3-flux && kubectl apply -k . && kubectl -n flux-system rollout status deploy/source-controller deploy/kustomize-controller --timeout=180s && kubectl apply -f 30-synchro.yaml && kubectl -n flux-system wait kustomization/exp3-hello --for=condition=Ready --timeout=300s'
curl -s https://epouvante.menuly-app.com/version   # puis HTTP, en-têtes et certificat
```
Résultat :
```
16:03:39 apply -k (namespace, quota, 2 Deployments, 6 NetworkPolicies) → 16:03:52 contrôleurs disponibles (13 s)
16:03:52 synchro appliquée → 16:04:40 Kustomization Ready (48 s, dont démarrage du pod)
GitRepository : stored artifact for revision 'main@sha1:f5f118d…' ; Kustomization : Applied revision f5f118d
Pod exp3-hello 1/1 Running, 0 redémarrage ; Ingress exp3-hello sur epouvante.menuly-app.com
/version → {"sha":"113f355b14827a89ff112335a9d52f3ef0d4510e","secretPresent":true}
HTTP → 301 vers https ; HTTPS 200 avec Strict-Transport-Security: max-age=31536000 ; certificat Let's Encrypt (YR1) existant
```
Constats :
- Les NetworkPolicies de `flux-system` suffisent du premier coup : clone GitHub, archive entre contrôleurs, API K3s.
- Le SHA affiché est celui du commit testé (`113f355`), pas celui du commit du bot (`f5f118d`) : c'est voulu,
  l'image porte le SHA du code qu'elle contient.
- Certificat `epouvante-tls` repris sans réémission.
