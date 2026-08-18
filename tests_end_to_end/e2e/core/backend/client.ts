import { gunzipSync } from 'node:zlib';
import { Opik } from 'opik';
import { loadEnvConfig } from '../../config/env.config';
import {
  pollTraceForFeedbackScore,
  type PollFeedbackScoreOpts,
} from './poll-feedback-score';
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
}

export interface AutomationRuleRef {
  id: string;
  name: string;
  projectIds: string[];
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

export interface DashboardRef {
  id: string;
  name: string;
}

/**
 * One series of a project-metrics response — the shape a chart widget turns
 * into a line. `name` is the metric sub-series (`traces`, `duration.p50`) or,
 * when the request carries a breakdown, the group value (a tag, a trace name).
 */
export interface ProjectMetricSeriesRef {
  name: string;
  points: Array<{ time: string; value: number | null }>;
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

  /**
   * A raw call to a private REST endpoint the pinned TS SDK does not model.
   * Everything the SDK *does* expose goes through `opik.api.*` — this is only
   * for the gaps (dashboards, the windowed/breakdown metric requests).
   */
  const privateFetch = async (
    method: 'GET' | 'POST' | 'DELETE',
    path: string,
    body?: unknown,
  ): Promise<Response> => {
    const headers: Record<string, string> = {
      Accept: 'application/json',
      'Comet-Workspace': env.workspace,
    };
    if (body !== undefined) headers['Content-Type'] = 'application/json';
    const key = apiKey ?? env.apiKey;
    if (key) headers['Authorization'] = key;

    const res = await fetch(`${env.apiBaseUrl}${path}`, {
      method,
      headers,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    });
    if (!res.ok) {
      throw new Error(
        `${method} ${path} -> ${res.status}: ${(await res.text()).slice(0, 300)}`,
      );
    }
    return res;
  };

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

      const res = await privateFetch('GET', `/v1/private/projects/stats?${params}`);
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
     * The series a project-metrics chart widget would draw — the exact call
     * `useProjectMetric` makes, so a spec can check the data really groups the
     * way it expects before opening a browser to look at the chart.
     *
     * `breakdownField` is a `BREAKDOWN_FIELD` value (`tags`, `name`, …).
     * Omitting it requests the ungrouped series, where `name` is the metric
     * sub-series rather than a group value.
     */
    async getProjectMetricSeries(args: {
      projectId: string;
      metricType: string;
      interval: 'HOURLY' | 'DAILY' | 'WEEKLY' | 'TOTAL';
      intervalStart: Date;
      intervalEnd: Date;
      breakdownField?: string;
    }): Promise<ProjectMetricSeriesRef[]> {
      const res = await privateFetch(
        'POST',
        `/v1/private/projects/${args.projectId}/metrics`,
        {
          metric_type: args.metricType,
          interval: args.interval,
          interval_start: args.intervalStart.toISOString(),
          interval_end: args.intervalEnd.toISOString(),
          ...(args.breakdownField ? { breakdown: { field: args.breakdownField } } : {}),
        },
      );
      const body = (await res.json()) as {
        results?: Array<{ name?: string; data?: Array<{ time: string; value: number | null }> }>;
      };
      return (body.results ?? []).map((r) => ({
        name: String(r.name ?? ''),
        points: r.data ?? [],
      }));
    },

    /**
     * Creates a dashboard from a full config document — the same payload the
     * FE's `useDashboardCreateMutation` posts, so a widget can be seeded
     * already configured instead of being assembled through the editor UI.
     *
     * The id only comes back in the `Location` header (the endpoint answers
     * 201 with an empty body), which is what `extractIdFromLocation` reads.
     */
    async createDashboard(args: {
      name: string;
      type: 'multi_project' | 'experiments';
      config: unknown;
      description?: string;
    }): Promise<DashboardRef> {
      const res = await privateFetch('POST', '/v1/private/dashboards', {
        name: args.name,
        type: args.type,
        config: args.config,
        ...(args.description ? { description: args.description } : {}),
      });
      const location = res.headers.get('location');
      const id = location?.split('/').pop();
      if (!id) {
        throw new Error(
          `POST /v1/private/dashboards returned no usable Location header: ${location}`,
        );
      }
      return { id, name: args.name };
    },

    /**
     * Dashboards are not swept by `global-teardown` (it only knows
     * experiments, datasets and projects) and do not cascade with the project
     * their widgets point at, so whatever creates one must delete it.
     */
    async deleteDashboard(id: string): Promise<void> {
      try {
        await privateFetch('DELETE', `/v1/private/dashboards/${id}`);
      } catch (err) {
        if (isNotFoundMessage(err)) return;
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

/**
 * The 404 check for `privateFetch`, which throws a plain Error carrying the
 * status rather than the SDK's structured `statusCode` — used so a delete of
 * an already-deleted entity stays a no-op, exactly as the SDK-backed deletes do.
 */
function isNotFoundMessage(err: unknown): boolean {
  return err instanceof Error && / -> 404:/.test(err.message);
}

function isNotFoundError(err: unknown): boolean {
  return (
    typeof err === 'object' &&
    err !== null &&
    'statusCode' in err &&
    (err as { statusCode: number }).statusCode === 404
  );
}
