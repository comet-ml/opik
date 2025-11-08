import pytest

try:
    import opik.integrations.genai.generate_videos_decorator as decorator
except ModuleNotFoundError:
    pytest.skip("google-genai not installed", allow_module_level=True)


def test_extract_duration_from_span_input() -> None:
    span_input = {"video_config": {"duration_seconds": "12"}}
    assert decorator._extract_duration_seconds(span_input, {}) == 12


def test_extract_duration_from_response() -> None:
    output = {"response": {"duration_seconds": 6}}
    assert decorator._extract_duration_seconds({}, output) == 6


def test_extract_manifest_builds_entries() -> None:
    output = {
        "response": {
            "generated_videos": [
                {"video": {"name": "files/123", "uri": "https://example.com/video.mp4"}}
            ]
        }
    }
    manifest = decorator._extract_video_manifest(output)
    assert manifest and manifest[0]["file_id"] == "files/123"


def test_extract_operation_name_prefers_top_level() -> None:
    output = {"name": "operations/abc"}
    assert decorator._extract_operation_name(output) == "operations/abc"
