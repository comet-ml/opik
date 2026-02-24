import base64
import dataclasses
import datetime
from threading import Lock
from typing import Optional

import numpy as np

from opik.message_processing import messages
from opik.message_processing.replay import message_serialization
from opik.rest_api.types import span_write, trace_write
from opik.types import ErrorInfoDict

"""
Tests for message serialization/deserialization to/from dict.

These tests verify that messages with nested objects (batch items, original_message)
can be serialized to dict, then to JSON string, and deserialized back correctly.
"""


class TestAddFeedbackScoresBatchMessageSerialization:
    def test_add_feedback_scores_batch_message__round_trip_serialization__preserves_data(
        self,
    ):
        """Test round-trip serialization/deserialization through JSON."""
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.AddFeedbackScoresBatchMessage,
            json_str=json_str,
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
    def test_add_threads_feedback_scores_batch_message__round_trip_serialization__preserves_data(
        self,
    ):
        """Test round-trip serialization/deserialization through JSON."""
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.AddThreadsFeedbackScoresBatchMessage,
            json_str=json_str,
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
    def test_create_spans_batch_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        start_time = datetime.datetime(
            2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
        )
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc)

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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateSpansBatchMessage,
            json_str=json_str,
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
    def test_create_trace_batch_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        start_time = datetime.datetime(
            2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
        )
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc)

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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateTraceBatchMessage,
            json_str=json_str,
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
    def test_guardrail_batch_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.GuardrailBatchMessage,
            json_str=json_str,
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
    def test_create_experiment_items_batch_message__round_trip_serialization__preserves_data(
        self,
    ):
        """Test round-trip serialization/deserialization through JSON."""
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateExperimentItemsBatchMessage,
            json_str=json_str,
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


class TestCreateAttachmentMessageSerialization:
    def test_create_attachment_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        original = messages.CreateAttachmentMessage(
            file_path="/path/to/document.pdf",
            file_name="document.pdf",
            mime_type="application/pdf",
            entity_type="span",
            entity_id="span-123",
            project_name="test-project",
            encoded_url_override="https://storage.example.com/uploads/document.pdf",
            delete_after_upload=True,
        )

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateAttachmentMessage,
            json_str=json_str,
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.CreateAttachmentMessage)
        assert deserialized.file_path == "/path/to/document.pdf"
        assert deserialized.file_name == "document.pdf"
        assert deserialized.mime_type == "application/pdf"
        assert deserialized.entity_type == "span"
        assert deserialized.entity_id == "span-123"
        assert deserialized.project_name == "test-project"
        assert (
            deserialized.encoded_url_override
            == "https://storage.example.com/uploads/document.pdf"
        )
        assert deserialized.delete_after_upload is True


class TestCreateTraceMessageSerialization:
    def test_create_trace_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        start_time = datetime.datetime(
            2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
        )
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc)
        last_updated_at = datetime.datetime(
            2024, 1, 1, 12, 0, 2, tzinfo=datetime.timezone.utc
        )
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateTraceMessage,
            json_str=json_str,
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.CreateTraceMessage)
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        assert deserialized.name == "test-trace"
        # Datetime fields become strings after JSON roundtrip
        assert str(deserialized.start_time) == str(start_time)
        assert str(deserialized.end_time) == str(end_time)
        assert deserialized.input == {"query": "test input"}
        assert deserialized.output == {"answer": "test output"}
        assert deserialized.metadata == {"meta_key": "meta_value"}
        assert deserialized.tags == ["tag1", "tag2"]
        assert deserialized.error_info == error_info
        assert deserialized.thread_id == "thread-1"
        assert str(deserialized.last_updated_at) == str(last_updated_at)


class TestUpdateTraceMessageSerialization:
    def test_update_trace_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc)

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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.UpdateTraceMessage,
            json_str=json_str,
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.UpdateTraceMessage)
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        # Datetime fields become strings after JSON roundtrip
        assert str(deserialized.end_time) == str(end_time)
        assert deserialized.input == {"query": "updated input"}
        assert deserialized.output == {"answer": "updated output"}
        assert deserialized.metadata == {"updated_key": "updated_value"}
        assert deserialized.tags == ["updated-tag"]
        assert deserialized.error_info is None
        assert deserialized.thread_id == "thread-2"


