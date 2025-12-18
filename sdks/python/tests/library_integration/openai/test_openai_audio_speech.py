import os
import pytest
import openai
from opik.integrations.openai import track_openai
from opik import opik_context
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

REQUIRES_OPENAI_KEY = pytest.mark.skipif(
    not os.environ.get("OPENAI_API_KEY"), 
    reason="OPENAI_API_KEY not set"
)

MODEL = "tts-1"
VOICE = "alloy"
INPUT_TEXT = "Hello world"

@REQUIRES_OPENAI_KEY
def test_audio_speech_create_sync(fake_backend):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    response = wrapped_client.audio.speech.create(
        model=MODEL,
        voice=VOICE,
        input=INPUT_TEXT
    )
    # consume response (it returns binary content directly in response.content or similar?)
    # openai.resources.audio.speech.Speech.create returns distinct types depending on response_format?
    # Default is mp3, returns HttpxBinaryResponseContent which has .content
    
    assert response.content is not None
    
    opik_context.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    
    assert len(trace.spans) == 1
    span = trace.spans[0]
    
    assert span.name == "audio_speech_create"
    assert span.input["input"] == INPUT_TEXT
    assert span.usage["prompt_tokens"] == len(INPUT_TEXT)
    
    # Check attachments
    assert len(span.attachments) == 1
    attachment = span.attachments[0]
    assert attachment["file_name"].startswith("audio_speech_create")
    # We don't check exact bytes but we trust it captured something
    

@REQUIRES_OPENAI_KEY
def test_audio_speech_stream_sync(fake_backend):
    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    # with_streaming_response.create returns a context manager
    with wrapped_client.audio.speech.with_streaming_response.create(
        model=MODEL,
        voice=VOICE,
        input=INPUT_TEXT
    ) as response:
        for _ in response.iter_bytes():
            pass

    opik_context.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    span = trace.spans[0]
    
    assert span.name == "audio_speech_with_streaming_response_create"
    assert span.usage["prompt_tokens"] == len(INPUT_TEXT)
    assert len(span.attachments) == 1


@REQUIRES_OPENAI_KEY
@pytest.mark.asyncio
async def test_audio_speech_create_async(fake_backend):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)

    response = await wrapped_client.audio.speech.create(
        model=MODEL,
        voice=VOICE,
        input=INPUT_TEXT
    )
    
    assert response.content is not None

    opik_context.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    span = trace.spans[0]
    
    assert span.name == "audio_speech_create"
    assert span.usage["prompt_tokens"] == len(INPUT_TEXT)
    assert len(span.attachments) == 1


@REQUIRES_OPENAI_KEY
@pytest.mark.asyncio
async def test_audio_speech_stream_async(fake_backend):
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)

    async with wrapped_client.audio.speech.with_streaming_response.create(
        model=MODEL,
        voice=VOICE,
        input=INPUT_TEXT
    ) as response:
        async for _ in response.aiter_bytes():
            pass

    opik_context.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    span = trace.spans[0]
    
    assert span.name == "audio_speech_with_streaming_response_create"
    assert span.usage["prompt_tokens"] == len(INPUT_TEXT)
    assert len(span.attachments) == 1
