"""
Unit tests for OpikMessageProcessor handling AddAssertionResultsBatchMessage.
"""

from unittest import mock

import pytest

from opik.message_processing import messages
from opik.message_processing.processors.online_message_processor import (
    OpikMessageProcessor,
)
from opik.rest_api import core as rest_api_core
from opik.rest_api.types import assertion_result_batch_item, feedback_score_batch_item


@pytest.fixture
def mock_rest_client() -> mock.MagicMock:
    client = mock.MagicMock()
    # MagicMock blocks attributes starting with "assert" as protection against
    # typoed assertion methods; configure the assertion_results client explicitly.
    client.assertion_results = mock.MagicMock(unsafe=True)
    return client


@pytest.fixture
def processor(mock_rest_client: mock.MagicMock) -> OpikMessageProcessor:
    return OpikMessageProcessor(
        rest_client=mock_rest_client,
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(),
        unauthorized_message_types_registry=mock.Mock(
            is_authorized=mock.Mock(return_value=True)
        ),
    )


def _build_message(
    items=None,
    entity_type="TRACE",
) -> messages.AddAssertionResultsBatchMessage:
    items = items or [
        messages.AssertionResultMessage(
            entity_id="trace-1",
            project_name="proj-A",
            name="assertion 1",
            status="passed",
            source="sdk",
            reason="looked good",
        ),
        messages.AssertionResultMessage(
            entity_id="trace-1",
            project_name="proj-A",
            name="assertion 2",
            status="failed",
            source="sdk",
            reason=None,
        ),
    ]
    msg = messages.AddAssertionResultsBatchMessage(batch=items, entity_type=entity_type)
    msg.message_id = 1
    return msg


class TestAssertionResultsHandler:
    def test_process__assertion_results_batch__forwards_to_rest_client(
        self,
        processor: OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        message = _build_message()

        processor.process(message)

        mock_rest_client.assertion_results.store_assertions_batch.assert_called_once()
        call_kwargs = (
            mock_rest_client.assertion_results.store_assertions_batch.call_args.kwargs
        )
        assert call_kwargs["entity_type"] == "TRACE"
        sent_items = call_kwargs["assertion_results"]
        assert len(sent_items) == 2
        assert all(
            isinstance(item, assertion_result_batch_item.AssertionResultBatchItem)
            for item in sent_items
        )
        assert sent_items[0].entity_id == "trace-1"
        assert sent_items[0].name == "assertion 1"
        assert sent_items[0].status == "passed"
        assert sent_items[0].source == "sdk"
        assert sent_items[0].reason == "looked good"
        assert sent_items[1].status == "failed"
        assert sent_items[1].reason is None

    def test_process__assertion_results_batch__does_not_use_feedback_scores_endpoint(
        self,
        processor: OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        processor.process(_build_message())

        mock_rest_client.traces.score_batch_of_traces.assert_not_called()
        mock_rest_client.spans.score_batch_of_spans.assert_not_called()


class TestAssertionResultsFallback:
    """
    Forward-compat fallback for OPIK-6054 / OPIK-6048: if the dedicated
    assertion-results endpoint is missing on an older self-hosted backend,
    write through the legacy feedback-scores piggyback and remember the
    choice for the rest of the session.
    """

    def test_process__bad_endpoint_404__falls_back_to_feedback_scores(
        self,
        processor: OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        mock_rest_client.assertion_results.store_assertions_batch.side_effect = (
            rest_api_core.ApiError(status_code=404, body="Not Found")
        )

        processor.process(_build_message())

        mock_rest_client.traces.score_batch_of_traces.assert_called_once()
        sent = mock_rest_client.traces.score_batch_of_traces.call_args.kwargs["scores"]
        assert len(sent) == 2
        assert all(
            isinstance(item, feedback_score_batch_item.FeedbackScoreBatchItem)
            for item in sent
        )
        assert all(item.category_name == "suite_assertion" for item in sent)
        assert sent[0].id == "trace-1"
        assert sent[0].name == "assertion 1"
        assert sent[0].value == 1.0
        assert sent[0].source == "sdk"
        assert sent[1].value == 0.0  # status="failed"
        assert processor._assertion_results_endpoint_unavailable is True

    def test_process__bad_endpoint_405__falls_back_to_feedback_scores(
        self,
        processor: OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        mock_rest_client.assertion_results.store_assertions_batch.side_effect = (
            rest_api_core.ApiError(status_code=405, body="Method Not Allowed")
        )

        processor.process(_build_message())

        mock_rest_client.traces.score_batch_of_traces.assert_called_once()
        assert processor._assertion_results_endpoint_unavailable is True

    def test_process__non_404_api_error__does_not_set_fallback_or_call_legacy_path(
        self,
        processor: OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        mock_rest_client.assertion_results.store_assertions_batch.side_effect = (
            rest_api_core.ApiError(status_code=500, body="Internal Server Error")
        )

        # Public entrypoint: process() catches non-404/405 ApiError, logs,
        # and lets the replay manager handle the failed message. The
        # fallback flag must stay False and the legacy feedback-scores
        # endpoint must not be touched.
        processor.process(_build_message())

        mock_rest_client.assertion_results.store_assertions_batch.assert_called_once()
        mock_rest_client.traces.score_batch_of_traces.assert_not_called()
        assert processor._assertion_results_endpoint_unavailable is False

    def test_process__sticky_fallback__second_call_skips_new_endpoint(
        self,
        processor: OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        mock_rest_client.assertion_results.store_assertions_batch.side_effect = (
            rest_api_core.ApiError(status_code=404, body="Not Found")
        )

        processor.process(_build_message())
        # First call: tried new endpoint, hit 404, set the sticky flag.
        assert mock_rest_client.assertion_results.store_assertions_batch.call_count == 1
        assert processor._assertion_results_endpoint_unavailable is True

        # Reset feedback-scores call_count to isolate the second send.
        mock_rest_client.traces.score_batch_of_traces.reset_mock()
        processor.process(_build_message())

        # Second call must NOT retry the new endpoint.
        assert mock_rest_client.assertion_results.store_assertions_batch.call_count == 1
        # It went straight to the fallback.
        mock_rest_client.traces.score_batch_of_traces.assert_called_once()
