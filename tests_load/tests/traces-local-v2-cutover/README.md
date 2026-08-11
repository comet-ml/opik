# Traces cutover — local simulation tooling

Ad-hoc CLI scripts to stand up a representative dataset and live traffic on a **local** Opik, so the traces
buffered-cutover runbook (`apps/opik-backend/data-migrations/traces-local-v2-cutover`) can be rehearsed end to end and
iterated on quickly.

| Script | What it does | Talks to |
|---|---|---|
| `seed_history.py` | Inserts N traces per week across several weeks, with back-dated `created_at` and matching-week UUIDv7 ids; optional far-future (`--bad-ids`) rows | ClickHouse (HTTP) |
| `live_traffic.py` | Emits new traces at a target TPS (with a share of updates) — "writes during the cutover window" | SDK / ingestion API |
| `delete_traffic.py` | Deletes existing traces at a target TPS — "deletes during the cutover window", the deletion-bridge exercise | SDK / REST API |

**Why the seeder writes to ClickHouse directly:** the ingestion API stamps `created_at` server-side and treats it as
read-only, so it cannot produce back-dated rows — but the backfill slices the source by `created_at`. Direct inserts are
the only way to get the multi-week history the weekly backfill loop needs. The two traffic scripts use the normal APIs.

## Setup

```bash
# 1. Start Opik locally with host port mapping (exposes ClickHouse on localhost:8123 / :9000) AND deletion capture on,
#    so deletes during the cutover window are recorded in the bridge (the whole point of the exercise):
ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true ./opik.sh --port-mapping

# 2. Install the SDK and these scripts' deps.
pip install -e sdks/python
pip install -r tests_load/tests/traces-local-v2-cutover/requirements.txt

# 3. Point the SDK at the local install.
export OPIK_URL_OVERRIDE=http://localhost:5173/api/
export OPIK_WORKSPACE=default

# 4. The driver scripts invoke `clickhouse-client` and read the standard CLICKHOUSE_* env, exactly as in production. Here
#    CLICKHOUSE_HOST (set in the rehearsal below) points at the ClickHouse that --port-mapping exposed on the host's
#    localhost:9000. Provide a clickhouse-client on PATH (matching the server, currently 26.3): either (a) a native
#    official client already on PATH — nothing to do — or (b) the same official-image wrapper the runbook documents,
#    symlinked onto PATH under the name the scripts call (needs only Docker):
mkdir -p ~/bin
ln -sf "$PWD/apps/opik-backend/data-migrations/traces-local-v2-cutover/scripts/clickhouse-client-docker.sh" ~/bin/clickhouse-client
export PATH="$HOME/bin:$PATH"
export CLICKHOUSE_CLIENT_DOCKER_OPTS=--network=host   # (b) only: reach the host-loopback ClickHouse from the container
```

ClickHouse connection defaults (user/password/db all `opik`, host `localhost:8123`) match `--port-mapping`; override via
`OPIK_CH_HOST` / `OPIK_CH_PORT` / `OPIK_CH_USER` / `OPIK_CH_PASSWORD` / `OPIK_CH_DATABASE` if yours differ.

### Changing backend config mid-rehearsal

The runbook's operator-owned steps are **backend config plus a restart** (raise/restore the async-insert buffer, flip
`traceColumnsNonNullable`, flip `tracesDistributedWrapEnabled`). These flags are read from a startup snapshot, so a
restart is required — `opik.sh` has no flag for them. Recreate just the backend, keeping the rest of the stack up. Note
`opik.sh` runs under compose project **`opik-opik`** (containers are `opik-opik-*`), so pass `-p opik-opik` or you will
silently create a second, parallel project:

```bash
recreate_backend() {   # exports must be set by the caller; unset a var to return it to its default
  docker compose -p opik-opik \
    -f deployment/docker-compose/docker-compose.yaml \
    -f deployment/docker-compose/docker-compose.override.yaml \
    --profile opik up -d --no-deps backend
  until [ "$(docker inspect -f '{{.State.Health.Status}}' opik-opik-backend-1)" = healthy ]; do sleep 3; done
  docker exec opik-opik-backend-1 env | grep -E 'ANALYTICS_DB_DATA_MODEL_TRACE|ASYNC_INSERT_BUSY_TIMEOUT_MAX' | sort
}
```

