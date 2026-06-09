# syntax=docker/dockerfile:1
#######################################################################
# Multi-stage build: Build stage
#######################################################################
# NOTE: pin by digest for reproducible, tamper-evident builds. The tag
# below is multi-arch; to pin, replace with the per-arch manifest digest:
#   FROM python:3.12.13-slim@sha256:<digest> AS builder
FROM python:3.12.13-slim AS builder

WORKDIR /build

# Install dependencies FIRST, in their own layer keyed only on requirements.txt.
# scoring_runner.py is copied afterwards so editing it does NOT bust the
# (slow) pip-install cache layer.
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Slim site-packages, then compile to bytecode. Done as a single layer so the
# intermediate bloat (source .py, metadata, stubs) never lands in a snapshot.
#   -o 2  : strip asserts + docstrings (equivalent to python -OO)
#   -b    : legacy layout (.pyc next to nothing) = fastest import, no
#           __pycache__ subdir traversal
# PYTHONNODEBUGRANGES=1 drops the 3.12 fine-grained traceback tables to cut
# both bytecode size and per-process memory.
RUN set -eux; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b /usr/local/lib/python3.12/site-packages; \
    # Drop source now that bytecode exists; keep only the compiled .pyc files.
    find /usr/local/lib/python3.12/site-packages -name '*.py' -delete; \
    # Remove leftover __pycache__ dirs (we use legacy -b layout, so these are
    # stale duplicates) and type stubs (never imported at runtime).
    # NOTE: *.dist-info is intentionally KEPT — litellm/opik/tiktoken read their
    # own version via importlib.metadata at import time, which needs METADATA.
    find /usr/local/lib/python3.12/site-packages -type d -name '__pycache__' -prune -exec rm -rf {} +; \
    find /usr/local/lib/python3.12/site-packages -name '*.pyi' -delete; \
    # Remove pip and friends entirely: the sandbox must not install packages.
    pip uninstall -y pip setuptools wheel ensurepip || true; \
    rm -rf /usr/local/lib/python3.12/site-packages/pip* \
           /usr/local/lib/python3.12/site-packages/setuptools* \
           /usr/local/lib/python3.12/site-packages/wheel* \
           /usr/local/lib/python3.12/site-packages/ensurepip*

# Compile the scoring runner and the prewarm script (also bytecode-only).
COPY scoring_runner.py .
RUN set -eux; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b ./scoring_runner.py; \
    # Pre-warm + persist the essential-module preload used by the runtime stage.
    printf '%s' "import sys, json, time, traceback, uuid, inspect, opik; from types import ModuleType; print('essential modules cached')" > /prewarm.py; \
    PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b /prewarm.py

# Build-time validation: FAIL the build if the compiled runner is broken,
# rather than baking a silently-broken image.
RUN printf 'print("build test passed")' \
    | python ./scoring_runner.pyc 'print("build test")' '{"test": true}'

#######################################################################
# Multi-stage build: Runtime stage
#######################################################################
FROM python:3.12.13-slim AS runtime

WORKDIR /opt/opik-sandbox-executor-python

# LITELLM_MODE: prevent litellm calling load_dotenv() at import time. It uses
#   frame inspection that fails in a .pyc-only context (after .py deletion).
#   The default "DEV" triggers the call; any other value suppresses it.
# PYTHONNODEBUGRANGES: reduce 3.12 memory overhead (matches build compile flag).
# PYTHONDONTWRITEBYTECODE: never write .pyc at runtime; we ship bytecode-only.
ENV LITELLM_MODE=PRODUCTION \
    PYTHONNODEBUGRANGES=1 \
    PYTHONDONTWRITEBYTECODE=1

# Replace the base image's site-packages with our slimmed, bytecode-only set.
RUN rm -rf /usr/local/lib/python3.12/site-packages/*
COPY --from=builder /usr/local/lib/python3.12/site-packages /usr/local/lib/python3.12/site-packages/

# Optimized .pyc artifacts (legacy layout, fastest execution).
COPY --from=builder /build/scoring_runner.pyc ./scoring_runner.pyc
COPY --from=builder /prewarm.pyc /tmp/prewarm.pyc

# Runtime pre-warming + validation. Pre-warm primes the import cache; the
# validation run fails the build if the artifact is unusable in this stage.
RUN python /tmp/prewarm.pyc \
    && printf 'print("runtime test passed")' \
       | python ./scoring_runner.pyc 'print("runtime test")' '{"test": true}'

# Drop privileges: the sandbox executes untrusted user code.
RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python
USER 1001:1001

CMD ["python", "/opt/opik-sandbox-executor-python/scoring_runner.pyc"]
