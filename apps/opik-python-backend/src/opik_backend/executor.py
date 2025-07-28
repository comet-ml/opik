"""Base class for code execution strategies."""
import json
import logging
import os
import asyncio

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
        self.max_parallel = 1
        self.exec_timeout = int(os.getenv("PYTHON_CODE_EXECUTOR_EXEC_TIMEOUT_IN_SECS", 3))

    async def parse_execution_result(self, result: ExecutionResult) -> dict:
        """Parse execution result into API response format."""
        loop = asyncio.get_event_loop()
        
        # Handle potentially blocking string operations asynchronously
        output_str = await loop.run_in_executor(None, result.output.decode, "utf-8")
        
        try:
            last_line = await loop.run_in_executor(None, lambda: output_str.strip().splitlines()[-1])
            parsed_result = await loop.run_in_executor(None, json.loads, last_line)
            
            # Check if the parsed result contains an error
            if "error" in parsed_result:
                # Ensure we have a proper error response with "code" field
                return {"code": parsed_result.get("code", 400), "error": parsed_result["error"]}
            else:
                # Success case - return the parsed result (should contain "scores")
                return parsed_result
                
        except Exception as e:
            # JSON parsing failed or no output
            if result.exit_code != 0:
                await loop.run_in_executor(None, logger.warning, f"Execution failed (Code: {result.exit_code}):\n{output_str}")
                return {"code": 400, "error": "Execution failed: Python code contains an invalid metric"}
            else:
                await loop.run_in_executor(None, logger.info, f"Exception parsing execution output: {e}")
                return {"code": 400, "error": "Execution failed: Unable to parse execution result"}
    
    @abstractmethod
    async def run_scoring(self, code: str, data: dict, payload_type: str | None = None) -> dict:
        """Execute code with data and return results."""
        pass
