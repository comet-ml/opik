# Spans cutover — local simulation tooling

Ad-hoc CLI scripts to stand up a representative dataset and live traffic on a **local** Opik, so the spans cutover
runbook (`apps/opik-backend/data-migrations/spans-local-v2-cutover`) can be rehearsed end to end and iterated on
quickly.

**Rehearse with traffic still flowing through the swap.** The cutover holds writes nowhere, so convergence has to come
from the procedure rather than from stopping traffic: keep the write and delete generators below running across the
delta and the `EXCHANGE`. Quiescing traffic to make the compare converge rehearses an assumption the procedure does
not make.

| Script | What it does | Talks to |
|---|---|---|
| `seed_history.py` | Inserts N traces × M spans per week across several weeks, with back-dated `created_at` and matching-week UUIDv7 ids; optional far-future (`--bad-ids`), non-v7/epoch (`--non-v7-ids`), 40-char-parent (`--parent-poison`) and tied-parent (`--split-parents`) rows | ClickHouse (HTTP) |
| `live_traffic.py` | Emits new traces each carrying a small span tree, at a target TPS (with a share of span updates) — "writes during the cutover window" | SDK / ingestion API |
| `delete_traffic.py` | Deletes **traces** at a target TPS, cascading to their spans — "span deletes during the cutover window", the deletion-bridge exercise | SDK / REST API |

**Why the seeder writes to ClickHouse directly:** the ingestion API stamps `created_at` server-side and treats it as
read-only, so it cannot produce back-dated rows — but the backfill slices the source by `created_at`. Direct inserts are
the only way to get the multi-week history the weekly backfill loop needs. The two traffic scripts use the normal APIs.

**Shared with the traces suite:** client construction, id minting, project discovery and the delete generator itself
live in [`tests_load/tests/cutover_common`](../cutover_common) — there is only one delete endpoint, so both suites
drive the same loop, differing only in what they resurrect afterwards (spans here, traces there). `_common.py` binds it
to this suite's project name; the seeder and the write generator stay local, because their row shapes and traffic
differ.

## What this harness has to do that the traces one did not

Four populations exist here that the traces harness had no counterpart for, and **each one leaves a specific part of the
spans cutover untested if you skip it**. None appears by chance in a short rehearsal.

| flag | population | what it exercises | what goes untested without it |
|---|---|---|---|
| `--bad-ids` | far-future UUIDv7 ids (~2201, the litellm shape) | the **partition spread** one window reaches, and so `max_partitions_per_insert_block` | the cap is never approached; the spread the setting exists for is absent |
| `--non-v7-ids` | UUIDv4 ids → `UUIDv7ToDateTime` returns 1970-01-01 | the **far-PAST** end of that spread (the epoch week) | a bug handling only the far-future direction passes |
| `--parent-poison` | `parent_span_id = leftPad('', 40, '*')` (40 chars) | the copy's **length guard** on a `FixedString(36)` destination | the guard is untested, and its failure mode is `TOO_LARGE_STRING_SIZE` aborting a whole window |
| `--split-parents` | one span id, two parents, **one** `last_updated_at` | the spans-only **version tie** | `verify.sh` never has to report INCONCLUSIVE for the reason unique to this table |

And one structural difference that shapes every script: **a span has no standalone delete**. Every span delete is the
cascade of a *trace* delete (`SpanService.deleteByTraceIds`), which is why `delete_traffic.py` deletes traces, why
`live_traffic.py` creates traces as well as spans, and why the runbook's "quiesce deletes across the swap" means quiesce
*trace* deletes.

> **The seeder's spans hang off `trace_id`s that have no `traces` row.** That is fine for the backfill, the delta and
> the fidelity compare — they only ever read `spans` — but it means `delete_traffic.py` cannot find or delete those
> traces through the API, so seeded history is never the target of a cascade. Run `live_traffic.py` for long enough that
> the delete generator has real traces to work on; its output warns if it resurrected nothing, which is usually this.

## Setup

