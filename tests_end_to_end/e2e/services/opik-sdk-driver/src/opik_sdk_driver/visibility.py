import time

import opik
from fastapi import HTTPException
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.types.trace_public import TracePublic

# Bounded below the TS client's default 30s request budget, so a trace that
# never lands surfaces as this route's own 500 naming the id rather than as an
# anonymous client-side abort.
_TRACE_VISIBLE_TIMEOUT_S = 25.0
_TRACE_POLL_INTERVAL_S = 0.5


def wait_for_trace(client: opik.Opik, trace_id: str) -> TracePublic:
    """Block until the trace is readable by id, and return it.

    Reads GET /traces/{id} rather than `search_traces(wait_for_at_least=...)`:
    the search endpoint is throttled per workspace (30/min by default) wherever
    rate limiting is on, and the SDK answers a 429 by sleeping out the whole
    retry-after window, about a minute. One seed of a few dozen traces spends
    that budget on its own, so on a shared cloud workspace a search-based
    read-back stalled seeds past every client timeout. The by-id read carries no
    per-endpoint limit, and an id is unique where a name filter is not.
    """
    deadline = time.monotonic() + _TRACE_VISIBLE_TIMEOUT_S
    while True:
        try:
            return client.get_trace_content(trace_id)
        except ApiError as err:
            if err.status_code != 404:
                raise
        if time.monotonic() >= deadline:
            raise HTTPException(
                status_code=500,
                detail=(
                    f"Trace {trace_id} not readable "
                    f"{_TRACE_VISIBLE_TIMEOUT_S:.0f}s after create + flush"
                ),
            )
        time.sleep(_TRACE_POLL_INTERVAL_S)
