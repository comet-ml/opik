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

/** Only a 401/403 is a permission denial; anything else (timeout, 5xx, malformed request) is a real failure the caller must not silently read as "correctly denied". */
export function isAuthorizationError(err: unknown): boolean {
  return (
    typeof err === 'object' &&
    err !== null &&
    'statusCode' in err &&
    ((err as { statusCode?: unknown }).statusCode === 401 || (err as { statusCode?: unknown }).statusCode === 403)
  );
}

export async function attemptSucceeds(fn: () => Promise<unknown>): Promise<boolean> {
  try {
    await fn();
    return true;
  } catch (err) {
    if (isAuthorizationError(err)) return false;
    throw err;
  }
}
