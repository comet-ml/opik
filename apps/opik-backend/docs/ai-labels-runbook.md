# ai-labels runbook

ai-labels labels every ended trace in opted-in workspaces with AI-generated labels, computed by opik-inference. It
runs only where `AI_LABELS_ENABLED=true`, and is off by default everywhere, OSS included. Nothing stops an OSS install
from turning it on, but it needs an opik-inference deployment and its credential, which only Comet-hosted deployments
have.

## Turning it on for a deployment

| Env var | Value |
|---|---|
| `AI_LABELS_ENABLED` | `true` |
| `AI_LABELS_INFERENCE_URL` | the opik-inference cell, e.g. `https://opik-inference.comet.com` |
| `AI_LABELS_INFERENCE_CREDENTIAL` | the deployment's opik-inference credential, from a secret (`component.backend.secretRefs`) |

- The backend refuses to start with ai-labels on unless `ANALYTICS_DB_DATA_MODEL_TRACE_COLUMNS_NON_NULLABLE=true`
  (the cut-over `traces` table). The `spans` cutover doesn't need to be done.
- opik-inference: the deployment needs its own `deployments` entry and credential in `config/inference.yaml`, with
  `allowed_orgs: "*"`. Opik sends workspace ids as `org_id`, so a real-org allow-list answers 403 `org_not_allowed`.
- Every other `aiLabels` setting is in `apps/opik-backend/config.yml` and overridable as `AI_LABELS_*`.

## Opting workspaces in and out

Opt-in is the `ai_label_workspaces` table in the state database (MySQL). There is no API.

Before enabling a workspace, check how much it will backfill: its traces in the horizon (7 days by default). Traces
created before a task's `applies_from` are never labeled.

```sql
-- ClickHouse
SELECT count()
FROM traces
WHERE workspace_id = '<workspace_id>'
  AND created_at >= now64(6) - INTERVAL 7 DAY;
```

The pool's throughput binds first: one L40S on the 4B model labels about 3 traces a second at 7K tokens each (22K
tokens/s), and a trace with large spans costs up to 50 times a small one. The sweep takes the newest traces first, so a
backfill works backward from now, and when the pool can't keep up the oldest traces age out of the horizon unlabeled
(`ai_labels.unsettled` stops shrinking). The global budget (`AI_LABELS_BUDGET_BYTES`, about 3 minutes of the pool by
default) bounds how much waits in opik-inference, and is shared out evenly between the workspaces that have traces to
label.

```sql
-- MySQL: enable (or re-enable) a workspace
INSERT INTO ai_label_workspaces (workspace_id, enabled, added_by)
VALUES ('<workspace_id>', TRUE, '<your name>')
ON DUPLICATE KEY UPDATE enabled = TRUE, added_by = '<your name>';

-- MySQL: disable a workspace. Immediate: the next sweep drops it. Jobs in flight still load, and labels stay.
UPDATE ai_label_workspaces SET enabled = FALSE WHERE workspace_id = '<workspace_id>';

-- MySQL: list the enabled workspaces
SELECT workspace_id, added_at, added_by FROM ai_label_workspaces WHERE enabled = TRUE ORDER BY added_at;
```

## Reading summaries

There is no UI yet. Take each trace's latest label (by write time):

```sql
-- ClickHouse
SELECT trace_id, task_version, JSONExtractString(output, 'summary') AS summary
FROM ai_labels
WHERE workspace_id = '<workspace_id>'
  AND project_id = '<project_id>'
  AND task = 'trace_summary'
  AND key = 'output'
ORDER BY workspace_id, project_id, task, trace_id, key, updated_at DESC
LIMIT 1 BY workspace_id, project_id, task, trace_id, key;
```

## What to watch

