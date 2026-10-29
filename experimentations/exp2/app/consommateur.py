# Expérience 2 — consommateur : ack après traitement ; un message « echec » est rejeté avec remise en file
# (reject, requeue) et c'est le broker qui compte les livraisons et l'envoie en DLQ.
# Depuis RabbitMQ 4.3, basic.nack ne compte pas comme un échec (delivery-count inchangé) : seul basic.reject
# (ou la perte de la connexion) fait avancer le compteur comparé à x-delivery-limit (journal, étape 3).
# Une ligne de log par message : les logs servent de preuve (identifiants traités, doublons).
import json
import os
import time

from commun import connexion

traitement = float(os.environ.get("TRAITEMENT_MS", "10")) / 1000

conn = connexion()
ch = conn.channel()
ch.basic_qos(prefetch_count=int(os.environ.get("PREFETCH", "10")))


def recu(ch, methode, props, corps):
    msg = json.loads(corps)
    livraisons = (props.headers or {}).get("x-delivery-count", 0)
    time.sleep(traitement)
    trace = f"run={msg['run']} id={msg['id']} x-delivery-count={livraisons} redelivered={methode.redelivered}"
    if msg.get("echec"):
        print(f"ECHEC {trace}", flush=True)
        ch.basic_reject(methode.delivery_tag, requeue=True)
        return
    print(f"TRAITE {trace}", flush=True)
    ch.basic_ack(methode.delivery_tag)


ch.basic_consume("notification.annonces", recu)
print("consommateur prêt", flush=True)
ch.start_consuming()