```bash
# 1. Start Opik locally with host port mapping (exposes ClickHouse on localhost:8123 / :9000) AND span deletion capture
#    on, so the cascade's deletes are recorded in the bridge (the whole point of the exercise):
ANALYTICS_DB_DATA_MODEL_SPAN_DELETION_EVENTS_CAPTURE_ENABLED=true ./opik.sh --port-mapping

# 2. Install the SDK and these scripts' deps.
pip install -e sdks/python
pip install -r tests_load/tests/cutover_common/requirements.txt

# 3. Point the SDK at the local install.
export OPIK_URL_OVERRIDE=http://localhost:5173/api/
export OPIK_WORKSPACE=default

# 4. The driver scripts invoke `clickhouse-client` and read the standard CLICKHOUSE_* env, exactly as in production. Here
#    CLICKHOUSE_HOST (set in the rehearsal below) points at the ClickHouse that --port-mapping exposed on the host's
#    localhost:9000. Provide a clickhouse-client on PATH (matching the server, currently 26.3): either (a) a native
#    official client already on PATH — nothing to do — or (b) the same official-image wrapper the runbook documents,
#    symlinked onto PATH under the name the scripts call (needs only Docker):
mkdir -p ~/bin
ln -sf "$PWD/apps/opik-backend/data-migrations/spans-local-v2-cutover/scripts/clickhouse-client-docker.sh" ~/bin/clickhouse-client
export PATH="$HOME/bin:$PATH"
export CLICKHOUSE_CLIENT_DOCKER_OPTS=--network=host   # (b) only: reach the host-loopback ClickHouse from the container
```

ClickHouse connection defaults (user/password/db all `opik`, host `localhost:8123`) match `--port-mapping`; override via
`OPIK_CH_HOST` / `OPIK_CH_PORT` / `OPIK_CH_USER` / `OPIK_CH_PASSWORD` / `OPIK_CH_DATABASE` if yours differ.

### Changing backend config mid-rehearsal

The runbook's operator-owned step is **backend config plus a restart** (flip `spanColumnsNonNullable`). That flag is read
from a startup snapshot, so a restart is required — `opik.sh` has no flag for it. Recreate just the backend, keeping the
rest of the stack up. Note `opik.sh` runs under compose project **`opik-opik`** (containers are `opik-opik-*`), so pass
`-p opik-opik` or you will silently create a second, parallel project:

```bash
recreate_backend() {   # exports must be set by the caller; unset a var to return it to its default
  docker compose -p opik-opik \
    -f deployment/docker-compose/docker-compose.yaml \
    -f deployment/docker-compose/docker-compose.override.yaml \
    --profile opik up -d --no-deps backend
  until [ "$(docker inspect -f '{{.State.Health.Status}}' opik-opik-backend-1)" = healthy ]; do sleep 3; done
  docker exec opik-opik-backend-1 env | grep -E 'ANALYTICS_DB_DATA_MODEL_SPAN' | sort
}
```

