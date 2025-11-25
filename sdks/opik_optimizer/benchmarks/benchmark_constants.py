"""Shared constants for benchmark runners and workers."""

# Default concurrency for Modal runner/worker
DEFAULT_MAX_CONCURRENT = 5

# Worker timeout (seconds)
WORKER_TIMEOUT_SECONDS = 60 * 60 * 12  # 12 hours

# Secret name used to inject Opik/model credentials into Modal workers
MODAL_SECRET_NAME = "opik-benchmarks"
