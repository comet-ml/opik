FROM python:3.12.9-slim

WORKDIR /opt/opik-sandbox-executor-python

COPY requirements.txt .
COPY scoring_runner.py .

RUN pip install --no-cache-dir -r requirements.txt

RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python
USER 1001:1001
