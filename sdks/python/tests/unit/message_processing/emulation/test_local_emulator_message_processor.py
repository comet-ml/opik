import datetime
import pprint
from unittest.mock import patch

from opik import datetime_helpers
from opik.message_processing import messages
from opik.message_processing.emulation import local_emulator_message_processor, models
from opik.rest_api.types import span_write, trace_write

from ....testlib import assert_helpers


class TestLocalEmulatorMessageProcessor:
    def test_init(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True
        )
        assert processor is not None
        assert processor.merge_duplicates is True

    def test_init_with_merge_duplicates_false(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True, merge_duplicates=False
        )
        assert processor is not None

    def test_create_trace_model(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True
        )
        trace_id = "test_trace_id"
        start_time = datetime.datetime.now()
        name = "test_trace"
        project_name = "test_project"
        input_data = {"key": "value"}
        output_data = {"result": "success"}
        tags = ["tag1", "tag2"]
        metadata = {"meta": "data"}
        end_time = datetime.datetime.now()
        error_info = None
        thread_id = "thread_123"
        last_updated_at = datetime.datetime.now()

        trace_model = processor.create_trace_model(
            trace_id=trace_id,
            start_time=start_time,
            name=name,
            project_name=project_name,
            input=input_data,
            output=output_data,
            tags=tags,
            metadata=metadata,
            end_time=end_time,
            spans=None,
            feedback_scores=None,
            error_info=error_info,
            thread_id=thread_id,
            last_updated_at=last_updated_at,
        )

        from opik.message_processing.emulation import models

        assert isinstance(trace_model, models.TraceModel)
        assert trace_model.id == trace_id
        assert trace_model.start_time == start_time
        assert trace_model.name == name
        assert trace_model.project_name == project_name
        assert trace_model.input == input_data
        assert trace_model.output == output_data
        assert trace_model.tags == tags
        assert trace_model.metadata == metadata
        assert trace_model.end_time == end_time
        assert trace_model.spans == []
        assert trace_model.feedback_scores == []
        assert trace_model.error_info == error_info
        assert trace_model.thread_id == thread_id
        assert trace_model.last_updated_at == last_updated_at

    def test_create_span_model(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True
        )
        span_id = "test_span_id"
        start_time = datetime.datetime.now()
        name = "test_span"
        input_data = {"input": "test"}
        output_data = {"output": "result"}
        tags = ["span_tag"]
        metadata = {"span_meta": "data"}
        span_type = "general"
        usage = {"tokens": 100}
        end_time = datetime.datetime.now()
        project_name = "test_project"
        model = "gpt-4"
        provider = "openai"
        error_info = None
        total_cost = 0.01
        last_updated_at = datetime.datetime.now()

        span_model = processor.create_span_model(
            span_id=span_id,
            start_time=start_time,
            name=name,
            input=input_data,
            output=output_data,
            tags=tags,
            metadata=metadata,
            type=span_type,
            usage=usage,
            end_time=end_time,
            project_name=project_name,
            spans=None,
            feedback_scores=None,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            last_updated_at=last_updated_at,
        )

        from opik.message_processing.emulation import models

        assert isinstance(span_model, models.SpanModel)
        assert span_model.id == span_id
        assert span_model.start_time == start_time
        assert span_model.name == name
        assert span_model.input == input_data
        assert span_model.output == output_data
        assert span_model.tags == tags
        assert span_model.metadata == metadata
        assert span_model.type == span_type
        assert span_model.usage == usage
        assert span_model.end_time == end_time
        assert span_model.project_name == project_name
        assert span_model.spans == []
        assert span_model.feedback_scores == []
        assert span_model.model == model
        assert span_model.provider == provider
        assert span_model.error_info == error_info
        assert span_model.total_cost == total_cost
        assert span_model.last_updated_at == last_updated_at

    def test_create_feedback_score_model(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True
        )
        score_id = "test_score_id"
        name = "accuracy"
        value = 0.95
        category_name = "evaluation"
        reason = "high confidence"

        feedback_score_model = processor.create_feedback_score_model(
            score_id=score_id,
            name=name,
            value=value,
            category_name=category_name,
            reason=reason,
        )

        from opik.message_processing.emulation import models

        assert isinstance(feedback_score_model, models.FeedbackScoreModel)
        assert feedback_score_model.id == score_id
        assert feedback_score_model.name == name
        assert feedback_score_model.value == value
        assert feedback_score_model.category_name == category_name
        assert feedback_score_model.reason == reason


