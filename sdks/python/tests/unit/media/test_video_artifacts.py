import base64
import os
from pathlib import Path

from opik.media import video_artifacts


def _cleanup_attachments(collection: video_artifacts.VideoArtifactCollection) -> None:
    for attachment in collection.attachments:
        if attachment.data and Path(attachment.data).exists():
            os.remove(attachment.data)


def test_collect_video_artifacts_inline_data():
    payload = {
        "candidates": [
            {
                "content": {
                    "parts": [
                        {
                            "inline_data": {
                                "mime_type": "video/mp4",
                                "data": base64.b64encode(b"demo-bytes").decode(),
                            }
                        }
                    ]
                }
            }
        ]
    }

    collection = video_artifacts.collect_video_artifacts(
        payload,
        provider="test-provider",
        source="unit-test",
    )

    try:
        assert len(collection.attachments) == 1
        attachment = collection.attachments[0]
        assert attachment.content_type == "video/mp4"
        assert attachment.delete_after_upload is True
        assert Path(attachment.data).exists()

        assert collection.manifest
        assert collection.manifest[0]["has_inline_data"] is True
        assert collection.manifest[0]["provider"] == "test-provider"
    finally:
        _cleanup_attachments(collection)


def test_collect_video_artifacts_video_url_only():
    payload = {
        "video_url": {
            "url": "https://example.com/demo.mp4",
            "mime_type": "video/mp4",
            "duration": 6,
        }
    }

    collection = video_artifacts.collect_video_artifacts(
        payload,
        provider="demo",
        source="unit-test",
    )

    assert collection.attachments == []
    assert collection.manifest
    assert collection.manifest[0]["url"].endswith(".mp4")
    assert collection.manifest[0]["duration_seconds"] == 6


def test_build_collection_from_bytes():
    collection = video_artifacts.build_collection_from_bytes(
        data=b"\x00\x01\x02",
        provider="openai",
        source="download",
        mime_type="video/mp4",
        extra_metadata={"variant": "video"},
    )

    try:
        assert len(collection.attachments) == 1
        attachment = collection.attachments[0]
        assert attachment.content_type == "video/mp4"
        assert Path(attachment.data).exists()

        assert collection.manifest
        assert collection.manifest[0]["byte_length"] == 3
        assert collection.manifest[0]["variant"] == "video"
    finally:
        _cleanup_attachments(collection)
