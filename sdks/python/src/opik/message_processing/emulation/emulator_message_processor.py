import abc
import collections
import dataclasses
import datetime
import logging
import threading
from typing import List, Dict, Tuple, TypeVar, Union, Optional, Any, Type, Callable

from opik import dict_utils
from opik.rest_api.types import span_write, trace_write
from opik.types import ErrorInfoDict, SpanType, TraceSource
from . import models
from .. import messages
from ..processors import message_processors

ModelT = TypeVar("ModelT", models.TraceModel, models.SpanModel)

# Fields are populated incrementally from outside the create-message path
# (tree building, feedback batches, attachment messages). They must not be
# overwritten when a duplicate Create message merges into an existing model.
_MERGE_PRESERVED_FIELDS = frozenset({"spans", "feedback_scores", "attachments"})


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
            self._trace_to_attachments: Dict[str, List[models.AttachmentModel]] = (
                collections.defaultdict(list)
            )
            self._span_to_attachments: Dict[str, List[models.AttachmentModel]] = (
                collections.defaultdict(list)
            )
            self._experiment_items: List[models.ExperimentItemModel] = []
            # Updates that arrived before the matching CreateSpanMessage /
            # CreateTraceMessage. Applied as soon as the create lands so we
            # don't drop fields when the queue/batcher delivers messages out
            # of order (common with batching enabled).
            self._pending_span_updates: Dict[str, List[Dict[str, Any]]] = (
                collections.defaultdict(list)
            )
            self._pending_trace_updates: Dict[str, List[Dict[str, Any]]] = (
                collections.defaultdict(list)
            )
            # Track parent/span pairs we've already warned about so multiple
            # `trace_trees` / `span_trees` reads don't spam the log.
            self._warned_orphan_parents: set = set()

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
            missing_trace_ids: set[str] = set()
            traces_with_new_children: set = set()

            for span_id, trace_id in self._span_to_trace.items():
                if trace_id is None:
                    continue
                if trace_id not in self._trace_observations:
                    missing_trace_ids.add(trace_id)
                    continue

                trace = self._trace_observations[trace_id]
                if self._span_to_parent_span[
                    span_id
                ] is None and not _observation_already_stored(span_id, trace.spans):
                    span = self._span_observations[span_id]
                    trace.spans.append(span)
                    traces_with_new_children.add(trace_id)

            for trace_id in traces_with_new_children:
                self._trace_observations[trace_id].spans.sort(
                    key=lambda x: x.start_time
                )

            if missing_trace_ids:
                LOGGER.warning(
                    "Skipping %d orphan span-to-trace link(s) with missing trace observation(s): %s",
                    len(missing_trace_ids),
                    ", ".join(sorted(missing_trace_ids)[:5]),
                )

            for trace in self._trace_trees:
                trace.feedback_scores = self._trace_to_feedback_scores[trace.id]
                trace.attachments = self._trace_to_attachments[trace.id] or None

            self._trace_trees.sort(key=lambda x: x.start_time)
            return self._trace_trees

    def _save_trace(self, trace: models.TraceModel) -> None:
        existing_trace = self._trace_observations.get(trace.id)

        if existing_trace is None or not self.merge_duplicates:
            self._trace_trees.append(trace)
            self._trace_observations[trace.id] = trace
            self._apply_pending_trace_updates(trace)
            return

        merged = _merge_models(existing_trace, trace)
        if existing_trace in self._trace_trees:
            index = self._trace_trees.index(existing_trace)
            self._trace_trees[index] = merged
        else:
            self._trace_trees.append(merged)
        self._trace_observations[trace.id] = merged
        self._apply_pending_trace_updates(merged)

    def _apply_pending_trace_updates(self, trace: models.TraceModel) -> None:
        pending = self._pending_trace_updates.pop(trace.id, None)
        if not pending:
            return
        for payload in pending:
            trace.__dict__.update(payload)

    def _save_span(
        self, span: models.SpanModel, trace_id: str, parent_span_id: Optional[str]
    ) -> None:
        existing_span = self._span_observations.get(span.id)

        if existing_span is None or not self.merge_duplicates:
            if parent_span_id is None:
                self._span_trees.append(span)
            self._span_to_parent_span[span.id] = parent_span_id
            self._span_to_trace[span.id] = trace_id
            self._span_observations[span.id] = span
            self._apply_pending_span_updates(span)
            return

        merged = _merge_models(existing_span, span)

        # Late start messages can arrive with a stale (None) parent_span_id /
        # trace_id even when the entity already had real values. Prefer the
        # non-None observation we already had.
        merged_parent_span_id = parent_span_id
        if merged_parent_span_id is None:
            merged_parent_span_id = self._span_to_parent_span.get(span.id)
        merged_trace_id = trace_id
        existing_trace_id = self._span_to_trace.get(span.id)
        if existing_trace_id is not None:
            merged_trace_id = existing_trace_id

        if existing_span in self._span_trees:
            index = self._span_trees.index(existing_span)
            self._span_trees[index] = merged
        elif merged_parent_span_id is None:
            self._span_trees.append(merged)

        self._span_to_parent_span[span.id] = merged_parent_span_id
        self._span_to_trace[span.id] = merged_trace_id
        self._span_observations[span.id] = merged
        self._apply_pending_span_updates(merged)

    def _apply_pending_span_updates(self, span: models.SpanModel) -> None:
        pending = self._pending_span_updates.pop(span.id, None)
        if not pending:
            return
        for payload in pending:
            span.__dict__.update(payload)

    @property
    def span_trees(self) -> List[models.SpanModel]:
        self._build_spans_tree()
        return self._span_trees

    def _build_spans_tree(self) -> None:
        """
        Builds a list of span trees based on the data from the processed messages.
        Children's spans are sorted by creation time.

        Children references are refreshed from `_span_observations` on every
        rebuild. When `_save_span` replaces an entry under `merge_duplicates`
        (a second CreateSpanMessage with the same ID but newer fields),
        the parent's `.spans` list would otherwise keep the stale reference
        with an out-of-date `start_time`, and the subsequent sort would put
        children in the wrong order.
        """
        with self._rlock:
            visited_parents: set = set()
            for span_id, parent_span_id in self._span_to_parent_span.items():
                if parent_span_id is None:
                    continue

                # Parent span may not have arrived yet when messages are
                # produced by parallel async flows; skip attaching until the
                # next rebuild instead of crashing. Log so orphans are
                # observable instead of silently dropped — if the parent
                # never arrives the child stays out of the tree, and this
                # warning is the only signal.
                parent_span = self._span_observations.get(parent_span_id)
                if parent_span is None:
                    warning_key = (span_id, parent_span_id)
                    if warning_key not in self._warned_orphan_parents:
                        self._warned_orphan_parents.add(warning_key)
                        LOGGER.warning(
                            "Skipping span %s: parent span %s is not yet observed. "
                            "Child will remain orphaned until the parent's "
                            "CreateSpanMessage is processed.",
                            span_id,
                            parent_span_id,
                        )
                    continue

                # First time we see this parent on this rebuild — drop stale
                # references so we re-attach against the current observation
                # snapshot.
                if parent_span_id not in visited_parents:
                    parent_span.spans = []
                    visited_parents.add(parent_span_id)

                if not _observation_already_stored(span_id, parent_span.spans):
                    parent_span.spans.append(self._span_observations[span_id])

            for parent_id in visited_parents:
                self._span_observations[parent_id].spans.sort(
                    key=lambda x: x.start_time
                )

            all_span_ids = self._span_to_trace
            for span_id in all_span_ids:
                span = self._span_observations[span_id]
                span.feedback_scores = self._span_to_feedback_scores[span_id]
                span.attachments = self._span_to_attachments[span_id] or None

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
        source: TraceSource,
        last_updated_at: Optional[datetime.datetime] = None,
        environment: Optional[str] = None,
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
        source: TraceSource,
        environment: Optional[str] = None,
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
            source: The source of the span's data (e.g., sdk, experiment).

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
            messages.CreateAttachmentMessage: self._handle_create_attachment_message,  # type: ignore
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
            source=message.source,
            environment=message.environment,
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
            source=message.source,
            environment=message.environment,
        )

        self._save_span(
            span, trace_id=message.trace_id, parent_span_id=message.parent_span_id
        )

    def _handle_update_span_message(self, message: messages.UpdateSpanMessage) -> None:
        update_payload = dict_utils.remove_none_from_dict(
            {
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
        )
        span = self._span_observations.get(message.span_id)
        if span is None:
            # Queue the payload until the matching create arrives — batching
            # can deliver Update before Create.
            self._pending_span_updates[message.span_id].append(update_payload)
            return
        span.__dict__.update(update_payload)

    def _handle_update_trace_message(
        self, message: messages.UpdateTraceMessage
    ) -> None:
        update_payload = dict_utils.remove_none_from_dict(
            {
                "output": message.output,
                "end_time": message.end_time,
                "metadata": message.metadata,
                "error_info": message.error_info,
                "tags": message.tags,
                "input": message.input,
                "thread_id": message.thread_id,
            }
        )
        current_trace = self._trace_observations.get(message.trace_id)
        if current_trace is None:
            self._pending_trace_updates[message.trace_id].append(update_payload)
            return
        current_trace.__dict__.update(update_payload)

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
            source=message.source,
            environment=message.environment,
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
            source=message.source,
            environment=message.environment,
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

    def _handle_create_attachment_message(
        self, message: messages.CreateAttachmentMessage
    ) -> None:
        """Handle attachment messages by adding them to the appropriate span or trace.

        Attachments are stored in temporary dictionaries and will be connected to their
        spans/traces when the trace trees are built, similar to how feedback scores work.
        """
        attachment_model = models.AttachmentModel(
            file_path=message.file_path,
            file_name=message.file_name,
            content_type=message.mime_type,
        )

        if message.entity_type == "span":
            self._span_to_attachments[message.entity_id].append(attachment_model)
        elif message.entity_type == "trace":
            self._trace_to_attachments[message.entity_id].append(attachment_model)

    def _noop_handler(self, message: messages.BaseMessage) -> None:
        # just ignore the message
        pass

    @property
    def experiment_items(self) -> List[models.ExperimentItemModel]:
        """Returns the list of experiment items collected."""
        with self._rlock:
            return self._experiment_items


def _merge_models(existing: ModelT, new: ModelT) -> ModelT:
    """Merge two trace/span models that share the same id.

    Duplicate Create messages reach the emulator out of order: a START message
    (no end_time, defaulted type, no output) can arrive after the matching
    FULL message (end_time set, output filled). The previous "newer end_time
    wins" check silently let the late START overwrite the FULL entry. This
    helper picks the more complete entry as primary and fills any None gaps
    from the other so neither order produces data loss.
    """
    primary, secondary = _pick_primary(existing, new)

    merged_kwargs: Dict[str, Any] = {}
    for field in dataclasses.fields(type(primary)):
        if field.name in _MERGE_PRESERVED_FIELDS:
            # Children/feedback/attachments are accumulated externally; the
            # in-memory `existing` model already holds whatever was attached.
            merged_kwargs[field.name] = getattr(existing, field.name)
            continue

        primary_value = getattr(primary, field.name)
        secondary_value = getattr(secondary, field.name)

        if _is_missing(primary_value) and not _is_missing(secondary_value):
            merged_kwargs[field.name] = secondary_value
        else:
            merged_kwargs[field.name] = primary_value

    return type(primary)(**merged_kwargs)


def _pick_primary(existing: ModelT, new: ModelT) -> Tuple[ModelT, ModelT]:
    """Return ``(primary, secondary)`` where primary is the more complete entry.

    Completeness order:
    1. Whichever entry has a non-None ``end_time`` wins (FULL > START).
    2. If both have end_time, the later one wins.
    3. If neither has end_time, fall back to ``last_updated_at``; ties go to
       ``new`` (last write wins).
    """
    if existing.end_time is not None and new.end_time is None:
        return existing, new
    if existing.end_time is None and new.end_time is not None:
        return new, existing
    if existing.end_time is not None and new.end_time is not None:
        if new.end_time >= existing.end_time:
            return new, existing
        return existing, new

    existing_updated = existing.last_updated_at
    new_updated = new.last_updated_at
    if existing_updated is not None and new_updated is None:
        return existing, new
    if existing_updated is None and new_updated is not None:
        return new, existing
    if existing_updated is not None and new_updated is not None:
        if new_updated >= existing_updated:
            return new, existing
        return existing, new
    return new, existing


def _is_missing(value: Any) -> bool:
    if value is None:
        return True
    if isinstance(value, list) and len(value) == 0:
        return True
    return False


def _observation_already_stored(
    observation_id: str,
    observations: Union[List[models.SpanModel], List[models.TraceModel]],
) -> bool:
    for observation in observations:
        if observation.id == observation_id:
            return True

    return False
