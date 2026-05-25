"""Burst, spread, and concurrent scenarios."""

import contextlib
import threading
import time
from concurrent.futures import Future, ThreadPoolExecutor
from typing import Iterator, List, Set

import opik
import pytest
from opik.api_objects import opik_client
from opik.message_processing.batching import batch_manager_constuctors

from . import _helpers
from ._helpers import Metrics


def test_burst_single_loop(metrics: Metrics, load_scale: float) -> None:
    """Worst-case burst: tight loop, zero think-time, single thread.

    Calls a ``@opik.track``-decorated handler 50k times back-to-back
    with no sleep between submits. This is the deliberate worst case —
    it puts maximum pressure on the SDK's in-process queue and batch
    flusher before any HTTP work catches up.

    Volume: 50k traces, ~200 B input each.

    Verifies every submitted trace id lands with required fields set.
    """
    trace_count: int = int(50_000 * load_scale)
    trace_input_bytes: int = 200
    project_name: str = _helpers.unique_project_name("burst")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["trace_input_bytes"] = trace_input_bytes

    submitted_trace_ids: List[str] = []

    @opik.track(project_name=project_name)
    def handle_request(prompt: str) -> str:
        submitted_trace_ids.append(opik.opik_context.get_current_trace_data().id)
        return f"echo: {prompt}"

    with metrics.timer("logging"):
        for _ in range(trace_count):
            handle_request(prompt=_helpers.random_text(trace_input_bytes))

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client, project_name=project_name, expected_ids=set(submitted_trace_ids)
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)


def test_spread_over_time(metrics: Metrics, load_scale: float) -> None:
    """Steady-rate workload paced over a long window.

    Calls a ``@opik.track``-decorated handler 10k times evenly spaced
    across a 10-minute window (~17 traces/sec sustained). Mirrors a real
    moderate-rate production workload and exercises the periodic flush
    path that fires on its interval rather than on batch-size triggers.

    Volume: 10k traces over 600 s, ~200 B input each.

    Verifies every submitted trace id lands with required fields set.
    """
    trace_count: int = int(10_000 * load_scale)
    window_seconds: int = max(1, int(600 * load_scale))
    trace_input_bytes: int = 200
    project_name: str = _helpers.unique_project_name("spread")

    metrics["project_name"] = project_name
    metrics["trace_count"] = trace_count
    metrics["window_seconds"] = window_seconds
    metrics["trace_input_bytes"] = trace_input_bytes

    submitted_trace_ids: List[str] = []

    @opik.track(project_name=project_name)
    def handle_request(prompt: str) -> str:
        submitted_trace_ids.append(opik.opik_context.get_current_trace_data().id)
        return f"echo: {prompt}"

    interval: float = window_seconds / trace_count
    next_log_time: float = time.perf_counter()
    with metrics.timer("logging"):
        for _ in range(trace_count):
            handle_request(prompt=_helpers.random_text(trace_input_bytes))
            next_log_time += interval
            sleep_for: float = next_log_time - time.perf_counter()
            if sleep_for > 0:
                time.sleep(sleep_for)

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client, project_name=project_name, expected_ids=set(submitted_trace_ids)
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)


def test_concurrent_writers_share_one_client(
    metrics: Metrics, load_scale: float
) -> None:
    """30 threads invoking the same ``@opik.track``-decorated handler.

    Every thread calls into the same global Opik client (the one the
    ``@opik.track`` decorator uses by default). Each invocation gets its
    own trace via thread-local context — exactly how a real multi-thread
    server uses the SDK. Realistic think-time prevents lockstep submits.
    This is the configuration most likely to surface batcher races —
    same shape as the OPIK-6444 unit regression, just one level up.

    Volume: 30 threads × 1k traces = 30k traces, ~200 B input each.

    Verifies that every submitted trace id lands with required fields
    set. Any dropped message fails the test with a sample of missing ids.
    """
    thread_workers: int = 30
    traces_per_worker: int = int(1_000 * load_scale)
    total_traces: int = thread_workers * traces_per_worker
    trace_input_bytes: int = 200
    project_name: str = _helpers.unique_project_name("concurrent")

    metrics["project_name"] = project_name
    metrics["thread_workers"] = thread_workers
    metrics["traces_per_worker"] = traces_per_worker
    metrics["total_traces"] = total_traces
    metrics["trace_input_bytes"] = trace_input_bytes

    submitted_trace_ids: List[str] = []
    submitted_lock: threading.Lock = threading.Lock()

    @opik.track(project_name=project_name)
    def handle_request(worker_id: int, prompt: str) -> str:
        trace_id: str = opik.opik_context.get_current_trace_data().id
        with submitted_lock:
            submitted_trace_ids.append(trace_id)
        return f"worker-{worker_id}: {prompt}"

    def worker(worker_id: int) -> None:
        for _ in range(traces_per_worker):
            handle_request(
                worker_id=worker_id,
                prompt=_helpers.random_text(trace_input_bytes),
            )
            _helpers.think_time()

    with metrics.timer("logging"):
        with ThreadPoolExecutor(max_workers=thread_workers) as pool:
            futures: List[Future[None]] = [
                pool.submit(worker, w) for w in range(thread_workers)
            ]
            for future in futures:
                future.result()

    with metrics.timer("flush"):
        opik.flush_tracker()

    client = _helpers.opik_client()
    with metrics.timer("verify"):
        delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
            client,
            project_name=project_name,
            expected_ids=set(submitted_trace_ids),
            timeout_seconds=1200,
        )

    metrics["delivered_trace_count"] = len(delivered_trace_ids)


