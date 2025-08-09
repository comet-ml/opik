FROM python:3.12.9-slim

WORKDIR /opt/opik-sandbox-executor-python

# Install system dependencies including Rust for tiktoken compilation
RUN apt-get update && apt-get install -y \
    curl \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install Rust using rustup to get the latest stable version that supports edition2024
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
ENV PATH="/root/.cargo/bin:${PATH}"

# Step 1: Install dependencies
COPY requirements.txt .
COPY scoring_runner.py .

RUN pip install --no-cache-dir -r requirements.txt

# Step 2: Copy optimized scoring runner
RUN chmod +x ./scoring_runner.py

# Step 3: Minimal .pyc compilation
RUN python -m compileall -q ./scoring_runner.py

# Step 4: Pre-warm modules used by local imports (build-time caching for runtime efficiency)
# These modules are imported locally in scoring_runner.py but benefit from pre-compilation
RUN echo "import sys, json, time, traceback, uuid, inspect, opik; from types import ModuleType; print('✅ Essential modules cached')" > /tmp/prewarm.py && python /tmp/prewarm.py && rm /tmp/prewarm.py

# Step 5: Test execution
RUN printf 'print("scoring test passed")' | python ./scoring_runner.py 'print("minimal test")' '{"test": true}' || echo "Warning: scoring test failed"

# Step 6: Set ownership for security
RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python 
USER 1001:1001

# Default command
CMD ["python", "/opt/opik-sandbox-executor-python/scoring_runner.py"]
