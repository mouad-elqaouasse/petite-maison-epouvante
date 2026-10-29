# Expérience 2 — déclaration de la topologie avec le compte admin (Job unique).
# Les comptes de test n'ont aucun droit « configure » : ils ne peuvent rien déclarer eux-mêmes.
from commun import connexion

conn = connexion()
ch = conn.channel()

ch.exchange_declare("annonces", "topic", durable=True)
ch.exchange_declare("annonces.dlx", "fanout", durable=True)

# File quorum : durable par nature, compte les livraisons (x-delivery-count).
# delivery-limit=3 : 1 livraison + 3 réessais, puis dead-lettering.
# at-least-once : le message n'est retiré de la file qu'une fois confirmé dans la DLQ (exige reject-publish).
ch.queue_declare(
    "notification.annonces",
    durable=True,
    arguments={
        "x-queue-type": "quorum",
        "x-delivery-limit": 3,
        "x-dead-letter-exchange": "annonces.dlx",
        "x-dead-letter-strategy": "at-least-once",
        "x-overflow": "reject-publish",
    },
)
ch.queue_declare("notification.annonces.dlq", durable=True, arguments={"x-queue-type": "quorum"})

ch.queue_bind("notification.annonces", "annonces", "annonce.publiee")
ch.queue_bind("notification.annonces.dlq", "annonces.dlx")

print("topologie déclarée", flush=True)
conn.close()
