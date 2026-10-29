# Expérience 2 — producteur : messages numérotés, persistants, publisher confirms, mandatory.
# Mode « lire » : vérifie que le compte producteur ne peut pas lire la file (moindre privilège).
import argparse
import json
import sys
import time

import pika

from commun import connexion

p = argparse.ArgumentParser()
p.add_argument("--run", required=True)
p.add_argument("--nb", type=int, default=100)
p.add_argument("--debit", type=float, default=0, help="messages par seconde, 0 = sans limite")
p.add_argument("--poison", default="", help="identifiants à faire échouer, séparés par des virgules")
p.add_argument("--taille", type=int, default=0, help="octets de remplissage par message")
p.add_argument("--mode", choices=["publier", "lire"], default="publier")
args = p.parse_args()

conn = connexion()
ch = conn.channel()

if args.mode == "lire":
    try:
        ch.basic_get("notification.annonces")
        print("ECART : lecture acceptée pour le producteur", flush=True)
        sys.exit(1)
    except pika.exceptions.ChannelClosedByBroker as e:
        print(f"lecture refusée : {e.reply_code} {e.reply_text}", flush=True)
        sys.exit(0)

poison = {int(i) for i in args.poison.split(",") if i}
ch.confirm_delivery()
confirmes = non_confirmes = 0
debut = time.monotonic()

for i in range(1, args.nb + 1):
    corps = json.dumps({"run": args.run, "id": i, "echec": i in poison, "remplissage": "x" * args.taille})
    props = pika.BasicProperties(delivery_mode=2, message_id=f"{args.run}-{i}", content_type="application/json")
    try:
        # Avec confirm_delivery, basic_publish attend l'ack du broker et lève une exception sinon.
        ch.basic_publish("annonces", "annonce.publiee", corps, props, mandatory=True)
        confirmes += 1
    except (pika.exceptions.UnroutableError, pika.exceptions.NackError) as e:
        non_confirmes += 1
        print(f"NON_CONFIRME run={args.run} id={i} {e!r}", flush=True)
    if args.debit > 0:
        attente = debut + i / args.debit - time.monotonic()
        if attente > 0:
            time.sleep(attente)
    if i % 100 == 0:
        print(f"progression {i}/{args.nb} ({time.monotonic() - debut:.1f} s)", flush=True)

print(
    f"BILAN_PRODUCTEUR run={args.run} envoyes={args.nb} confirmes={confirmes} "
    f"non_confirmes={non_confirmes} duree_s={time.monotonic() - debut:.1f}",
    flush=True,
)
conn.close()
