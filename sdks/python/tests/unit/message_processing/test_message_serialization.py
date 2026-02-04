import datetime

from opik.message_processing import messages
from opik.rest_api.types import span_write, trace_write
from opik.types import ErrorInfoDict

"""
Tests for message serialization/deserialization to/from dict.

These tests verify that messages with nested objects (batch items, original_message)
can be serialized to dict and deserialized back correctly.
"""


class TestAddFeedbackScoresBatchMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        feedback_scores = [
            messages.FeedbackScoreMessage(
                id="score-1",
                project_name="test-project",
                name="accuracy",
                value=0.95,
                source="sdk",
                reason="Good prediction",
                category_name="metrics",
            ),
            messages.FeedbackScoreMessage(
                id="score-2",
                project_name="test-project-2",
                name="latency",
                value=0.5,
                source="api",
                reason=None,
                category_name=None,
            ),
        ]

        original = messages.AddFeedbackScoresBatchMessage(batch=feedback_scores)

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["batch"], list)
        assert len(serialized["batch"]) == 2
        assert serialized["supports_batching"] is True

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.AddFeedbackScoresBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.AddFeedbackScoresBatchMessage)
        assert deserialized.supports_batching is True
        assert len(deserialized.batch) == 2

        # Verify the first batch item - all fields
        item0 = deserialized.batch[0]
        assert isinstance(item0, messages.FeedbackScoreMessage)
        assert item0.id == "score-1"
        assert item0.project_name == "test-project"
        assert item0.name == "accuracy"
        assert item0.value == 0.95
        assert item0.source == "sdk"
        assert item0.reason == "Good prediction"
        assert item0.category_name == "metrics"

        # Verify the second batch item - all fields
        item1 = deserialized.batch[1]
        assert isinstance(item1, messages.FeedbackScoreMessage)
        assert item1.id == "score-2"
        assert item1.project_name == "test-project-2"
        assert item1.name == "latency"
        assert item1.value == 0.5
        assert item1.source == "api"
        assert item1.reason is None
        assert item1.category_name is None


class TestAddThreadsFeedbackScoresBatchMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        feedback_scores = [
            messages.ThreadsFeedbackScoreMessage(
                id="thread-score-1",
                project_name="test-project",
                name="relevance",
                value=0.8,
                source="sdk",
                reason="Relevant response",
                category_name="quality",
            ),
        ]

        original = messages.AddThreadsFeedbackScoresBatchMessage(batch=feedback_scores)

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["batch"], list)
        assert len(serialized["batch"]) == 1
        assert serialized["supports_batching"] is True

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.AddThreadsFeedbackScoresBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.AddThreadsFeedbackScoresBatchMessage)
        assert deserialized.supports_batching is True
        assert len(deserialized.batch) == 1

        # Verify batch item - all fields
        item = deserialized.batch[0]
        assert isinstance(item, messages.ThreadsFeedbackScoreMessage)
        assert item.id == "thread-score-1"
        assert item.project_name == "test-project"
        assert item.name == "relevance"
        assert item.value == 0.8
        assert item.source == "sdk"
        assert item.reason == "Relevant response"
        assert item.category_name == "quality"


class TestCreateSpansBatchMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1)

        spans = [
            span_write.SpanWrite(
                id="span-1",
                trace_id="trace-1",
                project_name="test-project",
                parent_span_id="parent-span-1",
                name="test-span",
                type="llm",
                start_time=start_time,
                end_time=end_time,
                input={"prompt": "test"},
                output={"response": "result"},
                metadata={"key": "value"},
                model="gpt-4",
                provider="openai",
                tags=["tag1", "tag2"],
                usage={"prompt_tokens": 10, "completion_tokens": 20},
                total_estimated_cost=0.001,
            ),
        ]

        original = messages.CreateSpansBatchMessage(batch=spans)

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["batch"], list)
        assert len(serialized["batch"]) == 1

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.CreateSpansBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.CreateSpansBatchMessage)
        assert len(deserialized.batch) == 1

        # Verify batch item - all fields
        item = deserialized.batch[0]
        assert isinstance(item, span_write.SpanWrite)
        assert item.id == "span-1"
        assert item.trace_id == "trace-1"
        assert item.project_name == "test-project"
        assert item.parent_span_id == "parent-span-1"
        assert item.name == "test-span"
        assert item.type == "llm"
        assert item.start_time == start_time
        assert item.end_time == end_time
        assert item.input == {"prompt": "test"}
        assert item.output == {"response": "result"}
        assert item.metadata == {"key": "value"}
        assert item.model == "gpt-4"
        assert item.provider == "openai"
        assert item.tags == ["tag1", "tag2"]
        assert item.usage == {"prompt_tokens": 10, "completion_tokens": 20}
        assert item.total_estimated_cost == 0.001


class TestCreateTraceBatchMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1)

        traces = [
            trace_write.TraceWrite(
                id="trace-1",
                project_name="test-project",
                name="test-trace",
                start_time=start_time,
                end_time=end_time,
                input={"query": "test input"},
                output={"answer": "test output"},
                metadata={"meta_key": "meta_value"},
                tags=["trace-tag1", "trace-tag2"],
            ),
        ]

        original = messages.CreateTraceBatchMessage(batch=traces)

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["batch"], list)
        assert len(serialized["batch"]) == 1

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.CreateTraceBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.CreateTraceBatchMessage)
        assert len(deserialized.batch) == 1

        # Verify batch item - all fields
        item = deserialized.batch[0]
        assert isinstance(item, trace_write.TraceWrite)
        assert item.id == "trace-1"
        assert item.project_name == "test-project"
        assert item.name == "test-trace"
        assert item.start_time == start_time
        assert item.end_time == end_time
        assert item.input == {"query": "test input"}
        assert item.output == {"answer": "test output"}
        assert item.metadata == {"meta_key": "meta_value"}
        assert item.tags == ["trace-tag1", "trace-tag2"]


class TestGuardrailBatchMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        guardrail_items = [
            messages.GuardrailBatchItemMessage(
                project_name="test-project",
                entity_id="entity-1",
                secondary_id="secondary-1",
                name="content-filter",
                result="passed",
                config={"threshold": 0.5, "enabled": True},
                details={"score": 0.3, "categories": ["safe"]},
            ),
        ]

        original = messages.GuardrailBatchMessage(batch=guardrail_items)

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["batch"], list)
        assert len(serialized["batch"]) == 1
        assert serialized["supports_batching"] is True

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.GuardrailBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.GuardrailBatchMessage)
        assert deserialized.supports_batching is True
        assert len(deserialized.batch) == 1

        # Verify batch item - all fields
        item = deserialized.batch[0]
        assert isinstance(item, messages.GuardrailBatchItemMessage)
        assert item.project_name == "test-project"
        assert item.entity_id == "entity-1"
        assert item.secondary_id == "secondary-1"
        assert item.name == "content-filter"
        assert item.result == "passed"
        assert item.config == {"threshold": 0.5, "enabled": True}
        assert item.details == {"score": 0.3, "categories": ["safe"]}


class TestCreateExperimentItemsBatchMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        experiment_items = [
            messages.ExperimentItemMessage(
                id="item-1",
                experiment_id="exp-1",
                trace_id="trace-1",
                dataset_item_id="dataset-item-1",
            ),
            messages.ExperimentItemMessage(
                id="item-2",
                experiment_id="exp-2",
                trace_id="trace-2",
                dataset_item_id="dataset-item-2",
            ),
        ]

        original = messages.CreateExperimentItemsBatchMessage(batch=experiment_items)

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["batch"], list)
        assert len(serialized["batch"]) == 2
        assert serialized["supports_batching"] is True

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.CreateExperimentItemsBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.CreateExperimentItemsBatchMessage)
        assert deserialized.supports_batching is True
        assert len(deserialized.batch) == 2

        # Verify the first batch item - all fields
        item0 = deserialized.batch[0]
        assert isinstance(item0, messages.ExperimentItemMessage)
        assert item0.id == "item-1"
        assert item0.experiment_id == "exp-1"
        assert item0.trace_id == "trace-1"
        assert item0.dataset_item_id == "dataset-item-1"

        # Verify the second batch item - all fields
        item1 = deserialized.batch[1]
        assert isinstance(item1, messages.ExperimentItemMessage)
        assert item1.id == "item-2"
        assert item1.experiment_id == "exp-2"
        assert item1.trace_id == "trace-2"
        assert item1.dataset_item_id == "dataset-item-2"


