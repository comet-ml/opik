import types
from typing import List

import opik
from opik.integrations.openai import track_openai
from opik.config import OPIK_PROJECT_DEFAULT_NAME

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)
import openai
import pytest


MODEL_FOR_TESTS = "tts-1"
CHAR_COUNT = 123
EXPECTED_USAGE = {
    "total_tokens": CHAR_COUNT,
    "original_usage.character_count": CHAR_COUNT,
}


class _DummySpeechResponse:
    """Minimal stub mimicking openai.AudioSpeechResponse."""

    def __init__(self, audio: bytes):
        self._audio = audio

    def model_dump(self, mode: str = "json") -> dict:  # noqa: D401 – follow OpenAI style
        return {
            "model": MODEL_FOR_TESTS,
            "audio": self._audio,
            "usage": {
                "character_count": CHAR_COUNT,
            },
        }

    # attribute used nowhere but keep for parity
    @property
    def audio(self) -> bytes:  # type: ignore[override]
        return self._audio


class _DummyStream(openai.Stream):
    """Stub for openai.Stream yielding raw bytes and exposing .response"""

    class _DummyClient:
        @staticmethod
        def _make_sse_decoder():
            return lambda event: event

    class _FinalEvent:
        def __init__(self, resp):
            self.response = resp

    def __init__(self, chunks: List[bytes]):
        self._chunks = chunks  # set before super().__init__ which calls __stream__
        self._response_obj = _DummySpeechResponse(b"".join(chunks))
        super().__init__(cast_to=bytes, response=None, client=self._DummyClient())
        self.response = self._response_obj

    def __stream__(self):  # type: ignore[override]
        for c in self._chunks:
            yield c
        yield self._FinalEvent(self._response_obj)


@pytest.fixture
def dummy_openai_client(monkeypatch):
    """Return OpenAI client stubbed for speech methods."""

    client = openai.OpenAI(api_key="sk-test")

    if not hasattr(client, "audio"):
        client.audio = types.SimpleNamespace()
    if not hasattr(client.audio, "speech"):
        client.audio.speech = types.SimpleNamespace()

    def _sync_create(**kwargs):
        return _DummySpeechResponse(b"dummy-audio")

    def _streaming_create(**kwargs):
        return _DummyStream([b"1", b"2"])

    client.audio.speech.create = _sync_create  # type: ignore[attr-defined]
    stream_ns = types.SimpleNamespace(create=_streaming_create)
    client.audio.speech.with_streaming_response = stream_ns  # type: ignore[attr-defined]

    return client


def _assert_trace_tree(trace_tree, expected_project_name):
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="speech_create",
        input=ANY_DICT,
        output=ANY_DICT,
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="speech_create",
                input=ANY_DICT,
                output=ANY_DICT,
                tags=["openai"],
                metadata=ANY_DICT,
                usage=EXPECTED_USAGE,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=MODEL_FOR_TESTS,
                provider="openai",
            )
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-speech-test", "openai-speech-test"),
    ],
)
def test_openai_speech_create__happyflow(
    fake_backend, dummy_openai_client, project_name, expected_project_name
):
    wrapped = track_openai(dummy_openai_client, project_name=project_name)

    _ = wrapped.audio.speech.create(
        model=MODEL_FOR_TESTS,
        input="Hello world",
        voice="alloy",
        format="mp3",
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    _assert_trace_tree(fake_backend.trace_trees[0], expected_project_name)


def test_openai_speech_stream__happyflow(fake_backend, dummy_openai_client):
    wrapped = track_openai(dummy_openai_client)

    stream = wrapped.audio.speech.with_streaming_response.create(
        model=MODEL_FOR_TESTS,
        input="Hello",
        voice="alloy",
    )

    chunks = []
    for it in stream:
        if isinstance(it, (bytes, bytearray)):
            chunks.append(it)
    assert b"".join(chunks) == b"12"

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    tree = fake_backend.trace_trees[0]

    span = tree.spans[0]
    assert span.name == "speech_stream"
    assert span.end_time is not None
    assert span.usage == EXPECTED_USAGE
