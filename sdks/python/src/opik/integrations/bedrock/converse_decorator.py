import logging
from typing import List, Any, Dict, Optional, Callable, Tuple, Union
from opik import dict_utils
from opik.types import SpanType
from opik.decorator import base_track_decorator, arguments_helpers

from . import stream_wrappers

from botocore import eventstream

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "system", "toolConfig", "guardrailConfig"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUTS = ["output"]


class BedrockConverseDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of AWS bedrock client `converse` function.

    Besides special processing for input arguments and response content, it
    overrides _generators_handler() method to work correctly with bedrock's streams
    """

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        name: Optional[str],
        type: SpanType,
        tags: Optional[List[str]],
        metadata: Optional[Dict[str, Any]],
        capture_input: bool,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
        project_name: Optional[str],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in BedrockRuntime.Client.converse(**kwargs)"

        name = name if name is not None else func.__name__
        input, metadata = dict_utils.split_dict_by_keys(
            kwargs, KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata["created_by"] = "bedrock"
        tags = ["bedrock"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=type,
            tags=tags,
            metadata=metadata,
            project_name=project_name,
        )

        return result

    def _end_span_inputs_preprocessor(
        self, output: Any, capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
        usage = output["usage"]
        usage_in_openai_format = {
            "prompt_tokens": usage["inputTokens"],
            "completion_tokens": usage["outputTokens"],
            "total_tokens": usage["inputTokens"] + usage["outputTokens"],
        }

        output, metadata = dict_utils.split_dict_by_keys(
            output, RESPONSE_KEYS_TO_LOG_AS_OUTPUTS
        )
        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=usage_in_openai_format,
            metadata=metadata,
        )

        return result

    def _generators_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Union[
        base_track_decorator.Generator[Any, None, None],
        None,
    ]:
        assert (
            generations_aggregator is not None
        ), "Bedrock converse decorator will always get aggregator function as input"

        if "stream" in output:
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            stream: eventstream.EventStream = output["stream"]

            return stream_wrappers.wrap_stream(
                generator=stream,
                capture_output=capture_output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NOT_A_STREAM = None

        return NOT_A_STREAM
