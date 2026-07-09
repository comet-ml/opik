import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

import groq
from groq import _types as _groq_types
from groq.types.chat import chat_completion
from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

from . import chat_completion_chunks_aggregator, stream_patchers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "function_call"]


class GroqChatCompletionsTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator for tracking calls of Groq's
    `chat.completions.create` function, including `stream=True` mode.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "groq"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, (
            "Expected kwargs to be not None in chat.completions.create(**kwargs)"
        )

        name = track_options.name if track_options.name is not None else func.__name__
        if kwargs.get("stream") is True:
            kwargs = _remove_not_given_sentinel_values(kwargs)
            name = "chat_completion_stream"

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "groq",
                "type": "groq_chat",
            }
        )

        tags = ["groq"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("model", None),
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
        assert isinstance(
            output,
            (
                chat_completion.ChatCompletion,
                chat_completion_chunks_aggregator.ChatCompletionChunksAggregated,
            ),
        )

        result_dict = output.model_dump(mode="json")
        output, metadata = dict_utils.split_dict_by_keys(result_dict, ["choices"])

        opik_usage = None
        if result_dict.get("usage") is not None:
            # Groq returns an OpenAI-shaped usage payload, so it is parsed with the
            # OpenAI converter. Here "openai" denotes the usage payload format, not
            # the span's provider (which is recorded as "groq").
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage=result_dict["usage"],
                logger=LOGGER,
                error_message="Failed to log token usage from groq call",
            )

        model = result_dict["model"]

        result = arguments_helpers.EndSpanParameters(
            output=output,
            usage=opik_usage,
            metadata=metadata,
            model=model,
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
        assert generations_aggregator is not None, (
            "Groq decorator will always get aggregator function as input"
        )

        if isinstance(output, groq.Stream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if isinstance(output, groq.AsyncStream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NOT_A_STREAM = None

        return NOT_A_STREAM


def _remove_not_given_sentinel_values(dict_: Dict[str, Any]) -> Dict[str, Any]:
    """
    Under the hood the streaming helpers pass a lot of NOT_GIVEN values that we
    don't want to track.
    """
    return {
        key: value
        for key, value in dict_.items()
        if value is not _groq_types.NOT_GIVEN
        and not isinstance(value, _groq_types.Omit)
    }
