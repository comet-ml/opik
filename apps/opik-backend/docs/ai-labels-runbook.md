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

## Reading topics

`GET /v1/private/topics?project_id=<project_id>` returns a project's live topics and their trace counts over the
window: the last `TOPICS_BUILD_WINDOW_DAYS` UTC days (default 30), today included. The UI calls it. It reads MySQL,
then runs at most one ClickHouse query, and writes nothing.

```bash
curl "http://<opik-backend>:8080/v1/private/topics?project_id=<project_id>" \
  -H "Authorization: <api_key>" \
  -H "Comet-Workspace: <workspace_name>"
```

The caller needs `PROJECT_DATA_VIEW` in the workspace. For a project in a workspace you cannot view, run the queries
under [Reading a project by hand](#reading-a-project-by-hand). They apply the same rules.

### Status

Every body has `today` and `window_from` (UTC dates, both included) and `topics` (empty unless `ready`).

| `status` | When | Other fields |
|---|---|---|
| `disabled` | The workspace is not opted in to both topics and ai-labels. This includes a workspace opted out after a build: its topics stay in MySQL and are not shown. | None. No ClickHouse query. |
| `collecting` | Opted in, and the project has no live topic: before its first build, or after all its topics retired. | `vectors_in_window` (traces with a topic vector in the window) and `min_vectors` (`TOPICS_BUILD_MIN_VECTORS`). `last_run_at` after a saved run. `attempt` (`status` only) when the project's newest `topic_runs` row (by `started_at`, then `run_id`) is `running` or `failed`. |
| `ready` | Opted in, and the project has at least one live topic. | `last_run_at`, `total_traces`, `labelled_from`, `topics` in rank order, `other`. |

A `collecting` body is one of these cases:

| `attempt` | `last_run_at` | Meaning |
|---|---|---|
| none | none | Queued: no run yet. The first-build job runs every `TOPICS_FIRST_BUILD_INTERVAL` (10 minutes) and builds the project once `vectors_in_window` reaches `min_vectors` and its vectors backfill allows it (see "First builds and the vectors backfill"). |
| `running` | none or set | A build runs now. |
| `failed` | none or set | The newest run failed. The job tries again 1 hour after the first failed first build, 6 hours after the second, and 1 day after each later one (see "Retries"). |
| none | set | A run saved and left no live topic. |

The housekeeping at the start of every first-build run fails a `running` row whose pod died, so the API shows the
row as it is.

### How it counts

- **Window.** The `TOPICS_BUILD_WINDOW_DAYS` UTC days that end today: `[today - (windowDays - 1) days, today + 1 day)`.
  The day of a trace is `toDate(trace_created_at, 'UTC')`, the column the builds use. Near midnight UTC it can differ
  from the day the traces list shows.
- **Traces.** Only the traces the Logs page lists count. In `traces`, that is `source` `sdk` (or `unknown`, rows from
  before sources) and `visibility_mode` `default`, the filters the Logs page adds by itself. A trace counts when any
  of its rows matches, as in the traces list. Topics are built from every labelled trace. So experiment, playground,
  optimization and evaluator traces and hidden traces have topic rows, but no count includes them. A topic's `count`
  is then the number of traces Logs lists for it.
- **Rows.** Every row counts: arrival rows and run rows. A run writes its rows only after its MySQL save commits, so a
  failed run has none. A run's rows still count after retention deletes its `topic_runs` row.
- **Latest row.** Each trace counts once, by its latest row (the greatest `(last_updated_at, run_id)`). A trace whose
  latest row is a tombstone (`is_deleted = 1`) does not count.
- **Other.** The all-zeros id. A row whose `topic_id` is not a live topic counts as Other.
- **Numbers.** `count`: the topic's counted traces in the window. `total_traces`: all counted traces, Other included.
  `share`: `count / total_traces`, 0 with no trace. `series`: one `{day, count}` entry per window day, oldest first,
  today last. `rank`: by `count` descending, then `born`, then `id`. `is_new`: born after the first build's date and
  in the last 7 days. `other`: only its `series`; add it to the topics' series for the project's total per day.
- **`labelled_from`.** The first day in the window with any row, omitted with no row. Zeros before it mean nothing was
  labelled yet (a new opt-in), not that there was no traffic.
- **Cache.** The counts of a `ready` project are kept in Redis for `CACHE_MANAGER_TOPICS_DAY_COUNTS_DURATION`
  (default 5 minutes), so they can be up to that old: a trace labelled on arrival shows in the counts after at most
  that time. A saved run, or a new UTC day, starts a new entry at once. The MySQL reads, the `collecting` vector count
  and errors are not cached.

### Reading a project by hand

The queries only read, so a read-only database user can run them for any workspace. Run the MySQL ones first: the ClickHouse query
takes their output.

```sql
-- MySQL: 1 when the workspace is enabled in ai-labels. The workspace must also be in TOGGLE_TOPICS_WORKSPACES (see
-- "Opting workspaces in and out"). Not in the list, or 0 here, means `disabled`
SELECT COUNT(*) > 0 AS eligible
FROM ai_label_workspaces
WHERE workspace_id = '<workspace_id>' AND enabled = TRUE;

-- MySQL: the first build and the last saved run (`last_run_at`). No row means no live topic: `collecting`
SELECT first_built_at, last_success_at, last_run_id
FROM topic_project_states
WHERE workspace_id = '<workspace_id>' AND project_id = '<project_id>';

-- MySQL: the project's live topics; none means `collecting`
SELECT id, name, born
FROM topics
WHERE workspace_id = '<workspace_id>' AND project_id = '<project_id>' AND retired_at IS NULL
ORDER BY born, id;

-- MySQL: the newest run, for `attempt` in `collecting`: shown when `running` or `failed`, omitted when `saved`
SELECT run_id, status, started_at, error_code
FROM topic_runs
WHERE workspace_id = '<workspace_id>' AND project_id = '<project_id>'
ORDER BY started_at DESC, run_id DESC
LIMIT 1;

-- MySQL: the live topic ids for the ClickHouse query, quoted and comma-separated
SELECT CONCAT('''', GROUP_CONCAT(id SEPARATOR ''','''), '''') AS live_ids
FROM topics
WHERE workspace_id = '<workspace_id>' AND project_id = '<project_id>' AND retired_at IS NULL;
```

The ClickHouse query below is for the default window of 30 days: each `- 29` is `windowDays - 1`.

```sql
-- ClickHouse: the API's counts per topic and UTC day. Paste <live_ids> from MySQL.
SELECT if(latest_topic IN (<live_ids>), latest_topic, '00000000-0000-0000-0000-000000000000') AS topic,
       toDate(created_at, 'UTC') AS day,
       count() AS traces
FROM (
    SELECT argMax(topic_id, (last_updated_at, run_id)) AS latest_topic, any(trace_created_at) AS created_at
    FROM trace_topics
    WHERE workspace_id = '<workspace_id>'
      AND project_id = '<project_id>'
      AND trace_created_at >= toDateTime64(toDate(now(), 'UTC') - 29, 6, 'UTC')
      AND trace_created_at < toDateTime64(toDate(now(), 'UTC') + 1, 6, 'UTC')
      AND trace_id IN (
          SELECT id
          FROM traces
          WHERE workspace_id = '<workspace_id>'
            AND project_id = '<project_id>'
            AND created_at >= toDateTime64(toDate(now(), 'UTC') - 29, 6, 'UTC')
            AND created_at < toDateTime64(toDate(now(), 'UTC') + 1, 6, 'UTC')
            AND source IN ('sdk', 'unknown')
            AND visibility_mode = 'default'
      )
    GROUP BY trace_id
    HAVING argMax(is_deleted, (last_updated_at, run_id)) = 0
)
GROUP BY topic, day
ORDER BY day, topic;
```

From the result: a topic's `count` is the sum of its `traces`, and `total_traces` is the sum of all rows. To compare
with the API, run the query on the same UTC date and at least `CACHE_MANAGER_TOPICS_DAY_COUNTS_DURATION` after the
project's last API call, or the API can answer from its cache. A run that saves between the two reads changes the
counts.

### Traces filtered by topic

The traces list takes a `topic_id` filter. "View all logs" on a topic opens the list with it. The value is a topic's
`id` from this endpoint, or Other's all-zeros id. The field is `STRING_EXACT`, the type of the other id fields: the
value is compared exactly, and ids are lower case. The page and the Logs chip use `=`. A value that is not an id
matches no trace. The filter applies to the list and its `total`, to `GET /v1/private/traces/stats`, to the search
stream (`POST /v1/private/traces/search`), to the trace KPI cards (`POST /v1/private/projects/{id}/kpi-cards`) and to
the trace metrics (`POST /v1/private/projects/{id}/metrics`, `trace_filters`). `GET /v1/private/projects/stats`
ignores it: it spans many projects, and a topic belongs to one.

```json
[{"field": "topic_id", "operator": "=", "value": "<topic_id>"}]
```

The filter uses the rules of [How it counts](#how-it-counts). Every row counts, each trace goes by its latest row,
and a topic that is not live is Other. A trace with no counted row fails every operator, `!=` included: `!=` keeps
only traces that have a topic row, so it is not the opposite of `=`. With topics off, a workspace not opted in, or a
project with no live topic, the filter returns no trace and no error.

The page's link opens Logs with the time range, which bounds the list by the time in the trace id. The read API counts
by the trace's `created_at`. So the list's `total` can differ from the topic's `count` for traces whose id time is
outside the window (late or imported traces), and around UTC midnight. For an exact match, add two `created_at`
filters, `>=` the start of `window_from` and `<` the start of the day after `today`, both in UTC
(`2026-10-06T00:00:00Z`), and the Logs page's filters `source = sdk` and `visibility_mode = default`. With these, the
list's `total` equals the topic's `count`, and with the Other filter, the sum of `other.series`. A run that saves
between the two requests can change both.

The backend reads MySQL first: whether the workspace is eligible, then the project's live topics. Then each trace
query gets `AND id IN (<subquery>)`, as score filters do. The subquery reads the project's `trace_topics` rows, keeps
the latest row of each trace, and returns the ids that pass the filter. It is bounded only by the trace id range of
the list: its time range and its cursor. With neither, it reads the project's whole history in `trace_topics`. It has
no `log_comment` of its own: in `system.query_log` it is part of the list's queries (`find_traces_by_project_id`,
`count_traces_by_project`). The KPI cards and the metrics carry the same subquery, bounded by their own id range.

A query with a topic filter has two guards on the whole query, the trace read and the subquery together:
`max_execution_time` from `TOPICS_FILTER_MAX_EXECUTION_TIME` (30 s) and `max_memory_usage` from
`TOPICS_FILTER_MAX_MEMORY_USAGE` (4 GiB). The list, its count, the stats, the KPI cards and the trace metrics have them;
a query without a topic filter has neither. A query that stops at a guard answers `503` with `Retry-After: 5` (see
"Errors"). The search stream has already sent its status then, so it ends with an error, as for any ClickHouse error.
A project whose "All time" filter trips a guard on every retry needs a narrower date range, or a higher limit if
ClickHouse has the memory.

### Errors

| Code | When |
|---|---|
| 501 | `TOPICS_ENABLED=false`. No body. The first check inside the endpoint, before the project. |
| 404 | The project is not in the workspace. |
| 400, `This project is too large for topics` | The ClickHouse read hit its rows or bytes limit: code 158 (rows), 307 (bytes) or 396 (rows or bytes). The same project fails the same way on a retry, so there is no `Retry-After`. The backend logs a WARN `Topics read is too large` with the workspace and the project. Raise `TOPICS_VECTORS_FIND_MAX_ROWS_TO_READ` if the project should fit. A 400 without this message is a request without `project_id`. |
| 503, `Retry-After: 5` | A ClickHouse read stopped at its time (159) or memory (241) limit, or the backend waited 11 s for it. A retry can pass. The backend logs a WARN `Topics read hit a ClickHouse limit` with the workspace and the project. |
| 503, `Retry-After: 5`, traces filtered by topic | A trace query with a topic filter (list, count, stats, KPI cards, trace metrics) stopped at a guard: time (159, `TOPICS_FILTER_MAX_EXECUTION_TIME`) or memory (241, `TOPICS_FILTER_MAX_MEMORY_USAGE`). A retry can pass. The backend logs a WARN `Trace query filtered by topic hit a guard`. |
| 429 | More than `RATE_LIMIT_GET_PROJECT_TOPICS_PER_WORKSPACE_LIMIT` requests from one workspace in `RATE_LIMIT_GET_PROJECT_TOPICS_DURATION_IN_SEC` (60 s). |
| 500 | Any other error. |

The counts query has `max_execution_time` 10 s, and `max_rows_to_read` and `max_memory_usage` from
`TOPICS_VECTORS_FIND_MAX_ROWS_TO_READ` and `TOPICS_VECTORS_FIND_MAX_MEMORY_USAGE`. These two limits are shared with
the vectors job: raising them raises both. Its rows to read include the window's `traces` rows, for the Logs filters.

### Load

The counts query, measured locally:

| Traces in the window | Data | Memory | Time |
|---|---|---|---|
| 100,000 | One project | 95 MiB | 0.13 s p50 |
| 400,000 | Merged | 294 MiB | 0.76 s p50 |
| 2,400,000 over 120 days | Merged, 1,245,184 rows read | 379 MiB | 327 ms |

The largest window tested is 400,000 traces. Memory is the first limit expected: at the default
`TOPICS_VECTORS_FIND_MAX_MEMORY_USAGE`, about 1,500,000 traces in the window (an extrapolation, not measured). Many
unmerged versions per trace raise the cost: with 30 versions per trace the query reads 15 times the rows.

### What to watch

| Metric | Prometheus name | Meaning |
|---|---|---|
| `topics.api.duration_seconds` by `status` | `topics_api_duration_seconds` | Wall time of a request after its project check, a native histogram. `status` is `ready`, `collecting`, `disabled` or `error`. No workspace attributes. A 404 records nothing. |

In ClickHouse `system.query_log`, the counts query's `log_comment` starts with `topics:count_by_topic_and_day:` (filter
`log_comment LIKE 'topics:count_by_topic_and_day:%'`), then the workspace id and the project. A read served from the
cache runs no query. No alert reads these metrics.

### Turning it off

| Env var | Default | Meaning |
|---|---|---|
| `TOPICS_ENABLED` | `false` | Off: the endpoint answers 501. It also stops the vectors and the builds. |
| `RATE_LIMIT_GET_PROJECT_TOPICS_PER_WORKSPACE_LIMIT` | `60` | Requests per workspace in one time bucket. |
| `RATE_LIMIT_GET_PROJECT_TOPICS_DURATION_IN_SEC` | `60` | The time bucket, in seconds. |
| `CACHE_MANAGER_TOPICS_DAY_COUNTS_DURATION` | `PT5M` | How long the counts of a `ready` project stay in Redis. |

The endpoint has no switch of its own. The rollback is `TOPICS_ENABLED=false` and a restart; stored topics and rows
stay.
