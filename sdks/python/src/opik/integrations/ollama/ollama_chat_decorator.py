import inspect
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

        # chat(model, messages) may be called positionally; log it the same way
        kwargs = _bind_call_arguments(func, args, kwargs)

        name = track_options.name if track_options.name is not None else func.__name__
        if kwargs.get("stream") is True:
            name = "chat_stream"

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        if input.get("tools"):
            input["tools"] = _tools_as_schemas(input["tools"])
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

    The ``*_duration`` fields are deliberately left out. Ollama reports them in
    nanoseconds, so any call longer than ~2.1s overflows the backend's 32-bit
    usage values and the whole span batch is rejected. They are still logged in
    the span metadata.
    """
    prompt_tokens = result_dict.get("prompt_eval_count")
    completion_tokens = result_dict.get("eval_count")

    if prompt_tokens is None and completion_tokens is None:
        return None

    usage: Dict[str, Any] = {
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
    }
    for key in ("prompt_eval_count", "eval_count"):
        value = result_dict.get(key)
        if value is not None:
            usage[key] = value

    return llm_usage.build_opik_usage_from_unknown_provider(usage)


def _bind_call_arguments(
    func: Callable, args: Tuple, kwargs: Dict[str, Any]
) -> Dict[str, Any]:
    if not args:
        return kwargs
    try:
        bound = inspect.signature(func).bind_partial(*args, **kwargs)
    except (TypeError, ValueError):
        return kwargs
    arguments = dict(bound.arguments)
    arguments.pop("self", None)
    return arguments


def _tools_as_schemas(tools: Any) -> Any:
    """Log Python-function tools as the JSON schema ollama sends to the model.

    ollama accepts plain functions as tools and converts them itself, so the
    raw kwarg would otherwise be logged as ``<function name at 0x...>``. A tool
    that can't be converted is logged by its name; logging never fails the
    user's call.
    """
    if not isinstance(tools, (list, tuple)):
        return tools

    from ollama._utils import convert_function_to_tool

    schemas = []
    for tool in tools:
        try:
            if callable(tool):
                tool = convert_function_to_tool(tool)
            if hasattr(tool, "model_dump"):
                tool = tool.model_dump(exclude_none=True)
        except Exception:
            LOGGER.debug(
                "Failed to convert ollama tool %r to a schema", tool, exc_info=True
            )
            tool = getattr(tool, "__name__", str(tool))
        schemas.append(tool)
    return schemas
