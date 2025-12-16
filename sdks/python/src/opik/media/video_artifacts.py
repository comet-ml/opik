from __future__ import annotations

import base64
import binascii
import mimetypes
import os
import tempfile
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional, Sequence
from uuid import uuid4

from opik.api_objects.attachment import Attachment

VIDEO_METADATA_KEY = "opik_video_assets"
_VIDEO_URL_SUFFIXES = (
    ".mp4",
    ".mov",
    ".webm",
    ".mkv",
    ".avi",
    ".mpg",
    ".mpeg",
    ".m4v",
    ".gifv",
)


@dataclass
class VideoArtifact:
    provider: Optional[str]
    source: Optional[str]
    mime_type: Optional[str]
    data: Optional[bytes] = None
    url: Optional[str] = None
    label: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_attachment(self) -> Optional[Attachment]:
        if self.data is None:
            return None

        suffix = _extension_from_mime(self.mime_type)
        file_name = self.label or f"{self.provider or 'video'}-{uuid4().hex}{suffix}"
        file_path = _write_temp_file(self.data, suffix)
        return Attachment(
            data=file_path,
            file_name=file_name,
            content_type=self.mime_type,
            delete_after_upload=True,
        )

    def manifest_entry(
        self, attachment: Optional[Attachment]
    ) -> Optional[Dict[str, Any]]:
        has_inline = self.data is not None
        if not has_inline and not self.url:
            return None

        entry: Dict[str, Any] = {
            "provider": self.provider,
            "source": self.source,
            "mime_type": self.mime_type,
            "url": self.url,
            "has_inline_data": has_inline,
            "file_name": attachment.file_name if attachment else self.label,
        }
        entry.update(self.metadata)
        return {key: value for key, value in entry.items() if value is not None}


@dataclass
class VideoArtifactCollection:
    attachments: List[Attachment]
    manifest: List[Dict[str, Any]]

    @classmethod
    def from_artifacts(
        cls, artifacts: List[VideoArtifact]
    ) -> "VideoArtifactCollection":
        attachments: List[Attachment] = []
        manifest: List[Dict[str, Any]] = []

        for artifact in artifacts:
            attachment = artifact.to_attachment()
            if attachment is not None:
                attachments.append(attachment)

            entry = artifact.manifest_entry(attachment)
            if entry:
                manifest.append(entry)

        return cls(attachments=attachments, manifest=manifest)

    def has_entries(self) -> bool:
        return bool(self.attachments or self.manifest)


def collect_video_artifacts(
    payload: Any,
    *,
    provider: Optional[str],
    source: str,
) -> VideoArtifactCollection:
    artifacts: List[VideoArtifact] = []
    _walk_for_artifacts(
        payload=payload,
        artifacts=artifacts,
        provider=provider,
        source=source,
        path=[],
    )
    return VideoArtifactCollection.from_artifacts(artifacts)


def build_collection_from_bytes(
    data: bytes,
    *,
    provider: Optional[str],
    source: str,
    mime_type: Optional[str],
    label: Optional[str] = None,
    extra_metadata: Optional[Dict[str, Any]] = None,
) -> VideoArtifactCollection:
    artifact = VideoArtifact(
        provider=provider,
        source=source,
        mime_type=mime_type,
        data=data,
        label=label,
        metadata=dict(extra_metadata or {}),
    )
    if data is not None:
        artifact.metadata.setdefault("byte_length", len(data))
    return VideoArtifactCollection.from_artifacts([artifact])


def _walk_for_artifacts(
    payload: Any,
    artifacts: List[VideoArtifact],
    provider: Optional[str],
    source: str,
    path: Sequence[str],
) -> None:
    if isinstance(payload, dict):
        skip_keys = set()

        if _looks_like_inline_blob(payload):
            artifact = _artifact_from_inline_blob(
                blob=payload,
                provider=provider,
                source=source,
                path=path,
            )
            if artifact:
                artifacts.append(artifact)

        inline_child = payload.get("inline_data")
        if isinstance(inline_child, dict):
            artifact = _artifact_from_inline_blob(
                blob=inline_child,
                provider=provider,
                source=source,
                path=[*path, "inline_data"],
            )
            if artifact:
                artifacts.append(artifact)
                skip_keys.add("inline_data")

        file_data = payload.get("file_data")
        if isinstance(file_data, dict):
            artifact = _artifact_from_url_blob(
                blob=file_data,
                provider=provider,
                source=source,
                path=[*path, "file_data"],
            )
            if artifact:
                artifacts.append(artifact)
                skip_keys.add("file_data")

        if "video_url" in payload and isinstance(payload["video_url"], dict):
            artifact = _artifact_from_url_blob(
                blob=payload["video_url"],
                provider=provider,
                source=source,
                path=[*path, "video_url"],
            )
            if artifact:
                artifacts.append(artifact)
                skip_keys.add("video_url")

        if "video" in payload and isinstance(payload["video"], dict):
            artifact = _artifact_from_url_blob(
                blob=payload["video"],
                provider=provider,
                source=source,
                path=[*path, "video"],
            )
            if artifact:
                artifacts.append(artifact)
                skip_keys.add("video")

        for key, value in payload.items():
            if key in skip_keys:
                continue
            _walk_for_artifacts(
                payload=value,
                artifacts=artifacts,
                provider=provider,
                source=source,
                path=[*path, str(key)],
            )

    elif isinstance(payload, list):
        for index, item in enumerate(payload):
            _walk_for_artifacts(
                payload=item,
                artifacts=artifacts,
                provider=provider,
                source=source,
                path=[*path, f"[{index}]"],
            )


