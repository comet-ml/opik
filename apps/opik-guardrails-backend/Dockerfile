FROM python:3.10-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    tini \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /opt/opik-guardrails-backend

COPY requirements.txt .
RUN pip install -r requirements.txt

COPY src ./src

COPY entrypoint.sh .
RUN chmod u+x entrypoint.sh

EXPOSE 5000

ARG OPIK_VERSION
ENV OPIK_VERSION="${OPIK_VERSION}"

ENTRYPOINT ["tini", "--"]
CMD ./entrypoint.sh
