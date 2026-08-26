import { gunzipSync } from 'node:zlib';
import { Opik } from 'opik';
import { loadEnvConfig } from '../../config/env.config';
import {
  pollTraceForFeedbackScore,
  type PollFeedbackScoreOpts,
} from './poll-feedback-score';
import {
  waitForTraceScoresSettled,
  type WaitForScoresSettledOpts,
} from './wait-for-scores-settled';
import {
  pollOptimizationStatus,
  type OptimizationStatus,
  type PollOptimizationStatusOpts,
} from './poll-optimization-status';

export type BackendClient = ReturnType<typeof makeBackendClient>;

export interface ProjectRef {
  id: string;
  name: string;
}

export interface DatasetRef {
  id: string;
  name: string;
  description: string | null;
}

export interface DatasetItemRef {
  id: string;
  data: Record<string, unknown>;
}

/**
 * A dataset item read back with its tags. Separate from `DatasetItemRef`
 * because a filter-scoped batch update is asserted on exactly which rows did
 * and did not gain a tag, so `tags` must be present on every row rather than
 * dropped by the mapper.
 */
export interface DatasetItemWithTagsRef {
  id: string;
  data: Record<string, unknown>;
  tags: string[];
}

/** A raw REST answer, kept as status + message so a negative path can assert both. */
export interface RawApiResult {
  status: number;
  /** The backend's `message` field, or the raw body when it isn't JSON. */
  message: string;
  /**
   * The `Location` header, when the endpoint answers 201 with one. Creation
   * endpoints in this API return no body, so this is the only place the new
   * entity's id appears. Optional because the callers that only assert a
   * status code build this shape themselves and have no header to report.
   */
  location?: string | null;
}

/** One row of the dataset's Version history tab. */
export interface DatasetVersionRef {
  versionName: string;
  itemsTotal: number;
  itemsAdded: number;
  itemsModified: number;
  itemsDeleted: number;
  isLatest: boolean;
}

/** The windowed stats one row of the Projects table renders. */
export interface ProjectStatsRef {
  projectId: string;
  traceCount: number | null;
  threadCount: number | null;
  errorCount: number | null;
  feedbackScores: Record<string, number>;
}

export interface ExperimentRefDetail {
  id: string;
  name: string;
  datasetId: string | null;
}

export interface TestSuiteRef {
  id: string;
  name: string;
  description: string | null;
}

export interface TestSuiteItemRef {
  id: string;
  data: Record<string, unknown>;
}

export interface FeedbackScoreRef {
  name: string;
  value: number;
  reason: string | null;
  source: string;
}

export interface TraceDetail {
  id: string;
  name: string;
  projectId: string;
  feedbackScores: FeedbackScoreRef[];
  /**
   * The trace's `input` payload, untyped and unflattened. Kept as a raw record
   * so a caller can assert on which KEYS the SDK wrote — an absent key and a
   * key set to null are different answers, and any shaped type here would
   * collapse them.
   */
  input: Record<string, unknown> | null;
}

/** One conversation thread as `GET /v1/private/traces/threads/retrieve` answers it. */
export interface ThreadDetail {
  id: string;
  projectId: string;
  feedbackScores: FeedbackScoreRef[];
}

export interface AutomationRuleRef {
  id: string;
  name: string;
  projectIds: string[];
  enabled: boolean;
  /**
   * Fraction in [0, 1] — the backend's own units. The dialog shows a
   * percentage (50), the API stores a fraction (0.5); assertions must use the
   * fraction.
   */
  samplingRate: number;
}

/**
 * A rule read back through the raw REST view rather than the pinned SDK.
 *
 * The SDK bundled with this suite (opik 2.0.40) has no `triggerScope` on any
 * evaluator shape, so `listAutomationRulesForProject` structurally cannot
 * report it. A rule whose whole point is which trace sources it fires on has
 * to be read where the field exists.
 */
export interface AutomationRuleDetail {
  id: string;
  name: string;
  enabled: boolean;
  samplingRate: number;
  /** `production` | `experiment` | `both`. Defaults to `production` server-side. */
  triggerScope: string;
}

/** One line of a rule's user-facing log stream. */
export interface AutomationRuleLogRef {
  level: string;
  message: string;
}

/**
 * A trace `input`/`output`/`metadata` payload as the REST API accepts it.
 *
 * The endpoint stores a bare `JsonNode`, so a scalar, an array and an object
 * are all legal — and the online-scoring variable extraction behaves
 * differently for each, which is the point of
 * `online-evaluation-non-object-sections.spec.ts`. The pinned SDK's
 * `JsonListStringWrite` narrows this to object / array-of-objects / string, so
 * the number and scalar-array cases have to be widened here and cast at the
 * call, the same way `rawFetch` exists for calls the pinned SDK can't express.
 */
export type TraceJsonSection = Record<string, unknown> | unknown[] | string | number;

export interface AnnotationQueueReviewerRef {
  username: string;
  itemsScored: number;
}

export interface AnnotationQueueDetail {
  id: string;
  name: string;
  itemsCount: number;
  reviewers: AnnotationQueueReviewerRef[];
}

/**
 * One row of `GET /v1/private/traces/threads` — the aggregate the Threads view
 * renders per conversation. Every field a wrong `traces` prefilter would corrupt
 * is carried through, because the aggregates are the part no page shows as an
 * error: a thread with the right id but a wrong message count or cost still
 * renders as a perfectly ordinary row.
 */
export interface ThreadRowRef {
  id: string;
  numberOfMessages: number | null;
  totalEstimatedCost: number | null;
  usage: Record<string, number> | null;
  duration: number | null;
  /** ISO strings, not Dates — these are compared for byte-identity across reads. */
  startTime: string | null;
  endTime: string | null;
  status: string | null;
}

/** Percentile bucket a `PERCENTAGE` stat item carries instead of a scalar. */
export interface StatPercentiles {
  p50?: number;
  p90?: number;
  p99?: number;
}

/**
 * One value from the threads-stats endpoint. `COUNT` and `AVG` items are
 * scalar; a `PERCENTAGE` item (today, `duration`) is a percentile object. Kept
 * as a union so a caller cannot read a percentile object as though it were a
 * number.
 */
export type ThreadStatValue = number | StatPercentiles | null;

/**
 * Narrow a stat to a number, or throw naming the stat. Use this instead of
 * casting: the percentile case is a real shape from this endpoint, not a
 * type-system inconvenience. Throws rather than asserting so this module stays
 * free of test-runner imports.
 *
 * Accepts `undefined` so a *missing* stat fails here, loudly and named, rather
 * than being silently tested into an `if` at the callsite — an absent aggregate
 * is a regression, not a reason to skip a comparison.
 */
export function numericStat(value: ThreadStatValue | undefined, name: string): number {
  if (value === undefined) {
    throw new Error(`stat "${name}" is absent from the stats response`);
  }
  if (typeof value !== 'number') {
    throw new Error(
      `stat "${name}" is not scalar (got ${JSON.stringify(value)}) — ` +
        'a percentile object here means the endpoint changed shape',
    );
  }
  return value;
}

/** A backend filter as the REST layer serialises it (the `filters` query param). */
export interface BackendFilter {
  field: string;
  operator: string;
  value: string;
  type?: string;
  key?: string;
}

/** Optional rolling window applied to a windowed read (`from_time`/`to_time`). */
export interface ReadWindow {
  fromTime?: Date;
  toTime?: Date;
}

