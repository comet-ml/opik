import os


def opik_home() -> str:
    """Root directory for Opik local state (~/.opik by default, override with OPIK_HOME)."""
    return os.environ.get("OPIK_HOME", os.path.expanduser("~/.opik"))


def agents_file() -> str:
    """Path to the agents registry file."""
    return os.path.join(opik_home(), "agents.json")


def runner_state_file() -> str:
    """Path to the file tracking the active runner process."""
    return os.path.join(opik_home(), "runner.json")


def ensure_opik_home() -> None:
    """Create the Opik home directory if it doesn't exist."""
    os.makedirs(opik_home(), exist_ok=True)


# How long the main loop sleeps when no jobs are available or the pool is full.
POLL_IDLE_INTERVAL_SECONDS = 0.5

# Interval between heartbeat calls that register agents and check for cancellations.
DEFAULT_HEARTBEAT_INTERVAL_SECONDS = 5.0

# Upper bound on exponential backoff delay after repeated polling errors.
DEFAULT_BACKOFF_CAP_SECONDS = 30.0

# Starting delay for exponential backoff after a polling error.
DEFAULT_BACKOFF_INITIAL_SECONDS = 1.0

# Fallback thread pool size when max_workers is not specified and os.cpu_count() is unavailable.
DEFAULT_MAX_WORKERS = 4

# Max agent process stdout/stderr log entries buffered before flushing to the API.
LOG_BATCH_SIZE = 50

# Max time between agent process log flushes, even if the buffer is not full.
LOG_FLUSH_INTERVAL_SECONDS = 0.5