class TestAttachmentSupportingMessageSerialization:
    def test_serialize_and_deserialize_with_attachment_message(self):
        """Test round-trip with CreateAttachmentMessage as original."""
        original_attachment = messages.CreateAttachmentMessage(
            file_path="/path/to/file.txt",
            file_name="file.txt",
            mime_type="text/plain",
            entity_type="trace",
            entity_id="trace-1",
            project_name="test-project",
            encoded_url_override="https://example.com/file",
            delete_after_upload=True,
        )

        original = messages.AttachmentSupportingMessage(
            original_message=original_attachment
        )

        # Serialize
        serialized = original.as_db_message_dict()

        # Verify serialized structure
        assert isinstance(serialized["original_message"], dict)

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.AttachmentSupportingMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.AttachmentSupportingMessage)

        # Verify original_message - all fields
        orig_msg = deserialized.original_message
        assert isinstance(orig_msg, messages.CreateAttachmentMessage)
        assert orig_msg.file_path == "/path/to/file.txt"
        assert orig_msg.file_name == "file.txt"
        assert orig_msg.mime_type == "text/plain"
        assert orig_msg.entity_type == "trace"
        assert orig_msg.entity_id == "trace-1"
        assert orig_msg.project_name == "test-project"
        assert orig_msg.encoded_url_override == "https://example.com/file"
        assert orig_msg.delete_after_upload is True


class TestCreateTraceMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1)
        last_updated_at = datetime.datetime(2024, 1, 1, 12, 0, 2)
        error_info = ErrorInfoDict(
            exception_type="ValueError",
            message="test error",
            traceback="Traceback (most recent call last):\n",
        )

        original = messages.CreateTraceMessage(
            trace_id="trace-1",
            project_name="test-project",
            name="test-trace",
            start_time=start_time,
            end_time=end_time,
            input={"query": "test input"},
            output={"answer": "test output"},
            metadata={"meta_key": "meta_value"},
            tags=["tag1", "tag2"],
            error_info=error_info,
            thread_id="thread-1",
            last_updated_at=last_updated_at,
        )

        # Serialize
        serialized = original.as_db_message_dict()

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.CreateTraceMessage, serialized
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.CreateTraceMessage)
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        assert deserialized.name == "test-trace"
        assert deserialized.start_time == start_time
        assert deserialized.end_time == end_time
        assert deserialized.input == {"query": "test input"}
        assert deserialized.output == {"answer": "test output"}
        assert deserialized.metadata == {"meta_key": "meta_value"}
        assert deserialized.tags == ["tag1", "tag2"]
        assert deserialized.error_info == error_info
        assert deserialized.thread_id == "thread-1"
        assert deserialized.last_updated_at == last_updated_at


class TestUpdateTraceMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1)

        original = messages.UpdateTraceMessage(
            trace_id="trace-1",
            project_name="test-project",
            end_time=end_time,
            input={"query": "updated input"},
            output={"answer": "updated output"},
            metadata={"updated_key": "updated_value"},
            tags=["updated-tag"],
            error_info=None,
            thread_id="thread-2",
        )

        # Serialize
        serialized = original.as_db_message_dict()

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.UpdateTraceMessage, serialized
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.UpdateTraceMessage)
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        assert deserialized.end_time == end_time
        assert deserialized.input == {"query": "updated input"}
        assert deserialized.output == {"answer": "updated output"}
        assert deserialized.metadata == {"updated_key": "updated_value"}
        assert deserialized.tags == ["updated-tag"]
        assert deserialized.error_info is None
        assert deserialized.thread_id == "thread-2"


class TestCreateSpanMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1)
        last_updated_at = datetime.datetime(2024, 1, 1, 12, 0, 2)

        error_info = ErrorInfoDict(
            exception_type="ValueError",
            message="test error",
            traceback="Traceback (most recent call last):\n",
        )
        original = messages.CreateSpanMessage(
            span_id="span-1",
            trace_id="trace-1",
            project_name="test-project",
            parent_span_id="parent-span-1",
            name="test-span",
            start_time=start_time,
            end_time=end_time,
            input={"prompt": "test prompt"},
            output={"response": "test response"},
            metadata={"span_meta": "value"},
            tags=["span-tag1", "span-tag2"],
            type="llm",
            usage={"prompt_tokens": 100, "completion_tokens": 200},
            model="gpt-4",
            provider="openai",
            error_info=error_info,
            total_cost=0.05,
            last_updated_at=last_updated_at,
        )

        # Serialize
        serialized = original.as_db_message_dict()

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.CreateSpanMessage, serialized
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.CreateSpanMessage)
        assert deserialized.span_id == "span-1"
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        assert deserialized.parent_span_id == "parent-span-1"
        assert deserialized.name == "test-span"
        assert deserialized.start_time == start_time
        assert deserialized.end_time == end_time
        assert deserialized.input == {"prompt": "test prompt"}
        assert deserialized.output == {"response": "test response"}
        assert deserialized.metadata == {"span_meta": "value"}
        assert deserialized.tags == ["span-tag1", "span-tag2"]
        assert deserialized.type == "llm"
        assert deserialized.usage == {"prompt_tokens": 100, "completion_tokens": 200}
        assert deserialized.model == "gpt-4"
        assert deserialized.provider == "openai"
        assert deserialized.error_info == error_info
        assert deserialized.total_cost == 0.05
        assert deserialized.last_updated_at == last_updated_at


