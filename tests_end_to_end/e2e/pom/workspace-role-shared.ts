import { Opik } from 'opik';
import { loadEnvConfig } from '../config/env.config';
import type { WorkspaceRoleMember } from '../fixtures/workspace-role-member.fixture';

/** Shared by every resource-action factory: which workspace to operate in and the admin identity for `adminRemove` fallback cleanup. */
export interface AdminCtx {
  workspaceName: string;
  adminApiKey: string;
}

export function subjectOpikClient(member: WorkspaceRoleMember, workspaceName: string): Opik {
  const env = loadEnvConfig();
  return new Opik({ apiKey: member.apiKey, workspaceName, apiUrl: env.apiBaseUrl });
}

export function adminOpikClient(adminApiKey: string, workspaceName: string): Opik {
  const env = loadEnvConfig();
  return new Opik({ apiKey: adminApiKey, workspaceName, apiUrl: env.apiBaseUrl });
}

export async function attemptSucceeds(fn: () => Promise<unknown>): Promise<boolean> {
  try {
    await fn();
    return true;
  } catch {
    return false;
  }
}
