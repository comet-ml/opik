import { test, expect } from '@playwright/test';
import { Opik } from 'opik';
import { loadEnvConfig } from '../config/env.config';
import { makeBackendClient, uuid7 } from '../core/backend';
import type { WorkspaceRoleMember } from '../fixtures/workspace-role-member.fixture';

export interface ScreenAccessCheck {
  name: string;
  path: (projectId: string) => string;
}

export const SCREEN_ACCESS_CHECKS: ScreenAccessCheck[] = [
  { name: 'Dashboards', path: () => '/dashboards' },
  { name: 'Experiments', path: (id) => `/projects/${id}/experiments` },
  { name: 'Datasets', path: (id) => `/projects/${id}/datasets` },
  { name: 'Prompt library', path: (id) => `/projects/${id}/prompts` },
  { name: 'Playground', path: (id) => `/projects/${id}/playground` },
  { name: 'Agent playground', path: (id) => `/projects/${id}/agent-playground` },
  { name: 'Optimization runs', path: (id) => `/projects/${id}/optimizations` },
  { name: 'Online evaluation rules', path: (id) => `/projects/${id}/online-evaluation` },
  { name: 'Alerts', path: (id) => `/projects/${id}/alerts` },
];

export async function checkScreenAccess(
  member: WorkspaceRoleMember,
  workspaceName: string,
  projectId: string,
  check: ScreenAccessCheck,
  expectedAccessible: boolean,
): Promise<void> {
  return test.step(`${check.name}: ${expectedAccessible ? 'accessible' : 'blocked'}`, async () => {
    const env = loadEnvConfig();
    await member.page.goto(`${env.baseUrl}/${workspaceName}${check.path(projectId)}`);
    const deniedHeading = member.page.getByRole('heading', { name: 'Access denied' });
    if (expectedAccessible) {
      await expect.soft(deniedHeading).toBeHidden();
    } else {
      await expect.soft(deniedHeading).toBeVisible();
    }
  });
}

export function subjectOpikClient(member: WorkspaceRoleMember, workspaceName: string): Opik {
  const env = loadEnvConfig();
  return new Opik({ apiKey: member.apiKey, workspaceName, apiUrl: env.apiBaseUrl });
}

export function adminOpikClient(adminApiKey: string, workspaceName: string): Opik {
  const env = loadEnvConfig();
  return new Opik({ apiKey: adminApiKey, workspaceName, apiUrl: env.apiBaseUrl });
}

export interface CrudActions {
  create: () => Promise<string>;
  update: (id: string) => Promise<unknown>;
  remove: (id: string) => Promise<unknown>;
  /** Admin-identity delete, used only as a cleanup fallback if `remove` unexpectedly fails — never asserted on. */
  adminRemove: (id: string) => Promise<unknown>;
}

async function attemptSucceeds(fn: () => Promise<unknown>): Promise<boolean> {
  try {
    await fn();
    return true;
  } catch {
    return false;
  }
}

export async function checkResourceCrudSucceeds(
  label: string,
  member: WorkspaceRoleMember,
  actions: CrudActions,
): Promise<void> {
  await test.step(`${label}: create, edit, delete all succeed`, async () => {
    let id: string | null = null;
    const created = await attemptSucceeds(async () => {
      id = await actions.create();
    });
    expect.soft(created, `${member.role}: expected ${label} create to succeed`).toBe(true);
    if (!id) return;

    expect.soft(await attemptSucceeds(() => actions.update(id!)), `${member.role}: expected ${label} update to succeed`).toBe(true);
    const deleted = await attemptSucceeds(() => actions.remove(id!));
    expect.soft(deleted, `${member.role}: expected ${label} delete to succeed`).toBe(true);
    if (!deleted) {
      await actions.adminRemove(id!).catch(() => undefined);
    }
  });
}

export async function checkResourceCreateDenied(
  label: string,
  member: WorkspaceRoleMember,
  create: () => Promise<unknown>,
): Promise<void> {
  await test.step(`${label}: create denied`, async () => {
    expect.soft(await attemptSucceeds(create), `${member.role}: expected ${label} create to be denied`).toBe(false);
  });
}

export async function checkResourceEditDeleteDenied(
  label: string,
  member: WorkspaceRoleMember,
  resourceId: string,
  actions: { update: (id: string) => Promise<unknown>; remove: (id: string) => Promise<unknown> },
): Promise<void> {
  await test.step(`${label}: edit/delete denied on existing resource`, async () => {
    expect
      .soft(await attemptSucceeds(() => actions.update(resourceId)), `${member.role}: expected ${label} update to be denied`)
      .toBe(false);
    expect
      .soft(await attemptSucceeds(() => actions.remove(resourceId)), `${member.role}: expected ${label} delete to be denied`)
      .toBe(false);
  });
}

export interface CreateControlCheck {
  name: string;
  path: (projectId: string) => string;
  buttonName: RegExp | string;
}

export const CREATE_CONTROL_CHECKS: CreateControlCheck[] = [
  { name: 'Dashboards', path: () => '/dashboards', buttonName: 'Create dashboard' },
  { name: 'Datasets', path: (id) => `/projects/${id}/datasets`, buttonName: /Upload a file|Create dataset/ },
  { name: 'Annotation queues', path: (id) => `/projects/${id}/annotation-queues`, buttonName: 'Create queue' },
  { name: 'Prompt library', path: (id) => `/projects/${id}/prompts`, buttonName: /Create a text prompt/ },
];

