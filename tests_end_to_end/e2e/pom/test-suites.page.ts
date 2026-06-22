import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TestSuiteItemsPage } from './test-suite-items.page';

export interface CreateTestSuiteDialogFields {
  name: string;
  description?: string;
  assertions?: string[];
  runsPerItem?: number;
  passThreshold?: number;
}

export class TestSuitesPage {
  private projectId: string | null = null;
  /**
   * Assertions/policy stashed by fillCreateDialog. The SDK create flow only takes
   * name + description; assertions and pass criteria are applied after creation
   * via the in-suite Test settings dialog (see submitCreateDialog).
   */
  private pendingCriteria: Pick<
    CreateTestSuiteDialogFields,
    'assertions' | 'runsPerItem' | 'passThreshold'
  > = {};

  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/test-suites/`);
  }

  async waitForReady(): Promise<void> {
    const realRow = this.page.locator('tbody tr[data-row-id]').first();
    const emptyState = this.page.getByText('No test suites yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  testSuiteRow(name: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  async openTestSuiteByName(name: string): Promise<TestSuiteItemsPage> {
    const projectId = this.resolveProjectId();
    const row = this.testSuiteRow(name);
    await row.waitFor({ state: 'visible' });
    const suiteId = await row.getAttribute('data-row-id');
    if (!suiteId) {
      throw new Error(`TestSuitesPage.openTestSuiteByName: row for "${name}" has no data-row-id`);
    }
    await row.getByRole('cell', { name, exact: true }).click();
    await this.page.waitForURL((url) =>
      url.pathname.includes(`/test-suites/${suiteId}/items`),
    );
    return new TestSuiteItemsPage(this.page, projectId, suiteId);
  }

  /**
   * Opens the create sidebar in SDK mode. The empty state shows "Upload a file"
   * and "Use SDK" cards directly; once the list has rows the header button is a
   * dropdown trigger with the same two options. Handle both so the method works
   * regardless of list state.
   */
  async clickCreateTestSuite(): Promise<void> {
    const emptyStateUseSdk = this.page.getByRole('button', { name: 'Use SDK' });
    if (await emptyStateUseSdk.count()) {
      await emptyStateUseSdk.first().click();
    } else {
      await this.page.getByRole('button', { name: 'Create test suite' }).click();
      await this.page.getByRole('menuitem', { name: 'Use SDK' }).click();
    }
    await this.createDialog.waitFor({ state: 'visible' });
    await this.waitForCreateDialogTransform('translateX(0');
  }

  /**
   * SDK-mode create takes only name + description. Assertions and pass criteria
   * have no input in this flow — they're stashed and applied after creation via
   * the in-suite Test settings dialog (see submitCreateDialog).
   */
  async fillCreateDialog(fields: CreateTestSuiteDialogFields): Promise<void> {
    await this.createDialog.getByRole('textbox', { name: 'Name' }).fill(fields.name);
    if (fields.description !== undefined) {
      await this.createDialog
        .getByRole('textbox', { name: 'Description (optional)' })
        .fill(fields.description);
    }
    this.pendingCriteria = {
      assertions: fields.assertions,
      runsPerItem: fields.runsPerItem,
      passThreshold: fields.passThreshold,
    };
  }

  /**
   * Creates the (empty) suite via the SDK-mode footer button, lands on the items
   * page, then applies any assertions/pass criteria stashed by fillCreateDialog
   * through the Test settings dialog. Returns the new suite's items page.
   */
  async submitCreateDialog(name: string): Promise<TestSuiteItemsPage> {
    const projectId = this.resolveProjectId();
    await this.createDialog.getByRole('button', { name: 'Create test suite' }).click();
    await this.waitForCreateDialogTransform('translateX(100%)');

    await this.openTestSuiteByName(name);
    const match = this.page.url().match(/\/test-suites\/([0-9a-f-]+)/);
    if (!match) {
      throw new Error('TestSuitesPage.submitCreateDialog: could not derive suiteId from URL');
    }
    const items = new TestSuiteItemsPage(this.page, projectId, match[1]);

    await this.applyTestSettings();
    return items;
  }

  /** Apply the criteria stashed by fillCreateDialog via the in-suite Test settings dialog. */
  private async applyTestSettings(): Promise<void> {
    const { assertions, runsPerItem, passThreshold } = this.pendingCriteria;
    this.pendingCriteria = {};
    if (!assertions?.length && runsPerItem === undefined && passThreshold === undefined) {
      return;
    }

    await this.page.getByRole('button', { name: 'Test settings' }).click();
    const dialog = this.page.getByRole('dialog', { name: 'Test settings' });
    await dialog.waitFor({ state: 'visible' });

    if (runsPerItem !== undefined) {
      await dialog.getByRole('spinbutton', { name: 'Default runs per item' }).fill(String(runsPerItem));
    }
    if (passThreshold !== undefined) {
      await dialog.getByRole('spinbutton', { name: 'Default pass threshold' }).fill(String(passThreshold));
    }
    for (const assertion of assertions ?? []) {
      // Trigger button is "Add assertion" before any are added; becomes
      // "Assertion" (with a plus icon) once at least one exists.
      const addAssertionTrigger = dialog
        .getByRole('button', { name: 'Add assertion' })
        .or(dialog.getByRole('button', { name: 'Assertion', exact: true }));
      await addAssertionTrigger.first().click();
      const inputs = dialog.getByRole('textbox', {
        name: /Response should be factually accurate/,
      });
      await inputs.last().fill(assertion);
    }

    await dialog.getByRole('button', { name: 'Save' }).click();
    await dialog.waitFor({ state: 'hidden' });
  }

  /** Read projectId from the instance (set by goto) or fall back to the current URL. */
  private resolveProjectId(): string {
    if (this.projectId) return this.projectId;
    const match = this.page.url().match(/\/projects\/([0-9a-f-]+)\//);
    if (!match) {
      throw new Error(
        'TestSuitesPage: could not derive projectId — call goto(projectId) or navigate to a project-scoped page first',
      );
    }
    return match[1];
  }

  get createDialog(): Locator {
    return this.page.getByTestId('create-test_suite-sidebar');
  }

  private async waitForCreateDialogTransform(value: string): Promise<void> {
    await this.page.waitForFunction((expected) => {
      const el = document.querySelector('[data-testid="create-test_suite-sidebar"]') as HTMLElement | null;
      return (el?.style.transform ?? '').includes(expected);
    }, value);
  }
}
