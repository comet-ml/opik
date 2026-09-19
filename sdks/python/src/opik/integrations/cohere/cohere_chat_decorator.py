import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

import cohere
from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

from . import chat_stream_aggregator, stream_wrappers

LOGGER = logging.getLogger(__name__)

RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["message", "finish_reason"]


class CohereChatTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator for tracking calls of Cohere's
    `ClientV2.chat` and `ClientV2.chat_stream`.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "cohere"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        kwargs_copy = {k: v for k, v in kwargs.items() if v is not None}

        name = track_options.name if track_options.name is not None else func.__name__
        input_, metadata = dict_utils.split_dict_by_keys(kwargs_copy, ["messages"])
        metadata.update(
            {
                "created_from": "cohere",
                "type": "cohere_chat",
            }
        )

        tags = ["cohere"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs_copy.get("model"),
            provider=self.provider,
        )

        return result

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result_dict = output.model_dump(mode="json")
        span_output, metadata = dict_utils.split_dict_by_keys(
            result_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )

        opik_usage = None
        raw_usage = result_dict.get("usage")
        if raw_usage is not None:
            normalized = _to_openai_shaped_usage(raw_usage)
            if normalized is not None:
                # Cohere reports usage as tokens.input_tokens / tokens.output_tokens,
                # so it is normalized to the OpenAI shape above and parsed with the
                # OpenAI converter. Here "openai" denotes the usage payload format,
                # not the span's provider (which is recorded as "cohere").
                opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                    provider=LLMProvider.OPENAI,
                    usage=normalized,
                    logger=LOGGER,
                    error_message="Failed to log token usage from cohere call",
                )

        result = arguments_helpers.EndSpanParameters(
            output=span_output,
            usage=opik_usage,
            metadata=metadata,
            model=current_span_data.model,
            provider=self.provider,
        )

        return result

    @override
    def _streams_handler(  # type: ignore
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        assert generations_aggregator is chat_stream_aggregator.aggregate, (
            "Cohere decorator will always get aggregator function as input"
        )

        if isinstance(output, cohere.core.api_error.ApiError):
            return None

        # Pydantic models define __iter__, so duck-typing on it would misread a
        # plain chat() response as a stream. chat_stream() is a generator
        # function, so its return value is precisely a generator.
        if inspect.isasyncgen(output):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_async_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if inspect.isgenerator(output):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_sync_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NOT_A_STREAM = None

        return NOT_A_STREAM


def _to_openai_shaped_usage(usage: Dict[str, Any]) -> Optional[Dict[str, int]]:
    """Map Cohere's usage payload onto the OpenAI token names."""
    tokens = usage.get("tokens") or {}
    input_tokens = tokens.get("input_tokens")
    output_tokens = tokens.get("output_tokens")

    if input_tokens is None or output_tokens is None:
        return None

    input_tokens = int(input_tokens)
    output_tokens = int(output_tokens)

    return {
        "prompt_tokens": input_tokens,
        "completion_tokens": output_tokens,
        "total_tokens": input_tokens + output_tokens,
    }
