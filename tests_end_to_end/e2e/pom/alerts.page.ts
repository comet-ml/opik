import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The alerts list (`/projects/$projectId/alerts`) and the alert editor
 * (`/alerts/new`, `/alerts/$alertId`), which share one form component.
 *
 * ## A note on selectors
 *
 * Nothing under `AlertsPage/` carries a `data-testid` yet, so this POM selects
 * by accessible name throughout — the conventions' documented fallback when the
 * FE cannot be touched. It is deliberate here rather than an oversight: these
 * specs have to be runnable against a *prebuilt* deployment (the PR's own
 * review environment), and a spec that depends on a testid added in the same
 * commit cannot run anywhere until that frontend is rebuilt and redeployed.
 * Adding descriptive testids to the alert form and list is worth a follow-up;
 * every locator below should move to one when they land.
 *
 * Two exceptions, both identity-based rather than positional:
 *   - rows and cells go through the shared `DataTable`'s `data-row-id` /
 *     `data-cell-id` stamps, keyed on the alert's own id;
 *   - a trigger's threshold input is addressed by its react-hook-form field
 *     path (`name="triggers.<i>.threshold"`), which names the trigger's index
 *     in the form model — not its position in the DOM.
 */
export class AlertsPage {
  constructor(private readonly page: Page) {}

  private get env() {
    return loadEnvConfig();
  }

