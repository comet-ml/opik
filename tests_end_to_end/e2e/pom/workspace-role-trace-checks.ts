import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { makeBackendClient, uuid7 } from '../core/backend';
import type { WorkspaceRoleMember } from '../fixtures/workspace-role-member.fixture';
import { type AdminCtx, subjectOpikClient, adminOpikClient, attemptSucceeds, isAuthorizationError } from './workspace-role-shared';

/**
 * Polls the admin read-back until presence matches `expectPersisted` or the
 * timeout elapses, so search-index lag can't make a genuinely-permitted trace
 * look denied. Returns immediately once the expectation is already met — a
 * denied trace never appears, so the `expectPersisted: false` path resolves
 * on the first check rather than paying the full timeout.
 */
async function pollTracesByName(
  admin: ReturnType<typeof adminOpikClient>,
  projectId: string,
  traceName: string,
  expectPersisted: boolean,
  { timeoutMs = 15_000, pollIntervalMs = 1_500 } = {},
): Promise<NonNullable<Awaited<ReturnType<typeof admin.api.traces.getTracesByProject>>['content']>> {
  const start = Date.now();
  let content: NonNullable<Awaited<ReturnType<typeof admin.api.traces.getTracesByProject>>['content']> = [];
  do {
    const found = await admin.api.traces.getTracesByProject({ projectId, search: traceName });
    content = found.content ?? [];
    if (content.some((t) => t.name === traceName) === expectPersisted) return content;
    await new Promise((r) => setTimeout(r, pollIntervalMs));
  } while (Date.now() - start < timeoutMs);
  return content;
}

export async function logTraceAndVerify(
  member: WorkspaceRoleMember,
  ctx: AdminCtx,
  projectId: string,
  expectPersisted: boolean,
): Promise<void> {
  const restTraceName = `e2e-permcheck-rest-${member.role}-${Date.now()}`;
  const sdkTraceName = `e2e-permcheck-sdk-${member.role}-${Date.now()}`;
  const project = await makeBackendClient(ctx.adminApiKey, ctx.workspaceName).getProject(projectId);
  if (!project) {
    throw new Error(`logTraceAndVerify: project "${projectId}" not found`);
  }

  const admin = adminOpikClient(ctx.adminApiKey, ctx.workspaceName);
  const createdTraceIds: string[] = [];

  await test.step(`Traces: log via direct REST — ${expectPersisted ? 'succeeds' : 'denied (403)'}`, async () => {
    const sdk = subjectOpikClient(member, ctx.workspaceName);
    let succeeded = true;
    try {
      await sdk.api.traces.createTrace({ name: restTraceName, projectName: project.name, startTime: new Date() });
    } catch (err) {
      if (!isAuthorizationError(err)) throw err;
      succeeded = false;
    }
    expect
      .soft(succeeded, `${member.role}: expected direct REST trace creation to ${expectPersisted ? 'succeed' : 'be denied'}`)
      .toBe(expectPersisted);

    // `search` matches substrings across many fields (name, input, output,
    // metadata, tags, ...), not just an exact name — filter before deleting
    // so a coincidental match elsewhere never gets swept up in cleanup.
    const found = await pollTracesByName(admin, projectId, restTraceName, expectPersisted);
    for (const t of found) {
      if (t.id && t.name === restTraceName) createdTraceIds.push(t.id);
    }
  });

  await test.step(`Traces: log via SDK batch queue — ${expectPersisted ? 'succeeds' : 'denied'}`, async () => {
    const sdk = subjectOpikClient(member, ctx.workspaceName);
    sdk.trace({ name: sdkTraceName, projectName: project.name });
    await sdk.flush();

    const found = await pollTracesByName(admin, projectId, sdkTraceName, expectPersisted);
    const persisted = found.some((t) => t.name === sdkTraceName);
    expect
      .soft(
        persisted,
        `${member.role}: expected SDK-logged trace to ${expectPersisted ? 'persist' : 'be absent'} (flush() never throws — presence/absence via admin read-back is the only reliable signal)`,
      )
      .toBe(expectPersisted);

    for (const t of found) {
      if (t.id && t.name === sdkTraceName) createdTraceIds.push(t.id);
    }
  });

  if (createdTraceIds.length > 0) {
    await makeBackendClient(ctx.adminApiKey, ctx.workspaceName).deleteTraces(createdTraceIds).catch(() => undefined);
  }
}

