from unittest import mock

import pytest

from opik.api_objects.attachment import attachment, attachment_context
from opik.message_processing import messages
from opik.message_processing.preprocessing import constants
from opik.message_processing.processors import attachments_extraction_processor


@pytest.fixture
def mock_streamer():
    """Create a mock streamer."""
    return mock.Mock()


@pytest.fixture
def processor(mock_streamer):
    """Create an AttachmentsExtractionProcessor with default settings."""
    return attachments_extraction_processor.AttachmentsExtractionProcessor(
        min_attachment_size=20,
        messages_streamer=mock_streamer,
        url_override="https://example.com",
        is_active=True,
    )


@pytest.fixture
def inactive_processor(mock_streamer):
    """Create an inactive AttachmentsExtractionProcessor."""
    return attachments_extraction_processor.AttachmentsExtractionProcessor(
        min_attachment_size=20,
        messages_streamer=mock_streamer,
        url_override="https://example.com",
        is_active=False,
    )


def test_process_non_attachment_supporting_message_skips_processing(processor):
    """Test that non-AttachmentSupportingMessage messages are skipped."""
    # Create a regular message (not wrapped)
    regular_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input={"key": "value"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    # Process should return immediately without doing anything
    processor.process(regular_message)

    # Streamer should not be called
    processor.messages_streamer.put.assert_not_called()


def test_process_attachment_support_message_with_no_attachments(
    processor, mock_streamer
):
    """Test processing a message with no extractable attachments."""
    # Create an original message with plain text
    original_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input={"text": "just plain text"},
        output={"result": "no attachments here"},
        metadata={"info": "metadata"},
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    # Wrap in AttachmentSupportingMessage
    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Mock extractor to return no attachments
    with mock.patch.object(processor.extractor, "extract_and_replace", return_value=[]):
        processor.process(wrapped_message)

    # The original message should be re-queued with marker
    assert mock_streamer.put.call_count == 1
    requeued_message = mock_streamer.put.call_args[0][0]
    assert requeued_message is original_message
    assert hasattr(requeued_message, constants.MARKER_ATTRIBUTE_NAME)
    assert getattr(requeued_message, constants.MARKER_ATTRIBUTE_NAME) is True


def test_process_span_message_extracts_from_input(
    processor, mock_streamer, temp_file_15kb
):
    """Test extraction of attachments from span input."""
    # Create a span message with input containing base64
    original_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input={"image": "fake-base64-data"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Mock extracted attachment - use actual temp file
    mock_attachment = attachment.Attachment(
        data=temp_file_15kb.name,
        file_name="input-attachment-123.png",
        content_type="image/png",
        create_temp_copy=False,
    )

    mock_attachment_with_context = attachment_context.AttachmentWithContext(
        attachment_data=mock_attachment,
        entity_type="span",
        entity_id="span-123",
        project_name="test-project",
        context="input",
    )

    # Mock extractor to return one attachment from input
    def mock_extract(data, entity_type, entity_id, project_name, context):
        if context == "input":
            return [mock_attachment_with_context]
        return []

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    # Should have created one CreateAttachmentMessage and re-queued original
    assert mock_streamer.put.call_count == 2

    # First call should be the attachment message
    first_call = mock_streamer.put.call_args_list[0][0][0]
    assert isinstance(first_call, messages.CreateAttachmentMessage)
    assert first_call.entity_type == "span"
    assert first_call.entity_id == "span-123"
    assert first_call.project_name == "test-project"
    assert first_call.delete_after_upload is True

    # Second call should be the original message with marker
    second_call = mock_streamer.put.call_args_list[1][0][0]
    assert second_call is original_message
    assert hasattr(second_call, constants.MARKER_ATTRIBUTE_NAME)


def test_process_span_message_extracts_from_output(
    processor, mock_streamer, temp_file_15kb
):
    """Test extraction of attachments from the span output."""
    original_message = messages.CreateSpanMessage(
        span_id="span-789",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input=None,
        output={"image": "fake-base64-data"},
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    mock_attachment = attachment.Attachment(
        data=temp_file_15kb.name,
        file_name="output-attachment-456.png",
        content_type="image/png",
        create_temp_copy=False,
    )

    mock_attachment_with_context = attachment_context.AttachmentWithContext(
        attachment_data=mock_attachment,
        entity_type="span",
        entity_id="span-789",
        project_name="test-project",
        context="output",
    )

    def mock_extract(data, entity_type, entity_id, project_name, context):
        if context == "output":
            return [mock_attachment_with_context]
        return []

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    assert mock_streamer.put.call_count == 2
    attachment_msg = mock_streamer.put.call_args_list[0][0][0]
    assert isinstance(attachment_msg, messages.CreateAttachmentMessage)


def test_process_span_message_extracts_from_metadata(
    processor, mock_streamer, temp_file_15kb
):
    """Test extraction of attachments from span metadata."""
    original_message = messages.CreateSpanMessage(
        span_id="span-999",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input=None,
        output=None,
        metadata={"doc": "fake-base64-pdf"},
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    mock_attachment = attachment.Attachment(
        data=temp_file_15kb.name,
        file_name="metadata-attachment-789.pdf",
        content_type="application/pdf",
        create_temp_copy=False,
    )

    mock_attachment_with_context = attachment_context.AttachmentWithContext(
        attachment_data=mock_attachment,
        entity_type="span",
        entity_id="span-999",
        project_name="test-project",
        context="metadata",
    )

    def mock_extract(data, entity_type, entity_id, project_name, context):
        if context == "metadata":
            return [mock_attachment_with_context]
        return []

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    assert mock_streamer.put.call_count == 2
    attachment_msg = mock_streamer.put.call_args_list[0][0][0]
    assert isinstance(attachment_msg, messages.CreateAttachmentMessage)


def test_process_trace_message_extracts_attachments(
    processor, mock_streamer, temp_file_15kb
):
    """Test extraction from trace messages."""
    original_message = messages.CreateTraceMessage(
        trace_id="trace-123",
        project_name="test-project",
        name="test-trace",
        start_time=mock.Mock(),
        end_time=None,
        input={"data": "fake-base64"},
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    mock_attachment = attachment.Attachment(
        data=temp_file_15kb.name,
        file_name="input-attachment-trace.png",
        content_type="image/png",
        create_temp_copy=False,
    )

    mock_attachment_with_context = attachment_context.AttachmentWithContext(
        attachment_data=mock_attachment,
        entity_type="trace",
        entity_id="trace-123",
        project_name="test-project",
        context="input",
    )

    def mock_extract(data, entity_type, entity_id, project_name, context):
        if context == "input":
            return [mock_attachment_with_context]
        return []

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    # Should create an attachment message and re-queue the original message
    assert mock_streamer.put.call_count == 2

    # Verify it's a trace entity type
    attachment_msg = mock_streamer.put.call_args_list[0][0][0]
    assert isinstance(attachment_msg, messages.CreateAttachmentMessage)
    assert attachment_msg.entity_type == "trace"
    assert attachment_msg.entity_id == "trace-123"
    assert attachment_msg.delete_after_upload is True

    # Last call should be the original message with marker
    second_call = mock_streamer.put.call_args_list[1][0][0]
    assert second_call is original_message
    assert hasattr(second_call, constants.MARKER_ATTRIBUTE_NAME)


def test_process_update_span_message(processor, mock_streamer, temp_file_15kb):
    """Test extraction from UpdateSpanMessage."""
    original_message = messages.UpdateSpanMessage(
        span_id="span-update-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        end_time=mock.Mock(),
        input={"image": "base64-data"},
        output=None,
        metadata=None,
        tags=None,
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    mock_attachment = attachment.Attachment(
        data=temp_file_15kb.name,
        file_name="input-attachment.png",
        content_type="image/png",
        create_temp_copy=False,
    )

    mock_attachment_with_context = attachment_context.AttachmentWithContext(
        attachment_data=mock_attachment,
        entity_type="span",
        entity_id="span-update-123",
        project_name="test-project",
        context="input",
    )

    def mock_extract(data, entity_type, entity_id, project_name, context):
        if context == "input":
            return [mock_attachment_with_context]
        return []

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    assert mock_streamer.put.call_count == 2

    attachment_msg = mock_streamer.put.call_args_list[0][0][0]
    assert isinstance(attachment_msg, messages.CreateAttachmentMessage)


def test_process_update_trace_message(processor, mock_streamer, temp_file_15kb):
    """Test extraction from UpdateTraceMessage."""
    original_message = messages.UpdateTraceMessage(
        trace_id="trace-update-123",
        project_name="test-project",
        end_time=mock.Mock(),
        input=None,
        output={"result": "base64-data"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    mock_attachment = attachment.Attachment(
        data=temp_file_15kb.name,
        file_name="output-attachment.png",
        content_type="image/png",
        create_temp_copy=False,
    )

    mock_attachment_with_context = attachment_context.AttachmentWithContext(
        attachment_data=mock_attachment,
        entity_type="trace",
        entity_id="trace-update-123",
        project_name="test-project",
        context="output",
    )

    def mock_extract(data, entity_type, entity_id, project_name, context):
        if context == "output":
            return [mock_attachment_with_context]
        return []

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    assert mock_streamer.put.call_count == 2

    attachment_msg = mock_streamer.put.call_args_list[0][0][0]
    assert isinstance(attachment_msg, messages.CreateAttachmentMessage)


def test_process_multiple_attachments_from_different_contexts(
    processor, mock_streamer, temp_file_15kb
):
    """Test extraction of multiple attachments from input, output, and metadata."""
    original_message = messages.CreateSpanMessage(
        span_id="span-multi",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input={"image1": "base64"},
        output={"image2": "base64"},
        metadata={"doc": "base64"},
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Create three attachments for different contexts
    attachments_map = {
        "input": [
            attachment_context.AttachmentWithContext(
                attachment_data=attachment.Attachment(
                    data=temp_file_15kb.name,
                    file_name="input-att.png",
                    content_type="image/png",
                    create_temp_copy=False,
                ),
                entity_type="span",
                entity_id="span-multi",
                project_name="test-project",
                context="input",
            )
        ],
        "output": [
            attachment_context.AttachmentWithContext(
                attachment_data=attachment.Attachment(
                    data=temp_file_15kb.name,
                    file_name="output-att.png",
                    content_type="image/png",
                    create_temp_copy=False,
                ),
                entity_type="span",
                entity_id="span-multi",
                project_name="test-project",
                context="output",
            )
        ],
        "metadata": [
            attachment_context.AttachmentWithContext(
                attachment_data=attachment.Attachment(
                    data=temp_file_15kb.name,
                    file_name="meta-att.pdf",
                    content_type="application/pdf",
                    create_temp_copy=False,
                ),
                entity_type="span",
                entity_id="span-multi",
                project_name="test-project",
                context="metadata",
            )
        ],
    }

    def mock_extract(data, entity_type, entity_id, project_name, context):
        return attachments_map.get(context, [])

    with mock.patch.object(
        processor.extractor, "extract_and_replace", side_effect=mock_extract
    ):
        processor.process(wrapped_message)

    # Should create 3 attachment messages + 1 re-queued original = 4 total
    assert mock_streamer.put.call_count == 4

    # First 3 should be attachment messages
    for i in range(3):
        msg = mock_streamer.put.call_args_list[i][0][0]
        assert isinstance(msg, messages.CreateAttachmentMessage)

    # Last should be the original message
    last_msg = mock_streamer.put.call_args_list[3][0][0]
    assert last_msg is original_message


def test_process_with_null_input_output_metadata(processor, mock_streamer):
    """Test processing message with null input/output/metadata fields."""
    original_message = messages.CreateSpanMessage(
        span_id="span-nulls",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
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
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Extractor should never be called since all fields are None
    with mock.patch.object(processor.extractor, "extract_and_replace") as mock_extract:
        processor.process(wrapped_message)

        # Extractor should not be called
        mock_extract.assert_not_called()

    # Only the original message should be re-queued
    assert mock_streamer.put.call_count == 1


def test_process_inactive_processor_skips_extraction(inactive_processor, mock_streamer):
    """Test that the inactive processor skips extraction but still re-queues a message."""
    original_message = messages.CreateSpanMessage(
        span_id="span-inactive",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input={"image": "base64-data"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Should not call extractor since the processor is inactive
    with mock.patch.object(
        inactive_processor.extractor, "extract_and_replace"
    ) as mock_extract:
        inactive_processor.process(wrapped_message)

        # Extractor should not be called
        mock_extract.assert_not_called()

    # Original message should still be re-queued with marker
    assert mock_streamer.put.call_count == 1
    requeued_message = mock_streamer.put.call_args[0][0]
    assert requeued_message is original_message
    assert hasattr(requeued_message, constants.MARKER_ATTRIBUTE_NAME)


def test_process_handles_extraction_exception_gracefully(processor, mock_streamer):
    """Test that extraction exceptions are caught and logged, but processing continues."""
    original_message = messages.CreateSpanMessage(
        span_id="span-error",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
        end_time=None,
        input={"data": "causes-error"},
        output=None,
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=mock.Mock(),
    )

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Mock extractor to raise an exception
    with mock.patch.object(
        processor.extractor,
        "extract_and_replace",
        side_effect=Exception("Extraction failed"),
    ):
        # Should not raise exception
        processor.process(wrapped_message)

    # Original message should still be re-queued despite the error
    assert mock_streamer.put.call_count == 1
    requeued_message = mock_streamer.put.call_args[0][0]
    assert requeued_message is original_message
    assert hasattr(requeued_message, constants.MARKER_ATTRIBUTE_NAME)


def test_process_message_already_marked_returns_early(processor):
    """Test that messages already marked with a preprocessing attribute are handled."""
    # This test verifies behavior when a message with marker is wrapped
    # In practice, preprocess_message() should prevent this, but we test defense
    original_message = messages.CreateSpanMessage(
        span_id="span-marked",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=mock.Mock(),
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
        last_updated_at=mock.Mock(),
    )

    # Mark the message as already processed
    setattr(original_message, constants.MARKER_ATTRIBUTE_NAME, True)

    wrapped_message = messages.AttachmentSupportingMessage(
        original_message=original_message
    )

    # Mock extractor to verify it's still called (marker is on the original, not wrapper)
    with mock.patch.object(
        processor.extractor, "extract_and_replace", return_value=[]
    ) as mock_extract:
        processor.process(wrapped_message)

        # Extractor should still be called since the wrapper doesn't have the marker
        mock_extract.assert_called()


def test_entity_type_from_attachment_message_span():
    """Test entity_type_from_attachment_message extracts correct details for span."""
    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test",
        start_time=mock.Mock(),
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
        last_updated_at=mock.Mock(),
    )

    result = attachments_extraction_processor.entity_type_from_attachment_message(
        span_message
    )

    assert result is not None
    assert result.entity_type == "span"
    assert result.entity_id == "span-123"
    assert result.project_name == "test-project"


def test_entity_type_from_attachment_message_trace():
    """Test entity_type_from_attachment_message extracts correct details for trace."""
    trace_message = messages.CreateTraceMessage(
        trace_id="trace-789",
        project_name="my-project",
        name="test-trace",
        start_time=mock.Mock(),
        end_time=None,
        input=None,
        output=None,
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=mock.Mock(),
    )

    result = attachments_extraction_processor.entity_type_from_attachment_message(
        trace_message
    )

    assert result is not None
    assert result.entity_type == "trace"
    assert result.entity_id == "trace-789"
    assert result.project_name == "my-project"


def test_entity_type_from_attachment_message_unsupported():
    """Test entity_type_from_attachment_message returns None for unsupported message types."""
    unsupported_message = messages.BaseMessage()

    result = attachments_extraction_processor.entity_type_from_attachment_message(
        unsupported_message
    )

    assert result is None
