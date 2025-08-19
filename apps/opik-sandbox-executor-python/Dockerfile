FROM python:3.12.9-slim

WORKDIR /opt/opik-sandbox-executor-python

# Copy requirements and install dependencies
COPY requirements.txt .
COPY scoring_runner.py .

RUN pip install --no-cache-dir -r requirements.txt

RUN pip uninstall -y setuptools wheel || true

RUN PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b ./scoring_runner.py

# Use PYTHONNODEBUGRANGES=1 to reduce memory overhead (Python 3.12+)
# Use -b flag for legacy layout = fastest execution (no subdirectory traversal)
RUN PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b /usr/local/lib/python3.12/site-packages

# Pre-warm essential modules used in scoring (build-time)
RUN echo "import sys, json, time, traceback, uuid, inspect, opik; from types import ModuleType; print('✅ Essential modules cached')" > /tmp/prewarm.py && python /tmp/prewarm.py

# Keep prewarm.py for runtime stage
RUN python /tmp/prewarm.py

# Runtime verification test
RUN printf 'print("runtime test passed")' | python ./scoring_runner.py 'print("runtime test")' '{"test": true}' || echo "Warning: runtime test failed"

# Set environment variable for reduced memory overhead (Python 3.12+)
ENV PYTHONNODEBUGRANGES=1

# Set ownership and user
RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python
USER 1001:1001

CMD ["python", "/opt/opik-sandbox-executor-python/scoring_runner.py"]