| Metric | Meaning |
|---|---|
| `ai_labels.unsettled_past_slo` | Unsettled (trace, task) pairs past the SLO, per workspace. The headline. |
| `ai_labels.budget.in_flight` / `.limit` | Cost in flight against the budget. Pinned at the limit means the pool is the bottleneck. |
| `ai_labels.unsettled` | Unsettled (trace, task) pairs in the horizon: the backlog. |
| `ai_labels.tick.phase_seconds` by `phase` | Time in load, sweep and submit. Loading stops starting jobs at 40% of the tick budget. |
| `ai_labels.tick.interval_seconds`, `.budget_seconds` | The tick cadence and the time a tick may use, as configured (`AI_LABELS_TICK_INTERVAL`, `AI_LABELS_TICK_BUDGET`). Loading stops at 40% of the budget, the sweep at 70%, submit at all of it. |
| `ai_labels.sweep.buckets` / `.buckets_reached` | N as configured vs the buckets the latest sweep got through before its share of the tick budget. Fewer than N means the sweep ran out of time (`ai_labels.sweep.overrun`); the first bucket swept rotates, so none starves. |
| `ai_labels.sweep.candidates` / `ai_labels.tick.workspaces` | Traces the latest sweep found to label, and the workspaces they span. Many candidates but few admitted (`ai_labels.submit.traces`) means the budget, not the sweep, bounds throughput. |
| `ai_labels.jobs.submitted` by `endpoint` | Jobs opik-inference accepted (202) and the tick recorded as open. Flat while `ai_labels.sweep.candidates` is high means submits fail: see `ai_labels.submit.throttled`, `.rejected` and the logs. |
| `ai_labels.jobs.open` | Jobs submitted and not yet loaded, as the leader last knew them. Growing while `ai_labels.items.done` is flat means jobs finish slower than they start, or never: the pool, or the service, is behind. |
| `ai_labels.jobs.oldest_open_seconds` | How long the oldest open job has been out, measured at scrape time. Past a few tick intervals the pipeline is stuck: the job is hung at the service, or the leader stopped ticking (the gauge then keeps growing on the last leader). 0 with none open. |
| `ai_labels.render.traces` / `ai_labels.submit.traces` | Traces rendered vs admitted. Far apart means renders are wasted: raise `AI_LABELS_MIN_ITEM_BYTES` toward a typical item. |
| `ai_labels.items.failed` by `error_code` | `render_over_budget`, `render_error`, `item_too_large`, `job_lost`, `missing_result`, `missing_answer`, or the service's code. |
| `ai_labels.submit.rejected` | A submit rejected with 400: a renderer or task bug. |
| `ai_labels.sweep.guard_trips` | A sweep query hit a ClickHouse guard: raise `AI_LABELS_SWEEP_BUCKETS`. |
| `ai_labels.render.compaction_level`, `.bytes` | How far traces had to be compacted to fit. |
| `ai_labels.render.history_turns`, `.history_bytes` | The earlier thread turns left in each fitted history, and their size. |
| `ai_labels.render.history_errors` | Failed reads of earlier turns. The chunk's render fails and is retried as `render_error`. |
| `ai_labels.render.duration_seconds` by `outcome` | Wall time per chunk (ClickHouse reads + render): `ok`, or `one_at_a_time` after a chunk read failed. |
| `ai_labels.render.cpu_seconds` | Java CPU spent rendering, excluding ClickHouse reads; its rate is cores. Spec 10 budgets about 0.25 core of Opik CPU per tick. Emitted only when the JVM supports and enables current-thread CPU time: no series means no measurement, not zero CPU or a met budget. ClickHouse's side is in `system.query_log`, where `log_comment` starts with `ai-labels:render:` (filter `log_comment LIKE 'ai-labels:render:%'`). |

## Topic vectors

The topic vectors job stores one bge-small vector (384 numbers, unit length) for every trace that has a `done`
`trace_summary` label. It embeds the summary text through opik-inference `/v1/embed` and writes the row to the
ClickHouse table `topic_vectors`, one row per (trace, task version). It is off by default everywhere.

### Opting workspaces in and out

Topics have their own opt-in, the `topic_workspaces` table in the state database (MySQL). It works like
`ai_label_workspaces`, and there is no API. Topics read the ai-labels `trace_summary`, so a workspace runs topics only
when it is enabled in **both** tables. Opting a workspace in to ai-labels does not turn topics on.

```sql
-- MySQL: enable (or re-enable) topics for a workspace. It must also be enabled in ai_label_workspaces.
INSERT INTO topic_workspaces (workspace_id, enabled, added_by)
VALUES ('<workspace_id>', TRUE, '<your name>')
ON DUPLICATE KEY UPDATE enabled = TRUE, added_by = '<your name>';

-- MySQL: disable topics for a workspace. The next tick drops it. Stored vectors stay.
UPDATE topic_workspaces SET enabled = FALSE WHERE workspace_id = '<workspace_id>';

-- MySQL: the workspaces topics run for
SELECT t.workspace_id, t.added_at, t.added_by
FROM topic_workspaces t JOIN ai_label_workspaces a ON a.workspace_id = t.workspace_id
WHERE t.enabled = TRUE AND a.enabled = TRUE ORDER BY t.added_at;
```

A workspace opted in after its traces were labeled gets vectors for the last 7 days (the long window). For older
labels, see "Backfill".

### Turning it on

