from typing import Any, Callable, Dict, List, Union, TYPE_CHECKING

if TYPE_CHECKING:
    from . import evaluation_result
    from .metrics import experiment_metric_result

LLMTask = Callable[[Dict[str, Any]], Dict[str, Any]]

ScoringKeyMappingType = Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]

ExperimentMetric = Callable[
    ["evaluation_result.EvaluationResult"],
    Union[
        "experiment_metric_result.ExperimentMetricResult",
        List["experiment_metric_result.ExperimentMetricResult"],
    ],
]