/**
 * Uses a shared admin-seeded trace (safe across all 4 roles run serially,
 * since a successful add is immediately cleaned up by admin) rather than
 * each role's own logged trace — ANNOTATE holds trace_span_thread_annotate
 * without trace_span_thread_log, so it never has a trace of its own to
 * annotate.
 */
export async function checkTraceAnnotate(
  member: WorkspaceRoleMember,
  ctx: AdminCtx,
  seededTraceId: string,
  expectSucceeds: boolean,
): Promise<void> {
  await test.step(`Traces: annotate (feedback score) — ${expectSucceeds ? 'succeeds' : 'denied'}`, async () => {
    const sdk = subjectOpikClient(member, ctx.workspaceName);
    const scoreName = `e2e-${member.role}-score-${Date.now()}`;
    const succeeded = await attemptSucceeds(() =>
      sdk.api.traces.addTraceFeedbackScore(seededTraceId, { body: { name: scoreName, value: 1, source: 'sdk' } }),
    );
    expect
      .soft(succeeded, `${member.role}: expected trace annotate to ${expectSucceeds ? 'succeed' : 'be denied'}`)
      .toBe(expectSucceeds);
    if (succeeded) {
      await adminOpikClient(ctx.adminApiKey, ctx.workspaceName)
        .api.traces.deleteTraceFeedbackScore(seededTraceId, { body: { name: scoreName } })
        .catch(() => undefined);
    }
  });
}

/**
 * Uses a fresh admin-seeded scratch trace per role rather than the shared
 * seeded trace — MANAGE/WRITE actually delete it, which would remove it out
 * from under whichever role runs next in this serial suite.
 */
export async function checkTraceDelete(
  member: WorkspaceRoleMember,
  ctx: AdminCtx,
  projectName: string,
  expectSucceeds: boolean,
): Promise<void> {
  await test.step(`Traces: delete — ${expectSucceeds ? 'succeeds' : 'denied'}`, async () => {
    const admin = adminOpikClient(ctx.adminApiKey, ctx.workspaceName);
    const id = uuid7();
    await admin.api.traces.createTrace({ id, name: `e2e-${member.role}-delete-scratch-${Date.now()}`, projectName, startTime: new Date() });

    const sdk = subjectOpikClient(member, ctx.workspaceName);
    const deleted = await attemptSucceeds(() => sdk.api.traces.deleteTraceById(id));
    expect.soft(deleted, `${member.role}: expected trace delete to ${expectSucceeds ? 'succeed' : 'be denied'}`).toBe(expectSucceeds);
    if (!deleted) {
      await admin.api.traces.deleteTraceById(id).catch(() => undefined);
    }
  });
}

/** The trace side panel's "Annotate" button — opened directly via the `trace` URL param, confirmed live against staging. */
export async function checkTraceAnnotateButtonVisibility(
  member: WorkspaceRoleMember,
  workspaceName: string,
  projectId: string,
  traceId: string,
  expectedVisible: boolean,
): Promise<void> {
  await test.step(`Traces: Annotate button ${expectedVisible ? 'visible' : 'absent'}`, async () => {
    const env = loadEnvConfig();
    await member.page.goto(`${env.baseUrl}/${workspaceName}/projects/${projectId}/logs?logsType=traces&trace=${traceId}`);
    const annotateButton = member.page.getByRole('button', { name: 'Annotate A' });
    if (expectedVisible) {
      await expect.soft(annotateButton).toBeVisible();
    } else {
      await expect.soft(annotateButton).toBeHidden();
    }
  });
}
