"""The delete generator both cutover rehearsals use — the "deletes during the cutover window" reproducer.

One implementation serves both suites because there is only one delete endpoint: `traces.delete_traces`. A span has no
standalone delete, so every span delete is the cascade of a trace delete (`SpanService.deleteByTraceIds`). What the two
suites need differently is what gets RESURRECTED afterwards, which `resurrect` selects:

  * "trace" — re-create the deleted trace under its own id (the traces cutover's resurrection case).
  * "spans" — re-create the deleted trace's SPANS under their own ids (the spans cutover's; the trace itself stays
    deleted, because it is the span keys the spans replay must not mask).

With deletion capture enabled on the backend (`ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED` /
`..._SPAN_DELETION_EVENTS_CAPTURE_ENABLED`), each removed id is recorded in the deletion-events bridge and must be
replayed onto the destination — otherwise it leaks across the swap. Run the generator during or after the backfill so
the rows it removes have already been copied; that is the leak the bridge prevents.

`--resurrect-ratio` is the only way to exercise the forward replay's RESURRECTION GUARD: a resurrected id is bridged as
deleted (event_time >= backfill_start) yet is live again on the source, so the replay must NOT mask it on the
destination — a naive replay-by-key would drop a live row, silent data loss. Do not rely on the write/delete overlap to
produce this by chance: the traffic generators only ever update rows from their own recent-creates buffer, so a
rehearsal can easily see ZERO resurrections and leave the guard unexercised. Set it explicitly (e.g. 0.05).

This is a best-effort TRAFFIC GENERATOR (deletes newest-first for the run's --duration), NOT a guaranteed full-drain:
search returns the newest page, so during delete-mask visibility lag a refill can transiently return only ids already
in `seen`/`pool`; the loop tolerates a few such empty refills (EMPTY_REFILL_LIMIT) before concluding the project is
drained. It does not assert every trace was deleted — its job is to exercise the deletion bridge, not to empty the
table.
"""

import random
import signal
import time

import click

from . import LOGGER, make_opik_client

_stop = False

# Consecutive empty refills tolerated before concluding the project is drained. An empty refill can be a transient
# delete-mask-visibility lag (the just-deleted top ids not yet hidden from search), not a truly empty project — so give
# the mask several beats to propagate before stopping.
EMPTY_REFILL_LIMIT = 5

# Newest-page size to pull per refill. Larger reaches past the just-deleted (still-visible) top ids to undeleted ones
# during mask lag, so the run keeps finding work instead of stopping early.
REFILL_FETCH = 2000

# Seconds to wait after a delete before re-creating under --resurrect-ratio. A lightweight DELETE is an ASYNC mutation:
# its mask applies to every row matching the predicate in the parts it sweeps, so a row re-created before the mutation
# lands gets masked along with the original and never becomes live — the resurrection silently does not happen.
# Deferring past the mutation makes it real, and costs nothing: ids are queued rather than slept on, so the rate holds.
RESURRECT_SETTLE_SECONDS = 3.0

_RESURRECT_TRACE = "trace"
_RESURRECT_SPANS = "spans"

_HELP = {
    _RESURRECT_TRACE: "Delete existing traces at a steady rate through the normal SDK, bridging each delete for the "
                      "cutover's replay to catch. --resurrect-ratio re-creates a share of them under their SAME trace "
                      "id, which is the only way to exercise the replay's resurrection guard.",
    _RESURRECT_SPANS: "Delete existing TRACES at a steady rate through the normal SDK. Each delete cascades to that "
                      "trace's spans, which is the only path that bridges a span delete — so one call removes MANY "
                      "spans, burstier than the traces cutover's shape. --resurrect-ratio re-creates a share of the "
                      "removed SPANS under their SAME ids, which is the only way to exercise the replay's "
                      "resurrection guard.",
}

# Appended to the "nothing was resurrected" warning. The spans arm names the trap that causes it: seeded spans hang off
# trace ids with no `traces` row, so the API cannot find — let alone delete — their traces.
_UNEXERCISED_HINT = {
    _RESURRECT_TRACE: "",
    _RESURRECT_SPANS: " Confirm the traces being deleted actually have spans: live_traffic.py creates both, whereas "
                      "seed_history.py's spans hang off trace ids that have no `traces` row.",
}