Always re-read the env out of the container afterwards (as above) to confirm the flag actually took — that is the only
check available for `spanColumnsNonNullable`, whose failure mode is silent (see the runbook's prereq #6).

**`spansDistributedWrapEnabled` now exists (OPIK-7799), and this rehearsal still stops before the wrap.** That
matches what a real spans cutover is planned to do: the runbook defers the wrap while the readiness gap OPIK-7799 left
open is unresolved. `exchange_and_wrap.sh` refuses `--wrap-only` without `--confirm-daos-retargeted`, and its refusal
is worth reading once — it is the gate a production operator meets.

## End-to-end rehearsal

Every migration step runs through a driver script in the runbook's `scripts/` — no SQL is run by hand.

```bash
RUNBOOK=apps/opik-backend/data-migrations/spans-local-v2-cutover
export CLICKHOUSE_HOST=localhost CLICKHOUSE_USER=opik CLICKHOUSE_PASSWORD=opik

# 1. Seed a few weeks of history, INCLUDING all four special populations. Tune volumes for quick iteration; the flags
#    matter more than the counts.
python tests_load/tests/spans-local-v2-cutover/seed_history.py \
    --weeks 6 --traces-per-week 120 --spans-per-trace 6 \
    --bad-ids 40 --non-v7-ids 40 --parent-poison 25 --split-parents 10

# 2. Survey and audit. On production this is a prerequisite with its own Go/No-Go boxes; locally it is how you see the
#    audits produce the numbers the runbook says they do. Confirm audit 1 reports far-future AND far-past partitions,
#    and audit 2 reports exactly --parent-poison.
$RUNBOOK/scripts/estimate.sh --database opik --max-rows-per-insert 400 --pause-seconds 1

# 3. Generate concurrent write + delete traffic for the duration of the cutover (two more terminals). Size --duration
#    to cover the WHOLE sequence through the EXCHANGE, not just the backfill: the procedure takes no hold on writes, so
#    traffic flowing across the swap is the condition being rehearsed. Stopping traffic to make verify.sh converge
#    tests an assumption the runbook does not make.
#    --resurrect-ratio is REQUIRED to exercise the replay's resurrection guard (delete a trace, then re-create its spans
#    under the same ids). Do NOT expect the write/delete overlap to produce that by chance.
#    --in-progress-ratio is likewise REQUIRED for the end_time half of the pre-swap sentinel caveat. The ttft half needs
#    no flag at all — the SDK sets no ttft on an ordinary span — so once the flag is live expect sentinel_ttft to dwarf
#    sentinel_end_time. That IS the production shape; do not read it as a rehearsal artifact.
#    Let the delete traffic run PAST the end of the backfill: deleting a trace whose spans are already copied is the
#    leak the bridge exists to close, whereas a delete before they are copied is simply never copied.
python tests_load/tests/spans-local-v2-cutover/live_traffic.py   --tps 6 --spans-per-trace 4 --duration 1200 --update-ratio 0.2 --in-progress-ratio 0.15
python tests_load/tests/spans-local-v2-cutover/delete_traffic.py --tps 2 --duration 1200 --resurrect-ratio 0.05

# 4. Backfill. A small --max-rows-per-insert exercises the adaptive sub-window splitting on modest data. Record the
#    backfill_start it prints.
$RUNBOOK/scripts/backfill.sh --database opik --max-rows-per-insert 400 --pause-seconds 1 \
    --max-partitions-per-insert-block 20000
#    Worth doing once, deliberately: re-run with --max-partitions-per-insert-block 2 and confirm ClickHouse ABORTS the
#    INSERT with TOO_MANY_PARTS rather than degrading. That is the failure the raised cap exists to prevent, and the
#    seeded far-future/non-v7 ids are what make the window reach enough partitions to trigger it.
#    Also worth doing once: re-run a window at --min-insert-block-size-bytes 33554432 and compare peak memory and the
#    part count against the default, in system.query_log and system.parts. That is the one trade-off worth measuring
#    before production — see the runbook's "Partition spread, and the one setting that matters".

# 5. Delta + deletion replay, anchored at that backfill_start.
$RUNBOOK/scripts/delta_replay.sh --database opik --backfill-start '<backfill_start> UTC'

# 6. QA the copy BEFORE the swap: normalized fidelity compare of source vs destination.
$RUNBOOK/scripts/verify.sh --database opik            # --drill-down lists the differing keys of ANY differing window
#    Writes keep arriving throughout, so the current week converges only as far as the last delta reached: re-run
#    delta_replay.sh then verify.sh, and read a PASS as "the copy is faithful as of the last delta", not "nothing can
#    arrive after it". Do NOT stop the traffic to force a clean PASS.
#
#    Re-running only converges a MISMATCH. The other two non-zero verdicts do not resolve by re-copying:
#      * INCONCLUSIVE   a version tie left the winner-picking arbitrary. WITH --split-parents SEEDED, EXPECT THIS —
#                       it is the spans-only tie (one span id, two parents, one version), and the message says so
#                       (version_ties=src:N/dst:0). Copying again does not break the tie.
#      * UNCERTIFIABLE  the tie check did not return counts. A read or client failure, not a data one.
#    "OK -- superseded-version artifact" is a PASS that differs, so it needs no action.
#    Confirm the --parent-poison rows did NOT cause a mismatch: the fingerprint applies the copy's own normalization to
#    the source side, so they must compare equal. A mismatch there is a real bug in one of the two arms.

# 7. MANDATORY CONFIG STEP, and the one most easily skipped in a rehearsal: roll out spanColumnsNonNullable=true
#    BEFORE the EXCHANGE (runbook "The final cutover window"). This is the only restart the spans cutover needs.
#    Skipping it leaves the whole read-side half of the flag unexercised — and its failure mode is SILENT (writes still
#    succeed either way; an absent end_time just reads back as 1970-01-01 instead of null).
export ANALYTICS_DB_DATA_MODEL_SPAN_DELETION_EVENTS_CAPTURE_ENABLED=true \
       ANALYTICS_DB_DATA_MODEL_SPAN_COLUMNS_NON_NULLABLE=true
recreate_backend
#    Positive check (the only real one): an in-progress span must read back end_time = None after the swap.

# 8. Final delta + replay (the last write-facing step), then the EXCHANGE immediately after. The EXCHANGE is the data
#    cutover and leaves spans a MergeTree so the backend's cascade deletes keep working; it also renames the displaced
#    old data to spans_pre_cutover_backup. The wrap is NOT deferred here so much as unavailable — see the setup note.
#    --confirm-retention-paused holds trivially (retention is disabled by default).
$RUNBOOK/scripts/delta_replay.sh --database opik --backfill-start '<backfill_start> UTC'
$RUNBOOK/scripts/exchange_and_wrap.sh --database opik --backfill-start '<backfill_start> UTC' \
    --confirm-retention-paused --skip-wrap
#    The settle gate polls (default 1800s on spans, against the traces runbook's 120s — the thresholds are sized for
#    very large parts, which a local rehearsal does not have). Locally the queue drains immediately and the gate returns
#    at once; that is the early-exit path, not a skipped gate. To see it actually poll, throttle or stop a replica.
#    Record BOTH anchors: the delta_start delta_replay.sh printed and the exchange_done this driver prints (plus
#    cutover_start, for a rollback). Note the CUTOVER INCOMPLETE banner it ends with.
#
#    SIZE IT FIRST, before reconciling. --report-only issues no mutation, and its four counts are the gap's own
#    taxonomy: missing_keys for spans CREATED in the tail, stale_keys for spans UPDATED in the tail (already on the
#    successor at an older last_updated_at). live_traffic.py's --update-ratio is what makes stale_keys reachable.
$RUNBOOK/scripts/reconcile.sh --database opik --report-only \
    --gap-start '<delta_start from step 8> UTC' --swap-done '<exchange_done> UTC'

# 9. RECONCILE — the second half of the data cutover, not a check. Does not exit 0 until its postcondition reports
#    missing_keys=0 stale_keys=0 payload_mismatch_keys=0. A non-zero newer_keys alongside those three is expected.
#    The half worth watching is the one that only shows under traffic: delete_traffic.py is still cascading deletes into
#    live spans throughout, and the gate must NOT abort on that — it gates unfinished mutations on the PARKED table only.
$RUNBOOK/scripts/reconcile.sh --database opik --confirm-retention-paused \
    --gap-start '<delta_start from step 8> UTC' --swap-done '<exchange_done> UTC'

# 10. QA after the sweep. Two different compares, because the swept gap and a fidelity defect live in different weeks:
#
#     (a) THE RECONCILED WINDOW — payload-level fidelity over exactly what step 9 swept.
$RUNBOOK/scripts/verify.sh --database opik --old-table spans_pre_cutover_backup --new-table spans \
    --window-from '<delta_start from step 8>' --window-to '<now>'
#
#     (b) SEALED HISTORY — bounded below the cutover week, where any mismatch IS a defect.
$RUNBOOK/scripts/verify.sh --database opik --old-table spans_pre_cutover_backup --new-table spans --to-week last-sealed

# 11. Leave the config as it is: keep spanColumnsNonNullable=true and span deletion capture ON — capture must stay live
#     through the soak, since the rollback reverse-replay reads the bridge.
```

**Resetting between iterations depends on how far the last run got.** If you have **not** completed the `EXCHANGE`
(iterating on backfill/delta/verify), truncate **all three** tables and re-seed: `TRUNCATE TABLE spans`,
`TRUNCATE TABLE spans_local_v2`, `TRUNCATE TABLE deletion_events_local`. Also delete the persisted anchor
(`rm -f spans_cutover_backfill_start`) so the next `backfill.sh` captures a fresh `backfill_start` instead of reusing
the prior run's. Delete it **only together with truncating `spans_local_v2`** — `backfill.sh` aborts if the anchor is
missing while the destination still holds rows, because that combination means a resume whose original anchor was lost,
and minting a later one would leak deletes. (The default `--state-file` is CWD-relative, so also run the driver from the
same directory each time, or pass an absolute path.) Truncate the bridge and re-seed the source too, not just the shadow
— a prior run leaves stale delete events behind, and a new run whose `backfill_start` is *after* those events will
neither copy nor replay them, so `verify.sh` reports a spurious mismatch. Note the bridge also holds any **traces**
cutover events; those are harmless (every statement filters `source_table = 'spans'`) and truncating removes them too.

**If you have already completed the `EXCHANGE`, truncate + re-seed is not enough** — the swap made `spans` the
non-nullable successor with a `FixedString(36)` `parent_span_id` and an `Int64` `usage`, and `seed_history.py` writes
`NULL` `end_time`/`ttft`, a 40-character `parent_span_id` and `Int32` usage values, at least two of which the successor
rejects outright. Restore the original schema first: **start from a fresh `opik.sh` volume** (re-runs the migrations),
which is the clean reset after any completed cutover.

**Comparing the two tables:** compare **logical** rows, and on spans that means the **destination's** dedup key on both
sides — `SELECT uniqExact(workspace_id, project_id, trace_id, id)`, not the source's own key, which additionally carries
`parent_span_id`. With `--split-parents` seeded the two differ by construction, which is the point of that flag. A
freshly-backfilled `spans_local_v2` also holds un-merged `ReplacingMergeTree` versions (a backfilled row plus its delta
re-copy), so its raw `count()` runs ahead of the long-merged `spans` even when the logical content is identical — that
is exactly what `verify.sh` compares (deduped, mask-honored, per week). The parked backup legitimately diverges from the
live table too: it is a frozen copy while live `spans` keeps changing.

## Rehearsing rollback

Rollback is driven by `rollback.sh`; pick the stage by how far the forward run got. **On spans only stages A and B are
reachable** — stage C and `--unwrap-only` reverse a wrap that OPIK-7799 has not enabled, so no estate can be in the
state they assert. Run them anyway once, to see their topology guards refuse cleanly; that refusal is the tested
behaviour, not the promote.

Stage B re-applies the **reverse deletion replay**, so to exercise it, delete some traces *after* the EXCHANGE — their
spans are bridged with `event_time >= cutover_start`, and the rollback must re-apply them so they do **not** resurrect
on the restored `spans`. Pass the `cutover_start` that `exchange_and_wrap.sh` printed.

**Rehearse the rollback direction with traffic running too**, for the same reason as the forward direction: no path in
either direction holds writes, so the promote and the reverse replay have to be correct against a table that is still
being written to.

```bash
# Stage A — forward run stopped before the EXCHANGE: discards the shadow; live `spans` is untouched.
$RUNBOOK/scripts/rollback.sh --database opik --stage A

# Stage B — after the EXCHANGE. Generate post-cutover activity first, so the rollback has something to reverse.
# --resurrect-ratio matters here too, but for the OPPOSITE reason to the forward replay: a post-cutover
# delete-then-recreate must end up MASKED on the restored original (the reverse replay deliberately carries no
# resurrection guard — rollback discards post-cutover writes while honoring post-cutover deletes).
python tests_load/tests/spans-local-v2-cutover/delete_traffic.py --tps 3 --duration 45 --resurrect-ratio 0.25
python tests_load/tests/spans-local-v2-cutover/live_traffic.py   --tps 4 --duration 30   # -> the discarded writes
$RUNBOOK/scripts/rollback.sh --database opik --stage B --cutover-start '<cutover_start> UTC' \
    --confirm-retention-paused --accept-post-cutover-write-loss

# Stage C / --unwrap-only — confirm they REFUSE. There is no wrapped estate to reverse.
$RUNBOOK/scripts/rollback.sh --database opik --stage C --cutover-start '<cutover_start> UTC' \
    --confirm-retention-paused --accept-post-cutover-write-loss   # expect: "stage C expects the post-wrap state"
$RUNBOOK/scripts/rollback.sh --database opik --unwrap-only --confirm-maintenance   # expect: "traces is engine='...'"

# If a stage B run's reverse-replay was interrupted, re-apply just it (idempotent):
$RUNBOOK/scripts/rollback.sh --database opik --reverse-replay-only --cutover-start '<cutover_start> UTC' \
    --confirm-retention-paused

# The REVERSE reconciliation — the other half of what --accept-post-cutover-write-loss acknowledged discarding.
# This is where the sentinel->NULL denormalization AND the spans-only parent_span_id denormalization are exercised:
# the parked successor stores an absent parent as 36 NUL bytes, and the restored original wants ''. Getting that wrong
# is invisible in SQL (the CAST trims it) but breaks the read path, so spot-check one re-imported root span through the
# API afterwards, not only in ClickHouse.
$RUNBOOK/scripts/reconcile.sh --database opik --report-only \
    --cutover-start '<cutover_start> UTC' --swap-done '<promote_done> UTC'
$RUNBOOK/scripts/reconcile.sh --database opik --confirm-reimport-successor-writes \
    --confirm-retention-paused --cutover-start '<cutover_start> UTC' --swap-done '<promote_done> UTC'
```

**Worth provoking once: the reverse usage-range refusal.** It has no traces counterpart, it is the only narrowing in the
procedure, and its failure mode is a silently wrapped token count. Plant a value the `Int32` column cannot hold and
confirm the driver refuses rather than importing it:

```sql
-- against the PARKED successor, after a stage B rollback
INSERT INTO opik.spans_post_rollback_backup (id, workspace_id, project_id, trace_id, usage, created_at, last_updated_at)
SELECT id, workspace_id, project_id, trace_id,
       map('total_tokens', toInt64(3000000000)), now64(6), now64(6)
FROM opik.spans_post_rollback_backup LIMIT 1;
```

Then re-run the reverse `reconcile.sh`: it must print `usage_out_of_int32_range=1` and **exit 1 without importing**.
`--report-only` must report the same number rather than staying silent.

**Then finish the config half of the rollback** — `rollback.sh` prints both steps, and stage B is not complete without
them. Set `spanColumnsNonNullable=false` and `recreate_backend`; then repair the epoch/NaN sentinels the pre-swap window
wrote into the original, which the promote made live again — including the large **negative** `duration`, which
`verify.sh` cannot see (materialized columns are excluded from the fingerprint) and which a `MATERIALIZE COLUMN` does
**not** fix.

```bash
$RUNBOOK/scripts/rollback.sh --database opik --sentinel-repair-only --confirm-flag-reverted \
    --sentinel-window-from '<flag rolled out, UTC>' --sentinel-window-to '<revert landed everywhere, UTC>'
```

Watch the **sentinel** counters (`sentinel_end_time`, `sentinel_ttft`) reach `0`: those are the repair's actual success
criterion. **Expect `sentinel_ttft` to be very much larger than `sentinel_end_time`** — the SDK sets no `ttft` on an
ordinary span, so nearly every span written while the flag was live carries the NaN sentinel, whereas only in-flight
spans carry the epoch `end_time`. That ratio is the production shape and the driver prints a note saying so; reading it
as a wrong window is the mistake to avoid.

Locally `countIf(duration < 0)` reaches `0` too, but do not carry that expectation into production. It only holds
because seeded and generated spans never end before they start, so every negative duration here is sentinel-caused. A
real table also holds rows whose `end_time` genuinely precedes `start_time` — a pre-existing source artifact this repair
does not address — so there `duration < 0` settles at a non-zero floor while the sentinel counters still go to `0`.

## Committing

These are CLI tools (not pytest suites), so they add no CI cost and sit alongside the other `tests_load/tests` scripts.
