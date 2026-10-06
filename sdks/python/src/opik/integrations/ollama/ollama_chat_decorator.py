import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

from ollama._types import ChatResponse
from typing_extensions import override

import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator

from . import stream_wrappers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["messages", "tools"]


class OllamaChatTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """Tracks calls to ollama's ``Client.chat``, including ``stream=True``."""

    def __init__(self) -> None:
        super().__init__()
        self.provider = "ollama"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, "Expected kwargs to be not None in chat(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        if kwargs.get("stream") is True:
            name = "chat_stream"

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update({"created_from": "ollama", "type": "ollama_chat"})

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input,
            type=track_options.type,
            tags=["ollama"],
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
        assert isinstance(output, ChatResponse)

        result_dict = output.model_dump(mode="json")
        output_dict, metadata = dict_utils.split_dict_by_keys(result_dict, ["message"])

        return arguments_helpers.EndSpanParameters(
            output=output_dict,
            usage=_build_usage(result_dict),
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
            "Ollama decorator will always get aggregator function as input"
        )

        # chat(stream=True) returns a generator, not a stream object, so detect
        # it by the iterator protocol. A non-streamed call returns a
        # ChatResponse, which has neither __next__ nor __anext__.
        if hasattr(output, "__anext__"):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_wrappers.wrap_async_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        if hasattr(output, "__next__"):
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


def _build_usage(result_dict: Dict[str, Any]) -> Optional[llm_usage.OpikUsage]:
    """Map ollama's token counters onto Opik's usage shape.

    Ollama reports ``prompt_eval_count`` / ``eval_count`` rather than an
    OpenAI-style ``usage`` object, so there is no provider builder to reuse. The
    native counters are passed through as well, so they survive under
    ``original_usage.*`` rather than being dropped.
    """
    prompt_tokens = result_dict.get("prompt_eval_count")
    completion_tokens = result_dict.get("eval_count")

    if prompt_tokens is None and completion_tokens is None:
        return None

    usage: Dict[str, Any] = {
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
    }
    for key in (
        "prompt_eval_count",
        "eval_count",
        "prompt_eval_duration",
        "eval_duration",
        "total_duration",
        "load_duration",
    ):
        value = result_dict.get(key)
        if value is not None:
            usage[key] = value

    return llm_usage.build_opik_usage_from_unknown_provider(usage)
