FROM docker:latest

ENV DOCKER_HOST=unix:///var/run/docker.sock

RUN apk update && apk upgrade \
  && apk add --no-cache \
       python3 python3-dev py3-pip \
       libffi-dev openssl-dev build-base git curl bash \
       cargo gcc musl-dev

WORKDIR /opt/opik-python-backend

COPY requirements.txt .
RUN pip install -r requirements.txt --break-system-packages

COPY src ./src

EXPOSE 8000

CMD dockerd-entrypoint.sh & \
    sleep 5 \
    && gunicorn --workers 4 --bind=0.0.0.0:8000 --chdir ./src 'opik_backend:create_app()'
