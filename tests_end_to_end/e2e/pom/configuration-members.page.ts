import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import type { WorkspaceRoleId } from '../core/comet/client';

/** Human-readable role names as rendered by WorkspaceRolesSelectContent.tsx — `role.roleName`. */
const ROLE_NAME: Record<WorkspaceRoleId, string> = {
  'workspace-manage': 'Manage',
  'workspace-write': 'Write',
  'workspace-annotate': 'Annotate',
  'workspace-read': 'Read',
};

/**
 * Workspace Configuration → Members tab (`CollaboratorsTab.tsx`) — the
 * "Workspace role" column (`WorkspaceRoleCell.tsx`) is where an org admin
 * assigns Manage/Write/Annotate/Read to a member.
 *
 * Row identity: `data-row-id` is the row's positional index in the currently
 * rendered table ("0", "1", ...), not the member's userName/email — rows must
 * be found by cell content instead, the same way `ProjectsPage.projectRow`
 * does.
 *
 * The role `Select`'s options are Radix `SelectItem`s rendered in a portal —
 * they exist in the DOM but not inside the row, so option locators are scoped
 * to the page, not the row.
 */
export class ConfigurationMembersPage {
  constructor(
    private readonly page: Page,
    private readonly workspaceName: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('Open Configuration → Members', async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${this.workspaceName}/configuration?tab=members`);
      await this.page.getByRole('tab', { name: 'Members', selected: true }).waitFor({ state: 'visible' });
    });
  }

  async isTabVisible(): Promise<boolean> {
    return this.page
      .getByRole('tab', { name: 'Members' })
      .isVisible()
      .catch(() => false);
  }

  memberRow(identifier: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name: identifier, exact: true }) });
  }

  /** The role cell's Select trigger — its visible text is the member's current role name. */
  roleTrigger(identifier: string): Locator {
    return this.memberRow(identifier).getByRole('combobox');
  }

  async currentRoleText(userName: string): Promise<string | null> {
    return this.roleTrigger(userName).textContent();
  }

  async isRoleChangeDisabled(userName: string): Promise<boolean> {
    return this.roleTrigger(userName).isDisabled();
  }

  /**
   * Assigns `role` to `userName` via the real UI: opens the role Select and
   * picks the option named after the role (`WorkspaceRolesSelectContent.tsx`
   * renders `role.roleName` as each `SelectItem`'s visible text). Waits for
   * the mutation's PUT to resolve rather than a fixed delay.
   */
  async assignRole(userName: string, role: WorkspaceRoleId): Promise<void> {
    return test.step(`Assign role "${ROLE_NAME[role]}" to ${userName}`, async () => {
      const settled = this.page.waitForResponse(
        (res) =>
          res.request().method() === 'PUT' &&
          res.url().includes(`/workspace-roles/user/${encodeURIComponent(userName)}`),
      );
      await this.roleTrigger(userName).click();
      // exact: true matters once custom workspace roles exist alongside the
      // 4 canonical ones — self-hosted EKS has roles like "Write role with
      // Project Visibility" whose names also contain "Write", so a substring
      // match resolves to multiple options and throws a strict-mode violation.
      await this.page.getByRole('option', { name: ROLE_NAME[role], exact: true }).click();
      await settled;
      // The PUT resolving doesn't mean the cell has re-rendered with the new
      // value yet — give the trigger's own text a moment to catch up rather
      // than reading it immediately after.
      await expect(this.roleTrigger(userName)).toHaveText(ROLE_NAME[role]);
    });
  }

  async expectRole(userName: string, role: WorkspaceRoleId): Promise<boolean> {
    const text = await this.currentRoleText(userName);
    return text?.trim() === ROLE_NAME[role];
  }

  /**
   * The member list filters client-side on a debounced copy of the search
   * term — right after `fill()` the table still shows every row for a beat,
   * then re-renders down to the match. Interacting with a row's role Select
   * inside that window opens it against a trigger the debounce is about to
   * tear down, closing the listbox before an option can be picked. Waiting
   * for the table to actually narrow to the single matching row makes the
   * debounce a non-issue for whatever runs next.
   */
  async searchMembers(term: string): Promise<void> {
    return test.step(`Search members for "${term}"`, async () => {
      await this.page.getByTestId('search-input').fill(term);
      await expect(this.page.locator('tbody tr[data-row-id]')).toHaveCount(1);
    });
  }
}
