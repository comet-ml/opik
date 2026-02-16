"""
Unit tests for OpikMessageProcessor interaction with ReplayManager.

Tests the three replay-related code paths in OpikMessageProcessor.process():
- register_message: called before every handler invocation
- unregister_message: called after successful handler execution
- message_sent_failed: called on httpx.ConnectError / httpx.TimeoutException
"""

import datetime
from unittest import mock

import httpx
import pytest

from opik.message_processing import messages
from opik.message_processing.processors.online_message_processor import (
    OpikMessageProcessor,
)
from opik.message_processing.replay import replay_manager
from opik.rest_api import core as rest_api_core
from opik import exceptions


def _create_trace_message(message_id: int = 1) -> messages.CreateTraceMessage:
    msg = messages.CreateTraceMessage(
        trace_id="trace-1",
        project_name="test-project",
        name="test-trace",
        start_time=datetime.datetime(2024, 1, 1, 12, 0, 0),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1),
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
        start_time=datetime.datetime(2024, 1, 1, 12, 0, 0),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1),
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
    # No spec= so nested attributes like .traces.create_trace are auto-created
    return mock.MagicMock()


@pytest.fixture
def mock_file_uploader() -> mock.MagicMock:
    return mock.MagicMock()


@pytest.fixture
def mock_replay() -> mock.MagicMock:
    return mock.MagicMock(spec=replay_manager.ReplayManager)


@pytest.fixture
def processor(
    mock_rest_client: mock.MagicMock,
    mock_file_uploader: mock.MagicMock,
    mock_replay: mock.MagicMock,
) -> OpikMessageProcessor:
    return OpikMessageProcessor(
        rest_client=mock_rest_client,
        file_upload_manager=mock_file_uploader,
        fallback_replay_manager=mock_replay,
    )


