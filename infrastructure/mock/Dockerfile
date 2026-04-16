FROM python:3.12-slim

WORKDIR /app

RUN pip install --no-cache-dir paho-mqtt==2.*

COPY mock.py .

CMD ["python", "mock.py"]