def build_delete_traffic_command(*, default_project: str, resurrect: str):
    """Build the suite's `delete_traffic` click command.

    `default_project` is the suite's `DEFAULT_PROJECT`; `resurrect` is "trace" or "spans" per the module docstring.
    """
    if resurrect not in _HELP:
        raise ValueError(f"resurrect must be one of {sorted(_HELP)}, got {resurrect!r}")
    unit = "traces" if resurrect == _RESURRECT_TRACE else "spans"

    @click.command(help=_HELP[resurrect])
    @click.option("--project", default=default_project, help="Project name to delete traces from.")
    @click.option("--tps", default=2.0, help="Target trace deletes per second.")
    @click.option("--duration", default=120, help="How long to run, in seconds (0 = until Ctrl-C).")
    @click.option("--batch", default=1, help="Trace ids per delete call.")
    @click.option("--resurrect-ratio", default=0.0,
                  help=f"Fraction of deleted traces whose {unit.upper()} are re-created under their SAME ids via the "
                       "ingestion API, exercising the replay's resurrection guard. 0 disables. Try 0.05 when "
                       "rehearsing the cutover.")
    def main(project, tps, duration, batch, resurrect_ratio):
        _run(project, tps, duration, batch, resurrect_ratio, resurrect, unit)

    return main


def _handle_sigint(_signum, _frame):
    global _stop
    _stop = True
    LOGGER.info("stopping after the current batch...")


def _fetch_trace_ids(client, project, want, exclude):
    try:
        traces = client.search_traces(project_name=project, max_results=want, truncate=True)
    except Exception as exc:  # transient search failure: signal the caller to retry, not to treat the pool as drained
        LOGGER.warning("search_traces failed (will retry): %s", exc)
        return None
    return [t.id for t in traces if t.id not in exclude]


def _span_ids_of(client, project, trace_id):
    """The span ids a trace delete is about to cascade to, read BEFORE the delete while they are still visible."""
    try:
        spans = client.search_spans(project_name=project, trace_id=trace_id, max_results=1000, truncate=True)
    except Exception as exc:  # noqa: BLE001
        LOGGER.warning("search_spans failed for trace %s (skipping its resurrection): %s", trace_id, exc)
        return []
    return [(s.id, getattr(s, "parent_span_id", None)) for s in spans]


def _resurrect_one(client, project, trace_id, spans, mode):
    """Re-create what the delete removed, under the SAME ids. Returns how many rows were re-created."""
    if mode == _RESURRECT_TRACE:
        client.trace(id=trace_id, name="resurrected-trace", project_name=project,
                     input={"resurrected": True}, output={"resurrected": True}).end()
        return 1
    for span_id, parent_span_id in spans:
        # parent_span_id is carried verbatim — changing it would trip SpanDAO's poison pill rather than resurrect the
        # span, which is a different (and unwanted) experiment.
        client.span(id=span_id, trace_id=trace_id, parent_span_id=parent_span_id, name="resurrected-span",
                    project_name=project, input={"resurrected": True}, output={"resurrected": True}).end()
    return len(spans)