Always re-read the env out of the container afterwards (as above) to confirm the flag actually took — that is the only
check available for `traceColumnsNonNullable`, whose failure mode is silent (see the runbook's prereq #7).

## End-to-end rehearsal

Every migration step runs through a driver script in the runbook's `scripts/` — no SQL is run by hand.

```bash
# 1. Seed a few weeks of history (tune volumes for quick iteration). --bad-ids adds far-future-id rows.
python tests_load/tests/traces-local-v2-cutover/seed_history.py --weeks 6 --per-week 800 --bad-ids 40

RUNBOOK=apps/opik-backend/data-migrations/traces-local-v2-cutover
export CLICKHOUSE_HOST=localhost CLICKHOUSE_USER=opik CLICKHOUSE_PASSWORD=opik

# 2. (Optional) Estimate the backfill ETA for a given config.
$RUNBOOK/scripts/estimate.sh --database opik --max-rows-per-insert 400 --pause-seconds 1

# 3. Generate concurrent write + delete traffic for the duration of the cutover (two more terminals).
#    --resurrect-ratio is REQUIRED to exercise the replay's resurrection guard (delete then re-create under the same
#    id). Do NOT expect the write/delete overlap to produce that by chance: live_traffic.py only updates ids from its
#    own recent-creates buffer, and a 5-minute 5-tps/3-tps run has been observed to yield ZERO resurrections — leaving
#    the one silent-data-loss path in the replay completely unexercised. delete_traffic.py warns if it resurrected none.
#    Let the delete traffic run PAST the end of the backfill: a delete of an already-copied row is the leak the bridge
#    exists to close, whereas a delete before its row is copied is simply never copied (mask-honored INSERT).
#    NOTE: --bad-ids rows are ordinary deletable traces, so on a small dataset the delete traffic may remove them all
#    before the cutover (they are then bridged + replayed like any delete — 0 leak — a valid path, but the far-future
#    partition won't appear on the successor). Simplest fix: seed the --bad-ids rows into their OWN project that the
#    delete traffic does not target (delete_traffic.py takes --project), or run the backfill without delete traffic.
#    --in-progress-ratio is likewise REQUIRED to exercise the pre-swap window's sentinel / negative-duration caveat:
#    without it every trace is ended, so no trace is ever written with an absent end_time and the caveat (plus its
#    rollback repair) produces ZERO affected rows. live_traffic.py warns if it left none in progress.
python tests_load/tests/traces-local-v2-cutover/live_traffic.py   --tps 5 --duration 150 --update-ratio 0.4 --in-progress-ratio 0.15
python tests_load/tests/traces-local-v2-cutover/delete_traffic.py --tps 3 --duration 150 --resurrect-ratio 0.05

# 4. Backfill (small --max-rows-per-insert exercises the adaptive sub-window splitting on modest data). Record the
#    backfill_start it prints.
$RUNBOOK/scripts/backfill.sh --database opik --max-rows-per-insert 400 --pause-seconds 1

# 5. Delta + deletion replay, anchored at that backfill_start.
$RUNBOOK/scripts/delta_replay.sh --database opik --backfill-start '<backfill_start>'

# 6. QA the copy BEFORE the swap: normalized fidelity compare of source vs destination.
$RUNBOOK/scripts/verify.sh --database opik            # add --drill-down to list differing keys on a mismatch
#    Locally there is no async-insert buffer, so in-flight writes may still be settling: once traffic has stopped,
#    re-run delta_replay.sh then verify.sh until it reports "PASSED: all N windows match" (convergence). In production
#    the buffer holds writes during the cutover window instead.

# 7. MANDATORY CONFIG STEP, and the one most easily skipped in a rehearsal: roll out traceColumnsNonNullable=true
#    BEFORE the EXCHANGE (runbook "The final cutover window"), and raise the buffer ceiling so the --confirm-buffer-raised
#    assertion is truthful rather than vacuous. Use recreate_backend() from "Changing backend config mid-rehearsal" above.
#    Skipping this leaves the whole read-side half of the flag unexercised — and its failure mode is SILENT (writes still
#    succeed either way; absent end_time just reads back as 1970-01-01 instead of null).
export ANALYTICS_DB_DATA_MODEL_TRACE_DELETION_EVENTS_CAPTURE_ENABLED=true \
       ANALYTICS_DB_DATA_MODEL_TRACE_COLUMNS_NON_NULLABLE=true \
       ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS=10000
recreate_backend
#    Positive check (the only real one): an in-progress trace must read back end_time = None. Writing one now, while
#    `traces` is still the Nullable original, is also what exercises the pre-swap window's two documented caveats.

# 8. Final delta + replay (the last write-facing step), then the EXCHANGE immediately after. The EXCHANGE is the data
#    cutover and leaves traces a MergeTree so the backend's deletes keep working; it also renames the displaced old data
#    to traces_pre_cutover_backup. --skip-wrap defers the sharding-ready Distributed wrap (step 10).
#    --confirm-retention-paused holds trivially here (retention is disabled by default).
$RUNBOOK/scripts/delta_replay.sh --database opik --backfill-start '<backfill_start>'
$RUNBOOK/scripts/exchange_and_wrap.sh --database opik --backfill-start '<backfill_start>' \
    --confirm-buffer-raised --confirm-retention-paused --skip-wrap
#    Record the cutover_start it prints. Run the post-swap compare NOW, before writes resume — afterwards the current
#    week legitimately diverges (live is a superset of the frozen backup), so bound it with --to-week N instead.
$RUNBOOK/scripts/verify.sh --database opik --old-table traces_pre_cutover_backup --new-table traces

# 9. Restore the buffer ceiling (unset the env var and recreate_backend), keeping traceColumnsNonNullable=true and
#    deletion capture ON — capture must stay live through the soak, since the rollback reverse-replay reads the bridge.
unset ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS; recreate_backend

# 10. OPTIONAL — the deferred Distributed wrap. Flip tracesDistributedWrapEnabled=true FIRST (it retargets trace
#     mutations at traces_local) and re-raise the buffer for --confirm-maintenance. A mismatch here IS fail-loud: with
#     the flag true before the wrap exists, deletes 500 with "Code: 60 ... Table opik.traces_local does not exist" —
#     worth triggering once, to see it. After the wrap, `DELETE FROM opik.traces` returns "Code: 36 DELETE query is not
#     supported", and system.parts relabels from traces to traces_local.
export ANALYTICS_DB_DATA_MODEL_TRACES_DISTRIBUTED_WRAP_ENABLED=true \
       ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS=10000
recreate_backend
$RUNBOOK/scripts/exchange_and_wrap.sh --database opik --wrap-only --confirm-maintenance --confirm-daos-retargeted
unset ANALYTICS_DB_ASYNC_INSERT_BUSY_TIMEOUT_MAX_MS; recreate_backend
```

**Resetting between iterations depends on how far the last run got.** If you have **not** completed the `EXCHANGE`
(iterating on backfill/delta/verify), truncate **all three** tables and re-seed: `TRUNCATE TABLE traces`,
`TRUNCATE TABLE traces_local_v2`, `TRUNCATE TABLE deletion_events_local`. Also delete the persisted anchor
(`rm -f traces_cutover_backfill_start`) so the next `backfill.sh` captures a fresh `backfill_start` instead of reusing
the prior run's. Delete it **only together with truncating `traces_local_v2`** — `backfill.sh` now aborts if the anchor
is missing while the destination still holds rows, because that combination means a resume whose original anchor was
lost, and minting a later one would leak deletes. (The default `--state-file` is CWD-relative, so also run the driver
from the same directory each time, or pass an absolute path.) Truncate the bridge and re-seed the source too, not just the shadow — a prior run leaves stale delete
events (and rows deleted-then-recreated in the previous window) behind, and a new run whose `backfill_start` is *after*
those events will neither copy nor replay them, so `verify.sh` reports a spurious mismatch. A real cutover has no such
residue: `backfill_start` is captured once, before any migration-window activity, so every relevant delete is covered
by the replay.

**If you have already completed the `EXCHANGE`, truncate + re-seed is not enough** — the swap made `traces` the
non-nullable successor, and `seed_history.py` writes `NULL` `end_time`/`ttft` for some rows, which the successor rejects.
Restore the original schema first: **start from a fresh `opik.sh` volume** (re-runs the migrations), which is the clean
reset after any completed cutover.

**Comparing the two tables:** compare **logical** rows (`SELECT uniqExact(workspace_id, project_id, id)`, or `count()
… FINAL`), not raw `count()`. A freshly-backfilled `traces_local_v2` holds un-merged `ReplacingMergeTree` versions
(a backfilled row plus its delta re-copy), so its raw count runs ahead of the long-merged `traces` even when the logical
content is identical — that is exactly what `verify.sh` compares (deduped, mask-honored, per week). The parked backup
also legitimately diverges from the live table: it is a frozen copy (`traces_pre_cutover_backup` after a successful
cutover, `traces_post_rollback_backup` after a rollback) while the live `traces` keeps changing.

## Rehearsing rollback

Rollback is driven by `rollback.sh`; pick the stage by how far the forward run got. Stages B/C re-apply the **reverse
deletion replay**, so to exercise it, delete some traces *after* the EXCHANGE — they are bridged with
`event_time >= cutover_start`, and the rollback must re-apply them so they do **not** resurrect on the restored `traces`.
Pass the `cutover_start` that `exchange_and_wrap.sh` printed.

```bash
# Stage A — forward run stopped before the EXCHANGE: discards the shadow; live `traces` is untouched.
$RUNBOOK/scripts/rollback.sh --database opik --stage A

# Stage B — after the EXCHANGE, before the wrap. Generate post-cutover activity first, so the rollback has something to
# reverse. --resurrect-ratio matters here too, but for the OPPOSITE reason to the forward replay: a post-cutover
# delete-then-recreate must end up MASKED on the restored original (the reverse replay deliberately carries no
# resurrection guard — rollback discards post-cutover writes while honoring post-cutover deletes).
python tests_load/tests/traces-local-v2-cutover/delete_traffic.py --tps 4 --duration 45 --resurrect-ratio 0.25
python tests_load/tests/traces-local-v2-cutover/live_traffic.py   --tps 4 --duration 30   # -> the discarded writes
$RUNBOOK/scripts/rollback.sh --database opik --stage B --cutover-start '<cutover_start>' \
    --confirm-retention-paused --accept-post-cutover-write-loss

# Stage C — after the wrap. The post-cutover deletes can be issued AFTER the wrap: with
# tracesDistributedWrapEnabled=true (which the wrap requires anyway) TraceDAO targets `traces_local`, so the product's
# delete path keeps working — only a DIRECT `DELETE FROM traces` against the Distributed wrapper is rejected (code 36).
python tests_load/tests/traces-local-v2-cutover/delete_traffic.py --tps 4 --duration 45 --resurrect-ratio 0.25
$RUNBOOK/scripts/rollback.sh --database opik --stage C --cutover-start '<cutover_start>' \
    --confirm-retention-paused --accept-post-cutover-write-loss
# Worth triggering once: leave the toggle true after stage C and try a delete — it fails loudly with
# "Code: 60 ... Table opik.traces_local does not exist", the documented inverse mismatch. Then set it false + restart.

# If a stage B/C run's reverse-replay was interrupted, re-apply just it (idempotent):
$RUNBOOK/scripts/rollback.sh --database opik --reverse-replay-only --cutover-start '<cutover_start>' \
    --confirm-retention-paused
```

Check afterwards that no post-cutover-deleted id is live again on the restored `traces` (the reverse-replay's job), and
that the estate is canonical (`traces` = original; for B/C the successor is parked as `traces_post_rollback_backup`,
while stage A just empties the `traces_local_v2` shadow). A rollback leaves `traces` as the Nullable original, so
re-seeding for the next iteration works without a fresh volume.

To verify a rollback, use the **post-rollback table pair** — the `verify.sh` defaults do not apply (`traces_local_v2` is
gone, so a bare run dies with `Unknown table … traces_local_v2`) — and bound it to the sealed weeks, because the current
week legitimately diverges by the post-cutover writes the rollback discarded:

```bash
$RUNBOOK/scripts/verify.sh --database opik --old-table traces --new-table traces_post_rollback_backup --to-week N
```

**Chaining the stages.** Only stage A leaves you able to retry immediately (it truncates the shadow and leaves `traces`
untouched — a re-run of `backfill.sh` reuses the original anchor from the state file). Stages B/C consume the shadow: they
park the successor as `traces_post_rollback_backup` and leave **no** `traces_local_v2`, so retrying the cutover for the
next stage needs `finalize.sh --confirm` to recycle that backup back into an empty shadow — the irreversible step — or a
fresh volume. So rehearsing all three stages takes one recycle (or reset) between B and C.

**Then finish the config half of the rollback** — `rollback.sh` prints both steps, and stage B/C is not complete without
them (runbook: "Rolling back the `traceColumnsNonNullable` flip"). Set `traceColumnsNonNullable=false` and
`recreate_backend`; then repair the epoch/NaN sentinels the pre-swap window wrote into the original, which the promote
made live again — including its large **negative** `duration`, which `verify.sh` cannot see (materialized columns are
excluded from the fingerprint) and which a `MATERIALIZE COLUMN` does **not** fix. Confirming `countIf(duration < 0)`
goes from non-zero to `0` across that repair is the point of rehearsing it. After the wrap, also flip
`tracesDistributedWrapEnabled` back to `false` before traffic resumes, or deletes 500 against the now-parked
`traces_local`.

## Committing

These are CLI tools (not pytest suites), so they add no CI cost and sit alongside the other `tests_load/tests` scripts.
Drop the directory before the PR if you'd rather keep it local.
