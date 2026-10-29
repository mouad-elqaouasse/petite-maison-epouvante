# Expérience 2 — vide la DLQ en affichant chaque message et ses en-têtes de dead-lettering (compte consommateur).
import json

from commun import connexion

conn = connexion()
ch = conn.channel()
nb = 0
while True:
    methode, props, corps = ch.basic_get("notification.annonces.dlq")
    if methode is None:
        break
    nb += 1
    msg = json.loads(corps)
    entetes = props.headers or {}
    print(
        f"DLQ run={msg['run']} id={msg['id']} x-delivery-count={entetes.get('x-delivery-count')} "
        f"x-first-death-reason={entetes.get('x-first-death-reason')} x-death={entetes.get('x-death')}",
        flush=True,
    )
    ch.basic_ack(methode.delivery_tag)
print(f"BILAN_DLQ messages={nb}", flush=True)
conn.close()
