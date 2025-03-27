import concurrent.futures
import json
import logging
import subprocess
import time
from threading import BoundedSemaphore

from opik_backend.executor import CodeExecutorBase, ExecutionResult
from opik_backend.scoring_commands import PYTHON_SCORING_COMMAND

logger = logging.getLogger(__name__)

class ProcessExecutor(CodeExecutorBase):
    def __init__(self):
        super().__init__()
        self.semaphore = BoundedSemaphore(self.max_parallel)

    def run_scoring(self, code: str, data: dict) -> dict:
        try:
            # Try to acquire a slot, with timeout
            if not self.semaphore.acquire(timeout=self.exec_timeout):
                return {"code": 503, "error": "Server is at capacity. Please try again later."}

            try:
                with concurrent.futures.ThreadPoolExecutor() as exec_pool:
                    # Execute exactly like Docker - pass code and data as args
                    process = subprocess.Popen(
                        ["python", "-u", "-c", PYTHON_SCORING_COMMAND, code, json.dumps(data)],
                        stdout=subprocess.PIPE,
                        stderr=subprocess.PIPE,
                        text=False  # Use bytes to match ExecutionResult
                    )

                    try:
                        future = exec_pool.submit(process.wait)
                        exit_code = future.result(timeout=self.exec_timeout)
                        stdout, stderr = process.stdout.read(), process.stderr.read()
                        # Combine stdout/stderr like Docker does
                        result = ExecutionResult(
                            exit_code=exit_code,
                            output=stdout if not stderr else stderr,  # Use stderr if present
                        )
                        return self.parse_execution_result(result)
                    except concurrent.futures.TimeoutError:
                        logger.error(f"Execution timed out in process {process.pid}")
                        self.terminate_process(process)
                        return {"code": 504, "error": "Server processing exceeded timeout limit."}
                    except Exception as e:
                        logger.error(f"An unexpected error occurred: {e}")
                        self.terminate_process(process)
                        return {"code": 500, "error": "An unexpected error occurred"}
            finally:
                self.semaphore.release()
        except Exception as e:
            logger.error(f"Error creating process: {e}")
            return {"code": 500, "error": "Failed to create process"}

    def terminate_process(self, process):
        """Terminate a process with SIGTERM, falling back to SIGKILL if needed."""
        try:
            process.terminate()
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()
        except Exception as e:
            logger.error(f"Error terminating process {process.pid}: {e}")
