FROM nvidia/cuda:12.2.2-cudnn8-runtime-ubuntu22.04

# Set environment variables
ENV PYTHONUNBUFFERED=1 \
    DEBIAN_FRONTEND=noninteractive \
    TZ=UTC \
    OPIK_GUARDRAILS_DEVICE=cuda:0

# Install Python and other dependencies
#
# security.ubuntu.com is a pool of independently-syncing mirrors behind one name.
# While a security update is publishing, a mirror can advertise the new version in
# its index before it holds the matching .deb, so `apt-get update` plans an install
# of a version that the following download then 404s on. jammy-security supplying a
# newer libexpat1 than jammy-updates is what drags this package into a plain
# python3.10 install. It is a transient publication race, not a stale index: the
# base image's index is discarded by `apt-get update` above, and the mirrors
# converge within hours. Bumping the base image does NOT prevent it.
# Retrying is the fix: the pool is re-entered on each attempt, and the window
# where any given mirror is inconsistent is short.
#
# DL3008: Ubuntu apt packages track a rolling security channel; pinning exact
# versions here would rot as the base image's repos are updated.
# hadolint ignore=DL3008
#
# Every attempt is bounded so a stalled mirror cannot consume the calling CI job's
# 15-minute budget.
#
# Acquire::*::Timeout is an INACTIVITY timeout, not a cap on total download time:
# apt rebuilds the select() timeval on each read, so the timer resets whenever data
# arrives (methods/http.cc, HttpServerState::Go). 20s therefore aborts a silent or
# black-holed connection without penalising a slow but steadily progressing mirror;
# it only lowers apt's 120s default, which is too coarse to detect a stall in time.
#
# The `timeout` wrappers, by contrast, ARE hard wall-clock caps, so they are sized
# well above a normal run (the install completes in well under a minute) to leave a
# slow mirror room rather than killing it. DEADLINE caps the loop overall so retries
# stop once the budget is spent. Worst case is ~9 min, which still leaves room for the
# pip install and model downloads that follow within the caller's 15-minute job limit.
RUN DEADLINE=$(( $(date +%s) + 240 )); \
    for attempt in 1 2 3 4 5; do \
        timeout 120 apt-get update \
            -o Acquire::http::Timeout=20 \
            -o Acquire::https::Timeout=20 \
        && timeout 420 apt-get install -y --no-install-recommends \
            -o Acquire::Retries=3 \
            -o Acquire::http::Timeout=20 \
            -o Acquire::https::Timeout=20 \
            python3.10 \
            python3-pip \
            python3-dev \
            tini \
            wget \
        && break; \
        if [ "$attempt" = "5" ]; then echo "apt-get failed after $attempt attempts" >&2; exit 1; fi; \
        if [ "$(date +%s)" -ge "$DEADLINE" ]; then \
            echo "apt-get retry budget exhausted after $attempt attempts" >&2; exit 1; \
        fi; \
        echo "apt-get attempt $attempt failed; retrying" >&2; \
        rm -rf /var/lib/apt/lists/*; \
        sleep "$((attempt * 5))"; \
    done \
    && rm -rf /var/lib/apt/lists/* && apt-get clean

# Set Python aliases
RUN ln -sf /usr/bin/python3.10 /usr/bin/python && \
    ln -sf /usr/bin/python3.10 /usr/bin/python3

# uv is a drop-in faster pip whose resolver already handles the pinned
# torch/transformers/hf_xet wheels, so the pip>=24 bootstrap is no longer needed.
# The binary (pinned, from the official distroless image) is installed system-wide,
# used for the one install below, then removed so it doesn't linger in the image.
COPY --from=ghcr.io/astral-sh/uv:0.11.29 /uv /usr/local/bin/uv

WORKDIR /opt/opik-guardrails-backend

COPY requirements.txt .
RUN uv pip install --python /usr/bin/python3.10 --no-cache -r requirements.txt && \
    rm -f /usr/local/bin/uv

COPY entrypoint.sh .
RUN chmod u+x entrypoint.sh

# Download the cache with the final user to ensure that files are in the right
# place and readable by the user
RUN mkdir /.cache/ /.local/ && chown -R 1001:1001 /opt/opik-guardrails-backend /.cache/ /.local/
USER 1001:1001

# Download models with the final user so files land readable by the user:
#   - spaCy en_core_web_lg for PII detection
#   - transformer bart-large-mnli for restricted topic validation
# HF_HUB_DISABLE_XET=1 forces standard HTTP fallback; the Xet protocol
# used by huggingface_hub[hf_xet] is blocked in most CI/Docker build environments.
RUN python -m spacy download en_core_web_lg && \
    HF_HUB_DISABLE_XET=1 python -c "from transformers import AutoModelForSequenceClassification, AutoTokenizer; AutoModelForSequenceClassification.from_pretrained('facebook/bart-large-mnli'); AutoTokenizer.from_pretrained('facebook/bart-large-mnli')"

COPY --chown=1001:1001 opik_guardrails ./opik_guardrails

ARG OPIK_VERSION
ENV OPIK_VERSION="${OPIK_VERSION}"

# Set CUDA related environment variables
ENV NVIDIA_VISIBLE_DEVICES=all \
    NVIDIA_DRIVER_CAPABILITIES=compute,utility \
    TORCH_CUDA_ARCH_LIST="7.0;7.5;8.0;8.6;9.0"

EXPOSE 5000

ENTRYPOINT ["tini", "--"]
CMD ["./entrypoint.sh"]