export async function checkCreateControlVisibility(
  member: WorkspaceRoleMember,
  workspaceName: string,
  projectId: string,
  check: CreateControlCheck,
  expectedVisible: boolean,
): Promise<void> {
  return test.step(`${check.name}: create control ${expectedVisible ? 'visible' : 'absent'}`, async () => {
    const env = loadEnvConfig();
    await member.page.goto(`${env.baseUrl}/${workspaceName}${check.path(projectId)}`);
    const button = member.page.getByRole('button', { name: check.buttonName });
    if (expectedVisible) {
      await expect.soft(button.first()).toBeVisible();
    } else {
      await expect.soft(button.first()).toBeHidden();
    }
  });
}

/** Shared by every resource-action factory: which workspace to operate in and the admin identity for `adminRemove` fallback cleanup. */
export interface AdminCtx {
  workspaceName: string;
  adminApiKey: string;
}

export function dashboardActions(member: WorkspaceRoleMember, ctx: AdminCtx): CrudActions {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  return {
    create: async () => {
      const dashboard = await sdk.api.dashboards.createDashboard({
        name: `e2e-${member.role}-dashboard-${Date.now()}`,
        config: {},
      });
      if (!dashboard.id) throw new Error('createDashboard did not return an id');
      return dashboard.id;
    },
    update: (id) =>
      sdk.api.dashboards.updateDashboard(id, { body: { name: `e2e-${member.role}-dashboard-updated-${Date.now()}` } }),
    remove: (id) => sdk.api.dashboards.deleteDashboard(id),
    adminRemove: (id) => adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.dashboards.deleteDashboard(id),
  };
}

export function datasetActions(member: WorkspaceRoleMember, ctx: AdminCtx): CrudActions {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  return {
    create: async () => {
      const id = uuid7();
      await sdk.api.datasets.createDataset({ id, name: `e2e-${member.role}-dataset-${Date.now()}` });
      return id;
    },
    update: (id) => sdk.api.datasets.updateDataset(id, { name: `e2e-${member.role}-dataset-updated-${Date.now()}` }),
    remove: (id) => sdk.api.datasets.deleteDataset(id),
    adminRemove: (id) => adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.datasets.deleteDataset(id),
  };
}

export function annotationQueueActions(member: WorkspaceRoleMember, ctx: AdminCtx, projectId: string): CrudActions {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  return {
    create: async () => {
      const id = uuid7();
      await sdk.api.annotationQueues.createAnnotationQueue({
        id,
        projectId,
        name: `e2e-${member.role}-queue-${Date.now()}`,
        scope: 'trace',
      });
      return id;
    },
    update: (id) => sdk.api.annotationQueues.updateAnnotationQueue(id, { name: `e2e-${member.role}-queue-updated-${Date.now()}` }),
    remove: (id) => sdk.api.annotationQueues.deleteAnnotationQueueBatch({ ids: [id] }),
    adminRemove: (id) =>
      adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.annotationQueues.deleteAnnotationQueueBatch({ ids: [id] }),
  };
}

export function promptActions(member: WorkspaceRoleMember, ctx: AdminCtx): CrudActions {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  return {
    create: async () => {
      const id = uuid7();
      await sdk.api.prompts.createPrompt({ id, name: `e2e-${member.role}-prompt-${Date.now()}` });
      return id;
    },
    update: (id) => sdk.api.prompts.updatePrompt(id, { name: `e2e-${member.role}-prompt-updated-${Date.now()}` }),
    remove: (id) => sdk.api.prompts.deletePrompt(id),
    adminRemove: (id) => adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.prompts.deletePrompt(id),
  };
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
    } catch {
      succeeded = false;
    }
    expect
      .soft(succeeded, `${member.role}: expected direct REST trace creation to ${expectPersisted ? 'succeed' : 'be denied'}`)
      .toBe(expectPersisted);

    const found = await admin.api.traces.getTracesByProject({ projectId, search: restTraceName });
    for (const t of found.content ?? []) {
      if (t.id) createdTraceIds.push(t.id);
    }
  });

  await test.step(`Traces: log via SDK batch queue — ${expectPersisted ? 'succeeds' : 'denied'}`, async () => {
    const sdk = subjectOpikClient(member, ctx.workspaceName);
    sdk.trace({ name: sdkTraceName, projectName: project.name });
    await sdk.flush();

    const found = await admin.api.traces.getTracesByProject({ projectId, search: sdkTraceName });
    const persisted = (found.content ?? []).some((t) => t.name === sdkTraceName);
    expect
      .soft(
        persisted,
        `${member.role}: expected SDK-logged trace to ${expectPersisted ? 'persist' : 'be absent'} (flush() never throws — presence/absence via admin read-back is the only reliable signal)`,
      )
      .toBe(expectPersisted);

    for (const t of found.content ?? []) {
      if (t.id) createdTraceIds.push(t.id);
    }
  });

  if (createdTraceIds.length > 0) {
    await makeBackendClient(ctx.adminApiKey, ctx.workspaceName).deleteTraces(createdTraceIds).catch(() => undefined);
  }
}
