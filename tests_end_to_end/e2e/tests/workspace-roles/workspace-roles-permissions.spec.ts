import {
  test,
  expect,
  adminCtx,
  type SeededResources,
} from '@e2e/fixtures/workspace-role-resources.fixture';
import type { WorkspaceRoleMember } from '@e2e/fixtures/workspace-role-member.fixture';
import { ConfigurationMembersPage } from '@e2e/pom/configuration-members.page';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import { getUserPermissions, getPermissionValue, WORKSPACE_ROLE_ID, type WorkspaceRoleId } from '@e2e/core/comet/client';
import { loadEnvConfig } from '@e2e/config/env.config';
import {
  SCREEN_ACCESS_CHECKS,
  CREATE_CONTROL_CHECKS,
  checkScreenAccess,
  checkCreateControlVisibility,
  checkRowDeleteActionVisibility,
} from '@e2e/pom/workspace-role-screen-checks';
import {
  checkResourceCrudSucceeds,
  checkResourceCreateDenied,
  checkResourceEditDeleteDenied,
  checkResourceDeleteDenied,
  checkCreateDeleteSucceeds,
  checkCreateSucceeds,
  type CrudActions,
} from '@e2e/pom/workspace-role-crud-checks';
import {
  dashboardActions,
  datasetActions,
  annotationQueueActions,
  promptActions,
  projectActions,
  experimentActions,
  evalRuleActions,
  alertActions,
} from '@e2e/pom/workspace-role-resource-actions';
import { logTraceAndVerify, checkTraceAnnotate, checkTraceDelete, checkTraceAnnotateButtonVisibility } from '@e2e/pom/workspace-role-trace-checks';
import { checkOptimizationDelete } from '@e2e/pom/workspace-role-optimization-checks';
import type { AdminCtx } from '@e2e/pom/workspace-role-shared';