_RACE_FLUSH_INTERVAL_SECONDS: float = 0.005


@contextlib.contextmanager
def _race_stress_flush_interval() -> Iterator[None]:
    """Monkey-patches the batch flush interval down for the duration of one test.

    The production default is 2.0 s, so a typical submission window only
    contains ~1 flush cycle — far too few for a missing-lock regression in
    ``BatchManager.flush_ready`` (OPIK-6444) to surface reliably. Lowering
    the interval to 5 ms matches the unit-test setup and gives ~hundreds of
    flush cycles per submission window, turning a flaky catch into a
    near-deterministic one.

    Also drops any existing global Opik client so the next ``@opik.track``
    constructs a fresh client that picks up the patched intervals.
    """
    saved_trace = batch_manager_constuctors.CREATE_TRACES_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS
    saved_span = batch_manager_constuctors.CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS

    batch_manager_constuctors.CREATE_TRACES_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = _RACE_FLUSH_INTERVAL_SECONDS
    batch_manager_constuctors.CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = _RACE_FLUSH_INTERVAL_SECONDS
    opik_client.reset_global_client(end_client=True)
    try:
        yield
    finally:
        batch_manager_constuctors.CREATE_TRACES_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = saved_trace
        batch_manager_constuctors.CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = saved_span
        opik_client.reset_global_client(end_client=True)


# 300 s hang-guard: with the lock in place this scenario finishes in ~5 s at
# scale 0.05 and ~2 min at scale 1.0. If a regression in batch_manager makes
# flush deadlock against a concurrent add (the OPIK-6444 family of bugs),
# observed behaviour is a hard hang on opik.flush_tracker() or
# verify_exact_trace_ids — without this marker the worker would silently
# burn the entire workflow timeout. With it, the hang surfaces as a fast
# `Failed: Timeout >300.0s` failure in the report.
@pytest.mark.timeout(300)
def test_concurrent_writers_race_stress(
    metrics: Metrics, load_scale: float
) -> None:
    """High-pressure race-condition stress test for the SDK batch manager.

    Tuned specifically to maximize the number of flush cycles that fire
    while messages are being added concurrently, so a missing lock around
    ``BatchManager.flush_ready`` (the OPIK-6444 regression shape) surfaces
    reliably rather than only on lucky runs:

    - 100 threads (vs 30 in the realistic concurrent test) maximize
      contention on the shared batcher's accumulator.
    - **Zero think-time** keeps the submission window tight so multiple
      flush cycles must overlap with adds.
    - The batcher's per-type flush interval is monkey-patched from the
      production default (2.0 s) down to 5 ms via
      ``_race_stress_flush_interval``, so ~hundreds of flush cycles fire
      during submission instead of ~1.

    Volume: 100 threads × 500 traces = 50k traces (100k messages).

    Verifies every submitted trace id lands with required fields set.
    Any dropped CREATE message fails via ``verify_exact_trace_ids``; any
    dropped UPDATE message fails via the ``end_time``/``name`` field
    check in ``verify_traces``.
    """
    thread_workers: int = 100
    traces_per_worker: int = int(500 * load_scale)
    total_traces: int = thread_workers * traces_per_worker
    trace_input_bytes: int = 100
    project_name: str = _helpers.unique_project_name("concurrent-race")

    metrics["project_name"] = project_name
    metrics["thread_workers"] = thread_workers
    metrics["traces_per_worker"] = traces_per_worker
    metrics["total_traces"] = total_traces
    metrics["trace_input_bytes"] = trace_input_bytes
    metrics["flush_interval_seconds"] = _RACE_FLUSH_INTERVAL_SECONDS

    submitted_trace_ids: List[str] = []
    submitted_lock: threading.Lock = threading.Lock()

    with _race_stress_flush_interval():
        @opik.track(project_name=project_name)
        def handle_request(worker_id: int, prompt: str) -> str:
            trace_id: str = opik.opik_context.get_current_trace_data().id
            with submitted_lock:
                submitted_trace_ids.append(trace_id)
            return f"worker-{worker_id}: {prompt}"

        def worker(worker_id: int) -> None:
            for _ in range(traces_per_worker):
                handle_request(
                    worker_id=worker_id,
                    prompt=_helpers.random_text(trace_input_bytes),
                )

        with metrics.timer("logging"):
            with ThreadPoolExecutor(max_workers=thread_workers) as pool:
                futures: List[Future[None]] = [
                    pool.submit(worker, w) for w in range(thread_workers)
                ]
                for future in futures:
                    future.result()

        with metrics.timer("flush"):
            opik.flush_tracker()

        client = _helpers.opik_client()
        with metrics.timer("verify"):
            delivered_trace_ids: Set[str] = _helpers.verify_exact_trace_ids(
                client,
                project_name=project_name,
                expected_ids=set(submitted_trace_ids),
                timeout_seconds=1200,
            )

        metrics["delivered_trace_count"] = len(delivered_trace_ids)
