import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from ...testlib import (
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

    with pytest.raises((openai.BadRequestError, openai.NotFoundError)) as exc_info:
        _ = wrapped_client.videos.create_and_poll(
            model="invalid-model-name",
            prompt=prompt,
            seconds="4",
        )

    opik.flush_tracker()

    # OpenAI has rejected an unknown video model with both 400 and 404, so accept
    # either, but not auth/quota errors that would otherwise mask a broken setup.
    expected_exception_type = type(exc_info.value).__name__

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="videos.create_and_poll",
        input=ANY_DICT.containing({"prompt": prompt, "seconds": "4"}),
        output=None,
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        error_info={
            "exception_type": expected_exception_type,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="videos.create_and_poll",
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
                model=None,
                provider=None,
                error_info={
                    "exception_type": expected_exception_type,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="videos.create",
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
                            "exception_type": expected_exception_type,
                            "message": ANY_STRING,
                            "traceback": ANY_STRING,
                        },
                        spans=[],
                        source="sdk",
                    ),
                ],
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.asyncio
async def test_openai_async_client_videos_create_and_poll__error_handling(fake_backend):
    """
    Test async error handling when video creation fails with invalid model.

    This is a fast test (no actual video generation) that verifies async error handling.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    prompt = "Test video"

    with pytest.raises((openai.BadRequestError, openai.NotFoundError)) as exc_info:
        _ = await wrapped_client.videos.create_and_poll(
            model="invalid-model-name",
            prompt=prompt,
            seconds="4",
        )

    opik.flush_tracker()

    # OpenAI has rejected an unknown video model with both 400 and 404, so accept
    # either, but not auth/quota errors that would otherwise mask a broken setup.
    expected_exception_type = type(exc_info.value).__name__

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="videos.create_and_poll",
        input=ANY_DICT.containing({"prompt": prompt, "seconds": "4"}),
        output=None,
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_videos",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        error_info={
            "exception_type": expected_exception_type,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="general",
                name="videos.create_and_poll",
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
                model=None,
                provider=None,
                error_info={
                    "exception_type": expected_exception_type,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="videos.create",
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
                            "exception_type": expected_exception_type,
                            "message": ANY_STRING,
                            "traceback": ANY_STRING,
                        },
                        spans=[],
                        source="sdk",
                    ),
                ],
                source="sdk",
            ),
        ],
        source="sdk",
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
