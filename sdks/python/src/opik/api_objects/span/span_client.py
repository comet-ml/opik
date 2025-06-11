import datetime
import logging
from typing import Any, Dict, List, Optional, Union

from opik import datetime_helpers, id_helpers, llm_usage, Attachment
from opik.message_processing import messages, streamer

from ..attachment import converters as attachment_converters

from opik.types import (
    DistributedTraceHeadersDict,
    ErrorInfoDict,
    LLMProvider,
    SpanType,
)
from .. import constants, validation_helpers, helpers

LOGGER = logging.getLogger(__name__)


class Span:
    def __init__(
        self,
        id: str,
        trace_id: str,
        project_name: str,
        message_streamer: streamer.Streamer,
        url_override: str,
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
        self._url_override = url_override

    def end(
        self,
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
            usage: Usage data for the span. In order for input, output and total tokens to be visible in the UI,
                the usage must contain OpenAI-formatted keys (they can be passed additionaly to original usage on the top level of the dict):  prompt_tokens, completion_tokens and total_tokens.
                If OpenAI-formatted keys were not found, Opik will try to calculate them automatically if the usage
                format is recognized (you can see which provider's formats are recognized in opik.LLMProvider enum), but it is not guaranteed.
            model: The name of LLM.
            provider: The provider of LLM. You can find providers officially supported by Opik for cost tracking
                in `opik.LLMProvider` enum. If your provider is not here, please open an issue in our github - https://github.com/comet-ml/opik.
                If your provider not in the list, you can still specify it but the cost tracking will not be available
            error_info: The dictionary with error information (typically used when the span function has failed).
            total_cost: The cost of the span in USD. This value takes priority over the cost calculated by Opik from the usage.

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
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
        )

    def update(
        self,
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
    ) -> None:
        """
        Update the span attributes.

        Args:
            end_time: The end time of the span.
            metadata: Additional metadata to be associated with the span.
            input: The input data for the span.
            output: The output data for the span.
            tags: A list of tags to be associated with the span.
            usage: Usage data for the span. In order for input, output and total tokens to be visible in the UI,
                the usage must contain OpenAI-formatted keys (they can be passed additionaly to original usage on the top level of the dict):  prompt_tokens, completion_tokens and total_tokens.
                If OpenAI-formatted keys were not found, Opik will try to calculate them automatically if the usage
                format is recognized (you can see which provider's formats are recognized in opik.LLMProvider enum), but it is not guaranteed.
            model: The name of LLM.
            provider: The provider of LLM. You can find providers officially supported by Opik for cost tracking
                in `opik.LLMProvider` enum. If your provider is not here, please open an issue in our github - https://github.com/comet-ml/opik.
                If your provider not in the list, you can still specify it but the cost tracking will not be available
            error_info: The dictionary with error information (typically used when the span function has failed).
            total_cost: The cost of the span in USD. This value takes priority over the cost calculated by Opik from the usage.

        Returns:
            None
        """
        backend_compatible_usage = validation_helpers.validate_and_parse_usage(
            usage=usage,
            logger=LOGGER,
            provider=provider,
        )

        if backend_compatible_usage is not None:
            metadata = helpers.add_usage_to_metadata(usage=usage, metadata=metadata)

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
            usage=backend_compatible_usage,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
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
        usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None,
        model: Optional[str] = None,
        provider: LLMProvider = LLMProvider.OPENAI,
        error_info: Optional[ErrorInfoDict] = None,
        total_cost: Optional[float] = None,
        attachments: Optional[List[Attachment]] = None,
    ) -> "Span":
        """
        Create a new child span within the current span.

        Args:
            id: The ID of the span should be in UUIDv7 format. If not provided, a new ID will be generated.
            name: The name of the span.
            type: The type of the span. Defaults to "general".
            start_time: The start time of the span. If not provided, the current time will be used.
            end_time: The end time of the span.
            metadata: Additional metadata to be associated with the span.
            input: The input data for the span.
            output: The output data for the span.
            tags: A list of tags to be associated with the span.
            usage: Usage data for the span. In order for input, output and total tokens to be visible in the UI,
                the usage must contain OpenAI-formatted keys (they can be passed additionally to the original usage on the top level of the dict): prompt_tokens, completion_tokens and total_tokens.
                If OpenAI-formatted keys were not found, Opik will try to calculate them automatically if the usage
                format is recognized (you can see which provider's formats are recognized in opik.LLMProvider enum), but it is not guaranteed.
            model: The name of LLM (in this case `type` parameter should be == `llm`)
            provider: The provider of LLM. You can find providers officially supported by Opik for cost tracking
                in `opik.LLMProvider` enum. If your provider is not here, please open an issue in our GitHub - https://github.com/comet-ml/opik.
                If your provider is not in the list, you can still specify it, but the cost tracking will not be available
            error_info: The dictionary with error information (typically used when the span function has failed).
            total_cost: The cost of the span in USD. This value takes priority over the cost calculated by Opik from the usage.
            attachments: The list of attachments to be uploaded to the span.

        Returns:
            Span: The created child span object.
        """
        return create_span(
            trace_id=self.trace_id,
            project_name=self._project_name,
            url_override=self._url_override,
            message_streamer=self._streamer,
            span_id=id,
            parent_span_id=self.id,
            name=name,
            type=type,
            start_time=start_time,
            end_time=end_time,
            metadata=metadata,
            input=input,
            output=output,
            tags=tags,
            usage=usage,
            model=model,
            provider=provider,
            error_info=error_info,
            total_cost=total_cost,
            attachments=attachments,
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


def create_span(
    trace_id: str,
    project_name: str,
    url_override: str,
    message_streamer: streamer.Streamer,
    span_id: Optional[str] = None,
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
    attachments: Optional[List[Attachment]] = None,
) -> Span:
    span_id = span_id if span_id is not None else id_helpers.generate_id()
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
        trace_id=trace_id,
        project_name=project_name,
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
        last_updated_at=datetime_helpers.local_timestamp(),
    )
    message_streamer.put(create_span_message)

    if attachments is not None:
        for attachment_data in attachments:
            create_attachment_message = attachment_converters.attachment_to_message(
                attachment_data=attachment_data,
                entity_type="span",
                entity_id=span_id,
                project_name=project_name,
                url_override=url_override,
            )
            message_streamer.put(create_attachment_message)

    return Span(
        id=span_id,
        parent_span_id=parent_span_id,
        trace_id=trace_id,
        message_streamer=message_streamer,
        project_name=project_name,
        url_override=url_override,
    )
