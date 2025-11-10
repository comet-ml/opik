import signal
import sys
import traceback
import inspect
import uuid
import logging
from types import ModuleType
from typing import Type, Union, List, Optional

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult
from .payload_types import PayloadType

# Set up logging for the worker
logger = logging.getLogger("process_worker")
handler = logging.StreamHandler(sys.stderr)
formatter = logging.Formatter('[%(asctime)s] Worker PID %(process)d: [%(levelname)s] %(message)s')
handler.setFormatter(formatter)
if not logger.hasHandlers():
    logger.addHandler(handler)
logger.setLevel(logging.INFO)


def get_metric_class(module: ModuleType) -> Type[BaseMetric]:   
    for _, cls in inspect.getmembers(module, inspect.isclass):
        if issubclass(cls, BaseMetric):
            return cls

def to_scores(score_result: Union[ScoreResult, List[ScoreResult]]) -> List[ScoreResult]:
    scores = []
    if isinstance(score_result, ScoreResult):
        scores = [score_result]
    elif isinstance(score_result, list):
        for item in score_result:
            if isinstance(item, ScoreResult):
                scores.append(item)
    return scores


def run_user_code(code: str, data: dict, payload_type: Optional[str] = None) -> dict:
    """
    Run the scoring logic with the provided code and data.
    For trace_thread type, pass data as first positional argument.
    """
    module = ModuleType(str(uuid.uuid4()))

    try:
        exec(code, module.__dict__)
    except Exception as e:
        stacktrace = "\n".join(traceback.format_exc().splitlines()[3:])
        return {"code": 400, "error": f"Field 'code' contains invalid Python code: {stacktrace}"}

    metric_class = get_metric_class(module)
    if metric_class is None:
        return {"code": 400, "error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"}

    score_result: Union[ScoreResult, List[ScoreResult]] = []
    try:
        metric = metric_class()
        
        # Handle trace_thread type differently - pass data as first positional argument
        if payload_type == PayloadType.TRACE_THREAD.value:
            score_result = metric.score(data)
        else:
            # Regular scoring - unpack data as keyword arguments
            score_result = metric.score(**data)
    except Exception as e:
        stacktrace = "\n".join(traceback.format_exc().splitlines()[3:])
        return {"code": 400, "error": f"The provided 'code' and 'data' fields can't be evaluated: {stacktrace}"}
            
    scores = to_scores(score_result)

    return {"scores": [score.__dict__ for score in scores]}


def worker_process_main(connection):
    # Workers should ignore SIGINT; parent ProcessExecutor will manage shutdown.
    signal.signal(signal.SIGINT, signal.SIG_IGN)

    try:
        # Signal READY to the parent process via the pipe
        connection.send("READY")
        
        # Keep reading commands until told to exit or pipe closes
        while True:
            try:
                # Read a command from the pipe - this will block until we get a command
                command_data = connection.recv()
                if command_data is None or command_data.get("command") == "EXIT":
                    break
                    
                # Parse the command (code, data, and payload_type)
                code = command_data.get('code', '')
                input_data = command_data.get('data', {})
                payload_type = command_data.get('payload_type')
                
                # Execute the code
                result = run_user_code(code, input_data, payload_type)
                
                # Return the result via the pipe
                connection.send(result)
            except EOFError as e:
                # This occurs when the parent closes the pipe, e.g., during shutdown.
                # The worker will break from its loop and be terminated by SIGTERM from the parent.
                logger.info(f"Received EOF error, probably parent closed the pipe: {str(e)}")
                break
            except Exception as e:
                # Report any errors via the pipe
                error_msg = {"code": 500, "error": f"Worker error: {str(e)}", "traceback": traceback.format_exc()}
                try:
                    connection.send(error_msg)
                except Exception as send_e:
                    logger.error(f"Failed to send error to parent: {send_e}")
                logger.error(f"Encountered error: {e}")
                logger.error(traceback.format_exc())

    except Exception as e:
        # If we get an error during initialization, log it and exit
        # Also try to send error over pipe if possible
        error_payload = {"code": 500, "error": f"Worker initialization error: {str(e)}", "traceback": traceback.format_exc()}
        try:
            connection.send(error_payload)
        except Exception:
            pass  # Pipe might not be usable
        logger.error(f"Worker initialization error: {str(e)}\n{traceback.format_exc()}")
        sys.exit(1)
