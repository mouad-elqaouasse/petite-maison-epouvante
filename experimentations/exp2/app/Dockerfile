# Expérience 2 — image des clients de test (importée dans K3s sans registre : imagePullPolicy Never).
FROM python:3.13-alpine
RUN pip install --no-cache-dir pika==1.3.2 && adduser -D -u 10001 exp2
WORKDIR /app
COPY *.py /app/
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
USER 10001
