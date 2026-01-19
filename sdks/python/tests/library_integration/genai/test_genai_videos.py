"""
Tests for Google GenAI Veo video generation integration with Opik.

These tests verify that video generation calls are properly tracked,
including the full workflow: create -> wait -> save with attachment.

Note: Veo models require us-central1 region.
"""

import os
import tempfile
import time

import pytest
import google.genai as genai
from google.genai.types import HttpOptions, GenerateVideosConfig
from google.genai import errors as genai_errors
import opik
from opik.integrations.genai import track_genai

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    AttachmentModel,
    SpanModel,
    TraceModel,
    assert_equal,
    patch_environ,
)
import tenacity

pytestmark = [
    pytest.mark.usefixtures("ensure_vertexai_configured"),
    pytest.mark.usefixtures("use_us_central1_for_veo"),
]

VIDEO_MODEL = "veo-3.1-fast-generate-preview"

VIDEO_CONFIG = GenerateVideosConfig(
    duration_seconds=4,
    resolution="720p",
    generate_audio=False,
    number_of_videos=1,
)

SKIP_EXPENSIVE_TESTS = os.environ.get("OPIK_TEST_EXPENSIVE", "").lower() not in (
    "1",
    "true",
    "yes",
)


@pytest.fixture(autouse=False)
def use_us_central1_for_veo():
    """Veo models are only available in us-central1 region."""
    with patch_environ(add_keys={"GOOGLE_CLOUD_LOCATION": "us-central1"}):
        yield


def _is_rate_limit_error(exception: Exception) -> bool:
    if isinstance(exception, genai_errors.ClientError):
        return exception.response.status_code == 429
    return False


retry_on_rate_limit = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_incrementing(start=5, increment=5),
    retry=tenacity.retry_if_exception(_is_rate_limit_error),
)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@retry_on_rate_limit
def test_genai_client__generate_videos_and_save__sync__happyflow(fake_backend):
    """
    Test sync video generation workflow: create -> wait -> save.

    This test verifies:
    1. videos.generate span is created with correct input/output
    2. videos.save span is created when saving the video
    3. Video attachment is logged with correct metadata
    4. Model and provider are correctly populated
    """
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client, project_name="genai-video-test")

    prompt = "A blue sphere floating in space"

    # 1. Create video
    operation = client.models.generate_videos(
        model=VIDEO_MODEL,
        prompt=prompt,
        config=VIDEO_CONFIG,
    )

    # 2. Wait for completion
    max_wait_time = 300  # 5 minutes
    start_time = time.time()
    while not operation.done:
        if time.time() - start_time > max_wait_time:
            pytest.fail("Video generation timed out")
        time.sleep(10)
        operation = client.operations.get(operation)

    assert operation.error is None, f"Video generation failed: {operation.error}"
    assert operation.response is not None
    assert operation.response.generated_videos

    # 3. Save video
    with tempfile.TemporaryDirectory() as temp_dir:
        output_path = os.path.join(temp_dir, "test_video.mp4")
        video = operation.response.generated_videos[0].video
        video.save(output_path)

        # Verify file was created
        assert os.path.exists(output_path)

    opik.flush_tracker()

    # Three traces: models.generate_videos, operations.get, video.save
    assert len(fake_backend.trace_trees) >= 3

    EXPECTED_GENERATE_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="models.generate_videos",
        input=ANY_DICT.containing(
            {"prompt": prompt, "model": VIDEO_MODEL, "config": ANY_DICT}
        ),
        output=ANY_DICT,
        tags=["genai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "genai",
                "type": "genai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="models.generate_videos",
                input=ANY_DICT.containing(
                    {"prompt": prompt, "model": VIDEO_MODEL, "config": ANY_DICT}
                ),
                output=ANY_DICT,
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                spans=[],
                model=VIDEO_MODEL,
                provider="google_vertexai",
            )
        ],
    )

    EXPECTED_OPERATIONS_GET_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="operations.get",
        input=ANY_DICT.containing({"operation": ANY_BUT_NONE}),
        output={"name": ANY_BUT_NONE, "done": True},
        tags=["genai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "genai",
                "type": "genai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="operations.get",
                input=ANY_DICT.containing({"operation": ANY_BUT_NONE}),
                output={"name": ANY_BUT_NONE, "done": True},
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                spans=[],
            )
        ],
    )

    EXPECTED_SAVE_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="video.save",
        input={"file": ANY_BUT_NONE},
        output=None,
        tags=["genai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        attachments=[
            AttachmentModel(
                file_path=ANY_BUT_NONE,
                file_name="test_video.mp4",
                content_type="video/mp4",
            )
        ],
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="video.save",
                input={"file": ANY_BUT_NONE},
                output=None,
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                spans=[],
                attachments=[
                    AttachmentModel(
                        file_path=ANY_BUT_NONE,
                        file_name="test_video.mp4",
                        content_type="video/mp4",
                    )
                ],
            )
        ],
    )

    # Find traces by name
    generate_trace = next(
        t for t in fake_backend.trace_trees if t.name == "models.generate_videos"
    )
    # Get the last operations.get trace (the one that returned done=True)
    operations_get_traces = [
        t for t in fake_backend.trace_trees if t.name == "operations.get"
    ]
    operations_get_trace = operations_get_traces[-1]  # Last one should be done=True
    save_trace = next(t for t in fake_backend.trace_trees if t.name == "video.save")

    assert_equal(EXPECTED_GENERATE_TRACE, generate_trace)
    assert_equal(EXPECTED_OPERATIONS_GET_TRACE, operations_get_trace)
    assert_equal(EXPECTED_SAVE_TRACE, save_trace)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.asyncio
