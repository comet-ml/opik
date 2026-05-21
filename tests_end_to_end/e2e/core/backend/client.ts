import { Opik } from 'opik';
import { loadEnvConfig } from '../../config/env.config';

export type BackendClient = ReturnType<typeof makeBackendClient>;

export interface ProjectRef {
  id: string;
  name: string;
}

export interface TraceSummary {
  id: string;
  name: string;
  projectId: string;
}

export interface SpanRecord {
  id: string;
  type: 'general' | 'tool' | 'llm' | 'guardrail';
  name: string;
  input: unknown;
  output: unknown;
  errorInfo: { exception_type: string; message: string } | null;
}

export function makeBackendClient(apiKey: string | null = null) {
  const env = loadEnvConfig();
  const opik = new Opik({
    apiKey: apiKey ?? env.apiKey ?? undefined,
    workspaceName: env.workspace,
    apiUrl: env.apiBaseUrl,
  });

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

    async getProject(id: string): Promise<ProjectRef> {
      const project = await opik.api.projects.getProjectById(id);
      return { id: String(project.id), name: project.name as string };
    },

    async getTrace(id: string): Promise<TraceSummary> {
      const trace = await opik.api.traces.getTraceById(id);
      return {
        id: String(trace.id),
        name: trace.name as string,
        projectId: String(trace.projectId),
      };
    },

    async getTraceSpans(traceId: string, projectId?: string): Promise<SpanRecord[]> {
      const resolvedProjectId =
        projectId ?? String((await opik.api.traces.getTraceById(traceId)).projectId);
      const page = await opik.api.spans.getSpansByProject({
        traceId,
        projectId: resolvedProjectId,
        size: 500,
      });
      const content = page.content ?? [];
      return content.map((s) => ({
        id: String(s.id),
        type: (s.type ?? 'general') as SpanRecord['type'],
        name: (s.name ?? '') as string,
        input: s.input,
        output: s.output,
        errorInfo: s.errorInfo
          ? {
              exception_type: (s.errorInfo as { exceptionType?: string }).exceptionType ?? '',
              message: (s.errorInfo as { message?: string }).message ?? '',
            }
          : null,
      }));
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
