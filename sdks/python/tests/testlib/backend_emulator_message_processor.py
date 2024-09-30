from opik.message_processing import message_processors, messages
from typing import List, Tuple, Type, Dict, Union, Optional

from .models import TraceModel, SpanModel, FeedbackScoreModel

import collections


class BackendEmulatorMessageProcessor(message_processors.BaseMessageProcessor):
    def __init__(self) -> None:
        self.processed_messages: List[messages.BaseMessage] = []
        self._trace_trees: List[TraceModel] = []

        self._traces_to_spans_mapping: Dict[str, List[str]] = collections.defaultdict(
            list
        )
        self._span_trees: List[
            SpanModel
        ] = []  # the same as _trace_trees but without a trace. Useful for distributed tracing.
        self._observations: Dict[str, Union[TraceModel, SpanModel]] = {}

        self._span_to_parent_span: Dict[str, Optional[str]] = {}
        self._span_to_trace: Dict[str, Optional[str]] = {}
        self._trace_to_feedback_scores: Dict[str, List[FeedbackScoreModel]] = (
            collections.defaultdict(list)
        )
        self._span_to_feedback_scores: Dict[str, List[FeedbackScoreModel]] = (
            collections.defaultdict(list)
        )

    @property
    def trace_trees(self):
        """
        Builds list of trace trees based on the data from processed messages.
        Before processing traces, builds span_trees
        """
        self.span_trees  # call to connect all spans

        for span_id, trace_id in self._span_to_trace.items():
            if trace_id is None:
                continue

            trace = self._observations[trace_id]
            if self._span_to_parent_span[
                span_id
            ] is None and not _observation_already_stored(span_id, trace.spans):
                trace.spans.append(self._observations[span_id])
                trace.spans.sort(key=lambda x: x.start_time)

        for trace in self._trace_trees:
            trace.feedback_scores = self._trace_to_feedback_scores[trace.id]

        self._trace_trees.sort(key=lambda x: x.start_time)
        return self._trace_trees

    @property
    def span_trees(self):
        """
        Builds list of span trees based on the data from processed messages.
        Children spans are sorted by creation time
        """
        for span_id, parent_span_id in self._span_to_parent_span.items():
            if parent_span_id is None:
                continue

            parent_span = self._observations[parent_span_id]
            if not _observation_already_stored(span_id, parent_span.spans):
                parent_span.spans.append(self._observations[span_id])
                parent_span.spans.sort(key=lambda x: x.start_time)

        all_span_ids = self._span_to_trace
        for span_id in all_span_ids:
            span = self._observations[span_id]
            span.feedback_scores = self._span_to_feedback_scores[span_id]

        self._span_trees.sort(key=lambda x: x.start_time)
        return self._span_trees

    def process(self, message: messages.BaseMessage) -> None:
        if isinstance(message, messages.CreateTraceMessage):
            trace = TraceModel(
                id=message.trace_id,
                name=message.name,
                input=message.input,
                output=message.output,
                tags=message.tags,
                metadata=message.metadata,
                start_time=message.start_time,
                end_time=message.end_time,
            )

            self._trace_trees.append(trace)
            self._observations[message.trace_id] = trace

        elif isinstance(message, messages.CreateSpanMessage):
            span = SpanModel(
                id=message.span_id,
                name=message.name,
                input=message.input,
                output=message.output,
                tags=message.tags,
                metadata=message.metadata,
                type=message.type,
                start_time=message.start_time,
                end_time=message.end_time,
                usage=message.usage,
            )

            self._span_to_parent_span[span.id] = message.parent_span_id
            if message.parent_span_id is None:
                self._span_trees.append(span)

            self._span_to_trace[span.id] = message.trace_id

            self._observations[message.span_id] = span
        elif isinstance(message, messages.CreateSpansBatchMessage):
            for item in message.batch:
                self.process(item)
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
                feedback_model = FeedbackScoreModel(
                    id=feedback_score_message.id,
                    name=feedback_score_message.name,
                    value=feedback_score_message.value,
                    category_name=feedback_score_message.category_name,
                    reason=feedback_score_message.reason,
                )
                self._span_to_feedback_scores[feedback_score_message.id].append(
                    feedback_model
                )
        elif isinstance(message, messages.AddTraceFeedbackScoresBatchMessage):
            for feedback_score_message in message.batch:
                feedback_model = FeedbackScoreModel(
                    id=feedback_score_message.id,
                    name=feedback_score_message.name,
                    value=feedback_score_message.value,
                    category_name=feedback_score_message.category_name,
                    reason=feedback_score_message.reason,
                )
                self._trace_to_feedback_scores[feedback_score_message.id].append(
                    feedback_model
                )

        self.processed_messages.append(message)

    def get_messages_of_type(self, allowed_types: Tuple[Type, ...]):
        return [
            message
            for message in self.processed_messages
            if isinstance(message, allowed_types)
        ]


def _observation_already_stored(id, observations) -> bool:
    for observation in observations:
        if observation.id == id:
            return True

    return False
