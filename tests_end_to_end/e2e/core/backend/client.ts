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
import { uuidV7 } from './ids';

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
  /**
   * Whole-run spend: the trial experiments' cost plus optimizer-internal traces
   * attributed to the run by tag, deduplicated against the trials
   * (`Optimization.total_optimization_cost`). This is the figure the run page's
   * "Optimization cost" card and the runs list's "Opt. cost" column render, and
   * it can legitimately exceed the sum of the run's trials. `null` only when the
   * backend omits it (a build older than 2.2.28); the aggregate itself answers 0
   * rather than null when there is nothing to report.
   */
  totalOptimizationCost: number | null;
}

/** The experiment fields the optimization cost aggregate is built out of. */
export interface ExperimentDetail {
  id: string;
  name: string;
  /** `regular` | `trial` | `mini-batch` | `mutation` — trials are what an optimization rolls up. */
  type: string | null;
  optimizationId: string | null;
  totalEstimatedCost: number | null;
}

/** One experiment item, linking a dataset item to the trace that answered it. */
export interface ExperimentItemLink {
  datasetItemId: string;
  traceId: string;
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
   * Shared by the getById and the list read paths on purpose: the backend
   * computes the same fields through two different queries (`FIND` vs
   * `FIND_WITHOUT_EXPERIMENTS`), and a spec comparing them must not be comparing
   * two different mappings as well.
   */
  const toOptimizationRef = (o: {
    id?: string;
    name?: string;
    status: unknown;
    objectiveName?: string;
    datasetName?: string;
    numTrials?: number;
    baselineObjectiveScore?: number;
    bestObjectiveScore?: number;
    totalOptimizationCost?: number;
  }): OptimizationRef => ({
    id: String(o.id),
    name: o.name ?? '',
    status: String(o.status) as OptimizationStatus,
    objectiveName: o.objectiveName ?? null,
    datasetName: o.datasetName ?? null,
    numTrials: Number(o.numTrials ?? 0),
    baselineObjectiveScore: o.baselineObjectiveScore ?? null,
    bestObjectiveScore: o.bestObjectiveScore ?? null,
    totalOptimizationCost: o.totalOptimizationCost ?? null,
  });

  // Hoisted so the poll helpers (free functions) can call it without depending
  // on the not-yet-constructed return object.
  const localGetOptimization = async (id: string): Promise<OptimizationRef | null> => {
    try {
      return toOptimizationRef(await opik.api.optimizations.getOptimizationById(id));
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

    /**
     * Create an experiment directly, and return its id. `type: 'trial'` plus an
     * `optimizationId` is what makes it one of an optimization run's trials, and
     * therefore what the run's cost aggregate sums over.
     *
     * The experiment carries no cost of its own — its `totalEstimatedCost` is
     * derived from the spans of the traces linked to it, so seeding a priced
     * trial means creating a priced trace and calling `linkExperimentItems`.
     */
    async createExperiment(args: {
      name: string;
      datasetName: string;
      projectId: string;
      optimizationId?: string;
      type?: 'regular' | 'trial' | 'mini-batch' | 'mutation';
      status?: 'running' | 'completed' | 'cancelled';
    }): Promise<string> {
      const id = uuidV7();
      await opik.api.experiments.createExperiment({
        id,
        name: args.name,
        datasetName: args.datasetName,
        projectId: args.projectId,
        ...(args.optimizationId ? { optimizationId: args.optimizationId } : {}),
        type: (args.type ?? 'regular') as never,
        status: (args.status ?? 'completed') as never,
      });
      return id;
    },

    /** Link traces to an experiment as its items — one item per dataset item answered. */
    async linkExperimentItems(args: {
      experimentId: string;
      links: ExperimentItemLink[];
    }): Promise<void> {
      await opik.api.experiments.createExperimentItems({
        experimentItems: args.links.map((link) => ({
          id: uuidV7(),
          experimentId: args.experimentId,
          datasetItemId: link.datasetItemId,
          traceId: link.traceId,
        })),
      });
    },

    async getExperiment(id: string): Promise<ExperimentDetail | null> {
      try {
        const e = await opik.api.experiments.getExperimentById(id);
        return {
          id: String(e.id),
          name: e.name ?? '',
          type: e.type ? String(e.type) : null,
          optimizationId: e.optimizationId ? String(e.optimizationId) : null,
          totalEstimatedCost: e.totalEstimatedCost ?? null,
        };
      } catch (err) {
        if (isNotFoundError(err)) return null;
        throw err;
      }
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

    /**
     * Create an optimization run directly, and return its id.
     *
     * `projectId` is not optional here even though the API allows omitting it:
     * the cost aggregate prunes its span scan to the project ids of the
     * optimizations in scope, so a run written without one reads back a cost of
     * 0 whatever its traces cost — a seed that silently asserts nothing.
     */
    async createOptimization(args: {
      name: string;
      datasetName: string;
      projectId: string;
      objectiveName?: string;
      status?: OptimizationStatus;
    }): Promise<string> {
      const id = uuidV7();
      await opik.api.optimizations.createOptimization({
        id,
        name: args.name,
        datasetName: args.datasetName,
        projectId: args.projectId,
        objectiveName: args.objectiveName ?? 'equals',
        status: (args.status ?? 'completed') as never,
      });
      return id;
    },

    /**
     * Optimization runs matching a scope — the read path behind the runs list.
     * `projectId` narrows to one project's runs (`?project_id=`), `name` matches
     * a substring of the run name across the workspace; passing neither lists
     * the workspace.
     *
     * Worth knowing when asserting on the result: the backend answers a
     * project-scoped read with a *different query* depending on whether any run
     * in scope has an experiment, and only one of those two queries is the one
     * `getOptimization` uses. That divergence is exactly what a spec should be
     * checking, so this deliberately does not paper over it.
     */
    async listOptimizations(
      args: { projectId?: string; name?: string; size?: number } = {},
    ): Promise<OptimizationRef[]> {
      const page = await opik.api.optimizations.findOptimizations({
        ...(args.projectId ? { projectId: args.projectId } : {}),
        ...(args.name ? { name: args.name } : {}),
        size: args.size ?? 100,
      });
      return (page.content ?? []).map(toOptimizationRef);
    },

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
