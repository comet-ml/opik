import logging
from typing import (
    Any,
    AsyncIterator,
    Callable,
    Dict,
    Iterator,
    List,
    Optional,
    Tuple,
    Union,
)

import openai
from openai import _types as _openai_types
from openai.types.chat import chat_completion, chat_completion_chunk
from typing_extensions import override

from opik import dict_utils, llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.integrations.openai import chat_completion_chunks_aggregator
from opik.types import LLMProvider

from . import stream_patchers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "function_call"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = ["choices"]


class OpenaiChatCompletionsTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `chat.completion.create` and `chat.completions.parse` functions.

    Besides special processing for input arguments and response content, it
    overrides _generators_handler() method to work correctly with
    openai.Stream and openai.AsyncStream objects.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "openai"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in chat.completion.create(**kwargs), chat.completion.parse(**kwargs) or chat.completion.stream(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        if _is_completions_stream_call(
            name_passed_to_track_decorator=name, kwargs=kwargs
        ):
            kwargs = _remove_not_given_sentinel_values(kwargs)
            name = "chat_completion_stream"

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_chat",
            }
        )

        tags = ["openai"]

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
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage=result_dict["usage"],
                logger=LOGGER,
                error_message="Failed to log token usage from openai call",
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
        generations_aggregator: Optional[
            Callable[
                [List[chat_completion_chunk.ChatCompletionChunk]],
                chat_completion_chunks_aggregator.ChatCompletionChunksAggregated,
            ]
        ],
    ) -> Union[
        None,
        Iterator[chat_completion_chunk.ChatCompletionChunk],
        AsyncIterator[chat_completion_chunk.ChatCompletionChunk],
    ]:
        assert (
            generations_aggregator is not None
        ), "OpenAI decorator will always get aggregator function as input"

        if isinstance(output, openai.Stream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if isinstance(output, openai.AsyncStream):
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
    Under the hood of `stream()` method openai calls `create(..,stream=True,..)`
    and passes a lot of NOT_GIVEN values that we don't want to track.
    """
    return {
        key: value
        for key, value in dict_.items()
        if value is not _openai_types.NOT_GIVEN
    }


def _is_completions_stream_call(
    name_passed_to_track_decorator: str, kwargs: Dict[str, Any]
) -> bool:
    if not name_passed_to_track_decorator == "chat_completion_create":
        return False

    for _, value in kwargs.items():
        if value is _openai_types.NOT_GIVEN:
            return True

    return False
