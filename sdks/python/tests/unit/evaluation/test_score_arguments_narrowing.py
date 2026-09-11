"""Scoring inputs are narrowed to what each metric's signature accepts.

A metric without ``**kwargs`` used to fail on every item with
``score() got an unexpected keyword argument 'input'`` (OPIK-6925).
"""

from typing import Any, List, Optional
from unittest import mock

import pytest

from opik import exceptions, url_helpers
from opik.api_objects import opik_client
from opik.api_objects.dataset import dataset_item
from opik import evaluation
from opik.evaluation.metrics import base_metric, score_result


class NarrowSignature(base_metric.BaseMetric):
    """Declares exactly what it needs and nothing else — no **kwargs."""

    def __init__(self, track: bool = False) -> None:
        super().__init__(name="narrow_metric", track=track)

    def score(self, output: str, expected_label: str) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0 if output == expected_label else 0.0
        )


class AbsorbsKwargs(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="wide_metric", track=False)

    def score(self, output: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0, reason=f"saw {sorted(ignored)}"
        )


def _run_evaluation(
    metrics: List[base_metric.BaseMetric],
    items: Optional[List[dataset_item.DatasetItem]] = None,
):
    items = items or [
        dataset_item.DatasetItem(
            id="dataset-item-id-1", input="q", output="a", expected_label="a"
        )
    ]

    mock_dataset = mock.MagicMock(
        spec=[
            "__internal_api__stream_items_as_dataclasses__",
            "id",
            "dataset_items_count",
            "get_version_info",
            "get_execution_policy",
            "project_name",
            "get_evaluators",
        ]
    )
    mock_dataset.name = "the-dataset-name"
    mock_dataset.id = "the-dataset-id"
    mock_dataset.dataset_items_count = len(items)
    mock_dataset.get_version_info.return_value = None
    mock_dataset.project_name = None
    mock_dataset.get_execution_policy.return_value = {}
    mock_dataset.get_evaluators.return_value = []
    mock_dataset.__internal_api__stream_items_as_dataclasses__.return_value = iter(
        items
    )

    mock_experiment = mock.Mock()
    mock_experiment.prompts = None

    with mock.patch.object(
        opik_client.Opik, "create_experiment", mock.Mock(return_value=mock_experiment)
    ):
        with mock.patch.object(
            url_helpers, "get_experiment_url_by_id", mock.Mock(return_value="any_url")
        ):
            return evaluation.evaluate(
                dataset=mock_dataset,
                task=lambda item: {"output": item["output"]},
                scoring_metrics=metrics,
                experiment_name="the-experiment-name",
                task_threads=1,
            )


@pytest.mark.parametrize("track", [False, True])
def test_evaluate__metric_without_kwargs__unrelated_dataset_keys_are_not_passed(
    fake_backend, track
):
    # `track=True` is the default and wraps score() — the signature must still
    # be introspectable through that wrapper.
    result = _run_evaluation([NarrowSignature(track=track)])

    score = result.test_results[0].score_results[0]
    assert score.name == "narrow_metric"
    assert score.scoring_failed is False
    assert score.value == 1.0


def test_evaluate__metric_with_kwargs__still_receives_everything(fake_backend):
    result = _run_evaluation([AbsorbsKwargs()])

    score = result.test_results[0].score_results[0]
    assert score.scoring_failed is False
    # Unchanged behaviour: a metric that opts into **kwargs sees all the keys.
    assert score.reason == "saw ['expected_label', 'id', 'input']"


def test_evaluate__metric_without_kwargs__missing_argument_still_raises(fake_backend):
    # Narrowing must not mask a key the metric actually declared: the dataset
    # below has no `expected_label`, and that has to stay a hard failure.
    items = [dataset_item.DatasetItem(id="dataset-item-id-1", input="q", output="a")]

    with pytest.raises(exceptions.ScoreMethodMissingArguments):
        _run_evaluation([NarrowSignature()], items=items)


class PositionalOnlySignature(base_metric.BaseMetric):
    """`output` cannot be passed by keyword, so this metric could never be scored."""

    def __init__(self) -> None:
        super().__init__(name="positional_only_metric", track=False)

    def score(self, output: str, /) -> score_result.ScoreResult:
        return score_result.ScoreResult(name=self.name, value=len(output) / 10)


def test_evaluate__positional_only_parameter__is_passed_positionally(fake_backend):
    result = _run_evaluation([PositionalOnlySignature()])

    score = result.test_results[0].score_results[0]
    assert score.scoring_failed is False
    assert score.value == 0.1  # len("a") / 10


def test_evaluate__positional_only_parameter_is_missing__reported_as_missing_argument(
    fake_backend,
):
    # Previously this surfaced as a generic TypeError from the call itself, which
    # bypassed the dedicated missing-argument message.
    class NeedsPositionalOnly(base_metric.BaseMetric):
        def __init__(self) -> None:
            super().__init__(name="needs_positional_only", track=False)

        def score(self, gold_label: str, /) -> score_result.ScoreResult:
            return score_result.ScoreResult(name=self.name, value=1.0)

    with pytest.raises(exceptions.ScoreMethodMissingArguments, match="gold_label"):
        _run_evaluation([NeedsPositionalOnly()])


class TwoPositionalOnlyWithDefaults(base_metric.BaseMetric):
    """Both parameters are positional-only and both have defaults."""

    def __init__(self) -> None:
        super().__init__(name="two_positional_only", track=False)

    def score(
        self, reference: str = "MISSING_REF", output: str = "MISSING_OUT", /
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name,
            value=1.0,
            reason=f"reference={reference!r} output={output!r}",
        )


def test_evaluate__positional_only_gap__is_reported_instead_of_shifting_values(
    fake_backend,
):
    # `reference` is absent from the item but `output` is present. Skipping the gap
    # would bind the output value to `reference` and score the metric against the
    # wrong input, silently: both parameters have defaults, so the missing-argument
    # check does not require them.
    with pytest.raises(exceptions.ScoreMethodMissingArguments, match="reference"):
        _run_evaluation([TwoPositionalOnlyWithDefaults()])


class TrailingPositionalOnlyWithDefault(base_metric.BaseMetric):
    def __init__(self) -> None:
        super().__init__(name="trailing_positional_only", track=False)

    def score(
        self, output: str, missing_tail: str = "DEFAULTED", /
    ) -> score_result.ScoreResult:
        return score_result.ScoreResult(
            name=self.name, value=1.0, reason=f"tail={missing_tail!r}"
        )


def test_evaluate__positional_only_trailing_gap__falls_back_to_the_default(
    fake_backend,
):
    # Nothing is supplied after the gap, so the contiguous prefix is passed and the
    # parameter keeps its default — no shifting is possible.
    result = _run_evaluation([TrailingPositionalOnlyWithDefault()])

    score = result.test_results[0].score_results[0]
    assert score.scoring_failed is False
    assert score.reason == "tail='DEFAULTED'"


def test_evaluate__aggregated_metric__narrows_arguments_for_each_wrapped_metric(
    fake_backend,
):
    # AggregatedMetric declares **kwargs, so it receives every dataset key and then
    # re-dispatches to the wrapped metrics — which must be narrowed in turn.
    from opik.evaluation.metrics import AggregatedMetric

    aggregated = AggregatedMetric(
        name="aggregated",
        metrics=[NarrowSignature()],
        aggregator=lambda results: score_result.ScoreResult(
            name="aggregated", value=results[0].value
        ),
        track=False,
    )

    result = _run_evaluation([aggregated])

    score = result.test_results[0].score_results[0]
    assert score.scoring_failed is False
    assert score.value == 1.0
