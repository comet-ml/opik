from typing import Optional, Dict, Any, List
import opik
import json

from opik.types import FeedbackScoreDict
from opik.api_objects.dataset import dataset_item
from opik import synchronization

from .. import testlib
import mock


def verify_trace(
    opik_client: opik.Opik,
    trace_id: str,
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: Dict[str, Any] = mock.ANY,  # type: ignore
    output: Dict[str, Any] = mock.ANY,  # type: ignore
    tags: List[str] = mock.ANY,  # type: ignore
    feedback_scores: List[FeedbackScoreDict] = mock.ANY,  # type: ignore
):
    if not synchronization.until(
        lambda: (opik_client.get_trace_content(id=trace_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get trace with id {trace_id}.")

    trace = opik_client.get_trace_content(id=trace_id)

    assert trace.name == name, f"{trace.name} != {name}"
    assert trace.input == input, testlib.prepare_difference_report(trace.input, input)
    assert trace.output == output, testlib.prepare_difference_report(
        trace.output, output
    )
    assert trace.metadata == metadata, testlib.prepare_difference_report(
        trace.metadata, metadata
    )
    assert trace.tags == tags, testlib.prepare_difference_report(trace.tags, tags)

    if feedback_scores is not mock.ANY:
        actual_feedback_scores = (
            [] if trace.feedback_scores is None else trace.feedback_scores
        )
        assert (
            len(actual_feedback_scores) == len(feedback_scores)
        ), f"Expected amount of trace feedback scores ({len(feedback_scores)}) is not equal to actual amount ({len(actual_feedback_scores)})"

        actual_feedback_scores: List[FeedbackScoreDict] = [
            {
                "category_name": score.category_name,
                "id": trace_id,
                "name": score.name,
                "reason": score.reason,
                "value": score.value,
            }
            for score in trace.feedback_scores
        ]

        sorted_actual_feedback_scores = sorted(
            actual_feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        sorted_expected_feedback_scores = sorted(
            feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        for actual_score, expected_score in zip(
            sorted_actual_feedback_scores, sorted_expected_feedback_scores
        ):
            testlib.assert_dicts_equal(actual_score, expected_score)


def verify_span(
    opik_client: opik.Opik,
    span_id: str,
    trace_id: str,
    parent_span_id: Optional[str],
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: Dict[str, Any] = mock.ANY,  # type: ignore
    output: Dict[str, Any] = mock.ANY,  # type: ignore
    tags: List[str] = mock.ANY,  # type: ignore
    type: str = mock.ANY,  # type: ignore
    feedback_scores: List[FeedbackScoreDict] = mock.ANY,  # type: ignore,
):
    if not synchronization.until(
        lambda: (opik_client.get_span_content(id=span_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get span with id {span_id}.")

    span = opik_client.get_span_content(id=span_id)

    assert span.trace_id == trace_id, f"{span.trace_id} != {trace_id}"

    if parent_span_id is None:
        assert span.parent_span_id is None, f"{span.parent_span_id} != {parent_span_id}"
    else:
        assert (
            span.parent_span_id == parent_span_id
        ), f"{span.parent_span_id} != {parent_span_id}"

    assert span.name == name, f"{span.name} != {name}"
    assert span.type == type, f"{span.type} != {type}"

    assert span.input == input, testlib.prepare_difference_report(span.input, input)
    assert span.output == output, testlib.prepare_difference_report(span.output, output)
    assert span.metadata == metadata, testlib.prepare_difference_report(
        span.metadata, metadata
    )
    assert span.tags == tags, testlib.prepare_difference_report(span.tags, tags)

    if feedback_scores is not mock.ANY:
        actual_feedback_scores = (
            [] if span.feedback_scores is None else span.feedback_scores
        )
        assert (
            len(actual_feedback_scores) == len(feedback_scores)
        ), f"Expected amount of span feedback scores ({len(feedback_scores)}) is not equal to actual amount ({len(actual_feedback_scores)})"

        actual_feedback_scores: List[FeedbackScoreDict] = [
            {
                "category_name": score.category_name,
                "id": span_id,
                "name": score.name,
                "reason": score.reason,
                "value": score.value,
            }
            for score in span.feedback_scores
        ]

        sorted_actual_feedback_scores = sorted(
            actual_feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        sorted_expected_feedback_scores = sorted(
            feedback_scores, key=lambda item: json.dumps(item, sort_keys=True)
        )
        for actual_score, expected_score in zip(
            sorted_actual_feedback_scores, sorted_expected_feedback_scores
        ):
            testlib.assert_dicts_equal(actual_score, expected_score)


def verify_dataset(
    opik_client: opik.Opik,
    name: str,
    description: str = mock.ANY,
    dataset_items: List[dataset_item.DatasetItem] = mock.ANY,
):
    if not synchronization.until(
        lambda: (opik_client.get_dataset(name=name) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get dataset with name {name}.")

    actual_dataset = opik_client.get_dataset(name=name)
    assert actual_dataset.description == description

    actual_dataset_items = actual_dataset.get_all_items()
    assert (
        len(actual_dataset_items) == len(dataset_items)
    ), f"Amount of actual dataset items ({len(actual_dataset_items)}) is not the same as of expected ones ({len(dataset_items)})"

    actual_dataset_items_dicts = [item.__dict__ for item in actual_dataset_items]
    expected_dataset_items_dicts = [item.__dict__ for item in dataset_items]

    sorted_actual_items = sorted(
        actual_dataset_items_dicts, key=lambda item: json.dumps(item, sort_keys=True)
    )
    sorted_expected_items = sorted(
        expected_dataset_items_dicts, key=lambda item: json.dumps(item, sort_keys=True)
    )

    for actual_item, expected_item in zip(sorted_actual_items, sorted_expected_items):
        testlib.assert_dicts_equal(actual_item, expected_item, ignore_keys=["id"])


def verify_experiment(
    opik_client: opik.Opik,
    id: str,
    experiment_name: str,
    dataset_name: str,
    feedback_scores_amount: int,
    traces_amount: int,
):
    rest_client = (
        opik_client._rest_client
    )  # temporary solution until backend prepares proper endpoints

    rest_client.datasets.find_dataset_items_with_experiment_items

    if not synchronization.until(
        lambda: (rest_client.experiments.get_experiment_by_id(id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get experiment with id {id}.")

    experiment_content = rest_client.experiments.get_experiment_by_id(id)

    assert experiment_content.name == experiment_name
    assert len(experiment_content.feedback_scores) == feedback_scores_amount
    assert len(experiment_content.trace_count) == traces_amount
    assert experiment_content.dataset_id == opik_client.get_dataset(dataset_name).name
