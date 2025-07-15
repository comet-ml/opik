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
                scoring_key_mapping=None,
            )
    else:
        arguments_helpers.raise_if_score_arguments_are_missing(
            score_function=some_metric.score,
            score_name=some_metric.name,
            kwargs=score_kwargs,
            scoring_key_mapping=None,
        )


@pytest.mark.parametrize(
    argnames="score_kwargs, mappings, unused_kwarg",
    argvalues=[
        ({"a": 1, "c": 3, "d": 3}, {"d": "c"}, None),
        ({"a": 1, "c": 3}, {"e": "d"}, "d"),
    ],
)
def test_raise_if_score_arguments_are_missing__with_mapping(
    score_kwargs,
    mappings,
    unused_kwarg,
):
    class SomeMetric(base_metric.BaseMetric):
        def score(self, a, b, **ignored_kwargs):
            pass

    some_metric = SomeMetric(name="some-metric")

    with pytest.raises(exceptions.ScoreMethodMissingArguments) as exc_info:
        arguments_helpers.raise_if_score_arguments_are_missing(
            score_function=some_metric.score,
            score_name=some_metric.name,
            kwargs=score_kwargs,
            scoring_key_mapping=mappings,
        )

    # Check if the `unused_kwarg` is present in the exception message
    if unused_kwarg is not None:
        assert (
            f"Some keys in `scoring_key_mapping` didn't match anything: ['{unused_kwarg}']"
            in str(exc_info.value)
        ), f"'unused_kwarg' ({unused_kwarg}) not found in exception message"
    else:
        assert "Some keys in `scoring_key_mapping` didn't match anything" not in str(
            exc_info.value
        ), f"'unused_kwarg' ({unused_kwarg}) found in exception message"
