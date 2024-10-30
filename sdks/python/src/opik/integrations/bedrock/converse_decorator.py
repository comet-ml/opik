import logging
from typing import List, Any, Dict, Optional, Callable, Tuple
from opik import dict_utils
from opik.types import SpanType
from opik.decorator import base_track_decorator, arguments_helpers


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