class TestCreateSpanMessageSerialization:
    def test_create_span_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        start_time = datetime.datetime(
            2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
        )
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc)
        last_updated_at = datetime.datetime(
            2024, 1, 1, 12, 0, 2, tzinfo=datetime.timezone.utc
        )

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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateSpanMessage,
            json_str=json_str,
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.CreateSpanMessage)
        assert deserialized.span_id == "span-1"
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        assert deserialized.parent_span_id == "parent-span-1"
        assert deserialized.name == "test-span"
        # Datetime fields become strings after JSON roundtrip
        assert str(deserialized.start_time) == str(start_time)
        assert str(deserialized.end_time) == str(end_time)
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
        assert str(deserialized.last_updated_at) == str(last_updated_at)


class TestUpdateSpanMessageSerialization:
    def test_update_span_message__round_trip_serialization__preserves_data(self):
        """Test round-trip serialization/deserialization through JSON."""
        end_time = datetime.datetime(2024, 1, 1, 12, 0, 1, tzinfo=datetime.timezone.utc)

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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.UpdateSpanMessage,
            json_str=json_str,
        )

        # Verify deserialized message - all fields
        assert isinstance(deserialized, messages.UpdateSpanMessage)
        assert deserialized.span_id == "span-1"
        assert deserialized.parent_span_id == "parent-span-2"
        assert deserialized.trace_id == "trace-1"
        assert deserialized.project_name == "test-project"
        # Datetime fields become strings after JSON roundtrip
        assert str(deserialized.end_time) == str(end_time)
        assert deserialized.input == {"prompt": "updated prompt"}
        assert deserialized.output == {"response": "updated response"}
        assert deserialized.metadata == {"updated_meta": "new_value"}
        assert deserialized.tags == ["updated-span-tag"]
        assert deserialized.usage == {"prompt_tokens": 150, "completion_tokens": 250}
        assert deserialized.model == "gpt-4-turbo"
        assert deserialized.provider == "openai"
        assert deserialized.error_info is None
        assert deserialized.total_cost == 0.08


class TestDatetimeObjectHook:
    """Tests for datetime_object_hook: only known datetime fields are converted."""

    def test_datetime_object_hook__known_fields__converts_to_datetime(self):
        """Known datetime fields with valid ISO strings are converted to datetime."""
        obj = {
            "start_time": "2024-01-15T10:30:00",
            "end_time": "2024-01-15T11:00:00.123456",
            "last_updated_at": "2024-01-15T12:00:00Z",
        }
        result = message_serialization.datetime_object_hook(obj)

        assert isinstance(result["start_time"], datetime.datetime)
        assert result["start_time"] == datetime.datetime(2024, 1, 15, 10, 30, 0)
        assert isinstance(result["end_time"], datetime.datetime)
        assert result["end_time"] == datetime.datetime(2024, 1, 15, 11, 0, 0, 123456)
        assert isinstance(result["last_updated_at"], datetime.datetime)

    def test_datetime_object_hook__unknown_fields_with_iso_strings__not_converted(self):
        """ISO-like strings in non-datetime fields must remain as strings."""
        obj = {
            "name": "test",
            "timestamp": "2024-01-15T10:30:00",
            "created_at": "2024-06-01T08:00:00Z",
            "some_date": "2024-01-15T10:30:00.123456+02:00",
        }
        result = message_serialization.datetime_object_hook(obj)

        assert result["timestamp"] == "2024-01-15T10:30:00"
        assert isinstance(result["timestamp"], str)
        assert result["created_at"] == "2024-06-01T08:00:00Z"
        assert isinstance(result["created_at"], str)
        assert result["some_date"] == "2024-01-15T10:30:00.123456+02:00"
        assert isinstance(result["some_date"], str)

    def test_datetime_object_hook__mixed_fields__only_known_fields_converted(self):
        """Only known datetime fields are converted; others stay as strings."""
        obj = {
            "start_time": "2024-01-15T10:30:00",
            "event_time": "2024-01-15T10:30:00",
            "name": "test",
        }
        result = message_serialization.datetime_object_hook(obj)

        assert isinstance(result["start_time"], datetime.datetime)
        assert isinstance(result["event_time"], str)
        assert result["event_time"] == "2024-01-15T10:30:00"

    def test_datetime_object_hook__known_field_non_iso_string__not_converted(self):
        """Known datetime fields with non-ISO strings are left as-is."""
        obj = {
            "start_time": "not-a-date",
            "end_time": "2024/01/15 10:30:00",
        }
        result = message_serialization.datetime_object_hook(obj)

        assert result["start_time"] == "not-a-date"
        assert isinstance(result["start_time"], str)
        assert result["end_time"] == "2024/01/15 10:30:00"
        assert isinstance(result["end_time"], str)

    def test_datetime_object_hook__known_field_non_string_value__not_converted(self):
        """Known datetime fields with non-string values are left as-is."""
        obj = {
            "start_time": 12345,
            "end_time": None,
            "last_updated_at": True,
        }
        result = message_serialization.datetime_object_hook(obj)

        assert result["start_time"] == 12345
        assert result["end_time"] is None
        assert result["last_updated_at"] is True

    def test_datetime_object_hook__empty_dict__returns_empty(self):
        """Empty dicts pass through without error."""
        result = message_serialization.datetime_object_hook({})
        assert result == {}

    def test_datetime_object_hook__no_known_fields__unchanged(self):
        """Dicts without known datetime fields are returned unchanged."""
        obj = {"id": "123", "value": 0.95, "data": "2024-01-15T10:30:00"}
        result = message_serialization.datetime_object_hook(obj)

        assert result == {"id": "123", "value": 0.95, "data": "2024-01-15T10:30:00"}
        assert isinstance(result["data"], str)


