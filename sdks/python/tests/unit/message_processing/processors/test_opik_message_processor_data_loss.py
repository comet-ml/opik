"""Unit tests for terminal-drop (data-loss) recording in OpikMessageProcessor.

Verifies that messages abandoned after a non-recoverable error are recorded in
the DataLossTracker, while transient/retried states (429 with a usable
RateLimit-Reset or Retry-After header) and successful sends are not.
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
from opik.rest_client_configurator import retry_decorator


def _spans_batch_message(item_count: int = 2, message_id: int = 1):
    msg = messages.CreateSpansBatchMessage(batch=[])
    msg.batch = [f"span-{index}" for index in range(item_count)]
    msg.message_id = message_id
    return msg


def _recorded(
    tracker: data_loss.DataLossTracker,
) -> list:
    """All drops the tracker has recorded, read via its public API."""
    # (0, 0) is the zero marker — the running totals before anything was recorded.
    _count, _items, failures = tracker.drops_since((0, 0))
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


@pytest.mark.parametrize(
    "headers",
    [{"Retry-After": "0"}, {"retry-after": "0"}],
    ids=["standard_name", "lower_case_name"],
)
def test_process__429_with_retry_after_only__requeued_with_minimum_delay_not_recorded(
    processor, rest_client, tracker, headers
):
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=429, headers=headers
    )

    with pytest.raises(exceptions.OpikCloudRequestsRateLimited) as exc_info:
        processor.process(_spans_batch_message())

    assert exc_info.value.retry_after == 1.0
    assert _recorded(tracker) == []


@pytest.fixture
def clock(monkeypatch) -> list:
    now = [1000.0]
    monkeypatch.setattr(online_message_processor.time, "monotonic", lambda: now[0])
    return now


def _send_rate_limited_with_retry_after(processor, rest_client) -> bool:
    """Returns whether the message was re-queued."""
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=429, headers={"Retry-After": "1"}
    )
    try:
        processor.process(_spans_batch_message())
    except exceptions.OpikCloudRequestsRateLimited:
        return True
    return False


def test_process__429_with_retry_after_only_for_60_seconds__dropped_as_client_error(
    processor, rest_client, tracker, clock
):
    assert _send_rate_limited_with_retry_after(processor, rest_client) is True
    clock[0] += 59
    assert _send_rate_limited_with_retry_after(processor, rest_client) is True
    clock[0] += 1
    assert _send_rate_limited_with_retry_after(processor, rest_client) is False

    failure = _recorded(tracker)[0]
    assert failure.reason == data_loss.FailureReason.HTTP_CLIENT_ERROR
    assert failure.status_code == 429


def test_process__429_with_retry_after_only_after_giving_up__next_message_dropped_at_once(
    processor, rest_client, tracker, clock
):
    _send_rate_limited_with_retry_after(processor, rest_client)
    clock[0] += 60
    _send_rate_limited_with_retry_after(processor, rest_client)
    clock[0] += 1

    assert _send_rate_limited_with_retry_after(processor, rest_client) is False
    assert len(_recorded(tracker)) == 2


def test_process__429_with_retry_after_only_after_a_successful_send__new_window(
    processor, rest_client, tracker, clock
):
    _send_rate_limited_with_retry_after(processor, rest_client)
    clock[0] += 50
    rest_client.spans.create_spans.side_effect = None
    processor.process(_spans_batch_message())
    clock[0] += 20

    assert _send_rate_limited_with_retry_after(processor, rest_client) is True
    assert _recorded(tracker) == []


def test_process__429_with_retry_after_only_after_an_attachment__same_window(
    processor, rest_client, tracker, clock
):
    _send_rate_limited_with_retry_after(processor, rest_client)
    clock[0] += 50
    attachment = messages.CreateAttachmentMessage(
        file_path="file.txt",
        file_name="file.txt",
        mime_type=None,
        entity_type="span",
        entity_id="span-id",
        project_name="project",
        encoded_url_override="url",
    )
    attachment.message_id = 2
    # Only hands the file to the upload manager, so it proves nothing about 429s.
    processor.process(attachment)
    clock[0] += 10

    assert _send_rate_limited_with_retry_after(processor, rest_client) is False


def test_process__429_with_retry_after_only_after_a_long_quiet_gap__new_window(
    processor, rest_client, tracker, clock
):
    _send_rate_limited_with_retry_after(processor, rest_client)
    clock[0] += 121

    assert _send_rate_limited_with_retry_after(processor, rest_client) is True
    assert _recorded(tracker) == []


def test_process__429__rest_client_retry_not_used(processor, rest_client, tracker):
    send = mock.Mock(
        side_effect=rest_api_core.ApiError(
            status_code=429, headers={"Retry-After": "1"}
        )
    )
    rest_client.spans.create_spans = retry_decorator.opik_rest_retry(send)

    with pytest.raises(exceptions.OpikCloudRequestsRateLimited):
        processor.process(_spans_batch_message())

    send.assert_called_once()


@pytest.mark.parametrize(
    "headers", [None, {}, {"Retry-After": "soon"}], ids=["none", "empty", "garbage"]
)
def test_process__429_without_usable_headers__recorded_as_client_error_data_loss(
    processor, rest_client, tracker, headers
):
    rest_client.spans.create_spans.side_effect = rest_api_core.ApiError(
        status_code=429, headers=headers
    )

    processor.process(_spans_batch_message(item_count=2))

    failure = _recorded(tracker)[0]
    assert failure.reason == data_loss.FailureReason.HTTP_CLIENT_ERROR
    assert failure.status_code == 429
    assert failure.item_count == 2


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
