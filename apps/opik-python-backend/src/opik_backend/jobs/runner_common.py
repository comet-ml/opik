"""Common environment setup for optimizer runner subprocesses.

Both optimizer_runner.py and framework_runner.py run as isolated
subprocesses and share the same bootstrapping steps: terminal sizing,
logging configuration, and noisy-library suppression.  This module
centralises that logic so each runner only contains its own business
logic.
"""

import logging
import os
import sys
import warnings

TERMINAL_WIDTH = int(os.environ.get("OPTSTUDIO_LOG_TERM_WIDTH", "150"))


def setup_runner_environment() -> None:
    """Configure environment, logging, and warnings for a runner subprocess.

    * Sets terminal dimension env vars (COLUMNS, LINES, FORCE_COLOR, TERM)
      so Rich reads them at import time.
    * Configures ``logging.basicConfig`` to write to stderr.
    * Suppresses noisy third-party loggers (httpx, LiteLLM, etc.).
    * Filters Pydantic serialisation warnings emitted by LiteLLM.
    """
    os.environ["COLUMNS"] = str(TERMINAL_WIDTH)
    os.environ["LINES"] = "50"
    os.environ["FORCE_COLOR"] = "1"
    os.environ["TERM"] = "xterm-256color"

    log_level = os.environ.get("OPTSTUDIO_LOG_LEVEL", "INFO").upper()
    logging.basicConfig(
        level=getattr(logging, log_level, logging.INFO),
        format="%(asctime)s [%(levelname)s] %(name)s: %(message)s",
        stream=sys.stderr,
        force=True,
    )

    logging.getLogger("pyrate_limiter").setLevel(logging.WARNING)
    logging.getLogger("httpx").setLevel(logging.WARNING)
    logging.getLogger("httpcore").setLevel(logging.WARNING)
    logging.getLogger("LiteLLM").setLevel(logging.WARNING)

    warnings.filterwarnings("ignore", category=UserWarning, module="pydantic")
