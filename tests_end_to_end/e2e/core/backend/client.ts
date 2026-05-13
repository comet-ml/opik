import createClient from 'openapi-fetch';
import type { paths } from './schema';
import { loadEnvConfig } from '../../config/env.config';

export type BackendClient = ReturnType<typeof makeBackendClient>;

export interface ProjectRef {
  id: string;
  name: string;
}

export function makeBackendClient(apiKey: string | null = null) {
  const env = loadEnvConfig();
  const headers: Record<string, string> = { 'Content-Type': 'application/json' };
  if (apiKey) headers['Authorization'] = apiKey;
  if (env.workspace) headers['Comet-Workspace'] = env.workspace;

  const client = createClient<paths>({
    baseUrl: env.apiBaseUrl,
    headers,
  });

  return {
    raw: client,

    async createProject(name: string, description?: string): Promise<void> {
      const { error } = await client.POST('/v1/private/projects', {
        body: { name, ...(description ? { description } : {}) },
      });
      if (error) {
        throw new Error(`createProject failed: ${JSON.stringify(error)}`);
      }
    },

    async deleteProject(id: string): Promise<void> {
      const { error, response } = await client.DELETE('/v1/private/projects/{id}', {
        params: { path: { id } },
      });
      if (error && response.status !== 404) {
        throw new Error(`deleteProject failed: ${JSON.stringify(error)}`);
      }
    },

    async listProjectsWithPrefix(prefix: string): Promise<ProjectRef[]> {
      const { data, error } = await client.GET('/v1/private/projects', {
        params: { query: { name: prefix, size: 500 } },
      });
      if (error) throw new Error(`listProjects failed: ${JSON.stringify(error)}`);
      const content = data?.content ?? [];
      return content
        .filter((p) => p.name.startsWith(prefix))
        .map((p) => ({ id: String(p.id), name: p.name }));
    },
  };
}