class TestRegisterMessage:
    def test_process__known_message__registers_before_handler(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """register_message must be called before the REST API handler fires."""
        call_order = []
        mock_replay.register_message.side_effect = lambda m: call_order.append(
            "register"
        )
        mock_rest_client.traces.create_trace.side_effect = (
            lambda **kw: call_order.append("handler")
        )

        msg = _create_trace_message()
        processor.process(msg)

        assert "register" in call_order
        assert "handler" in call_order
        assert call_order.index("register") < call_order.index("handler")

    def test_process__trace_message__registers_with_correct_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """register_message is called with the exact message object."""
        msg = _create_trace_message(message_id=42)
        processor.process(msg)

        mock_replay.register_message.assert_called_once_with(msg)

    def test_process__unknown_message_type__does_not_register(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """Unknown message types should be skipped without registration."""
        msg = mock.MagicMock(spec=messages.BaseMessage)
        # Use a type not in the handlers dict
        msg.__class__ = type("UnknownMessage", (messages.BaseMessage,), {})

        processor.process(msg)

        mock_replay.register_message.assert_not_called()

    def test_process__inactive_processor__does_not_register(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        mock_replay: mock.MagicMock,
    ):
        """When the processor is inactive, no registration should occur."""
        inactive_processor = OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=mock_replay,
            active=False,
        )

        msg = _create_trace_message()
        inactive_processor.process(msg)

        mock_replay.register_message.assert_not_called()


class TestUnregisterMessage:
    def test_process__successful_handler__unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """After successful handler execution, unregister_message is called."""
        msg = _create_trace_message(message_id=10)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(10)

    def test_process__successful_handler__unregisters_after_handler(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """unregister_message must be called after the handler completes."""
        call_order = []
        mock_rest_client.traces.create_trace.side_effect = (
            lambda **kw: call_order.append("handler")
        )
        mock_replay.unregister_message.side_effect = lambda mid: call_order.append(
            "unregister"
        )

        msg = _create_trace_message()
        processor.process(msg)

        assert call_order.index("handler") < call_order.index("unregister")


class TestMessageSentFailed:
    @pytest.mark.parametrize(
        "connection_error",
        [httpx.ConnectError("connection refused"), httpx.ReadTimeout("read timed out")],
    )
    def test_process__connection_error__marks_message_as_failed(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
        connection_error: Exception,
    ):
        """httpx.TimeoutException should trigger message_sent_failed."""
        mock_rest_client.traces.create_trace.side_effect = connection_error

        msg = _create_trace_message(message_id=8)
        processor.process(msg)

        mock_replay.message_sent_failed.assert_called_once_with(
            8, failure_reason=str(connection_error)
        )

        # assert that unregister_message was not called
        mock_replay.unregister_message.assert_not_called()


class TestErrorPathsNoFailedMark:
    """Verify that non-connection errors do NOT call message_sent_failed.
    The message stays registered (not unregistered, not marked failed)."""

    def test_process__api_error_409__no_failed_mark(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """409 Conflict is silently ignored and a message unregistered."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=409, body="conflict"
        )

        msg = _create_trace_message(message_id=1)
        processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()
        mock_replay.unregister_message.assert_called()

    def test_process__api_error_500__no_failed_mark(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """500 Server Error logs but does not mark as failed."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=500, body="internal error"
        )

        msg = _create_trace_message(message_id=2)
        processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()
        mock_replay.unregister_message.assert_called()

    def test_process__generic_exception__no_failed_mark(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """A generic Exception does not mark as failed."""
        mock_rest_client.traces.create_trace.side_effect = RuntimeError("unexpected")

        msg = _create_trace_message(message_id=3)
        processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()
        mock_replay.unregister_message.assert_called()


class TestApiError429WithRateLimiter:
    """Verify that a 429 ApiError with valid rate-limit headers raises
    OpikCloudRequestsRateLimited, leaving the message registered for retry."""

    def test_process__api_error_429_with_rate_limit_headers__no_unregister_no_failed(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """Neither unregister nor message_sent_failed is called — a message stays
        registered so the queue consumer can retry it after the rate-limit window."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=429, body="rate limited", headers={"RateLimit-Reset": "10"}
        )

        msg = _create_trace_message(message_id=3)

        with pytest.raises(exceptions.OpikCloudRequestsRateLimited):
            processor.process(msg)

        mock_replay.register_message.assert_called_once_with(msg)
        mock_replay.unregister_message.assert_not_called()
        mock_replay.message_sent_failed.assert_not_called()

    def test_process__api_error_429_without_rate_limit_headers__no_raise(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """429 without parseable rate-limit headers falls through to generic
        ApiError logging, unregisters the message, and does not raise."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=429, body="rate limited", headers={"Other-Header": "value"}
        )

        msg = _create_trace_message(message_id=4)

        # Should NOT raise — falls through to the generic ApiError branch
        processor.process(msg)

        mock_replay.register_message.assert_called_once_with(msg)

        # Not a connection error, so a message is unregistered
        mock_replay.unregister_message.assert_called_once_with(4)
        mock_replay.message_sent_failed.assert_not_called()

    def test_process__api_error_429_with_none_headers__no_raise(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """429 with headers=None skips rate-limit parsing, unregisters the
        message, and does not raise."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=429, body="rate limited", headers=None
        )

        msg = _create_trace_message(message_id=5)

        processor.process(msg)

        mock_replay.register_message.assert_called_once_with(msg)

        # Not a connection error, so a message is unregistered
        mock_replay.unregister_message.assert_called_once_with(5)
        mock_replay.message_sent_failed.assert_not_called()


class TestMultipleMessagesSequence:
    def test_process__success_then_connection_error__correct_replay_calls(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """The first message succeeds (register+unregister), the second fails with
        ConnectError (register+message_sent_failed)."""
        # The first call succeeds, the second raises ConnectError
        mock_rest_client.traces.create_trace.side_effect = [
            None,
            httpx.ConnectError("connection refused"),
        ]

        msg1 = _create_trace_message(message_id=1)
        msg2 = _create_trace_message(message_id=2)

        processor.process(msg1)
        processor.process(msg2)

        # Both registered
        assert mock_replay.register_message.call_count == 2

        # Only first unregistered
        mock_replay.unregister_message.assert_called_once_with(1)

        # Only second marked as failed
        mock_replay.message_sent_failed.assert_called_once_with(
            2, failure_reason="connection refused"
        )

    def test_process__connection_error_then_success__correct_replay_calls(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """The first message fails with a connection error, the second succeeds."""
        mock_rest_client.traces.create_trace.side_effect = [
            httpx.ConnectError("connection refused"),
            None,
        ]

        msg1 = _create_trace_message(message_id=1)
        msg2 = _create_trace_message(message_id=2)

        processor.process(msg1)
        processor.process(msg2)

        assert mock_replay.register_message.call_count == 2
        mock_replay.message_sent_failed.assert_called_once_with(
            1, failure_reason="connection refused"
        )
        mock_replay.unregister_message.assert_called_once_with(2)
