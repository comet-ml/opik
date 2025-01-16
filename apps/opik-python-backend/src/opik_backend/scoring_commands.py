PYTHON_SCORING_COMMAND = """
import inspect
import json
import uuid
from sys import argv
from types import ModuleType
from typing import Type, Union, List, Any, Dict

from opik.evaluation.metrics import BaseMetric
from opik.evaluation.metrics.score_result import ScoreResult


def get_module(code: str) -> ModuleType:
    module_name = str(uuid.uuid4())
    module = ModuleType(module_name)
    exec(code, module.__dict__)
    return module


def get_metric_class(module: ModuleType) -> Type[BaseMetric]:
    for _, cls in inspect.getmembers(module, inspect.isclass):
        if issubclass(cls, BaseMetric):
            return cls


def evaluate_metric(metric_class: Type[BaseMetric], data: Dict[Any, Any]) -> Union[ScoreResult, List[ScoreResult]]:
    metric = metric_class()
    return metric.score(**data)


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

module = get_module(code)
metric_class = get_metric_class(module)
score_result = evaluate_metric(metric_class, data)
scores = to_scores(score_result)

response = json.dumps({"scores": [score.__dict__ for score in scores]})
print(response)
"""
