"""Unit tests for terminal-drop (data-loss) recording in OpikMessageProcessor.

Verifies that messages abandoned after a non-recoverable error are recorded in
the DataLossTracker, while transient/retried states (429 with usable headers)
and successful sends are not.
"""

from unittest import mock

import pydantic
import pytest
import tenacity

from opik import exceptions
from opik.message_processing import data_loss, messages, permissions
from opik.message_processing.processors import online_message_processor
from opik.message_processing.replay import replay_manager
from opik.rest_api import core as rest_api_core


def _spans_batch_message(item_count: int = 2, message_id: int = 1):
    msg = messages.CreateSpansBatchMessage(batch=[])
    msg.batch = [f"span-{index}" for index in range(item_count)]
    msg.message_id = message_id
    return msg


def _recorded(
    tracker: data_loss.DataLossTracker,
) -> list:
    """All drops the tracker has recorded, read via its public API."""
    _, failures = tracker.drops_since(0)
    return failures


@pytest.fixture
def tracker() -> data_loss.DataLossTracker:
    return data_loss.DataLossTracker()


@pytest.fixture
def rest_client() -> mock.MagicMock:
    return mock.MagicMock()


@pytest.fixture
def processor(
    rest_client: mock.MagicMock, tracker: data_loss.DataLossTracker
) -> online_message_processor.OpikMessageProcessor:
    registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
    registry.is_authorized.return_value = True
    return online_message_processor.OpikMessageProcessor(
        rest_client=rest_client,
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(spec=replay_manager.ReplayManager),
        unauthorized_message_types_registry=registry,
        data_loss_tracker=tracker,
    )


def test_process__batch_403__recorded_as_client_error_data_loss(
    processor, rest_client, tracker
):
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=403, body="<html>Forbidden</html>"
    )

    processor.process(_spans_batch_message(item_count=2))

    failures = _recorded(tracker)
    assert len(failures) == 1
    assert failures[0].reason == data_loss.FailureReason.HTTP_CLIENT_ERROR
    assert failures[0].status_code == 403
    assert failures[0].item_count == 2
    assert failures[0].message_type == "CreateSpansBatchMessage"


def test_process__batch_500__recorded_as_server_error_data_loss(
    processor, rest_client, tracker
):
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=500, body="oops"
    )

    processor.process(_spans_batch_message())

    assert _recorded(tracker)[0].reason == data_loss.FailureReason.HTTP_SERVER_ERROR


def test_process__unexpected_exception__recorded_as_unknown_data_loss(
    processor, rest_client, tracker
):
    rest_client.spans.create_spans.side_effect = ValueError("boom")

    processor.process(_spans_batch_message())

    assert _recorded(tracker)[0].reason == data_loss.FailureReason.UNKNOWN


def test_process__429_with_usable_headers__retried_not_recorded(
    processor, rest_client, tracker
):
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=429, headers={"retry-after": "1"}
    )

    with mock.patch.object(
        online_message_processor.rate_limit,
        "parse_rate_limit",
        return_value=mock.Mock(retry_after=mock.Mock(return_value=1.0)),
    ):
        with pytest.raises(exceptions.OpikCloudRequestsRateLimited):
            processor.process(_spans_batch_message())

    assert _recorded(tracker) == []


def test_process__successful_send__nothing_recorded(processor, tracker):
    processor.process(_spans_batch_message())

    assert _recorded(tracker) == []


def test_process__unauthorized_type_skipped__recorded_as_unauthorized(
    rest_client, tracker
):
    registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
    registry.is_authorized.return_value = False
    processor = online_message_processor.OpikMessageProcessor(
        rest_client=rest_client,
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(spec=replay_manager.ReplayManager),
        unauthorized_message_types_registry=registry,
        data_loss_tracker=tracker,
    )

    processor.process(_spans_batch_message())

    assert _recorded(tracker)[0].reason == data_loss.FailureReason.UNAUTHORIZED


def test_process__batch_401__recorded_as_unauthorized_and_type_registered(
    rest_client, tracker
):
    registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
    registry.is_authorized.return_value = True
    processor = online_message_processor.OpikMessageProcessor(
        rest_client=rest_client,
        file_upload_manager=mock.MagicMock(),
        fallback_replay_manager=mock.MagicMock(spec=replay_manager.ReplayManager),
        unauthorized_message_types_registry=registry,
        data_loss_tracker=tracker,
    )
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=401, body="no access"
    )

    processor.process(_spans_batch_message(item_count=2))

    failure = _recorded(tracker)[0]
    assert failure.reason == data_loss.FailureReason.UNAUTHORIZED
    assert failure.status_code == 401
    assert failure.item_count == 2
    # 401 also registers the type so it is not re-sent.
    registry.add.assert_called_once_with("CreateSpansBatchMessage")


def test_process__validation_error__recorded_as_serialization(
    processor, rest_client, tracker
):
    class _Model(pydantic.BaseModel):
        value: int

    try:
        _Model(value="not-an-int")
    except pydantic.ValidationError as validation_error:
        rest_client.spans.create_spans.side_effect = validation_error

    processor.process(_spans_batch_message(item_count=3))

    failure = _recorded(tracker)[0]
    assert failure.reason == data_loss.FailureReason.SERIALIZATION
    assert failure.item_count == 3


def test_process__retry_error__recorded_with_cause_status_code(
    processor, rest_client, tracker
):
    last_attempt = mock.Mock()
    last_attempt.exception.return_value = rest_api_core.ApiError(
        status_code=500, body="upstream down"
    )
    rest_client.spans.create_spans.side_effect = tenacity.RetryError(last_attempt)

    processor.process(_spans_batch_message())

    failure = _recorded(tracker)[0]
    assert failure.reason == data_loss.FailureReason.HTTP_SERVER_ERROR
    assert failure.status_code == 500


def test_process__429_without_usable_headers__recorded_as_client_error(
    processor, rest_client, tracker
):
    # A 429 whose headers can't be parsed into a retry directive can't be
    # re-enqueued, so it is a terminal drop and must be recorded (not silently
    # dropped) — this is exactly the loss the tracker exists to capture.
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=429, headers=None, body="slow down"
    )

    processor.process(_spans_batch_message(item_count=2))

    failure = _recorded(tracker)[0]
    assert failure.reason == data_loss.FailureReason.HTTP_CLIENT_ERROR
    assert failure.status_code == 429
    assert failure.item_count == 2
