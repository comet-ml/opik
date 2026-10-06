"""`opik.integrations.*` trackers, driven against a mock provider.

The bridge's other routes exercise the SDK's own object model. This one
exercises an INTEGRATION: a third-party client the SDK wraps, where the thing
under test is the span the wrapper writes rather than anything the caller
passes.

Two decisions shape the whole file.

**The provider is mocked, and mocked inside this process.** A live Ollama would
make the spans a function of a downloaded model: the chunk boundaries of a
streamed reply and the token counts are exactly what the usage mapping and the
stream aggregation are asserted on, and both drift per model and per machine.
The mock answers a fixed `ChatResponse` and a fixed chunk sequence, so a span
that disagrees with the expectation is the tracker's fault.

It lives here rather than as a fourth `webServer` in `playwright.config.ts`
because `ollama.Client` accepts a host carrying a path (`_parse_host`), so the
real client can be pointed at this app's own `/integrations/ollama/mock` and
every spec run already starts this app. A separate service would add a process
that every run in the estate waits on, for one spec's benefit.

**The route makes a whole call sequence in one request.** The calls have to
share one client to observe the `opik_tracked` guard at all, and the nested case
needs an `@opik.track` frame around one of them — neither survives being split
across HTTP requests. The route returns what each call returned and nothing it
could have read off the backend; the spans themselves are the caller's to assert
over REST.
"""

import asyncio
import atexit
import json
from typing import Any, AsyncIterator, Iterator

import ollama
import opik
from fastapi import APIRouter, Header, Request
from fastapi.responses import StreamingResponse
# The documented entry point for this integration. `opik.integrations` is a
# namespace package with an empty `__init__`, so `opik.integrations.ollama`
# is not reachable off the top-level import and has to be named here.
from opik.integrations.ollama import track_ollama

from ..opik_factory import make_opik_client
from ..schemas import (
    OllamaChatCall,
    OllamaChatRequest,
    OllamaChatResponse,
    OllamaChatResult,
)

router = APIRouter(prefix="/integrations/ollama", tags=["integrations"])

# The reply the mock composes, as the four streamed chunks that spell it. A
# multi-chunk reply is the point: a decorator that kept only the last chunk, or
# opened a span per chunk, both produce a plausible-looking span and only a
# known concatenation tells them apart.
_STREAM_CHUNKS = ("Hello", ",", " world", "!")
_FULL_CONTENT = "".join(_STREAM_CHUNKS)

# Ollama's own token counters, which the tracker has to map onto Opik's
# `prompt_tokens` / `completion_tokens` / `total_tokens`. Distinct values, so a
# mapping that crossed the two is visible; `total_tokens` is deliberately NOT
# reported by the provider — ollama has no such counter, and the sum is the
# SDK's to derive.
_PROMPT_EVAL_COUNT = 11
_EVAL_COUNT = 7

# The remaining native counters. They carry no meaning to assert, but they must
# survive under `original_usage.*` rather than being dropped, so they have to be
# present and non-zero.
_DURATIONS = {
    "total_duration": 1_234_567,
    "load_duration": 234_567,
    "prompt_eval_duration": 345_678,
    "eval_duration": 456_789,
}

_CREATED_AT = "2026-01-01T00:00:00.000000000Z"


def _done_chunk(model: str, content: str) -> dict[str, Any]:
    """The terminal chunk: the only one carrying `done`, the counters and timings.

    `content` is empty for a streamed reply — ollama puts no text on the done
    chunk — which is what makes the aggregation observable: a span whose output
    is the done chunk's own message reads as an empty answer.
    """
    return {
        "model": model,
        "created_at": _CREATED_AT,
        "message": {"role": "assistant", "content": content},
        "done": True,
        "done_reason": "stop",
        "prompt_eval_count": _PROMPT_EVAL_COUNT,
        "eval_count": _EVAL_COUNT,
        **_DURATIONS,
    }


def _partial_chunk(model: str, content: str) -> dict[str, Any]:
    return {
        "model": model,
        "created_at": _CREATED_AT,
        "message": {"role": "assistant", "content": content},
        "done": False,
    }


@router.post("/mock/api/chat")
async def mock_chat(request: Request) -> Any:
    """A fixed `/api/chat`, reached by a real `ollama.Client` pointed at this app.

    Async on purpose: the routes below run in FastAPI's thread pool and call
    back into this process over the loopback, so this handler has to be served
    by the event loop those threads are not occupying.
    """
    body = await request.json()
    model = body.get("model") or "mock-model"

    if not body.get("stream"):
        return _done_chunk(model, _FULL_CONTENT)

    async def ndjson() -> AsyncIterator[bytes]:
        for piece in _STREAM_CHUNKS:
            yield (json.dumps(_partial_chunk(model, piece)) + "\n").encode()
        yield (json.dumps(_done_chunk(model, "")) + "\n").encode()

    return StreamingResponse(ndjson(), media_type="application/x-ndjson")


