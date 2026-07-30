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

## End-to-end rehearsal

Every migration step runs through a driver script in the runbook's `scripts/` — no SQL is run by hand.

```bash
# 1. Seed a few weeks of history (tune volumes for quick iteration). --bad-ids adds far-future-id rows.
python tests_load/tests/traces-local-v2-cutover/seed_history.py --weeks 6 --per-week 800 --bad-ids 40

RUNBOOK=apps/opik-backend/data-migrations/traces-local-v2-cutover
export CLICKHOUSE_HOST=localhost CLICKHOUSE_USER=opik CLICKHOUSE_PASSWORD=opik

# 2. (Optional) Estimate the backfill ETA for a given config.
$RUNBOOK/scripts/estimate.sh --database opik --max-rows-per-insert 400 --pause-seconds 1

# 3. Generate concurrent write + delete traffic for the duration of the cutover (two more terminals). The overlap
#    naturally produces delete-then-resurrect ids (a delete followed by an update to the same id) — the replay's
#    resurrection guard keeps those live on the destination.
#    NOTE: --bad-ids rows are ordinary deletable traces, so on a small dataset the delete traffic may remove them all
#    before the cutover (they are then bridged + replayed like any delete — 0 leak — a valid path, but the far-future
#    partition won't appear on the successor). To exercise the far-future-partition path specifically, seed with
#    --bad-ids and run the backfill WITHOUT the delete traffic.
python tests_load/tests/traces-local-v2-cutover/live_traffic.py   --tps 5 --duration 150 --update-ratio 0.4
python tests_load/tests/traces-local-v2-cutover/delete_traffic.py --tps 3 --duration 150   # deletes existing (already-backfilled) traces

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

# 7. EXCHANGE (the data cutover; leaves traces a MergeTree so the backend's deletes keep working). It also renames the
#    displaced old data to traces_pre_cutover_backup. --skip-wrap defers the sharding-ready Distributed wrap, which
#    requires the delete DAO to target traces_local first. Pass the backfill_start from step 4. The two --confirm gates
#    are operator assertions that hold trivially in this local rehearsal: --confirm-buffer-raised (there is no async
#    buffer locally — just make sure the traffic scripts have stopped before the swap) and --confirm-retention-paused
#    (retention is disabled by default). --backfill-start drives the final deletion replay run just before the swap.
$RUNBOOK/scripts/exchange_and_wrap.sh --database opik --backfill-start '<backfill_start>' \
    --confirm-buffer-raised --confirm-retention-paused --skip-wrap
$RUNBOOK/scripts/verify.sh --database opik --old-table traces_pre_cutover_backup --new-table traces   # post-swap fidelity
```

**Resetting between iterations depends on how far the last run got.** If you have **not** completed the `EXCHANGE`
(iterating on backfill/delta/verify), truncate **all three** tables and re-seed: `TRUNCATE TABLE traces`,
`TRUNCATE TABLE traces_local_v2`, `TRUNCATE TABLE deletion_events_local`. Also delete the persisted anchor
(`rm -f traces_cutover_backfill_start`) so the next `backfill.sh` captures a fresh `backfill_start` instead of reusing
the prior run's. Truncate the bridge and re-seed the source too, not just the shadow — a prior run leaves stale delete
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

# Stage B — after the EXCHANGE, before the wrap. Generate post-cutover deletes first (traces is still a MergeTree):
python tests_load/tests/traces-local-v2-cutover/delete_traffic.py --tps 4 --duration 30
$RUNBOOK/scripts/rollback.sh --database opik --stage B --cutover-start '<cutover_start>' \
    --confirm-retention-paused --accept-post-cutover-write-loss

# Stage C — after the wrap. Issue the post-cutover deletes BEFORE applying the wrap (a Distributed `traces` rejects
# DELETEs), then roll back:
$RUNBOOK/scripts/rollback.sh --database opik --stage C --cutover-start '<cutover_start>' \
    --confirm-retention-paused --accept-post-cutover-write-loss

# If a stage B/C run's reverse-replay was interrupted, re-apply just it (idempotent):
$RUNBOOK/scripts/rollback.sh --database opik --reverse-replay-only --cutover-start '<cutover_start>' \
    --confirm-retention-paused
```

Check afterwards that no post-cutover-deleted id is live again on the restored `traces` (the reverse-replay's job), and
that the estate is canonical (`traces` = original; for B/C the successor is parked as `traces_post_rollback_backup`,
while stage A just empties the `traces_local_v2` shadow). A rollback leaves `traces` as the Nullable original, so
re-seeding for the next iteration works without a fresh volume.

## Committing

These are CLI tools (not pytest suites), so they add no CI cost and sit alongside the other `tests_load/tests` scripts.
Drop the directory before the PR if you'd rather keep it local.
