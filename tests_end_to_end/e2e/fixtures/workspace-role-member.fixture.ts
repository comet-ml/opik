import type { Browser, BrowserContext, Page } from '@playwright/test';
import { test as base } from './base.fixture';
import type { EnvConfig } from '../config/env.config';
import {
  addUserToWorkspace,
  assignWorkspaceRole,
  deleteCometUser,
  getWorkspaceIds,
  loginCometUser,
  signUpCometUser,
  WORKSPACE_ROLE_ID,
  type WorkspaceRoleId,
} from '../core/comet/client';

/**
 * Provisions the 4 workspace-role holders (Manage/Write/Annotate/Read) used
 * by `tests/admin-dashboard/workspace-roles-permissions.spec.ts` — once per
 * worker via a worker-scoped fixture, not a per-test factory (see the plan's
 * "User provisioning strategy": 4 disposable users total for the whole file,
 * not one per screen/test).
 *
 * Worker-scoped is only "once per file" if that file's tests are pinned to a
 * single worker — the spec file forces this with
 * `test.describe.configure({ mode: 'serial' })`, both because its 5 tests
 * share one admin `Page` (Configuration → Members, would race under
 * `fullyParallel: true` otherwise) and so this fixture's first access creates
 * the 4 users exactly once for the whole run of that describe block.
 */

export interface WorkspaceRoleMember {
  role: WorkspaceRoleId;
  username: string;
  email: string;
  password: string;
  apiKey: string;
  context: BrowserContext;
  page: Page;
}

export interface WorkspaceRoleTestContext {
  organizationId: string;
  workspaceId: string;
  adminContext: BrowserContext;
  adminPage: Page;
  manage: WorkspaceRoleMember;
  write: WorkspaceRoleMember;
  annotate: WorkspaceRoleMember;
  read: WorkspaceRoleMember;
}

/**
 * Whether this env has everything needed to drive these tests: an org-admin
 * session (adminEmail/adminPassword) and the superuser admin API
 * (adminApiKey/adminBaseUrl) for guaranteed cleanup. All four are required —
 * partial config still hard-skips, since a run that can create users but not
 * delete them would leak disposable accounts into a real environment.
 */
export function hasWorkspaceRoleTestCredentials(env: EnvConfig): boolean {
  return Boolean(env.adminEmail && env.adminPassword && env.adminApiKey && env.adminBaseUrl);
}

const ROLE_PREFIX: Record<WorkspaceRoleId, string> = {
  [WORKSPACE_ROLE_ID.MANAGE]: 'manage',
  [WORKSPACE_ROLE_ID.WRITE]: 'write',
  [WORKSPACE_ROLE_ID.ANNOTATE]: 'annotate',
  [WORKSPACE_ROLE_ID.READ]: 'read',
};

async function provisionMember(
  browser: Browser,
  adminContext: BrowserContext,
  workspaceId: string,
  role: WorkspaceRoleId,
): Promise<WorkspaceRoleMember> {
  const credentials = await signUpCometUser(ROLE_PREFIX[role]);
  await addUserToWorkspace(adminContext.request, workspaceId, credentials.username);
  await assignWorkspaceRole(adminContext.request, credentials.username, workspaceId, role);

  const context = await browser.newContext();
  const page = await context.newPage();
  const apiKey = await loginCometUser(context.request, credentials.email, credentials.password);

  return { role, ...credentials, apiKey, context, page };
}

export interface WorkspaceRoleFixtures {
  workspaceRoleMembers: WorkspaceRoleTestContext;
}

export const test = base.extend<{}, WorkspaceRoleFixtures>({
  workspaceRoleMembers: [
    async ({ browser, envConfig }, use) => {
      // test.skip() is callable from within a fixture — it applies to
      // whichever test is currently resolving this fixture. Referenced via
      // `base` (not the `test` this file itself defines) to avoid a
      // self-referential import.
      base.skip(
        envConfig.deployment === 'oss',
        'Workspace roles/permissions management do not exist on plain oss deployments',
      );
      base.skip(
        !hasWorkspaceRoleTestCredentials(envConfig),
        'ADMIN_API_KEY/ADMIN_BASE_URL/USER_EMAIL/PASSWORD not fully configured for this env — ' +
          'skipping rather than creating disposable users we could not guarantee cleaning up',
      );

      const adminContext = await browser.newContext();
      const adminPage = await adminContext.newPage();
      await loginCometUser(adminContext.request, envConfig.adminEmail!, envConfig.adminPassword!);
      const { organizationId, workspaceId } = await getWorkspaceIds(adminContext.request, envConfig.workspace);

      const [manage, write, annotate, read] = await Promise.all([
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.MANAGE),
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.WRITE),
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.ANNOTATE),
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.READ),
      ]);

      await use({ organizationId, workspaceId, adminContext, adminPage, manage, write, annotate, read });

      const members = [manage, write, annotate, read];
      for (const member of members) {
        await member.context.close();
      }
      await adminContext.close();
      // Sequential, not Promise.all: keeps the admin API key's rate limit
      // happy and surfaces exactly which user failed to delete if one does.
      for (const member of members) {
        await deleteCometUser(member.username);
      }
    },
    { scope: 'worker' },
  ],
});

export { expect } from './base.fixture';
