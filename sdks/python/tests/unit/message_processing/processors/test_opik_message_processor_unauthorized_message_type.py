"""
Unit tests for OpikMessageProcessor interaction with UnauthorizedMessageTypeRegistry.

Tests cover:
- Unauthorized message types (is_authorized returns False) are skipped:
  handler not called, replay manager not touched.
- 401 ApiError triggers registry.add() with the message type.
- 401 ApiError still causes message unregistration (not a connection error).
- Non-401 errors do NOT add to the registry.
- End-to-end: after a 401 causes a type to be registered, later messages
  of that type are blocked; messages of other types are unaffected.
"""

import datetime
from unittest import mock

import pytest

from opik.message_processing import messages
from opik.message_processing import permissions
from opik.message_processing.processors import online_message_processor
from opik.message_processing.replay import replay_manager
from opik.rest_api import core as rest_api_core


def _create_trace_message(message_id: int = 1) -> messages.CreateTraceMessage:
    msg = messages.CreateTraceMessage(
        trace_id="trace-1",
        project_name="test-project",
        name="test-trace",
        start_time=datetime.datetime(
            2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
        ),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc),
        input={"query": "test"},
        output={"answer": "response"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=None,
    )
    msg.message_id = message_id
    return msg


def _create_span_message(message_id: int = 1) -> messages.CreateSpanMessage:
    msg = messages.CreateSpanMessage(
        span_id="span-1",
        trace_id="trace-1",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=datetime.datetime(
            2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
        ),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc),
        input={"prompt": "test"},
        output={"response": "result"},
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=None,
    )
    msg.message_id = message_id
    return msg


@pytest.fixture
def mock_rest_client() -> mock.MagicMock:
    return mock.MagicMock()


@pytest.fixture
def mock_file_uploader() -> mock.MagicMock:
    return mock.MagicMock()


@pytest.fixture
def mock_replay() -> mock.MagicMock:
    return mock.MagicMock(spec=replay_manager.ReplayManager)


@pytest.fixture
def mock_registry() -> mock.MagicMock:
    registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
    registry.is_authorized.return_value = True
    return registry


@pytest.fixture
def processor(
    mock_rest_client: mock.MagicMock,
    mock_file_uploader: mock.MagicMock,
    mock_replay: mock.MagicMock,
    mock_registry: mock.MagicMock,
) -> online_message_processor.OpikMessageProcessor:
    return online_message_processor.OpikMessageProcessor(
        rest_client=mock_rest_client,
        file_upload_manager=mock_file_uploader,
        fallback_replay_manager=mock_replay,
        unauthorized_message_types_registry=mock_registry,
    )


def test_process__authorized_message_type__handler_called(
    processor: online_message_processor.OpikMessageProcessor,
    mock_rest_client: mock.MagicMock,
):
    """Control: when is_authorized returns True, the handler must be invoked."""
    msg = _create_trace_message()
    processor.process(msg)

    mock_rest_client.traces.create_trace.assert_called_once()