  async goto(projectId: string): Promise<void> {
    const env = this.env;
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/alerts`);
  }

  async gotoNewAlert(projectId: string): Promise<void> {
    const env = this.env;
    await this.page.goto(`${env.baseUrl}/${env.workspace}/projects/${projectId}/alerts/new`);
    await this.waitForFormReady();
  }

  /**
   * Wait for either a real alert row OR the empty state — whichever the project
   * happens to be in. Racing them is what lets one POM serve a project with no
   * alerts and a project with several.
   */
  async waitForReady(): Promise<void> {
    const anyRow = this.page.locator('tbody tr[data-row-id]').first();
    const emptyState = this.page.getByText('No alerts yet');
    await Promise.race([
      anyRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  /** The alert editor is ready once its Name field has rendered. */
  async waitForFormReady(): Promise<void> {
    await this.nameInput.waitFor({ state: 'visible' });
  }

  // ---------------------------------------------------------------- the list

  /** One alert row, addressed by the alert's own id (`DataTable`'s stamp). */
  alertRow(alertId: string): Locator {
    return this.page.locator(`tbody tr[data-row-id="${alertId}"]`);
  }

  /**
   * A single cell of an alert's row, addressed by `<alertId>_<columnId>` so it
   * survives the user-configurable column order (`:nth-child` does not).
   */
  alertCell(alertId: string, columnId: string): Locator {
    return this.page.locator(`[data-cell-id="${alertId}_${columnId}"]`);
  }

  /** The row's Status cell — "Enabled" / "Disabled", rendered by `StatusCell`. */
  alertStatusCell(alertId: string): Locator {
    return this.alertCell(alertId, 'status');
  }

  /** The row's Name cell. */
  alertNameCell(alertId: string): Locator {
    return this.alertCell(alertId, 'name');
  }

  /**
   * Every alert name currently rendered in the list, in row order.
   *
   * Returned as the whole set rather than a per-name lookup so a caller can
   * assert the complete answer: "my alert is present" would hold just as well
   * for a list that also carried rows it should not have.
   */
  async listedAlertNames(): Promise<string[]> {
    return test.step('read the alert names in the list', async () => {
      const cells = this.page.locator('[data-cell-id$="_name"]');
      return (await cells.allInnerTexts()).map((t) => t.trim());
    });
  }

  /**
   * Open an alert in the editor through its row's kebab → Edit, the way a user
   * reaches it. Resolves once the editor has rendered.
   */
  async openAlertForEdit(alertId: string): Promise<void> {
    return test.step(`open alert ${alertId} for edit`, async () => {
      const row = this.alertRow(alertId);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      await expect(this.page.getByRole('heading', { name: 'Edit alert' })).toBeVisible();
      await this.waitForFormReady();
    });
  }

  // ---------------------------------------------------------------- the form

  /** The alert Name field. Its accessible name comes from the placeholder. */
  get nameInput(): Locator {
    return this.page.getByRole('textbox', { name: 'Name' });
  }

  /** The webhook Endpoint URL field. */
  get endpointUrlInput(): Locator {
    return this.page.getByPlaceholder('https://hooks.slack.com/services/...');
  }

  /**
   * The "Enable alert" switch. Rendered only in edit mode — the create form
   * deliberately has no toggle (every new alert is created enabled), which is
   * what `alertEnableSwitches` exists to assert.
   */
  get enableAlertSwitch(): Locator {
    return this.page.getByRole('switch', { name: 'Enable alert' });
  }

  /**
   * Every switch on the form, not just the "Enable alert" one.
   *
   * A count of zero is the assertion the create form's gating needs: "the
   * *named* switch is absent" would still pass if the toggle came back under a
   * different label.
   */
  get formSwitches(): Locator {
    return this.page.getByRole('switch');
  }

  /** The `Enable alert` label, asserted absent on the create form. */
  get enableAlertLabel(): Locator {
    return this.page.getByText('Enable alert', { exact: true });
  }

  /**
   * Add a trigger by the title shown in the "Add trigger" popover, e.g.
   * `Cost threshold`. The popover's checkboxes take their accessible name from
   * the title *and* the description, so the title is matched anchored at the
   * start — an unanchored match would be ambiguous between
   * "Trace feedback score threshold" and "Thread feedback score threshold".
   */
  async addTrigger(title: string): Promise<void> {
    return test.step(`add the "${title}" trigger`, async () => {
      await this.page.getByRole('button', { name: 'Add trigger' }).click();
      const option = this.page.getByRole('checkbox', {
        name: new RegExp(`^${escapeRegExp(title)}\\b`),
      });
      await expect(option, `"${title}" matches exactly one trigger`).toHaveCount(1);
      await option.click();
      await expect(option).toBeChecked();
      // Close the popover so the form underneath is interactable again.
      await this.page.keyboard.press('Escape');
      await expect(option).toBeHidden();
    });
  }

  /**
   * The threshold input of the trigger at `index` in the form's trigger array.
   *
   * Addressed by the react-hook-form field path the FE puts on the input's
   * `name` attribute, so it identifies the trigger rather than its position on
   * screen.
   */
  triggerThresholdInput(index: number): Locator {
    return this.page.locator(`input[name="triggers.${index}.threshold"]`);
  }

  async fillTriggerThreshold(index: number, value: string): Promise<void> {
    return test.step(`set trigger ${index}'s threshold to ${value}`, async () => {
      const input = this.triggerThresholdInput(index);
      await input.fill(value);
      await expect(input).toHaveValue(value);
    });
  }

  /**
   * The "In the last" time-window select.
   *
   * Deliberately singular: the select carries no field-path attribute, so the
   * only way to tell two of them apart would be their position. Callers keep at
   * most one windowed trigger on the form, and the `toHaveCount(1)` below makes
   * a second one fail loudly here rather than silently drive the wrong trigger.
   */
  get windowSelect(): Locator {
    return this.page.getByRole('combobox');
  }

  async selectTriggerWindow(label: string): Promise<void> {
    return test.step(`set the time window to "${label}"`, async () => {
      const select = this.windowSelect;
      await expect(
        select,
        'exactly one windowed trigger must be on the form to address it unambiguously',
      ).toHaveCount(1);
      await select.click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
      await expect(select).toContainText(label);
    });
  }

  /** Submit the create form and wait for the router to land back on the list. */
  async submitCreate(): Promise<void> {
    return test.step('submit the create-alert form', async () => {
      await this.page.getByRole('button', { name: 'Create alert' }).click();
      await this.expectBackOnList();
    });
  }

  /** Submit the edit form and wait for the router to land back on the list. */
  async submitUpdate(): Promise<void> {
    return test.step('submit the edit-alert form', async () => {
      await this.page.getByRole('button', { name: 'Update alert' }).click();
      await this.expectBackOnList();
    });
  }

  /**
   * A successful save navigates back to the alerts list. Waiting on the list
   * heading (rather than a fixed pause) is what makes "the save went through"
   * observable: a validation error leaves the form mounted and fails here.
   */
  private async expectBackOnList(): Promise<void> {
    await expect(this.page.getByRole('heading', { name: 'Alerts', level: 1 })).toBeVisible();
    await this.waitForReady();
  }
}

/** Escape a literal so it can be embedded in a `RegExp` accessible-name match. */
function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
