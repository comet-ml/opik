import { randomBytes } from 'node:crypto';
import type { APIRequestContext } from '@playwright/test';
import { loadEnvConfig } from '../../config/env.config';

/**
 * comet-backend (root domain, e.g. https://staging.dev.comet.com) org/user/
 * workspace-role client — distinct from ../backend/client.ts, which talks to
 * opik-backend under {baseUrl}/opik/api. Only covers what the workspace-role
 * permission tests need: creating disposable users, resolving org/workspace
 * ids, assigning a workspace role, and reading back effective permissions.
 */

export const WORKSPACE_ROLE_ID = {
  MANAGE: 'workspace-manage',
  WRITE: 'workspace-write',
  ANNOTATE: 'workspace-annotate',
  READ: 'workspace-read',
} as const;

export type WorkspaceRoleId = (typeof WORKSPACE_ROLE_ID)[keyof typeof WORKSPACE_ROLE_ID];

export interface CometUserCredentials {
  username: string;
  email: string;
  password: string;
}

export interface WorkspacePermissionEntry {
  permissionName: string;
  permissionValue: 'true' | 'false';
}

export interface WorkspacePermissions {
  workspaceName: string;
  permissions: WorkspacePermissionEntry[];
}

/** Strips a trailing `/opik` path segment — comet-backend lives at the root domain, not under it. */
export function cometRootBaseUrl(): string {
  const env = loadEnvConfig();
  return env.baseUrl.replace(/\/opik$/, '');
}

function randomSuffix(): string {
  return BigInt(`0x${randomBytes(8).toString('hex')}`).toString(36).slice(0, 7);
}

/**
 * `randomSuffix()` occasionally emits a run of the same character back to
 * back (e.g. "aa"), which staging's password policy rejects with
 * ILLEGAL_REPEATED_CHARS. Collapsing any such run to a single char guarantees
 * it can never trip that rule, regardless of what random.toString(36) produces.
 */
function randomPassword(): string {
  const collapsed = `${randomSuffix()}${randomSuffix()}${randomSuffix()}`.replace(/(.)\1+/g, '$1');
  return `Aa1!${collapsed}`;
}

/**
 * Self-serve signup (`POST /api/auth/new`) — no auth required, mirrors
 * comet-automation-tests' `utils/APIMethods/user.py:create_user`. Returns
 * credentials only; call `loginCometUser` separately to mint a session/apiKey.
 */
export async function signUpCometUser(rolePrefix: string): Promise<CometUserCredentials> {
  const root = cometRootBaseUrl();
  const username = `e2e-${rolePrefix}-${randomSuffix()}`;
  const email = `automation-test+${username}@comet-mail.com`;
  const password = randomPassword();

  const res = await fetch(`${root}/api/auth/new?sendEmail=false`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ userName: username, email, plainTextPassword: password }),
  });
  if (!res.ok) {
    throw new Error(
      `signUpCometUser("${username}") failed (${res.status}): ${(await res.text()).slice(0, 300)}`,
    );
  }
  return { username, email, password };
}

/**
 * Logs in via `POST /api/auth/login` using the given request context, so any
 * session cookie lands on that context's browser context (for UI-driving
 * sessions). Returns the minted API key from the response.
 */
