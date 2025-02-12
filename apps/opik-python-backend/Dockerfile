FROM alpine:3.21.2 AS build

RUN apk add --no-cache curl jq

WORKDIR /opt/opik-python-backend

COPY pull_executor_images.sh .
RUN chmod u+x pull_executor_images.sh

ENV PYTHON_CODE_EXECUTOR_ASSET_REPOSITORY="comet-ml/opik"
ENV PYTHON_CODE_EXECUTOR_ASSET_NAME="opik-sandbox-executor-python_image.tar.gz"
ENV PYTHON_CODE_EXECUTOR_ASSERT_TAG="latest"

RUN ./pull_executor_images.sh

FROM docker:27.5.1

RUN apk add --no-cache tini python3 py3-pip

WORKDIR /opt/opik-python-backend

ENV PYTHON_CODE_EXECUTOR_ASSET_NAME="opik-sandbox-executor-python_image.tar.gz"

COPY --from=build /opt/opik-python-backend/images/$PYTHON_CODE_EXECUTOR_ASSET_NAME ./images/

COPY requirements.txt .
RUN pip install -r requirements.txt --break-system-packages

COPY src ./src

COPY entrypoint.sh .
RUN chmod u+x entrypoint.sh

EXPOSE 8000

ENV DOCKER_HOST="unix:///var/run/docker.sock"

ENV TINI_SUBREAPER=""

ENV PYTHON_CODE_EXECUTOR_IMAGE_REGISTRY="ghcr.io/comet-ml/opik"
ENV PYTHON_CODE_EXECUTOR_IMAGE_NAME="opik-sandbox-executor-python"
ENV PYTHON_CODE_EXECUTOR_IMAGE_TAG="latest"

ENTRYPOINT ["tini", "--"]
CMD ./entrypoint.sh
