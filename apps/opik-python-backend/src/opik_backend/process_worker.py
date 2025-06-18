import os
import signal
import sys
import traceback
import inspect
import uuid
from types import ModuleType
from typing import Type, Union, List

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


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


def run_user_code(code: str, data: dict) -> dict:
    """
    Run the scoring logic with the provided code and data.
    """
    module = ModuleType(str(uuid.uuid4()))

    try:
        exec(code, module.__dict__)
    except Exception:  
        stacktrace = "\n".join(traceback.format_exc().splitlines()[2:])  
        return {"error": f"Field 'code' contains invalid Python code: {stacktrace}"}

    metric_class = get_metric_class(module)
    if metric_class is None:
        return {"error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"}

    score_result: Union[ScoreResult, List[ScoreResult]] = []
    try:
        metric = metric_class()
        score_result = metric.score(**data)
    except Exception:
        stacktrace = "\n".join(traceback.format_exc().splitlines()[2:])
        return {"error": f"The provided 'code' and 'data' fields can't be evaluated: {stacktrace}"}
            
    scores = to_scores(score_result)

    return {"scores": [score.__dict__ for score in scores]}


def _graceful_shutdown_handler(signum, frame):
    # This handler acknowledges the signal. Essential, async-signal-safe cleanup
    # could be done here. For now, logging is removed to prevent reentrant stderr calls.
    # The process will terminate due to the original signal (e.g., SIGTERM)
    # after this handler returns. The parent ProcessExecutor logs worker termination.
    pass


def worker_process_main(connection):
    # Register the graceful shutdown handler for SIGTERM
    signal.signal(signal.SIGTERM, _graceful_shutdown_handler)
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
                    
                # Parse the command (code and data)
                code = command_data.get('code', '')
                input_data = command_data.get('data', {})
                
                # Execute the code
                result = run_user_code(code, input_data)
                
                # Return the result via the pipe
                connection.send(result)
            except EOFError:
                # This occurs when the parent closes the pipe, e.g., during shutdown.
                # The worker will break from its loop and be terminated by SIGTERM from the parent.
                # Logging is removed to reduce noise as parent logs worker termination.
                break
            except Exception as e:
                # Report any errors via the pipe
                error_msg = {"code": 500, "error": f"Worker error: {str(e)}", "traceback": traceback.format_exc()}
                try:
                    connection.send(error_msg)
                except Exception as send_e:
                    print(f"Worker PID {os.getpid()}: Failed to send error to parent: {send_e}", file=sys.stderr)
                # Optionally, decide if the worker should exit on all errors or try to continue
                # For now, let's continue, but log the error to worker's stderr (or file log)
                print(f"Worker PID {os.getpid()}: Encountered error: {e}", file=sys.stderr)
                print(traceback.format_exc(), file=sys.stderr)
        
        # Loop finished (e.g. EXIT command or pipe closed), worker function will now return.
        # Process termination will be handled by parent or signals.
    except Exception as e:
        # If we get an error during initialization, write it to stderr and exit
        # Also try to send error over pipe if possible
        error_payload = {"code": 500, "error": f"Worker initialization error: {str(e)}", "traceback": traceback.format_exc()}
        try:
            connection.send(error_payload)
        except Exception:
            pass # Pipe might not be usable
        sys.stderr.write(f"Worker initialization error: {str(e)}\n{traceback.format_exc()}\n")
        sys.stderr.flush()
        sys.exit(1)


# The __main__ block is no longer needed as this script will be imported
# and worker_process_main will be the target of multiprocessing.Process
