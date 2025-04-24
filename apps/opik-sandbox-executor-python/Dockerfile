FROM python:3.12.9-slim

WORKDIR /opt/opik-sandbox-executor-python

COPY requirements.txt .
RUN pip install -r requirements.txt

RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python
USER 1001:1001