| Env var | Default | Meaning |
|---|---|---|
| `TOPICS_ENABLED` | `false` | Runs the job. The backend refuses to start with it on unless `AI_LABELS_ENABLED=true` and `AI_LABELS_INFERENCE_URL` is set: it uses the same opik-inference URL and credential. |
| `TOPICS_VECTORS_TICK_INTERVAL` | `1m` | How often the job ticks. At most one tick runs per interval across all pods (see "How ticks run"). |
| `TOPICS_VECTORS_TICK_BUDGET` | `50s` | Every query and embed call of a tick starts within this. Must be under the tick interval. |
| `TOPICS_VECTORS_WINDOW` | `24h` | How far back, by `ai_label_status` write time, a normal tick looks. Never before the task's `applies_from`. |
| `TOPICS_VECTORS_LONG_WINDOW` | `7d` | The window of the long-window pass, for rows a normal tick missed. At least `TOPICS_VECTORS_WINDOW`. |
| `TOPICS_VECTORS_LONG_WINDOW_INTERVAL` | `1d` | How often the long-window pass starts. The pass stays on until it drains (see "How ticks run"); the interval runs from the tick that drained it. |
| `TOPICS_VECTORS_BACKFILL_WINDOW` | `0s` (off) | When above zero, the long-window pass uses this window instead of `TOPICS_VECTORS_LONG_WINDOW`. 0, or at least `TOPICS_VECTORS_WINDOW`; at most `365d`. See "Backfill". |
| `TOPICS_VECTORS_MAX_TRACES_PER_TICK` | `5000` | The most traces one tick embeds **per workspace**, oldest first. The tick budget bounds the total. The rest wait for the next tick. |
| `TOPICS_VECTORS_EMBED_BATCH_SIZE` | `256` | Texts per embed call. 256 is the most `/v1/embed` takes. |
| `TOPICS_VECTORS_EMBED_TIMEOUT` | `10s` | Each embed call's deadline. A chunk starts only with at least this much of the tick budget left; the rest is `over_budget`. Under the tick budget, at most 60s. |
| `TOPICS_VECTORS_FIND_MAX_ROWS_TO_READ` | `30000000` | ClickHouse `max_rows_to_read` of one workspace's window read. A trip fails that workspace's read for the tick. |
| `TOPICS_VECTORS_FIND_MAX_MEMORY_USAGE` | `1073741824` | ClickHouse `max_memory_usage` of one window read (1 GiB). |
| `TOPICS_VECTORS_FIND_MAX_EXECUTION_TIME` | `30s` | ClickHouse `max_execution_time` of one window read, cut to the time left in the tick. At most `TOPICS_VECTORS_TICK_BUDGET`. |
| `TOPICS_VECTORS_FIND_CREATED_AT_SLACK` | `1d` | The window read skips traces created before its lower bound minus `AI_LABELS_HORIZON` minus this margin. The margin covers the time from the ai-labels sweep to the status write. |

To turn it off, set `TOPICS_ENABLED=false` and restart. Stored vectors stay. When it is turned on again, the next
ticks write the vectors that are missing in the window, and the long-window pass writes the ones in the long window.
Older ones need a backfill.

### How ticks run

- Every pod fires the job every tick interval, each at its own second. Two Redis locks make that one tick per interval
  across the cluster:
  - `topics:vectors:leader` is held for the tick and released when it ends, so two ticks never overlap. Its lease is
    tick budget + embed timeout + find max execution time + 5s (95s by default). It matters only when the pod dies
    mid-tick: the next tick starts when the lease expires.
  - `topics:vectors:tick` is taken by the leader before it ticks and never released. It expires one interval (less up to
    1s for trigger jitter) after the tick started, so a pod that fires sooner skips.
- A skipped tick logs `Topic vectors tick skipped, lock '<name>' ...` at INFO. With several pods, expect most firings
  to skip on `topics:vectors:tick`. Ticks skipped on `topics:vectors:leader` for longer than one lease mean a tick
  is stuck.
- Each tick reads the enabled workspaces in turn, starting at an offset that moves by one every interval, so no
  workspace is always last. A read starts only with at least 1s of the budget left. A workspace that is not reached in
  time is read the next tick (one WARN).
- After an embed call fails with a 429 or a 5xx (whatever the error code), a timeout or a retryable code, the job stops
  calling for **that workspace** for the rest of the tick (`embed_failed`). Other workspaces go on.
- **Long-window pass.** Once per `TOPICS_VECTORS_LONG_WINDOW_INTERVAL` a tick uses the long window (Redis key
  `topics:vectors:long_window`). The pass stays on, tick after tick, until one of its ticks drains it: every workspace
  read with no failure, each under the per-workspace cap, and no candidate left for want of time (`over_budget`), a
  read (`read_failed`), a back-off or an insert (`insert_failed`). Then the interval starts. After an outage longer than
  the short window, the pass therefore runs every tick until the backlog in the long window is written. A summary that
  makes `/v1/embed` fail with a 5xx every time keeps the pass on until its status leaves the long window: each pass
  tick costs one long-window read per workspace.

### Backfill

The job only looks back as far as the long window (7 days). To write vectors for older labels, for example when
topics is turned on weeks after ai-labels, or a workspace is opted in to topics later than to ai-labels:

1. Set `TOPICS_VECTORS_BACKFILL_WINDOW` to the age to cover (for example `30d`), and restart.
2. Delete the Redis key `topics:vectors:long_window` to start the pass now. Otherwise it starts at the next interval.
3. Watch `topics.vectors.long_window.missed` and `topics.vectors.written`. The pass stays on until it drains.
4. Set `TOPICS_VECTORS_BACKFILL_WINDOW=0s` again, and restart.

Cost: the window read's cost grows with the window, not with what it finds. In the load test (one workspace, 2.07M
statuses) a 24h read took 220–440 ms and read 0.9–2.7M rows; a 7d read took 570–750 ms, read 2.4–5.0M rows and used
about 300 MiB. A 30d or 90d read of a large workspace reads proportionally more and can trip
`TOPICS_VECTORS_FIND_MAX_ROWS_TO_READ` or `..._MAX_MEMORY_USAGE`; the read then fails every pass tick for that workspace
(`topics.vectors.read.failures{phase=find}`). Raise the guards for the backfill, or backfill in steps (7d, 14d, 30d).
While the pass is on, every tick does this read for every enabled workspace.

