import { test, expect } from '@playwright/test';
import { uuid7 } from '../core/backend';
import type { WorkspaceRoleMember } from '../fixtures/workspace-role-member.fixture';
import { type AdminCtx, subjectOpikClient, adminOpikClient, attemptSucceeds } from './workspace-role-shared';

/**
 * Optimization runs have no distinct "create" permission in the matrix (only
 * canDeleteOptimizationRuns) — each role gets a fresh admin-seeded scratch
 * run rather than sharing one, since MANAGE/WRITE actually delete it and a
 * shared instance would be consumed out from under whichever role runs next
 * in this serial suite.
 */
export async function checkOptimizationDelete(
  member: WorkspaceRoleMember,
  ctx: AdminCtx,
  datasetName: string,
  projectId: string,
  expectSucceeds: boolean,
): Promise<void> {
  await test.step(`Optimization: delete run — ${expectSucceeds ? 'succeeds' : 'denied'}`, async () => {
    const admin = adminOpikClient(ctx.adminApiKey, ctx.workspaceName);
    const id = uuid7();
    await admin.api.optimizations.createOptimization({
      id,
      name: `e2e-${member.role}-opt-scratch-${Date.now()}`,
      datasetName,
      objectiveName: 'accuracy',
      status: 'running',
      projectId,
    });

    const sdk = subjectOpikClient(member, ctx.workspaceName);
    const deleted = await attemptSucceeds(() => sdk.api.optimizations.deleteOptimizationsById({ ids: [id] }));
    expect
      .soft(deleted, `${member.role}: expected optimization delete to ${expectSucceeds ? 'succeed' : 'be denied'}`)
      .toBe(expectSucceeds);
    if (!deleted) {
      await admin.api.optimizations.deleteOptimizationsById({ ids: [id] }).catch(() => undefined);
    }
  });
}
