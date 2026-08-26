import { test as workspaceRoleTest, expect, type WorkspaceRoleTestContext } from './workspace-role-member.fixture';
import { makeBackendClient, uuid7 } from '../core/backend';
import { type AdminCtx, adminOpikClient } from '../pom/workspace-role-shared';
import { LLM_AS_JUDGE_CODE } from '../pom/workspace-role-resource-actions';

/**
 * The worker-scoped project + seeded resources every workspace-role
 * permissions test shares (`tests/workspace-roles/workspace-roles-permissions.spec.ts`).
 * `projectId` is a throwaway anchor project created fresh per worker;
 * `seededResources` is one instance of every resource type the 5 tests check
 * against, admin-created so ANNOTATE/READ (who can't create most of it
 * themselves) still have something real to attempt edit/delete against.
 */

export interface SeededResources {
  dashboardId: string;
  datasetId: string;
  datasetName: string;
  queueId: string;
  promptId: string;
  evalRuleId: string;
  alertId: string;
  traceId: string;
  projectId: string;
  projectName: string;
  optimizationId: string;
}

/** Every worker-scoped resource here belongs to the admin's own org/workspace — never the baseline suite's. */
export function adminCtx(ctx: WorkspaceRoleTestContext): AdminCtx {
  return { workspaceName: ctx.workspaceName, adminApiKey: ctx.adminOpikApiKey };
}

export interface WorkspaceRoleResourceFixtures {
  projectId: string;
  seededResources: SeededResources;
}