class TestLocalEmulatorMessageProcessorProcess:
    def setup_method(self):
        self.test_datetime = datetime_helpers.local_timestamp()

    def test_process__active__calls_dispatch_message(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True, merge_duplicates=True
        )
        message = messages.CreateTraceMessage(
            trace_id="test_trace_id",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.test_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata={"meta": "data"},
            tags=["tag1", "tag2"],
            error_info=None,
            thread_id="thread_123",
            last_updated_at=self.test_datetime,
        )

        with patch.object(processor, "_dispatch_message") as mock_dispatch:
            processor.process(message)

        mock_dispatch.assert_called_once_with(message)

    def test_process__not_active__do_not_call_dispatch_message(self):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=False, merge_duplicates=True
        )
        message = messages.CreateTraceMessage(
            trace_id="test_trace_id",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.test_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata={"meta": "data"},
            tags=["tag1", "tag2"],
            error_info=None,
            thread_id="thread_123",
            last_updated_at=self.test_datetime,
        )

        with patch.object(processor, "_dispatch_message") as mock_dispatch:
            processor.process(message)

        mock_dispatch.assert_not_called()

    def test_process__exception_handling(self, capture_log):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True, merge_duplicates=True
        )
        message = messages.CreateTraceMessage(
            trace_id="test_trace_id",
            start_time=datetime.datetime.now(),
            name="test_trace",
            project_name="test_project",
            input=None,
            output=None,
            tags=None,
            metadata=None,
            end_time=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        with patch.object(
            processor, "_dispatch_message", side_effect=Exception("Test exception")
        ):
            processor.process(message)

        assert (
            "Failed to process message by emulator message processor"
            in capture_log.text
        )

    def test_process__create_trace_message__retry_message_not_skipped_when_entity_missing(
        self,
    ):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True, merge_duplicates=True
        )
        message_1 = messages.CreateTraceMessage(
            trace_id="test_trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.test_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata={"meta": "data"},
            tags=["tag1", "tag2"],
            error_info=None,
            thread_id="thread_123",
            last_updated_at=self.test_datetime,
        )

        message_2 = messages.CreateTraceMessage(
            trace_id="test_trace_2",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.test_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=self.test_datetime,
        )
        # mark as the second delivery attempt
        message_2.delivery_attempts = 2

        with patch.object(processor, "_dispatch_message") as mock_dispatch:
            processor.process(message_1)
            processor.process(message_2)

        assert mock_dispatch.call_count == 2
        mock_dispatch.assert_any_call(message_1)
        mock_dispatch.assert_any_call(message_2)

    def test_process__create_trace_message__duplicate_retry_skipped_when_entity_exists(
        self,
    ):
        processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True, merge_duplicates=True
        )
        message = messages.CreateTraceMessage(
            trace_id="test_trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.test_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata={"meta": "data"},
            tags=["tag1", "tag2"],
            error_info=None,
            thread_id="thread_123",
            last_updated_at=self.test_datetime,
        )
        retry_message = messages.CreateTraceMessage(
            trace_id="test_trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.test_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata={"meta": "data"},
            tags=["tag1", "tag2"],
            error_info=None,
            thread_id="thread_123",
            last_updated_at=self.test_datetime,
        )
        retry_message.delivery_attempts = 2

        processor.process(message)

        with patch.object(processor, "_dispatch_message") as mock_dispatch:
            processor.process(retry_message)

        mock_dispatch.assert_not_called()


