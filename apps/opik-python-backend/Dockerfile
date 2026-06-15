# syntax=docker/dockerfile:1
#######################################################################
# Multi-stage build: Build stage
#######################################################################
FROM docker:29.5.3 AS builder

# Toolchain only for compiling native wheels (rust/cargo for pydantic-core).
RUN apk add --no-cache python3 py3-pip build-base python3-dev musl-dev gcc libffi-dev rust cargo

WORKDIR /opt/opik-python-backend

# Install deps into a venv that we copy whole into the runtime stage.
COPY requirements.txt .
RUN python3 -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
RUN pip install --no-cache-dir -r requirements.txt

# Bytecode-only deps (-o 2, -b = fastest layout); strip source/stubs + package
# managers. src/ keeps its .py; *.dist-info kept for importlib.metadata.
# datasets + opik_optimizer keep their .py: both call inspect.getsource() at
# import time (datasets' packaged-module registry; opik_optimizer SDK-version
# probe), which raises OSError on a .pyc-only layout and breaks optimizer jobs.
RUN set -eux; \
    PY_SITE="$(/opt/venv/bin/python -c 'import sysconfig; print(sysconfig.get_path("purelib"))')"; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b "$PY_SITE"; \
    find "$PY_SITE" \
         -path "$PY_SITE/datasets/*" -prune -o \
         -path "$PY_SITE/opik_optimizer/*" -prune -o \
         -name '*.py' -type f -print | xargs -r rm -f; \
    find "$PY_SITE" -type d -name '__pycache__' -prune -exec rm -rf {} +; \
    find "$PY_SITE" -name '*.pyi' -delete; \
    pip uninstall -y pip setuptools wheel || true; \
    rm -rf "$PY_SITE"/pip* "$PY_SITE"/setuptools* "$PY_SITE"/wheel* "$PY_SITE"/ensurepip*

#######################################################################
# Multi-stage build: Runtime stage
#######################################################################
FROM docker:29.5.3

# Upgrade libexpat to the latest available version to mitigate CVEs.
RUN apk add --no-cache --upgrade libexpat
# Runtime-only OS deps: tini (PID 1 reaper) + python3 interpreter.
RUN apk add --no-cache tini python3

WORKDIR /opt/opik-python-backend

# Slimmed, bytecode-only venv from the builder.
COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# LITELLM_MODE: avoid load_dotenv() frame inspection that breaks .pyc-only deps.
# PYTHONNODEBUGRANGES: cut 3.12 memory overhead (matches the build compile flag).
# PYTHONDONTWRITEBYTECODE: ship bytecode-only, never write .pyc at runtime.
ENV LITELLM_MODE=PRODUCTION \
    PYTHONNODEBUGRANGES=1 \
    PYTHONDONTWRITEBYTECODE=1

ENV PYTHON_CODE_EXECUTOR_ASSET_NAME="opik-sandbox-executor-python"
# Optional: copied if present, otherwise pulled at runtime before first use.
COPY *${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz ./images/${PYTHON_CODE_EXECUTOR_ASSET_NAME}.tar.gz

# App source — kept as .py (subprocess scripts + from_pyfile config).
COPY src ./src

COPY entrypoint.sh demo_data_entrypoint.sh ./
RUN chmod u+x entrypoint.sh demo_data_entrypoint.sh

EXPOSE 8000

# Runs as root: needs the Docker socket to spawn sandbox-executor containers.
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
