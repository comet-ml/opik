from opik.message_processing import message_processors, messages
from typing import List, Tuple, Type, Dict, Union

from .testlib_dsl import TraceModel, SpanModel, FeedbackScoreModel


class FakeMessageProcessor(message_processors.BaseMessageProcessor):
    def __init__(self) -> None:
        self.processed_messages: List[messages.BaseMessage] = []
        self.trace_trees: List[TraceModel] = []
        self.span_trees: List[
            SpanModel
        ] = []  # the same as trace_trees but without a trace. Useful for distributed tracing.
        self._observations: Dict[str, Union[TraceModel, SpanModel]] = {}

    def process(self, message: messages.BaseMessage) -> None:
        print(message)
        if isinstance(message, messages.CreateTraceMessage):
            trace = TraceModel(
                id=message.trace_id,
                name=message.name,
                input=message.input,
                tags=message.tags,
                metadata=message.metadata,
                start_time=message.start_time,
                end_time=message.end_time,
            )
            self.trace_trees.append(trace)
            self._observations[message.trace_id] = trace
        elif isinstance(message, messages.CreateSpanMessage):
            span = SpanModel(
                id=message.span_id,
                name=message.name,
                input=message.input,
                tags=message.tags,
                metadata=message.metadata,
                type=message.type,
                start_time=message.start_time,
                end_time=message.end_time,
            )

            if message.parent_span_id is None:
                current_trace: TraceModel = self._observations[message.trace_id]
                current_trace.spans.append(span)
                self.span_trees.append(span)
            else:
                parent_span: SpanModel = self._observations[message.parent_span_id]
                parent_span.spans.append(span)

            self._observations[message.span_id] = span
        elif isinstance(message, messages.UpdateSpanMessage):
            span: SpanModel = self._observations[message.span_id]
            span.output = message.output
            span.usage = message.usage
            span.end_time = message.end_time
        elif isinstance(message, messages.UpdateTraceMessage):
            current_trace: TraceModel = self._observations[message.trace_id]
            current_trace.output = message.output
            current_trace.end_time = message.end_time
        elif isinstance(message, messages.AddSpanFeedbackScoresBatchMessage):
            for feedback_score_message in message.batch:
                span: SpanModel = self._observations[feedback_score_message.id]
                feedback_model = FeedbackScoreModel(
                    id=feedback_score_message.id,
                    name=feedback_score_message.name,
                    value=feedback_score_message.value,
                    category_name=feedback_score_message.category_name,
                    reason=feedback_score_message.reason,
                )
                span.feedback_scores.append(feedback_model)
        elif isinstance(message, messages.AddTraceFeedbackScoresBatchMessage):
            for feedback_score_message in message.batch:
                trace: TraceModel = self._observations[feedback_score_message.id]
                feedback_model = FeedbackScoreModel(
                    id=feedback_score_message.id,
                    name=feedback_score_message.name,
                    value=feedback_score_message.value,
                    category_name=feedback_score_message.category_name,
                    reason=feedback_score_message.reason,
                )
                trace.feedback_scores.append(feedback_model)

        self.processed_messages.append(message)

    def get_messages_of_type(self, allowed_types: Tuple[Type, ...]):
        return [
            message
            for message in self.processed_messages
            if isinstance(message, allowed_types)
        ]
