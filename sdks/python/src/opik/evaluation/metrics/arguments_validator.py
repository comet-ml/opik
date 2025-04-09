import abc
from typing import Dict, Any, Optional

from . import base_metric, arguments_helpers
from .. import types as evaluation_types


class ScoreArgumentsValidator(abc.ABC):
    """Abstract base class for all metrics allowing validation of score method's arguments.
    It validates if arguments to be provided to the score method are valid, otherwise it raises an error.
    """

    @abc.abstractmethod
    def validate_score_arguments(
        self,
        score_kwargs: Dict[str, Any],
        key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
    ) -> None:
        """The subclasses must implement this method to provide actual validation logic."""
        pass


def validate_score_arguments(
    metric: base_metric.BaseMetric,
    kwargs: Dict[str, Any],
    scoring_key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
) -> None:
    if isinstance(metric, ScoreArgumentsValidator):
        metric.validate_score_arguments(
            score_kwargs=kwargs, key_mapping=scoring_key_mapping
        )
    else:
        arguments_helpers.raise_if_score_arguments_are_missing(
            score_function=metric.score,
            score_name=metric.name,
            kwargs=kwargs,
            scoring_key_mapping=scoring_key_mapping,
        )