class TestUnauthorizedMessageTypeBlocking:
    """Tests for the early-exit path when is_authorized() returns False.

    When is_authorized returns False, process() should return immediately
    without invoking the handler or any replay manager methods.
    """

    @pytest.fixture
    def unauthorized_processor(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        mock_replay: mock.MagicMock,
    ) -> online_message_processor.OpikMessageProcessor:
        registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
        registry.is_authorized.return_value = False
        return online_message_processor.OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=mock_replay,
            unauthorized_message_types_registry=registry,
        )

    def test_process__unauthorized_message_type__handler_not_called(
        self,
        unauthorized_processor: online_message_processor.OpikMessageProcessor,
        mock_rest_client: mock.MagicMock,
    ):
        """When the message type is unauthorized, no REST handler should fire."""
        msg = _create_trace_message()
        unauthorized_processor.process(msg)

        mock_rest_client.traces.create_trace.assert_not_called()

    def test_process__unauthorized_message_type__register_not_called(
        self,
        unauthorized_processor: online_message_processor.OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """When the message type is unauthorized, register_message must not be called."""
        msg = _create_trace_message()
        unauthorized_processor.process(msg)

        mock_replay.register_message.assert_not_called()

    def test_process__unauthorized_message_type__unregister_not_called(
        self,
        unauthorized_processor: online_message_processor.OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """When the message type is unauthorized, unregister_message must not be called."""
        msg = _create_trace_message()
        unauthorized_processor.process(msg)

        mock_replay.unregister_message.assert_not_called()

    def test_process__unauthorized_message_type__message_sent_failed_not_called(
        self,
        unauthorized_processor: online_message_processor.OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """When the message type is unauthorized, message_sent_failed must not be called."""
        msg = _create_trace_message()
        unauthorized_processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()

    def test_process__unauthorized_message_type__is_authorized_called_with_message_type(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        mock_replay: mock.MagicMock,
    ):
        """is_authorized must be invoked with message.message_type."""
        registry = mock.MagicMock(spec=permissions.UnauthorizedMessageTypeRegistry)
        registry.is_authorized.return_value = False

        proc = online_message_processor.OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=mock_replay,
            unauthorized_message_types_registry=registry,
        )

        msg = _create_trace_message()
        proc.process(msg)

        registry.is_authorized.assert_called_once_with(msg.message_type)


class TestUnauthorizedOnApiError401:
    """Tests for the 401 ApiError path.

    A 401 response must cause the message type to be added to the unauthorized
    registry. It should also unregister the message (not a connection error) but
    must not call message_sent_failed.
    """

    def test_process__api_error_401__adds_message_type_to_registry(
        self,
        processor: online_message_processor.OpikMessageProcessor,
        mock_registry: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """On 401, registry.add must be called with message.message_type."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=401, body="Unauthorized"
        )

        msg = _create_trace_message()
        processor.process(msg)

        mock_registry.add.assert_called_once_with(msg.message_type)

    def test_process__api_error_401__unregisters_message(
        self,
        processor: online_message_processor.OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """On 401, unregister_message must be called (not a connection error)."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=401, body="Unauthorized"
        )

        msg = _create_trace_message(message_id=20)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(20)

    def test_process__api_error_401__message_sent_failed_not_called(
        self,
        processor: online_message_processor.OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """On 401, message_sent_failed must NOT be called."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=401, body="Unauthorized"
        )

        msg = _create_trace_message()
        processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()

    @pytest.mark.parametrize(
        "status_code,body",
        [
            (500, "Internal Server Error"),
            (409, "Conflict"),
        ],
    )
    def test_process__api_error_not_401__does_not_add_to_registry(
        self,
        status_code,
        body,
        processor: online_message_processor.OpikMessageProcessor,
        mock_registry: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """A non-401 error must NOT call registry.add — only 401 triggers this."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=status_code, body=body
        )

        msg = _create_trace_message()
        processor.process(msg)

        mock_registry.add.assert_not_called()


class TestUnauthorizedMessageTypeEndToEnd:
    """Integration tests using a real UnauthorizedMessageTypeRegistry.

    These tests verify that after a 401 causes a type to be registered,
    later messages of that type are blocked, while other types continue
    to be processed normally.
    """

    def test_process__after_401_error__same_type_blocked(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        mock_replay: mock.MagicMock,
        capture_log,
    ):
        """After a 401 error registers a type, the next message of that type must
        be skipped — handler not called, replay not touched for the second message."""
        real_registry = permissions.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=3600  # long interval — type stays blocked
        )
        proc = online_message_processor.OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=mock_replay,
            unauthorized_message_types_registry=real_registry,
        )

        # The first call gets a 401 → type is registered as unauthorized
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=401, body="Unauthorized"
        )
        proc.process(_create_trace_message(message_id=1))

        # Reset call tracking for the second call
        mock_rest_client.reset_mock()
        mock_replay.reset_mock()

        # The second call — type is now blocked, handler should not fire
        proc.process(_create_trace_message(message_id=2))

        mock_rest_client.traces.create_trace.assert_not_called()
        mock_replay.register_message.assert_not_called()

        assert (
            capture_log.records[0].message
            == "Unauthorized message type 'CreateTraceMessage' processing request: Unauthorized"
        )
        assert capture_log.records[0].levelname == "ERROR"

    def test_process__after_401_error__different_type_not_blocked(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        mock_replay: mock.MagicMock,
    ):
        """After 401 blocks CreateTraceMessage, CreateSpanMessage must still
        be processed normally."""
        real_registry = permissions.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=3600
        )
        proc = online_message_processor.OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=mock_replay,
            unauthorized_message_types_registry=real_registry,
        )

        # Cause CreateTraceMessage to be blocked via a 401
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=401, body="Unauthorized"
        )
        proc.process(_create_trace_message(message_id=1))

        mock_rest_client.reset_mock()
        mock_replay.reset_mock()

        # CreateSpanMessage is a different type — should still go through
        proc.process(_create_span_message(message_id=2))

        mock_rest_client.spans.create_span.assert_called_once()

    def test_process__after_retry_interval__type_authorized_again(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        mock_replay: mock.MagicMock,
    ):
        """After the retry interval has elapsed, an unauthorized type should be
        re-authorized and its handler invoked."""
        real_registry = permissions.UnauthorizedMessageTypeRegistry(
            retry_interval_seconds=60
        )
        proc = online_message_processor.OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=mock_replay,
            unauthorized_message_types_registry=real_registry,
        )

        # Manually add the type with a timestamp far in the past so the
        # retry interval (60 s) has already elapsed
        past_time = 0.0
        real_registry.add(
            messages.CreateTraceMessage.message_type, attempt_time=past_time
        )

        mock_rest_client.reset_mock()
        mock_replay.reset_mock()

        # The handler should be called because the interval has elapsed
        mock_rest_client.traces.create_trace.side_effect = None  # success
        proc.process(_create_trace_message(message_id=3))

        mock_rest_client.traces.create_trace.assert_called_once()
