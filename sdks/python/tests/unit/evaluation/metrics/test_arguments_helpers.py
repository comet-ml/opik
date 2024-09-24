from opik import exceptions
from opik.evaluation.metrics import arguments_helpers, base_metric

import pytest


@pytest.mark.parametrize(
    argnames="score_kwargs, should_raise",
    argvalues=[
        ({"a": 1, "b": 2}, False),
        ({"a": 1, "b": 2, "c": 3}, False),
        ({"a": 1, "c": 3}, True),
        ({}, True),
    ],
)
def test_raise_if_score_arguments_are_missing(score_kwargs, should_raise):
    class SomeMetric(base_metric.BaseMetric):
        def score(self, a, b, **ignored_kwargs):
            pass

    some_metric = SomeMetric(name="some-metric")

    if should_raise:
        with pytest.raises(exceptions.ScoreMethodMissingArguments):
            arguments_helpers.raise_if_score_arguments_are_missing(
                score_function=some_metric.score,
                score_name=some_metric.name,
                kwargs=score_kwargs,
            )
    else:
        arguments_helpers.raise_if_score_arguments_are_missing(
            score_function=some_metric.score,
            score_name=some_metric.name,
            kwargs=score_kwargs,
        )
