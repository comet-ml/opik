# syntax=docker/dockerfile:1
#######################################################################
# Multi-stage build: Build stage
#######################################################################
FROM python:3.12.13-slim AS builder

# uv is a drop-in faster pip; the binary is copied from the official distroless
# image (pinned) and used only in this builder stage — it never reaches runtime.
COPY --from=ghcr.io/astral-sh/uv:0.11.29 /uv /usr/local/bin/uv

WORKDIR /build

# Install deps first so editing scoring_runner.py doesn't bust this cache layer.
COPY requirements.txt .
RUN uv pip install --system --no-cache -r requirements.txt

# Compile to bytecode (-o 2 strips asserts/docstrings, -b = fastest legacy
# layout), drop source/stubs, and remove package managers from the sandbox.
# uv installs no pip; the rm below removes the base image's bundled pip/setuptools/wheel.
# *.dist-info is kept: litellm/opik/tiktoken read it via importlib.metadata.
RUN set -eux; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b /usr/local/lib/python3.12/site-packages; \
    find /usr/local/lib/python3.12/site-packages -name '*.py' -delete; \
    find /usr/local/lib/python3.12/site-packages -type d -name '__pycache__' -prune -exec rm -rf {} +; \
    find /usr/local/lib/python3.12/site-packages -name '*.pyi' -delete; \
    rm -rf /usr/local/lib/python3.12/site-packages/pip* \
           /usr/local/lib/python3.12/site-packages/setuptools* \
           /usr/local/lib/python3.12/site-packages/wheel* \
           /usr/local/lib/python3.12/site-packages/ensurepip*

# Compile the runner and a prewarm script that primes essential imports.
COPY scoring_runner.py .
RUN set -eux; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b ./scoring_runner.py; \
    printf '%s' "import sys, json, time, traceback, uuid, inspect, opik; from types import ModuleType; print('essential modules cached')" > /prewarm.py; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b /prewarm.py

# Fail the build if the compiled runner can't score a real metric end-to-end.
COPY selftest.sh /usr/local/bin/selftest.sh
RUN sh /usr/local/bin/selftest.sh ./scoring_runner.pyc

#######################################################################
# Multi-stage build: Runtime stage
#######################################################################
FROM python:3.12.13-slim AS runtime

WORKDIR /opt/opik-sandbox-executor-python

# LITELLM_MODE: avoid load_dotenv() frame inspection that breaks .pyc-only mode.
# PYTHONNODEBUGRANGES: cut 3.12 memory overhead (matches the build compile flag).
# PYTHONDONTWRITEBYTECODE: ship bytecode-only, never write .pyc at runtime.
ENV LITELLM_MODE=PRODUCTION \
    PYTHONNODEBUGRANGES=1 \
    PYTHONDONTWRITEBYTECODE=1

# Replace the base site-packages with the slimmed, bytecode-only set.
RUN rm -rf /usr/local/lib/python3.12/site-packages/*
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages/

COPY --from=builder /build/scoring_runner.pyc ./scoring_runner.pyc
COPY --from=builder /prewarm.pyc /tmp/prewarm.pyc
COPY --from=builder /usr/local/bin/selftest.sh /usr/local/bin/selftest.sh

# Bake stdlib bytecode at build time. The slimming above only touched
# site-packages; the stdlib under /usr/local/lib/python3.12 keeps its source
# with no __pycache__. The runtime CMD runs as user 1001 with
# PYTHONDONTWRITEBYTECODE=1, so without this the cold per-request process
# recompiles inspect/typing/ast/re/... from source on EVERY invocation and
# cannot cache the result (root-owned dir + DONTWRITEBYTECODE). Compile at
# opt-level 0 so the cached .pyc matches the runtime optimization level.
RUN python -m compileall -q /usr/local/lib/python3.12 || true

# Prewarm imports, then fail the build if the runner can't score in this stage.
RUN python /tmp/prewarm.pyc \
    && sh /usr/local/bin/selftest.sh ./scoring_runner.pyc

# Drop privileges: the sandbox executes untrusted user code.
RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python
USER 1001:1001

CMD ["python", "/opt/opik-sandbox-executor-python/scoring_runner.pyc"]
