# Expérience 2 — connexion commune aux scripts de test.
# Sous refus total, les premières connexions d'un pod peuvent être refusées environ 1 s (exp. 1, partie 5, étape B8) :
# on réessaie au lieu d'échouer.
import os
import sys
import time

import pika


def connexion(utilisateur=None, mot_de_passe=None, essais=10):
    params = pika.ConnectionParameters(
        host=os.environ.get("RABBITMQ_HOTE", "rabbitmq"),
        port=5672,
        credentials=pika.PlainCredentials(
            utilisateur or os.environ["RABBITMQ_UTILISATEUR"],
            mot_de_passe or os.environ["RABBITMQ_MOT_DE_PASSE"],
        ),
        heartbeat=30,
    )
    for essai in range(1, essais + 1):
        try:
            return pika.BlockingConnection(params)
        except pika.exceptions.AMQPConnectionError as e:
            print(f"connexion impossible (essai {essai}/{essais}) : {e!r}", flush=True)
            time.sleep(1)
    sys.exit("RabbitMQ injoignable")
