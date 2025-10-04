import logging
import inspect
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

# from openai.types.audio import speech_create_params
from typing_extensions import override

from opik import dict_utils, llm_usage
from opik.api_objects import span
from opik.decorator import (
    arguments_helpers,
    base_track_decorator,
)
from opik.types import LLMProvider
from openai import Stream, AsyncStream
from openai._response import ResponseContextManager

from . import stream_patchers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["input"]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = []


class OpenaiAudioSpeechTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `audio.speech.create` function.
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
        ), "Expected kwargs to be not None in audio.speech.create(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        metadata = track_options.metadata if track_options.metadata is not None else {}

        input, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_audio_speech",
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
        opik_usage = None
        if current_span_data.input and current_span_data.input.get("input"):
            opik_usage = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage={"total_tokens": len(current_span_data.input["input"])},
                logger=LOGGER,
                error_message="Failed to log token usage from openai call",
            )
        result = arguments_helpers.EndSpanParameters(
            output={},
            usage=opik_usage,
            metadata={},
            model=current_span_data.model,
            provider=self.provider,
        )
        return result

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], str]],
    ) -> Optional[Union[Iterator, AsyncIterator]]:
        if not capture_output:
            return output

        if isinstance(output, (Stream, ResponseContextManager)):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_sync_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )
        if isinstance(output, AsyncStream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()
            return stream_patchers.patch_async_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=self._after_call,
            )

        return None

    async def _acall_and_repack(self, func: Callable, *args: Any, **kwargs: Any) -> Any:
        if inspect.iscoroutinefunction(func):
            result = await func(*args, **kwargs)
        else:
            result = func(*args, **kwargs)
        return self._handle_response(result=result, **kwargs)

    def _handle_response(self, result: Any, **kwargs: Any) -> Any:
        is_stream_response = self._is_stream_response(result)
        is_stream_manager_response = self._is_stream_manager_response(result)