def _artifact_from_inline_blob(
    blob: Dict[str, Any],
    provider: Optional[str],
    source: str,
    path: Sequence[str],
) -> Optional[VideoArtifact]:
    mime_type = blob.get("mime_type")
    if not _is_video_mime(mime_type):
        return None

    data = _decode_inline_data(blob)
    if data is None:
        return None

    metadata = {
        "path": _stringify_path(path),
        "byte_length": len(data),
    }
    if blob.get("duration") is not None:
        metadata["duration_seconds"] = blob["duration"]

    return VideoArtifact(
        provider=provider,
        source=source,
        mime_type=mime_type,
        data=data,
        label=blob.get("file_name") or blob.get("name"),
        metadata=metadata,
    )


def _artifact_from_url_blob(
    blob: Dict[str, Any],
    provider: Optional[str],
    source: str,
    path: Sequence[str],
) -> Optional[VideoArtifact]:
    mime_type = blob.get("mime_type")
    url = _extract_video_url(blob)

    if not url:
        return None

    if not _is_video_mime(mime_type) and not _looks_like_video_url(url):
        return None

    metadata: Dict[str, Any] = {"path": _stringify_path(path)}
    if blob.get("expires_at") is not None:
        metadata["expires_at"] = blob["expires_at"]
    if blob.get("duration") is not None:
        metadata["duration_seconds"] = blob["duration"]

    return VideoArtifact(
        provider=provider,
        source=source,
        mime_type=mime_type,
        url=url,
        label=blob.get("file_name") or blob.get("name"),
        metadata=metadata,
    )


def _decode_inline_data(blob: Dict[str, Any]) -> Optional[bytes]:
    data_field = None
    if isinstance(blob.get("data"), (str, bytes)):
        data_field = blob["data"]
    elif isinstance(blob.get("b64_json"), str):
        data_field = blob["b64_json"]
    elif isinstance(blob.get("inline_data"), dict):
        inline = blob["inline_data"]
        if isinstance(inline.get("data"), (str, bytes)):
            data_field = inline["data"]

    if data_field is None:
        return None

    if isinstance(data_field, bytes):
        return data_field

    payload = data_field
    if payload.startswith("data:") and ";base64," in payload:
        payload = payload.split(";base64,", 1)[1]

    try:
        return base64.b64decode(payload)
    except (binascii.Error, ValueError):
        return None


def _extract_video_url(blob: Dict[str, Any]) -> Optional[str]:
    for key in ("file_uri", "file_url", "url", "download_url", "uri"):
        value = blob.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return None


def _looks_like_inline_blob(value: Any) -> bool:
    if not isinstance(value, dict):
        return False
    mime_type = value.get("mime_type")
    if not _is_video_mime(mime_type):
        return False

    if isinstance(value.get("data"), (str, bytes)):
        return True
    if isinstance(value.get("b64_json"), str):
        return True
    inline = value.get("inline_data")
    if isinstance(inline, dict) and isinstance(inline.get("data"), (str, bytes)):
        return True

    return False


def _is_video_mime(mime_type: Optional[str]) -> bool:
    if not mime_type:
        return False
    return mime_type.lower().startswith("video/")


def _looks_like_video_url(url: str) -> bool:
    lowered = url.lower()
    return any(lowered.endswith(suffix) for suffix in _VIDEO_URL_SUFFIXES)


def _stringify_path(path: Sequence[str]) -> Optional[str]:
    if not path:
        return None
    return "/".join(path)


def _extension_from_mime(mime_type: Optional[str]) -> str:
    if mime_type:
        extension = mimetypes.guess_extension(mime_type)
        if extension:
            return extension
    return ".mp4"


def _write_temp_file(data: bytes, suffix: str) -> str:
    fd, path = tempfile.mkstemp(prefix="opik-video-", suffix=suffix)
    try:
        with os.fdopen(fd, "wb") as file:
            file.write(data)
    except Exception:
        try:
            os.remove(path)
        except OSError:
            pass
        raise
    return path
