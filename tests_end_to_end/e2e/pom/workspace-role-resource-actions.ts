import { makeBackendClient, uuid7 } from '../core/backend';
import type { WorkspaceRoleMember } from '../fixtures/workspace-role-member.fixture';
import { type AdminCtx, subjectOpikClient, adminOpikClient } from './workspace-role-shared';
import type { CrudActions, CreateRemoveActions } from './workspace-role-crud-checks';

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

/**
 * Projects have no distinct "edit" permission in the matrix — only create and
 * delete are gated (canCreateProjects/canDeleteProjects). createProject
 * returns void, so the id is resolved back via a name-prefixed lookup, same
 * as the spec's own anchor-project fixture.
 */
export function projectActions(member: WorkspaceRoleMember, ctx: AdminCtx): CreateRemoveActions {
  const memberBackend = makeBackendClient(member.apiKey, ctx.workspaceName);
  const adminBackend = makeBackendClient(ctx.adminApiKey, ctx.workspaceName);
  return {
    create: async () => {
      const name = `e2e-${member.role}-project-${Date.now()}`;
      await memberBackend.createProject(name);
      const [created] = await adminBackend.listProjectsWithPrefix(name);
      if (!created) throw new Error(`projectActions: failed to resolve id for created project "${name}"`);
      return created.id;
    },
    remove: (id) => memberBackend.deleteProject(id),
    adminRemove: (id) => adminBackend.deleteProject(id),
  };
}

/** Experiments have no distinct "delete" permission in the matrix — only canCreateExperiments is gated. */
export function experimentActions(
  member: WorkspaceRoleMember,
  ctx: AdminCtx,
  datasetName: string,
): { create: () => Promise<string>; adminRemove: (id: string) => Promise<unknown> } {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  return {
    create: async () => {
      const id = uuid7();
      await sdk.api.experiments.createExperiment({ id, datasetName, name: `e2e-${member.role}-experiment-${Date.now()}` });
      return id;
    },
    adminRemove: (id) =>
      adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.experiments.deleteExperimentsById({ ids: [id] }),
  };
}

/**
 * The SDK types `code` as optional on the LLM-as-judge write payload, but the
 * backend rejects a create/update with "code must not be null" if it's
 * omitted — this is the minimal config that satisfies validation without
 * needing a real judge model call (the rule is never actually triggered).
 */
export const LLM_AS_JUDGE_CODE = {
  model: { name: 'gpt-4o-mini' },
  messages: [{ role: 'USER' as const, content: 'Score this response' }],
  variables: {},
  schema: [{ name: 'score', type: 'BOOLEAN' as const, description: 'pass/fail' }],
};

/**
 * Online evaluation rules and Alerts are both gated by a single "update"
 * permission bit covering create/edit/delete together (no separate create
 * permission), so both reuse the generic CrudActions checks like the other
 * 4 resource types.
 */
export function evalRuleActions(member: WorkspaceRoleMember, ctx: AdminCtx, projectId: string): CrudActions {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  return {
    create: async () => {
      // createAutomationRuleEvaluator returns void and its write payload has
      // no settable `id` (unlike datasets/prompts/queues) — resolve the id
      // back via a name-filtered lookup, same as projectActions.
      const name = `e2e-${member.role}-eval-rule-${Date.now()}`;
      await sdk.api.automationRuleEvaluators.createAutomationRuleEvaluator({
        type: 'llm_as_judge',
        name,
        action: 'evaluator',
        projectId,
        code: LLM_AS_JUDGE_CODE,
      });
      const found = await sdk.api.automationRuleEvaluators.findEvaluators({ projectId, name });
      const rule = found.content?.find((r) => r.name === name);
      if (!rule?.id) throw new Error(`evalRuleActions: failed to resolve id for created rule "${name}"`);
      return rule.id;
    },
    update: (id) =>
      sdk.api.automationRuleEvaluators.updateAutomationRuleEvaluator(id, {
        body: {
          type: 'llm_as_judge',
          name: `e2e-${member.role}-eval-rule-updated-${Date.now()}`,
          action: 'evaluator',
          projectId,
          code: LLM_AS_JUDGE_CODE,
        },
      }),
    remove: (id) => sdk.api.automationRuleEvaluators.deleteAutomationRuleEvaluatorBatch({ projectId, body: { ids: [id] } }),
    adminRemove: (id) =>
      adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.automationRuleEvaluators.deleteAutomationRuleEvaluatorBatch({
        projectId,
        body: { ids: [id] },
      }),
  };
}

export function alertActions(member: WorkspaceRoleMember, ctx: AdminCtx): CrudActions {
  const sdk = subjectOpikClient(member, ctx.workspaceName);
  const webhook = (suffix: string) => ({ url: `https://example.com/e2e-webhook-${suffix}` });
  return {
    create: async () => {
      const id = uuid7();
      await sdk.api.alerts.createAlert({ id, name: `e2e-${member.role}-alert-${Date.now()}`, webhook: webhook('create') });
      return id;
    },
    update: (id) =>
      sdk.api.alerts.updateAlert(id, {
        body: { name: `e2e-${member.role}-alert-updated-${Date.now()}`, webhook: webhook('update') },
      }),
    remove: (id) => sdk.api.alerts.deleteAlertBatch({ ids: [id] }),
    adminRemove: (id) => adminOpikClient(ctx.adminApiKey, ctx.workspaceName).api.alerts.deleteAlertBatch({ ids: [id] }),
  };
}
