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
import pydantic
import pytest
import tenacity

from opik.message_processing import messages
from opik.message_processing.processors import online_message_processor
from opik.message_processing.processors.online_message_processor import (
    OpikMessageProcessor,
)
from opik.file_upload.s3_multipart_upload import s3_upload_error
from opik.message_processing.replay import replay_manager, db_manager
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


def _create_attachment_message(message_id: int = 1) -> messages.CreateAttachmentMessage:
    msg = messages.CreateAttachmentMessage(
        file_path="/tmp/test-file.bin",
        file_name="test-file.bin",
        mime_type="application/octet-stream",
        entity_type="trace",
        entity_id="trace-1",
        project_name="test-project",
        encoded_url_override="aHR0cDovL2xvY2FsaG9zdA==",
        delete_after_upload=False,
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


class TestNoServerConnection:
    """Tests for when has_server_connection is False — messages should be
    registered as failed and the handler should NOT execute."""

    @pytest.fixture
    def offline_replay(self) -> mock.MagicMock:
        m = mock.MagicMock(spec=replay_manager.ReplayManager)
        m.has_server_connection = False
        return m

    @pytest.fixture
    def offline_processor(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
        offline_replay: mock.MagicMock,
    ) -> OpikMessageProcessor:
        return OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=offline_replay,
        )

    def test_process__no_connection__registers_message_as_failed(
        self,
        offline_processor: OpikMessageProcessor,
        offline_replay: mock.MagicMock,
    ):
        """When there is no server connection, register_message must be called
        with status=MessageStatus.failed."""
        msg = _create_trace_message(message_id=10)
        offline_processor.process(msg)

        offline_replay.register_message.assert_called_once_with(
            msg, status=db_manager.MessageStatus.failed
        )
        offline_replay.unregister_message.assert_not_called()

    def test_process__no_connection__handler_not_called(
        self,
        offline_processor: OpikMessageProcessor,
        offline_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """When there is no server connection, the REST API handler must NOT
        be invoked."""
        msg = _create_trace_message()
        offline_processor.process(msg)

        mock_rest_client.traces.create_trace.assert_not_called()
        offline_replay.unregister_message.assert_not_called()

    def test_process__no_connection__unregister_not_called(
        self,
        offline_processor: OpikMessageProcessor,
        offline_replay: mock.MagicMock,
    ):
        """When there is no server connection, unregister_message must NOT be
        called since the handler is skipped."""
        msg = _create_trace_message()
        offline_processor.process(msg)

        offline_replay.unregister_message.assert_not_called()

    def test_process__no_connection__message_sent_failed_not_called(
        self,
        offline_processor: OpikMessageProcessor,
        offline_replay: mock.MagicMock,
    ):
        """message_sent_failed should NOT be called — it is only for connection
        errors that occur during handler execution."""
        msg = _create_trace_message()
        offline_processor.process(msg)

        offline_replay.message_sent_failed.assert_not_called()


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

    def test_process__api_error_409__unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """409 Conflict triggers an explicit unregister_message call and early
        return (the message is considered delivered)."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=409, body="conflict"
        )

        msg = _create_trace_message(message_id=11)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(11)

    def test_process__api_error_500__unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """Non-connection API errors (e.g. 500) still unregister the message."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=500, body="internal error"
        )

        msg = _create_trace_message(message_id=12)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(12)

    def test_process__retry_error__unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """tenacity.RetryError unregisters the message (not a connection error)."""
        future = tenacity.Future(attempt_number=1)
        future.set_exception(RuntimeError("underlying cause"))
        mock_rest_client.traces.create_trace.side_effect = tenacity.RetryError(
            last_attempt=future,
        )

        msg = _create_trace_message(message_id=13)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(13)

    def test_process__validation_error__unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """pydantic.ValidationError unregisters the message."""
        # Generate a real ValidationError via pydantic
        validation_error: pydantic.ValidationError
        try:
            pydantic.TypeAdapter(int).validate_python("not-an-int")
        except pydantic.ValidationError as e:
            validation_error = e

        mock_rest_client.traces.create_trace.side_effect = validation_error

        msg = _create_trace_message(message_id=14)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(14)

    def test_process__generic_exception__unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """A generic Exception still unregisters the message."""
        mock_rest_client.traces.create_trace.side_effect = RuntimeError("unexpected")

        msg = _create_trace_message(message_id=15)
        processor.process(msg)

        mock_replay.unregister_message.assert_called_once_with(15)

    @pytest.mark.parametrize(
        "connection_error",
        [httpx.ConnectError("refused"), httpx.ReadTimeout("timed out")],
    )
    def test_process__connection_error__does_not_unregister(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
        connection_error: Exception,
    ):
        """httpx connection/timeout errors must NOT unregister — the message
        stays registered for replay."""
        mock_rest_client.traces.create_trace.side_effect = connection_error

        msg = _create_trace_message(message_id=16)
        processor.process(msg)

        mock_replay.unregister_message.assert_not_called()

    def test_process__api_error_429_with_rate_limit__does_not_unregister(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_rest_client: mock.MagicMock,
    ):
        """429 with valid rate-limit headers raises and leaves the message
        registered for retry — unregister must NOT be called."""
        mock_rest_client.traces.create_trace.side_effect = rest_api_core.ApiError(
            status_code=429, body="rate limited", headers={"RateLimit-Reset": "10"}
        )

        msg = _create_trace_message(message_id=17)

        with pytest.raises(exceptions.OpikCloudRequestsRateLimited):
            processor.process(msg)

        mock_replay.unregister_message.assert_not_called()


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


