"""Delete existing traces at a steady rate through the normal SDK — the "deletes during the cutover window" reproducer.

This is what makes the cutover interesting: with deletion capture enabled
(ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true on the backend), each delete is recorded in the
deletion-events bridge and must be replayed onto the destination — otherwise it leaks across the swap.

It pulls a pool of existing trace ids via search and deletes them at the target rate, refilling as it drains. Run it
during or after the backfill so the traces it deletes have already been copied — that is the leak the bridge prevents.

Prerequisites: `OPIK_URL_OVERRIDE` pointing at the local install. Run `python delete_traffic.py --help` for options.
"""

import signal
import time

import click

from _common import LOGGER, DEFAULT_PROJECT, make_opik_client

_stop = False


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
    started = time.time()
    LOGGER.info("delete traffic: project='%s' tps=%.2f batch=%d duration=%ss (Ctrl-C to stop)",
                project, tps, batch, duration or "∞")

    while not _stop and (duration == 0 or time.time() - started < duration):
        tick = time.time()
        if len(pool) < batch:
            # Exclude both already-deleted ids and those still queued in `pool`, so a refill can't requeue an in-flight id.
            fetched = _fetch_ids(client, project, want=500, exclude=seen | set(pool))
            if fetched is None:
                # transient search failure — back off and retry rather than mistaking it for "no more traces".
                time.sleep(interval if interval > 0 else 0.5)
                continue
            pool.extend(fetched)
            if not pool:
                LOGGER.info("no more traces to delete; stopping")
                break
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
