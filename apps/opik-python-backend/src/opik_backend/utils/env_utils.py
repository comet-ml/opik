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
    return os.getenv("RQ_WORKER_ENABLED", "false").lower() == "true"
