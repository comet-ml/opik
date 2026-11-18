"""Tests for reading every experiment's items on a dataset from the cursor stream."""

import functools
import json
import types
from typing import Any, Dict, List, Optional
from unittest.mock import Mock

import pytest

from opik import exceptions
from opik.api_objects import rest_stream_parser
from opik.api_objects.experiment import (
    experiments_client as experiments_client_module,
    rest_operations,
)
from opik.rest_api.core.api_error import ApiError

DATASET_ID = "dataset-1"


def _row(experiment_id: str, row_id: str, dataset_item_id: str) -> Dict[str, Any]:
    return {
        "id": row_id,
        "experiment_id": experiment_id,
        "dataset_item_id": dataset_item_id,
        "trace_id": f"trace-{experiment_id}-{row_id}",
        "input": {"question": dataset_item_id},
        "output": {"answer": f"{experiment_id}/{row_id}"},
        "feedback_scores": [{"name": "accuracy", "value": 1.0}],
    }


class _StreamEndpoint:
    """Serves rows like the backend: (experiment_id, id) descending, below the cursor."""

    def __init__(self, rows: List[Dict[str, Any]]) -> None:
        self._rows = sorted(
            rows, key=lambda row: (row["experiment_id"], row["id"]), reverse=True
        )
        self.calls: List[Dict[str, Any]] = []

    def __call__(self, **kwargs: Any) -> List[bytes]:
        self.calls.append(kwargs)
        rows = self._rows
        if kwargs["last_retrieved_experiment_id"] is not None:
            cursor = (
                kwargs["last_retrieved_experiment_id"],
                kwargs["last_retrieved_id"],
            )
            rows = [row for row in rows if (row["experiment_id"], row["id"]) < cursor]
        return [
            json.dumps(row).encode("utf-8") + b"\n" for row in rows[: kwargs["limit"]]
        ]


def _client(stream: Any) -> experiments_client_module.ExperimentsClient:
    rest_client = Mock()
    rest_client.datasets.get_dataset_by_identifier.return_value = types.SimpleNamespace(
        id=DATASET_ID
    )
    rest_client.datasets.stream_dataset_experiment_items.side_effect = stream
    return experiments_client_module.ExperimentsClient(rest_client)


def _find(
    client: experiments_client_module.ExperimentsClient, **kwargs: Any
) -> List[Any]:
    return client.find_experiment_items_for_dataset(dataset_name="dataset", **kwargs)


def test_find_experiment_items_for_dataset__no_experiment_ids__reads_every_row_from_the_stream_grouped_by_item(
    monkeypatch,
):
    monkeypatch.setattr(
        rest_operations.rest_stream_parser,
        "read_and_parse_full_stream",
        functools.partial(
            rest_stream_parser.read_and_parse_full_stream, max_endpoint_batch_size=2
        ),
    )
    stream = _StreamEndpoint(
        [
            _row("exp-a", "r1", "item-2"),
            _row("exp-a", "r2", "item-1"),
            _row("exp-b", "r3", "item-1"),
            _row("exp-b", "r4", "item-2"),
            _row("exp-c", "r5", "item-1"),
        ]
    )

    items = _find(_client(stream))

    assert [(item.dataset_item_id, item.experiment_id) for item in items] == [
        ("item-1", "exp-c"),
        ("item-1", "exp-b"),
        ("item-1", "exp-a"),
        ("item-2", "exp-b"),
        ("item-2", "exp-a"),
    ]
    assert [
        (call["last_retrieved_experiment_id"], call["last_retrieved_id"])
        for call in stream.calls
    ] == [(None, None), ("exp-b", "r4"), ("exp-a", "r2")]
    assert all(call["dataset_id"] == DATASET_ID for call in stream.calls)


def test_find_experiment_items_for_dataset__no_experiment_ids__row_fields_mapped():
    stream = _StreamEndpoint([_row("exp-a", "r1", "item-1")])

    (item,) = _find(_client(stream))

    assert item.id == "r1"
    assert item.experiment_id == "exp-a"
    assert item.trace_id == "trace-exp-a-r1"
    assert item.dataset_item_data == {"question": "item-1"}
    assert item.evaluation_task_output == {"answer": "exp-a/r1"}
    assert item.feedback_scores == [
        {"category_name": None, "name": "accuracy", "reason": None, "value": 1.0}
    ]


