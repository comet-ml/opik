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
from . import speech_stream_buffer
from opik.api_objects import opik_client as opik_client_module
from opik.api_objects import attachment as attachment_module
from opik.api_objects import span as span_module
from opik.api_objects import trace as trace_module
import os
from pathlib import Path

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

    @staticmethod
    def _extract_bytes(item: Any) -> Optional[bytes]:
        if isinstance(item, (bytes, bytearray)):
            return bytes(item)
        for attr in ("audio_chunk", "data"):
            candidate = getattr(item, attr, None)
            if isinstance(candidate, (bytes, bytearray)):
                return bytes(candidate)
        return None

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
        generations_aggregator: Callable[[List[Any]], None],
    ) -> Union[
        None,
        Iterator[Any],
        AsyncIterator[Any],
    ]:
        if isinstance(output, openai.Stream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()

            max_size_env = os.getenv("OPIK_SPEECH_STREAM_MAX_BYTES")
            max_size_bytes = (
                int(max_size_env) if max_size_env and max_size_env.isdigit() else None
            )
            buffer = speech_stream_buffer.SpeechStreamBuffer(max_size=max_size_bytes)

            def _chunk_processor(item: Any) -> None:
                data = self._extract_bytes(item)
                if data is not None:
                    buffer.add(data)

            def _finally_callback(
                output: Any,
                error_info: Any,
                capture_output: bool,
                generators_span_to_end: span_module.SpanData,
                generators_trace_to_end: Optional[trace_module.TraceData],
                flush: bool = False,
            ) -> None:
                self._after_call(
                    output=output,
                    error_info=error_info,
                    capture_output=capture_output,
                    generators_span_to_end=generators_span_to_end,
                    generators_trace_to_end=generators_trace_to_end,
                    flush=flush,
                )

                if error_info is None and buffer.should_attach():
                    path: Optional[Path] = buffer.flush_to_tempfile(".mp3")
                    if path is not None:
                        client = opik_client_module.get_client_cached()
                        attach_obj = attachment_module.Attachment(data=str(path))
                        client.update_span(
                            id=generators_span_to_end.id,
                            trace_id=generators_span_to_end.trace_id,
                            parent_span_id=generators_span_to_end.parent_span_id,
                            project_name=generators_span_to_end.project_name or "",
                            attachments=[attach_obj],
                        )
                        try:
                            path.unlink()
                        except Exception:
                            pass

            return stream_patchers.patch_sync_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=_finally_callback,
                chunk_processor=_chunk_processor,
            )

        if isinstance(output, openai.AsyncStream):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()

            max_size_env = os.getenv("OPIK_SPEECH_STREAM_MAX_BYTES")
            max_size_bytes = (
                int(max_size_env) if max_size_env and max_size_env.isdigit() else None
            )
            buffer = speech_stream_buffer.SpeechStreamBuffer(max_size=max_size_bytes)

            def _chunk_processor(item: Any) -> None:
                data = self._extract_bytes(item)
                if data is not None:
                    buffer.add(data)

            def _finally_callback_async(
                output: Any,
                error_info: Any,
                capture_output: bool,
                generators_span_to_end: span_module.SpanData,
                generators_trace_to_end: Optional[trace_module.TraceData],
                flush: bool = False,
            ) -> None:
                self._after_call(
                    output=output,
                    error_info=error_info,
                    capture_output=capture_output,
                    generators_span_to_end=generators_span_to_end,
                    generators_trace_to_end=generators_trace_to_end,
                    flush=flush,
                )

                if error_info is None and buffer.should_attach():
                    path: Optional[Path] = buffer.flush_to_tempfile(".mp3")
                    if path is not None:
                        client = opik_client_module.get_client_cached()
                        attach_obj = attachment_module.Attachment(data=str(path))
                        client.update_span(
                            id=generators_span_to_end.id,
                            trace_id=generators_span_to_end.trace_id,
                            parent_span_id=generators_span_to_end.parent_span_id,
                            project_name=generators_span_to_end.project_name or "",
                            attachments=[attach_obj],
                        )
                        try:
                            path.unlink()
                        except Exception:
                            pass

            return stream_patchers.patch_async_stream(
                stream=output,
                span_to_end=span_to_end,
                trace_to_end=trace_to_end,
                generations_aggregator=generations_aggregator,
                finally_callback=_finally_callback_async,
                chunk_processor=_chunk_processor,
            )

        NOT_A_STREAM = None
        return NOT_A_STREAM
