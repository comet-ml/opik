import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

from openai import _legacy_response

try:  # OpenAI < 1.43 doesn't expose Video type
    from openai.types import Video as OpenAIVideoType
except Exception:  # pragma: no cover - optional dependency guard
    OpenAIVideoType = None
from typing_extensions import override

import opik.dict_utils as dict_utils
from opik.api_objects import span
from opik.decorator import arguments_helpers, base_track_decorator
from opik.media import video_artifacts
from opik.media import video_utils

LOGGER = logging.getLogger(__name__)

_VIDEO_INPUT_KEYS = ["prompt", "seconds", "size", "input_reference"]


class OpenAIVideoJobTrackDecorator(base_track_decorator.BaseTrackDecorator):
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
        ), "Expected kwargs to be not None in openai.videos.*(**kwargs)"

        name = track_options.name if track_options.name is not None else func.__name__
        metadata = dict(track_options.metadata or {})
        metadata.update({"created_from": "openai", "type": "openai_videos"})

        sanitized_kwargs = dict(kwargs)
        if "input_reference" in sanitized_kwargs:
            sanitized_kwargs["input_reference"] = _describe_input_reference(
                sanitized_kwargs["input_reference"]
            )

        input_payload = {
            key: sanitized_kwargs.get(key)
            for key in _VIDEO_INPUT_KEYS
            if key in sanitized_kwargs
        }

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input_payload,
            type=track_options.type,
            tags=["openai", "video"],
            metadata=metadata,
            project_name=track_options.project_name,
            model=kwargs.get("model"),
            provider=self.provider,
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        result_dict = _video_response_to_dict(output)
        return arguments_helpers.EndSpanParameters(
            output=result_dict,
            metadata={"status": result_dict.get("status")},
            model=result_dict.get("model"),
            provider=self.provider,
            usage=_build_video_usage(result_dict),
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        return None


class OpenAIVideoDownloadTrackDecorator(base_track_decorator.BaseTrackDecorator):
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
        assert kwargs is not None, "Expected kwargs for openai.videos.download_content"
        name = track_options.name if track_options.name is not None else func.__name__
        metadata = dict(track_options.metadata or {})
        variant = kwargs.get("variant", "video")
        metadata.update(
            {
                "created_from": "openai",
                "type": "openai_video_download",
                "variant": variant,
            }
        )
        input_payload = {
            "video_id": kwargs.get("video_id"),
            "variant": variant,
        }

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=input_payload,
            type=track_options.type,
            tags=["openai", "video"],
            metadata=metadata,
            project_name=track_options.project_name,
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
            output, _legacy_response.HttpxBinaryResponseContent
        ), f"Expected HttpxBinaryResponseContent, got {type(output)}"

        payload = output.content or b""
        mime_type = output.response.headers.get("content-type")
        variant = None
        if isinstance(current_span_data.metadata, dict):
            variant = current_span_data.metadata.get("variant")

        collection = video_artifacts.build_collection_from_bytes(
            data=payload,
            provider=self.provider,
            source="openai.videos.download",
            mime_type=mime_type,
            label=None,
            extra_metadata={"variant": variant} if variant else None,
        )

        output_summary = {
            "byte_length": len(payload),
            "variant": variant,
        }

        return arguments_helpers.EndSpanParameters(
            output=output_summary,
            metadata=dict_utils.deepmerge(
                current_span_data.metadata or {},
                {video_artifacts.VIDEO_METADATA_KEY: collection.manifest}
                if collection.manifest
                else {},
            )
            if collection.manifest
            else current_span_data.metadata,
            attachments=collection.attachments if collection.attachments else None,
            provider=self.provider,
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[List[Any]], Any]],
    ) -> Optional[Any]:
        return None


def _describe_input_reference(value: Any) -> str:
    if value is None:
        return "none"
    if isinstance(value, (str, bytes)):
        return "<binary reference>" if isinstance(value, bytes) else value
    if isinstance(value, tuple):
        return f"<tuple ({len(value)})>"
    return f"<{type(value).__name__}>"


def _video_response_to_dict(output: Any) -> Dict[str, Any]:
    if OpenAIVideoType is not None and isinstance(output, OpenAIVideoType):
        return output.model_dump(mode="json")
    if hasattr(output, "model_dump"):
        try:  # pragma: no cover - fallback path
            return output.model_dump(mode="json")
        except Exception:
            pass
    if isinstance(output, dict):
        return output
    return {"raw": str(output)}



def _build_video_usage(result: Dict[str, Any]) -> Optional[Dict[str, int]]:
    duration = video_utils.extract_duration_seconds(None, result)
    if duration is None or duration <= 0:
        return None
    return {"video_duration_seconds": duration}