class TestLocalEmulatorMessageProcessorTraceTreesProperty:
    def setup_method(self):
        self.processor = local_emulator_message_processor.LocalEmulatorMessageProcessor(
            active=True, merge_duplicates=True
        )
        self.test_datetime = datetime_helpers.local_timestamp()
        self.later_datetime = self.test_datetime + datetime.timedelta(seconds=1)

    def test_trace_trees_empty_when_no_traces_processed(self):
        assert self.processor.trace_trees == []

    def test_trace_trees_returns_single_trace_without_spans(self):
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.later_datetime,
            input={"key": "value"},
            output={"result": "success"},
            metadata={"meta": "data"},
            tags=["tag1"],
            error_info=None,
            thread_id="thread_1",
            last_updated_at=self.test_datetime,
        )

        self.processor.process(trace_message)
        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        assert trace_trees[0].id == "trace_1"
        assert trace_trees[0].name == "test_trace"
        assert trace_trees[0].spans == []
        assert trace_trees[0].feedback_scores == []

    def test_trace_trees_returns_trace_with_top_level_spans(self):
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        span1_message = messages.CreateSpanMessage(
            span_id="span_1",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id=None,
            name="first_span",
            start_time=self.test_datetime,
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
            last_updated_at=None,
        )

        span2_message = messages.CreateSpanMessage(
            span_id="span_2",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id=None,
            name="second_span",
            start_time=self.later_datetime,
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
            last_updated_at=None,
        )

        self.processor.process(trace_message)
        self.processor.process(span1_message)
        self.processor.process(span2_message)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1

        EXCPECTED_TRACE_TREE = models.TraceModel(
            id="trace_1",
            start_time=self.test_datetime,
            name="test_trace",
            project_name="test_project",
            input=None,
            output=None,
            tags=None,
            metadata=None,
            end_time=None,
            spans=[
                models.SpanModel(
                    id="span_1",
                    start_time=self.test_datetime,
                    name="first_span",
                    input=None,
                    output=None,
                    tags=None,
                    metadata=None,
                    type="general",
                    usage=None,
                    end_time=None,
                    project_name="test_project",
                    spans=[],
                    feedback_scores=[],
                    model=None,
                    provider=None,
                    error_info=None,
                    total_cost=None,
                    last_updated_at=None,
                ),
                models.SpanModel(
                    id="span_2",
                    start_time=self.later_datetime,
                    name="second_span",
                    input=None,
                    output=None,
                    tags=None,
                    metadata=None,
                    type="general",
                    usage=None,
                    end_time=None,
                    project_name="test_project",
                    spans=[],
                    feedback_scores=[],
                    model=None,
                    provider=None,
                    error_info=None,
                    total_cost=None,
                    last_updated_at=None,
                ),
            ],
            feedback_scores=[],
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        assert_helpers.assert_equal(
            expected=EXCPECTED_TRACE_TREE, actual=trace_trees[0]
        )

    def test_trace_trees_skips_orphan_span_trace_links(self, capture_log):
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=self.later_datetime,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )
        orphan_span_message = messages.CreateSpanMessage(
            span_id="span_orphan",
            trace_id="missing_trace_id",
            project_name="test_project",
            parent_span_id=None,
            name="orphan_span",
            start_time=self.test_datetime,
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
            last_updated_at=None,
        )

        self.processor.process(trace_message)
        self.processor.process(orphan_span_message)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        assert trace_trees[0].id == "trace_1"
        assert "orphan span-to-trace link" in capture_log.text

    def test_trace_trees_with_nested_span_hierarchy(self):
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        parent_span_message = messages.CreateSpanMessage(
            span_id="parent_span",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id=None,
            name="parent_span",
            start_time=self.test_datetime,
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
            last_updated_at=None,
        )

        child_span_message = messages.CreateSpanMessage(
            span_id="child_span",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id="parent_span",
            name="child_span",
            start_time=self.later_datetime,
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
            last_updated_at=None,
        )

        self.processor.process(trace_message)
        self.processor.process(parent_span_message)
        self.processor.process(child_span_message)

        trace_trees = self.processor.trace_trees
        assert len(trace_trees) == 1

        EXCPECTED_TRACE_TREE = models.TraceModel(
            id="trace_1",
            start_time=self.test_datetime,
            name="test_trace",
            project_name="test_project",
            input=None,
            output=None,
            tags=None,
            metadata=None,
            end_time=None,
            spans=[
                models.SpanModel(
                    id="parent_span",
                    start_time=self.test_datetime,
                    name="parent_span",
                    input=None,
                    output=None,
                    tags=None,
                    metadata=None,
                    type="general",
                    usage=None,
                    end_time=None,
                    project_name="test_project",
                    spans=[
                        models.SpanModel(
                            id="child_span",
                            start_time=self.later_datetime,
                            name="child_span",
                            input=None,
                            output=None,
                            tags=None,
                            metadata=None,
                            type="general",
                            usage=None,
                            end_time=None,
                            project_name="test_project",
                            spans=[],
                            feedback_scores=[],
                            model=None,
                            provider=None,
                            error_info=None,
                            total_cost=None,
                            last_updated_at=None,
                        )
                    ],
                    feedback_scores=[],
                    model=None,
                    provider=None,
                    error_info=None,
                    total_cost=None,
                    last_updated_at=None,
                )
            ],
            feedback_scores=[],
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        assert_helpers.assert_equal(
            expected=EXCPECTED_TRACE_TREE, actual=trace_trees[0]
        )

    def test_trace_trees_multiple_traces_sorted_by_start_time(self):
        trace1_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="trace_1",
            start_time=self.later_datetime,  # Later start time
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        trace2_message = messages.CreateTraceMessage(
            trace_id="trace_2",
            project_name="test_project",
            name="trace_2",
            start_time=self.test_datetime,  # Earlier start time
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        self.processor.process(trace1_message)
        self.processor.process(trace2_message)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 2
        assert trace_trees[0].id == "trace_2"  # Earlier start time first
        assert trace_trees[1].id == "trace_1"  # Later start time second

    def test_trace_trees_with_trace_feedback_scores_batch(self):
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        # Create batch feedback score messages for trace
        feedback_score_1 = messages.FeedbackScoreMessage(
            id="trace_1",  # This should match the trace_id
            project_name="test_project",
            name="accuracy",
            value=0.95,
            source="user",
            category_name="evaluation",
            reason="high confidence",
        )

        feedback_score_2 = messages.FeedbackScoreMessage(
            id="trace_1",  # This should match the trace_id
            project_name="test_project",
            name="relevance",
            value=0.88,
            source="automated",
            category_name="quality",
            reason="good content match",
        )

        trace_feedback_batch_message = messages.AddTraceFeedbackScoresBatchMessage(
            batch=[feedback_score_1, feedback_score_2]
        )

        self.processor.process(trace_message)
        self.processor.process(trace_feedback_batch_message)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        trace = trace_trees[0]
        assert trace.id == "trace_1"
        assert len(trace.feedback_scores) == 2

        # Check first feedback score
        feedback_1 = trace.feedback_scores[0]
        assert feedback_1.name == "accuracy"
        assert feedback_1.value == 0.95
        assert feedback_1.category_name == "evaluation"
        assert feedback_1.reason == "high confidence"

        # Check the second feedback score
        feedback_2 = trace.feedback_scores[1]
        assert feedback_2.name == "relevance"
        assert feedback_2.value == 0.88
        assert feedback_2.category_name == "quality"
        assert feedback_2.reason == "good content match"

    def test_trace_trees_with_span_feedback_scores_batch(self):
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        span_message = messages.CreateSpanMessage(
            span_id="span_1",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id=None,
            name="test_span",
            start_time=self.test_datetime,
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
            last_updated_at=None,
        )

        # Create batch feedback score messages for span
        span_feedback_score_1 = messages.FeedbackScoreMessage(
            id="span_1",  # This should match the span_id
            project_name="test_project",
            name="quality",
            value=0.92,
            source="user",
            category_name="output_quality",
            reason="well structured response",
        )

        span_feedback_score_2 = messages.FeedbackScoreMessage(
            id="span_1",  # This should match the span_id
            project_name="test_project",
            name="latency_score",
            value=0.75,
            source="automated",
            category_name="performance",
            reason="acceptable response time",
        )

        span_feedback_batch_message = messages.AddSpanFeedbackScoresBatchMessage(
            batch=[span_feedback_score_1, span_feedback_score_2]
        )

        self.processor.process(trace_message)
        self.processor.process(span_message)
        self.processor.process(span_feedback_batch_message)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        trace = trace_trees[0]
        assert trace.id == "trace_1"
        assert len(trace.spans) == 1

        span = trace.spans[0]
        assert span.id == "span_1"
        assert len(span.feedback_scores) == 2

        # Check the first span feedback score
        span_feedback_1 = span.feedback_scores[0]
        assert span_feedback_1.name == "quality"
        assert span_feedback_1.value == 0.92
        assert span_feedback_1.category_name == "output_quality"
        assert span_feedback_1.reason == "well structured response"

        # Check the second span feedback score
        span_feedback_2 = span.feedback_scores[1]
        assert span_feedback_2.name == "latency_score"
        assert span_feedback_2.value == 0.75
        assert span_feedback_2.category_name == "performance"
        assert span_feedback_2.reason == "acceptable response time"

    def test_trace_trees_with_mixed_feedback_scores_batch(self):
        # Test both trace and span feedback scores in the same trace tree
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        span_message = messages.CreateSpanMessage(
            span_id="span_1",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id=None,
            name="test_span",
            start_time=self.test_datetime,
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
            last_updated_at=None,
        )

        # Trace feedback scores
        trace_feedback_score = messages.FeedbackScoreMessage(
            id="trace_1",
            project_name="test_project",
            name="overall_quality",
            value=0.90,
            source="user",
            category_name="overall",
            reason="good trace execution",
        )

        trace_feedback_batch_message = messages.AddTraceFeedbackScoresBatchMessage(
            batch=[trace_feedback_score]
        )

        # Span feedback scores
        span_feedback_score = messages.FeedbackScoreMessage(
            id="span_1",
            project_name="test_project",
            name="step_quality",
            value=0.85,
            source="user",
            category_name="step",
            reason="good individual step",
        )

        span_feedback_batch_message = messages.AddSpanFeedbackScoresBatchMessage(
            batch=[span_feedback_score]
        )

        self.processor.process(trace_message)
        self.processor.process(span_message)
        self.processor.process(trace_feedback_batch_message)
        self.processor.process(span_feedback_batch_message)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        trace = trace_trees[0]
        assert trace.id == "trace_1"
        assert len(trace.feedback_scores) == 1
        assert trace.feedback_scores[0].name == "overall_quality"
        assert trace.feedback_scores[0].value == 0.90

        assert len(trace.spans) == 1
        span = trace.spans[0]
        assert span.id == "span_1"
        assert len(span.feedback_scores) == 1
        assert span.feedback_scores[0].name == "step_quality"
        assert span.feedback_scores[0].value == 0.85

    def test_trace_trees_with_empty_feedback_scores_batch(self):
        # Test empty batch messages don't cause issues
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        empty_trace_feedback_batch = messages.AddTraceFeedbackScoresBatchMessage(
            batch=[]
        )

        empty_span_feedback_batch = messages.AddSpanFeedbackScoresBatchMessage(batch=[])

        self.processor.process(trace_message)
        self.processor.process(empty_trace_feedback_batch)
        self.processor.process(empty_span_feedback_batch)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        trace = trace_trees[0]
        assert trace.id == "trace_1"
        assert len(trace.feedback_scores) == 0
        assert len(trace.spans) == 0

    def test_trace_trees_with_multiple_feedback_scores_batch_for_same_entity(self):
        # Test multiple batch messages targeting the same trace/span
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        # First batch of trace feedback scores
        first_batch = messages.AddTraceFeedbackScoresBatchMessage(
            batch=[
                messages.FeedbackScoreMessage(
                    id="trace_1",
                    project_name="test_project",
                    name="accuracy",
                    value=0.95,
                    source="user",
                    category_name="evaluation",
                )
            ]
        )

        # Second batch of trace feedback scores
        second_batch = messages.AddTraceFeedbackScoresBatchMessage(
            batch=[
                messages.FeedbackScoreMessage(
                    id="trace_1",
                    project_name="test_project",
                    name="relevance",
                    value=0.88,
                    source="automated",
                    category_name="quality",
                ),
                messages.FeedbackScoreMessage(
                    id="trace_1",
                    project_name="test_project",
                    name="completeness",
                    value=0.92,
                    source="user",
                    category_name="evaluation",
                ),
            ]
        )

        self.processor.process(trace_message)
        self.processor.process(first_batch)
        self.processor.process(second_batch)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        trace = trace_trees[0]
        assert trace.id == "trace_1"
        assert len(trace.feedback_scores) == 3  # Should accumulate all feedback scores

        # Check all feedback scores are present
        feedback_names = [fs.name for fs in trace.feedback_scores]
        assert "accuracy" in feedback_names
        assert "relevance" in feedback_names
        assert "completeness" in feedback_names

    def test_trace_trees_with_create_trace_batch_message(self):
        # Test CreateTraceBatchMessage with multiple TraceWrite objects
        trace_write_1 = trace_write.TraceWrite(
            id="trace_1",
            project_name="test_project",
            name="first_trace",
            start_time=self.test_datetime,
            end_time=None,
            input={"input": "test1"},
            output={"output": "result1"},
            metadata={"meta": "data1"},
            tags=["tag1"],
            error_info=None,
            last_updated_at=None,
            thread_id="thread_1",
        )

        trace_write_2 = trace_write.TraceWrite(
            id="trace_2",
            project_name="test_project",
            name="second_trace",
            start_time=self.later_datetime,
            end_time=None,
            input={"input": "test2"},
            output={"output": "result2"},
            metadata={"meta": "data2"},
            tags=["tag2"],
            error_info=None,
            last_updated_at=None,
            thread_id="thread_2",
        )

        trace_batch_message = messages.CreateTraceBatchMessage(
            batch=[trace_write_1, trace_write_2]
        )

        self.processor.process(trace_batch_message)
        trace_trees = self.processor.trace_trees
        assert len(trace_trees) == 2

        pprint.pprint(trace_trees)

        EXPECTED_TRACE_TREE = [
            models.TraceModel(
                id="trace_1",
                start_time=self.test_datetime,
                name="first_trace",
                project_name="test_project",
                input={"input": "test1"},
                output={"output": "result1"},
                tags=["tag1"],
                metadata={"meta": "data1"},
                end_time=None,
                spans=[],
                feedback_scores=[],
                error_info=None,
                thread_id="thread_1",
                last_updated_at=None,
            ),
            models.TraceModel(
                id="trace_2",
                start_time=self.later_datetime,
                name="second_trace",
                project_name="test_project",
                input={"input": "test2"},
                output={"output": "result2"},
                tags=["tag2"],
                metadata={"meta": "data2"},
                end_time=None,
                spans=[],
                feedback_scores=[],
                error_info=None,
                thread_id="thread_2",
                last_updated_at=None,
            ),
        ]

        assert_helpers.assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees)

    def test_trace_trees_with_create_spans_batch_message(self):
        # First, create a trace to attach spans to
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        # Create batch span write objects
        span_write_1 = span_write.SpanWrite(
            id="span_1",
            project_name="test_project",
            trace_id="trace_1",
            parent_span_id=None,
            name="first_span",
            type="general",
            start_time=self.test_datetime,
            end_time=None,
            input={"input": "span1_input"},
            output={"output": "span1_result"},
            metadata={"meta": "span1_data"},
            model="gpt-3.5",
            provider="openai",
            tags=["span1_tag"],
            usage={"tokens": 50},
            error_info=None,
            last_updated_at=None,
            total_estimated_cost=0.005,
        )

        span_write_2 = span_write.SpanWrite(
            id="span_2",
            project_name="test_project",
            trace_id="trace_1",
            parent_span_id=None,
            name="second_span",
            type="tool",
            start_time=self.later_datetime,
            end_time=None,
            input={"input": "span2_input"},
            output={"output": "span2_result"},
            metadata={"meta": "span2_data"},
            model="gpt-4",
            provider="openai",
            tags=["span2_tag"],
            usage={"tokens": 100},
            error_info=None,
            last_updated_at=None,
            total_estimated_cost=0.02,
        )

        spans_batch_message = messages.CreateSpansBatchMessage(
            batch=[span_write_1, span_write_2]
        )

        self.processor.process(trace_message)
        self.processor.process(spans_batch_message)

        trace_trees = self.processor.trace_trees
        assert len(trace_trees) == 1

        EXPECTED_TRACE_TREE = models.TraceModel(
            id="trace_1",
            start_time=self.test_datetime,
            name="test_trace",
            project_name="test_project",
            input=None,
            output=None,
            tags=None,
            metadata=None,
            end_time=None,
            spans=[
                models.SpanModel(
                    id="span_1",
                    start_time=self.test_datetime,
                    name="first_span",
                    input={"input": "span1_input"},
                    output={"output": "span1_result"},
                    tags=["span1_tag"],
                    metadata={"meta": "span1_data"},
                    type="general",
                    usage={"tokens": 50},
                    end_time=None,
                    project_name="test_project",
                    spans=[],
                    feedback_scores=[],
                    model="gpt-3.5",
                    provider="openai",
                    error_info=None,
                    total_cost=0.005,
                    last_updated_at=None,
                ),
                models.SpanModel(
                    id="span_2",
                    start_time=self.later_datetime,
                    name="second_span",
                    input={"input": "span2_input"},
                    output={"output": "span2_result"},
                    tags=["span2_tag"],
                    metadata={"meta": "span2_data"},
                    type="tool",
                    usage={"tokens": 100},
                    end_time=None,
                    project_name="test_project",
                    spans=[],
                    feedback_scores=[],
                    model="gpt-4",
                    provider="openai",
                    error_info=None,
                    total_cost=0.02,
                    last_updated_at=None,
                ),
            ],
            feedback_scores=[],
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        assert_helpers.assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees[0])

    def test_trace_trees_with_nested_spans_batch_message(self):
        # Test CreateSpansBatchMessage with nested spans (parent-child relationships)
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )

        parent_span_write = span_write.SpanWrite(
            id="parent_span",
            project_name="test_project",
            trace_id="trace_1",
            parent_span_id=None,
            name="parent_span",
            type="general",
            start_time=self.test_datetime,
            end_time=None,
            input={"parent": "input"},
            output={"parent": "output"},
            metadata=None,
            model=None,
            provider=None,
            tags=None,
            usage=None,
            error_info=None,
            last_updated_at=None,
        )

        child_span_write = span_write.SpanWrite(
            id="child_span",
            project_name="test_project",
            trace_id="trace_1",
            parent_span_id="parent_span",
            name="child_span",
            type="tool",
            start_time=self.later_datetime,
            end_time=None,
            input={"child": "input"},
            output={"child": "output"},
            metadata=None,
            model=None,
            provider=None,
            tags=None,
            usage=None,
            error_info=None,
            last_updated_at=None,
        )

        spans_batch_message = messages.CreateSpansBatchMessage(
            batch=[parent_span_write, child_span_write]
        )

        self.processor.process(trace_message)
        self.processor.process(spans_batch_message)

        trace_trees = self.processor.trace_trees
        assert len(trace_trees) == 1

        EXPECTED_TRACE_TREE = models.TraceModel(
            id="trace_1",
            start_time=self.test_datetime,
            name="test_trace",
            project_name="test_project",
            input=None,
            output=None,
            tags=None,
            metadata=None,
            end_time=None,
            spans=[
                models.SpanModel(
                    id="parent_span",
                    start_time=self.test_datetime,
                    name="parent_span",
                    input={"parent": "input"},
                    output={"parent": "output"},
                    tags=None,
                    metadata=None,
                    type="general",
                    usage=None,
                    end_time=None,
                    project_name="test_project",
                    spans=[
                        models.SpanModel(
                            id="child_span",
                            start_time=self.later_datetime,
                            name="child_span",
                            input={"child": "input"},
                            output={"child": "output"},
                            tags=None,
                            metadata=None,
                            type="tool",
                            usage=None,
                            end_time=None,
                            project_name="test_project",
                            spans=[],
                            feedback_scores=[],
                            model=None,
                            provider=None,
                            error_info=None,
                            total_cost=None,
                            last_updated_at=None,
                        )
                    ],
                    feedback_scores=[],
                    model=None,
                    provider=None,
                    error_info=None,
                    total_cost=None,
                    last_updated_at=None,
                )
            ],
            feedback_scores=[],
            error_info=None,
            thread_id=None,
            last_updated_at=self.later_datetime,
        )
        assert_helpers.assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees[0])

    def test_trace_trees_with_empty_batch_messages(self):
        # Test empty batch messages don't cause issues
        trace_message = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="test_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        empty_traces_batch = messages.CreateTraceBatchMessage(batch=[])
        empty_spans_batch = messages.CreateSpansBatchMessage(batch=[])

        self.processor.process(trace_message)
        self.processor.process(empty_traces_batch)
        self.processor.process(empty_spans_batch)

        trace_trees = self.processor.trace_trees

        assert len(trace_trees) == 1
        trace = trace_trees[0]
        assert trace.id == "trace_1"
        assert len(trace.spans) == 0

    def test_trace_trees_with_mixed_batch_and_individual_messages(self):
        # Test a combination of batch messages and individual create messages
        # Individual trace message
        individual_trace = messages.CreateTraceMessage(
            trace_id="trace_1",
            project_name="test_project",
            name="individual_trace",
            start_time=self.test_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            thread_id=None,
            last_updated_at=None,
        )

        # Batch trace message
        batch_trace_write = trace_write.TraceWrite(
            id="trace_2",
            project_name="test_project",
            name="batch_trace",
            start_time=self.later_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            tags=None,
            error_info=None,
            last_updated_at=None,
            thread_id=None,
        )

        trace_batch = messages.CreateTraceBatchMessage(batch=[batch_trace_write])

        # Individual span message
        individual_span = messages.CreateSpanMessage(
            span_id="span_1",
            trace_id="trace_1",
            project_name="test_project",
            parent_span_id=None,
            name="individual_span",
            start_time=self.test_datetime,
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
            last_updated_at=None,
        )

        # Batch span message
        batch_span_write = span_write.SpanWrite(
            id="span_2",
            project_name="test_project",
            trace_id="trace_2",
            parent_span_id=None,
            name="batch_span",
            type="tool",
            start_time=self.later_datetime,
            end_time=None,
            input=None,
            output=None,
            metadata=None,
            model=None,
            provider=None,
            tags=None,
            usage=None,
            error_info=None,
            last_updated_at=None,
        )

        spans_batch = messages.CreateSpansBatchMessage(batch=[batch_span_write])

        # Process all messages
        self.processor.process(individual_trace)
        self.processor.process(trace_batch)
        self.processor.process(individual_span)
        self.processor.process(spans_batch)

        trace_trees = self.processor.trace_trees
        assert len(trace_trees) == 2

        EXPECTED_TRACE_TREE = [
            models.TraceModel(
                id="trace_1",
                start_time=self.test_datetime,
                name="individual_trace",
                project_name="test_project",
                input=None,
                output=None,
                tags=None,
                metadata=None,
                end_time=None,
                spans=[
                    models.SpanModel(
                        id="span_1",
                        start_time=self.test_datetime,
                        name="individual_span",
                        input=None,
                        output=None,
                        tags=None,
                        metadata=None,
                        type="general",
                        usage=None,
                        end_time=None,
                        project_name="test_project",
                        spans=[],
                        feedback_scores=[],
                        model=None,
                        provider=None,
                        error_info=None,
                        total_cost=None,
                        last_updated_at=None,
                    )
                ],
                feedback_scores=[],
                error_info=None,
                thread_id=None,
                last_updated_at=None,
            ),
            models.TraceModel(
                id="trace_2",
                start_time=self.later_datetime,
                name="batch_trace",
                project_name="test_project",
                input=None,
                output=None,
                tags=None,
                metadata=None,
                end_time=None,
                spans=[
                    models.SpanModel(
                        id="span_2",
                        start_time=self.later_datetime,
                        name="batch_span",
                        input=None,
                        output=None,
                        tags=None,
                        metadata=None,
                        type="tool",
                        usage=None,
                        end_time=None,
                        project_name="test_project",
                        spans=[],
                        feedback_scores=[],
                        model=None,
                        provider=None,
                        error_info=None,
                        total_cost=None,
                        last_updated_at=None,
                    )
                ],
                feedback_scores=[],
                error_info=None,
                thread_id=None,
                last_updated_at=None,
            ),
        ]
        assert_helpers.assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_trees)