export interface OptimizationRef {
  id: string;
  name: string;
  status: OptimizationStatus;
  objectiveName: string | null;
  datasetName: string | null;
  numTrials: number;
  /**
   * Baseline/best objective scores. NOTE: a healthy run can legitimately score
   * 0 (a weak model won't emit exact-match labels), so tests assert these are
   * present and in [0,1] — never that the run "improved".
   */
  baselineObjectiveScore: number | null;
  bestObjectiveScore: number | null;
}

/** Backend discriminator for Dataset vs Test Suite (shared DB table). */
const TEST_SUITE_TYPE = 'evaluation_suite';

/** One clause of the `sorting` query param the grids serialise. */
export interface BackendSort {
  field: string;
  direction: 'ASC' | 'DESC';
}

/**
 * The filter shape the SDK's dataset-item mutations accept, derived from the
 * method signature rather than hand-written.
 *
 * `BackendFilter.operator` is a plain `string` (the estate's filters cover more
 * endpoints than this one), while the SDK narrows it to a union, so a direct
 * assignment does not type-check. Casting through this alias keeps the *field
 * names* checked — a typo'd `feild`, or the `type` key the SDK does not accept,
 * still fails the build — which a bare `as never` would silently swallow.
 */
type SdkDatasetItemFilters = NonNullable<
  Parameters<Opik['api']['datasets']['deleteDatasetItems']>[0]
>['filters'];

/** The dashboard widget metric types these tests exercise. */
export type WorkspaceMetricType = 'SPAN_TOKEN_USAGE';
export type MetricInterval = 'HOURLY' | 'DAILY' | 'WEEKLY';

/**
 * Every `MetricType` a project-scoped Time series widget can ask for, in the
 * order the backend enum declares them.
 *
 * Exhaustive on purpose. The seven span-time metrics render SQL that binds the
 * project as a set (`IN :project_ids`); the other thirteen bind the scalar
 * `:project_id`, and R2DBC raises for a bind the rendered SQL does not declare
 * — so which side of that partition a metric falls on is only observable by
 * asking for all of them. Kept as a const array rather than a bare union so a
 * spec can iterate it and assert it drove the whole enum.
 */
export const PROJECT_METRIC_TYPES = [
  'FEEDBACK_SCORES',
  'TRACE_COUNT',
  'TOKEN_USAGE',
  'DURATION',
  'COST',
  'GUARDRAILS_FAILED_COUNT',
  'THREAD_COUNT',
  'THREAD_DURATION',
  'THREAD_FEEDBACK_SCORES',
  'SPAN_FEEDBACK_SCORES',
  'SPAN_COUNT',
  'SPAN_DURATION',
  'SPAN_TOKEN_USAGE',
  'TRACE_AVERAGE_DURATION',
  'TRACE_ERROR_RATE',
  'SPAN_AVERAGE_DURATION',
  'SPAN_COST',
  'SPAN_ERROR_RATE',
  'THREAD_AVERAGE_DURATION',
  'THREAD_COST',
] as const;

export type ProjectMetricType = (typeof PROJECT_METRIC_TYPES)[number];

/** The `breakdown` block a widget sends when the user picks a "Group by". */
export interface MetricBreakdown {
  /** `model`, `provider`, `type`, `name`, `tags`, … — the backend's BreakdownField. */
  field: string;
  /**
   * The series the breakdown aggregates. A usage key (`total_tokens`) for token
   * metrics, a percentile (`p50`) for duration metrics, a feedback-score name
   * for score metrics. Required by those three families — validation answers
   * 422 for a blank one.
   */
  subMetric?: string;
  metadataKey?: string;
}

/** One `{name, data:[{time,value}]}` series of a workspace-metrics answer. */
export interface MetricSeries {
  name: string;
  points: Array<{ time: string; value: number | null }>;
}

/** One attachment as `GET /v1/private/attachment/list` answers it. */
export interface AttachmentRef {
  fileName: string;
  /**
   * Never optional here even though the API type allows it: this is the field
   * the tika detection spec asserts on, and a `?? ''` fallback would turn a
   * missing answer into a passing comparison against the wrong expectation.
   */
  mimeType: string;
  fileSize: number;
  link: string | null;
}

/**
 * The `results` block of a metrics answer, flattened to `{name, points}`.
 *
 * Shared by the workspace and per-project reads: both endpoints answer in the
 * same `{results: [{name, data: [{time, value}]}]}` shape, and a caller that
 * compares one to the other must not be comparing two different mappings of it.
 */
function toMetricSeries(json: unknown): MetricSeries[] {
  const results =
    (json as { results?: Array<{ name?: string; data?: Array<{ time?: string; value?: number | null }> }> } | null)
      ?.results ?? [];
  return results.map((r) => ({
    name: String(r.name ?? ''),
    points: (r.data ?? []).map((p) => ({
      time: String(p.time ?? ''),
      value: p.value ?? null,
    })),
  }));
}

