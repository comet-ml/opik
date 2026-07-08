import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

from mistralai import ChatCompletionResponse
from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

from . import chat_completion_chunks_aggregator, stream_patchers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages"]


class MistralTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """Tracks ``mistralai`` ``chat.complete``/``chat.stream`` (sync and async)."""

    def __init__(self) -> None:
        super().__init__()
        self.provider = LLMProvider.MISTRALAI.value

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, (
            "Expected kwargs to be not None in chat.complete(**kwargs) / chat.stream(**kwargs)"
        )

        # Named after the wrapped method (complete -> chat_completion_create,
        # stream -> chat_completion_stream). parse()/parse_stream() delegate to
        # those primitives, so they share the same span name; response_format is
        # not used to rename because a direct complete(response_format=...) call
        # is a legitimate structured-output request, not a parse call.
        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "mistral",
                "type": "mistral_chat",
            }
        )

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=["mistral"],
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("model", None),
            provider=self.provider,
        )

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
                ChatCompletionResponse,
                chat_completion_chunks_aggregator.MistralChatCompletionChunksAggregated,
            ),
        )

        result_dict = output.model_dump(mode="json")
        output_dict, metadata = dict_utils.split_dict_by_keys(result_dict, ["choices"])

        opik_usage = None
        if result_dict.get("usage") is not None:
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.MISTRALAI,
                usage=result_dict["usage"],
                logger=LOGGER,
                error_message="Failed to log token usage from mistral call",
            )

        return arguments_helpers.EndSpanParameters(
            output=output_dict,
            usage=opik_usage,
            metadata=metadata,
            model=result_dict.get("model"),
            provider=self.provider,
        )

    @override
    def _streams_handler(  # type: ignore
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        assert generations_aggregator is not None, (
            "Mistral decorator will always get aggregator function as input"
        )

        # Detect the stream by its iterator protocol rather than importing the
        # concrete class from mistralai's internal ``utils.eventstreaming``
        # module. chat.stream() returns a self-iterator (``__next__`` + context
        # manager); chat.stream_async() an async one (``__anext__``). The
        # non-stream ChatCompletionResponse has neither.
        if hasattr(output, "__anext__"):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_event_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if hasattr(output, "__next__"):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_event_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        NOT_A_STREAM = None
        return NOT_A_STREAM