### Checking that vectors flow

Run both queries for one workspace. The counts must match, up to the traces labeled since the last tick. A gap that
stays means the job skips traces: see `topics.vectors.skipped` and the logs.

```sql
-- ClickHouse: traces with a done trace_summary label in the last 24 hours (latest status per trace, as the job reads it)
SELECT count()
FROM (
    SELECT status, updated_at
    FROM ai_label_status
    WHERE workspace_id = '<workspace_id>'
      AND task = 'trace_summary'
    ORDER BY workspace_id, project_id, task, trace_id, updated_at DESC
    LIMIT 1 BY workspace_id, project_id, task, trace_id
)
WHERE status = 'done'
  AND updated_at >= now64(6) - INTERVAL 24 HOUR;

-- ClickHouse: vectors for the same traces
SELECT count()
FROM (
    SELECT trace_id, task_version
    FROM topic_vectors
    WHERE workspace_id = '<workspace_id>'
      AND (project_id, trace_id, task_version) IN (
          SELECT project_id, trace_id, task_version
          FROM (
              SELECT project_id, trace_id, task_version, status, updated_at
              FROM ai_label_status
              WHERE workspace_id = '<workspace_id>'
                AND task = 'trace_summary'
              ORDER BY workspace_id, project_id, task, trace_id, updated_at DESC
              LIMIT 1 BY workspace_id, project_id, task, trace_id
          )
          WHERE status = 'done'
            AND updated_at >= now64(6) - INTERVAL 24 HOUR
      )
    ORDER BY workspace_id, project_id, trace_id, task_version, updated_at DESC
    LIMIT 1 BY workspace_id, project_id, trace_id, task_version
);
```

### What to watch

| Metric | Meaning |
|---|---|
| `topics.vectors.oldest_unvectored_seconds` by `workspace_id` | Age of the oldest done label without a vector that the last tick found in its window. The headline. It grows when the job falls behind or stops. Only the pod that ticked within the last two tick intervals publishes it, so read it with `max` across pods. A workspace whose window read failed, or was not reached, has no value for that tick: watch `topics.vectors.read.failures` with it. It sees only the window: after an outage longer than the short window it stays near the window's length until the long-window pass runs. |
| `topics.vectors.written` | Vectors stored. |
| `topics.vectors.skipped` by `reason` | `missing_summary` (the output row the window read saw was gone or of another task version by the summary read, which usually clears on the next tick; a WARN per workspace per tick names the count and the oldest trace), `embed_failed` (the call failed, or the workspace backed off: the next tick retries), `invalid_vector` (a missing, null, zero or wrong-dimension vector), `over_budget` (not enough of the tick left to start a call: the next tick continues), `insert_failed` (the ClickHouse insert failed: the next tick embeds them again), `read_failed` (the summary read failed: the next tick reads them again). |
| `topics.vectors.read.failures` by `phase`, `workspace_id`, `error_type` | Failed ClickHouse reads: `find` (one workspace's window read, for example a guard trip) or `summaries` (one workspace's summary read). `error_type` is the root cause's class name. |
| `topics.vectors.embed.failures` by `http_status`, `code`, `error_type` | Failed embed calls. 429 `quota_exceeded` means the embed pool is full. |
| `topics.vectors.embed.duration_seconds` by `outcome` | Wall time per embed call: `ok`, or the error code. |
| `topics.vectors.tick.phase_seconds` by `phase` | Time in `find` (ClickHouse window reads) and `embed` (summary reads, embed calls and inserts). |
| `topics.vectors.long_window.missed` | Vectors the long-window pass wrote for statuses older than the short window: rows normal ticks missed, for example after an outage longer than the window. Above zero outside a backfill or an outage means normal ticks miss rows. |

The window read leaves out a done status whose latest output is missing, of another task version, or empty or only
whitespace. Such a status gets no vector and is not counted, and it does not take a place in the per-workspace cap, so
it never holds back newer traces.

ClickHouse queries of the job have a `log_comment` that starts with `topics:`.

### Deletes

A vector lives as long as its trace. Retention deletes vectors with the traces (the same rules as `ai_labels`), and a
trace delete deletes its vectors. There is no project or workspace delete path, the same as for `ai_labels`: deleting
a project or a workspace leaves its vectors until retention removes them. A trace deleted between a tick's read and its
insert (at most one tick) can leave a vector row behind, exactly like `ai_labels`. The retention sweep removes such rows
by trace id range and week.

A trace delete deletes the vectors before the ai-labels rows, each on its own, so neither failure skips the other.
If the ai-labels delete fails, the trace's `done` status stays in `ai_label_status`, and the next tick writes its vector
again from the label (the job reads only `ai_label_status` and `ai_labels`). Retention removes both, in workspaces with
a retention policy.

## Topics nightly job