class TestIsoStringsInDataFieldsPreserved:
    """Tests that ISO datetime strings inside input/output/metadata are NOT converted."""

    def test_create_trace__iso_strings_in_input_output_metadata__preserved_as_strings(
        self,
    ):
        """ISO strings in input/output/metadata dicts survive a round-trip as strings."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)

        original = messages.CreateTraceMessage(
            trace_id="trace-1",
            project_name="test-project",
            name="test-trace",
            start_time=start_time,
            end_time=None,
            input={"query": "test", "requested_at": "2024-06-15T09:00:00Z"},
            output={"answer": "result", "completed_at": "2024-06-15T09:01:00"},
            metadata={"event_time": "2024-06-15T09:00:30.123456+02:00"},
            tags=[],
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        json_str = message_serialization.serialize_message(original)
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateTraceMessage,
            json_str=json_str,
        )

        # Datetime fields are properly converted
        assert isinstance(deserialized.start_time, datetime.datetime)

        # ISO strings inside input/output/metadata must remain as strings
        assert deserialized.input["requested_at"] == "2024-06-15T09:00:00Z"
        assert isinstance(deserialized.input["requested_at"], str)

        assert deserialized.output["completed_at"] == "2024-06-15T09:01:00"
        assert isinstance(deserialized.output["completed_at"], str)

        assert deserialized.metadata["event_time"] == "2024-06-15T09:00:30.123456+02:00"
        assert isinstance(deserialized.metadata["event_time"], str)

    def test_create_span__iso_strings_in_input_output_metadata__preserved_as_strings(
        self,
    ):
        """ISO strings in span input/output/metadata dicts survive a round-trip as strings."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)

        original = messages.CreateSpanMessage(
            span_id="span-1",
            trace_id="trace-1",
            project_name="test-project",
            parent_span_id=None,
            name="test-span",
            start_time=start_time,
            end_time=None,
            input={"timestamp": "2024-06-15T09:00:00"},
            output={"created": "2024-06-15T09:01:00Z"},
            metadata={"logged_at": "2024-06-15T09:00:30"},
            tags=[],
            type="general",
            usage=None,
            model=None,
            provider=None,
            error_info=None,
            total_cost=None,
            last_updated_at=None,
        )

        json_str = message_serialization.serialize_message(original)
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateSpanMessage,
            json_str=json_str,
        )

        assert isinstance(deserialized.start_time, datetime.datetime)

        assert deserialized.input["timestamp"] == "2024-06-15T09:00:00"
        assert isinstance(deserialized.input["timestamp"], str)

        assert deserialized.output["created"] == "2024-06-15T09:01:00Z"
        assert isinstance(deserialized.output["created"], str)

        assert deserialized.metadata["logged_at"] == "2024-06-15T09:00:30"
        assert isinstance(deserialized.metadata["logged_at"], str)

    def test_create_spans_batch__iso_strings_in_span_data_fields__preserved_as_strings(
        self,
    ):
        """ISO strings in batch span input/output/metadata survive a round-trip as strings."""
        start_time = datetime.datetime(2024, 1, 1, 12, 0, 0)

        from opik.rest_api.types import span_write

        spans = [
            span_write.SpanWrite(
                id="span-1",
                trace_id="trace-1",
                project_name="test-project",
                name="test-span",
                type="llm",
                start_time=start_time,
                input={"user_ts": "2024-03-10T14:30:00Z"},
                output={"llm_ts": "2024-03-10T14:30:05"},
                metadata={"logged": "2024-03-10T14:30:01+05:00"},
            ),
        ]

        original = messages.CreateSpansBatchMessage(batch=spans)

        json_str = message_serialization.serialize_message(original)
        deserialized = message_serialization.deserialize_message(
            message_class=messages.CreateSpansBatchMessage,
            json_str=json_str,
        )

        item = deserialized.batch[0]
        assert isinstance(item.start_time, datetime.datetime)

        assert item.input["user_ts"] == "2024-03-10T14:30:00Z"
        assert isinstance(item.input["user_ts"], str)

        assert item.output["llm_ts"] == "2024-03-10T14:30:05"
        assert isinstance(item.output["llm_ts"], str)

        assert item.metadata["logged"] == "2024-03-10T14:30:01+05:00"
        assert isinstance(item.metadata["logged"], str)


