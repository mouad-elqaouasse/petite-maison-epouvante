"""SQ-14 — Prépare la pile E2E (e2e/compose.yaml) dans e2e/genere/ (ignoré par git).

- Certificat auto-signé pour epouvante.menuly-app.com et auth.epouvante.menuly-app.com (passerelle Traefik, D2).
- Realm Keycloak de test : client et mappers de deploy/app/keycloak/realm-epouvante.json (une seule source), comptes de
  test e2e-auteur et e2e-abonne à la place des comptes de démonstration (D3).
- Définitions RabbitMQ : deploy/scripts/rabbitmq-definitions.json (ADR 0026), hachés des mots de passe de test.
- .env : images à tester (variables IMAGE_TROC, IMAGE_NOTIFICATION, IMAGE_FRONT si déjà définies, sinon le tag
  déployé, lu dans deploy/app/<composant>/kustomization.yaml).

Valeurs de test seulement, propres à la pile E2E : jamais celles du cluster.
Usage : python3 e2e/preparer.py
"""

import base64
import hashlib
import json
import os
import re
import secrets
import shutil
import subprocess
from pathlib import Path

RACINE = Path(__file__).resolve().parent.parent
GENERE = RACINE / "e2e" / "genere"
HOTES = ["epouvante.menuly-app.com", "auth.epouvante.menuly-app.com"]
REGISTRE = "ghcr.io/mouad-elqaouasse/petite-maison-epouvante"
COMPOSANTS = {"IMAGE_TROC": "troc-service", "IMAGE_NOTIFICATION": "notification-service", "IMAGE_FRONT": "front"}

# Comptes de test du realm E2E : mot de passe = nom d'utilisateur, comme le realm de test des services.
COMPTES = ["e2e-auteur", "e2e-abonne"]
# Mots de passe de test des services, seulement dans la pile E2E.
MDP = {"troc": "troc-e2e", "notification": "notification-e2e"}


def certificat():
    noms = ",".join(f"DNS:{h}" for h in HOTES)
    subprocess.run(
        ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
         "-keyout", str(GENERE / "cle.pem"), "-out", str(GENERE / "certificat.pem"),
         "-subj", "/CN=epouvante-e2e", "-addext", f"subjectAltName={noms}"],
        check=True, capture_output=True)


def realm():
    source = json.loads((RACINE / "deploy/app/keycloak/realm-epouvante.json").read_text(encoding="utf-8"))
    source["users"] = [{
        "username": nom,
        "enabled": True,
        "email": f"{nom}@example.com",
        "emailVerified": True,
        "firstName": nom,
        "lastName": "E2E",
        "realmRoles": ["USER"],
        "requiredActions": [],
        "credentials": [{"type": "password", "value": nom, "temporary": False}],
    } for nom in COMPTES]
    (GENERE / "realm-epouvante.json").write_text(json.dumps(source, ensure_ascii=False), encoding="utf-8")


# Format rabbit_password_hashing_sha256, comme deploy/scripts/rabbitmq-secrets.sh.
def hache(mdp):
    sel = secrets.token_bytes(4)
    return base64.b64encode(sel + hashlib.sha256(sel + mdp.encode()).digest()).decode()


def definitions():
    modele = (RACINE / "deploy/scripts/rabbitmq-definitions.json").read_text(encoding="utf-8")
    texte = modele.replace("__HACHE_TROC__", hache(MDP["troc"])).replace(
        "__HACHE_NOTIFICATION__", hache(MDP["notification"]))
    assert "__HACHE_" not in texte
    (GENERE / "definitions.json").write_text(texte, encoding="utf-8")


def tag_deploye(composant):
    texte = (RACINE / f"deploy/app/{composant}/kustomization.yaml").read_text(encoding="utf-8")
    return re.search(r'newTag:\s*"?([0-9a-f]{40})"?', texte).group(1)


def env():
    lignes = []
    for variable, composant in COMPOSANTS.items():
        image = os.environ.get(variable) or f"{REGISTRE}/{composant}:{tag_deploye(composant)}"
        lignes.append(f"{variable}={image}")
    lignes += [f"MDP_TROC={MDP['troc']}", f"MDP_NOTIFICATION={MDP['notification']}"]
    (GENERE / ".env").write_text("\n".join(lignes) + "\n", encoding="utf-8")
    print("\n".join(l for l in lignes if l.startswith("IMAGE_")))


if __name__ == "__main__":
    shutil.rmtree(GENERE, ignore_errors=True)
    GENERE.mkdir(parents=True)
    certificat()
    realm()
    definitions()
    env()
