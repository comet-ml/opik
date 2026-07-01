"""Load scenario for the OPIK-7152 migrate-cascade span read.

The ``opik migrate dataset`` cascade re-reads a source experiment's traces
and spans full-fidelity before recreating them at the destination. On large
experiments the single-shot / large-page read drove the backend's container
RSS past its limit (native/off-heap read buffers tracking the ClickHouse
SELECT firehose) and it was OOM-killed mid-stream — surfacing to the SDK as
``RemoteProtocolError: incomplete chunked read``.

The fix bounds the per-request page size (``max_batch_size``) and adds an
adaptive shrink in ``read_and_parse_full_stream``. This scenario seeds a
dense, experiment-linked dataset (few traces × many heavy spans) and then
exercises the cascade's exact read path — ``search_traces`` +
``search_spans`` with a bounded ``max_batch_size`` — at the shipped migrate
page size (500) and at the old SDK default (2000), recording read wall-time
and whether the read survived.

This does NOT run the ``opik migrate`` CLI: that renames the source and
writes a destination (mutating, non-idempotent). We call only the read half
of the cascade, which is what the fix changed, so the scenario is safe to
re-run and needs no cleanup between runs.

Scale with ``--load-scale`` / ``OPIK_LOAD_SCALE``. Defaults give
50 traces × 4000 spans = 200k spans at ~50 KB each (~10 GB of span content);
``OPIK_LOAD_SCALE=5`` reaches ~1M spans, matching the Bayer-scale volume the
ticket describes.
"""

from __future__ import annotations

import os
import time
from datetime import timedelta
from typing import Dict, List, Optional, Set

import httpx
import opik
from opik import id_helpers
from opik.message_processing import messages
from opik.rest_api.types.experiment_item import ExperimentItem

from . import _helpers
from ._helpers import KB, Metrics

# The migrate cascade's shipped per-request page size + the old SDK default,
# so the scenario measures the exact before/after the fix changed.
MIGRATE_PAGE_SIZE: int = 500
OLD_DEFAULT_PAGE_SIZE: int = 2000


def _seed_experiment(
    client: opik.Opik,
    *,
    dataset_name: str,
    project_name: str,
    trace_count: int,
    spans_per_trace: int,
    span_input_bytes: int,
    span_output_bytes: int,
    metrics: Metrics,
) -> str:
    """Seed a dataset + experiment with ``trace_count`` traces, each carrying
    ``spans_per_trace`` heavy spans, and link every trace to the experiment.

    Returns the source experiment id. Uses the low-level streamer + REST link
    so the shape matches what the cascade reads: an experiment whose items
    reference traces that carry the heavy spans.
    """
    rest = client.rest_client

    dataset = client.get_or_create_dataset(name=dataset_name)
    dataset.insert([{"q": f"item-{i}"} for i in range(trace_count)])
    item_ids: List[str] = [it["id"] for it in dataset.get_items()][:trace_count]

    experiment_name = f"{dataset_name}-exp"
    rest.experiments.create_experiment(
        name=experiment_name, dataset_name=dataset_name, project_name=project_name
    )
    experiments = rest.experiments.find_experiments(dataset_id=dataset.id).content or []
    experiment_id = next(e.id for e in experiments if e.name == experiment_name)

    exp_items: List[ExperimentItem] = []
    with metrics.timer("seed"):
        for i in range(trace_count):
            trace_id = id_helpers.generate_id()
            client.__internal_api__trace__(
                id=trace_id,
                name=f"trace-{i}",
                input={"prompt": _helpers.random_text(1 * KB)},
                output={"completion": _helpers.random_text(1 * KB)},
                project_name=project_name,
                source="experiment",
            )
            for s in range(spans_per_trace):
                client._streamer.put(
                    messages.CreateSpanMessage(
                        span_id=id_helpers.generate_id(),
                        trace_id=trace_id,
                        project_name=project_name,
                        parent_span_id=None,
                        name=f"span-{i}-{s}",
                        type="general",
                        start_time=_helpers.now_utc(),
                        end_time=_helpers.now_utc(),
                        input={"prompt": _helpers.random_text(span_input_bytes)},
                        output={"completion": _helpers.random_text(span_output_bytes)},
                        metadata=None,
                        tags=None,
                        usage=None,
                        model=None,
                        provider=None,
                        error_info=None,
                        total_cost=None,
                        last_updated_at=None,
                        source="experiment",
                        environment=None,
                    )
                )
            if i < len(item_ids):
                exp_items.append(
                    ExperimentItem(
                        experiment_id=experiment_id,
                        dataset_item_id=item_ids[i],
                        trace_id=trace_id,
                    )
                )
            # Flush + link per small batch so ingestion drains steadily and an
            # interrupted seed still leaves experiment-linked data.
            if (i + 1) % 5 == 0:
                client.flush()
                rest.experiments.create_experiment_items(experiment_items=exp_items)
                exp_items = []

    client.flush()
    if exp_items:
        rest.experiments.create_experiment_items(experiment_items=exp_items)

    return experiment_id


