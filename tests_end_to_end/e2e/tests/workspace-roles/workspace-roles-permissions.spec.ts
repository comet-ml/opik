import {
  test as workspaceRoleTest,
  expect,
  type WorkspaceRoleMember,
} from '@e2e/fixtures/workspace-role-member.fixture';
import { ConfigurationMembersPage } from '@e2e/pom/configuration-members.page';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import { getUserPermissions, getPermissionValue, WORKSPACE_ROLE_ID, type WorkspaceRoleId } from '@e2e/core/comet/client';
import { makeBackendClient, uuid7 } from '@e2e/core/backend';
import { loadEnvConfig } from '@e2e/config/env.config';
import {
  SCREEN_ACCESS_CHECKS,
  CREATE_CONTROL_CHECKS,
  checkScreenAccess,
  checkCreateControlVisibility,
  checkResourceCrudSucceeds,
  checkResourceCreateDenied,
  checkResourceEditDeleteDenied,
  dashboardActions,
  datasetActions,
  annotationQueueActions,
  promptActions,
  adminOpikClient,
  logTraceAndVerify,
  type CrudActions,
} from '@e2e/pom/workspace-role-checks';

interface SeededResources {
  dashboardId: string;
  datasetId: string;
  queueId: string;
  promptId: string;
}

const test = workspaceRoleTest.extend<{}, { projectId: string; seededResources: SeededResources }>({
  projectId: [
    async ({}, use) => {
      const backend = makeBackendClient();
      const name = `e2e-workspace-roles-${Date.now()}`;
      await backend.createProject(name);
      const [created] = await backend.listProjectsWithPrefix(name);
      if (!created) {
        throw new Error(`Failed to resolve id for created anchor project "${name}"`);
      }
      await use(created.id);
      await backend.deleteProject(created.id);
    },
    { scope: 'worker' },
  ],

  seededResources: [
    async ({ projectId }, use) => {
      const admin = adminOpikClient();
      const dashboard = await admin.api.dashboards.createDashboard({
        name: `e2e-seed-dashboard-${Date.now()}`,
        config: {},
      });
      if (!dashboard.id) {
        throw new Error('createDashboard did not return an id');
      }

      const datasetId = uuid7();
      await admin.api.datasets.createDataset({ id: datasetId, name: `e2e-seed-dataset-${Date.now()}` });

      const queueId = uuid7();
      await admin.api.annotationQueues.createAnnotationQueue({
        id: queueId,
        projectId,
        name: `e2e-seed-queue-${Date.now()}`,
        scope: 'trace',
      });

      const promptId = uuid7();
      await admin.api.prompts.createPrompt({ id: promptId, name: `e2e-seed-prompt-${Date.now()}` });

      const resources: SeededResources = { dashboardId: dashboard.id, datasetId, queueId, promptId };
      await use(resources);

      await admin.api.dashboards.deleteDashboard(resources.dashboardId).catch(() => undefined);
      await admin.api.datasets.deleteDataset(resources.datasetId).catch(() => undefined);
      await admin.api.annotationQueues.deleteAnnotationQueueBatch({ ids: [resources.queueId] }).catch(() => undefined);
      await admin.api.prompts.deletePrompt(resources.promptId).catch(() => undefined);
    },
    { scope: 'worker' },
  ],
});

function resourceChecks(member: WorkspaceRoleMember, projectId: string, seeded: SeededResources) {
  return [
    { label: 'Dashboards', actions: dashboardActions(member), seededId: seeded.dashboardId },
    { label: 'Datasets', actions: datasetActions(member), seededId: seeded.datasetId },
    { label: 'Annotation queues', actions: annotationQueueActions(member, projectId), seededId: seeded.queueId },
    { label: 'Prompt library', actions: promptActions(member), seededId: seeded.promptId },
  ] satisfies Array<{ label: string; actions: CrudActions; seededId: string }>;
}

/**
 * The fixture pre-assigns each member's real target role via REST so tests
 * 2-5 don't depend on this test succeeding. That means the role cell already
 * shows the target role by the time this test runs — re-selecting the exact
 * same value in the role Select is a no-op (Radix only fires onValueChange,
 * and therefore the PUT, on an actual change), so asserting the UI mechanism
 * itself requires selecting away from the current value first.
 */
const AWAY_ROLE: Record<WorkspaceRoleId, WorkspaceRoleId> = {
  [WORKSPACE_ROLE_ID.MANAGE]: WORKSPACE_ROLE_ID.WRITE,
  [WORKSPACE_ROLE_ID.WRITE]: WORKSPACE_ROLE_ID.ANNOTATE,
  [WORKSPACE_ROLE_ID.ANNOTATE]: WORKSPACE_ROLE_ID.READ,
  [WORKSPACE_ROLE_ID.READ]: WORKSPACE_ROLE_ID.MANAGE,
};

