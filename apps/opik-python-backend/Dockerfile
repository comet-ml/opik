# Multi-stage build to reduce final image size
FROM docker:28.4.0 AS builder

# Build dependencies (only needed during pip install)
RUN apk add --no-cache tini python3 py3-pip build-base python3-dev musl-dev gcc libffi-dev && \
    apk add --no-cache rust cargo

WORKDIR /opt/opik-python-backend
COPY requirements.txt .

# Install Python packages in virtual environment for easy copying
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir -r requirements.txt

##############################################################
# Runtime stage - minimal packages only
FROM docker:29.2.1

# First upgrading to latest available versions to mitigate CVEs
RUN apk add --no-cache --upgrade libexpat

# Only runtime dependencies needed
RUN apk add --no-cache tini python3

WORKDIR /opt/opik-python-backend

# Copy the virtual environment from builder stage
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

ENV PYTHON_CODE_EXECUTOR_ASSET_NAME="opik-sandbox-executor-python"
# Optionally copies the file. It's built ok without it, as it'll be pulled before running anyway.
COPY *${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz ./images/${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz

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
ENV PYTHON_CODE_EXECUTOR_PARALLEL_NUM=5
ENV PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS=3
ENV PYTHON_CODE_EXECUTOR_STRATEGY="docker"
ENV PYTHON_CODE_EXECUTOR_ALLOW_NETWORK=false

ENV OPIK_VERSION=${OPIK_VERSION}

ENTRYPOINT ["tini", "--"]

CMD ["./entrypoint.sh"]