Once a night the job builds or updates each project's topic list from its topic vectors. A project without live topics
gets a first build; a project with live topics gets a nightly update (see [Nightly update](#nightly-update)). It reads
the same workspaces as ai-labels, and runs only with `TOPICS_ENABLED=true`.

A first build reads the project's window (the 30 whole UTC days before the night, the newest 30,000 traces at most),
merges identical intent texts, groups them with k-means (15 to 50 groups), asks Qwen through opik-inference
`/v1/generate` to name up to 10 topics over the groups, and assigns every window trace to its nearest topic prototype
or to Other. It writes:

| Table | Database | What |
|---|---|---|
| `topics` | MySQL | One row per topic: name, definition, `born` (the night), `size` (window traces), and the nightly update's lifecycle columns: `low_nights`, `lo7`, `hi7`, `inactive_since`, `retired_at`, `retired_reason`. A retired topic keeps its row; its id is never reused. |
| `topic_prototypes` | MySQL | The vectors traces are assigned by, several per topic, and the known-Other ones (`topic_id` null). |
| `topic_project_states` | MySQL | One row per project with a saved list: `rebuilds` (saved nights), `last_run_id`, `window_until`. |
| `topic_runs` | MySQL | One row per run: `running`, then `saved` or `failed` with `error_code`. Kept 90 days. |
| `trace_topics` | ClickHouse | One row per window trace and run: its `topic_id`, the all-zeros id for Other. The latest row wins; a failed run deletes its rows (best effort). |

### Turning it on and off

| Env var | Default | Meaning |
|---|---|---|
| `TOPICS_NIGHTLY_ENABLED` | `true` | Runs the job. Needs `TOPICS_ENABLED=true`. |
| `TOPICS_NIGHTLY_SCHEDULE` | `0 0 2 * * ?` | Quartz cron, UTC. The night is the UTC date the job starts on. |
| `TOPICS_NIGHTLY_BUDGET` | `2h` | No project starts after this; the rest run the next night. |
| `TOPICS_NIGHTLY_PROJECT_TIMEOUT` | `15m` | One run is stopped and failed with `timeout` after this. Under the budget. |
| `TOPICS_NIGHTLY_MIN_VECTORS` | `100` | A project is built when its window holds at least this many traces with a vector. |
| `TOPICS_NIGHTLY_WINDOW_DAYS` | `30` | The window, in whole UTC days before the night. |
| `TOPICS_NIGHTLY_MAX_VECTORS` | `30000` | A larger window keeps its newest traces. |
| `TOPICS_NIGHTLY_QWEN_DEADLINE` | `120s` | The deadline of the first build's author call and the nightly update's revise call. The verify (60s) and naming (30s) deadlines are fixed. Every call uses the `batch` lane, which takes up to 300s. |
| `TOPICS_NIGHTLY_RUNS_RETENTION` | `90d` | `topic_runs` rows are deleted after this. |

To turn it off, set `TOPICS_NIGHTLY_ENABLED=false` and restart. Saved lists stay and freeze at their last saved night.
This is also the rollback of the nightly update.

To stop it for one workspace without a restart, opt the workspace out (`UPDATE topic_workspaces SET enabled = FALSE
WHERE workspace_id = '<workspace_id>'`). This also stops its topic vectors. The next night skips it; a night already
running reads the workspaces once, at its start, so it still runs the workspace's projects.

### How a night runs

- Every pod fires the job on the cron. The first to take the Redis lock `topics:nightly:lock` runs the night and keeps
  the lock until it expires (budget + project timeout + 5 minutes), so no other pod runs the same night. If that pod
  dies during the night (a deploy), the rest of the night waits for the next night: the cron does not fire again, and
  the night endpoint answers `409` until the lock expires. To finish the night sooner, delete the Redis key
  `topics:nightly:lock` and call the night endpoint (see "Running by hand").
- The night first fails runs left `running` by an earlier night (`error_code = 'abandoned'`), and deletes runs older
  than the retention.
- It counts each enabled workspace's projects in the window, drops those under `TOPICS_NIGHTLY_MIN_VECTORS`, and runs
  the rest smallest first, one at a time: a `nightly_update` for a project with live topics, else a `first_build`.
- Each run holds the Redis lock `topics:project:<project_id>`, so a run started by hand and the night never build the
  same project at once.
- A first build under 1,000 traces is saved with `"provisional": true` in its stats.

A failed run keeps only its `topic_runs` row: when its save fails after the `trace_topics` insert, it deletes those
rows again (best effort). The project runs again the next night. A skipped or failed
night does not count: the lifecycle counters and `rebuilds` change only on a saved run.

| `error_code` | Meaning |
|---|---|
| `qwen_timeout` | A Qwen call ran out of its deadline: `TOPICS_NIGHTLY_QWEN_DEADLINE` for author and revise, 60s for verify, 30s for naming. |
| `qwen_error` | A Qwen call (author, revise, verify or naming) failed after its 2 retries, or with a 4xx. `error_message` has the call's kind, code and HTTP status. |
| `qwen_bad_answer` | The author or revise answer is not a JSON object, or the author answer breaks the schema. Or opik-inference answered `schema_violation` to any call: a cut or off-schema answer, which is not retried. The answer is in the DEBUG logs. |
| `window_read` | The ClickHouse window read failed, the window had no usable vector, or the nightly update could not read last night's labels. |
| `invariant` | A check before the save failed: more than 10 live topics, a retired id live again, a renamed topic, a new topic not born tonight, a blank or catch-all name, a prototype not of unit length, a trace not labelled once or labelled with a retired topic. |
| `timeout` | The run outlasted `TOPICS_NIGHTLY_PROJECT_TIMEOUT`, or the job was interrupted. |
| `save` | The ClickHouse insert or the MySQL transaction failed, or another run saved the project meanwhile (`error_message` says so; only if the project's Redis lock was lost). The run deletes the `trace_topics` rows it inserted; if that delete fails too (a WARN names the run), they stay until the next saved run replaces them. |
| `abandoned` | The pod stopped during the run. |
| `internal` | A bug: read the logs for the run id. |

### Nightly update

A project with live topics keeps its list and updates it. The run reads the window and the saved list (live topics,
their prototypes, the known-Other prototypes, `rebuilds`), then:

1. On a review night the known-Other prototypes are dropped: the Other memory expires weekly.
2. Every window trace goes to its nearest prototype at cosine 0.75: a topic, known Other, or unassigned.
3. Each topic's prototypes move to its members of tonight (k-means, 1 to 10 prototypes by member count); so do the
   known-Other ones.
4. When 20 or more traces are unassigned, they are grouped by k-means. A group of 20 or more traces is a candidate. A
   candidate is big when its share of the window, or of the last 7 days, is 2% or more.
5. Qwen is called only when there is a big candidate or it is a review night. Most nights make no call.
   - `revise` (`revise_v4.md`, or `revise_review_v4.md` on review nights): assign candidates to topics, create new
     topics (not when the list holds 10), send candidates to Other; on review nights also merge, retire and redefine
     topics. A review never retires a topic with a 2% share or more (overall or in the last 7 days): such a topic
     can only be merged or redefined. A failed revise fails the run.
   - `verify` (`verify_batch.md`): one call checks every candidate given to a topic. A rejected or unanswered pair is
     undone. An unreadable verify answer undoes every pair and the run goes on. A failed verify call fails the run:
     read as "no pair verified", it would turn sound assigns into forced births.
   - `naming` (`naming_group.md`), one call per night for every big candidate left without a topic and tight enough
     (0.80). Qwen sees the live topics and may give a candidate to one of them, put same-kind candidates into one new
     topic, or send a candidate to Other. A candidate given to a topic is checked by one more verify call
     (`verify_naming`); a rejected one goes to a second call (`naming_new`, `naming_group_new.md`) that offers a new
     topic or Other only. A new name equal to a live topic's (case and spaces ignored) joins that topic, and equal new
     names are one topic. On a full list a new topic replaces the topic (not born tonight) with the lowest 7-day
     share, if that share is below the sum of its candidates' 7-day shares. A loose candidate, a full list with no
     such topic, a catch-all or blank name, or a candidate the answers leave out goes to known Other, and the run goes
     on. A failed naming call fails the run.
6. The centre of each candidate given to a topic becomes one more prototype of that topic; one given to Other becomes
   a known-Other prototype.
7. Every window trace is assigned again, as in the first build (cosine 0.75, the 0.95 gate on known Other).
8. Lifecycle, then a reassignment when a topic was retired:
   - `low_nights` counts saved nights in a row under 0.5% of the window; at 3 the topic is retired (`low_share`). A
     topic born after the first build is measured from 7 days before its birth (newborn protection).
   - More than 10 live topics: the smallest is retired (`cap`).
   - Inactive is a display state, not a retirement: 3 nights in a row under 0.5% of the last 7 days set
     `inactive_since` (`lo7` counts them); 2 nights at 1% or more clear it (`hi7`). An inactive topic still counts
     traces and can still be retired.
   - New is a display state too: born less than 7 days ago.

`retired_reason` is one of `low_share`, `cap`, `merged` (dropped by a review night's merge), `review` (retired by a
review night's answer) or `replaced` (replaced by a forced new topic on a full list).

The save writes tonight's `trace_topics` rows, then in one MySQL transaction: tonight's new topics, the changed
columns of the topics it read, the project's prototypes (replaced), and `rebuilds` + 1.

#### Review nights

A review night is a run whose `rebuilds` (saved nights before it) is a multiple of 7, above 0: every 7th saved night.
On a review night the known-Other memory is dropped, and Qwen gets the review prompt, which may also merge, retire and
redefine topics (a redefine keeps the name). A run is also a review when it has a big candidate and the list already
holds 10 topics (`"review": true`, `"review_night": false` in its stats).

To force a review night by hand, set `rebuilds` to the next multiple of 7. The next saved night is a review night, and
the weekly cadence moves with it. `rebuilds` also seeds the night's k-means, so the candidate groups change too.

```sql
-- MySQL: make the project's next night a review night
UPDATE topic_project_states SET rebuilds = 7 * CEIL(rebuilds / 7)
WHERE project_id = '<project_id>' AND rebuilds > 0;
```

#### Churn

Before writing tonight's labels, the run reads last night's label of each window trace. Over the traces labelled on
both nights:

- `churn_any` is the share whose label changed, Other included;
- `churn` is the same over the traces in a topic on both nights: a trace that moved from one topic to another.

A trace that moved to or from Other counts in `churn_any` only. A retired topic's traces moving to Other raise
`churn_any`, not `churn`. Both are null on a night with no trace labelled the night before. A stable list stays near 0:
the target for a project is a mean `churn` under 0.01 over two weeks, with no night over 0.05.

### Tonight's runs

```sql
-- MySQL: the runs started since midnight UTC, newest first
SELECT run_id, workspace_id, project_id, mode, status, error_code, started_at, finished_at,
       JSON_EXTRACT(stats, '$.vectors') AS vectors, JSON_EXTRACT(stats, '$.topics') AS topics,
       JSON_EXTRACT(stats, '$.other_share') AS other_share, JSON_EXTRACT(stats, '$.provisional') AS provisional,
       JSON_EXTRACT(stats, '$.review') AS review, JSON_EXTRACT(stats, '$.born') AS born,
       JSON_EXTRACT(stats, '$.retired') AS retired, JSON_EXTRACT(stats, '$.churn') AS churn,
       JSON_EXTRACT(stats, '$.churn_any') AS churn_any
FROM topic_runs
WHERE started_at >= UTC_DATE()
ORDER BY started_at DESC;

-- MySQL: a project's live topics with their lifecycle state
SELECT name, size, born, inactive_since, low_nights, lo7, hi7, definition FROM topics
WHERE project_id = '<project_id>' AND retired_at IS NULL
ORDER BY size DESC;

-- MySQL: a project's retired topics
SELECT name, born, retired_at, retired_reason, size FROM topics
WHERE project_id = '<project_id>' AND retired_at IS NOT NULL
ORDER BY retired_at DESC;
```

A nightly update's `stats` holds, besides `vectors`, `topics`, `other_share`, `seconds` and `task_version_shares`:

| Key | Meaning |
|---|---|
| `mode` | `nightly_update`. |
| `rebuilds_before`, `topics_before` | `rebuilds` and the live topics the run started from. |
| `review_night`, `review` | A review night by `rebuilds`; a review at all (also forced by a full list). |
| `unassigned`, `candidates`, `big` | Traces no prototype took at 0.75; candidate groups; big ones. `big` 0 and `review` false mean no Qwen call. |
| `verify_pairs`, `verify_failed` | Pairs sent to verify; true when the answer was unreadable and every pair was undone. |
| `forced` | Big candidates left without a topic, by outcome: `born`, `assigned` (given to a live topic and verified), `same_name` (a new name equal to a live topic's), `other_loose`, `other_full`, `other_name`. |
| `born`, `retired` | Names of tonight's new topics; tonight's retirements by reason. |
| `other_prototypes` | Known-Other prototypes after the night. |
| `churn`, `churn_any` | See [Churn](#churn). |
| `qwen_calls`, `qwen` | The number of calls, and per call its `kind` (`revise`, `verify`, `naming`), `outcome` and tokens. |
| `seconds` | Phases: `window`, `state`, `update`, `churn`, `save`. |

```sql
-- ClickHouse: a project's trace counts per topic in the last saved window
-- (window_from and window_until are in the run's stats)
SELECT topic_id, count() AS traces
FROM (
    SELECT trace_id, topic_id
    FROM trace_topics
    WHERE workspace_id = '<workspace_id>' AND project_id = '<project_id>'
    AND trace_created_at >= '<window_from>' AND trace_created_at < '<window_until>'
    ORDER BY trace_id, updated_at DESC
    LIMIT 1 BY trace_id
)
GROUP BY topic_id
ORDER BY traces DESC;
```

Each run logs at INFO, by run id: Qwen's token counts for each call, and one summary line. A first build's line has the
window, vectors, unique texts, k, seed, the topics with their sizes, the Other share and the phase timings; a nightly
update's has the review flag, the unassigned, candidates and big counts, the topics born and retired, the Other share,
the churn and the timings. The prompts (author, or revise, verify and naming) and Qwen's raw answers hold
trace-derived text, so they are logged at DEBUG only (logger `com.comet.opik.domain.topics.TopicsRebuildService`).
Search the logs for `Topics run '<run_id>'`.

### Running by hand

Two internal endpoints start work now instead of waiting for the night. Call them from inside the cluster. Both answer
`501` when `TOPICS_ENABLED` is off and `409` when `TOPICS_NIGHTLY_ENABLED` is off. Both take `&night=yyyy-MM-dd`
(`?night=` for the night endpoint): the night is today (UTC) by default, so the window ends at today's start. `night`
is at most tomorrow, whose window holds today's traces so far, and at least 365 days ago.

**One project.** The night's rules apply: the workspace is opted in to topics, and the project's window holds at least
`TOPICS_NIGHTLY_MIN_VECTORS` vectors. Only one run by hand goes at a time across the cluster.

```bash
curl -X POST "http://<opik-backend>:8080/v1/internal/topics/projects/<project_id>/run?workspace_id=<workspace_id>"
```

It answers `202` with the `run_id`. The run goes on in the background with the same timeout; read its outcome in
`topic_runs`. The mode is the night's: a `nightly_update` when the project has live topics, else a `first_build`. It
answers `409` when the workspace is not opted in, the window is too small (or the project is not the workspace's), a run
of the project or another run by hand is in progress, or the project's last saved run already covers the night (its
`window_until` is at or after the night's start).

**A whole night.** It runs every eligible project, as the scheduled job does, under the same night lock. The lock is
released when the night ends, so the scheduled job is skipped only while a night started by hand is still running.

```bash
curl -X POST "http://<opik-backend>:8080/v1/internal/topics/nights/run"
```

It answers `202` with the `night`, and `409` when a night is in progress. Each project's outcome is its `topic_runs`
row.

To build a project that already has a list again (for example after a bad first build), delete its list first. Its
`trace_topics` rows stay until the new run replaces them:

```sql
-- MySQL: forget a project's list, so the next night (or the endpoint) builds it again
DELETE FROM topics WHERE project_id = '<project_id>';
DELETE FROM topic_prototypes WHERE project_id = '<project_id>';
DELETE FROM topic_project_states WHERE project_id = '<project_id>';
```

### What to watch

| Metric | Meaning |
|---|---|
| `topics.nightly.projects` by `decision` | Projects the night looked at: `skip` (under the minimum, or held by a run started by hand), `first_build`, `nightly_update`, `not_reached` (the budget ran out: they run the next night). `not_reached` above zero means the budget is too short. |
| `topics.runs` by `status`, `error_code` | Finished runs. |
| `topics.run.duration_seconds` by `phase` | Time in `window` (ClickHouse read), `kmeans`, `qwen`, `assign`, `save` (first build); `window`, `state`, `update`, `churn`, `save` (nightly update). |
| `topics.qwen.calls` by `kind`, `outcome` | `kind` is `author`, `revise`, `verify` or `naming`; `outcome` is `ok`, `timeout`, `error` or `bad_answer`. A failed call of any kind fails the run. |
| `topics.topics.live` | Live topics of a project after each saved run. |
| `topics.topics.born` by `mode` | Topics born by saved runs. A few a week per project is normal; many every night means the list does not settle. |
| `topics.topics.retired` by `reason` | `low_share`, `cap`, `merged`, `review`, `replaced`. |
| `topics.run.churn`, `topics.run.churn_any` | Per saved nightly update, see [Churn](#churn). |
| `topics.save.rows` by `table` | Rows saved runs wrote. |
| `topics.nightly.budget_used_seconds` | Wall time of each night. Near `TOPICS_NIGHTLY_BUDGET` means projects will soon be `not_reached`. |

ClickHouse queries of the job have a `log_comment` that starts with `topics:`.

### Alerts

| Alert | Condition | Check | First step |
|---|---|---|---|
| Churn | `churn` over 0.05 on any project's saved run. | The churn query below. | Read the run's `born`, `retired` and `forced` stats and its revise answer in the logs. A merge or a replaced topic explains a one-night jump; a high churn every night means the list does not settle. |
| Failed runs | More than 20% of a night's finished runs failed. | `topics.runs` by `status`, or the failed-share query below. A run whose pod died is failed as `abandoned` only by the next night. | Group the failures by `error_code`: `qwen_*` points at opik-inference, `window_read` and `save` at ClickHouse or MySQL. |
| No saved run | A project with a list has no `saved` run for 3 nights. | The stale-project query below. | Find its runs in `topic_runs`: failed (read `error_code`), never started (`not_reached`: the budget), or skipped (under `TOPICS_NIGHTLY_MIN_VECTORS`: quiet traffic, no action). |

`topics.run.churn` has no project attribute: use it for the trend, and the SQL for which project.

```sql
-- MySQL: saved runs of the last day with churn over 0.05
SELECT run_id, workspace_id, project_id, started_at,
       JSON_EXTRACT(stats, '$.churn') AS churn, JSON_EXTRACT(stats, '$.churn_any') AS churn_any,
       JSON_EXTRACT(stats, '$.born') AS born, JSON_EXTRACT(stats, '$.retired') AS retired
FROM topic_runs
WHERE status = 'saved' AND mode = 'nightly_update' AND started_at >= NOW() - INTERVAL 1 DAY
  AND JSON_EXTRACT(stats, '$.churn') > 0.05;

-- MySQL: the failed share of the runs started since midnight UTC
SELECT SUM(status = 'failed') / NULLIF(SUM(status <> 'running'), 0) AS failed_share
FROM topic_runs
WHERE started_at >= UTC_DATE();

-- MySQL: projects with a list and no saved run for 3 nights, in workspaces still opted in
SELECT s.project_id, s.workspace_id, s.last_success_at
FROM topic_project_states s
JOIN topic_workspaces t ON t.workspace_id = s.workspace_id AND t.enabled = TRUE
JOIN ai_label_workspaces a ON a.workspace_id = s.workspace_id AND a.enabled = TRUE
WHERE s.last_success_at < UTC_DATE() - INTERVAL 2 DAY;
```

### Deletes

A `trace_topics` row lives as long as its trace, as for `topic_vectors`: retention and the trace-delete cascade delete
both. Deleting a project deletes its MySQL rows too: `topics`, `topic_prototypes`, `topic_project_states` and
`topic_runs`, since names, definitions and prototypes come from its traces. `topic_runs` rows go after 90 days anyway.
Deleting a workspace does not delete its MySQL rows, as for `ai_labels`.
