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

  async clickCreateTestSuite(): Promise<void> {
    await this.page.getByRole('button', { name: 'Create test suite' }).click();
    await this.createDialog.waitFor({ state: 'visible' });
    await this.waitForCreateDialogTransform('translateX(0');
  }

  async fillCreateDialog(fields: CreateTestSuiteDialogFields): Promise<void> {
    await this.createDialog.getByRole('textbox', { name: 'Name' }).fill(fields.name);
    if (fields.description !== undefined) {
      await this.createDialog.getByRole('textbox', { name: 'Description' }).fill(fields.description);
    }
    await this.createDialog.getByRole('button', { name: 'Next' }).click();
    await this.createDialog
      .getByRole('heading', { name: 'Add test data', level: 3 })
      .waitFor({ state: 'visible' });

    const hasAdvanced =
      fields.assertions?.length || fields.runsPerItem !== undefined || fields.passThreshold !== undefined;
    if (hasAdvanced) {
      await this.createDialog.getByRole('heading', { name: 'Advanced settings', level: 3 }).click();
      if (fields.runsPerItem !== undefined) {
        await this.createDialog
          .getByRole('spinbutton', { name: 'Default runs per item' })
          .fill(String(fields.runsPerItem));
      }
      if (fields.passThreshold !== undefined) {
        await this.createDialog
          .getByRole('spinbutton', { name: 'Default pass threshold' })
          .fill(String(fields.passThreshold));
      }
      for (const assertion of fields.assertions ?? []) {
        // Trigger button is "No assertions added yet" before any are added; becomes
        // "Assertion" (with a plus icon) once at least one exists.
        const addAssertionTrigger = this.createDialog
          .getByRole('button', { name: 'No assertions added yet' })
          .or(this.createDialog.getByRole('button', { name: 'Assertion', exact: true }));
        await addAssertionTrigger.first().click();
        const inputs = this.createDialog.getByRole('textbox', {
          name: /Response should be factually accurate/,
        });
        await inputs.last().fill(assertion);
      }
    }
  }

  /**
   * Submits the dialog (already on step 2). Returns the new suite's items page.
   *
   * The dialog has a 3-step flow ending on a "Test suite created!" success screen
   * with "Go to test suite" + "Create another" buttons (mirrors the dataset
   * create flow). We click "Go to test suite" to land on the items page.
   */
  async submitCreateDialog(name: string): Promise<TestSuiteItemsPage> {
    const projectId = this.resolveProjectId();
    await this.createDialog.getByRole('button', { name: 'Create', exact: true }).click();
    // Wait for the success screen.
    await this.createDialog
      .getByRole('heading', { name: 'Test suite created!' })
      .waitFor({ state: 'visible', timeout: 15_000 });
    await this.createDialog.getByRole('button', { name: 'Go to test suite' }).click();

    // After "Go to test suite", the URL navigates to the items page.
    await this.page.waitForURL(/\/test-suites\/[0-9a-f-]+\/items/, { timeout: 15_000 });
    const match = this.page.url().match(/\/test-suites\/([0-9a-f-]+)/);
    if (!match) {
      throw new Error('TestSuitesPage.submitCreateDialog: could not derive suiteId from URL');
    }
    return new TestSuiteItemsPage(this.page, projectId, match[1]);
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
