"""Tests for ``Experiment.bulk_upload_items`` — validation, batching and retry."""

import datetime
import json
from typing import Any, List, Optional, Tuple
from unittest.mock import Mock, patch

import pytest

from opik import exceptions
from opik.api_objects import constants
from opik.api_objects.experiment import bulk_item, experiment as experiment_module
from opik.rest_api import client as rest_api_client
from opik.rest_api.core.api_error import ApiError

START_TIME = datetime.datetime(2026, 8, 4, 12, 0, 0)


class _StopAfterCapture(Exception):
    """Aborts the request once the encoded body has been captured."""


def _create_experiment(
    project_name: Optional[str] = None,
) -> Tuple[experiment_module.Experiment, Mock]:
    mock_rest_client = Mock()
    experiment = experiment_module.Experiment(
        id="experiment-id",
        name="experiment-name",
        dataset_name="dataset-name",
        rest_client=mock_rest_client,
        streamer=Mock(),
        experiments_client=Mock(),
        project_name=project_name,
    )
    return experiment, mock_rest_client


def _record(**kwargs: Any) -> bulk_item.ExperimentItemBulkRecord:
    kwargs.setdefault("dataset_item_id", "dataset-item-id")
    return bulk_item.ExperimentItemBulkRecord(**kwargs)


def _sent_batch_sizes(mock_rest_client: Mock) -> List[int]:
    return [
        len(call.kwargs["items"])
        for call in mock_rest_client.experiments.experiment_items_bulk.call_args_list
    ]


class TestBulkUploadItemsRequest:
    def test_bulk_upload_items__single_item__sends_one_request_with_experiment_identity(
        self,
    ) -> None:
        experiment, mock_rest_client = _create_experiment(project_name="my-project")

        experiment.bulk_upload_items(
            [
                _record(
                    trace=bulk_item.ExperimentItemBulkTrace(
                        start_time=START_TIME,
                        input={"question": "q"},
                        output={"answer": "a"},
                    ),
                    feedback_scores=[{"name": "accuracy", "value": 1.0}],
                )
            ]
        )

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 1
        kwargs = mock_rest_client.experiments.experiment_items_bulk.call_args.kwargs
        assert kwargs["experiment_id"] == "experiment-id"
        assert kwargs["experiment_name"] == "experiment-name"
        assert kwargs["dataset_name"] == "dataset-name"
        assert kwargs["project_name"] == "my-project"

        sent_item = kwargs["items"][0]
        assert sent_item.dataset_item_id == "dataset-item-id"
        assert sent_item.trace.input == {"question": "q"}
        assert sent_item.trace.output == {"answer": "a"}
        assert (
            sent_item.feedback_scores[0].source == constants.FEEDBACK_SCORE_SOURCE_SDK
        )

    def test_bulk_upload_items__empty_list__no_request_is_sent(self) -> None:
        experiment, mock_rest_client = _create_experiment()

        experiment.bulk_upload_items([])

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 0

    def test_bulk_upload_items__explicit_project_name__overrides_experiment_project(
        self,
    ) -> None:
        experiment, mock_rest_client = _create_experiment(project_name="experiment-one")

        experiment.bulk_upload_items([_record()], project_name="explicit-one")

        kwargs = mock_rest_client.experiments.experiment_items_bulk.call_args.kwargs
        assert kwargs["project_name"] == "explicit-one"


