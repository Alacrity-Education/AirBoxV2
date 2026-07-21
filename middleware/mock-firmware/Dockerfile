FROM python:3.12-alpine

RUN adduser -S -D -H app
WORKDIR /app

COPY publish_random.py ./

USER app

ENV AIRBOX_ENDPOINT="https://ingest.airbox.alacrity.ro/api/v2/submit" \
    AIRBOX_INTERVAL_SECONDS="10" \
    AIRBOX_REQUEST_COUNT="0" \
    AIRBOX_AUTH_STYLE="random" \
    AIRBOX_GEOHASHES="sxft17ek,sxfscg0b,sxfsd565,sxfs8jdh"

ENTRYPOINT ["python", "publish_random.py"]