@retry_on_rate_limit
async def test_genai_client__generate_videos_and_save__async__happyflow(fake_backend):
    """
    Test async video generation workflow: create -> wait -> save.

    This test verifies that the async GenAI client works correctly with video tracking.
    """
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client, project_name="genai-video-test")

    prompt = "A red cube rotating slowly"

    # 1. Create video (async)
    operation = await client.aio.models.generate_videos(
        model=VIDEO_MODEL,
        prompt=prompt,
        config=VIDEO_CONFIG,
    )

    # 2. Wait for completion (polling is sync in genai SDK)
    max_wait_time = 300  # 5 minutes
    start_time = time.time()
    while not operation.done:
        if time.time() - start_time > max_wait_time:
            pytest.fail("Video generation timed out")
        time.sleep(10)
        operation = client.operations.get(operation)

    assert operation.error is None, f"Video generation failed: {operation.error}"
    assert operation.response is not None
    assert operation.response.generated_videos

    # 3. Save video
    with tempfile.TemporaryDirectory() as temp_dir:
        output_path = os.path.join(temp_dir, "test_video.mp4")
        video = operation.response.generated_videos[0].video
        video.save(output_path)

        # Verify file was created
        assert os.path.exists(output_path)

    opik.flush_tracker()

    # Three traces: models.generate_videos, operations.get, video.save
    assert len(fake_backend.trace_trees) >= 3

    EXPECTED_GENERATE_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="models.generate_videos",
        input=ANY_DICT.containing(
            {"prompt": prompt, "model": VIDEO_MODEL, "config": ANY_DICT}
        ),
        output=ANY_DICT,
        tags=["genai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "genai",
                "type": "genai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="models.generate_videos",
                input=ANY_DICT.containing(
                    {"prompt": prompt, "model": VIDEO_MODEL, "config": ANY_DICT}
                ),
                output=ANY_DICT,
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                spans=[],
                model=VIDEO_MODEL,
                provider="google_vertexai",
            )
        ],
    )

    EXPECTED_OPERATIONS_GET_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="operations.get",
        input=ANY_DICT.containing({"operation": ANY_BUT_NONE}),
        output={"name": ANY_BUT_NONE, "done": True},
        tags=["genai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "genai",
                "type": "genai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="operations.get",
                input=ANY_DICT.containing({"operation": ANY_BUT_NONE}),
                output={"name": ANY_BUT_NONE, "done": True},
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                spans=[],
            )
        ],
    )

    EXPECTED_SAVE_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="video.save",
        input={"file": ANY_BUT_NONE},
        output=None,
        tags=["genai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        attachments=[
            AttachmentModel(
                file_path=ANY_BUT_NONE,
                file_name="test_video.mp4",
                content_type="video/mp4",
            )
        ],
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="video.save",
                input={"file": ANY_BUT_NONE},
                output=None,
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                spans=[],
                attachments=[
                    AttachmentModel(
                        file_path=ANY_BUT_NONE,
                        file_name="test_video.mp4",
                        content_type="video/mp4",
                    )
                ],
            )
        ],
    )

    # Find traces by name
    generate_trace = next(
        t for t in fake_backend.trace_trees if t.name == "models.generate_videos"
    )
    # Get the last operations.get trace (the one that returned done=True)
    operations_get_traces = [
        t for t in fake_backend.trace_trees if t.name == "operations.get"
    ]
    operations_get_trace = operations_get_traces[-1]  # Last one should be done=True
    save_trace = next(t for t in fake_backend.trace_trees if t.name == "video.save")

    assert_equal(EXPECTED_GENERATE_TRACE, generate_trace)
    assert_equal(EXPECTED_OPERATIONS_GET_TRACE, operations_get_trace)
    assert_equal(EXPECTED_SAVE_TRACE, save_trace)


def test_genai_client__generate_videos__error_handling(fake_backend):
    """
    Test error handling when video creation fails with invalid model.

    This is a fast test (no actual video generation) that verifies:
    1. Error info is logged on trace and span
    2. Trace and span are finished gracefully despite the error
    """
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client, project_name="genai-video-test")

    prompt = "Test video"

    with pytest.raises(genai_errors.ClientError):
        _ = client.models.generate_videos(
            model="invalid-model-name",
            prompt=prompt,
            config=VIDEO_CONFIG,
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="models.generate_videos",
        input=ANY_DICT.containing({"prompt": prompt}),
        output=None,
        tags=["genai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "genai",
                "type": "genai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-video-test",
        error_info={
            "exception_type": "ClientError",
            "message": ANY_BUT_NONE,
            "traceback": ANY_BUT_NONE,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="models.generate_videos",
                input=ANY_DICT.containing({"prompt": prompt}),
                output=None,
                tags=["genai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "genai",
                        "type": "genai_videos",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name="genai-video-test",
                model="invalid-model-name",
                provider="google_vertexai",
                error_info={
                    "exception_type": "ClientError",
                    "message": ANY_BUT_NONE,
                    "traceback": ANY_BUT_NONE,
                },
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