class TestBulkUploadItemsBatching:
    def test_bulk_upload_items__more_items_than_max_batch_size__splits_by_count(
        self,
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        items_count = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE * 2 + 500

        experiment.bulk_upload_items(
            [_record(dataset_item_id=f"item-{i}") for i in range(items_count)]
        )

        batch_sizes = _sent_batch_sizes(mock_rest_client)
        assert batch_sizes == [
            constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE,
            constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE,
            500,
        ]
        assert sum(batch_sizes) == items_count

    def test_bulk_upload_items__items_exceeding_payload_limit__splits_by_size(
        self,
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        one_megabyte_payload = {"padding": "x" * 1_000_000}

        experiment.bulk_upload_items(
            [
                _record(
                    dataset_item_id=f"item-{i}",
                    trace=bulk_item.ExperimentItemBulkTrace(
                        start_time=START_TIME, output=one_megabyte_payload
                    ),
                )
                for i in range(10)
            ]
        )

        batch_sizes = _sent_batch_sizes(mock_rest_client)
        assert sum(batch_sizes) == 10
        assert len(batch_sizes) > 1
        # Each batch must stay under the backend's per-request ceiling.
        assert all(size <= 3 for size in batch_sizes)


class TestBulkUploadItemsSerialization:
    """Assert on the serialized request body rather than the mocked call.

    The backend maps ``evaluate_task_result`` to a Jackson ``JsonNode``, so an
    explicit JSON null deserializes to ``NullNode`` instead of Java ``null``.
    Sending ``"evaluate_task_result": null`` next to a trace therefore trips the
    "cannot provide both" validator with a 422. A mock-based test cannot see
    this — only the encoded body can.
    """

    @staticmethod
    def _sent_item(records: List[bulk_item.ExperimentItemBulkRecord]) -> Any:
        rest_client = rest_api_client.OpikApi(base_url="http://testserver", api_key="k")
        captured: dict = {}

        def capture(request: Any, **kwargs: Any) -> Any:
            captured["body"] = request.read()
            raise _StopAfterCapture()

        rest_client._client_wrapper.httpx_client.httpx_client._transport.handle_request = capture

        experiment = experiment_module.Experiment(
            id="experiment-id",
            name="experiment-name",
            dataset_name="dataset-name",
            rest_client=rest_client,
            streamer=Mock(),
            experiments_client=Mock(),
        )

        with pytest.raises(_StopAfterCapture):
            experiment.bulk_upload_items(records)

        return json.loads(captured["body"])["items"][0]

    def test_bulk_upload_items__trace_only__evaluate_task_result_is_omitted(
        self,
    ) -> None:
        sent_item = self._sent_item(
            [
                _record(
                    trace=bulk_item.ExperimentItemBulkTrace(
                        start_time=START_TIME, output={"answer": "a"}
                    )
                )
            ]
        )

        assert "evaluate_task_result" not in sent_item
        assert "trace" in sent_item

    def test_bulk_upload_items__evaluate_task_result_only__trace_is_omitted(
        self,
    ) -> None:
        sent_item = self._sent_item([_record(evaluate_task_result={"answer": "a"})])

        assert "trace" not in sent_item
        assert sent_item["evaluate_task_result"] == {"answer": "a"}

    def test_bulk_upload_items__no_spans_or_scores__keys_are_omitted(self) -> None:
        sent_item = self._sent_item([_record(evaluate_task_result={"answer": "a"})])

        assert "spans" not in sent_item
        assert "feedback_scores" not in sent_item


class TestBulkUploadItemsConcurrency:
    @pytest.mark.parametrize("num_threads", [1, 2, 4, 8, 16])
    def test_bulk_upload_items__any_thread_count__every_batch_is_sent_exactly_once(
        self, num_threads: int
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        items_count = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE * 4 + 7

        experiment.bulk_upload_items(
            [_record(dataset_item_id=f"item-{i}") for i in range(items_count)],
            num_threads=num_threads,
        )

        sent_dataset_item_ids = [
            item.dataset_item_id
            for call in mock_rest_client.experiments.experiment_items_bulk.call_args_list
            for item in call.kwargs["items"]
        ]
        assert sorted(sent_dataset_item_ids) == sorted(
            f"item-{i}" for i in range(items_count)
        )

    def test_bulk_upload_items__num_threads_below_one__raises_validation_error(
        self,
    ) -> None:
        experiment, mock_rest_client = _create_experiment()

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items([_record()], num_threads=0)

        assert "num_threads must be at least 1" in str(exc_info.value)
        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 0

    def test_bulk_upload_items__multiple_threads__batch_failure_propagates(
        self,
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        mock_rest_client.experiments.experiment_items_bulk.side_effect = ApiError(
            status_code=400, headers={}, body="bad request"
        )

        with pytest.raises(ApiError):
            experiment.bulk_upload_items(
                [
                    _record(dataset_item_id=f"item-{i}")
                    for i in range(constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE * 3)
                ],
                num_threads=4,
            )


class TestBulkUploadItemsValidation:
    @pytest.mark.parametrize("field_name", ["input", "output", "metadata"])
    def test_bulk_upload_items__trace_json_field_is_a_string__raises_validation_error(
        self, field_name: str
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        trace = bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, **{field_name: json.dumps({"a": 1})}
        )

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items([_record(trace=trace)])

        assert f"items[0].trace.{field_name} must be a dict" in str(exc_info.value)
        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 0

    def test_bulk_upload_items__span_output_is_a_string__raises_validation_error(
        self,
    ) -> None:
        experiment, _ = _create_experiment()
        span = bulk_item.ExperimentItemBulkSpan(start_time=START_TIME, output="plain")

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items([_record(spans=[span])])

        assert "items[0].spans[0].output must be a dict" in str(exc_info.value)

    def test_bulk_upload_items__both_trace_and_evaluate_task_result__raises_validation_error(
        self,
    ) -> None:
        experiment, _ = _create_experiment()

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items(
                [
                    _record(
                        evaluate_task_result={"answer": "a"},
                        trace=bulk_item.ExperimentItemBulkTrace(start_time=START_TIME),
                    )
                ]
            )

        assert "but not both" in str(exc_info.value)

    def test_bulk_upload_items__evaluate_task_result_is_a_string__raises_validation_error(
        self,
    ) -> None:
        experiment, _ = _create_experiment()

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items([_record(evaluate_task_result="plain")])

        assert "items[0].evaluate_task_result must be a dict" in str(exc_info.value)

    def test_bulk_upload_items__trace_project_name_differs_from_upload__raises_validation_error(
        self,
    ) -> None:
        experiment, _ = _create_experiment()
        trace = bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, project_name="other-project"
        )

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items(
                [_record(trace=trace)], project_name="upload-project"
            )

        assert "does not match the upload project_name" in str(exc_info.value)

    def test_bulk_upload_items__trace_project_name_differs_only_by_case__is_accepted(
        self,
    ) -> None:
        """The backend compares project names case-insensitively, so we must too."""
        experiment, mock_rest_client = _create_experiment()
        trace = bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, project_name="My-Project"
        )

        experiment.bulk_upload_items([_record(trace=trace)], project_name="my-project")

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 1

    def test_bulk_upload_items__all_failures_reported_together(self) -> None:
        experiment, _ = _create_experiment()

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items(
                [
                    _record(evaluate_task_result="plain"),
                    _record(dataset_item_id=""),
                ]
            )

        message = str(exc_info.value)
        assert "items[0].evaluate_task_result must be a dict" in message
        assert "items[1].dataset_item_id must be a non-empty string" in message

    def test_bulk_upload_items__single_item_larger_than_request_limit__raises_validation_error(
        self,
    ) -> None:
        """An oversized item would otherwise be sent alone and rejected with a 422."""
        experiment, mock_rest_client = _create_experiment()
        oversized_trace = bulk_item.ExperimentItemBulkTrace(
            start_time=START_TIME, output={"padding": "x" * 5_000_000}
        )

        with pytest.raises(exceptions.ValidationError) as exc_info:
            experiment.bulk_upload_items([_record(trace=oversized_trace)])

        assert "exceeds the" in str(exc_info.value)
        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 0


class TestBulkUploadItemsRateLimitRetry:
    @patch("opik.api_objects.rest_helpers._sleep")
    def test_bulk_upload_items__429_with_retry_after_header__retries_with_correct_delay(
        self, mock_sleep: Mock
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        mock_rest_client.experiments.experiment_items_bulk.side_effect = [
            ApiError(status_code=429, headers={"RateLimit-Reset": "5"}, body="limited"),
            None,
        ]

        experiment.bulk_upload_items([_record()])

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 2
        mock_sleep.assert_called_once_with(5.0)

    @patch("opik.api_objects.rest_helpers._sleep")
    def test_bulk_upload_items__429_without_header__uses_fallback_delay(
        self, mock_sleep: Mock
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        mock_rest_client.experiments.experiment_items_bulk.side_effect = [
            ApiError(status_code=429, headers={}, body="limited"),
            None,
        ]

        experiment.bulk_upload_items([_record()])

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 2
        mock_sleep.assert_called_once_with(1)

    @patch("opik.api_objects.rest_helpers._sleep")
    def test_bulk_upload_items__non_429_error__raises_without_retrying(
        self, mock_sleep: Mock
    ) -> None:
        experiment, mock_rest_client = _create_experiment()
        mock_rest_client.experiments.experiment_items_bulk.side_effect = ApiError(
            status_code=400, headers={}, body="bad request"
        )

        with pytest.raises(ApiError):
            experiment.bulk_upload_items([_record()])

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 1
        mock_sleep.assert_not_called()

    @patch("opik.api_objects.rest_helpers._sleep")
    def test_bulk_upload_items__batch_fails__remaining_batches_are_not_sent(
        self, mock_sleep: Mock
    ) -> None:
        """Fail-fast, matching Dataset.insert: the caller retries the whole call."""
        experiment, mock_rest_client = _create_experiment()
        mock_rest_client.experiments.experiment_items_bulk.side_effect = [
            None,
            ApiError(status_code=400, headers={}, body="bad request"),
            None,
        ]
        items_count = constants.EXPERIMENT_ITEMS_BULK_MAX_BATCH_SIZE * 2 + 1

        with pytest.raises(ApiError):
            experiment.bulk_upload_items(
                [_record(dataset_item_id=f"item-{i}") for i in range(items_count)]
            )

        assert mock_rest_client.experiments.experiment_items_bulk.call_count == 2
