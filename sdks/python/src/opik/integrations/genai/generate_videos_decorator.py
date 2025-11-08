import logging
import os
import tempfile
from typing import Any, Callable, Dict, List, Optional, Tuple
from typing_extensions import override

import opik.dict_utils as dict_utils
from opik.api_objects import span, attachment
from opik.decorator import arguments_helpers, base_track_decorator
from opik.media import video_artifacts, video_utils

LOGGER = logging.getLogger(__name__)

KWARGS_KEYS_TO_LOG_AS_INPUTS = [
    "prompt",
    "video_config",
    "config",
    "tools",
]
RESPONSE_KEYS_TO_LOG_AS_OUTPUT = [
    "operation",
    "response",
    "generated_videos",
]


class GenerateVideosTrackDecorator(base_track_decorator.BaseTrackDecorator):
    """
    Tracks calls to google.genai Client models.generate_videos / aio equivalent.
    """

    def __init__(self, provider: str) -> None:
        super().__init__()
        self.provider = provider

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        assert kwargs is not None, "generate_videos expected kwargs"

        name = track_options.name if track_options.name is not None else func.__name__
        metadata = dict(track_options.metadata or {})
        metadata["created_from"] = "genai"
        metadata["type"] = "genai_generate_videos"

        inputs, new_metadata = dict_utils.split_dict_by_keys(
            kwargs, keys=KWARGS_KEYS_TO_LOG_AS_INPUTS
        )
        metadata = dict_utils.deepmerge(metadata, new_metadata)

        return arguments_helpers.StartSpanParameters(
            name=name,
            input=inputs,
            type=track_options.type,
            tags=["genai", "video"],
            metadata=metadata,
            project_name=track_options.project_name,
            provider=self.provider,
            model=kwargs.get("model"),
        )

    @override
    def _end_span_inputs_preprocessor(
        self,
        output: Any,
        capture_output: bool,
        current_span_data: span.SpanData,
    ) -> arguments_helpers.EndSpanParameters:
        output_dict = _serialize_response(output)
        result, metadata = dict_utils.split_dict_by_keys(
            output_dict, RESPONSE_KEYS_TO_LOG_AS_OUTPUT
        )
        metadata = metadata or {}
        metadata.setdefault("provider", self.provider)

        operation_name = _extract_operation_name(output_dict)
        if operation_name:
            metadata["genai_operation"] = operation_name

        manifest = _extract_video_manifest(output_dict)
        if manifest:
            metadata[video_artifacts.VIDEO_METADATA_KEY] = manifest
        attachments_collection = _build_attachment_collection(output_dict)
        attachments = (
            attachments_collection.attachments if attachments_collection else None
        )
        if (
            attachments_collection
            and attachments_collection.manifest
            and video_artifacts.VIDEO_METADATA_KEY not in metadata
        ):
            metadata[video_artifacts.VIDEO_METADATA_KEY] = (
                attachments_collection.manifest
            )

        duration_seconds = video_utils.extract_duration_seconds(
            current_span_data.input, output_dict
        )
        usage: Optional[Dict[str, int]] = None
        if duration_seconds is not None and duration_seconds > 0:
            usage = {"video_duration_seconds": duration_seconds}

        return arguments_helpers.EndSpanParameters(
            output=result,
            metadata=metadata,
            provider=self.provider,
            model=current_span_data.model or output_dict.get("model"),
            usage=usage,
            attachments=attachments,
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[Any], Any]],
    ) -> Optional[Any]:
        return None


def _serialize_response(output: Any) -> Dict[str, Any]:
    if output is None:
        return {}

    if hasattr(output, "model_dump"):
        try:
            return output.model_dump(mode="json")  # type: ignore[call-arg]
        except Exception:
            LOGGER.debug("Failed to model_dump genai video response", exc_info=True)

    if hasattr(output, "__dict__"):
        try:
            return dict(output.__dict__)
        except Exception:
            LOGGER.debug(
                "Failed to convert genai video response to dict", exc_info=True
            )

    if isinstance(output, dict):
        return output

    return {"value": str(output)}


def _extract_operation_name(output: Dict[str, Any]) -> Optional[str]:
    if isinstance(output.get("operation"), dict):
        operation = output["operation"]
    else:
        operation = None

    if "name" in output and not operation:
        return output["name"]

    if isinstance(operation, dict):
        return operation.get("name")
    return None


def _extract_video_manifest(output: Dict[str, Any]) -> Optional[List[Dict[str, Any]]]:
    response = output.get("response") or {}
    videos = response.get("generated_videos") or output.get("generated_videos")
    if not isinstance(videos, list):
        return None

    manifest: List[Dict[str, Any]] = []
    for video_obj in videos:
        if not isinstance(video_obj, dict):
            continue
        video_data = video_obj.get("video")
        if not isinstance(video_data, dict):
            continue
        entry = {
            "file_id": video_data.get("name"),
            "uri": video_data.get("uri"),
            "mime_type": video_data.get("mime_type"),
            "size_bytes": video_data.get("size_bytes"),
        }
        manifest.append({k: v for k, v in entry.items() if v is not None})

    return manifest or None


def _build_attachment_collection(
    output: Dict[str, Any],
) -> Optional[video_artifacts.VideoArtifactCollection]:
    manifest = _extract_video_manifest(output)
    if not manifest:
        return None

    attachments = []
    for entry in manifest:
        uri = entry.get("uri")
        if not uri:
            continue
        attachments.append(
            video_artifacts.VideoArtifact(
                provider="google.genai",
                source="genai.operations.get",
                mime_type=entry.get("mime_type"),
                url=uri,
                metadata={"file_id": entry.get("file_id")},
            )
        )

    return video_artifacts.VideoArtifactCollection.from_artifacts(attachments)


