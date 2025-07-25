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
from typing_extensions import override

from opik import dict_utils, llm_usage
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.types import LLMProvider

from . import stream_patchers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "input",
    "voice",
    "format",
    "model",
    "speed",
    "pitch",
]


class OpenaiSpeechTrackDecorator(base_track_decorator.BaseTrackDecorator):
    def __init__(self) -> None:
        super().__init__()
        self.provider = "openai"

    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Optional[Tuple],
        kwargs: Optional[Dict[str, Any]],
    ) -> arguments_helpers.StartSpanParameters:
        assert (
            kwargs is not None
        ), "Expected kwargs to be not None in speech.create(**kwargs) or speech_stream.create(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__

        metadata = track_options.metadata if track_options.metadata is not None else {}

        input_dict, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)
        metadata.update({"created_from": "openai", "type": "openai_speech"})

        tags = ["openai"]

        result = arguments_helpers.StartSpanParameters(
            name=name,
            input=input_dict,
            type=track_options.type,
            tags=tags,
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("model", None),
            provider=self.provider,
        )
        return result

    def _end_span_inputs_preprocessor(
        self,
        output: Optional[Any],
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        if output is not None and hasattr(output, "model_dump"):
            result_dict = output.model_dump(mode="json")  # type: ignore[arg-type]
        else:
            result_dict = {}

        opik_usage_obj = None
        if result_dict.get("usage") is not None:
            opik_usage_obj = llm_usage.try_build_opik_usage_or_log_error(
                provider=LLMProvider.OPENAI,
                usage=result_dict["usage"],
                logger=LOGGER,
                error_message="Failed to log usage from openai speech call",
            )

        model = result_dict.get("model", None)

        output_data, metadata = dict_utils.split_dict_by_keys(result_dict, [])

        result = arguments_helpers.EndSpanParameters(
            output=output_data if capture_output else None,
            usage=opik_usage_obj,
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
    ) -> Union[
        None,
        Iterator[Any],
        AsyncIterator[Any],
    ]:
        assert (
            generations_aggregator is not None
        ), "OpenAI speech decorator expects aggregator"

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
