"""Emit new traces-with-spans at a steady rate through the normal SDK — the "live writes during the cutover window"
reproducer.

Run it alongside the cutover so the delta-insert has fresh rows to catch. These use the ingestion API, so their
`created_at` is the current week; a share of them are logged as updates to an already-seen span (a second `end()` with
new content) to exercise the version-bump path the delta relies on, and to make the reconciliation's `stale_keys` arm
reachable (a span already on the successor at an older `last_updated_at`, which a presence check would report as clean).

**It writes TRACES as well as spans, on purpose.** A span delete is only ever the cascade of a trace delete, so the
delete generator needs real traces to delete — and a rehearsal whose spans have no traces exercises no deletion path at
all. Each tick creates one trace and a small tree of spans under it.

`--in-progress-ratio` leaves a share of the spans UNENDED (no `end_time`, no `ttft`). That is the only way to reproduce
the pre-swap window's sentinel caveat: while `spanColumnsNonNullable=true` and `spans` is still the Nullable original, an
absent value is written as the epoch/NaN sentinel instead of NULL, and the original's MATERIALIZED `duration` — which
guards only `end_time IS NOT NULL` — turns that into a large NEGATIVE duration that a stage B rollback makes live again
(see the runbook's "The `spanColumnsNonNullable` flip"). Without this option every span is ended, so a rehearsal
produces ZERO affected rows and the caveat plus its rollback repair go untested.

Note the `ttft` arm of that caveat needs no option at all, and is the one that bites hardest on spans: the SDK does not
set `ttft` on an ordinary span, so nearly every span this script writes carries the NaN sentinel once the flag is live.
Expect `sentinel_ttft` to dwarf `sentinel_end_time` in the rollback repair's counts — that is the production shape, not
a rehearsal artifact.

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


def _usage() -> dict:
    prompt = random.randint(1, 40_000)
    completion = random.randint(1, 8_000)
    return {"prompt_tokens": prompt, "completion_tokens": completion, "total_tokens": prompt + completion}


@click.command()
@click.option("--project", default=DEFAULT_PROJECT, help="Project name to write into.")
@click.option("--tps", default=5.0, help="Target traces per second (each carrying --spans-per-trace spans).")
@click.option("--spans-per-trace", default=4, help="Spans per created trace: one root plus children.")
@click.option("--duration", default=120, help="How long to run, in seconds (0 = until Ctrl-C).")
@click.option("--update-ratio", default=0.2,
              help="Fraction of ticks that update a prior SPAN instead of creating a new trace. Keep it non-zero: it "
                   "is what makes the reconciliation's stale_keys arm reachable.")
@click.option("--in-progress-ratio", default=0.0,
              help="Fraction of created spans left UNENDED (no end_time), reproducing the pre-swap window's "
                   "epoch sentinel + negative-duration caveat. 0 disables. Try 0.15 when rehearsing the cutover.")
def main(project, tps, spans_per_trace, duration, update_ratio, in_progress_ratio):
    signal.signal(signal.SIGINT, _handle_sigint)
    client = make_opik_client()
    interval = 1.0 / tps if tps > 0 else 0.0

    traces_created = 0
    spans_created = 0
    spans_updated = 0
    in_progress = 0
    # (trace_id, span_id) pairs: an update needs both, since a span is addressed within its trace.
    recent_spans: list[tuple[str, str]] = []
    started = time.time()
    LOGGER.info("live traffic: project='%s' tps=%.2f spans/trace=%d duration=%ss (Ctrl-C to stop)",
                project, tps, spans_per_trace, duration or "∞")

    while not _stop and (duration == 0 or time.time() - started < duration):
        tick = time.time()
        if recent_spans and random.random() < update_ratio:
            # Update an existing span: a new version with a fresh server-side last_updated_at. end() finalizes the
            # update so it flushes as completed traffic, not just a create-attempt.
            #
            # DELIBERATELY NOT changing parent_span_id. Doing so would make SpanDAO's upsert hit its poison pill and
            # fail the write — which is the product behaving as designed, not something this generator should provoke.
            # The seeder's --parent-poison / --split-parents produce those row shapes directly instead.
            trace_id, span_id = random.choice(recent_spans)
            client.span(id=span_id, trace_id=trace_id, project_name=project,
                        output={"update": _text(120)}, usage=_usage()).end()
            spans_updated += 1
        else:
            trace = client.trace(name="live-trace", project_name=project, start_time=utcnow(),
                                 input={"prompt": _text(160)}, output={"completion": _text(160)})
            root = client.span(trace_id=trace.id, name="live-span-root", project_name=project,
                               start_time=utcnow(), input={"prompt": _text(160)},
                               output={"completion": _text(160)}, usage=_usage())
            root.end()
            spans_created += 1
            recent_spans.append((trace.id, root.id))
            for _child in range(max(spans_per_trace - 1, 0)):
                leave_in_progress = in_progress_ratio > 0 and random.random() < in_progress_ratio
                child = client.span(
                    trace_id=trace.id,
                    parent_span_id=root.id,
                    name="live-span-in-progress" if leave_in_progress else "live-span",
                    type=random.choice(["general", "tool", "llm"]),
                    project_name=project,
                    start_time=utcnow(),
                    input={"prompt": _text(160)},
                    **({} if leave_in_progress else {"output": {"completion": _text(160)}, "usage": _usage()}),
                )
                if leave_in_progress:
                    # Deliberately NOT ended: no end_time, so the write carries an absent value. Do not add it to
                    # recent_spans either — an update would end it and defeat the point.
                    in_progress += 1
                else:
                    child.end()
                    recent_spans.append((trace.id, child.id))
                spans_created += 1
            trace.end()
            traces_created += 1
            if len(recent_spans) > 2000:
                del recent_spans[:len(recent_spans) - 2000]

        if (spans_created + spans_updated) % 50 == 0:
            client.flush()
        sleep = interval - (time.time() - tick)
        if sleep > 0:
            time.sleep(sleep)

    client.flush()
    elapsed = time.time() - started
    LOGGER.info("done: traces=%d spans=%d (of which %d left in-progress) span-updates=%d in %.1fs (%.2f spans/s)",
                traces_created, spans_created, in_progress, spans_updated, elapsed,
                (spans_created + spans_updated) / elapsed if elapsed else 0)
    if in_progress_ratio > 0 and in_progress == 0:
        LOGGER.warning("in-progress-ratio was set but every span was ended — the pre-swap sentinel / negative-duration "
                       "caveat is UNEXERCISED. Raise --in-progress-ratio, --spans-per-trace or --duration.")
    if update_ratio > 0 and spans_updated == 0:
        LOGGER.warning("update-ratio was set but nothing was updated — the reconciliation's stale_keys arm will have "
                       "no rows to classify. Raise --update-ratio or --duration.")


if __name__ == "__main__":
    main()
