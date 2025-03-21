FROM docker:27.5.1

RUN apk add --no-cache tini python3 py3-pip rust cargo

WORKDIR /opt/opik-python-backend

COPY requirements.txt .
RUN pip install -r requirements.txt --break-system-packages

ENV PYTHON_CODE_EXECUTOR_ASSET_NAME="opik-sandbox-executor-python"
# Optionally copies the file. It's built ok without it, as it'll be pulled before running anyway.
COPY *${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz /images/${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz

COPY src ./src

COPY entrypoint.sh demo_data_entrypoint.sh ./
RUN chmod u+x entrypoint.sh demo_data_entrypoint.sh

EXPOSE 8000

ENV DOCKER_HOST="unix:///var/run/docker.sock"

ENV TINI_SUBREAPER=""

ARG OPIK_VERSION
ENV PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY="ghcr.io/comet-ml/opik"
ENV PYTHON_CODE_EXECUTOR_IMAGE_NAME="opik-sandbox-executor-python"
ENV PYTHON_CODE_EXECUTOR_IMAGE_TAG="${OPIK_VERSION}"
ENV CREATE_DEMO_DATA="true"

ENTRYPOINT ["tini", "--"]
CMD ./entrypoint.sh