def test_find_experiment_items_for_dataset__filter_and_versions__sent_as_dataset_item_filters():
    stream = _StreamEndpoint([])

    _find(
        _client(stream),
        filter_string='id = "item-1"',
        dataset_versions=["v1", "abc123"],
        truncate=False,
    )

    (call,) = stream.calls
    assert json.loads(call["filters"]) == [
        {"field": "id", "key": "", "type": "string", "operator": "=", "value": "item-1"}
    ]
    assert call["dataset_versions"] == ["v1", "abc123"]
    assert call["truncate"] is False


def test_find_experiment_items_for_dataset__max_results__caps_the_stream_read():
    stream = _StreamEndpoint(
        [_row("exp-a", f"r{index}", f"item-{index}") for index in range(5)]
    )

    items = _find(_client(stream), max_results=3)

    assert len(items) == 3
    assert stream.calls[0]["limit"] == 3


@pytest.mark.parametrize("max_results", [0, -1])
def test_find_experiment_items_for_dataset__non_positive_max_results__no_stream_request(
    max_results,
):
    stream = _StreamEndpoint([_row("exp-a", "r1", "item-1")])

    assert _find(_client(stream), max_results=max_results) == []
    assert stream.calls == []


def test_find_experiment_items_for_dataset__experiment_ids_and_dataset_versions__raises_value_error():
    stream = _StreamEndpoint([])
    client = _client(stream)

    with pytest.raises(ValueError, match="dataset_versions"):
        _find(client, experiment_ids=["exp-a"], dataset_versions=["v1"])

    assert stream.calls == []


def _failing_stream(status_code: int, body: Optional[Dict[str, Any]]) -> Any:
    def stream(**kwargs: Any) -> Any:
        raise ApiError(status_code=status_code, headers={}, body=body)

    return stream


def test_find_experiment_items_for_dataset__404_with_dataset_versions__raises_dataset_version_not_found():
    body = {"errors": ["Version not found for dataset hash='v9'"]}

    with pytest.raises(exceptions.DatasetVersionNotFound, match="v9") as exc_info:
        _find(_client(_failing_stream(404, body)), dataset_versions=["v9"])

    assert isinstance(exc_info.value.__cause__, ApiError)


@pytest.mark.parametrize(
    "status_code, dataset_versions",
    [(404, None), (403, ["v1"]), (422, ["v1"])],
)
def test_find_experiment_items_for_dataset__other_api_errors__propagate_unchanged(
    status_code, dataset_versions
):
    with pytest.raises(ApiError) as exc_info:
        _find(
            _client(_failing_stream(status_code, {"errors": ["boom"]})),
            dataset_versions=dataset_versions,
        )

    assert exc_info.value.status_code == status_code


def test_find_experiment_items_for_dataset__404_for_the_dataset_with_dataset_versions__propagates_api_error():
    # The dataset can be deleted between its lookup and the stream request; that
    # 404 is not a missing version.
    with pytest.raises(ApiError) as exc_info:
        _find(
            _client(
                _failing_stream(404, {"code": 404, "message": "Dataset not found"})
            ),
            dataset_versions=["v1"],
        )

    assert not isinstance(exc_info.value, exceptions.DatasetVersionNotFound)
    assert exc_info.value.status_code == 404


def test_find_experiment_items_for_dataset__version_message_in_message_field__raises_dataset_version_not_found():
    body = {
        "code": 404,
        "message": "Version not found for dataset hash='v9' datasetId='d'",
    }

    with pytest.raises(
        exceptions.DatasetVersionNotFound,
        match="Version not found for dataset hash='v9'",
    ):
        _find(_client(_failing_stream(404, body)), dataset_versions=["v9"])


def test_find_experiment_items_for_dataset__error_line_in_the_stream__raises_api_error():
    error_line = {"code": 500, "message": "Streaming operation timed out"}

    def stream(**kwargs: Any) -> List[bytes]:
        return [
            json.dumps(_row("exp-a", "r1", "item-1")).encode("utf-8") + b"\n",
            json.dumps(error_line).encode("utf-8") + b"\n",
        ]

    with pytest.raises(ApiError) as exc_info:
        _find(_client(stream))

    assert exc_info.value.status_code == 500
    assert exc_info.value.body == error_line
