import { Opik } from 'opik';
import { loadEnvConfig } from '../../config/env.config';
import {
  pollTraceForFeedbackScore,
  type PollFeedbackScoreOpts,
} from './poll-feedback-score';

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

/** Backend discriminator for Dataset vs Test Suite (shared DB table). */
const TEST_SUITE_TYPE = 'evaluation_suite';

export function makeBackendClient(apiKey: string | null = null) {
  const env = loadEnvConfig();
  const opik = new Opik({
    apiKey: apiKey ?? env.apiKey ?? undefined,
    workspaceName: env.workspace,
    apiUrl: env.apiBaseUrl,
  });

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

    async getDatasetItems(datasetId: string): Promise<DatasetItemRef[]> {
      const page = await opik.api.datasets.getDatasetItems(datasetId);
      const content = page.content ?? [];
      return content.map((item) => ({
        id: String(item.id),
        data: (item.data ?? {}) as Record<string, unknown>,
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
