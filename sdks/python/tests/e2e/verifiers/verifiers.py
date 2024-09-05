from typing import Optional, Dict, Any, List
import opik
import json

from opik.api_objects.dataset import dataset_item
from opik import synchronization

from ...testlib import testlib_dsl
import mock


def verify_trace(
    opik_client: opik.Opik,
    trace_id: str,
    name: str = mock.ANY,  # type: ignore
    metadata: Dict[str, Any] = mock.ANY,  # type: ignore
    input: Dict[str, Any] = mock.ANY,  # type: ignore
    output: Dict[str, Any] = mock.ANY,  # type: ignore
    tags: List[str] = mock.ANY,  # type: ignore
):
    if not synchronization.until(
        lambda: (opik_client.get_trace_content(id=trace_id) is not None),
        allow_errors=True,
    ):
        raise AssertionError(f"Failed to get trace with id {trace_id}.")

    trace = opik_client.get_trace_content(id=trace_id)

    assert trace.name == name, f"{trace.name} != {name}"
    assert trace.input == input, testlib_dsl.prepare_difference_report(
        trace.input, input
    )
    assert trace.output == output, testlib_dsl.prepare_difference_report(
        trace.output, output
    )
    assert trace.metadata == metadata, testlib_dsl.prepare_difference_report(
        trace.metadata, metadata
    )
    assert trace.tags == tags, testlib_dsl.prepare_difference_report(trace.tags, tags)


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

    assert span.input == input, testlib_dsl.prepare_difference_report(span.input, input)
    assert span.output == output, testlib_dsl.prepare_difference_report(
        span.output, output
    )
    assert span.metadata == metadata, testlib_dsl.prepare_difference_report(
        span.metadata, metadata
    )
    assert span.tags == tags, testlib_dsl.prepare_difference_report(span.tags, tags)


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
        _assert_dicts_equal(actual_item, expected_item, ignore_keys=["id"])


def _assert_dicts_equal(
    dict1: Dict[str, Any],
    dict2: Dict[str, Any],
    ignore_keys: Optional[List[str]] = None,
) -> bool:
    dict1_copy, dict2_copy = {**dict1}, {**dict2}

    ignore_keys = [] if ignore_keys is None else ignore_keys

    for key in ignore_keys:
        dict1_copy.pop(key, None)
        dict2_copy.pop(key, None)

    assert dict1_copy == dict2_copy, testlib_dsl.prepare_difference_report(
        dict1_copy, dict2_copy
    )
