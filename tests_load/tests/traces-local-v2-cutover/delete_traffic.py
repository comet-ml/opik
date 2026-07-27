"""Delete existing traces at a steady rate through the normal SDK — the "deletes during the cutover window" reproducer.

This is what makes the cutover interesting: with deletion capture enabled
(ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true on the backend), each delete is recorded in the
deletion-events bridge and must be replayed onto the destination — otherwise it leaks across the swap.

It pulls a pool of existing trace ids via search and deletes them at the target rate, refilling as it drains. Run it
during or after the backfill so the traces it deletes have already been copied — that is the leak the bridge prevents.

This is a best-effort TRAFFIC GENERATOR (deletes newest-first for the run's --duration), NOT a guaranteed full-drain:
search returns the newest page, so during delete-mask visibility lag a refill can transiently return only ids already
in `seen`/`pool`; the loop tolerates a few such empty refills (EMPTY_REFILL_LIMIT) before concluding the project is
drained. It does not assert every trace was deleted — its job is to exercise the deletion bridge, not to empty the table.

Prerequisites: `OPIK_URL_OVERRIDE` pointing at the local install. Run `python delete_traffic.py --help` for options.
"""

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
def main(project, tps, duration, batch):
    signal.signal(signal.SIGINT, _handle_sigint)
    client = make_opik_client()
    interval = batch / tps if tps > 0 else 0.0

    seen: set[str] = set()
    pool: list[str] = []
    deleted = 0
    empty_refills = 0
    started = time.time()
    LOGGER.info("delete traffic: project='%s' tps=%.2f batch=%d duration=%ss (Ctrl-C to stop)",
                project, tps, batch, duration or "∞")

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
                # An empty refill can be transient: the delete mask may not be visible to search yet, so the newest 500
                # ids can all still be in `seen`/`pool`. Only stop after several consecutive empty refills, so a mask-lag
                # blip doesn't end the run while thousands of lower-id traces remain undeleted.
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
        if deleted % 50 == 0:
            LOGGER.info("deleted %d traces", deleted)
        sleep = interval - (time.time() - tick)
        if sleep > 0:
            time.sleep(sleep)

    elapsed = time.time() - started
    LOGGER.info("done: deleted=%d in %.1fs (%.2f deletes/s effective)",
                deleted, elapsed, deleted / elapsed if elapsed else 0)


if __name__ == "__main__":
    main()
