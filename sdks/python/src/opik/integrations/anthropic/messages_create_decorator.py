import logging
from typing import List, Any, Dict, Optional, Callable, Tuple, Union
from opik.types import SpanType
from opik.decorator import base_track_decorator, arguments_helpers
from opik import dict_utils

from anthropic.types import Message as AnthropicMessage


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
        ), "Expected kwargs to be not None in Antropic.messages.create(**kwargs)"

        input, metadata = dict_utils.split_dict_by_keys(
            kwargs, KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata["created_from"] = "anthropic"
        tags = ["anthropic"]
        name = name if name is not None else func.__name__

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
        self, output: AnthropicMessage, capture_output: bool
    ) -> arguments_helpers.EndSpanParameters:
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
    ) -> Union[None,]:
        NOT_A_STREAM = None

        return NOT_A_STREAM