class TestAttachmentUploadCallbacks:
    """Tests for CreateAttachmentMessage upload callback interactions with
    the replay manager.

    The attachment handler passes on_upload_success / on_upload_failed callbacks
    to the file uploader. These callbacks are responsible for calling
    unregister_message or message_sent_failed on the replay manager."""

    def test_process__attachment__registers_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """CreateAttachmentMessage should be registered before the upload handler."""
        msg = _create_attachment_message(message_id=50)
        processor.process(msg)

        mock_replay.register_message.assert_called_once_with(msg)

    def test_process__attachment__passes_callbacks_to_uploader(
        self,
        processor: OpikMessageProcessor,
        mock_file_uploader: mock.MagicMock,
    ):
        """The file uploader must receive on_upload_success and on_upload_failed
        callbacks."""
        msg = _create_attachment_message(message_id=50)
        processor.process(msg)

        mock_file_uploader.upload.assert_called_once()
        call_kwargs = mock_file_uploader.upload.call_args
        assert call_kwargs.kwargs["on_upload_success"] is not None
        assert call_kwargs.kwargs["on_upload_failed"] is not None

    def test_process__attachment__success_callback_unregisters_message(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When the on_upload_success callback fires, it should call
        unregister_message with the correct message_id."""
        msg = _create_attachment_message(message_id=60)

        # Capture the callback by invoking it inside the mock upload
        def fake_upload(message, on_upload_success=None, on_upload_failed=None):
            on_upload_success()

        mock_file_uploader.upload.side_effect = fake_upload
        mock_replay.reset_mock()

        processor.process(msg)

        mock_replay.unregister_message.assert_any_call(60)

    def test_process__attachment__failed_callback_connect_error__marks_failed(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When on_upload_failed fires with httpx.ConnectError, it should call
        message_sent_failed."""
        msg = _create_attachment_message(message_id=70)
        error = httpx.ConnectError("connection refused")

        def fake_upload(message, on_upload_success=None, on_upload_failed=None):
            on_upload_failed(error)

        mock_file_uploader.upload.side_effect = fake_upload
        mock_replay.reset_mock()

        processor.process(msg)

        mock_replay.message_sent_failed.assert_called_once_with(
            70, failure_reason=str(error)
        )

    def test_process__attachment__failed_callback_timeout__marks_failed(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When on_upload_failed fires with httpx.TimeoutException, it should call
        message_sent_failed."""
        msg = _create_attachment_message(message_id=71)
        error = httpx.ReadTimeout("read timed out")

        def fake_upload(message, on_upload_success=None, on_upload_failed=None):
            on_upload_failed(error)

        mock_file_uploader.upload.side_effect = fake_upload
        mock_replay.reset_mock()

        processor.process(msg)

        mock_replay.message_sent_failed.assert_called_once_with(
            71, failure_reason=str(error)
        )

    def test_process__attachment__failed_callback_s3_connection_error__marks_failed(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When on_upload_failed fires with S3UploadError(connection_error=True),
        it should call message_sent_failed."""
        msg = _create_attachment_message(message_id=72)
        error = s3_upload_error.S3UploadError(
            reason="connection lost", connection_error=True
        )

        def fake_upload(message, on_upload_success=None, on_upload_failed=None):
            on_upload_failed(error)

        mock_file_uploader.upload.side_effect = fake_upload
        mock_replay.reset_mock()

        processor.process(msg)

        mock_replay.message_sent_failed.assert_called_once_with(
            72, failure_reason=str(error)
        )

    def test_process__attachment__failed_callback_s3_non_connection_error__no_failed_mark(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When on_upload_failed fires with S3UploadError(connection_error=False),
        message_sent_failed should NOT be called."""
        msg = _create_attachment_message(message_id=73)
        error = s3_upload_error.S3UploadError(
            reason="file too large", connection_error=False
        )

        def fake_upload(message, on_upload_success=None, on_upload_failed=None):
            on_upload_failed(error)

        mock_file_uploader.upload.side_effect = fake_upload
        mock_replay.reset_mock()

        processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()
        mock_replay.unregister_message.assert_called_once_with(73)

    def test_process__attachment__failed_callback_generic_error__no_failed_mark(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When on_upload_failed fires with a non-connection error (e.g. ValueError),
        message_sent_failed should NOT be called."""
        msg = _create_attachment_message(message_id=74)
        error = ValueError("bad data")

        def fake_upload(message, on_upload_success=None, on_upload_failed=None):
            on_upload_failed(error)

        mock_file_uploader.upload.side_effect = fake_upload
        mock_replay.reset_mock()

        processor.process(msg)

        mock_replay.message_sent_failed.assert_not_called()
        mock_replay.unregister_message.assert_called_once_with(74)

    def test_process__attachment_no_connection__registers_as_failed_and_skips_upload(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """When has_server_connection is False, the attachment message should be
        registered as failed and the file uploader should NOT be called."""
        offline_replay = mock.MagicMock(spec=replay_manager.ReplayManager)
        offline_replay.has_server_connection = False
        offline_processor = OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=offline_replay,
        )

        msg = _create_attachment_message(message_id=80)
        offline_processor.process(msg)

        offline_replay.register_message.assert_called_once_with(
            msg, status=db_manager.MessageStatus.failed
        )
        mock_file_uploader.upload.assert_not_called()
        offline_replay.unregister_message.assert_not_called()


def _create_attachment_supporting_message(
    message_id: int = 1,
) -> messages.AttachmentSupportingMessage:
    inner = _create_trace_message(message_id)
    msg = messages.AttachmentSupportingMessage(original_message=inner)
    msg.message_id = message_id
    return msg


class TestIgnoredMessageTypes:
    """Tests for messages listed in _ignored_message_types_for_replay.

    AttachmentSupportingMessage is the sole type on this list. Such messages
    bypass replay registration entirely — the handler runs directly regardless
    of connection state, and replay manager methods are never invoked.
    """

    def test_process__ignored_type__no_replay_on_online_connection__handler_still_called(
        self,
        processor: OpikMessageProcessor,
        mock_replay: mock.MagicMock,
    ):
        """All three replay manager methods are untouched for ignored types when online."""
        noop_handler = mock.MagicMock(
            spec=online_message_processor.MessageProcessingHandler
        )
        processor.register_message_handler(
            handler=noop_handler, message_type=messages.AttachmentSupportingMessage
        )

        msg = _create_attachment_supporting_message(message_id=106)
        processor.process(msg)

        # check that the noop handler was called
        noop_handler.assert_called_once_with(msg)

        # No replay interaction at all
        mock_replay.register_message.assert_not_called()
        mock_replay.unregister_message.assert_not_called()
        mock_replay.message_sent_failed.assert_not_called()

    def test_process__ignored_type__no_replay_called_on_offline_connection__handler_still_called(
        self,
        mock_rest_client: mock.MagicMock,
        mock_file_uploader: mock.MagicMock,
    ):
        """The handler (noop) must be invoked for ignored types even when in offline mode —
        replay state does not block execution for these message types."""
        offline_replay = mock.MagicMock(spec=replay_manager.ReplayManager)
        offline_replay.has_server_connection = False
        offline_processor = OpikMessageProcessor(
            rest_client=mock_rest_client,
            file_upload_manager=mock_file_uploader,
            fallback_replay_manager=offline_replay,
        )

        noop_handler = mock.MagicMock(
            spec=online_message_processor.MessageProcessingHandler
        )
        offline_processor.register_message_handler(
            handler=noop_handler, message_type=messages.AttachmentSupportingMessage
        )

        # Process completes without error — the noop handler runs
        msg = _create_attachment_supporting_message(message_id=105)
        offline_processor.process(msg)

        # check that the noop handler was called
        noop_handler.assert_called_once_with(msg)

        # No replay interaction at all
        offline_replay.register_message.assert_not_called()
        offline_replay.unregister_message.assert_not_called()
        offline_replay.message_sent_failed.assert_not_called()
