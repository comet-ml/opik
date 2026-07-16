"""Emit new traces at a steady rate through the normal SDK — the "live writes during the cutover window" reproducer.

Run it alongside the cutover so the delta-insert has fresh rows to catch. These use the ingestion API, so their
`created_at` is the current week; a share of them are logged as updates to an already-seen trace (a second `end()` with
new content) to exercise the version-bump path the delta relies on.

Prerequisites: `OPIK_URL_OVERRIDE` pointing at the local install. Run `python live_traffic.py --help` for options.
"""

import random
import signal
import string
import time

import click

from _common import LOGGER, DEFAULT_PROJECT, make_opik_client, utcnow

_stop = False


def _handle_sigint(_signum, _frame):
    global _stop
    _stop = True
    LOGGER.info("stopping after the current trace...")


def _text(n: int) -> str:
    return "".join(random.choices(string.ascii_letters + " ", k=n))


@click.command()
@click.option("--project", default=DEFAULT_PROJECT, help="Project name to write into.")
@click.option("--tps", default=5.0, help="Target traces per second.")
@click.option("--duration", default=120, help="How long to run, in seconds (0 = until Ctrl-C).")
@click.option("--update-ratio", default=0.2, help="Fraction of ticks that update a prior trace instead of creating one.")
def main(project, tps, duration, update_ratio):
    signal.signal(signal.SIGINT, _handle_sigint)
    client = make_opik_client()
    interval = 1.0 / tps if tps > 0 else 0.0

    created = 0
    updated = 0
    recent_ids: list[str] = []
    started = time.time()
    LOGGER.info("live traffic: project='%s' tps=%.2f duration=%ss (Ctrl-C to stop)", project, tps, duration or "∞")

    while not _stop and (duration == 0 or time.time() - started < duration):
        tick = time.time()
        if recent_ids and random.random() < update_ratio:
            # Update an existing trace: a new version with a fresh server-side last_updated_at.
            trace_id = random.choice(recent_ids)
            client.trace(id=trace_id, project_name=project, output={"update": _text(120)})
            updated += 1
        else:
            trace = client.trace(
                name="live-trace",
                project_name=project,
                start_time=utcnow(),
                input={"prompt": _text(160)},
                output={"completion": _text(160)},
            )
            trace.end()
            recent_ids.append(trace.id)
            if len(recent_ids) > 500:
                recent_ids.pop(0)
            created += 1

        if (created + updated) % 50 == 0:
            client.flush()
        sleep = interval - (time.time() - tick)
        if sleep > 0:
            time.sleep(sleep)

    client.flush()
    elapsed = time.time() - started
    LOGGER.info("done: created=%d updated=%d in %.1fs (%.2f traces/s effective)",
                created, updated, elapsed, (created + updated) / elapsed if elapsed else 0)


if __name__ == "__main__":
    main()
