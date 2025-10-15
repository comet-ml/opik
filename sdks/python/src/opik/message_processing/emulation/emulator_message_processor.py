import abc
import collections
import datetime
import logging
from typing import List, Tuple, Type, Dict, Union, Optional, Any

from opik.rest_api.types import span_write, trace_write
from opik.types import ErrorInfoDict, SpanType
from opik import dict_utils

from . import models
from .. import message_processors, messages


LOGGER = logging.getLogger(__name__)


class EmulatorMessageProcessor(message_processors.BaseMessageProcessor, abc.ABC):
    """
    This class acts as a stand-in for the actual backend.

    The real message processor uses data from messages passed
    to the process method to send information to the backend.
    In contrast, the emulator does not send any requests — instead,
    it collects the data from incoming messages and stores it
    in its attributes.

    Beyond simple data storage, it also constructs complete trace
    and span trees from the received messages. These trees are defined
    by model classes and their subclasses located in models. Concrete
    model instances are created in the abstract create_* methods,
    which must be implemented by subclasses.

    Note: If a new message type is added to the Opik SDK, this
    class must be updated accordingly.

    Args:
        active: Flag indicating whether the emulator is active.
        merge_duplicates: Flag indicating whether duplicates (traces or spans) should
            be merged to retain only unique and updated ones.
    """

    def __init__(self, active: bool, merge_duplicates: bool) -> None:
        self.processed_messages: List[messages.BaseMessage] = []
        self._trace_trees: List[models.TraceModel] = []
        self.merge_duplicates = merge_duplicates
        self._active = active

        self._traces_to_spans_mapping: Dict[str, List[str]] = collections.defaultdict(
            list
        )
        # the same as _trace_trees but without a trace. Useful for distributed tracing.
        self._span_trees: List[models.SpanModel] = []
        self._trace_observations: Dict[str, models.TraceModel] = {}
        self._span_observations: Dict[str, models.SpanModel] = {}

        self._span_to_parent_span: Dict[str, Optional[str]] = {}
        self._span_to_trace: Dict[str, Optional[str]] = {}
        self._trace_to_feedback_scores: Dict[str, List[models.FeedbackScoreModel]] = (
            collections.defaultdict(list)
        )
        self._span_to_feedback_scores: Dict[str, List[models.FeedbackScoreModel]] = (
            collections.defaultdict(list)
        )

    def is_active(self) -> bool:
        return self._active

    def set_active(self, active: bool) -> None:
        self._active = active

    @property
    def trace_trees(self) -> List[models.TraceModel]:
        """
        Builds a list of trace trees based on the data from the processed messages.
        Before processing traces, builds span_trees
        """
        # call to connect all spans
        self._build_spans_tree()

        for span_id, trace_id in self._span_to_trace.items():
            if trace_id is None:
                continue

            trace = self._trace_observations[trace_id]
            if self._span_to_parent_span[
                span_id
            ] is None and not _observation_already_stored(span_id, trace.spans):
                span = self._span_observations[span_id]
                trace.spans.append(span)
                trace.spans.sort(key=lambda x: x.start_time)

        for trace in self._trace_trees:
            trace.feedback_scores = self._trace_to_feedback_scores[trace.id]

        self._trace_trees.sort(key=lambda x: x.start_time)
        return self._trace_trees

    def _save_trace(self, trace: models.TraceModel) -> None:
        if self.merge_duplicates:
            # merge traces with the same id to keep only the latest one
            if trace.id in self._trace_observations:
                existing_trace: models.TraceModel = self._trace_observations[trace.id]
                if trace.end_time is not None:
                    if (
                        existing_trace.end_time is None
                        or trace.end_time > existing_trace.end_time
                    ):
                        # remove the current trace from the list
                        self._trace_trees.remove(existing_trace)

        self._trace_trees.append(trace)
        self._trace_observations[trace.id] = trace

    def _save_span(
        self, span: models.SpanModel, trace_id: str, parent_span_id: Optional[str]
    ) -> None:
        if self.merge_duplicates:
            # merge spans with the same id to keep only the latest one
            if span.id in self._span_observations:
                existing_span = self._span_observations[span.id]
                if span.end_time is not None:
                    if (
                        existing_span.end_time is None
                        or span.end_time > existing_span.end_time
                    ):
                        self._span_trees.remove(existing_span)

        self._span_to_parent_span[span.id] = parent_span_id
        if parent_span_id is None:
            self._span_trees.append(span)

        self._span_to_trace[span.id] = trace_id
        self._span_observations[span.id] = span

    @property
    def span_trees(self) -> List[models.SpanModel]:
        self._build_spans_tree()
        return self._span_trees

    def _build_spans_tree(self) -> None:
        """
        Builds a list of span trees based on the data from the processed messages.
        Children's spans are sorted by creation time
        """
        for span_id, parent_span_id in self._span_to_parent_span.items():
            if parent_span_id is None:
                continue

            parent_span = self._span_observations[parent_span_id]
            if not _observation_already_stored(span_id, parent_span.spans):
                parent_span.spans.append(self._span_observations[span_id])
                parent_span.spans.sort(key=lambda x: x.start_time)

        all_span_ids = self._span_to_trace
        for span_id in all_span_ids:
            span = self._span_observations[span_id]
            span.feedback_scores = self._span_to_feedback_scores[span_id]

        self._span_trees.sort(key=lambda x: x.start_time)

    def _dispatch_message(self, message: messages.BaseMessage) -> None:
        if isinstance(message, messages.CreateTraceMessage):
            trace = self.create_trace_model(
                trace_id=message.trace_id,
                name=message.name,
                input=message.input,
                output=message.output,
                tags=message.tags,
                metadata=message.metadata,
                start_time=message.start_time,
                end_time=message.end_time,
                project_name=message.project_name,
                error_info=message.error_info,
                thread_id=message.thread_id,
                last_updated_at=message.last_updated_at,
                feedback_scores=None,
                spans=None,
            )

            self._save_trace(trace)

        elif isinstance(message, trace_write.TraceWrite):
            if message.error_info is not None:
                error_info = ErrorInfoDict(
                    exception_type=message.error_info.exception_type,
                    message=message.error_info.message,
                    traceback=message.error_info.traceback,
                )
            else:
                error_info = None

            trace = self.create_trace_model(
                trace_id=message.id,
                name=message.name,
                input=message.input,
                output=message.output,
                tags=message.tags,
                metadata=message.metadata,
                start_time=message.start_time,
                end_time=message.end_time,
                project_name=message.project_name,
                thread_id=message.thread_id,
                last_updated_at=message.last_updated_at,
                spans=None,
                feedback_scores=None,
                error_info=error_info,
            )
            self._save_trace(trace)

        elif isinstance(message, messages.CreateSpanMessage):
            span = self.create_span_model(
                span_id=message.span_id,
                name=message.name,
                input=message.input,
                output=message.output,
                tags=message.tags,
                metadata=message.metadata,
                type=message.type,
                start_time=message.start_time,
                end_time=message.end_time,
                usage=message.usage,
                project_name=message.project_name,
                model=message.model,
                provider=message.provider,
                error_info=message.error_info,
                total_cost=message.total_cost,
                last_updated_at=message.last_updated_at,
                spans=None,
                feedback_scores=None,
            )

            self._save_span(
                span, trace_id=message.trace_id, parent_span_id=message.parent_span_id
            )

        elif isinstance(message, span_write.SpanWrite):
            if message.error_info is not None:
                error_info = ErrorInfoDict(
                    exception_type=message.error_info.exception_type,
                    message=message.error_info.message,
                    traceback=message.error_info.traceback,
                )
            else:
                error_info = None

            span = self.create_span_model(
                span_id=message.id,
                name=message.name,
                input=message.input,
                output=message.output,
                tags=message.tags,
                metadata=message.metadata,
                type=message.type,
                start_time=message.start_time,
                end_time=message.end_time,
                usage=message.usage,
                project_name=message.project_name,
                model=message.model,
                provider=message.provider,
                total_cost=message.total_estimated_cost,
                last_updated_at=message.last_updated_at,
                spans=None,
                feedback_scores=None,
                error_info=error_info,
            )

            self._save_span(
                span, trace_id=message.trace_id, parent_span_id=message.parent_span_id
            )

        elif isinstance(message, messages.CreateSpansBatchMessage):
            for item in message.batch:
                self.process(item)
        elif isinstance(message, messages.CreateTraceBatchMessage):
            for item in message.batch:
                self.process(item)
        elif isinstance(message, messages.UpdateSpanMessage):
            span = self._span_observations[message.span_id]
            update_payload = {
                "output": message.output,
                "usage": message.usage,
                "provider": message.provider,
                "model": message.model,
                "end_time": message.end_time,
                "metadata": message.metadata,
                "error_info": message.error_info,
                "tags": message.tags,
                "input": message.input,
                "total_cost": message.total_cost,
            }
            cleaned_update_payload = dict_utils.remove_none_from_dict(update_payload)
            span.__dict__.update(cleaned_update_payload)

        elif isinstance(message, messages.UpdateTraceMessage):
            current_trace = self._trace_observations[message.trace_id]
            update_payload = {
                "output": message.output,
                "end_time": message.end_time,
                "metadata": message.metadata,
                "error_info": message.error_info,
                "tags": message.tags,
                "input": message.input,
                "thread_id": message.thread_id,
            }
            cleaned_update_payload = dict_utils.remove_none_from_dict(update_payload)
            current_trace.__dict__.update(cleaned_update_payload)

        elif isinstance(message, messages.AddSpanFeedbackScoresBatchMessage):
            for feedback_score_message in message.batch:
                feedback_model = self.create_feedback_score_model(
                    score_id=feedback_score_message.id,
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
                feedback_model = self.create_feedback_score_model(
                    score_id=feedback_score_message.id,
                    name=feedback_score_message.name,
                    value=feedback_score_message.value,
                    category_name=feedback_score_message.category_name,
                    reason=feedback_score_message.reason,
                )
                self._trace_to_feedback_scores[feedback_score_message.id].append(
                    feedback_model
                )

        self.processed_messages.append(message)

    def process(
        self,
        message: Union[
            messages.BaseMessage, span_write.SpanWrite, trace_write.TraceWrite
        ],
    ) -> None:
        if not self.is_active():
            return
        try:
            self._dispatch_message(message)
        except Exception as exception:
            LOGGER.error(
                "Failed to process message by emulator message processor, reason: %s",
                exception,
                exc_info=True,
            )

    def get_messages_of_type(
        self, allowed_types: Tuple[Type, ...]
    ) -> List[messages.BaseMessage]:
        """
        Returns all messages instances of requested types
        """
        return [
            message
            for message in self.processed_messages
            if isinstance(message, allowed_types)
        ]

    @abc.abstractmethod
    def create_trace_model(
        self,
        trace_id: str,
        start_time: datetime.datetime,
        name: Optional[str],
        project_name: str,
        input: Any,
        output: Any,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        end_time: Optional[datetime.datetime],
        spans: Optional[List[models.SpanModel]],
        feedback_scores: Optional[List[models.FeedbackScoreModel]],
        error_info: Optional[ErrorInfoDict],
        thread_id: Optional[str],
        last_updated_at: Optional[datetime.datetime] = None,
    ) -> models.TraceModel:
        """
        Creates a trace model with the specified attributes. The method is abstract and must be
        implemented in a subclass to define how a trace model is created. It involves parameters
        such as timing details, trace-specific metadata, associated tags, input/output data,
        and other relevant information.

        Args:
            trace_id: A unique identifier for the trace.
            start_time: The starting time of the trace.
            name: An optional name representing the trace's purpose or identifier.
            project_name: The name of the project associated with the trace.
            input: Input data associated with the trace.
            output: Output data generated from the trace execution.
            tags: Optional list of tags for categorizing or labeling the trace.
            metadata: Optional dictionary containing additional metadata related
                to the trace.
            end_time: An optional datetime indicating when the trace ended.
            spans: A list of SpanModel instances representing spans of the trace.
            feedback_scores: Optional list of FeedbackScoreModel instances detailing
                collected feedback or evaluation metrics.
            error_info: Optional dictionary providing details on any errors
                encountered during the trace.
            thread_id: An optional identifier for the thread executing the trace.
            last_updated_at: Optional datetime to specify when the trace was
                last updated.

        Returns:
            An instance of TraceModel with the specified attributes.

        Raises:
            NotImplementedError: This method must be implemented in a subclass.
        """
        raise NotImplementedError("This method must be implemented in a subclass.")

    @abc.abstractmethod
    def create_span_model(
        self,
        span_id: str,
        start_time: datetime.datetime,
        name: Optional[str],
        input: Any,
        output: Any,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        type: SpanType,
        usage: Optional[Dict[str, Any]],
        end_time: Optional[datetime.datetime],
        project_name: str,
        spans: Optional[List[models.SpanModel]],
        feedback_scores: Optional[List[models.FeedbackScoreModel]],
        model: Optional[str],
        provider: Optional[str],
        error_info: Optional[ErrorInfoDict],
        total_cost: Optional[float],
        last_updated_at: Optional[datetime.datetime],
    ) -> models.SpanModel:
        """
        Abstract method to create a span model representing a span of a trace.
        This method is intended to facilitate the creation and organization of
        span-related data for tracing or debugging purposes.

        Args:
            span_id: Unique identifier for the span.
            start_time : The start timestamp of the span.
            name: Name of the span (e.g., an operation or process name).
            input: Input data or parameters related to the span.
            output: Output data or result associated with the span.
            tags: List of tags or labels to categorize the span.
            metadata: Additional metadata about the span.
            type: The type or category of the span.
            usage: Information about resource usage within the span.
            end_time: The end timestamp of the span.
            project_name: Name of the project to which the span belongs.
            spans: List of child or nested spans within the current span context.
            feedback_scores: Feedback scores related to this span, if applicable.
            model: The model identifier relevant to the span's processing.
            provider: The provider associated with the span or model.
            error_info: Information regarding errors encountered during the span's execution.
            total_cost: Total cost incurred during the span.
            last_updated_at: Timestamp marking the last update of the span's information.

        Returns:
            models.SpanModel: A fully initialized span model.

        Raises:
            NotImplementedError: This method must be implemented in a subclass.
        """
        raise NotImplementedError("This method must be implemented in a subclass.")

    @abc.abstractmethod
    def create_feedback_score_model(
        self,
        score_id: str,
        name: str,
        value: float,
        category_name: Optional[str],
        reason: Optional[str],
    ) -> models.FeedbackScoreModel:
        """
        Creates a feedback score model with the specified parameters.

        The method is abstract and must be implemented by a subclass. It defines
        the structure for creating a feedback score model by combining the given
        data into the appropriate model object.

        Args:
            score_id: The unique identifier for the feedback score.
            name: The name associated with the feedback score.
            value: The numeric value representing the feedback score.
            category_name: An optional category name for classifying the score.
            reason: An optional explanation or reason related to the feedback
                score.

        Returns:
            An instance of FeedbackScoreModel containing the created feedback
            score details.

        Raises:
            NotImplementedError: This method must be implemented in a subclass.
        """
        raise NotImplementedError("This method must be implemented in a subclass.")


def _observation_already_stored(
    observation_id: str,
    observations: Union[List[models.SpanModel], List[models.TraceModel]],
) -> bool:
    for observation in observations:
        if observation.id == observation_id:
            return True

    return False
