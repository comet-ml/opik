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

# 4. Make clickhouse-client (used by the runbook driver scripts) resolve to the container's client, forwarding the
#    CLICKHOUSE_* connection env the scripts set. The compose ClickHouse is user/password/db all "opik".
alias clickhouse-client='docker exec -i -e CLICKHOUSE_HOST -e CLICKHOUSE_USER -e CLICKHOUSE_PASSWORD opik-opik-clickhouse-1 clickhouse-client'
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
#    requires the delete DAO to target traces_local first.
$RUNBOOK/scripts/exchange_and_wrap.sh --database opik --skip-wrap
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
cutover, `traces_local_v2` after a rollback) while the live `traces` keeps changing.

## Committing

These are CLI tools (not pytest suites), so they add no CI cost and sit alongside the other `tests_load/tests` scripts.
Drop the directory before the PR if you'd rather keep it local.
