import logging

from typing import (
    Any,
    Callable,
    Dict,
    List,
    Optional,
    Tuple,
    Iterator,
)
from typing_extensions import override


from opik.types import LLMProvider
import opik.dict_utils as dict_utils
import opik.llm_usage as llm_usage
from opik.api_objects import span, attachment
from opik.decorator import arguments_helpers, base_track_decorator, generator_wrappers

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = ["input", "voice", "response_format", "speed"]


class OpenaiAudioSpeechTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    An implementation of BaseTrackDecorator designed specifically for tracking
    calls of OpenAI's `audio.speech.create` and `audio.speech.with_streaming_response.create` functions.
    """

    def __init__(self) -> None:
        super().__init__()
        self.provider = "openai"

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
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
        # Calculate usage based on input text length (character count)
        input_text = (
            current_span_data.input.get("input", "") if current_span_data.input else ""
        )
        char_count = len(str(input_text))

        usage = {
            "prompt_tokens": char_count,
            "completion_tokens": 0,
            "total_tokens": char_count,
        }

        opik_usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=LLMProvider.OPENAI,
            usage=usage,
            logger=LOGGER,
            error_message="Failed to log usage from openai audio speech call",
        )

        attachments = None
        # If output is the binary content (HttpxBinaryResponseContent)
        # We can try to access .content if available
        if hasattr(output, "content"):
            try:
                audio_content = output.content
                if isinstance(audio_content, bytes):
                    attachments = [
                        attachment.Attachment(
                            data=audio_content,
                            name="audio_response",
                            # We could try to guess mime type from response format, but default to octet-stream works or infer from metadata
                            type="audio/mpeg",  # Default for TTS, but could be opus/aac/flac depending on response_format
                        )
                    ]
            except Exception as e:
                LOGGER.warning("Failed to extract audio content for attachment: %s", e)

        # If output is bytes (from our stream aggregator)
        elif isinstance(output, bytes):
            attachments = [
                attachment.Attachment(
                    data=output, name="audio_response", type="audio/mpeg"
                )
            ]

        return arguments_helpers.EndSpanParameters(
            output=None,  # Don't log binary audio as output JSON
            usage=opik_usage,
            metadata=None,
            model=current_span_data.model,
            provider=self.provider,
            attachments=attachments,
        )

    @override
    def _streams_handler(  # type: ignore
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        # Handle context manager returned by with_streaming_response.create

        # Sync context manager
        if hasattr(output, "__enter__") and hasattr(output, "__exit__"):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()

            original_enter = output.__enter__

            def enter_wrapper(*args: Any, **kwargs: Any) -> Any:
                stream = original_enter(*args, **kwargs)
                return patch_binary_stream(
                    stream=stream,
                    span_to_end=span_to_end,
                    trace_to_end=trace_to_end,
                    finally_callback=self._after_call,
                )

            output.__enter__ = enter_wrapper
            return output

        # Async context manager
        if hasattr(output, "__aenter__") and hasattr(output, "__aexit__"):
            span_to_end, trace_to_end = base_track_decorator.pop_end_candidates()

            original_aenter = output.__aenter__

            async def aenter_wrapper(*args: Any, **kwargs: Any) -> Any:
                stream = await original_aenter(*args, **kwargs)
                return patch_async_binary_stream(
                    stream=stream,
                    span_to_end=span_to_end,
                    trace_to_end=trace_to_end,
                    finally_callback=self._after_call,
                )

            output.__aenter__ = aenter_wrapper
            return output

        return None


def patch_binary_stream(
    stream: Any,  # StreamedBinaryAPIResponse
    span_to_end: span.SpanData,
    trace_to_end: Optional[span.SpanData],  # Actually TraceData
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Any:
    """
    Wraps StreamedBinaryAPIResponse to capture audio bytes.
    """
    if not hasattr(stream, "iter_bytes"):
        return stream

    original_iter_bytes = stream.iter_bytes

    def iter_bytes_wrapper(chunk_size: Optional[int] = None) -> Iterator[bytes]:
        accumulated_bytes = bytearray()
        error_info = None

        try:
            for chunk in original_iter_bytes(chunk_size=chunk_size):
                accumulated_bytes.extend(chunk)
                yield chunk
        except Exception as e:
            error_info = base_track_decorator.error_info_collector.collect(e)
            raise e
        finally:
            output = bytes(accumulated_bytes) if error_info is None else None
            finally_callback(
                output=output,
                error_info=error_info,
                capture_output=True,
                generators_span_to_end=span_to_end,
                generators_trace_to_end=trace_to_end,
            )

    stream.iter_bytes = iter_bytes_wrapper

    return stream


def patch_async_binary_stream(
    stream: Any,  # AsyncStreamedBinaryAPIResponse
    span_to_end: span.SpanData,
    trace_to_end: Optional[span.SpanData],  # Actually TraceData
    finally_callback: generator_wrappers.FinishGeneratorCallback,
) -> Any:
    """
    Wraps AsyncStreamedBinaryAPIResponse to capture audio bytes.
    """
    if not hasattr(stream, "aiter_bytes"):
        return stream

    original_aiter_bytes = stream.aiter_bytes

    async def aiter_bytes_wrapper(chunk_size: Optional[int] = None) -> Any:
        accumulated_bytes = bytearray()
        error_info = None

        try:
            async for chunk in original_aiter_bytes(chunk_size=chunk_size):
                accumulated_bytes.extend(chunk)
                yield chunk
        except Exception as e:
            error_info = base_track_decorator.error_info_collector.collect(e)
            raise e
        finally:
            output = bytes(accumulated_bytes) if error_info is None else None
            finally_callback(
                output=output,
                error_info=error_info,
                capture_output=True,
                generators_span_to_end=span_to_end,
                generators_trace_to_end=trace_to_end,
            )

    stream.aiter_bytes = aiter_bytes_wrapper

    return stream
