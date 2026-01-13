import os
import tempfile

import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from .constants import VIDEO_MODEL_FOR_TESTS, VIDEO_SIZE_FOR_TESTS
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


@pytest.fixture(autouse=True)
def check_openai_configured(ensure_openai_configured):
    pass


def test_openai_client_videos_create_and_poll_and_download__happyflow(fake_backend):
    """
    Test videos.create_and_poll and download_content - the main video generation workflow.

    This test verifies:
    1. Trace and span structure with proper nesting
    2. Input/output logging for all video methods
    3. Metadata contains video_seconds and video_size for cost calculation
    4. Model and provider are correctly populated
    5. Tags are applied correctly
    6. Download and write_to_file spans are created
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    prompt = "A serene mountain landscape at sunset"

    video = wrapped_client.videos.create_and_poll(
        model=VIDEO_MODEL_FOR_TESTS,
        prompt=prompt,
        seconds="4",
        size=VIDEO_SIZE_FOR_TESTS,
    )

    # Assume video generation succeeds
    assert video.status == "completed", f"Video generation failed: {video.error}"

    with tempfile.TemporaryDirectory() as temp_dir:
        output_path = os.path.join(temp_dir, "test_video.mp4")
        content = wrapped_client.videos.download_content(video_id=video.id)
        content.write_to_file(output_path)

        # Verify file was created
        assert os.path.exists(output_path)

    opik.flush_tracker()

    # Three traces: create_and_poll, download_content, write_to_file
    assert len(fake_backend.trace_trees) == 3

    EXPECTED_CREATE_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="videos_create_and_poll",
        input={"prompt": prompt, "seconds": "4", "size": VIDEO_SIZE_FOR_TESTS},
        output={
            "id": ANY_BUT_NONE,
            "status": "completed",
            "prompt": prompt,
            "seconds": "4",
            "size": VIDEO_SIZE_FOR_TESTS,
            "progress": ANY,
            "error": ANY,
        },
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="videos_create_and_poll",
                input={"prompt": prompt, "seconds": "4", "size": VIDEO_SIZE_FOR_TESTS},
                output={
                    "id": ANY_BUT_NONE,
                    "status": ANY_STRING,
                    "prompt": prompt,
                    "seconds": "4",
                    "size": VIDEO_SIZE_FOR_TESTS,
                    "progress": ANY,
                    "error": ANY,
                },
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_videos",
                        "video_seconds": 4,
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model=VIDEO_MODEL_FOR_TESTS,
                provider="openai",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="videos_create",
                        input={
                            "prompt": prompt,
                            "seconds": "4",
                            "size": VIDEO_SIZE_FOR_TESTS,
                        },
                        output={
                            "id": ANY_BUT_NONE,
                            "status": ANY_STRING,
                            "prompt": prompt,
                            "seconds": "4",
                            "size": VIDEO_SIZE_FOR_TESTS,
                            "progress": ANY,
                            "error": ANY,
                        },
                        tags=["openai"],
                        metadata=ANY_DICT.containing(
                            {
                                "created_from": "openai",
                                "type": "openai_videos",
                                "video_seconds": 4,
                            }
                        ),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=OPIK_PROJECT_DEFAULT_NAME,
                        model=VIDEO_MODEL_FOR_TESTS,
                        provider="openai",
                        spans=[],
                    ),
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="general",
                        name="videos_poll",
                        input=ANY_DICT,
                        output=ANY_DICT,
                        tags=["openai"],
                        metadata=ANY_DICT.containing(
                            {
                                "created_from": "openai",
                                "type": "openai_videos",
                            }
                        ),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=OPIK_PROJECT_DEFAULT_NAME,
                        model=ANY,
                        provider="openai",
                        spans=[],
                    ),
                ],
            )
        ],
    )

    EXPECTED_DOWNLOAD_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="videos_download_content",
        input={"video_id": video.id},
        output={
            "message": "Video content ready for download",
            "content_type": "video/mp4",
        },
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="videos_download_content",
                input={"video_id": video.id},
                output={
                    "message": "Video content ready for download",
                    "content_type": "video/mp4",
                },
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_videos",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model=None,
                provider="openai",
                spans=[],
            )
        ],
    )

    EXPECTED_WRITE_TO_FILE_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="videos_write_to_file",
        input={"file": ANY_BUT_NONE},
        output=None,
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="videos_write_to_file",
                input={"file": ANY_BUT_NONE},
                output=None,
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_videos",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model=None,
                provider="openai",
                spans=[],
            )
        ],
    )

    # Find traces by name
    create_trace = next(
        t for t in fake_backend.trace_trees if t.name == "videos_create_and_poll"
    )
    download_trace = next(
        t for t in fake_backend.trace_trees if t.name == "videos_download_content"
    )
    write_to_file_trace = next(
        t for t in fake_backend.trace_trees if t.name == "videos_write_to_file"
    )

    assert_equal(EXPECTED_CREATE_TRACE, create_trace)
    assert_equal(EXPECTED_DOWNLOAD_TRACE, download_trace)
    assert_equal(EXPECTED_WRITE_TO_FILE_TRACE, write_to_file_trace)


def test_openai_client_videos_create_and_poll__error_handling(fake_backend):
    """
    Test error handling when video creation fails with invalid model.

    This is a fast test (no actual video generation) that verifies:
    1. Error info is logged on both parent and nested spans
    2. Trace and spans are finished gracefully despite the error
    3. Nested structure is preserved even on error
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    prompt = "Test video"

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.videos.create_and_poll(
            model="invalid-model-name",
            prompt=prompt,
            seconds="4",
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="videos_create_and_poll",
        input=ANY_DICT.containing({"prompt": prompt, "seconds": "4"}),
        output=None,
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        error_info={
            "exception_type": "BadRequestError",
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="videos_create_and_poll",
                input=ANY_DICT.containing({"prompt": prompt, "seconds": "4"}),
                output=None,
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_videos",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model="invalid-model-name",
                provider="openai",
                error_info={
                    "exception_type": "BadRequestError",
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="videos_create",
                        input=ANY_DICT.containing({"prompt": prompt, "seconds": "4"}),
                        output=None,
                        tags=["openai"],
                        metadata=ANY_DICT.containing(
                            {
                                "created_from": "openai",
                                "type": "openai_videos",
                            }
                        ),
                        usage=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=OPIK_PROJECT_DEFAULT_NAME,
                        model="invalid-model-name",
                        provider="openai",
                        error_info={
                            "exception_type": "BadRequestError",
                            "message": ANY_STRING,
                            "traceback": ANY_STRING,
                        },
                        spans=[],
                    ),
                ],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
