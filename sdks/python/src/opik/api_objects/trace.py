import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional

from . import constants, helpers, span, validation_helpers
from .. import datetime_helpers
from ..message_processing import messages, streamer
from ..types import CreatedByType, FeedbackScoreDict, SpanType, UsageDict, ErrorInfoDict
from opik import dict_utils

LOGGER = logging.getLogger(__name__)


class Trace:
    def __init__(
        self,
        id: str,
        message_streamer: streamer.Streamer,
        project_name: str,
    ):
        """
        A Trace object. This object should not be created directly, instead use :meth:`opik.Opik.trace` to create a new trace.
        """
        self.id = id
        self._streamer = message_streamer
        self._project_name = project_name

    def end(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
    ) -> None:
        """
        End the trace and update its attributes.

        This method is similar to the `update` method, but it automatically computes
        the end time if not provided.

        Args:
            end_time: The end time of the trace. If not provided, the current time will be used.
            metadata: Additional metadata to be associated with the trace.
            input: The input data for the trace.
            output: The output data for the trace.
            tags: A list of tags to be associated with the trace.
            error_info: The dictionary with error information (typically used when the trace function has failed).

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
            error_info=error_info,
        )

    def update(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
    ) -> None:
        """
        Update the trace attributes.

        Args:
            end_time: The end time of the trace.
            metadata: Additional metadata to be associated with the trace.
            input: The input data for the trace.
            output: The output data for the trace.
            tags: A list of tags to be associated with the trace.
            error_info: The dictionary with error information (typically used when the trace function has failed).

        Returns:
            None
        """
        update_trace_message = messages.UpdateTraceMessage(
            trace_id=self.id,
            project_name=self._project_name,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            error_info=error_info,
        )
        self._streamer.put(update_trace_message)

    def span(
        self,
        id: Optional[str] = None,
        parent_span_id: Optional[str] = None,
        name: Optional[str] = None,
        type: SpanType = "general",
        start_time: Optional[datetime.datetime] = None,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[str]] = None,
        usage: Optional[UsageDict] = None,
        model: Optional[str] = None,
        provider: Optional[str] = None,
        error_info: Optional[ErrorInfoDict] = None,
    ) -> span.Span:
        """
        Create a new span within the trace.

        Args:
            id: The ID of the span, should be in UUIDv7 format. If not provided, a new ID will be generated.
            parent_span_id: The ID of the parent span, if any.
            name: The name of the span.
            type: The type of the span. Defaults to "general".
            start_time: The start time of the span. If not provided, current time will be used.
            end_time: The end time of the span.
            metadata: Additional metadata to be associated with the span.
            input: The input data for the span.
            output: The output data for the span.
            tags: A list of tags to be associated with the span.
            usage: Usage information for the span.
            model: The name of LLM (in this case `type` parameter should be == `llm`)
            provider: The provider of LLM.
            error_info: The dictionary with error information (typically used when the span function has failed).

        Returns:
            span.Span: The created span object.
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
            trace_id=self.id,
            project_name=self._project_name,
            parent_span_id=parent_span_id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            input=input,
            output=output,
            metadata=metadata,
            tags=tags,
            usage=parsed_usage.supported_usage,
            model=model,
            provider=provider,
            error_info=error_info,
        )
        self._streamer.put(create_span_message)

        return span.Span(
            id=span_id,
            parent_span_id=parent_span_id,
            trace_id=self.id,
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
        Log a feedback score for the trace.

        Args:
            name: The name of the feedback score.
            value: The value of the feedback score.
            category_name: The category name for the feedback score.
            reason: The reason for the feedback score.

        Returns:
            None
        """
        add_trace_feedback_batch_message = messages.AddTraceFeedbackScoresBatchMessage(
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

        self._streamer.put(add_trace_feedback_batch_message)


# Engineer note:
#
# After moving to minimal python version 3.10, a lot of common content
# from SpanData and TraceData can be moved to ObservationData parent dataclass.
# Before that it's impossible because of the dataclasses limitation to have optional arguments
# strictly after positional ones (including the attributes from the parent class).
# In python 3.10 @dataclass(kw_only=True) should help.
@dataclasses.dataclass
class TraceData:
    """
    The TraceData object is returned when calling :func:`opik.opik_context.get_current_trace_data` from a tracked function.
    """

    id: str = dataclasses.field(default_factory=helpers.generate_id)
    name: Optional[str] = None
    start_time: Optional[datetime.datetime] = dataclasses.field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
    project_name: Optional[str] = None
    created_by: Optional[CreatedByType] = None
    error_info: Optional[ErrorInfoDict] = None

    def update(self, **new_data: Any) -> "TraceData":
        for key, value in new_data.items():
            if value is None:
                continue

            if key not in self.__dict__:
                LOGGER.debug(
                    "An attempt to update span with parameter name it doesn't have: %s",
                    key,
                )
                continue

            if key == "metadata":
                self._update_metadata(value)
                continue
            elif key == "output":
                self._update_output(value)
                continue
            elif key == "input":
                self._update_input(value)
                continue

            self.__dict__[key] = value

        return self

    def _update_metadata(self, new_metadata: Dict[str, Any]) -> None:
        if self.metadata is None:
            self.metadata = new_metadata
        else:
            self.metadata = dict_utils.deepmerge(self.metadata, new_metadata)

    def _update_output(self, new_output: Dict[str, Any]) -> None:
        if self.output is None:
            self.output = new_output
        else:
            self.output = dict_utils.deepmerge(self.output, new_output)

    def _update_input(self, new_input: Dict[str, Any]) -> None:
        if self.input is None:
            self.input = new_input
        else:
            self.input = dict_utils.deepmerge(self.input, new_input)

    def init_end_time(self) -> "TraceData":
        self.end_time = datetime_helpers.local_timestamp()
        return self
