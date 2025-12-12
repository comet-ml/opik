from unittest import mock

from opik.message_processing import messages
from opik.message_processing.preprocessing import constants, attachments_preprocessor


def test_preprocess_message_does_not_wrap_create_span_without_end_time():
    """Test preprocess_message does not wrap CreateSpanMessage without end_time."""
    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test",
        start_time=mock.Mock(),
        end_time=None,
        input={"input_key": "input_value"},
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

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(span_message)

    # Should return the original message unchanged since end_time is None
    assert result is span_message
    assert not isinstance(result, messages.AttachmentSupportingMessage)


def test_preprocess_message_does_not_wrap_create_trace_without_end_time():
    """Test preprocess_message does not wrap CreateTraceMessage without end_time."""
    trace_message = messages.CreateTraceMessage(
        trace_id="trace-789",
        project_name="test-project",
        name="test-trace",
        start_time=mock.Mock(),
        end_time=None,
        input=None,
        output={"output_key": "output_value"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=mock.Mock(),
    )

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(trace_message)

    # Should return the original message unchanged since end_time is None
    assert result is trace_message
    assert not isinstance(result, messages.AttachmentSupportingMessage)


def test_preprocess_message_does_not_wrap_other_messages():
    """Test preprocess_message doesn't wrap unsupported message types."""
    base_message = messages.BaseMessage()

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(base_message)

    # Should return the original message unchanged
    assert result is base_message
    assert not isinstance(result, messages.AttachmentSupportingMessage)


def test_preprocess_message_avoids_double_wrapping():
    """Test preprocess_message doesn't re-wrap already marked messages."""
    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test",
        start_time=mock.Mock(),
        end_time=None,
        input=None,
        output={"output_key": "output_value"},
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

    # Mark the message as already preprocessed
    setattr(span_message, constants.MARKER_ATTRIBUTE_NAME, True)

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(span_message)

    # Should return an original message without wrapping
    assert result is span_message
    assert not isinstance(result, messages.AttachmentSupportingMessage)


def test_preprocess_message_avoids_wrapping_message_with_empty_candidate_fields():
    """Test preprocess_message doesn't wrap messages with empty candidate fields."""
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

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(span_message)

    # Should return an original message without wrapping
    assert result is span_message
    assert not isinstance(result, messages.AttachmentSupportingMessage)


def test_preprocess_message_wraps_create_span_with_end_time():
    """Test preprocess_message wraps CreateSpanMessage with end_time set."""
    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test",
        start_time=mock.Mock(),
        end_time=mock.Mock(),  # end_time is set
        input={"input_key": "input_value"},
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

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(span_message)

    assert isinstance(result, messages.AttachmentSupportingMessage)
    assert result.original_message is span_message


def test_preprocess_message_wraps_create_trace_with_end_time():
    """Test preprocess_message wraps CreateTraceMessage with end_time set."""
    trace_message = messages.CreateTraceMessage(
        trace_id="trace-789",
        project_name="test-project",
        name="test-trace",
        start_time=mock.Mock(),
        end_time=mock.Mock(),  # end_time is set
        input=None,
        output={"output_key": "output_value"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=mock.Mock(),
    )

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(trace_message)

    assert isinstance(result, messages.AttachmentSupportingMessage)
    assert result.original_message is trace_message


def test_preprocess_message_wraps_update_span():
    """Test preprocess_message wraps UpdateSpanMessage."""
    span_message = messages.UpdateSpanMessage(
        span_id="span-123",
        parent_span_id=None,
        trace_id="trace-456",
        project_name="test-project",
        end_time=None,
        input={"input_key": "input_value"},
        output=None,
        metadata=None,
        tags=None,
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
    )

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(span_message)

    assert isinstance(result, messages.AttachmentSupportingMessage)
    assert result.original_message is span_message


def test_preprocess_message_wraps_update_trace():
    """Test preprocess_message wraps UpdateTraceMessage."""
    trace_message = messages.UpdateTraceMessage(
        trace_id="trace-789",
        project_name="test-project",
        end_time=None,
        input=None,
        output={"output_key": "output_value"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
    )

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(trace_message)

    assert isinstance(result, messages.AttachmentSupportingMessage)
    assert result.original_message is trace_message


def test_preprocess_message_does_not_wrap_create_span_with_end_time_but_empty_fields():
    """Test preprocess_message doesn't wrap CreateSpanMessage with end_time if all candidate fields are empty."""
    span_message = messages.CreateSpanMessage(
        span_id="span-123",
        trace_id="trace-456",
        project_name="test-project",
        parent_span_id=None,
        name="test",
        start_time=mock.Mock(),
        end_time=mock.Mock(),  # end_time is set
        input=None,  # but all candidate fields are None
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

    processor = attachments_preprocessor.AttachmentsPreprocessor()
    result = processor.preprocess(span_message)

    # Should return the original message unchanged since all candidate fields are None
    assert result is span_message
    assert not isinstance(result, messages.AttachmentSupportingMessage)
