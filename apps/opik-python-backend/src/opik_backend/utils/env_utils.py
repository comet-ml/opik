import logging
import os

logger = logging.getLogger(__name__)


def get_env_int(name: str, default: int) -> int:
    """Get an integer environment variable with fallback on invalid values.

    Args:
        name: Environment variable name
        default: Default value if env var is missing or invalid

    Returns:
        Parsed integer value or default
    """
    raw = os.getenv(name)
    if raw is None:
        return default

    try:
        return int(raw.strip())
    except (ValueError, TypeError):
        logger.warning(f"Invalid value for {name}='{raw}', using default {default}")
        return default


def is_rq_worker_enabled() -> bool:
    # Default on: running the Optimization Studio job worker is the
    # python-backend's primary role. Deployments that want an executor-only
    # instance (no Redis) can opt out with RQ_WORKER_ENABLED=false.
    return os.getenv("RQ_WORKER_ENABLED", "true").lower() == "true"
