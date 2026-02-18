"""
Unit tests for streamer handling of create spans and traces messages with embedded attachments.

This module tests the complete flow of attachment processing through the streamer, including:
- AttachmentsPreprocessor wrapping messages
- Message flow through the queue
- Integration with the message processor
"""

from datetime import datetime
from unittest import mock

import pytest

from opik.message_processing import messages, streamer_constructors
from opik.message_processing.preprocessing import constants


@pytest.fixture
def streamer_with_attachments_enabled(fake_file_upload_manager):
    """Create a streamer with attachment extraction enabled."""
    tested = None
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
            use_attachment_extraction=True,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=mock.Mock(),
        )

        yield tested, mock_message_processor
    finally:
        if tested is not None:
            tested.close(timeout=5)


@pytest.fixture
def streamer_with_attachments_disabled(fake_file_upload_manager):
    """Create a streamer with attachment extraction disabled."""
    tested = None
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=mock.Mock(),
        )

        yield tested, mock_message_processor
    finally:
        if tested is not None:
            tested.close(timeout=5)


def test_streamer__create_span_message__attachments_enabled__does_not_wrap_without_end_time(
    streamer_with_attachments_enabled,
):
    """Test that CreateSpanMessage without end_time is NOT wrapped even when attachments are enabled."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    # Create a span message with potential attachment data but no end_time
    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=datetime.now(),
        end_time=None,  # No end_time - should not be wrapped
        input={"image": "base64-encoded-image-data"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=datetime.now(),
    )

    tested.put(span_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should NOT be wrapped in AttachmentSupportingMessage since end_time is None
    # (it may be batched into CreateSpansBatchMessage by the batching preprocessor)
    assert not isinstance(processed_message, messages.AttachmentSupportingMessage)


def test_streamer__create_trace_message__attachments_enabled__does_not_wrap_without_end_time(
    streamer_with_attachments_enabled,
):
    """Test that CreateTraceMessage without end_time is NOT wrapped even when attachments are enabled."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    # Create a trace message with potential attachment data but no end_time
    trace_message = messages.CreateTraceMessage(
        trace_id="trace-123",
        project_name="test-project",
        name="test-trace",
        start_time=datetime.now(),
        end_time=None,  # No end_time - should not be wrapped
        input={"document": "base64-encoded-pdf-data"},
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=datetime.now(),
    )

    tested.put(trace_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should NOT be wrapped in AttachmentSupportingMessage since end_time is None
    # (it may be batched into CreateTraceBatchMessage by the batching preprocessor)
    assert not isinstance(processed_message, messages.AttachmentSupportingMessage)


def test_streamer__create_span_message__attachments_disabled__does_not_wrap(
    streamer_with_attachments_disabled,
):
    """Test that CreateSpanMessage is NOT wrapped when attachments are disabled (can be batched)."""
    tested, mock_message_processor = streamer_with_attachments_disabled

    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=datetime.now(),
        end_time=None,
        input={"image": "base64-encoded-image-data"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=datetime.now(),
    )

    tested.put(span_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should NOT be wrapped in AttachmentSupportingMessage
    # (it may be batched into CreateSpansBatchMessage by the batching preprocessor)
    assert not isinstance(processed_message, messages.AttachmentSupportingMessage)


def test_streamer__create_trace_message__attachments_disabled__does_not_wrap(
    streamer_with_attachments_disabled,
):
    """Test that CreateTraceMessage is NOT wrapped when attachments are disabled (may be batched)."""
    tested, mock_message_processor = streamer_with_attachments_disabled

    trace_message = messages.CreateTraceMessage(
        trace_id="trace-123",
        project_name="test-project",
        name="test-trace",
        start_time=datetime.now(),
        end_time=None,
        input={"document": "base64-encoded-pdf-data"},
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=datetime.now(),
    )

    tested.put(trace_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should NOT be wrapped in AttachmentSupportingMessage
    # (it may be batched into CreateTraceBatchMessage by the batching preprocessor)
    assert not isinstance(processed_message, messages.AttachmentSupportingMessage)


def test_streamer__update_span_message__attachments_enabled__wraps_in_attachment_supporting_message(
    streamer_with_attachments_enabled,
):
    """Test that UpdateSpanMessage is wrapped in AttachmentSupportingMessage when attachments are enabled."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    update_span_message = messages.UpdateSpanMessage(
        span_id="span-update-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        end_time=datetime.now(),
        input=None,
        output={"result": "base64-encoded-data"},
        metadata=None,
        tags=None,
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
    )

    tested.put(update_span_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should be wrapped in AttachmentSupportingMessage
    assert isinstance(processed_message, messages.AttachmentSupportingMessage)
    assert processed_message.original_message is update_span_message


def test_streamer__update_trace_message__attachments_enabled__wraps_in_attachment_supporting_message(
    streamer_with_attachments_enabled,
):
    """Test that UpdateTraceMessage is wrapped in AttachmentSupportingMessage when attachments are enabled."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    update_trace_message = messages.UpdateTraceMessage(
        trace_id="trace-update-123",
        project_name="test-project",
        end_time=datetime.now(),
        input=None,
        output={"final_result": "base64-encoded-data"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
    )

    tested.put(update_trace_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should be wrapped in AttachmentSupportingMessage
    assert isinstance(processed_message, messages.AttachmentSupportingMessage)
    assert processed_message.original_message is update_trace_message


def test_streamer__multiple_span_messages__attachments_enabled__not_wrapped_without_end_time(
    streamer_with_attachments_enabled,
):
    """Test that multiple CreateSpanMessages without end_time are NOT wrapped even when attachments are enabled."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    span_messages = [
        messages.CreateSpanMessage(
            span_id=f"span-{i}",
            trace_id="trace-456",
            project_name="test-project",
            parent_span_id=None,
            name=f"test-span-{i}",
            start_time=datetime.now(),
            end_time=None,  # No end_time - should not be wrapped
            input={"data": f"base64-data-{i}"},
            output=None,
            metadata=None,
            tags=None,
            type="general",
            usage=None,
            model=None,
            provider=None,
            error_info=None,
            total_cost=None,
            last_updated_at=datetime.now(),
        )
        for i in range(3)
    ]

    for span_message in span_messages:
        tested.put(span_message)

    assert tested.flush(timeout=1.0) is True

    # Verify all messages were processed (can be batched)
    # When batching is enabled, they might be combined into a single batch
    assert mock_message_processor.process.call_count >= 1

    # Check that none of the messages are wrapped in AttachmentSupportingMessage
    for call in mock_message_processor.process.call_args_list:
        processed_message = call[0][0]
        assert not isinstance(processed_message, messages.AttachmentSupportingMessage)


def test_streamer__span_with_all_fields_populated__attachments_enabled__wraps_correctly(
    streamer_with_attachments_enabled,
):
    """Test that span messages with all fields populated are wrapped correctly."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    span_message = messages.CreateSpanMessage(
        span_id="span-full",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id="parent-span-789",
        name="test-span-full",
        start_time=datetime.now(),
        end_time=datetime.now(),
        input={"prompt": "test prompt", "image": "base64-image"},
        output={"response": "test response", "chart": "base64-chart"},
        metadata={"model": "gpt-4", "doc": "base64-pdf"},
        tags=["test", "full"],
        type="llm",
        usage={"prompt_tokens": 10, "completion_tokens": 20},
        model="gpt-4",
        provider="openai",
        error_info=None,
        total_cost=0.05,
        last_updated_at=datetime.now(),
    )

    tested.put(span_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should be wrapped
    assert isinstance(processed_message, messages.AttachmentSupportingMessage)

    # Verify the original message is preserved with all fields
    original = processed_message.original_message
    assert original.span_id == "span-full"
    assert original.input == {"prompt": "test prompt", "image": "base64-image"}
    assert original.output == {"response": "test response", "chart": "base64-chart"}
    assert original.metadata == {"model": "gpt-4", "doc": "base64-pdf"}


def test_streamer__trace_with_all_fields_populated__attachments_enabled__wraps_correctly(
    streamer_with_attachments_enabled,
):
    """Test that trace messages with all fields populated are wrapped correctly."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    trace_message = messages.CreateTraceMessage(
        trace_id="trace-full",
        project_name="test-project",
        name="test-trace-full",
        start_time=datetime.now(),
        end_time=datetime.now(),
        input={"request": "test request", "audio": "base64-audio"},
        output={"result": "test result", "video": "base64-video"},
        metadata={"session": "abc123", "file": "base64-file"},
        tags=["production", "important"],
        error_info=None,
        thread_id="thread-123",
        last_updated_at=datetime.now(),
    )

    tested.put(trace_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should be wrapped
    assert isinstance(processed_message, messages.AttachmentSupportingMessage)

    # Verify the original message is preserved with all fields
    original = processed_message.original_message
    assert original.trace_id == "trace-full"
    assert original.input == {"request": "test request", "audio": "base64-audio"}
    assert original.output == {"result": "test result", "video": "base64-video"}
    assert original.metadata == {"session": "abc123", "file": "base64-file"}
    assert original.thread_id == "thread-123"


def test_streamer__message_already_marked__attachments_enabled__does_not_wrap_twice(
    streamer_with_attachments_enabled,
):
    """Test that messages already marked are not wrapped again (prevents infinite recursion)."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    span_message = messages.CreateSpanMessage(
        span_id="span-marked",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=datetime.now(),
        end_time=None,
        input={"data": "value"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=datetime.now(),
    )

    # Mark the message as already processed
    setattr(span_message, constants.MARKER_ATTRIBUTE_NAME, True)

    tested.put(span_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should NOT be wrapped in AttachmentSupportingMessage since it's already marked
    # (it may still be batched into CreateSpansBatchMessage by the batching preprocessor)
    assert not isinstance(processed_message, messages.AttachmentSupportingMessage)


def test_streamer__null_input_output_metadata__attachments_enabled__doesnt_wraps(
    streamer_with_attachments_enabled,
):
    """Test that messages with None input/output/metadata are not wrapped."""
    tested, mock_message_processor = streamer_with_attachments_enabled

    span_message = messages.CreateSpanMessage(
        span_id="span-nulls",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=datetime.now(),
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=datetime.now(),
    )

    tested.put(span_message)
    assert tested.flush(timeout=1.0) is True

    # Verify the message was processed
    mock_message_processor.process.assert_called_once()
    processed_message = mock_message_processor.process.call_args[0][0]

    # The message should not be wrapped because it has no potential attachments
    assert not isinstance(processed_message, messages.AttachmentSupportingMessage)
