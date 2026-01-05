import abc
import collections
import datetime
import logging
import threading
from typing import List, Dict, Union, Optional, Any, Type, Callable

from opik import dict_utils
from opik.rest_api.types import span_write, trace_write
from opik.types import ErrorInfoDict, SpanType
from . import models
from .. import messages
from ..processors import message_processors


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
        self.merge_duplicates = merge_duplicates
        self._active = active

        self._register_handlers()

        self._rlock = threading.RLock()

        self.reset()

    def reset(self) -> None:
        """
        Resets the internal state of the instance to its initial configuration.

        This method clears and re-initializes all internal attributes related to
        processed messages, traces, spans, and feedback scores. It ensures that
        the instance can start fresh, as if it was just created, without retaining
        any previous data.
        """
        with self._rlock:
            self._trace_trees: List[models.TraceModel] = []

            self._traces_to_spans_mapping: Dict[str, List[str]] = (
                collections.defaultdict(list)
            )
            # the same as _trace_trees but without a trace. Useful for distributed tracing.
            self._span_trees: List[models.SpanModel] = []
            self._trace_observations: Dict[str, models.TraceModel] = {}
            self._span_observations: Dict[str, models.SpanModel] = {}

            self._span_to_parent_span: Dict[str, Optional[str]] = {}
            self._span_to_trace: Dict[str, Optional[str]] = {}
            self._trace_to_feedback_scores: Dict[
                str, List[models.FeedbackScoreModel]
            ] = collections.defaultdict(list)
            self._span_to_feedback_scores: Dict[
                str, List[models.FeedbackScoreModel]
            ] = collections.defaultdict(list)
            self._experiment_items: List[models.ExperimentItemModel] = []

    def is_active(self) -> bool:
        with self._rlock:
            return self._active

    def set_active(self, active: bool) -> None:
        with self._rlock:
            self._active = active

    @property
    def trace_trees(self) -> List[models.TraceModel]:
        """
        Builds a list of trace trees based on the data from the processed messages.
        Before processing traces, builds span_trees
        """
        with self._rlock:
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
        with self._rlock:
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
        message_type = type(message)
        handler = self._handlers.get(message_type)
        if handler is None:
            LOGGER.debug("Unknown type of message - %s", message_type.__name__)
            return

        handler(message)

    def process(self, message: messages.BaseMessage) -> None:
        with self._rlock:
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

    def _register_handlers(self) -> None:
        self._handlers: Dict[Type, Callable[[messages.BaseMessage], None]] = {
            messages.CreateSpanMessage: self._handle_create_span_message,  # type: ignore
            messages.CreateTraceMessage: self._handle_create_trace_message,  # type: ignore
            messages.UpdateSpanMessage: self._handle_update_span_message,  # type: ignore
            messages.UpdateTraceMessage: self._handle_update_trace_message,  # type: ignore
            messages.AddTraceFeedbackScoresBatchMessage: self._handle_add_trace_feedback_scores_batch_message,  # type: ignore
            messages.AddSpanFeedbackScoresBatchMessage: self._handle_add_span_feedback_scores_batch_message,  # type: ignore
            messages.CreateSpansBatchMessage: self._handle_create_spans_batch_message,  # type: ignore
            messages.CreateTraceBatchMessage: self._handle_create_traces_batch_message,  # type: ignore
            messages.CreateExperimentItemsBatchMessage: self._handle_create_experiment_items_batch_message,  # type: ignore
            messages.AttachmentSupportingMessage: self._noop_handler,  # type: ignore
        }

    def _handle_create_trace_message(
        self, message: messages.CreateTraceMessage
    ) -> None:
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

    def _handle_create_span_message(self, message: messages.CreateSpanMessage) -> None:
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

    def _handle_update_span_message(self, message: messages.UpdateSpanMessage) -> None:
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

    def _handle_update_trace_message(
        self, message: messages.UpdateTraceMessage
    ) -> None:
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

    def _handle_add_span_feedback_scores_batch_message(
        self, message: messages.AddSpanFeedbackScoresBatchMessage
    ) -> None:
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

    def _handle_add_trace_feedback_scores_batch_message(
        self, message: messages.AddTraceFeedbackScoresBatchMessage
    ) -> None:
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

    def _handle_create_spans_batch_message(
        self, message: messages.CreateSpansBatchMessage
    ) -> None:
        for item in message.batch:
            self._handle_span_write(item)

    def _handle_span_write(self, message: span_write.SpanWrite) -> None:
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

    def _handle_create_traces_batch_message(
        self, message: messages.CreateTraceBatchMessage
    ) -> None:
        for item in message.batch:
            self._handle_trace_write(item)

    def _handle_trace_write(self, message: trace_write.TraceWrite) -> None:
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

    def _handle_create_experiment_items_batch_message(
        self, message: messages.CreateExperimentItemsBatchMessage
    ) -> None:
        for experiment_item_message in message.batch:
            experiment_item = models.ExperimentItemModel(
                id=experiment_item_message.id,
                experiment_id=experiment_item_message.experiment_id,
                trace_id=experiment_item_message.trace_id,
                dataset_item_id=experiment_item_message.dataset_item_id,
            )
            self._experiment_items.append(experiment_item)

    def _noop_handler(self, message: messages.BaseMessage) -> None:
        # just ignore the message
        pass

    @property
    def experiment_items(self) -> List[models.ExperimentItemModel]:
        """Returns the list of experiment items collected."""
        with self._rlock:
            return self._experiment_items


def _observation_already_stored(
    observation_id: str,
    observations: Union[List[models.SpanModel], List[models.TraceModel]],
) -> bool:
    for observation in observations:
        if observation.id == observation_id:
            return True

    return False