class GenerateVideosOperationTracker(base_track_decorator.BaseTrackDecorator):
    """
    Tracks operations.get to enrich spans when a video job completes.
    """

    def __init__(
        self,
        provider: str,
        client: Any,
        download_video_attachments: bool,
    ) -> None:
        super().__init__()
        self.provider = provider
        self._client = client
        self._download_video_attachments = download_video_attachments

    @override
    def _start_span_inputs_preprocessor(
        self,
        func: Callable,
        track_options: arguments_helpers.TrackOptions,
        args: Tuple,
        kwargs: Dict[str, Any],
    ) -> arguments_helpers.StartSpanParameters:
        return arguments_helpers.StartSpanParameters(
            name="operations_get",
            type=track_options.type,
            tags=["genai", "operation"],
            metadata={"created_from": "genai", "type": "genai_operation_get"},
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
        output_dict = _serialize_response(output)
        collection = _build_attachment_collection(output_dict)
        manifest = collection.manifest if collection else None

        metadata: Dict[str, Any] = {}
        if manifest:
            metadata[video_artifacts.VIDEO_METADATA_KEY] = manifest
        operation_name = _extract_operation_name(output_dict)
        if operation_name:
            metadata["genai_operation"] = operation_name

        duration_seconds = video_utils.extract_duration_seconds(
            current_span_data.input, output_dict
        )
        usage = (
            {"video_duration_seconds": duration_seconds}
            if duration_seconds and duration_seconds > 0
            else None
        )

        attachments: Optional[List[attachment.Attachment]] = None
        download_errors: Optional[List[Dict[str, Any]]] = None
        if (
            self._download_video_attachments
            and output_dict.get("done") is True
            and manifest
        ):
            attachments, download_errors = self._download_video_files(manifest)
            if download_errors:
                metadata["genai_video_download_errors"] = download_errors

        return arguments_helpers.EndSpanParameters(
            metadata=metadata or None,
            attachments=attachments,
            usage=usage,
            provider=self.provider,
        )

    @override
    def _streams_handler(
        self,
        output: Any,
        capture_output: bool,
        generations_aggregator: Optional[Callable[[Any], Any]],
    ) -> Optional[Any]:
        return None

    def _download_video_files(
        self, manifest: List[Dict[str, Any]]
    ) -> Tuple[Optional[List[attachment.Attachment]], Optional[List[Dict[str, Any]]]]:
        attachments: List[attachment.Attachment] = []
        errors: List[Dict[str, Any]] = []
        for entry in manifest:
            file_id = entry.get("file_id")
            if not isinstance(file_id, str):
                continue
            mime_type = entry.get("mime_type") or "video/mp4"
            downloaded, error = self._download_single_file(file_id, mime_type)
            if downloaded is not None:
                attachments.append(downloaded)
            elif error is not None:
                errors.append(error)
        return attachments or None, errors or None

    def _download_single_file(
        self, file_id: str, mime_type: str
    ) -> Tuple[Optional[attachment.Attachment], Optional[Dict[str, Any]]]:
        try:
            response = self._try_download(file_id)
            if response is None:
                return None, self._record_download_error(file_id, "empty_response")

            temp_path = self._persist_download(response)
            if temp_path is None:
                return None, self._record_download_error(file_id, "persist_failed")

            file_name = os.path.basename(file_id) or "video.mp4"
            return (
                attachment.Attachment(
                    data=temp_path,
                    file_name=file_name,
                    content_type=mime_type,
                    delete_after_upload=True,
                ),
                None,
            )
        except Exception as exc:
            return None, self._record_download_error(file_id, str(exc))

    def _try_download(self, file_id: str) -> Optional[Any]:
        try:
            response = self._client.files.download(name=file_id)
        except TypeError:
            try:
                response = self._client.files.download(file={"name": file_id})
            except Exception:
                LOGGER.debug("Fallback download failed for %s", file_id, exc_info=True)
                return None
        except Exception:
            LOGGER.debug("Download failed for %s", file_id, exc_info=True)
            return None

        self._raise_for_status(response)
        return response

    def _raise_for_status(self, response: Any) -> None:
        status_code = getattr(response, "status_code", None)
        if status_code is None:
            return
        try:
            status_code = int(status_code)
        except Exception:
            return
        if 200 <= status_code < 300:
            return
        body = getattr(response, "text", None) or getattr(response, "content", None)
        raise RuntimeError(f"HTTP {status_code}: {body}")

    def _persist_download(self, response: Any) -> Optional[str]:
        fd, temp_path = tempfile.mkstemp(prefix="opik-genai-video-", suffix=".mp4")
        try:
            os.close(fd)
        except OSError:
            pass
        try:
            if hasattr(response, "save"):
                response.save(temp_path)  # type: ignore[attr-defined]
                return temp_path

            data = None
            if hasattr(response, "read"):
                data = response.read()
            elif hasattr(response, "content"):
                data = response.content
            elif hasattr(response, "body"):
                data = response.body

            if data is None:
                raise RuntimeError("Download response body is empty")

            with open(temp_path, "wb") as fp:
                fp.write(data)
            return temp_path
        except Exception:
            LOGGER.debug("Failed to persist downloaded video", exc_info=True)
            try:
                os.remove(temp_path)
            except OSError:
                pass
            return None

    def _record_download_error(self, file_id: str, reason: str) -> Dict[str, Any]:
        message = f"⚠️  Google GenAI video download failed for {file_id}: {reason}"
        LOGGER.warning(message)
        return {"file_id": file_id, "reason": reason}