export function makeBackendClient(apiKey: string | null = null) {
  const env = loadEnvConfig();
  const opik = new Opik({
    apiKey: apiKey ?? env.apiKey ?? undefined,
    workspaceName: env.workspace,
    apiUrl: env.apiBaseUrl,
  });

  // Hoisted so the poll helpers (free functions) can call it without depending
  // on the not-yet-constructed return object.
  const localGetOptimization = async (id: string): Promise<OptimizationRef | null> => {
    try {
      const o = await opik.api.optimizations.getOptimizationById(id);
      return {
        id: String(o.id),
        name: o.name ?? '',
        status: String(o.status) as OptimizationStatus,
        objectiveName: o.objectiveName ?? null,
        datasetName: o.datasetName ?? null,
        numTrials: Number(o.numTrials ?? 0),
        baselineObjectiveScore: o.baselineObjectiveScore ?? null,
        bestObjectiveScore: o.bestObjectiveScore ?? null,
      };
    } catch (err) {
      if (isNotFoundError(err)) return null;
      throw err;
    }
  };

  /**
   * A REST call that returns the status and body instead of throwing.
   *
   * The typed client raises on any non-2xx and does not surface the response
   * body, but for the filter-validation paths the status *and* the message are
   * the contract under test: an operator the backend cannot serve must answer
   * 400 naming the field and operator, and a malformed filter list must answer
   * 422 — never 500. Same raw-fetch shape as `getProjectStats` below, which
   * exists for the same reason (the pinned SDK can't express the call).
   */
  const rawFetch = async (
    method: 'GET' | 'POST' | 'PATCH',
    path: string,
    opts: { query?: URLSearchParams; body?: unknown } = {},
  ): Promise<RawApiResult & { json: unknown }> => {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Comet-Workspace': env.workspace,
    };
    const key = apiKey ?? env.apiKey;
    if (key) headers['Authorization'] = key;

    const query = opts.query ? `?${opts.query}` : '';
    const res = await fetch(`${env.apiBaseUrl}${path}${query}`, {
      method,
      headers,
      ...(opts.body === undefined ? {} : { body: JSON.stringify(opts.body) }),
    });
    const text = await res.text();
    let json: unknown = null;
    let message = text;
    try {
      json = JSON.parse(text);
      const m = (json as { message?: unknown } | null)?.message;
      if (typeof m === 'string') message = m;
    } catch {
      // Not JSON (an empty 204 body, or an HTML error page) — keep the raw text.
    }
    return { status: res.status, message, json, location: res.headers.get('location') };
  };

  /** Authorization + workspace headers, for calls that bypass `rawFetch`. */
  const workspaceHeaders = (): Record<string, string> => {
    const headers: Record<string, string> = { 'Comet-Workspace': env.workspace };
    const key = apiKey ?? env.apiKey;
    if (key) headers['Authorization'] = key;
    return headers;
  };

  /**
   * The `path` every attachment endpoint wants: the API base URL, base64url.
   *
   * base64url, not base64 — the backend decodes with `Base64.getUrlDecoder()`,
   * which rejects the `+` and `/` a standard encoder emits, and a raw URL is
   * refused outright with `400 Invalid base URL format`.
   */
  const attachmentBasePath = (): string => Buffer.from(env.apiBaseUrl, 'utf-8').toString('base64url');

  /**
   * The generated REST type marks `samplingRate` optional. Defaulting a missing
   * value to 1 would present as "100% of traces", which is indistinguishable
   * from a correctly-configured full-rate rule — so a sampling assertion built
   * on that default could pass while the field was never returned at all.
   * Fail loudly instead.
   */
  const requireSamplingRate = (rate: number | undefined, ruleName: string): number => {
    if (typeof rate !== 'number' || Number.isNaN(rate)) {
      throw new Error(
        `listAutomationRulesForProject: rule '${ruleName}' returned no samplingRate — ` +
          `cannot assert on sampling behaviour.`,
      );
    }
    return rate;
  };

  // Hoisted so pollTraceForFeedbackScore (a free function) can call it without
  // depending on the not-yet-constructed return object.
  const localGetTrace = async (traceId: string): Promise<TraceDetail | null> => {
    try {
      const t = await opik.api.traces.getTraceById(traceId);
      return {
        id: String(t.id),
        name: t.name ?? '',
        projectId: String(t.projectId ?? ''),
        feedbackScores: (t.feedbackScores ?? []).map((fs) => ({
          name: fs.name,
          value: Number(fs.value),
          reason: fs.reason ?? null,
          source: String(fs.source),
        })),
        input: (t.input as Record<string, unknown> | undefined) ?? null,
      };
    } catch (err) {
      if (isNotFoundError(err)) return null;
      throw err;
    }
  };

  return {
    async createProject(name: string, description?: string): Promise<void> {
      await opik.api.projects.createProject({
        name,
        ...(description ? { description } : {}),
      });
    },

    async deleteProject(id: string): Promise<void> {
      try {
        await opik.api.projects.deleteProjectById(id);
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    async getProject(id: string): Promise<ProjectRef | null> {
      try {
        const p = await opik.api.projects.getProjectById(id);
        return { id: String(p.id), name: p.name as string };
      } catch (err) {
        if (isNotFoundError(err)) return null;
        throw err;
      }
    },

    async listProjectsWithPrefix(prefix: string): Promise<ProjectRef[]> {
      const page = await opik.api.projects.findProjects({ name: prefix, size: 500 });
      const content = page.content ?? [];
      return content
        .filter((p) => typeof p.name === 'string' && p.name.startsWith(prefix))
        .map((p) => ({ id: String(p.id), name: p.name as string }));
    },

    async listDatasetsWithPrefix(prefix: string): Promise<DatasetRef[]> {
      const page = await opik.api.datasets.findDatasets({ name: prefix, size: 500 });
      const content = page.content ?? [];
      return content
        .filter((d) => typeof d.name === 'string' && d.name.startsWith(prefix))
        .map((d) => ({
          id: String(d.id),
          name: d.name as string,
          description: d.description ?? null,
        }));
    },

    async findDatasetByName(name: string, projectName?: string): Promise<DatasetRef | null> {
      try {
        const dataset = await opik.api.datasets.getDatasetByIdentifier({
          datasetName: name,
          ...(projectName ? { projectName } : {}),
        });
        return {
          id: String(dataset.id),
          name: dataset.name,
          description: dataset.description ?? null,
        };
      } catch (err) {
        if (isNotFoundError(err)) return null;
        throw err;
      }
    },

    async deleteDataset(id: string): Promise<void> {
      try {
        await opik.api.datasets.deleteDataset(id);
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    /**
     * The id `DELETE`/`GET /v1/private/prompts/{id}` expect. The Python SDK's
     * create_prompt returns the prompt VERSION id instead, which 404s against
     * both — so resolve by name rather than trusting the id it handed back.
     */
    async findPromptIdByName(name: string, projectId?: string): Promise<string | null> {
      // `name` is a partial-match search, so re-check it exactly.
      const page = await opik.api.prompts.getPrompts({
        name,
        size: 50,
        ...(projectId ? { projectId } : {}),
      });
      const match = (page.content ?? []).find((p) => p.name === name);
      return match?.id ? String(match.id) : null;
    },

    async promptExistsByName(name: string, projectId?: string): Promise<boolean> {
      const page = await opik.api.prompts.getPrompts({
        name,
        size: 50,
        ...(projectId ? { projectId } : {}),
      });
      return (page.content ?? []).some((p) => p.name === name);
    },

    async deletePrompt(id: string): Promise<void> {
      try {
        await opik.api.prompts.deletePrompt(id);
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    async getDatasetItems(datasetId: string): Promise<DatasetItemRef[]> {
      const page = await opik.api.datasets.getDatasetItems(datasetId);
      const content = page.content ?? [];
      return content.map((item) => ({
        id: String(item.id),
        data: (item.data ?? {}) as Record<string, unknown>,
      }));
    },

    /**
     * Versions of a dataset, newest first — the rows the Version history tab
     * renders, including the item counters it shows in "Item count".
     */
    async getDatasetVersions(datasetId: string): Promise<DatasetVersionRef[]> {
      const page = await opik.api.datasets.listDatasetVersions(datasetId, { size: 100 });
      const content = page.content ?? [];
      return content.map((v) => ({
        versionName: String(v.versionName ?? ''),
        itemsTotal: Number(v.itemsTotal ?? 0),
        itemsAdded: Number(v.itemsAdded ?? 0),
        itemsModified: Number(v.itemsModified ?? 0),
        itemsDeleted: Number(v.itemsDeleted ?? 0),
        isLatest: Boolean(v.isLatest),
      }));
    },

    /**
     * Every item id actually stored in the dataset, paged out in full. This is
     * the ground truth a version's `items_total` is supposed to agree with, so
     * it must not be capped at a page — hence the loop. `truncate` drops the
     * item payloads we don't need, keeping a few-thousand-item read cheap.
     */
    async listDatasetItemIds(datasetId: string): Promise<string[]> {
      const pageSize = 1000;
      const ids: string[] = [];
      for (let page = 1; ; page++) {
        const result = await opik.api.datasets.getDatasetItems(datasetId, {
          page,
          size: pageSize,
          truncate: true,
        });
        const content = result.content ?? [];
        ids.push(...content.map((item) => String(item.id)));
        if (content.length < pageSize) return ids;
      }
    },

    /**
     * Dataset items under `filters`, with their tags — `GET /v1/private/datasets/
     * {id}/items`. This is the read the filter-scoped mutations preview: whatever
     * this returns is exactly the set a delete or batch-update with the same
     * filter is entitled to touch.
     */
    async listDatasetItemsFiltered(args: {
      datasetId: string;
      filters?: BackendFilter[];
    }): Promise<DatasetItemWithTagsRef[]> {
      const page = await opik.api.datasets.getDatasetItems(args.datasetId, {
        size: 1000,
        page: 1,
        ...(args.filters?.length ? { filters: JSON.stringify(args.filters) } : {}),
      });
      return (page.content ?? []).map((item) => ({
        id: String(item.id),
        data: (item.data ?? {}) as Record<string, unknown>,
        tags: (item as { tags?: string[] }).tags ?? [],
      }));
    },

    /**
     * Tag every dataset item matching `filters` — `PATCH /v1/private/datasets/
     * items/batch`. Scope is decided server-side by the filter, which is the
     * whole point: the caller never names the ids.
     */
    async batchUpdateDatasetItemsByFilter(args: {
      datasetId: string;
      filters: BackendFilter[];
      tags: string[];
    }): Promise<void> {
      await opik.api.datasets.batchUpdateDatasetItems({
        datasetId: args.datasetId,
        filters: args.filters as SdkDatasetItemFilters,
        update: { tagsToAdd: args.tags },
      });
    },

    /**
     * Delete every dataset item matching `filters` — `POST /v1/private/datasets/
     * items/delete`. Non-reversible and filter-scoped, so a test using it must
     * assert the surviving set exactly, not just that something was removed.
     *
     * **Ungrouped on purpose, and therefore not the UI's exact request.** No
     * `batch_group_id` is sent, so the backend mutates the latest dataset
     * version rather than creating a new one. The UI's select-all delete *does*
     * send one (`DatasetItemsActionsPanel` calls `generateBatchGroupId()` when
     * every row is selected), which commits the delete as its own version.
     *
     * The distinction is deliberate: what the filter-scoped endpoints needed
     * covering is *which rows a filter selects* — the destructive part, where an
     * over-matching filter silently deletes data. Version-commit semantics are a
     * separate contract, already asserted by `dataset-items.spec.ts` and
     * `dataset-version-counters.spec.ts` for the id-scoped paths. A caller that
     * wants the grouped behaviour must pass `batchGroupId` and assert the new
     * version; this helper does not, so do not read it as the user-facing path.
     */
    async deleteDatasetItemsByFilter(args: {
      datasetId: string;
      filters: BackendFilter[];
    }): Promise<void> {
      await opik.api.datasets.deleteDatasetItems({
        datasetId: args.datasetId,
        filters: args.filters as SdkDatasetItemFilters,
      });
    },

    /**
     * The status and message a filter-scoped dataset-item mutation answers with,
     * without throwing — for the negative paths, where the contract is that a
     * filter the backend cannot serve is rejected at validation (400/422) rather
     * than blowing up in the query builder (500).
     *
     * `filters` is `unknown` on purpose: some of these cases send a filter list
     * that is deliberately malformed (a null element), which no typed filter
     * shape can express.
     */
    async datasetItemMutationStatus(args: {
      operation: 'delete' | 'batch-update';
      datasetId: string;
      filters: unknown;
    }): Promise<RawApiResult> {
      const { status, message } =
        args.operation === 'delete'
          ? await rawFetch('POST', '/v1/private/datasets/items/delete', {
              body: { dataset_id: args.datasetId, filters: args.filters },
            })
          : await rawFetch('PATCH', '/v1/private/datasets/items/batch', {
              body: {
                dataset_id: args.datasetId,
                filters: args.filters,
                update: { tags_to_add: ['should-never-be-applied'] },
              },
            });
      return { status, message };
    },

    /**
     * Dataset-item ids in the order the experiment-comparison grid asks for them
     * — `GET /v1/private/datasets/{id}/items/experiments/items?sorting=`.
     *
     * Ids only, and in order: this read exists to assert *which* rows come back
     * and in *what* order, never their content. The `sorting` field travels
     * verbatim, so `output.<key>` exercises the dynamic-key binding directly.
     */
    async listCompareItemIds(args: {
      datasetId: string;
      experimentIds: string[];
      sorting?: BackendSort[];
      size?: number;
    }): Promise<string[]> {
      const page = await opik.api.datasets.findDatasetItemsWithExperimentItems(args.datasetId, {
        experimentIds: JSON.stringify(args.experimentIds),
        size: args.size ?? 200,
        page: 1,
        truncate: true,
        ...(args.sorting?.length ? { sorting: JSON.stringify(args.sorting) } : {}),
      });
      return (page.content ?? []).map((item) => String(item.id));
    },

    /**
     * `POST /v1/private/workspaces/metrics/spans` — the aggregation a dashboard
     * Time series widget plots when it is scoped to "All projects in the
     * workspace". Raw fetch because the pinned SDK has no binding for it, and
     * because the widget's own payload (including a 400) is what's under test.
     *
     * `projectIds` is deliberately absent from the body when empty: that is how
     * the front end asks for the whole workspace, and a specific project would
     * route the widget to `/projects/{id}/metrics` instead — a different
     * endpoint entirely.
     */
    async workspaceSpanMetric(args: {
      metricType: WorkspaceMetricType;
      interval: MetricInterval;
      intervalStart: Date;
      intervalEnd: Date;
      /** Sent verbatim: these tests assert on the exact payload the UI emits. */
      filters?: unknown[];
    }): Promise<RawApiResult & { series: MetricSeries[] }> {
      const { status, message, json } = await rawFetch(
        'POST',
        '/v1/private/workspaces/metrics/spans',
        {
          body: {
            metric_type: args.metricType,
            interval: args.interval,
            interval_start: args.intervalStart.toISOString(),
            interval_end: args.intervalEnd.toISOString(),
            ...(args.filters?.length ? { filters: args.filters } : {}),
          },
        },
      );
      return { status, message, series: toMetricSeries(json) };
    },

    /**
     * `POST /v1/private/projects/{id}/metrics` — the aggregation a Time series
     * widget plots when it is scoped to one project rather than to the whole
     * workspace. A different endpoint from `workspaceSpanMetric` above, and the
     * only one that renders the per-metric-type project predicate OPIK-7725
     * gated, so the two are not interchangeable.
     *
     * Raw fetch for the same two reasons as the workspace read: the pinned SDK
     * has no binding for it, and the status is part of the contract under test
     * — a mis-gated bind surfaces as 500, not as a wrong number.
     */
    async projectMetric(args: {
      projectId: string;
      metricType: ProjectMetricType;
      interval: MetricInterval;
      intervalStart: Date;
      intervalEnd: Date;
      breakdown?: MetricBreakdown;
    }): Promise<RawApiResult & { series: MetricSeries[] }> {
      const { status, message, json } = await rawFetch(
        'POST',
        `/v1/private/projects/${args.projectId}/metrics`,
        {
          body: {
            metric_type: args.metricType,
            interval: args.interval,
            interval_start: args.intervalStart.toISOString(),
            interval_end: args.intervalEnd.toISOString(),
            ...(args.breakdown
              ? {
                  breakdown: {
                    field: args.breakdown.field,
                    ...(args.breakdown.subMetric === undefined
                      ? {}
                      : { sub_metric: args.breakdown.subMetric }),
                    ...(args.breakdown.metadataKey === undefined
                      ? {}
                      : { metadata_key: args.breakdown.metadataKey }),
                  },
                }
              : {}),
          },
        },
      );
      return { status, message, series: toMetricSeries(json) };
    },

    /**
     * `DELETE /v1/private/dashboards/{id}`.
     *
     * Dashboards are not swept by `global-teardown` (it knows about
     * experiments, datasets and projects only) and they do not hang off a
     * project, so a spec that builds one has to remove it itself.
     */
    async deleteDashboard(id: string): Promise<void> {
      const headers = workspaceHeaders();
      const res = await fetch(`${env.apiBaseUrl}/v1/private/dashboards/${id}`, {
        method: 'DELETE',
        headers,
      });
      if (!res.ok && res.status !== 404) {
        throw new Error(
          `DELETE /v1/private/dashboards/${id} -> ${res.status}: ${(await res.text()).slice(0, 300)}`,
        );
      }
    },

    /**
     * Uploads one attachment against a trace or span through the real
     * presigned multipart flow: `upload-start` → PUT the bytes → `upload-complete`.
     *
     * `mimeType` is optional *and meant to be omitted*: with no caller-supplied
     * type the backend falls through to `tika.detect(fileName)`, which is the
     * only reachable surface of the tika dependency and the thing the
     * attachments spec asserts on.
     *
     * Deployment-neutral. On S3 `upload-start` hands back a genuine presigned
     * URL, which must be PUT to with no `Authorization` header — adding one
     * breaks the signature. On MinIO (the OSS compose stack) it hands back a URL
     * on this same API instead, which *does* need the workspace headers. Hence
     * the origin check rather than a hard-coded choice of one or the other.
     */
    async uploadAttachment(args: {
      projectName: string;
      entityType: 'trace' | 'span';
      entityId: string;
      fileName: string;
      /** A Buffer, not a bare Uint8Array: `fetch` will not take a shared-buffer view. */
      content: Buffer<ArrayBuffer>;
      mimeType?: string;
    }): Promise<void> {
      const start = await rawFetch('POST', '/v1/private/attachment/upload-start', {
        body: {
          file_name: args.fileName,
          num_of_file_parts: 1,
          project_name: args.projectName,
          entity_type: args.entityType,
          entity_id: args.entityId,
          path: attachmentBasePath(),
          ...(args.mimeType === undefined ? {} : { mime_type: args.mimeType }),
        },
      });
      if (start.status !== 200) {
        throw new Error(
          `POST /v1/private/attachment/upload-start (${args.fileName}) -> ${start.status}: ${start.message}`,
        );
      }
      const { upload_id: uploadId, pre_sign_urls: preSignUrls } = start.json as {
        upload_id?: string;
        pre_sign_urls?: string[];
      };
      const url = preSignUrls?.[0];
      if (!uploadId || !url) {
        throw new Error(
          `upload-start (${args.fileName}) returned no upload_id/pre_sign_urls: ${JSON.stringify(start.json)}`,
        );
      }

      const put = await fetch(url, {
        method: 'PUT',
        headers: url.startsWith(env.apiBaseUrl) ? workspaceHeaders() : {},
        body: args.content,
      });
      if (!put.ok) {
        throw new Error(
          `PUT attachment part (${args.fileName}) -> ${put.status}: ${(await put.text()).slice(0, 300)}`,
        );
      }
      // MinIO's stand-in upload answers 204 with no ETag; S3 always sends one.
      const eTag = put.headers.get('etag') ?? 'BEMinIO';

      const complete = await rawFetch('POST', '/v1/private/attachment/upload-complete', {
        body: {
          file_name: args.fileName,
          project_name: args.projectName,
          entity_type: args.entityType,
          entity_id: args.entityId,
          file_size: args.content.byteLength,
          upload_id: uploadId,
          uploaded_file_parts: [{ e_tag: eTag, part_number: 1 }],
        },
      });
      if (complete.status !== 204 && complete.status !== 200) {
        throw new Error(
          `POST /v1/private/attachment/upload-complete (${args.fileName}) -> ${complete.status}: ${complete.message}`,
        );
      }
    },

    /**
     * `GET /v1/private/attachment/list` for one entity.
     *
     * `path` must be the base64url of the API base URL; a raw URL answers
     * `400 Invalid base URL format`.
     */
    async listAttachments(args: {
      projectId: string;
      entityType: 'trace' | 'span';
      entityId: string;
    }): Promise<AttachmentRef[]> {
      const query = new URLSearchParams({
        project_id: args.projectId,
        entity_type: args.entityType,
        entity_id: args.entityId,
        path: attachmentBasePath(),
        size: '100',
        page: '1',
      });
      const { status, message, json } = await rawFetch(
        'GET',
        '/v1/private/attachment/list',
        { query },
      );
      if (status !== 200) {
        throw new Error(`GET /v1/private/attachment/list -> ${status}: ${message}`);
      }
      const content =
        (json as { content?: Array<Record<string, unknown>> } | null)?.content ?? [];
      return content.map((a) => {
        const mimeType = a.mime_type;
        if (typeof mimeType !== 'string' || mimeType === '') {
          throw new Error(
            `attachment '${String(a.file_name)}' came back with no mime_type: ${JSON.stringify(a)}`,
          );
        }
        return {
          fileName: String(a.file_name ?? ''),
          mimeType,
          fileSize: Number(a.file_size ?? 0),
          link: typeof a.link === 'string' ? a.link : null,
        };
      });
    },

    /**
     * `POST /v1/private/attachment/delete`. Deleting the owning trace cascades
     * to its attachments, but a fixture that seeds attachments onto a trace it
     * does not own has to clean up its own objects.
     */
    async deleteAttachments(args: {
      projectId: string;
      entityType: 'trace' | 'span';
      entityId: string;
      fileNames: string[];
    }): Promise<void> {
      if (args.fileNames.length === 0) return;
      const { status, message } = await rawFetch('POST', '/v1/private/attachment/delete', {
        body: {
          file_names: args.fileNames,
          entity_type: args.entityType,
          entity_id: args.entityId,
          container_id: args.projectId,
        },
      });
      if (status !== 204 && status !== 200 && status !== 404) {
        throw new Error(`POST /v1/private/attachment/delete -> ${status}: ${message}`);
      }
    },

    /**
     * Stats for the projects whose name matches `name`, optionally scoped to a
     * time window — the exact call the v2 Projects table makes to fill its
     * "(30d)" columns.
     *
     * Raw fetch rather than `opik.api.projects.getProjectStats`: the pinned TS
     * SDK's request type has no from_time/to_time, and the window is the whole
     * point here. Switch to the typed call once the SDK exposes them.
     */
    async getProjectStats(args: {
      name: string;
      fromTime?: Date;
      toTime?: Date;
    }): Promise<ProjectStatsRef[]> {
      const params = new URLSearchParams({ name: args.name, size: '100' });
      if (args.fromTime) params.set('from_time', args.fromTime.toISOString());
      if (args.toTime) params.set('to_time', args.toTime.toISOString());

      const headers: Record<string, string> = {
        Accept: 'application/json',
        'Comet-Workspace': env.workspace,
      };
      const key = apiKey ?? env.apiKey;
      if (key) headers['Authorization'] = key;

      const res = await fetch(`${env.apiBaseUrl}/v1/private/projects/stats?${params}`, {
        headers,
      });
      if (!res.ok) {
        throw new Error(
          `GET /v1/private/projects/stats -> ${res.status}: ${(await res.text()).slice(0, 300)}`,
        );
      }
      const body = (await res.json()) as {
        content?: Array<{
          project_id?: string;
          trace_count?: number | null;
          thread_count?: number | null;
          error_count?: { count?: number | null } | null;
          feedback_scores?: Array<{ name: string; value: number }> | null;
        }>;
      };
      return (body.content ?? []).map((item) => ({
        projectId: String(item.project_id ?? ''),
        // Outside every seeded window the backend answers 200 with the metrics
        // absent rather than zeroed, so null and 0 are genuinely different
        // answers here and must not be collapsed.
        traceCount: item.trace_count ?? null,
        threadCount: item.thread_count ?? null,
        errorCount: item.error_count?.count ?? null,
        feedbackScores: Object.fromEntries(
          (item.feedback_scores ?? []).map((s) => [s.name, Number(s.value)]),
        ),
      }));
    },

    /**
     * By id, unlike findExperimentByName — `findExperiments({ name })` is not
     * scoped to a project, so a same-named experiment elsewhere would answer
     * for this one.
     */
    async experimentExists(id: string): Promise<boolean> {
      try {
        await opik.api.experiments.getExperimentById(id);
        return true;
      } catch (err) {
        if (isNotFoundError(err)) return false;
        throw err;
      }
    },

    async findExperimentByName(name: string): Promise<ExperimentRefDetail | null> {
      const page = await opik.api.experiments.findExperiments({ name, size: 50 });
      const content = page.content ?? [];
      const match = content.find((e) => e.name === name);
      if (!match) return null;
      return {
        id: String(match.id),
        name: match.name as string,
        datasetId: match.datasetId ? String(match.datasetId) : null,
      };
    },

    async listExperimentsWithPrefix(prefix: string): Promise<ExperimentRefDetail[]> {
      const page = await opik.api.experiments.findExperiments({ name: prefix, size: 500 });
      const content = page.content ?? [];
      return content
        .filter((e) => typeof e.name === 'string' && (e.name as string).startsWith(prefix))
        .map((e) => ({
          id: String(e.id),
          name: e.name as string,
          datasetId: e.datasetId ? String(e.datasetId) : null,
        }));
    },

    async deleteExperiment(id: string): Promise<void> {
      try {
        await opik.api.experiments.deleteExperimentsById({ ids: [id] });
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    async findTestSuiteByName(name: string, projectName?: string): Promise<TestSuiteRef | null> {
      try {
        const dataset = await opik.api.datasets.getDatasetByIdentifier({
          datasetName: name,
          ...(projectName ? { projectName } : {}),
        });
        // Backend stores test suites and datasets on the same table, discriminated
        // by `type`. Require an explicit match: if `type` is missing or anything
        // other than 'evaluation_suite', this isn't a test suite.
        const typeFromBackend = (dataset as { type?: string }).type;
        if (typeFromBackend !== TEST_SUITE_TYPE) {
          return null;
        }
        return {
          id: String(dataset.id),
          name: dataset.name,
          description: dataset.description ?? null,
        };
      } catch (err) {
        if (isNotFoundError(err)) return null;
        throw err;
      }
    },

    async listTestSuitesWithPrefix(prefix: string): Promise<TestSuiteRef[]> {
      const page = await opik.api.datasets.findDatasets({ name: prefix, size: 500 });
      const content = page.content ?? [];
      return content
        .filter(
          (d) =>
            typeof d.name === 'string' &&
            d.name.startsWith(prefix) &&
            (d as { type?: string }).type === TEST_SUITE_TYPE,
        )
        .map((d) => ({
          id: String(d.id),
          name: d.name as string,
          description: d.description ?? null,
        }));
    },

    async getTestSuiteItems(suiteId: string): Promise<TestSuiteItemRef[]> {
      const page = await opik.api.datasets.getDatasetItems(suiteId);
      const content = page.content ?? [];
      return content.map((item) => ({
        id: String(item.id),
        data: (item.data ?? {}) as Record<string, unknown>,
      }));
    },

    getTrace: localGetTrace,

    /**
     * A trace's `input` and `output` exactly as stored, with no shape claimed.
     *
     * `TraceDetail.input` is typed as a record because every caller that reads
     * it asserts on which KEYS the SDK wrote. A spec whose whole subject is a
     * section that is deliberately NOT an object cannot use that type to prove
     * its own seed landed — and a seed that silently became `{"output": "x"}`
     * would make the test pass while exercising nothing.
     *
     * Returns null while the trace is not yet readable — the REST write answers
     * 201 before the row is queryable, so an immediate read-back after seeding
     * legitimately 404s. Callers poll rather than treating that as a failure,
     * the same way `getTrace` does.
     */
    async getTraceSections(
      traceId: string,
    ): Promise<{ input: unknown; output: unknown } | null> {
      try {
        const trace = await opik.api.traces.getTraceById(traceId);
        return { input: trace.input ?? null, output: trace.output ?? null };
      } catch (err) {
        if (isNotFoundError(err)) return null;
        throw err;
      }
    },

    async deleteTraces(ids: string[]): Promise<void> {
      await opik.api.traces.deleteTraces({ ids });
    },

    async pollTraceForFeedbackScore(
      traceId: string,
      scoreName: string,
      opts: PollFeedbackScoreOpts = {},
    ): Promise<FeedbackScoreRef> {
      return pollTraceForFeedbackScore(localGetTrace, traceId, scoreName, opts);
    },

    async waitForTraceScoresSettled(
      traceId: string,
      opts: WaitForScoresSettledOpts = {},
    ): Promise<TraceDetail> {
      return waitForTraceScoresSettled(localGetTrace, traceId, opts);
    },

    async listAutomationRulesForProject(projectId: string): Promise<AutomationRuleRef[]> {
      const page = await opik.api.automationRuleEvaluators.findEvaluators({
        projectId,
        size: 500,
      });
      const content = page.content ?? [];
      return content.map((r) => ({
        id: String(r.id),
        name: r.name,
        projectIds: (r.projects ?? []).map((p) => String(p.projectId)),
        enabled: r.enabled ?? true,
        samplingRate: requireSamplingRate(r.samplingRate, r.name),
      }));
    },

    /**
     * Create an online-evaluation rule and return its id.
     *
     * Goes through `rawFetch` rather than the pinned SDK for two reasons the
     * specs depend on:
     *   - `triggerScope` does not exist on any SDK evaluator shape (see
     *     `AutomationRuleDetail`), and a rule that must fire on experiment /
     *     playground traces cannot be built without it.
     *   - creation answers 201 with an empty body, so the id only exists in the
     *     `Location` header, which the SDK's `void` return discards.
     *
     * The id is parsed from `Location` rather than recovered by listing the
     * project's rules by name: a name lookup would silently pick up a rule left
     * behind by an earlier run under the same namespace.
     */
    async createAutomationRule(args: {
      projectId: string;
      name: string;
      /** Fraction in [0, 1], the backend's own units — not the dialog's percentage. */
      samplingRate: number;
      /** Python source for the metric class. */
      metric: string;
      /** `score()` parameter name -> extraction path (e.g. `output.answer`). */
      arguments: Record<string, string>;
      triggerScope?: 'production' | 'experiment' | 'both';
      enabled?: boolean;
    }): Promise<string> {
      const { status, message, location } = await rawFetch(
        'POST',
        '/v1/private/automations/evaluators/',
        {
          body: {
            type: 'user_defined_metric_python',
            action: 'evaluator',
            name: args.name,
            project_ids: [args.projectId],
            sampling_rate: args.samplingRate,
            enabled: args.enabled ?? true,
            ...(args.triggerScope ? { trigger_scope: args.triggerScope } : {}),
            code: { metric: args.metric, arguments: args.arguments },
          },
        },
      );
      if (status !== 201) {
        throw new Error(
          `createAutomationRule: expected 201 for '${args.name}', got ${status}: ${message}`,
        );
      }
      const id = location?.split('/').filter(Boolean).pop();
      if (!id) {
        throw new Error(
          `createAutomationRule: 201 for '${args.name}' carried no usable Location header ` +
            `(got '${location}') — cannot address the rule.`,
        );
      }
      return id;
    },

    /** One rule by id, including the `triggerScope` the pinned SDK cannot see. */
    async getAutomationRule(ruleId: string): Promise<AutomationRuleDetail> {
      const { status, message, json } = await rawFetch(
        'GET',
        `/v1/private/automations/evaluators/${ruleId}`,
      );
      if (status !== 200) {
        throw new Error(`getAutomationRule: ${ruleId} answered ${status}: ${message}`);
      }
      const rule = json as {
        id?: string;
        name?: string;
        enabled?: boolean;
        sampling_rate?: number;
        trigger_scope?: string;
      };
      // Same reasoning as `requireSamplingRate`: defaulting an absent rate or
      // scope would present as the server's default, which is exactly the value
      // these specs are trying to prove was NOT silently applied.
      if (typeof rule.sampling_rate !== 'number' || Number.isNaN(rule.sampling_rate)) {
        throw new Error(`getAutomationRule: ${ruleId} returned no sampling_rate`);
      }
      if (typeof rule.trigger_scope !== 'string') {
        throw new Error(`getAutomationRule: ${ruleId} returned no trigger_scope`);
      }
      return {
        id: String(rule.id ?? ruleId),
        name: String(rule.name ?? ''),
        enabled: rule.enabled ?? true,
        samplingRate: rule.sampling_rate,
        triggerScope: rule.trigger_scope,
      };
    },

    /**
     * A rule's user-facing log stream — the lines `/automation-logs` renders.
     *
     * This is the only place the engine says why it did or did not score a
     * trace: a skipped trace produces a log line and no feedback score, so an
     * absence assertion has nothing else to anchor on.
     */
    async getAutomationRuleLogs(
      ruleId: string,
      opts: { size?: number } = {},
    ): Promise<AutomationRuleLogRef[]> {
      const page = await opik.api.automationRuleEvaluators.getEvaluatorLogsById(ruleId, {
        size: opts.size ?? 1000,
      });
      return (page.content ?? []).map((item) => ({
        level: String(item.level ?? ''),
        message: String(item.message ?? ''),
      }));
    },

    async deleteAutomationRule(projectId: string, ruleId: string): Promise<void> {
      try {
        await opik.api.automationRuleEvaluators.deleteAutomationRuleEvaluatorBatch({
          projectId,
          body: { ids: [ruleId] },
        });
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    /**
     * One page of `GET /v1/private/traces/threads` — the exact read the Threads
     * view issues, including its `filters` and time window.
     *
     * `filters` is passed through verbatim rather than built here: the whole
     * point of the thread-prefilter tests is to drive a *specific* field and
     * operator (an EQUAL on `id` takes a different backend branch than a
     * CONTAINS), so the caller must own that choice.
     */
    async listThreads(
      args: { projectId: string; filters?: BackendFilter[]; size?: number } & ReadWindow,
    ): Promise<{ total: number; threads: ThreadRowRef[] }> {
      const page = await opik.api.traces.getTraceThreads({
        projectId: args.projectId,
        size: args.size ?? 100,
        page: 1,
        ...(args.filters?.length ? { filters: JSON.stringify(args.filters) } : {}),
        ...(args.fromTime ? { fromTime: args.fromTime } : {}),
        ...(args.toTime ? { toTime: args.toTime } : {}),
      });
      return {
        total: Number(page.total ?? 0),
        threads: (page.content ?? []).map((t) => ({
          id: String(t.id ?? ''),
          // null and 0 are different answers from this endpoint (an absent
          // aggregate is not a zero one), so they must not be collapsed.
          numberOfMessages: t.numberOfMessages ?? null,
          totalEstimatedCost: t.totalEstimatedCost ?? null,
          usage: t.usage ?? null,
          duration: t.duration ?? null,
          startTime: t.startTime ? new Date(t.startTime).toISOString() : null,
          endTime: t.endTime ? new Date(t.endTime).toISOString() : null,
          status: t.status ? String(t.status) : null,
        })),
      };
    },

    /**
     * One thread by id, with the feedback scores attached to the THREAD itself.
     *
     * Not derivable from `listThreads`: the row shape that view renders carries
     * the aggregates, not the scores. Thread-level metrics (`evaluate_threads`)
     * write here and nowhere else — a score on a thread is not a score on any
     * of its traces — so this is the only API read that can confirm one landed.
     */
    async getThread(args: { projectId: string; threadId: string }): Promise<ThreadDetail> {
      const thread = await opik.api.traces.getTraceThread({
        projectId: args.projectId,
        threadId: args.threadId,
      });
      return {
        id: String(thread.id ?? ''),
        projectId: String(thread.projectId ?? ''),
        feedbackScores: (thread.feedbackScores ?? []).map((fs) => ({
          name: fs.name,
          value: Number(fs.value),
          reason: fs.reason ?? null,
          source: String(fs.source),
        })),
      };
    },

    /**
     * `GET /v1/private/traces/threads/stats` under the same filters — the
     * numbers the Threads view's count card shows, flattened to name -> value.
     *
     * Not every stat is scalar: the endpoint's items are a tagged union, and a
     * `PERCENTAGE` one (today, `duration`) carries a `{p50, p90, p99}` object
     * rather than a number. The value type says so, so a caller reading
     * `duration` as a number has to narrow first instead of silently computing
     * on an object. `numericStat()` below is the narrowing helper.
     *
     * `Partial` because the endpoint returns only the stats it has: a plain
     * `Record` would claim every key is present and let a caller read a missing
     * aggregate as though the endpoint had answered. Callers that require a stat
     * must assert it is present rather than testing it into an `if`.
     */
    async getThreadsStats(
      args: { projectId: string; filters?: BackendFilter[] } & ReadWindow,
    ): Promise<Partial<Record<string, ThreadStatValue>>> {
      const stats = await opik.api.traces.getTraceThreadStats({
        projectId: args.projectId,
        ...(args.filters?.length ? { filters: JSON.stringify(args.filters) } : {}),
        ...(args.fromTime ? { fromTime: args.fromTime } : {}),
        ...(args.toTime ? { toTime: args.toTime } : {}),
      });
      return Object.fromEntries(
        (stats.stats ?? []).map((s) => {
          const value = (s as { value?: ThreadStatValue }).value;
          return [String(s.name ?? ''), value ?? null];
        }),
      );
    },

    /**
     * Trace ids visible for a project under `filters` and an optional window —
     * `GET /v1/private/traces`. Returned as ids only: these tests assert *which*
     * traces a scoped view is entitled to, never their content.
     */
    async listTraceIds(
      args: { projectId: string; filters?: BackendFilter[]; size?: number } & ReadWindow,
    ): Promise<string[]> {
      const page = await opik.api.traces.getTracesByProject({
        projectId: args.projectId,
        size: args.size ?? 200,
        page: 1,
        truncate: true,
        ...(args.filters?.length ? { filters: JSON.stringify(args.filters) } : {}),
        ...(args.fromTime ? { fromTime: args.fromTime } : {}),
        ...(args.toTime ? { toTime: args.toTime } : {}),
      });
      return (page.content ?? []).map((t) => String(t.id));
    },

    /**
     * Create a trace with an explicit id and `source`. The SDK bridge always
     * emits `source=sdk`; the optimization-trial overlay filters on
     * `source=optimization`, so a trial-log fixture cannot be built through the
     * bridge and has to go through the REST write directly.
     *
     * The id is caller-supplied because `createTrace` returns 204 with no body,
     * and these tests assert on exact trace ids.
     *
     * Written through `rawFetch` rather than the pinned SDK because the SDK
     * validates the request body against `JsonListStringWrite`, which admits an
     * object, an array OF OBJECTS, or a string — while the endpoint itself
     * stores a bare `JsonNode` and happily accepts a number or an array of
     * scalars. Those are exactly the shapes
     * `online-evaluation-non-object-sections.spec.ts` exists to seed, and the
     * SDK rejects them client-side before the request is made.
     */
    async createTraceWithSource(args: {
      id: string;
      projectName: string;
      name: string;
      source: 'sdk' | 'experiment' | 'playground' | 'optimization';
      input?: TraceJsonSection;
      output?: TraceJsonSection;
      metadata?: Record<string, unknown>;
      startTime?: Date;
      /**
       * Set this to make the trace eligible for online scoring.
       * `OnlineScoringSampler.onTracesCreated` drops every trace with a null
       * `end_time` as a partial write, so a trace seeded without one is never
       * scored — and a scoring spec built on it would assert nothing.
       */
      endTime?: Date;
    }): Promise<string> {
      const { status, message } = await rawFetch('POST', '/v1/private/traces', {
        body: {
          id: args.id,
          project_name: args.projectName,
          name: args.name,
          source: args.source,
          start_time: (args.startTime ?? new Date()).toISOString(),
          ...(args.endTime ? { end_time: args.endTime.toISOString() } : {}),
          ...(args.input === undefined ? {} : { input: args.input }),
          ...(args.output === undefined ? {} : { output: args.output }),
          ...(args.metadata ? { metadata: args.metadata } : {}),
        },
      });
      if (status !== 201) {
        throw new Error(
          `createTraceWithSource: expected 201 for '${args.name}', got ${status}: ${message}`,
        );
      }
      return args.id;
    },

    /**
     * Create an optimization run row directly. Seeding the row rather than
     * launching a real run keeps the trial-scoping tests deterministic and
     * LLM-free — the thing under test is which traces a trial's Logs overlay
     * lists, which has nothing to do with how the optimizer got there.
     */
    async createOptimization(args: {
      id: string;
      name: string;
      datasetName: string;
      projectName: string;
      objectiveName: string;
      status?: 'completed' | 'running';
    }): Promise<string> {
      await opik.api.optimizations.createOptimization({
        id: args.id,
        name: args.name,
        datasetName: args.datasetName,
        projectName: args.projectName,
        objectiveName: args.objectiveName,
        status: args.status ?? 'completed',
      });
      return args.id;
    },

    /**
     * Create an experiment row directly, optionally as a trial of an
     * optimization (`type: 'trial'` + `optimizationId`) — the shape the
     * Optimization run page's Trials tab lists.
     */
    async createExperiment(args: {
      id: string;
      name: string;
      datasetName: string;
      projectName: string;
      type?: 'regular' | 'trial' | 'mini-batch';
      optimizationId?: string;
      /**
       * Trial rows are grouped and numbered from `metadata.candidate_id` and
       * `metadata.step_index` (step 0 is the run's baseline, which is not a
       * numbered trial), so a fixture that needs a specific trial to render has
       * to set them.
       */
      metadata?: Record<string, unknown>;
    }): Promise<string> {
      await opik.api.experiments.createExperiment({
        id: args.id,
        name: args.name,
        datasetName: args.datasetName,
        projectName: args.projectName,
        ...(args.type ? { type: args.type } : {}),
        ...(args.optimizationId ? { optimizationId: args.optimizationId } : {}),
        ...(args.metadata ? { metadata: args.metadata } : {}),
      });
      return args.id;
    },

    /**
     * Link existing traces to an experiment. This is what puts a trace inside an
     * experiment's scope, and therefore what the entity-scoped Logs views read.
     */
    async createExperimentItems(
      items: Array<{ experimentId: string; datasetItemId: string; traceId: string }>,
    ): Promise<void> {
      await opik.api.experiments.createExperimentItems({ experimentItems: items });
    },

    getOptimization: localGetOptimization,

    async pollOptimizationStatus(
      optimizationId: string,
      target: OptimizationStatus,
      opts: PollOptimizationStatusOpts = {},
    ): Promise<OptimizationRef> {
      return pollOptimizationStatus(localGetOptimization, optimizationId, target, opts);
    },

    async deleteOptimization(id: string): Promise<void> {
      try {
        await opik.api.optimizations.deleteOptimizationsById({ ids: [id] });
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    async getAnnotationQueue(id: string): Promise<AnnotationQueueDetail | null> {
      try {
        const q = await opik.api.annotationQueues.getAnnotationQueueById(id);
        return {
          id: String(q.id),
          name: q.name,
          itemsCount: q.itemsCount ?? 0,
          reviewers: (q.reviewers ?? []).map((r) => ({
            username: r.username ?? '',
            itemsScored: r.status ?? 0,
          })),
        };
      } catch (err) {
        if (isNotFoundError(err)) return null;
        throw err;
      }
    },

    async deleteAnnotationQueue(id: string): Promise<void> {
      try {
        await opik.api.annotationQueues.deleteAnnotationQueueBatch({ ids: [id] });
      } catch (err) {
        if (isNotFoundError(err)) return;
        throw err;
      }
    },

    /**
     * Fetch the studio run's logs. The backend returns a presigned URL to a
     * gzipped log object (the optimizer subprocess stdout); this resolves it and
     * gunzips the content.
     *
     * `urlReachable` distinguishes two very different outcomes so tests can
     * assert precisely:
     *  - the backend must always return a `url` (it produced logs) — absence is
     *    a real failure the caller should assert on;
     *  - the object-store host may be unreachable *from the test runner* — on a
     *    local MinIO install the presigned URL uses the internal `minio:9000`
     *    hostname, resolvable only inside the compose network. That's an
     *    environment artifact, not a Studio defect, so the fetch failing is
     *    reported (urlReachable=false) rather than thrown.
     */
    async getOptimizationLogs(
      id: string,
    ): Promise<{ url: string | null; urlReachable: boolean; content: string | null }> {
      const meta = await opik.api.optimizations.getStudioOptimizationLogs(id);
      const url = meta.url ?? null;
      if (!url) return { url: null, urlReachable: false, content: null };
      try {
        const res = await fetch(url);
        if (!res.ok) return { url, urlReachable: false, content: null };
        const content = gunzipSync(Buffer.from(await res.arrayBuffer())).toString('utf8');
        return { url, urlReachable: true, content };
      } catch {
        return { url, urlReachable: false, content: null };
      }
    },
  };
}

function isNotFoundError(err: unknown): boolean {
  return (
    typeof err === 'object' &&
    err !== null &&
    'statusCode' in err &&
    (err as { statusCode: number }).statusCode === 404
  );
}
