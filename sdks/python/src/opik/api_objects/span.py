import datetime
import dataclasses
import logging

from typing import Optional, Any, List, Dict
from ..types import SpanType, UsageDict, DistributedTraceHeadersDict, FeedbackScoreDict

from ..message_processing import streamer, messages
from .. import datetime_helpers
from . import helpers, validation_helpers, constants

LOGGER = logging.getLogger(__name__)


class Span:
    def __init__(
        self,
        id: str,
        trace_id: str,
        project_name: str,
        message_streamer: streamer.Streamer,
        parent_span_id: Optional[str] = None,
    ):
        """
        A Span object. This object should not be created directly, instead use the `span` method of a Trace (:func:`opik.Opik.span`) or another Span (:meth:`opik.Span.span`).
        """
        self.id = id
        self.trace_id = trace_id
        self.parent_span_id = parent_span_id
        self._streamer = message_streamer
        self._project_name = project_name

    def end(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[UsageDict] = None,
    ) -> None:
        """
        End the span and update its attributes.

        This method is similar to the `update` method, but it automatically computes
        the end time if not provided.

        Args:
            end_time: The end time of the span. If not provided, the current time will be used.
            metadata: Additional metadata to be associated with the span.
            input: The input data for the span.
            output: The output data for the span.
            tags: A list of tags to be associated with the span.
            usage: Usage information for the span.

        Returns:
            None
        """
        end_time = (
            end_time if end_time is not None else datetime_helpers.local_timestamp()
        )

        self.update(
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=usage,
        )

    def update(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[UsageDict] = None,
    ) -> None:
        """
        Update the span attributes.

        Args:
            end_time: The end time of the span.
            metadata: Additional metadata to be associated with the span.
            input: The input data for the span.
            output: The output data for the span.
            tags: A list of tags to be associated with the span.
            usage: Usage information for the span.

        Returns:
            None
        """
        parsed_usage = validation_helpers.validate_and_parse_usage(usage, LOGGER)
        if parsed_usage.full_usage is not None:
            metadata = (
                {"usage": parsed_usage.full_usage}
                if metadata is None
                else {"usage": parsed_usage.full_usage, **metadata}
            )

        end_span_message = messages.UpdateSpanMessage(
            span_id=self.id,
            trace_id=self.trace_id,
            parent_span_id=self.parent_span_id,
            project_name=self._project_name,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=parsed_usage.supported_usage,
        )
        self._streamer.put(end_span_message)

    def span(
        self,
        id: Optional[str] = None,
        name: Optional[str] = None,
        type: SpanType = "general",
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[UsageDict] = None,
    ) -> "Span":
        """
        Create a new child span within the current span.

        Args:
            id: The ID of the span, should be in UUIDv7 format. If not provided, a new ID will be generated.
            name: The name of the span.
            type: The type of the span. Defaults to "general".
            start_time: The start time of the span. If not provided, current time will be used.
            end_time: The end time of the span.
            metadata: Additional metadata to be associated with the span.
            input: The input data for the span.
            output: The output data for the span.
            tags: A list of tags to be associated with the span.
            usage: Usage information for the span.

        Returns:
            Span: The created child span object.
        """
        span_id = id if id is not None else helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        parsed_usage = validation_helpers.validate_and_parse_usage(usage, LOGGER)
        if parsed_usage.full_usage is not None:
            metadata = (
                {"usage": parsed_usage.full_usage}
                if metadata is None
                else {"usage": parsed_usage.full_usage, **metadata}
            )

        create_span_message = messages.CreateSpanMessage(
            span_id=span_id,
            trace_id=self.trace_id,
            project_name=self._project_name,
            parent_span_id=self.id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            usage=parsed_usage.supported_usage,
        )
        self._streamer.put(create_span_message)

        return Span(
            id=span_id,
            parent_span_id=self.id,
            trace_id=self.trace_id,
            message_streamer=self._streamer,
            project_name=self._project_name,
        )

    def log_feedback_score(
        self,
        name: str,
        value: float,
        category_name: Optional[str] = None,
        reason: Optional[str] = None,
    ) -> None:
        """
        Log a feedback score for the span.

        Args:
            name: The name of the feedback score.
            value: The value of the feedback score.
            category_name: The category name for the feedback score.
            reason: The reason for the feedback score.

        Returns:
            None
        """
        add_span_feedback_batch_message = messages.AddSpanFeedbackScoresBatchMessage(
            batch=[
                messages.FeedbackScoreMessage(
                    id=self.id,
                    name=name,
                    value=value,
                    category_name=category_name,
                    reason=reason,
                    source=constants.FEEDBACK_SCORE_SOURCE_SDK,
                    project_name=self._project_name,
                )
            ],
        )

        self._streamer.put(add_span_feedback_batch_message)

    def get_distributed_trace_headers(self) -> DistributedTraceHeadersDict:
        """
        Returns headers dictionary to be passed into tracked
        function on remote node.
        """
        return {"opik_parent_span_id": self.id, "opik_trace_id": self.trace_id}


@dataclasses.dataclass
class SpanData:
    """
    The SpanData object is returned when calling :func:`opik.opik_context.get_current_span_data` from a tracked function.
    """

    trace_id: str
    id: str = dataclasses.field(default_factory=helpers.generate_id)
    parent_span_id: Optional[str] = None
    name: Optional[str] = None
    type: SpanType = "general"
    start_time: Optional[datetime.datetime] = dataclasses.field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    usage: Optional[UsageDict] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None

    def update(self, **new_data: Any) -> "SpanData":
        for key, value in new_data.items():
            if value is not None:
                if key in self.__dict__:
                    self.__dict__[key] = value
                else:
                    LOGGER.debug(
                        "An attempt to update span with parameter name it doesn't have: %s",
                        key,
                    )

        return self

    def init_end_time(self) -> "SpanData":
        self.end_time = datetime_helpers.local_timestamp()

        return self
