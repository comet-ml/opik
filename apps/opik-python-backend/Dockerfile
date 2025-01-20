FROM docker:27.5.0

RUN apk add --no-cache tini python3 py3-pip

WORKDIR /opt/opik-python-backend

COPY requirements.txt .
RUN pip install -r requirements.txt --break-system-packages

COPY src ./src

COPY entrypoint.sh .
RUN chmod u+x entrypoint.sh

EXPOSE 8000

ENV DOCKER_HOST=unix:///var/run/docker.sock

ENV TINI_SUBREAPER=""

ENV PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY=ghcr.io/comet-ml/opik
ENV PYTHON_CODE_EXECUTOR_IMAGE_NAME=opik-sandbox-executor-python
ENV PYTHON_CODE_EXECUTOR_IMAGE_TAG=latest

ENTRYPOINT ["tini", "--"]
CMD ./entrypoint.sh