export const test = workspaceRoleTest.extend<{}, WorkspaceRoleResourceFixtures>({
  projectId: [
    async ({ workspaceRoleMembers, envConfig }, use, workerInfo) => {
      const ctx = adminCtx(workspaceRoleMembers);
      const backend = makeBackendClient(ctx.adminApiKey, ctx.workspaceName);
      // `cuj-{runId}-w{worker}-` lets global-setup's orphan sweep and
      // global-teardown find and remove these if this run gets SIGKILLed or
      // CI-timed-out before reaching the rollbacks below — see
      // sweepWorkspace in global-teardown.ts / sweepOrphans in global-setup.ts.
      const ns = `${envConfig.cujPrefix}-w${workerInfo.workerIndex}`;
      const name = `${ns}-workspace-roles-${Date.now()}`;
      await backend.createProject(name);
      const [created] = await backend.listProjectsWithPrefix(name);
      if (!created) {
        // createProject returns void — if this lookup can't resolve an id,
        // the project it just created is otherwise unreachable to delete.
        // The name is deterministic, so a follow-up sweep by prefix can
        // still find and remove it even without the id.
        const strays = await backend.listProjectsWithPrefix(name).catch(() => []);
        for (const stray of strays) {
          await backend.deleteProject(stray.id).catch(() => undefined);
        }
        throw new Error(`Failed to resolve id for created anchor project "${name}"`);
      }
      await use(created.id);
      await backend.deleteProject(created.id);
    },
    { scope: 'worker' },
  ],

  seededResources: [
    async ({ workspaceRoleMembers, projectId, envConfig }, use, workerInfo) => {
      const ctx = adminCtx(workspaceRoleMembers);
      const admin = adminOpikClient(ctx.adminApiKey, ctx.workspaceName);
      const backend = makeBackendClient(ctx.adminApiKey, ctx.workspaceName);
      // Same sweepable namespace as the projectId fixture above.
      const ns = `${envConfig.cujPrefix}-w${workerInfo.workerIndex}`;

      // If any create call below throws, everything created by an earlier
      // one in this same setup would otherwise leak — nothing runs past a
      // thrown error except propagating it, so the teardown code after
      // `await use()` never gets a chance to run. Each step registers its
      // own undo the moment it succeeds, so a failure partway still cleans
      // up everything that landed before it.
      const rollbacks: Array<() => Promise<unknown>> = [];
      const runSetup = async (): Promise<SeededResources> => {
        const dashboard = await admin.api.dashboards.createDashboard({
          name: `${ns}-e2e-seed-dashboard-${Date.now()}`,
          config: {},
        });
        if (!dashboard.id) {
          throw new Error('createDashboard did not return an id');
        }
        rollbacks.push(() => admin.api.dashboards.deleteDashboard(dashboard.id!));

        const datasetId = uuid7();
        const datasetName = `${ns}-e2e-seed-dataset-${Date.now()}`;
        await admin.api.datasets.createDataset({ id: datasetId, name: datasetName });
        rollbacks.push(() => admin.api.datasets.deleteDataset(datasetId));

        const queueId = uuid7();
        await admin.api.annotationQueues.createAnnotationQueue({
          id: queueId,
          projectId,
          name: `${ns}-e2e-seed-queue-${Date.now()}`,
          scope: 'trace',
        });
        rollbacks.push(() => admin.api.annotationQueues.deleteAnnotationQueueBatch({ ids: [queueId] }));

        const promptId = uuid7();
        await admin.api.prompts.createPrompt({ id: promptId, name: `${ns}-e2e-seed-prompt-${Date.now()}` });
        rollbacks.push(() => admin.api.prompts.deletePrompt(promptId));

        const evalRuleName = `${ns}-e2e-seed-eval-rule-${Date.now()}`;
        await admin.api.automationRuleEvaluators.createAutomationRuleEvaluator({
          type: 'llm_as_judge',
          name: evalRuleName,
          action: 'evaluator',
          projectId,
          code: LLM_AS_JUDGE_CODE,
        });
        const foundRules = await admin.api.automationRuleEvaluators.findEvaluators({ projectId, name: evalRuleName });
        const evalRuleId = foundRules.content?.find((r) => r.name === evalRuleName)?.id;
        if (!evalRuleId) throw new Error(`Failed to resolve id for seeded eval rule "${evalRuleName}"`);
        rollbacks.push(() =>
          admin.api.automationRuleEvaluators.deleteAutomationRuleEvaluatorBatch({ projectId, body: { ids: [evalRuleId] } }),
        );

        const alertId = uuid7();
        await admin.api.alerts.createAlert({
          id: alertId,
          name: `${ns}-e2e-seed-alert-${Date.now()}`,
          webhook: { url: 'https://example.com/e2e-seed-webhook' },
        });
        rollbacks.push(() => admin.api.alerts.deleteAlertBatch({ ids: [alertId] }));

        const project = await backend.getProject(projectId);
        if (!project) throw new Error(`seededResources: anchor project "${projectId}" not found`);

        const traceId = uuid7();
        await admin.api.traces.createTrace({ id: traceId, name: `${ns}-e2e-seed-trace-${Date.now()}`, projectName: project.name, startTime: new Date() });
        rollbacks.push(() => backend.deleteTraces([traceId]));

        const seedProjectName = `${ns}-e2e-seed-project-${Date.now()}`;
        await backend.createProject(seedProjectName);
        const [seedProject] = await backend.listProjectsWithPrefix(seedProjectName);
        if (!seedProject) throw new Error(`Failed to resolve id for seeded project "${seedProjectName}"`);
        rollbacks.push(() => backend.deleteProject(seedProject.id));

        const optimizationId = uuid7();
        await admin.api.optimizations.createOptimization({
          id: optimizationId,
          name: `${ns}-e2e-seed-optimization-${Date.now()}`,
          datasetName,
          objectiveName: 'accuracy',
          status: 'running',
          projectId,
        });
        rollbacks.push(() => admin.api.optimizations.deleteOptimizationsById({ ids: [optimizationId] }));

        return {
          dashboardId: dashboard.id!,
          datasetId,
          datasetName,
          queueId,
          promptId,
          evalRuleId,
          alertId,
          traceId,
          projectId: seedProject.id,
          projectName: seedProjectName,
          optimizationId,
        };
      };

      let resources: SeededResources;
      try {
        resources = await runSetup();
      } catch (err) {
        for (const rollback of rollbacks.reverse()) {
          await rollback().catch(() => undefined);
        }
        throw err;
      }

      await use(resources);

      for (const rollback of rollbacks.reverse()) {
        await rollback().catch(() => undefined);
      }
    },
    { scope: 'worker' },
  ],
});

export { expect };
export type { WorkspaceRoleTestContext };