class TestSubclassInheritance:
    """Test that subclasses properly inherit serialization behavior."""

    def test_add_trace_feedback_scores_batch_message__inherits_serialization(self):
        """Test AddTraceFeedbackScoresBatchMessage inherits serialization through JSON."""
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.AddTraceFeedbackScoresBatchMessage,
            json_str=json_str,
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

    def test_add_span_feedback_scores_batch_message__inherits_serialization(self):
        """Test AddSpanFeedbackScoresBatchMessage inherits serialization through JSON."""
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

        # Serialize to JSON string
        json_str = message_serialization.serialize_message(original)

        # Deserialize to message
        deserialized = message_serialization.deserialize_message(
            message_class=messages.AddSpanFeedbackScoresBatchMessage,
            json_str=json_str,
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


class TestNonJsonSerializableTypesInMessageFields:
    """
    Tests that non-JSON-serializable types placed in input/output/metadata fields
    are handled correctly through the serialize_message -> deserialize_message
    round-trip, matching the encoding behaviour exercised by test_jsonable_encoder.py.

    After round-trip:
      bytes         -> base64-encoded string
      set/tuple     -> list
      np.ndarray    -> list
      datetime.date -> "YYYY-MM-DD" string
      datetime      (key NOT in DATETIME_FIELD_NAMES) -> ISO string (stays string)
      Lock / custom class -> str() representation
      cyclic object -> "<Cyclic reference to ...>" marker string, no crash
      repeated refs -> no false cycle marker
    """

    def _make_trace(
        self,
        input_data=None,
        output_data=None,
        metadata=None,
    ) -> messages.CreateTraceMessage:
        return messages.CreateTraceMessage(
            trace_id="trace-1",
            project_name="test-project",
            name="test-trace",
            start_time=datetime.datetime(
                2024, 1, 1, 12, 0, 0, tzinfo=datetime.timezone.utc
            ),
            end_time=None,
            input=input_data,
            output=output_data,
            metadata=metadata,
            tags=[],
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

    def _round_trip(
        self, msg: messages.CreateTraceMessage
    ) -> messages.CreateTraceMessage:
        json_str = message_serialization.serialize_message(msg)
        return message_serialization.deserialize_message(
            message_class=messages.CreateTraceMessage,
            json_str=json_str,
        )

    def test_bytes_in_input__serialized_as_base64_string(self):
        """bytes values in input are base64-encoded strings after a round-trip."""
        original = self._make_trace(input_data={"data": b"deadbeef"})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["data"], str)
        assert deserialized.input["data"] == base64.b64encode(b"deadbeef").decode(
            "utf-8"
        )

    def test_set_in_input__serialized_as_list(self):
        """set values in input are converted to a list after a round-trip."""
        original = self._make_trace(input_data={"items": {1, 2, 3}})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["items"], list)
        assert sorted(deserialized.input["items"]) == [1, 2, 3]

    def test_tuple_in_input__serialized_as_list(self):
        """tuple values in input are converted to a list after a round-trip."""
        original = self._make_trace(input_data={"coords": (10, 20, 30)})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["coords"], list)
        assert deserialized.input["coords"] == [10, 20, 30]

    def test_numpy_array_in_input__serialized_as_list(self):
        """numpy arrays in input are converted to a list after a round-trip."""
        original = self._make_trace(input_data={"embedding": np.array([1.0, 2.0, 3.0])})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["embedding"], list)
        assert deserialized.input["embedding"] == [1.0, 2.0, 3.0]

    def test_date_in_input__serialized_as_date_string(self):
        """datetime.date values in input become 'YYYY-MM-DD' strings after a round-trip."""
        original = self._make_trace(
            input_data={"event_date": datetime.date(2024, 1, 15)}
        )
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["event_date"], str)
        assert deserialized.input["event_date"] == "2024-01-15"

    def test_datetime_with_unknown_key_in_input__serialized_as_iso_string(self):
        """
        datetime objects in input whose key is NOT in DATETIME_FIELD_NAMES are
        serialized to an ISO string and stay as a string after a round-trip
        (the datetime_object_hook only converts the known set of field names).
        """
        dt = datetime.datetime(2024, 1, 15, 10, 30, 0, tzinfo=datetime.timezone.utc)
        original = self._make_trace(input_data={"event_timestamp": dt})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["event_timestamp"], str)
        assert deserialized.input["event_timestamp"] == "2024-01-15T10:30:00Z"

    def test_non_serializable_lock_in_input__serialized_as_string(self):
        """Lock objects in input become their str() representation after a round-trip."""
        original = self._make_trace(input_data={"lock": Lock()})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["lock"], str)
        assert "<unlocked _thread.lock object at 0x" in deserialized.input["lock"]

    def test_non_serializable_class_in_input__serialized_as_string(self):
        """Arbitrary non-serializable class instances in input become str() after a round-trip."""

        class SomeObject:
            value = 42

        original = self._make_trace(input_data={"obj": SomeObject()})
        deserialized = self._round_trip(original)
        assert isinstance(deserialized.input["obj"], str)
        assert "SomeObject" in deserialized.input["obj"]

    def test_cyclic_reference_in_input__serialized_with_cycle_marker(self):
        """
        Cyclic object references in input do not crash serialization.
        The cycle is replaced with a '<Cyclic reference to ...>' marker string.
        """

        @dataclasses.dataclass
        class Node:
            value: int
            child: Optional["Node"] = None

        node_a = Node(value=1)
        node_b = Node(value=2)
        node_a.child = node_b
        node_b.child = node_a

        original = self._make_trace(input_data={"graph": node_a})
        deserialized = self._round_trip(original)

        graph = deserialized.input["graph"]
        assert isinstance(graph, dict)
        assert graph["value"] == 1
        assert isinstance(graph["child"], dict)
        assert graph["child"]["value"] == 2
        # node_b.child points back to node_a — must be a cycle marker, not a full object
        assert isinstance(graph["child"]["child"], str)
        assert "<Cyclic reference to " in graph["child"]["child"]

    def test_repeated_objects_in_input__no_cycle_marker(self):
        """
        The same object referenced multiple times (but without a real cycle) is
        encoded without any '<Cyclic reference>' marker.
        """

        @dataclasses.dataclass
        class Item:
            value: int

        item = Item(value=42)
        original = self._make_trace(input_data={"items": [item, item, item]})
        deserialized = self._round_trip(original)

        assert isinstance(deserialized.input["items"], list)
        assert len(deserialized.input["items"]) == 3
        for entry in deserialized.input["items"]:
            assert isinstance(entry, dict)
            assert entry["value"] == 42
            assert "Cyclic reference" not in str(entry)