test.describe('Workspace role permissions', { tag: ['@t3-nightly', '@workspace-roles'] }, () => {
  test.describe.configure({ mode: 'serial' });

  test('Role assignment & guardrails', async ({ workspaceRoleMembers }) => {
    const { adminPage, adminContext, organizationId, manage, write, annotate, read } = workspaceRoleMembers;
    const membersPage = new ConfigurationMembersPage(adminPage);
    const members: WorkspaceRoleMember[] = [manage, write, annotate, read];

    await membersPage.goto();

    for (const member of members) {
      await test.step(`Assign ${member.role} to ${member.username} via UI`, async () => {
        const awayRole = AWAY_ROLE[member.role];

        await membersPage.searchMembers(member.username);
        await membersPage.assignRole(member.username, awayRole);
        expect
          .soft(
            await membersPage.expectRole(member.username, awayRole),
            `${member.username}: role cell should show ${awayRole} after UI assignment`,
          )
          .toBe(true);

        await membersPage.assignRole(member.username, member.role);
        expect
          .soft(
            await membersPage.expectRole(member.username, member.role),
            `${member.username}: role cell should show ${member.role} after UI assignment`,
          )
          .toBe(true);

        const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
        expect.soft(permissions.length > 0, `${member.username}: getUserPermissions() should return a permission set`).toBe(true);
      });
    }
  });

  test('MANAGE', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 60_000);
    const { manage: member, adminContext, organizationId } = workspaceRoleMembers;
    const env = loadEnvConfig();

    await test.step('Projects: create control visible', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeVisible();
    });

    await logTraceAndVerify(member, projectId, true);

    for (const check of SCREEN_ACCESS_CHECKS) {
      await checkScreenAccess(member, projectId, check, true);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, projectId, check, true);
    }

    for (const rc of resourceChecks(member, projectId, seededResources)) {
      await checkResourceCrudSucceeds(rc.label, member, rc.actions);
    }

    await test.step('Optimization: studio use visible', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/optimizations`);
      await expect.soft(member.page.getByRole('button', { name: /Use the Optimization studio/ })).toBeVisible();
    });

    await test.step('Workspace settings: configure control visible', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeVisible();
    });

    await test.step('AI providers: update control visible', async () => {
      const configPage = new ConfigurationPage(member.page);
      await configPage.gotoAiProviders();
      await expect.soft(member.page.getByRole('button', { name: 'Add configuration', exact: true }).first()).toBeVisible();
    });

    await test.step('Invite users: Members tab accessible', async () => {
      const membersPage = new ConfigurationMembersPage(member.page);
      await membersPage.goto();
      expect.soft(await membersPage.isTabVisible(), 'MANAGE should see the Members tab (isWorkspaceOwner)').toBe(true);
    });

    await test.step('Permission cross-check', async () => {
      const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
      for (const name of [
        'workspace_settings_configure',
        'prompt_view',
        'prompt_delete',
        'online_evaluation_rule_update',
        'alert_update',
        'agent_playground_use',
      ]) {
        expect.soft(getPermissionValue(permissions, env.workspace, name), `MANAGE should hold "${name}"`).toBe(true);
      }
    });
  });

  test('WRITE', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 60_000);
    const { write: member, adminContext, organizationId } = workspaceRoleMembers;
    const env = loadEnvConfig();

    await test.step('Projects: create control visible', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeVisible();
    });

    await logTraceAndVerify(member, projectId, true);

    for (const check of SCREEN_ACCESS_CHECKS) {
      await checkScreenAccess(member, projectId, check, true);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, projectId, check, true);
    }

    for (const rc of resourceChecks(member, projectId, seededResources)) {
      await checkResourceCrudSucceeds(rc.label, member, rc.actions);
    }

    await test.step('Workspace settings: configure control absent', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeHidden();
    });

    await test.step('AI providers: update control visible', async () => {
      const configPage = new ConfigurationPage(member.page);
      await configPage.gotoAiProviders();
      await expect.soft(member.page.getByRole('button', { name: 'Add configuration', exact: true }).first()).toBeVisible();
    });

    await test.step('Invite users: Members tab NOT accessible despite canInviteMembers=true', async () => {
      const membersPage = new ConfigurationMembersPage(member.page);
      await member.page.goto(`${env.baseUrl}/${env.workspace}/configuration?tab=workspace-preferences`);
      expect
        .soft(await membersPage.isTabVisible(), 'WRITE should NOT see the Members tab (isWorkspaceOwner requires MANAGE)')
        .toBe(false);
    });

    await test.step('Permission cross-check', async () => {
      const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
      expect
        .soft(getPermissionValue(permissions, env.workspace, 'workspace_settings_configure'), 'WRITE should NOT hold workspace_settings_configure')
        .toBe(false);
      for (const name of ['online_evaluation_rule_update', 'alert_update', 'prompt_delete', 'invite_users_to_workspace', 'agent_playground_use']) {
        expect.soft(getPermissionValue(permissions, env.workspace, name), `WRITE should hold "${name}"`).toBe(true);
      }
    });
  });

  test('ANNOTATE', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 60_000);
    const { annotate: member, adminContext, organizationId } = workspaceRoleMembers;
    const env = loadEnvConfig();

    await test.step('Projects: create control absent', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeHidden();
    });

    await logTraceAndVerify(member, projectId, false);

    for (const check of SCREEN_ACCESS_CHECKS) {
      await checkScreenAccess(member, projectId, check, false);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, projectId, check, false);
    }

    for (const rc of resourceChecks(member, projectId, seededResources)) {
      await checkResourceCreateDenied(rc.label, member, rc.actions.create);
      await checkResourceEditDeleteDenied(rc.label, member, rc.seededId, rc.actions);
    }

    await test.step('Annotation queues: page accessible (no route guard)', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues`);
      await expect.soft(member.page.getByRole('heading', { name: 'Access denied' })).toBeHidden();
    });

    await test.step('Workspace settings, AI providers, invite users absent', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeHidden();

      const membersPage = new ConfigurationMembersPage(member.page);
      expect.soft(await membersPage.isTabVisible(), 'ANNOTATE should NOT see the Members tab').toBe(false);
    });

    await test.step('Permission cross-check', async () => {
      const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
      for (const name of [
        'trace_span_thread_log',
        'prompt_view',
        'dashboard_view',
        'experiment_view',
        'dataset_view',
        'online_evaluation_rule_view',
        'alert_view',
        'agent_playground_use',
        'optimization_run_view',
      ]) {
        expect.soft(getPermissionValue(permissions, env.workspace, name), `ANNOTATE should NOT hold "${name}"`).toBe(false);
      }
      for (const name of ['trace_span_thread_annotate', 'annotation_queue_view', 'annotation_queue_annotate']) {
        expect.soft(getPermissionValue(permissions, env.workspace, name), `ANNOTATE should hold "${name}"`).toBe(true);
      }
    });
  });

  test('READ', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 60_000);
    const { read: member, adminContext, organizationId } = workspaceRoleMembers;
    const env = loadEnvConfig();

    await test.step('Projects: create control absent', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeHidden();
    });

    await logTraceAndVerify(member, projectId, false);

    for (const check of SCREEN_ACCESS_CHECKS) {
      // READ holds the view permission for every screen except Playground and
      // Agent playground (the latter fails the compound canViewAgentPlayground
      // check despite READ holding the raw AGENT_PLAYGROUND_USE permission).
      const expectedAccessible = !['Playground', 'Agent playground'].includes(check.name);
      await checkScreenAccess(member, projectId, check, expectedAccessible);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, projectId, check, false);
    }

    for (const rc of resourceChecks(member, projectId, seededResources)) {
      await checkResourceCreateDenied(rc.label, member, rc.actions.create);
      await checkResourceEditDeleteDenied(rc.label, member, rc.seededId, rc.actions);
    }

    await test.step('Annotation queues: page accessible (no route guard)', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/annotation-queues`);
      await expect.soft(member.page.getByRole('heading', { name: 'Access denied' })).toBeHidden();
    });

    await test.step('Optimization: studio use absent', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/optimizations`);
      await expect.soft(member.page.getByRole('button', { name: /Use the Optimization studio/ })).toBeHidden();
    });

    await test.step('Workspace settings, AI providers, invite users absent', async () => {
      await member.page.goto(`${env.baseUrl}/${env.workspace}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeHidden();

      const membersPage = new ConfigurationMembersPage(member.page);
      expect.soft(await membersPage.isTabVisible(), 'READ should NOT see the Members tab').toBe(false);
    });

    await test.step('Permission cross-check', async () => {
      const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
      expect
        .soft(getPermissionValue(permissions, env.workspace, 'trace_span_thread_annotate'), 'READ should NOT hold trace_span_thread_annotate')
        .toBe(false);
      for (const name of [
        'dashboard_view',
        'experiment_view',
        'dataset_view',
        'prompt_view',
        'online_evaluation_rule_view',
        'alert_view',
        'agent_playground_use',
        'optimization_run_view',
        'annotation_queue_annotate',
      ]) {
        expect.soft(getPermissionValue(permissions, env.workspace, name), `READ should hold "${name}"`).toBe(true);
      }
    });
  });
});
