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
import { uuid7 } from './uuid7';

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
}

/**
 * One row of an automation rule's user-facing log — the same payload the
 * Automation logs page renders, from `GET /automations/evaluators/{id}/logs`.
 *
 * `markers` is where the per-trace attribution lives (`trace_id`), which is what
 * makes "this trace failed and that one did not" assertable at all.
 */
export interface AutomationRuleLogRef {
  level: string;
  message: string;
  markers: Record<string, string>;
}

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

  // Hoisted so createPythonAutomationRule can read its own rule back without
  // going through the not-yet-constructed return object.
  const localListAutomationRules = async (projectId: string): Promise<AutomationRuleRef[]> => {
    const page = await opik.api.automationRuleEvaluators.findEvaluators({
      projectId,
      size: 500,
    });
    return (page.content ?? []).map((r) => ({
      id: String(r.id),
      name: r.name,
      projectIds: (r.projects ?? []).map((p) => String(p.projectId)),
      enabled: r.enabled ?? true,
    }));
  };

  /**
   * Raw REST against the traces resource, for the payload shapes the pinned TS
   * SDK's types cannot express.
   *
   * `TraceWrite.output` is typed `Record<string, unknown> | Record<string,
   * unknown>[] | string`, so a trace whose output is a bare JSON number or an
   * array of scalars is unrepresentable — and those shapes are exactly what the
   * scalar-section specs exist to drive. Same escape hatch, and same reason, as
   * `getProjectStats` below.
   */
  const rawTraceFetch = async (
    method: 'GET' | 'POST',
    path: string,
    body?: unknown,
  ): Promise<unknown> => {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Comet-Workspace': env.workspace,
    };
    const key = apiKey ?? env.apiKey;
    if (key) headers['Authorization'] = key;

    const res = await fetch(`${env.apiBaseUrl}${path}`, {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    const text = await res.text();
    if (!res.ok) {
      throw new Error(`${method} ${path} -> ${res.status}: ${text.slice(0, 300)}`);
    }
    return text.trim() ? JSON.parse(text) : null;
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

    listAutomationRulesForProject: localListAutomationRules,

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
     * Create a Python-code online-evaluation rule and return it, id included.
     *
     * The API rather than the create-rule dialog because these specs are about
     * what the scoring engine extracts, not about the dialog: the dialog owns
     * the variable mapping (`OnlineEvaluationPage.setVariableMapping` always
     * writes `output.output`), so a spec that needs the whole-section mapping
     * `output` can only state it here. Driving the dialog is covered by
     * online-evaluation-smoke.
     *
     * `createAutomationRuleEvaluator` answers 201 with no body, so the rule is
     * read back by name — which doubles as proof it landed on this project.
     */
    async createPythonAutomationRule(args: {
      projectId: string;
      name: string;
      /** Full Python source of a single `BaseMetric` subclass. */
      metric: string;
      /** Variable mapping: `score()` parameter name -> trace path. */
      metricArguments: Record<string, string>;
      samplingRate?: number;
    }): Promise<AutomationRuleRef> {
      await opik.api.automationRuleEvaluators.createAutomationRuleEvaluator({
        type: 'user_defined_metric_python',
        action: 'evaluator',
        name: args.name,
        projectIds: [args.projectId],
        enabled: true,
        samplingRate: args.samplingRate ?? 1.0,
        code: { metric: args.metric, arguments: args.metricArguments },
      });

      const rules = await localListAutomationRules(args.projectId);
      const created = rules.find((r) => r.name === args.name);
      if (!created) {
        throw new Error(
          `createPythonAutomationRule: rule '${args.name}' is not listed under project ` +
            `${args.projectId} after a successful create — it did not land on this project.`,
        );
      }
      return created;
    },

    /**
     * Every user-facing log line a rule has emitted, newest first.
     *
     * Read as one page (the endpoint defaults to 1000 rows and these specs
     * produce single digits) so a caller can assert on the WHOLE set — "no ERROR
     * row for this trace" is only true if nothing was left unread.
     */
    async listAutomationRuleLogs(ruleId: string): Promise<AutomationRuleLogRef[]> {
      const page = await opik.api.automationRuleEvaluators.getEvaluatorLogsById(ruleId);
      return (page.content ?? []).map((item) => ({
        level: String(item.level ?? ''),
        message: item.message ?? '',
        markers: item.markers ?? {},
      }));
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
     */
    async createTraceWithSource(args: {
      id: string;
      projectName: string;
      name: string;
      source: 'sdk' | 'experiment' | 'playground' | 'optimization';
      input?: Record<string, unknown>;
      output?: Record<string, unknown>;
      startTime?: Date;
    }): Promise<string> {
      await opik.api.traces.createTrace({
        id: args.id,
        projectName: args.projectName,
        name: args.name,
        source: args.source,
        startTime: args.startTime ?? new Date(),
        ...(args.input ? { input: args.input } : {}),
        ...(args.output ? { output: args.output } : {}),
      });
      return args.id;
    },

    /**
     * Create a completed trace whose `output` is an arbitrary JSON value — a
     * bare string, a number, an array, or an object.
     *
     * Neither the Python bridge nor the typed client can produce these shapes:
     * `@opik.track` wraps a scalar return as `{"output": ...}` before it ever
     * reaches the wire, and `TraceWrite.output`'s type union excludes numbers
     * and scalar arrays. A trace whose output is a bare JSON value is an
     * ordinary production shape (any non-decorated ingestion writes one), and it
     * takes a different branch of the online-scoring extractor, so it has to be
     * seedable.
     *
     * `endTime` is always sent: `OnlineScoringSampler.onTracesCreated` drops
     * every trace with a null `end_time` as incomplete, so a trace seeded
     * without one is silently never scored — which reads as "the rule is broken"
     * rather than "the fixture was".
     */
    async createTraceWithRawOutput(args: {
      projectName: string;
      name: string;
      output: unknown;
      input?: Record<string, unknown>;
      /** Defaults to a fresh v7 id, returned so the caller can assert on it. */
      id?: string;
      startTime?: Date;
    }): Promise<string> {
      const id = args.id ?? uuid7();
      const startTime = args.startTime ?? new Date();
      await rawTraceFetch('POST', '/v1/private/traces', {
        id,
        project_name: args.projectName,
        name: args.name,
        start_time: startTime.toISOString(),
        end_time: new Date(startTime.getTime() + 1_000).toISOString(),
        ...(args.input ? { input: args.input } : {}),
        output: args.output,
      });
      return id;
    },

    /**
     * The trace's `output` exactly as stored, with no type coercion.
     *
     * `getTrace` cannot answer this: `TraceDetail.output` would have to be typed
     * through the same union that made the write unrepresentable. Specs use this
     * to prove the seeded shape really survived ingestion before asserting on
     * what the scoring engine did with it — a bare string that had been wrapped
     * into an object on the way in would leave the spec asserting the object
     * branch while claiming to cover the scalar one.
     */
    async getTraceRawOutput(traceId: string): Promise<unknown> {
      const trace = await rawTraceFetch('GET', `/v1/private/traces/${traceId}`);
      return (trace as { output?: unknown } | null)?.output;
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
