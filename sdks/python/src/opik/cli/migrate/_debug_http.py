"""DEBUG-gated httpx interception for ``opik migrate``.

Exists for one purpose: surface OPIK-6602's "single-process migrate
manufactures concurrent writes" claim as concrete log lines an operator
can grep.

A hook installed on the migrate-time ``opik.Opik`` client tags every
outbound request with a start timestamp + thread id, then matches them
to the response with elapsed time. The module also keeps a per-host
in-flight counter so overlapping requests against the same host print
``in_flight=N`` -- ``N > 1`` is the bug behavior.

Pure DEBUG: when the ``opik`` logger isn't at DEBUG the hook is never
installed and the counter is never touched, so production callers pay
zero overhead.
"""

from __future__ import annotations

import logging
import threading
import time
from collections import defaultdict
from typing import Dict

import httpx

from ... import hooks as opik_hooks

LOGGER = logging.getLogger("opik.cli.migrate._debug_http")

_REQUEST_START_EXT_KEY = "opik_migrate_debug_start"
_REQUEST_TID_EXT_KEY = "opik_migrate_debug_tid"

# Per-host counters; one process worth of state, guarded by a single lock.
# A migrate process talks to one host, so the dict has size 1 in practice.
_in_flight_by_host: Dict[str, int] = defaultdict(int)
_in_flight_lock = threading.Lock()


def _on_request(request: httpx.Request) -> None:
    tid = threading.get_ident()
    now = time.monotonic()
    request.extensions[_REQUEST_START_EXT_KEY] = now
    request.extensions[_REQUEST_TID_EXT_KEY] = tid

    host = request.url.host or "?"
    with _in_flight_lock:
        _in_flight_by_host[host] += 1
        in_flight = _in_flight_by_host[host]

    LOGGER.debug(
        "migrate.http >>>   tid=%d host=%s in_flight=%d %s %s",
        tid,
        host,
        in_flight,
        request.method,
        request.url.path,
    )


def _on_response(response: httpx.Response) -> None:
    request = response.request
    started = request.extensions.get(_REQUEST_START_EXT_KEY)
    tid = request.extensions.get(_REQUEST_TID_EXT_KEY, threading.get_ident())
    elapsed_ms = (time.monotonic() - started) * 1000.0 if started else float("nan")

    host = request.url.host or "?"
    with _in_flight_lock:
        # response_hook fires once per response; redirected / retried
        # requests in httpx run through the chain again and each gets
        # its own request_hook fire, so the bookkeeping stays balanced.
        _in_flight_by_host[host] = max(0, _in_flight_by_host[host] - 1)
        in_flight = _in_flight_by_host[host]

    LOGGER.debug(
        "migrate.http <<<   tid=%d host=%s in_flight=%d %s %s -> %d (%.0fms)",
        tid,
        host,
        in_flight,
        request.method,
        request.url.path,
        response.status_code,
        elapsed_ms,
    )


def install_if_debug() -> None:
    """Register the migrate http hook iff the ``opik`` logger is at DEBUG.

    Idempotent: calling more than once installs the same hook list each
    time but the hooks themselves de-dupe by identity, and ``add_httpx_client_hook``
    just appends to a list -- the second registration would only fire
    the same logging callbacks twice, doubling log lines. The migrate
    CLI calls this exactly once at startup, so the simple check below
    is enough to guard ``opik migrate`` invocation while keeping other
    test paths (which import the module) safe.
    """
    if not LOGGER.isEnabledFor(logging.DEBUG):
        return

    def _modifier(client: httpx.Client) -> None:
        # Append rather than overwrite so any pre-existing hooks
        # (e.g. set by integrations) keep firing.
        existing = client.event_hooks
        existing.setdefault("request", []).append(_on_request)
        existing.setdefault("response", []).append(_on_response)
        client.event_hooks = existing

    opik_hooks.add_httpx_client_hook(
        opik_hooks.HttpxClientHook(
            client_modifier=_modifier,
            client_init_arguments=None,
        )
    )
    LOGGER.debug(
        "migrate.http hook installed (DEBUG logging detected); future "
        "opik.Opik() httpx clients will trace requests"
    )