def _mock_host(request: Request) -> str:
    """This app's own mock endpoint, as an ollama host.

    Derived from the request rather than configured: the bridge's port is
    `playwright.config.ts`'s to choose, and a second copy of it here is one more
    thing that can disagree. `ollama.Client` keeps a path on the host and
    appends `/api/chat` to it.
    """
    return f"{str(request.base_url).rstrip('/')}/integrations/ollama/mock"


def _collect(stream: Iterator[Any]) -> tuple[str, int]:
    """Drain a streamed `chat()` to exhaustion; return its text and chunk count.

    Exhaustion matters to the tracker, not just to this function: the decorator
    ends the span from the stream wrapper's own completion, so a partially
    consumed generator leaves the span open and the assertion that follows reads
    a span that was never finished.

    The count is returned because "the streamed call produced ONE span" is only
    a claim about aggregation if the stream really arrived in several chunks. A
    caller that could not see the count would pass identically against a mock
    that answered in one.
    """
    pieces = [chunk.message.content or "" for chunk in stream]
    return "".join(pieces), len(pieces)


async def _collect_async(stream: AsyncIterator[Any]) -> tuple[str, int]:
    pieces: list[str] = []
    async for chunk in stream:
        pieces.append(chunk.message.content or "")
    return "".join(pieces), len(pieces)


def _make_call(
    call: OllamaChatCall,
    body: OllamaChatRequest,
    host: str,
) -> OllamaChatResult:
    kwargs: dict[str, Any] = {
        "model": body.model,
        "messages": [{"role": "user", "content": body.prompt}],
        "stream": call.stream,
    }

    track_kwargs: dict[str, Any] = {"project_name": body.project_name}
    if call.provider is not None:
        track_kwargs["provider"] = call.provider

    if call.use_async:
        client = track_ollama(
            ollama.AsyncClient(host=host), **track_kwargs
        )

        async def _run() -> tuple[str, int]:
            result = await client.chat(**kwargs)
            if call.stream:
                return await _collect_async(result)
            return result.message.content or "", 0

        # A fresh loop in this worker thread. The mock endpoint is served by the
        # app's own loop on the main thread, so a self-request from here does
        # not deadlock.
        content, chunk_count = asyncio.run(_run())
        return OllamaChatResult(
            label=call.label, content=content, model=body.model, chunk_count=chunk_count
        )

    client = track_ollama(ollama.Client(host=host), **track_kwargs)

    def _one_call() -> tuple[str, int]:
        result = client.chat(**kwargs)
        if call.stream:
            return _collect(result)
        return result.message.content or "", 0

    if call.parent_name is None:
        content, chunk_count = _one_call()
        return OllamaChatResult(
            label=call.label, content=content, model=body.model, chunk_count=chunk_count
        )

    # The nested case. `opik.track` is applied here rather than at import time
    # because the parent's name is the caller's, and the span the chat call must
    # end up under is this function's.
    tracked = opik.track(name=call.parent_name, project_name=body.project_name)(_one_call)
    content, chunk_count = tracked()
    return OllamaChatResult(
        label=call.label, content=content, model=body.model, chunk_count=chunk_count
    )


@router.post("/chat", response_model=OllamaChatResponse, status_code=201)
def ollama_chat(
    body: OllamaChatRequest,
    request: Request,
    x_opik_api_key: str | None = Header(default=None),
) -> OllamaChatResponse:
    """Drive `track_ollama` through every call shape the caller asked for.

    The decorator resolves its client through `get_global_client()`, so the
    request's workspace and key have to be bound globally — the same wiring
    `traces.py` and `threads.py` need.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)
    host = _mock_host(request)

    # The `opik_tracked` guard, checked on a client this route then throws away.
    # Observing it through the spans would mean asserting an absence of
    # duplicates, which passes just as well when the tracker never ran.
    probe = track_ollama(
        ollama.Client(host=host), project_name=body.project_name
    )
    probe_chat = probe.chat
    rewrapped = track_ollama(probe, project_name=body.project_name)
    double_track_is_noop = rewrapped is probe and rewrapped.chat is probe_chat

    try:
        results = [_make_call(call, body, host) for call in body.calls]
        # Flush before answering so the caller's first REST read is not racing
        # the SDK's background streamer. The spans are still eventually
        # consistent in ClickHouse, which the caller polls for.
        opik.flush_tracker()
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return OllamaChatResponse(
        double_track_is_noop=double_track_is_noop, calls=results
    )
