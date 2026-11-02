#!/usr/bin/env bash
# SQ-05 — Vérifie les droits des comptes RabbitMQ de app (docs/cadrage.md §1.2.10), à lancer sur le VPS.
# Prérequis : image exp2-amqp:2 (clients pika, experimentations/exp2/app) importée dans containerd de K3s, puis
# supprimée après le test (docker build, docker save | ssh … 'sudo k3s ctr images import -').
# Pod éphémère, mots de passe lus dans les Secrets par variables d'environnement, jamais affichés.
# Attendu : troc publie (confirmé) mais ne lit pas ; notification lit et acquitte mais ne publie pas (403).
set -euo pipefail
NS=app

kubectl -n "$NS" apply -f - <<'EOF'
apiVersion: v1
kind: Pod
metadata:
  name: rabbitmq-verifier-droits
  labels:
    app: rabbitmq-verifier-droits
spec:
  restartPolicy: Never
  automountServiceAccountToken: false
  securityContext:
    runAsNonRoot: true
    runAsUser: 10001
    runAsGroup: 10001
    seccompProfile:
      type: RuntimeDefault
  containers:
    - name: verifier
      image: exp2-amqp:2
      imagePullPolicy: Never
      env:
        - name: MDP_TROC
          valueFrom:
            secretKeyRef:
              name: troc-rabbitmq
              key: password
        - name: MDP_NOTIFICATION
          valueFrom:
            secretKeyRef:
              name: notification-rabbitmq
              key: password
      command:
        - python
        - -c
        - |
          import os, sys, pika
          from commun import connexion
          FILE, ECHANGE, CLE = "notification.annonce-publiee", "annonces", "annonce.publiee"
          ecarts = 0

          def refuse(nom, action):
              global ecarts
              try:
                  action()
                  print(f"ECART : {nom} accepté", flush=True); ecarts += 1
              except pika.exceptions.ChannelClosedByBroker as e:
                  print(f"OK : {nom} refusé ({e.reply_code} {e.reply_text})", flush=True)

          troc = connexion("troc", os.environ["MDP_TROC"])
          ch = troc.channel(); ch.confirm_delivery()
          ch.basic_publish(ECHANGE, CLE, b'{"verification":"SQ-05"}',
                           pika.BasicProperties(delivery_mode=2), mandatory=True)
          print("OK : troc publie sur annonces (confirmé, routé)", flush=True)
          refuse("troc lit la file", lambda: troc.channel().basic_get(FILE))
          troc.close()

          notif = connexion("notification", os.environ["MDP_NOTIFICATION"])
          ch = notif.channel()
          methode, _, corps = ch.basic_get(FILE)
          if methode is None:
              print("ECART : notification ne trouve pas le message", flush=True); ecarts += 1
          else:
              ch.basic_ack(methode.delivery_tag)
              print(f"OK : notification lit et acquitte {corps.decode()}", flush=True)
          ch.basic_get(FILE + ".dlq")
          print("OK : notification lit la DLQ", flush=True)

          def publier():
              c = notif.channel(); c.confirm_delivery()
              c.basic_publish(ECHANGE, CLE, b"{}", mandatory=True)
          refuse("notification publie sur annonces", publier)
          notif.close()
          sys.exit(1 if ecarts else 0)
      resources:
        requests:
          cpu: 20m
          memory: 32Mi
        limits:
          cpu: 200m
          memory: 64Mi
      securityContext:
        allowPrivilegeEscalation: false
        readOnlyRootFilesystem: true
        capabilities:
          drop: ["ALL"]
EOF

kubectl -n "$NS" wait --for=jsonpath='{.status.phase}'=Succeeded pod/rabbitmq-verifier-droits --timeout=60s || true
kubectl -n "$NS" logs pod/rabbitmq-verifier-droits
kubectl -n "$NS" get pod rabbitmq-verifier-droits -o jsonpath='{.status.phase}{"\n"}'
kubectl -n "$NS" delete pod rabbitmq-verifier-droits --wait=false