def _run(project, tps, duration, batch, resurrect_ratio, mode, unit):
    signal.signal(signal.SIGINT, _handle_sigint)
    client = make_opik_client()
    interval = batch / tps if tps > 0 else 0.0

    seen: set[str] = set()
    pool: list[str] = []
    # (ready_at, trace_id, [(span_id, parent_span_id), ...]) queued for re-creation once the delete has settled. The
    # span list is empty in "trace" mode, where the trace id is all the resurrection needs.
    pending_resurrect: list[tuple[float, str, list]] = []
    deleted = 0
    resurrected = 0
    empty_refills = 0
    started = time.time()

    def flush_resurrections(force=False):
        """Re-create every queued trace whose delete has settled (all of them when force)."""
        nonlocal resurrected
        due = [(tid, spans) for ready_at, tid, spans in pending_resurrect if force or time.time() >= ready_at]
        if not due:
            return
        due_ids = {tid for tid, _ in due}
        pending_resurrect[:] = [(r, t, s) for r, t, s in pending_resurrect if t not in due_ids]
        for trace_id, spans in due:
            resurrected += _resurrect_one(client, project, trace_id, spans, mode)

    LOGGER.info("delete traffic: project='%s' tps=%.2f batch=%d duration=%ss resurrect-ratio=%.3f resurrects=%s "
                "(Ctrl-C to stop)", project, tps, batch, duration or "∞", resurrect_ratio, unit)

    while not _stop and (duration == 0 or time.time() - started < duration):
        tick = time.time()
        if len(pool) < batch:
            # Exclude both already-deleted ids and those still queued in `pool`, so a refill can't requeue an in-flight id.
            fetched = _fetch_trace_ids(client, project, want=REFILL_FETCH, exclude=seen | set(pool))
            if fetched is None:
                # transient search failure — back off and retry rather than mistaking it for "no more traces".
                time.sleep(interval if interval > 0 else 0.5)
                continue
            pool.extend(fetched)
            if not pool:
                # An empty refill can be transient: the delete mask may not be visible to search yet, so the newest
                # REFILL_FETCH ids can all still be in `seen`/`pool`. Only stop after several consecutive empty refills,
                # so a mask-lag blip doesn't end the run while thousands of lower-id traces remain undeleted.
                empty_refills += 1
                if empty_refills >= EMPTY_REFILL_LIMIT:
                    LOGGER.info("no more traces to delete after %d empty refills; stopping", empty_refills)
                    break
                time.sleep(interval if interval > 0 else 0.5)
                continue
            empty_refills = 0
        ids = [pool.pop(0) for _ in range(min(batch, len(pool)))]
        seen.update(ids)
        # Choose (and, in "spans" mode, capture) what to resurrect BEFORE the delete: afterwards the spans are masked
        # and search will not return them, so a resurrection queued later would have nothing to re-create.
        to_resurrect = []
        for trace_id in ids:
            if resurrect_ratio > 0 and random.random() < resurrect_ratio:
                spans = _span_ids_of(client, project, trace_id) if mode == _RESURRECT_SPANS else []
                if mode == _RESURRECT_TRACE or spans:
                    to_resurrect.append((trace_id, spans))
        client.rest_client.traces.delete_traces(ids=ids)
        deleted += len(ids)
        # Queue them for re-creation once the delete's mutation has settled. Their traces stay in `seen`, so a later
        # refill never queues them for deletion again.
        for trace_id, spans in to_resurrect:
            pending_resurrect.append((time.time() + RESURRECT_SETTLE_SECONDS, trace_id, spans))
        flush_resurrections()
        if deleted % 50 == 0:
            LOGGER.info("deleted %d traces (resurrected %d %s)", deleted, resurrected, unit)
        sleep = interval - (time.time() - tick)
        if sleep > 0:
            time.sleep(sleep)

    # Drain the queue: wait out the settle window for the last deletes, then re-create the remainder.
    if pending_resurrect:
        time.sleep(RESURRECT_SETTLE_SECONDS)
        flush_resurrections(force=True)
    client.flush()  # the resurrections go through the batching ingest path, so flush before reporting
    elapsed = time.time() - started
    LOGGER.info("done: traces_deleted=%d %s_resurrected=%d in %.1fs (%.2f trace-deletes/s effective)",
                deleted, unit, resurrected, elapsed, deleted / elapsed if elapsed else 0)
    if resurrect_ratio > 0 and resurrected == 0:
        LOGGER.warning("resurrect-ratio was set but nothing was resurrected — the replay's resurrection guard is "
                       "UNEXERCISED by this run. Raise --resurrect-ratio or --duration.%s", _UNEXERCISED_HINT[mode])
    elif resurrected:
        LOGGER.info("Confirm the resurrections are live on the SOURCE before the replay runs — an id bridged as "
                    "deleted yet live again is exactly what the guard must not mask on the destination.")
