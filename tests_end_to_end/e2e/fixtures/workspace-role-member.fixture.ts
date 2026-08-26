import type { Browser, BrowserContext, Page } from '@playwright/test';
import { test as base } from './base.fixture';
import type { EnvConfig } from '../config/env.config';
import { shouldLeaveArtifacts } from '../core/artifacts';
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
import { registerPendingUser, clearPendingUser } from '../core/comet/pending-users-registry';

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
  workspaceName: string;
  /**
   * The org-admin's own minted opik-backend API key, scoped to workspaceName.
   * Named distinctly from EnvConfig.deleteUserApiKey (the superuser
   * delete-user key) — the two are unrelated credentials that happen to
   * both be "admin".
   */
  adminOpikApiKey: string;
  adminContext: BrowserContext;
  adminPage: Page;
  manage: WorkspaceRoleMember;
  write: WorkspaceRoleMember;
  annotate: WorkspaceRoleMember;
  read: WorkspaceRoleMember;
}

/**
 * Whether this env has everything needed to drive these tests: an org-admin
 * session (adminEmail/adminPassword) targeting its own workspace
 * (adminWorkspace — never the baseline suite's `workspace`, so the two
 * credential sets never have to share an org) and the superuser admin API
 * (deleteUserApiKey/deleteUserBaseUrl) for guaranteed cleanup. All five are
 * required — partial config still hard-skips, since a run that can create
 * users but not delete them would leak disposable accounts into a real
 * environment.
 */
export function hasWorkspaceRoleTestCredentials(env: EnvConfig): boolean {
  return Boolean(env.adminEmail && env.adminPassword && env.deleteUserApiKey && env.deleteUserBaseUrl && env.adminWorkspace);
}

const ROLE_PREFIX: Record<WorkspaceRoleId, string> = {
  [WORKSPACE_ROLE_ID.MANAGE]: 'manage',
  [WORKSPACE_ROLE_ID.WRITE]: 'write',
  [WORKSPACE_ROLE_ID.ANNOTATE]: 'annotate',
  [WORKSPACE_ROLE_ID.READ]: 'read',
};

/**
 * Cleans up its own partial state on failure (signed-up user, browser
 * context) rather than leaving it for the caller — a rejection from here must
 * never leak a disposable account or context, whichever step it happened at.
 */
async function provisionMember(
  browser: Browser,
  adminContext: BrowserContext,
  workspaceId: string,
  role: WorkspaceRoleId,
): Promise<WorkspaceRoleMember> {
  const credentials = await signUpCometUser(ROLE_PREFIX[role]);
  await registerPendingUser(credentials.username);
  try {
    await addUserToWorkspace(adminContext.request, workspaceId, credentials.username);
    await assignWorkspaceRole(adminContext.request, credentials.username, workspaceId, role);

    const context = await browser.newContext();
    try {
      const page = await context.newPage();
      const apiKey = await loginCometUser(context.request, credentials.email, credentials.password);
      return { role, ...credentials, apiKey, context, page };
    } catch (err) {
      await context.close().catch(() => undefined);
      throw err;
    }
  } catch (err) {
    await deleteCometUser(credentials.username)
      .then(() => clearPendingUser(credentials.username))
      .catch((cleanupErr) => console.warn(`[provisionMember] rollback: failed to delete "${credentials.username}":`, cleanupErr));
    throw err;
  }
}

export interface WorkspaceRoleFixtures {
  workspaceRoleMembers: WorkspaceRoleTestContext;
}

/** Worker-scoped so every test in the serial describe block can observe whether any of them failed, mirroring `shouldLeaveArtifacts` (test-scoped) for this worker-scoped fixture. */
interface LeaveFailuresState {
  leave: boolean;
}

export const test = base.extend<{ trackLeaveFailures: void }, WorkspaceRoleFixtures & { leaveFailuresState: LeaveFailuresState }>({
  leaveFailuresState: [
    async ({}, use) => {
      await use({ leave: false });
    },
    { scope: 'worker' },
  ],

  // Auto so it observes every test in this file's worker without each test
  // opting in — a worker-scoped fixture cannot read `testInfo` itself, so this
  // test-scoped fixture relays the one bit `workspaceRoleMembers`' teardown needs.
  trackLeaveFailures: [
    async ({ leaveFailuresState }, use, testInfo) => {
      await use();
      if (shouldLeaveArtifacts(testInfo)) leaveFailuresState.leave = true;
    },
    { auto: true },
  ],

  workspaceRoleMembers: [
    async ({ browser, envConfig, leaveFailuresState }, use) => {
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
        'ADMIN_API_KEY/ADMIN_BASE_URL/OPIK_PERM_USER_EMAIL/OPIK_PERM_USER_PASSWORD/WORKSPACE_ROLES_WORKSPACE not fully ' +
          'configured for this env — skipping rather than creating disposable users we could not guarantee cleaning up',
      );

      const adminContext = await browser.newContext();
      const adminPage = await adminContext.newPage();
      const adminOpikApiKey = await loginCometUser(adminContext.request, envConfig.adminEmail!, envConfig.adminPassword!);
      const workspaceName = envConfig.adminWorkspace!;
      const { organizationId, workspaceId } = await getWorkspaceIds(adminContext.request, workspaceName);

      const results = await Promise.allSettled([
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.MANAGE),
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.WRITE),
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.ANNOTATE),
        provisionMember(browser, adminContext, workspaceId, WORKSPACE_ROLE_ID.READ),
      ]);

      const rejected = results.filter((r): r is PromiseRejectedResult => r.status === 'rejected');
      if (rejected.length > 0) {
        // provisionMember already cleaned up its own partial state on
        // failure — this only has to roll back the siblings that fully
        // succeeded, or they'd be orphaned by the throw below.
        const fulfilled = results.filter((r): r is PromiseFulfilledResult<WorkspaceRoleMember> => r.status === 'fulfilled');
        for (const r of fulfilled) {
          await r.value.context.close().catch(() => undefined);
          await deleteCometUser(r.value.username)
            .then(() => clearPendingUser(r.value.username))
            .catch((cleanupErr) => console.warn(`[workspaceRoleMembers] rollback: failed to delete "${r.value.username}":`, cleanupErr));
        }
        await adminContext.close().catch(() => undefined);
        throw new Error(
          `workspaceRoleMembers: failed to provision ${rejected.length}/4 role members: ` +
            rejected.map((r) => (r.reason instanceof Error ? r.reason.message : String(r.reason))).join('; '),
        );
      }

      const [manage, write, annotate, read] = results.map((r) => (r as PromiseFulfilledResult<WorkspaceRoleMember>).value);

      await use({
        organizationId,
        workspaceId,
        workspaceName,
        adminOpikApiKey,
        adminContext,
        adminPage,
        manage,
        write,
        annotate,
        read,
      });

      if (leaveFailuresState.leave) {
        console.warn(
          '[workspaceRoleMembers] OPIK_LEAVE_FAILURES=true and a test in this suite failed — leaving role users/contexts for debugging',
        );
        return;
      }

      const members = [manage, write, annotate, read];
      for (const member of members) {
        await member.context.close();
      }
      await adminContext.close();
      // Sequential, not Promise.all: keeps the admin API key's rate limit
      // happy and surfaces exactly which user failed to delete if one does.
      for (const member of members) {
        await deleteCometUser(member.username);
        await clearPendingUser(member.username);
      }
    },
    { scope: 'worker' },
  ],
});

export { expect } from './base.fixture';
