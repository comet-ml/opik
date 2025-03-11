import datetime
import logging
from typing import Any, Dict, List, Optional, Union


from opik import datetime_helpers, id_helpers, llm_usage
from opik.message_processing import messages, streamer
from opik.types import ErrorInfoDict, SpanType, LLMProvider
from .. import constants, span, validation_helpers, helpers

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
        thread_id: Optional[str] = None,
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
            thread_id: Used to group multiple traces into a thread.
                The identifier is user-defined and has to be unique per project.

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
            thread_id=thread_id,
        )

    def update(
        self,
        end_time: Optional[datetime.datetime] = None,
        metadata: Optional[Dict[str, Any]] = None,
        input: Optional[Dict[str, Any]] = None,
        output: Optional[Dict[str, Any]] = None,
        tags: Optional[List[Any]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        thread_id: Optional[str] = None,
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
            thread_id: Used to group multiple traces into a thread.
                The identifier is user-defined and has to be unique per project.

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
            thread_id=thread_id,
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
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        model: Optional[str] = None,
        provider: Optional[Union[LLMProvider, str]] = None,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
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
            usage: Usage data for the span. In order for input, output and total tokens to be visible in the UI,
                the usage must contain OpenAI-formatted keys (they can be passed additionaly to original usage on the top level of the dict):  prompt_tokens, completion_tokens and total_tokens.
                If OpenAI-formatted keys were not found, Opik will try to calculate them automatically if the usage
                format is recognized (you can see which provider's formats are recognized in opik.LLMProvider enum), but it is not guaranteed.
            model: The name of LLM (in this case `type` parameter should be == `llm`)
            provider: The provider of LLM. You can find providers officially supported by Opik for cost tracking
                in `opik.LLMProvider` enum. If your provider is not here, please open an issue in our github - https://github.com/comet-ml/opik.
                If your provider not in the list, you can still specify it but the cost tracking will not be available
            error_info: The dictionary with error information (typically used when the span function has failed).
            total_cost: The cost of the span in USD. This value takes priority over the cost calculated by Opik from the usage.

        Returns:
            span.Span: The created span object.
        """
        span_id = id if id is not None else id_helpers.generate_id()
        start_time = (
            start_time if start_time is not None else datetime_helpers.local_timestamp()
        )
        backend_compatible_usage = validation_helpers.validate_and_parse_usage(
            usage=usage,
            logger=LOGGER,
            provider=provider,
        )

        if backend_compatible_usage is not None:
            metadata = helpers.add_usage_to_metadata(usage=usage, metadata=metadata)

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
            usage=backend_compatible_usage,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
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