function resourceChecks(member: WorkspaceRoleMember, ctx: AdminCtx, projectId: string, seeded: SeededResources) {
  return [
    { label: 'Dashboards', actions: dashboardActions(member, ctx), seededId: seeded.dashboardId, skipDeleteCheck: false },
    { label: 'Datasets', actions: datasetActions(member, ctx), seededId: seeded.datasetId, skipDeleteCheck: false },
    {
      label: 'Annotation queues',
      actions: annotationQueueActions(member, ctx, projectId),
      seededId: seeded.queueId,
      skipDeleteCheck: false,
    },
    { label: 'Prompt library', actions: promptActions(member, ctx), seededId: seeded.promptId, skipDeleteCheck: false },
    // OPIK-8091: delete-batch isn't permission-checked for these two — see
    // checkResourceEditDeleteDenied's skipDeleteCheck doc comment.
    {
      label: 'Online evaluation rules',
      actions: evalRuleActions(member, ctx, projectId),
      seededId: seeded.evalRuleId,
      skipDeleteCheck: true,
    },
    { label: 'Alerts', actions: alertActions(member, ctx), seededId: seeded.alertId, skipDeleteCheck: true },
  ] satisfies Array<{ label: string; actions: CrudActions; seededId: string; skipDeleteCheck: boolean }>;
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
    const { adminPage, adminContext, organizationId, workspaceName, manage, write, annotate, read } = workspaceRoleMembers;
    const membersPage = new ConfigurationMembersPage(adminPage, workspaceName);
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
    testInfo.setTimeout(testInfo.timeout + 90_000);
    const { manage: member, adminContext, organizationId, workspaceName } = workspaceRoleMembers;
    const ctx = adminCtx(workspaceRoleMembers);
    const env = loadEnvConfig();

    await test.step('Projects: create control visible', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeVisible();
    });

    await checkCreateDeleteSucceeds('Projects', member, projectActions(member, ctx));
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Projects',
      '/projects',
      seededResources.projectName,
      seededResources.projectName,
      true,
    );

    await checkCreateSucceeds('Experiments', member, experimentActions(member, ctx, seededResources.datasetName));

    await logTraceAndVerify(member, ctx, projectId, true);
    await checkTraceAnnotate(member, ctx, seededResources.traceId, true);
    await checkTraceAnnotateButtonVisibility(member, workspaceName, projectId, seededResources.traceId, true);
    await checkTraceDelete(member, ctx, seededResources.projectName, true);

    for (const check of SCREEN_ACCESS_CHECKS) {
      await checkScreenAccess(member, workspaceName, projectId, check, true);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, workspaceName, projectId, check, true);
    }

    for (const rc of resourceChecks(member, ctx, projectId, seededResources)) {
      await checkResourceCrudSucceeds(rc.label, member, rc.actions);
    }

    await test.step('Optimization: studio use visible', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects/${projectId}/optimizations`);
      await expect.soft(member.page.getByRole('button', { name: /Use the Optimization studio/ })).toBeVisible();
    });

    await checkOptimizationDelete(member, ctx, seededResources.datasetName, projectId, true);
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Optimization runs',
      `/projects/${projectId}/optimizations`,
      null,
      /e2e-seed-optimization/,
      true,
    );

    await test.step('Workspace settings: configure control visible', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeVisible();
    });

    await test.step('AI providers: update control visible', async () => {
      const configPage = new ConfigurationPage(member.page);
      await configPage.gotoAiProviders(workspaceName);
      await expect.soft(member.page.getByRole('button', { name: 'Add configuration', exact: true }).first()).toBeVisible();
    });

    await test.step('Invite users: Members tab accessible', async () => {
      const membersPage = new ConfigurationMembersPage(member.page, workspaceName);
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
        expect.soft(getPermissionValue(permissions, workspaceName, name), `MANAGE should hold "${name}"`).toBe(true);
      }
    });
  });

  test('WRITE', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 90_000);
    const { write: member, adminContext, organizationId, workspaceName } = workspaceRoleMembers;
    const ctx = adminCtx(workspaceRoleMembers);
    const env = loadEnvConfig();

    await test.step('Projects: create control visible', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeVisible();
    });

    await checkCreateDeleteSucceeds('Projects', member, projectActions(member, ctx));
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Projects',
      '/projects',
      seededResources.projectName,
      seededResources.projectName,
      true,
    );

    await checkCreateSucceeds('Experiments', member, experimentActions(member, ctx, seededResources.datasetName));

    await logTraceAndVerify(member, ctx, projectId, true);
    await checkTraceAnnotate(member, ctx, seededResources.traceId, true);
    await checkTraceAnnotateButtonVisibility(member, workspaceName, projectId, seededResources.traceId, true);
    await checkTraceDelete(member, ctx, seededResources.projectName, true);

    for (const check of SCREEN_ACCESS_CHECKS) {
      await checkScreenAccess(member, workspaceName, projectId, check, true);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, workspaceName, projectId, check, true);
    }

    for (const rc of resourceChecks(member, ctx, projectId, seededResources)) {
      await checkResourceCrudSucceeds(rc.label, member, rc.actions);
    }

    await checkOptimizationDelete(member, ctx, seededResources.datasetName, projectId, true);
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Optimization runs',
      `/projects/${projectId}/optimizations`,
      null,
      /e2e-seed-optimization/,
      true,
    );

    await test.step('Workspace settings: configure control absent', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeHidden();
    });

    await test.step('AI providers: update control visible', async () => {
      const configPage = new ConfigurationPage(member.page);
      await configPage.gotoAiProviders(workspaceName);
      await expect.soft(member.page.getByRole('button', { name: 'Add configuration', exact: true }).first()).toBeVisible();
    });

    await test.step('Invite users: Members tab NOT accessible despite canInviteMembers=true', async () => {
      const membersPage = new ConfigurationMembersPage(member.page, workspaceName);
      await member.page.goto(`${env.baseUrl}/${workspaceName}/configuration?tab=workspace-preferences`);
      expect
        .soft(await membersPage.isTabVisible(), 'WRITE should NOT see the Members tab (isWorkspaceOwner requires MANAGE)')
        .toBe(false);
    });

    await test.step('Permission cross-check', async () => {
      const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
      expect
        .soft(getPermissionValue(permissions, workspaceName, 'workspace_settings_configure'), 'WRITE should NOT hold workspace_settings_configure')
        .toBe(false);
      for (const name of ['online_evaluation_rule_update', 'alert_update', 'prompt_delete', 'invite_users_to_workspace', 'agent_playground_use']) {
        expect.soft(getPermissionValue(permissions, workspaceName, name), `WRITE should hold "${name}"`).toBe(true);
      }
    });
  });

  test('ANNOTATE', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 90_000);
    const { annotate: member, adminContext, organizationId, workspaceName } = workspaceRoleMembers;
    const ctx = adminCtx(workspaceRoleMembers);
    const env = loadEnvConfig();

    await test.step('Projects: create control absent', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeHidden();
    });

    await checkResourceCreateDenied('Projects', member, () => projectActions(member, ctx).create());
    await checkResourceDeleteDenied('Projects', member, seededResources.projectId, (id) => projectActions(member, ctx).remove(id));
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Projects',
      '/projects',
      seededResources.projectName,
      seededResources.projectName,
      false,
    );

    await checkResourceCreateDenied('Experiments', member, () => experimentActions(member, ctx, seededResources.datasetName).create());

    await logTraceAndVerify(member, ctx, projectId, false);
    await checkTraceAnnotate(member, ctx, seededResources.traceId, true);
    await checkTraceAnnotateButtonVisibility(member, workspaceName, projectId, seededResources.traceId, true);
    await checkTraceDelete(member, ctx, seededResources.projectName, false);

    for (const check of SCREEN_ACCESS_CHECKS) {
      await checkScreenAccess(member, workspaceName, projectId, check, false);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, workspaceName, projectId, check, false);
    }

    for (const rc of resourceChecks(member, ctx, projectId, seededResources)) {
      await checkResourceCreateDenied(rc.label, member, rc.actions.create);
      await checkResourceEditDeleteDenied(rc.label, member, rc.seededId, rc.actions, rc.skipDeleteCheck);
    }

    await checkOptimizationDelete(member, ctx, seededResources.datasetName, projectId, false);
    // Optimization runs is entirely access-denied for ANNOTATE (SCREEN_ACCESS_CHECKS
    // above already covers this) — no row-delete-visibility check makes sense here.

    await test.step('Annotation queues: page accessible (no route guard)', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects/${projectId}/annotation-queues`);
      await expect.soft(member.page.getByRole('heading', { name: 'Access denied' })).toBeHidden();
    });

    await test.step('Workspace settings, AI providers, invite users absent', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeHidden();

      const membersPage = new ConfigurationMembersPage(member.page, workspaceName);
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
        expect.soft(getPermissionValue(permissions, workspaceName, name), `ANNOTATE should NOT hold "${name}"`).toBe(false);
      }
      for (const name of ['trace_span_thread_annotate', 'annotation_queue_view', 'annotation_queue_annotate']) {
        expect.soft(getPermissionValue(permissions, workspaceName, name), `ANNOTATE should hold "${name}"`).toBe(true);
      }
    });
  });

  test('READ', async ({ workspaceRoleMembers, projectId, seededResources }, testInfo) => {
    testInfo.setTimeout(testInfo.timeout + 90_000);
    const { read: member, adminContext, organizationId, workspaceName } = workspaceRoleMembers;
    const ctx = adminCtx(workspaceRoleMembers);
    const env = loadEnvConfig();

    await test.step('Projects: create control absent', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects`);
      await expect.soft(member.page.getByRole('button', { name: 'Create project' })).toBeHidden();
    });

    await checkResourceCreateDenied('Projects', member, () => projectActions(member, ctx).create());
    await checkResourceDeleteDenied('Projects', member, seededResources.projectId, (id) => projectActions(member, ctx).remove(id));
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Projects',
      '/projects',
      seededResources.projectName,
      seededResources.projectName,
      false,
    );

    await checkResourceCreateDenied('Experiments', member, () => experimentActions(member, ctx, seededResources.datasetName).create());

    await logTraceAndVerify(member, ctx, projectId, false);
    await checkTraceAnnotate(member, ctx, seededResources.traceId, false);
    await checkTraceAnnotateButtonVisibility(member, workspaceName, projectId, seededResources.traceId, false);
    await checkTraceDelete(member, ctx, seededResources.projectName, false);

    for (const check of SCREEN_ACCESS_CHECKS) {
      // READ holds the view permission for every screen except Playground and
      // Agent playground (the latter fails the compound canViewAgentPlayground
      // check despite READ holding the raw AGENT_PLAYGROUND_USE permission).
      const expectedAccessible = !['Playground', 'Agent playground'].includes(check.name);
      await checkScreenAccess(member, workspaceName, projectId, check, expectedAccessible);
    }

    for (const check of CREATE_CONTROL_CHECKS) {
      await checkCreateControlVisibility(member, workspaceName, projectId, check, false);
    }

    for (const rc of resourceChecks(member, ctx, projectId, seededResources)) {
      await checkResourceCreateDenied(rc.label, member, rc.actions.create);
      await checkResourceEditDeleteDenied(rc.label, member, rc.seededId, rc.actions, rc.skipDeleteCheck);
    }

    await checkOptimizationDelete(member, ctx, seededResources.datasetName, projectId, false);
    await checkRowDeleteActionVisibility(
      member,
      workspaceName,
      'Optimization runs',
      `/projects/${projectId}/optimizations`,
      null,
      /e2e-seed-optimization/,
      false,
    );

    await test.step('Annotation queues: page accessible (no route guard)', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects/${projectId}/annotation-queues`);
      await expect.soft(member.page.getByRole('heading', { name: 'Access denied' })).toBeHidden();
    });

    await test.step('Optimization: studio use absent', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/projects/${projectId}/optimizations`);
      await expect.soft(member.page.getByRole('button', { name: /Use the Optimization studio/ })).toBeHidden();
    });

    await test.step('Workspace settings, AI providers, invite users absent', async () => {
      await member.page.goto(`${env.baseUrl}/${workspaceName}/configuration?tab=workspace-preferences`);
      await expect.soft(member.page.getByRole('button', { name: 'Edit' }).first()).toBeHidden();

      const membersPage = new ConfigurationMembersPage(member.page, workspaceName);
      expect.soft(await membersPage.isTabVisible(), 'READ should NOT see the Members tab').toBe(false);
    });

    await test.step('Permission cross-check', async () => {
      const permissions = await getUserPermissions(adminContext.request, organizationId, member.username);
      expect
        .soft(getPermissionValue(permissions, workspaceName, 'trace_span_thread_annotate'), 'READ should NOT hold trace_span_thread_annotate')
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
        expect.soft(getPermissionValue(permissions, workspaceName, name), `READ should hold "${name}"`).toBe(true);
      }
    });
  });
});
