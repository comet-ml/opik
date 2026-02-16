FROM python:3.13.12-slim

COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /uvx /bin/

# Update system packages to reduce vulnerabilities
RUN apt-get update \
 && apt-get upgrade -y \
 && apt-get install -y python3-dev default-libmysqlclient-dev build-essential pkg-config git wget \
 && apt-get clean \
 && rm -rf /var/lib/apt/lists/*

WORKDIR /app/

# Install dependencies first (cached unless pyproject.toml or uv.lock change)
COPY pyproject.toml uv.lock .python-version .

RUN uv sync --locked --no-progress --no-dev

RUN adduser --disabled-password --gecos "" myuser && \
    chown -R myuser:myuser /app

# Copy application code (changes frequently, so this layer is last)
COPY src/opik_ai_backend/ /app/opik_ai_backend/

# Pre-compile Python bytecode cache
RUN .venv/bin/python -m compileall /app/opik_ai_backend/

# Set build-time variables last so they don't bust the dependency cache
ARG GIT_TAG
ARG GIT_COMMIT
ARG BUILD_TIME

ENV APP_VERSION=$GIT_TAG
ENV APP_COMMIT=$GIT_COMMIT
ENV APP_BUILD_TIME=$BUILD_TIME

USER myuser

ENV PATH="/home/myuser/.local/bin:$PATH"

ENTRYPOINT [".venv/bin/uvicorn", "--workers", "4", "--host", "0.0.0.0", "--port", "8081"]
CMD ["opik_ai_backend.main:app"]
