import json
import sys
import traceback
import time
import inspect
import uuid
from types import ModuleType
from typing import Type, Union, List, Any, Dict

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


def run_scoring(code: str, data: dict) -> dict:
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


def main():
    try:
        # Make sure stdout is flushed immediately
        sys.stdout = open(sys.stdout.fileno(), 'w', buffering=1)
        
        # Flush immediately to signal we're ready
        sys.stdout.write("READY\n")
        sys.stdout.flush()
        
        # Keep reading commands until told to exit
        while True:
            try:
                # Read a line from stdin - this will block until we get a command
                line = sys.stdin.readline()
                if not line or line.strip() == "EXIT":
                    break
                    
                # Parse the command (code and data)
                data = json.loads(line)
                code = data.get('code', '')
                input_data = data.get('data', {})
                
                # Execute the code
                result = run_scoring(code, input_data)
                
                # Return the result
                sys.stdout.write(json.dumps(result) + "\n")
                sys.stdout.flush()
            except Exception as e:
                # Report any errors
                error_msg = {"code": 500, "error": f"Worker error: {str(e)}", "traceback": traceback.format_exc()}
                sys.stdout.write(json.dumps(error_msg) + "\n")
                sys.stdout.flush()
        
        # Exit cleanly
        sys.exit(0)
    except Exception as e:
        # If we get an error during initialization, write it to stderr and exit
        sys.stderr.write(f"Worker initialization error: {str(e)}\n{traceback.format_exc()}\n")
        sys.stderr.flush()
        sys.exit(1)

if __name__ == "__main__":
    main()