export async function loginCometUser(
  request: APIRequestContext,
  email: string,
  password: string,
): Promise<string> {
  const root = cometRootBaseUrl();
  const res = await request.post(`${root}/api/auth/login`, {
    data: { email, plainTextPassword: password },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!res.ok()) {
    throw new Error(
      `loginCometUser("${email}") failed (${res.status()}): ${(await res.text()).slice(0, 300)}`,
    );
  }
  const json = (await res.json()) as { apiKeys?: string[] };
  const apiKey = json.apiKeys?.[0];
  if (!apiKey) {
    throw new Error(`loginCometUser("${email}"): login response had no apiKeys`);
  }
  return apiKey;
}

export interface WorkspaceIds {
  workspaceId: string;
  organizationId: string;
}

/** Resolves workspace/org ids for `workspaceName`, as seen by the session on `request`. */
export async function getWorkspaceIds(
  request: APIRequestContext,
  workspaceName: string,
): Promise<WorkspaceIds> {
  const root = cometRootBaseUrl();
  const res = await request.get(`${root}/api/workspaces`);
  if (!res.ok()) {
    throw new Error(`getWorkspaceIds failed (${res.status()}): ${(await res.text()).slice(0, 300)}`);
  }
  const workspaces = (await res.json()) as Array<{
    workspaceId: string;
    workspaceName: string;
    organizationId: string;
  }>;
  const match = workspaces.find((w) => w.workspaceName === workspaceName);
  if (!match) {
    throw new Error(
      `getWorkspaceIds: workspace "${workspaceName}" not visible to this session. Available: ${workspaces
        .map((w) => w.workspaceName)
        .join(', ')}`,
    );
  }
  return { workspaceId: match.workspaceId, organizationId: match.organizationId };
}

/**
 * Adds `userName` to the workspace via the LLM-only invite path
 * (`POST /api/workspaces/invite-usernames-llm`) — unmetered, avoids the
 * Stripe-billing 500s the regular add-to-workspace path can hit on orgs with
 * a stale Stripe customer record (same workaround the org-role tests use).
 * The user lands with a default workspace role (typically WRITE); call
 * `assignWorkspaceRole` afterward to set the role under test.
 */
export async function addUserToWorkspace(
  request: APIRequestContext,
  workspaceId: string,
  userName: string,
): Promise<void> {
  const root = cometRootBaseUrl();
  const res = await request.post(`${root}/api/workspaces/invite-usernames-llm`, {
    data: { userNames: [userName], workspaceId },
  });
  if (!res.ok()) {
    throw new Error(
      `addUserToWorkspace("${userName}") failed (${res.status()}): ${(await res.text()).slice(0, 300)}`,
    );
  }
}

export async function assignWorkspaceRole(
  request: APIRequestContext,
  userName: string,
  workspaceId: string,
  roleId: WorkspaceRoleId,
): Promise<void> {
  const root = cometRootBaseUrl();
  const res = await request.put(`${root}/api/workspace-roles/user/${encodeURIComponent(userName)}`, {
    data: { roleId, workspaceId },
  });
  if (!res.ok()) {
    throw new Error(
      `assignWorkspaceRole("${userName}", ${roleId}) failed (${res.status()}): ${(await res.text()).slice(0, 300)}`,
    );
  }
}

/** The full per-workspace permission catalog for `userName`, as this org's admin would see it. */
export async function getUserPermissions(
  request: APIRequestContext,
  organizationId: string,
  userName: string,
): Promise<WorkspacePermissions[]> {
  const root = cometRootBaseUrl();
  const res = await request.get(
    `${root}/api/permissions/organization/${organizationId}/user/${encodeURIComponent(userName)}`,
  );
  if (!res.ok()) {
    throw new Error(
      `getUserPermissions("${userName}") failed (${res.status()}): ${(await res.text()).slice(0, 300)}`,
    );
  }
  const json = (await res.json()) as { userPermissions?: WorkspacePermissions[] };
  return json.userPermissions ?? [];
}

/** Looks up a single permission's boolean value for a given workspace from `getUserPermissions`'s result. */
export function getPermissionValue(
  permissions: WorkspacePermissions[],
  workspaceName: string,
  permissionName: string,
): boolean {
  const entry = permissions
    .find((p) => p.workspaceName === workspaceName)
    ?.permissions.find((p) => p.permissionName === permissionName);
  return entry?.permissionValue === 'true';
}

/**
 * Cleanup via the superuser admin API (`DELETE {adminBaseUrl}/delete-user`).
 * The workspace-role-member fixture hard-skips the whole suite when
 * `adminApiKey`/`adminBaseUrl` aren't configured (see
 * `fixtures/workspace-role-member.fixture.ts`) specifically so this always
 * has what it needs to run — disposable users must not be left behind in a
 * real environment. This throws (rather than warns) if called anyway with
 * either missing, since that indicates the skip gate was bypassed.
 */
export async function deleteCometUser(username: string): Promise<void> {
  const env = loadEnvConfig();
  if (!env.adminApiKey || !env.adminBaseUrl) {
    throw new Error(
      `deleteCometUser("${username}"): ADMIN_API_KEY/ADMIN_BASE_URL not configured — the workspace-role-member fixture should have skipped before creating this user.`,
    );
  }
  const res = await fetch(`${env.adminBaseUrl}delete-user?userName=${encodeURIComponent(username)}`, {
    method: 'DELETE',
    headers: { Authorization: env.adminApiKey },
  });
  if (!res.ok) {
    console.warn(
      `[cometClient] deleteCometUser("${username}") failed (${res.status}): ${(await res.text()).slice(0, 300)}`,
    );
  }
}
