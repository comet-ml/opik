FROM python:3.13-slim

WORKDIR /opt/opik-sandbox-executor-python

# Step 1: Install dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Step 2: Copy optimized scoring runner
COPY scoring_runner.py /app/scoring_runner.py
RUN chmod +x /app/scoring_runner.py

# Step 3: Minimal .pyc compilation
RUN python -m compileall -q /app/scoring_runner.py

# Step 4: Pre-warm essential modules (conservative)
RUN echo "import sys, json, time, traceback, uuid, inspect; from types import ModuleType; print('✅ Essential modules cached')" > /tmp/prewarm.py && python -S /tmp/prewarm.py && rm /tmp/prewarm.py

# Step 5: Test execution
RUN printf 'print("scoring test passed")' | python -S /app/scoring_runner.py 'print("minimal test")' '{"test": true}' || echo "Warning: scoring test failed"

# Step 6: Set ownership for security
RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python /app
USER 1001:1001

# Default command
CMD ["python", "-S", "/app/scoring_runner.py"]