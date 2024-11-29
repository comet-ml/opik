import logging
from typing import List, Any, Dict, Optional, Callable, Tuple, Union
from opik.decorator import base_track_decorator, arguments_helpers
from opik import dict_utils

import anthropic
from anthropic.types import Message as AnthropicMessage

from . import stream_patchers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "system", "tools"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["content"]


class AnthropicMessagesCreateDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of `[Anthropic.AsyncAnthropic].messages.create` method.
    """

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in Antropic.messages.create(**kwargs)"
        metadata = track_options.metadata if track_options.metadata is not None else {}
        name = track_options.name if track_options.name is not None else func.__name__

        input, metadata_from_kwargs = dict_utils.split_dict_by_keys(
            kwargs, KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata.update(metadata_from_kwargs)
        metadata["created_from"] = "anthropic"
        tags = ["anthropic"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
        )

        return result

    def _end_span_inputs_preprocessor(
        self, output: Union[str, AnthropicMessage], capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
        if isinstance(output, str):
            output = {"error": output}
            result = arguments_helpers.EndSpanParameters(output=output)

            return result

        usage = {
            "prompt_tokens": output.usage.input_tokens,
            "completion_tokens": output.usage.output_tokens,
            "total_tokens": output.usage.input_tokens + output.usage.output_tokens,
        }

        output_dict = output.model_dump()
        span_output, metadata = dict_utils.split_dict_by_keys(
            output_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        result = arguments_helpers.EndSpanParameters(
            output=span_output, usage=usage, metadata=metadata
        )

        return result

    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Union[
        None, anthropic.MessageStreamManager, anthropic.AsyncMessageStreamManager
    ]:
        if isinstance(output, anthropic.MessageStreamManager):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_message_stream_manager(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        if isinstance(output, anthropic.AsyncMessageStreamManager):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_message_stream_manager(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        if isinstance(output, anthropic.Stream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_stream(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        if isinstance(output, anthropic.AsyncStream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_stream(
                output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                finally_callback=self._after_call,
            )

        NOT_A_STREAM = None

        return NOT_A_STREAM
