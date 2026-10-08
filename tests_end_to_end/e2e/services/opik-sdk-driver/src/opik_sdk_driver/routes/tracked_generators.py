"""`@opik.track` on a GENERATOR, stopped before it is exhausted.

The bridge already drives `@opik.track` for real, but only on a plain function
(`traces.py`'s `create_trace`). A tracked generator is a different code path
entirely — `SyncTrackedGenerator`, which opens its span on the first `next()`
and, before opik#8518, ended it only on `StopIteration`. A consumer that
stopped early therefore left a span that was never ended, and the WHOLE TRACE
was dropped: from the user's side, the SDK logged nothing at all.

Two decisions shape this file.

**One request makes the whole sequence, then flushes once.** The calls have to
share this process to be reported together, and the controls are only worth
anything beside the early-exit shapes in the same run — a trace missing from a
run where the controls also went missing says nothing about generators.

**Each shape runs inside its own function, which then RETURNS.** That is not
tidiness: what ends the span of an abandoned generator is the wrapper being
dropped (`__del__` -> `_finalize_if_unfinished`) or closed, so the references
holding it have to actually go away before the flush. A shape written inline in
the route would keep its generator alive in this frame's locals for the whole
request, and for `consumer_raises` the caught exception's traceback would keep
the loop's frame — and so the iterator — alive as well. Running each in a
function that returns normally drops both.
"""

import atexit
import gc
import itertools
from typing import Callable, Iterator

import opik
from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..schemas import (
    TrackedGeneratorCall,
    TrackedGeneratorRequest,
    TrackedGeneratorResponse,
    TrackedGeneratorResult,
)

router = APIRouter(prefix="/traces", tags=["traces"])


def _tracked_generator(
    label: str, project_name: str, items: list[str]
) -> Callable[[], Iterator[str]]:
    """A `@opik.track`ed generator named `label`, yielding `items`.

    Decorated here rather than at import time because the name is the caller's,
    and the name is how the spec addresses the trace this call produces.
    """

    @opik.track(name=label, project_name=project_name)
    def _generate() -> Iterator[str]:
        for item in items:
            yield item

    return _generate


def _consume(call: TrackedGeneratorCall, project_name: str, items: list[str]) -> TrackedGeneratorResult:
    """Make one call, in the shape asked for, and report what the consumer saw."""
    generate = _tracked_generator(call.label, project_name, items)
    consumed: list[str] = []
    caught: str | None = None

    if call.shape == "break_after":
        # No named variable for the generator: the `for` loop's own reference is
        # the only one, and `break` pops it.
        for item in generate():
            consumed.append(item)
            if len(consumed) >= call.take:
                break

    elif call.shape == "bare_next":
        # A peek and nothing else. The generator is a temporary, dropped as soon
        # as `next()` returns.
        consumed.append(next(generate()))

    elif call.shape == "islice":
        # The consumer never touches the generator — `islice` holds it, and
        # dropping the islice object is what has to drop the generator.
        consumed.extend(itertools.islice(generate(), call.take))

    elif call.shape == "consumer_raises":
        sentinel = RuntimeError(f"{call.label} consumer gave up")
        try:
            for item in generate():
                consumed.append(item)
                if len(consumed) >= call.take:
                    raise sentinel
        except RuntimeError as exception:
            # Caught here, inside the function that will return: the generator
            # itself never failed, so the span must read as a partial success
            # rather than an error, and that is only observable if the span is
            # ended at all.
            caught = type(exception).__name__

    elif call.shape == "exhaust":
        consumed.extend(generate())

    elif call.shape == "plain_function":

        @opik.track(name=call.label, project_name=project_name)
        def _plain() -> str:
            return "".join(items)

        # Reported as one "item" so the caller derives the expected span output
        # the same way for every shape.
        consumed.append(_plain())

    else:  # pragma: no cover - pydantic's Literal already rejects anything else
        raise ValueError(f"unknown shape {call.shape!r}")

    return TrackedGeneratorResult(label=call.label, consumed=consumed, caught=caught)


@router.post(
    "/track-generator", response_model=TrackedGeneratorResponse, status_code=201
)
def track_generator(
    body: TrackedGeneratorRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> TrackedGeneratorResponse:
    """Drive `@opik.track` over every generator-consumption shape asked for.

    The decorator resolves its client through `get_global_client()`, so the
    request's workspace and key have to be bound globally — the same wiring
    `traces.py` and `integrations.py` need.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    try:
        results = [_consume(call, body.project_name, body.items) for call in body.calls]

        # Belt and braces, and deliberately BEFORE the flush. CPython's
        # refcounting already drops each wrapper when `_consume` returns, which
        # is what ends its span; this only matters if a reference cycle put one
        # on the collector's queue instead. It is not covering for the product:
        # the SDK's own backstop is an `atexit` hook, and this bridge process
        # outlives the whole suite — so a span that reached neither `close()`
        # nor `__del__` here never gets ended at all, and the spec goes red.
        gc.collect()

        # Flush before answering so the caller's first REST read is not racing
        # the SDK's background streamer. The rows are still eventually
        # consistent in ClickHouse, which the caller polls for.
        opik.flush_tracker()
    finally:
        client.end(flush=True)
        atexit.unregister(client.end)

    return TrackedGeneratorResponse(calls=results)