class TestUpdateSpanMessageSerialization:
    def test_serialize_and_deserialize(self):
        """Test round-trip serialization/deserialization."""
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1)

        original = messages.UpdateSpanMessage(
            span_id="span-1",
            parent_span_id="parent-span-2",
            trace_id="trace-1",
            project_name="test-project",
            end_time=end_time,
            input={"prompt": "updated prompt"},
            output={"response": "updated response"},
            metadata={"updated_meta": "new_value"},
            tags=["updated-span-tag"],
            usage={"prompt_tokens": 150, "completion_tokens": 250},
            model="gpt-4-turbo",
            provider="openai",
            error_info=None,
            total_cost=0.08,
        )

        # Serialize
        serialized = original.as_db_message_dict()

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.UpdateSpanMessage, serialized
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.UpdateSpanMessage)
        assert deserialized.span_id == "span-1"
        assert deserialized.parent_span_id == "parent-span-2"
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        assert deserialized.end_time == end_time
        assert deserialized.input == {"prompt": "updated prompt"}
        assert deserialized.output == {"response": "updated response"}
        assert deserialized.metadata == {"updated_meta": "new_value"}
        assert deserialized.tags == ["updated-span-tag"]
        assert deserialized.usage == {"prompt_tokens": 150, "completion_tokens": 250}
        assert deserialized.model == "gpt-4-turbo"
        assert deserialized.provider == "openai"
        assert deserialized.error_info is None
        assert deserialized.total_cost == 0.08


class TestSubclassInheritance:
    """Test that subclasses properly inherit serialization behavior."""

    def test_add_trace_feedback_scores_batch_message(self):
        """Test AddTraceFeedbackScoresBatchMessage inherits serialization."""
        feedback_scores = [
            messages.FeedbackScoreMessage(
                id="score-1",
                project_name="test-project",
                name="accuracy",
                value=0.95,
                source="sdk",
                reason="Good accuracy",
                category_name="metrics",
            ),
        ]

        original = messages.AddTraceFeedbackScoresBatchMessage(batch=feedback_scores)

        # Serialize
        serialized = original.as_db_message_dict()

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.AddTraceFeedbackScoresBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.AddTraceFeedbackScoresBatchMessage)
        assert deserialized.supports_batching is True
        assert len(deserialized.batch) == 1

        # Verify batch item - all fields
        item = deserialized.batch[0]
        assert isinstance(item, messages.FeedbackScoreMessage)
        assert item.id == "score-1"
        assert item.project_name == "test-project"
        assert item.name == "accuracy"
        assert item.value == 0.95
        assert item.source == "sdk"
        assert item.reason == "Good accuracy"
        assert item.category_name == "metrics"

    def test_add_span_feedback_scores_batch_message(self):
        """Test AddSpanFeedbackScoresBatchMessage inherits serialization."""
        feedback_scores = [
            messages.FeedbackScoreMessage(
                id="score-1",
                project_name="test-project",
                name="latency",
                value=0.5,
                source="api",
                reason=None,
                category_name=None,
            ),
        ]

        original = messages.AddSpanFeedbackScoresBatchMessage(batch=feedback_scores)

        # Serialize
        serialized = original.as_db_message_dict()

        # Deserialize
        deserialized = messages.from_db_message_dict(
            messages.AddSpanFeedbackScoresBatchMessage, serialized
        )

        # Verify deserialized message
        assert isinstance(deserialized, messages.AddSpanFeedbackScoresBatchMessage)
        assert deserialized.supports_batching is True
        assert len(deserialized.batch) == 1

        # Verify batch item - all fields
        item = deserialized.batch[0]
        assert isinstance(item, messages.FeedbackScoreMessage)
        assert item.id == "score-1"
        assert item.project_name == "test-project"
        assert item.name == "latency"
        assert item.value == 0.5
        assert item.source == "api"
        assert item.reason is None
        assert item.category_name is None
