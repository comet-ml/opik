PYTHON_SCORING_COMMAND = """
import inspect
import json
import traceback
import uuid
from sys import argv
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


code = argv[1]
data = json.loads(argv[2])

module = ModuleType(str(uuid.uuid4()))

try:
    exec(code, module.__dict__)
except Exception:  
    stacktrace = "\\n".join(traceback.format_exc().splitlines()[2:])  
    print(json.dumps({"error": f"Field 'code' contains invalid Python code: {stacktrace}"}))
    exit(1)

metric_class = get_metric_class(module)
if metric_class is None:
    print(json.dumps({"error": "Field 'code' in the request doesn't contain a subclass implementation of 'opik.evaluation.metrics.BaseMetric'"}))
    exit(1)

score_result : Union[ScoreResult, List[ScoreResult]] = []
try:
    metric = metric_class()
    score_result = metric.score(**data)
except Exception:
    stacktrace = "\\n".join(traceback.format_exc().splitlines()[2:])
    print(json.dumps({"error": f"The provided 'code' and 'data' fields can't be evaluated: {stacktrace}"}))
    exit(1)
        
scores = to_scores(score_result)

response = json.dumps({"scores": [score.__dict__ for score in scores]})
print(response)
"""
