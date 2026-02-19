# Multi-stage build: Build stage
FROM python:3.12.12-slim AS builder

WORKDIR /build

# Copy requirements and install dependencies
COPY requirements.txt .
COPY scoring_runner.py .

RUN pip install --no-cache-dir -r requirements.txt

# Completely remove pip and all package management tools
RUN pip uninstall -y pip setuptools wheel ensurepip

# Compile scoring_runner.py to bytecode for optimized execution
RUN PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b ./scoring_runner.py

# Use PYTHONNODEBUGRANGES=1 to reduce memory overhead (Python 3.12+)
# Use -b flag for legacy layout = fastest execution (no subdirectory traversal)
RUN PYTHONNODEBUGRANGES=1 python -m compileall -o 2 -b /usr/local/lib/python3.12/site-packages

# Remove .py files from site-packages to reduce package size and improve cold starts
# Note: This trades detailed tracebacks for performance (keeping .pyc files only)
RUN find /usr/local/lib/python3.12/site-packages -name "*.py" -delete

# Pre-warm essential modules used in scoring (build-time)
RUN echo "import sys, json, time, traceback, uuid, inspect, opik; from types import ModuleType; print('✅ Essential modules cached')" > /tmp/prewarm.py && python /tmp/prewarm.py

# Keep prewarm.py for runtime stage and compile it
RUN cp /tmp/prewarm.py /prewarm.py
RUN python -m compileall -o 2 -b /prewarm.py

# Run build-time test
RUN printf 'print("build test passed")' | python ./scoring_runner.pyc 'print("build test")' '{"test": true}' || echo "Warning: scoring_runner.pyc build validation failed - compiled bytecode may be corrupted or dependencies may be missing"

# Copy site-packages to runtime location
RUN cp -r /usr/local/lib/python3.12/site-packages /runtime-python

#######################################################################
# Multi-stage build: Runtime stage
FROM python:3.12.12-slim AS runtime

WORKDIR /opt/opik-sandbox-executor-python

# Clear any existing site-packages from base image
RUN rm -rf /usr/local/lib/python3.12/site-packages/*

# Copy artifacts from builder
COPY --from=builder /runtime-python /usr/local/lib/python3.12/site-packages/

# Copy optimized .pyc file for fastest execution (legacy layout with -b flag)
COPY --from=builder /build/scoring_runner.pyc ./scoring_runner.pyc
COPY --from=builder /prewarm.pyc /tmp/prewarm.pyc

# Runtime pre-warming for additional performance boost
RUN python /tmp/prewarm.pyc

# Runtime verification test
RUN printf 'print("runtime test passed")' | python ./scoring_runner.pyc 'print("runtime test")' '{"test": true}' || echo "Warning: runtime validation failed - scoring_runner.pyc may not be properly configured or dependencies may be missing"

# Set environment variable for reduced memory overhead (Python 3.12+)
ENV PYTHONNODEBUGRANGES=1

# Set ownership and user
RUN chown -R 1001:1001 /opt/opik-sandbox-executor-python
USER 1001:1001

# Use .pyc file for optimized execution
CMD ["python", "/opt/opik-sandbox-executor-python/scoring_runner.pyc"]