def _cascade_read(
    client: opik.Opik,
    *,
    project_name: str,
    experiment_id: str,
    page_size: int,
) -> Dict[str, object]:
    """Run the read half of the migrate cascade at ``page_size``.

    Mirrors ``cli/migrate/datasets/experiments._copy_traces_and_spans``: bulk
    ``search_traces(experiment_id=...)`` then ``search_spans`` over the traces'
    time window, both with ``truncate=False`` and a bounded ``max_batch_size``.
    Returns timing + a ``read_error`` field (None on success), so a
    ``RemoteProtocolError`` at the large page size is captured rather than
    aborting the whole run.
    """
    result: Dict[str, object] = {"page_size": page_size, "read_error": None}
    start = time.perf_counter()
    try:
        traces = client.search_traces(
            project_name=project_name,
            filter_string=f'experiment_id = "{experiment_id}"',
            max_results=1_000_000,
            truncate=False,
            max_batch_size=page_size,
        )
        result["traces_read"] = len(traces)

        # Time window across the experiment's traces (same shape the cascade
        # derives before its bulk span read).
        starts = [t.start_time for t in traces if t.start_time is not None]
        ends = [t.end_time or t.last_updated_at for t in traces]
        ends = [e for e in ends if e is not None]
        filter_string: Optional[str] = None
        if starts and ends:
            # Pad the window like the real cascade's _SPAN_BULK_WINDOW_BUFFER:
            # spans can land at/just past a trace's end_time (async streamer,
            # clock granularity), so an unpadded ``start_time <= to_time``
            # drops the last trace's spans.
            buffer = timedelta(minutes=5)
            lo = (min(starts) - buffer).isoformat().replace("+00:00", "Z")
            hi = (max(ends) + buffer).isoformat().replace("+00:00", "Z")
            filter_string = f'start_time >= "{lo}" AND start_time <= "{hi}"'

        expected: Set[str] = {t.id for t in traces}
        spans = client.search_spans(
            project_name=project_name,
            filter_string=filter_string,
            max_results=10_000_000,
            truncate=False,
            max_batch_size=page_size,
        )
        result["spans_read"] = sum(1 for s in spans if s.trace_id in expected)
    except (httpx.RemoteProtocolError, httpx.ReadTimeout, httpx.ConnectError) as exc:
        result["read_error"] = type(exc).__name__
    result["read_seconds"] = round(time.perf_counter() - start, 3)
    return result


def test_migrate_cascade_read(metrics: Metrics, load_scale: float) -> None:
    """Seed a dense experiment, then read it the cascade way at page=500
    (shipped) and page=2000 (old default), recording read time + errors.

    At ~50 KB/span a page of 2000 is ~100 MB of span content per request vs
    ~25 MB at 500. On a memory-constrained backend the 2000-page read is the
    one that OOM-drops the socket; page=500 (and the adaptive shrink) is what
    the fix relies on. The scenario records both so a regression that reverts
    the bounding shows up as a read_error at page=500.
    """
    trace_count: int = int(50 * load_scale)
    # Env override keeps smoke tests cheap without changing the CI default.
    spans_per_trace: int = int(os.getenv("OPIK_MIGRATE_SPANS_PER_TRACE", "4000"))
    span_input_bytes: int = 25 * KB
    span_output_bytes: int = 25 * KB
    project_name: str = _helpers.unique_project_name("migrate-cascade-read")
    dataset_name: str = f"migrate-cascade-{id_helpers.generate_id()[:8]}"

    metrics["project_name"] = project_name
    metrics["dataset_name"] = dataset_name
    metrics["trace_count"] = trace_count
    metrics["spans_per_trace"] = spans_per_trace
    metrics["span_input_bytes"] = span_input_bytes
    metrics["span_output_bytes"] = span_output_bytes
    metrics["total_spans"] = trace_count * spans_per_trace

    client = _helpers.opik_client()

    experiment_id = _seed_experiment(
        client,
        dataset_name=dataset_name,
        project_name=project_name,
        trace_count=trace_count,
        spans_per_trace=spans_per_trace,
        span_input_bytes=span_input_bytes,
        span_output_bytes=span_output_bytes,
        metrics=metrics,
    )
    metrics["experiment_id"] = experiment_id

    # Shipped migrate page size — must succeed.
    shipped = _cascade_read(
        client,
        project_name=project_name,
        experiment_id=experiment_id,
        page_size=MIGRATE_PAGE_SIZE,
    )
    metrics["read_page_500"] = shipped

    # Old SDK default — recorded for contrast. Not asserted: whether it OOMs
    # depends on the backend's memory ceiling, so we capture the outcome
    # rather than require a crash.
    old_default = _cascade_read(
        client,
        project_name=project_name,
        experiment_id=experiment_id,
        page_size=OLD_DEFAULT_PAGE_SIZE,
    )
    metrics["read_page_2000"] = old_default

    # The fix's contract: the shipped page size reads the whole experiment
    # without a size-correlated socket drop.
    assert shipped["read_error"] is None, (
        f"cascade read at page={MIGRATE_PAGE_SIZE} failed with "
        f"{shipped['read_error']} — the bounding fix regressed"
    )
    assert shipped["spans_read"] > 0, "no spans read back from the seeded experiment"
