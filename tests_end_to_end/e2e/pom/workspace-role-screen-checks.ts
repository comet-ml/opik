import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
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
  { name: 'Experiments', path: (id) => `/projects/${id}/experiments`, buttonName: 'Create experiment' },
  { name: 'Online evaluation rules', path: (id) => `/projects/${id}/online-evaluation`, buttonName: 'Create rule' },
  { name: 'Alerts', path: (id) => `/projects/${id}/alerts`, buttonName: 'Create alert' },
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

/**
 * The row-level "Delete" action shares one pattern across every list page
 * this suite touches (Projects, Optimization runs): open the row's Actions
 * menu, then check whether "Delete" is offered. Visibility-only, matching
 * the depth of the suite's other UI checks — doesn't click through to the
 * confirm dialog.
 */
export async function checkRowDeleteActionVisibility(
  member: WorkspaceRoleMember,
  workspaceName: string,
  label: string,
  path: string,
  searchTerm: string | null,
  rowName: string | RegExp,
  expectedVisible: boolean,
): Promise<void> {
  await test.step(`${label}: row delete action ${expectedVisible ? 'visible' : 'absent'}`, async () => {
    const env = loadEnvConfig();
    await member.page.goto(`${env.baseUrl}/${workspaceName}${path}`);
    if (searchTerm !== null) {
      // Only needed for lists that both (a) filter by exactly this typed
      // term and (b) live in a shared, cluttered workspace where the seeded
      // row may not be on page 1 (e.g. Projects). Confirmed live that the
      // Optimization runs page's search box does NOT filter by run name —
      // passing a term there returns "No matching results" even for a row
      // that's genuinely on the page — and its anchor project is fresh per
      // test run anyway, so it never needs filtering in the first place.
      await member.page.getByTestId('search-input').fill(searchTerm);
    }
    const row = member.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: member.page.getByRole('cell', { name: rowName, exact: true }) });
    await row.waitFor({ state: 'visible' });

    // A role without delete rights may not render the "Actions menu" trigger
    // at all (not just hide "Delete" inside an always-present menu) — check
    // this first so the negative case doesn't hang trying to click a button
    // that was never going to appear.
    const actionsButton = row.getByRole('button', { name: 'Actions menu' });
    const hasActionsButton = await actionsButton.isVisible().catch(() => false);
    if (!hasActionsButton) {
      expect
        .soft(expectedVisible, `${member.role}: expected ${label} row delete action to be ${expectedVisible ? 'visible' : 'absent'} (Actions menu itself is not rendered)`)
        .toBe(false);
      return;
    }

    await actionsButton.click();
    const deleteItem = member.page.getByRole('menuitem', { name: 'Delete' });
    if (expectedVisible) {
      await expect.soft(deleteItem).toBeVisible();
    } else {
      await expect.soft(deleteItem).toBeHidden();
    }
    await member.page.keyboard.press('Escape');
  });
}
