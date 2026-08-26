"""Delete existing traces at a steady rate through the normal SDK — the "deletes during the cutover window" reproducer.

This is what makes the cutover interesting: with deletion capture enabled
(ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true on the backend), each delete is recorded in the
deletion-events bridge and must be replayed onto the destination — otherwise it leaks across the swap.

It pulls a pool of existing trace ids via search and deletes them at the target rate, refilling as it drains. Run it
during or after the backfill so the traces it deletes have already been copied — that is the leak the bridge prevents.

`--resurrect-ratio` additionally re-creates a share of the just-deleted ids through the normal ingestion API, which is
the only way to exercise the forward replay's RESURRECTION GUARD: such an id is bridged as deleted (event_time >=
backfill_start) yet is live again on the source, so the replay must NOT mask it on the destination — a naive
replay-by-key would drop a live row, silent data loss. Do not rely on the write/delete overlap to produce this by
chance: `live_traffic.py` only ever updates ids from its own recent-creates buffer, so a rehearsal can easily see ZERO
resurrections and leave the guard unexercised. Set it explicitly (e.g. `--resurrect-ratio 0.05`) when rehearsing.

This is a best-effort TRAFFIC GENERATOR (deletes newest-first for the run's --duration), NOT a guaranteed full-drain:
search returns the newest page, so during delete-mask visibility lag a refill can transiently return only ids already
in `seen`/`pool`; the loop tolerates a few such empty refills (EMPTY_REFILL_LIMIT) before concluding the project is
drained. It does not assert every trace was deleted — its job is to exercise the deletion bridge, not to empty the table.

Prerequisites: `OPIK_URL_OVERRIDE` pointing at the local install. Run `python delete_traffic.py --help` for options.
"""

import random
import signal
import time

import click

from _common import LOGGER, DEFAULT_PROJECT, make_opik_client

_stop = False

# Consecutive empty refills tolerated before concluding the project is drained. An empty refill can be a transient
# delete-mask-visibility lag (the just-deleted top ids not yet hidden from search), not a truly empty project — so give
# the mask several beats to propagate before stopping.
EMPTY_REFILL_LIMIT = 5
# Newest-page size to pull per refill. Larger reaches past the just-deleted (still-visible) top ids to undeleted ones
# during mask lag, so the run keeps finding work instead of stopping early.
REFILL_FETCH = 2000
# Seconds to wait after a delete before re-creating that id under --resurrect-ratio. A lightweight DELETE is an ASYNC
# mutation: its mask applies to every row matching the predicate in the parts it sweeps, so an id re-created before the
# mutation lands gets masked along with the original and never becomes live — the resurrection silently does not happen.
# Re-creating immediately therefore loses most attempts, silently leaving the guard unexercised. Deferring past the
# mutation makes the resurrection real, and costs nothing: ids are queued rather than slept on, so the delete rate holds.
RESURRECT_SETTLE_SECONDS = 3.0


def _handle_sigint(_signum, _frame):
    global _stop
    _stop = True
    LOGGER.info("stopping after the current batch...")


def _fetch_ids(client, project, want, exclude):
    try:
        traces = client.search_traces(project_name=project, max_results=want, truncate=True)
    except Exception as exc:  # transient search failure: signal the caller to retry, not to treat the pool as drained
        LOGGER.warning("search_traces failed (will retry): %s", exc)
        return None
    return [t.id for t in traces if t.id not in exclude]


@click.command()
@click.option("--project", default=DEFAULT_PROJECT, help="Project name to delete from.")
@click.option("--tps", default=2.0, help="Target deletes per second.")
@click.option("--duration", default=120, help="How long to run, in seconds (0 = until Ctrl-C).")
@click.option("--batch", default=1, help="Trace ids per delete call.")
@click.option("--resurrect-ratio", default=0.0,
              help="Fraction of deleted ids to re-create under the SAME id via the ingestion API, exercising the "
                   "replay's resurrection guard. 0 disables. Try 0.05 when rehearsing the cutover.")
def main(project, tps, duration, batch, resurrect_ratio):
    signal.signal(signal.SIGINT, _handle_sigint)
    client = make_opik_client()
    interval = batch / tps if tps > 0 else 0.0

    seen: set[str] = set()
    pool: list[str] = []
    # (ready_at, trace_id) queued for re-creation once the delete mutation has had RESURRECT_SETTLE_SECONDS to land.
    pending_resurrect: list[tuple[float, str]] = []
    deleted = 0
    resurrected = 0
    empty_refills = 0
    started = time.time()

    def flush_resurrections(force=False):
        """Re-create every queued id whose delete has settled (all of them when force)."""
        nonlocal resurrected
        due = [tid for ready_at, tid in pending_resurrect if force or time.time() >= ready_at]
        if not due:
            return
        pending_resurrect[:] = [(r, t) for r, t in pending_resurrect if t not in set(due)]
        for trace_id in due:
            # Same id, through the normal ingestion path: bridged as deleted, yet live again on the source.
            client.trace(id=trace_id, name="resurrected-trace", project_name=project,
                         input={"resurrected": True}, output={"resurrected": True}).end()
            resurrected += 1

    LOGGER.info("delete traffic: project='%s' tps=%.2f batch=%d duration=%ss resurrect-ratio=%.3f (Ctrl-C to stop)",
                project, tps, batch, duration or "∞", resurrect_ratio)

    while not _stop and (duration == 0 or time.time() - started < duration):
        tick = time.time()
        if len(pool) < batch:
            # Exclude both already-deleted ids and those still queued in `pool`, so a refill can't requeue an in-flight id.
            fetched = _fetch_ids(client, project, want=REFILL_FETCH, exclude=seen | set(pool))
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
        client.rest_client.traces.delete_traces(ids=ids)
        deleted += len(ids)
        # Queue a share of the just-deleted ids for re-creation under the SAME id, once their delete mutation has
        # settled. Such an id is bridged as deleted but live again on the source — the case the replay's resurrection
        # guard must survive. They stay in `seen`, so a later refill never queues them for deletion again.
        if resurrect_ratio > 0:
            for trace_id in ids:
                if random.random() < resurrect_ratio:
                    pending_resurrect.append((time.time() + RESURRECT_SETTLE_SECONDS, trace_id))
        flush_resurrections()
        if deleted % 50 == 0:
            LOGGER.info("deleted %d traces (resurrected %d)", deleted, resurrected)
        sleep = interval - (time.time() - tick)
        if sleep > 0:
            time.sleep(sleep)

    # Drain the queue: wait out the settle window for the last deletes, then re-create the remainder.
    if pending_resurrect:
        time.sleep(RESURRECT_SETTLE_SECONDS)
        flush_resurrections(force=True)
    client.flush()  # the resurrections go through the batching ingest path, so flush before reporting
    elapsed = time.time() - started
    LOGGER.info("done: deleted=%d resurrected=%d in %.1fs (%.2f deletes/s effective)",
                deleted, resurrected, elapsed, deleted / elapsed if elapsed else 0)
    if resurrect_ratio > 0 and resurrected == 0:
        LOGGER.warning("resurrect-ratio was set but nothing was resurrected — the replay's resurrection guard is "
                       "UNEXERCISED by this run. Raise --resurrect-ratio or --duration.")
    elif resurrected:
        LOGGER.info("Confirm the resurrections are live on the SOURCE before the replay runs — an id bridged as deleted "
                    "yet live again is exactly what the guard must not mask on the destination.")


if __name__ == "__main__":
    main()
