"""A `@opik.track`ed trace whose payload carries numpy scalars, and its control.

`jsonable_encoder.encode` sits on the write path of every SDK-logged trace:
`encoder_helpers.encode_and_anonymize` runs it over the whole trace and span
write kwargs in `batchers.py` and `online_message_processor.py`. opik#9573 added
a branch to it that converts `np.bool_`, `np.integer` and `np.floating` to
Python primitives instead of letting them fall through to `str(obj)`, while
deliberately keeping `np.datetime64` and `np.timedelta64` as text.

This route exists because that payload cannot be sent over HTTP. A numpy scalar
has no JSON spelling — writing one into a request body would already have
converted it, which is the thing under test — so the values have to be
constructed in the process that calls the SDK. Everything the caller needs to
assert against is returned instead: the two trace ids, and the control payload
as the plain-Python dict it was built from.

**Two traces, in one request, into one project.** The control is what makes the
assertion discriminating rather than descriptive: the numpy trace's stored JSON
has to be byte-identical to a trace built from the equivalent plain Python
values. Asserting the numpy trace alone against a literal would pin whatever
this release happens to produce. And they share the request so a flush that
silently dropped a batch takes both — a numpy trace missing from a run where the
control also went missing says nothing about numpy.

**The `dt64` and `td64` controls are strings on purpose.** `np.datetime64` and
`np.timedelta64` are the two types the fix leaves as text, so the plain-Python
equivalent of what the encoder must produce for them *is* `"2026-11-23"` and
`"5 seconds"`. Spelling them out here, rather than converting with `str()`, is
what makes the control an independent statement of the expected output instead
of a second copy of the code under test.
"""

import atexit
from typing import Any

import numpy as np
import opik
from fastapi import APIRouter, Header

from ..opik_factory import make_opik_client
from ..visibility import wait_for_trace
from ..schemas import NumpyPayloadRequest, NumpyPayloadResponse

router = APIRouter(prefix="/traces", tags=["traces"])


def _numpy_payload() -> dict[str, Any]:
    """The payload under test: one value per branch opik#9573 touches.

    `np.float32(1.5)` and not a value like 0.1: 1.5 is exactly representable in
    binary32, so `float(np.float32(1.5))` is 1.5 and the control can state the
    expected number as a literal. A non-representable value would make the
    expectation a float32 rounding artefact, which is a fact about IEEE 754 and
    not about this fix.

    `np.array([2, 3])` covers the pre-existing `ndarray` branch beside the new
    scalar ones, and its elements are `np.int64` — so it also says the
    conversion reaches inside a container.
    """
    return {
        "i64": np.int64(7),
        "f32": np.float32(1.5),
        "b": np.bool_(True),
        "dt64": np.datetime64("2026-11-23"),
        "td64": np.timedelta64(5, "s"),
        "arr": np.array([2, 3]),
    }


def _control_payload() -> dict[str, Any]:
    """The same payload in plain Python — what the encoder must produce.

    Every value here is already JSON-native, so this dict passes through
    `jsonable_encoder.encode` unchanged and the trace it is logged on records
    exactly these values. That is what makes it usable as the expected output
    for the numpy trace as well as a trace in its own right.
    """
    return {
        "i64": 7,
        "f32": 1.5,
        "b": True,
        # Text, deliberately — see the module docstring.
        "dt64": "2026-11-23",
        "td64": "5 seconds",
        "arr": [2, 3],
    }


def _log_trace(name: str, project_name: str, payload: dict[str, Any]) -> str:
    """Log one `@opik.track`ed trace carrying `payload` and return its id.

    The payload goes in three places, because the encoder is applied to the
    whole write kwargs dict and a branch that reached only one of them would be
    a real regression: the function's ARGUMENT becomes the trace input, its
    RETURN becomes the output, and `update_current_trace(metadata=...)` sets the
    metadata the panel renders.

    `update_current_trace` is called from INSIDE the tracked function on
    purpose, the way `traces.py`'s `create_trace` sets `thread_id`: it merges
    into the live `TraceData` that the decorator emits when the function
    returns, so there is no second update message racing the create. Calling
    `Trace.end()` by hand after a create is what loses a batched create — the
    SDK's own documented race, and exactly how "the numpy payload vanished"
    looks.
    """
    emitted: dict[str, str] = {}

    @opik.track(name=name, project_name=project_name)
    def _emit(payload: dict[str, Any]) -> dict[str, Any]:
        emitted["trace_id"] = opik.opik_context.get_current_trace_data().id
        opik.opik_context.update_current_trace(metadata=payload)
        return payload

    _emit(payload)
    return emitted["trace_id"]


@router.post("/numpy-payload", response_model=NumpyPayloadResponse, status_code=201)
def numpy_payload(
    body: NumpyPayloadRequest,
    x_opik_api_key: str | None = Header(default=None),
) -> NumpyPayloadResponse:
    """Log the numpy trace and its plain-Python control, and report both ids.

    `@opik.track` resolves its client through `get_global_client()`, so the
    request's workspace and key have to be bound globally — the same wiring
    `traces.py` and `tracked_generators.py` need.

    Both traces are flushed together and then read back by id, so the route only
    answers once each row is queryable. The caller's first REST read is
    therefore not racing the SDK's streamer thread.
    """
    client = make_opik_client(workspace=body.workspace, api_key=x_opik_api_key)
    opik.set_global_client(client, context_wise=True)

    try:
        numpy_trace_id = _log_trace(
            body.numpy_trace_name, body.project_name, _numpy_payload()
        )
        control_trace_id = _log_trace(
            body.control_trace_name, body.project_name, _control_payload()
        )
        opik.flush_tracker()
        wait_for_trace(client, numpy_trace_id)
        wait_for_trace(client, control_trace_id)
    finally:
        client.end(flush=False)
        atexit.unregister(client.end)

    return NumpyPayloadResponse(
        numpy_trace_id=numpy_trace_id,
        control_trace_id=control_trace_id,
        expected=_control_payload(),
        numpy_version=np.__version__,
    )
