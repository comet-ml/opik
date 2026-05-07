"""Base class for code execution strategies."""
import json
import logging
import math
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)

# Default configuration values for Docker executor containers
# CPU shares: higher value = higher priority. Docker default is 1024.
# Value of 512 gives containers moderate priority relative to other processes.
DEFAULT_CPU_SHARES = 512
# Memory limit for executor containers
# Uses Docker SDK format: number followed by single letter unit (b/k/m/g)
DEFAULT_MEM_LIMIT = "256m"
# CPU hard limit for executor containers (in fractional CPUs, e.g. "0.5" = half a CPU core)
# None means no hard limit (only cpu_shares soft priority applies)
DEFAULT_CPU_LIMIT = None

# Human-readable bodies returned with HTTP 503. Callers should branch on
# the HTTP status code, not on these strings.
SATURATED_ERROR = "Code executor is saturated, please retry"
SHUTDOWN_ERROR = "Service is shutting down"

@dataclass
class ExecutionResult:
    """Result of code execution."""
    exit_code: int
    output: bytes

class CodeExecutorBase(ABC):
    """Base class for code execution strategies."""

    def __init__(self):
        # Shared configuration
        self.max_parallel = int(os.getenv("PYTHON_CODE_EXECUTOR_PARALLEL_NUM", 5))
        self.exec_timeout = int(os.getenv("PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS", 3))
        # Maximum wait for a free executor before responding with HTTP 503.
        # Defaults to 0 (fail fast): once the pool is empty, the next slot
        # only opens after a fresh container/worker is created — too long to
        # absorb on the server side without re-pinning request threads, which
        # is the failure mode this knob exists to prevent. The HTTP layer's
        # retry-with-backoff is the right place to soak up bursts. Operators
        # can raise this if their traffic shape benefits from a short wait.
        self.pool_acquire_timeout = self._parse_pool_acquire_timeout()

    @staticmethod
    def _parse_pool_acquire_timeout():
        """Parse PYTHON_CODE_EXECUTOR_POOL_ACQUIRE_TIMEOUT_IN_SECS as a non-negative float."""
        raw = os.getenv("PYTHON_CODE_EXECUTOR_POOL_ACQUIRE_TIMEOUT_IN_SECS")
        if raw is None:
            return 0.0
        try:
            value = float(raw)
        except (TypeError, ValueError):
            logger.warning(
                f"PYTHON_CODE_EXECUTOR_POOL_ACQUIRE_TIMEOUT_IN_SECS must be a number, "
                f"got '{raw}'; falling back to 0"
            )
            return 0.0
        if not math.isfinite(value) or value < 0:
            # Reject nan/inf alongside negatives: an infinite timeout would
            # re-introduce the unbounded blocking acquire this knob exists
            # to bound (see OPIK-6308).
            logger.warning(
                f"PYTHON_CODE_EXECUTOR_POOL_ACQUIRE_TIMEOUT_IN_SECS must be a finite "
                f"non-negative number, got '{raw}'; falling back to 0"
            )
            return 0.0
        return value

    def parse_execution_result(self, result: ExecutionResult) -> dict:
        """Parse execution result into API response format."""
        if result.exit_code == 0:
            last_line = result.output.decode("utf-8").strip().splitlines()[-1]
            return json.loads(last_line)
        else:
            logger.warning(f"Execution failed (Code: {result.exit_code}):\n{result.output.decode('utf-8')}")
            try:
                last_line = result.output.decode("utf-8").strip().splitlines()[-1]
                return {"code": 400, "error": json.loads(last_line).get("error")}
            except Exception as e:
                logger.info(f"Exception parsing execution logs: {e}")
                return {"code": 400, "error": "Execution failed: Python code contains an invalid metric"}

    @abstractmethod
    def run_scoring(self, code: str, data: dict, payload_type: Optional[str] = None) -> dict:
        """Execute code with data and return results."""
        pass
