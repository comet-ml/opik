"""Base class for code execution strategies."""
import json
import logging
import os
from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)

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
