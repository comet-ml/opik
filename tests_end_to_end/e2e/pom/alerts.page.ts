import { test, expect, type Page, type Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

/**
 * The alert create/edit form at `/$workspace/projects/$projectId/alerts/new`
 * and `.../alerts/$alertId`.
 *
 * Both routes render the same `AlertForm`, so one class covers them; the only
 * difference the specs care about is that the Enable alert switch is edit-only.
 */
export class AlertFormPage {
  constructor(private readonly page: Page) {}

  async gotoCreate(projectId: string): Promise<void> {
    return test.step('open the Create alert form', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/alerts/new`,
      );
      await this.waitForReady();
    });
  }

  async gotoEdit(projectId: string, alertId: string): Promise<void> {
    return test.step(`open the edit form for alert ${alertId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/alerts/${alertId}`,
      );
      await this.waitForReady();
    });
  }

  /** Both sections rendered and the Name field mounted — the form is interactive. */
  async waitForReady(): Promise<void> {
    await this.nameInput.waitFor({ state: 'visible' });
    await this.triggersSection.waitFor({ state: 'visible' });
    await this.webhookSettingsSection.waitFor({ state: 'visible' });
  }

  get nameInput(): Locator {
    return this.page.getByTestId('alert-name-input');
  }

  get endpointUrlInput(): Locator {
    return this.page.getByTestId('alert-webhook-url-input');
  }

  get triggersSection(): Locator {
    return this.page.getByTestId('alert-triggers-section');
  }

  get webhookSettingsSection(): Locator {
    return this.page.getByTestId('alert-webhook-settings-section');
  }

  /**
   * The Enable alert switch. Named through its `htmlFor`-bound label rather
   * than a testid so that "the control is not rendered on the create form"
   * fails if the label is dropped too — the user-visible fact is the labelled
   * control, not the attribute.
   */
  get enabledSwitch(): Locator {
    return this.page.getByRole('switch', { name: 'Enable alert' });
  }

  get testConnectionButton(): Locator {
    return this.webhookSettingsSection.getByRole('button', { name: 'Test connection' });
  }

  get testTriggerButton(): Locator {
    return this.triggersSection.getByRole('button', { name: 'Test trigger' });
  }

  /** The "Example payload" accordion triggers, one per configured trigger. */
  get examplePayloadToggles(): Locator {
    return this.triggersSection.getByRole('button', { name: 'Example payload' });
  }

  /**
   * The two form sections in DOM order.
   *
   * `evaluateAll` over a single locator matching both, rather than two
   * bounding-box reads: order is a fact about the document, and comparing
   * rendered y-coordinates would also pass for a layout that merely happened to
   * paint them that way.
   */
  async sectionOrder(): Promise<string[]> {
    return test.step('read the form section order', async () =>
      this.page
        .locator(
          '[data-testid="alert-triggers-section"], [data-testid="alert-webhook-settings-section"]',
        )
        .evaluateAll((els) => els.map((el) => el.getAttribute('data-testid') ?? '')));
  }

  /**
   * Add a trigger by its card title (`TRIGGER_CONFIG[...].title`).
   *
   * The picker is a multi-select popover that stays open after a choice by
   * design, so it is dismissed with Escape rather than by clicking away — a
   * stray click would land on whatever the popover was covering.
   */
  async addTrigger(title: string): Promise<void> {
    return test.step(`add the "${title}" trigger`, async () => {
      await this.triggersSection.getByRole('button', { name: 'Add trigger' }).click();
      const option = this.page.getByRole('checkbox', { name: title });
      await expect(option, `exactly one "${title}" option in the trigger picker`).toHaveCount(1);
      await option.click();
      await this.page.keyboard.press('Escape');
      await expect(this.triggerCardTitle(title)).toBeVisible();
    });
  }

  /** The configured trigger's own title inside the triggers card. */
  triggerCardTitle(title: string): Locator {
    return this.triggersSection.getByText(new RegExp(`^\\s*${escapeRegExp(title)}\\s*$`));
  }

  /**
   * The threshold input of the only threshold-style trigger on the form.
   *
   * `role=spinbutton` is the `type="number"` input; the specs that use this
   * configure exactly one threshold trigger and assert that count first, so an
   * ambiguous match fails loudly instead of silently editing another row.
   */
  get thresholdInput(): Locator {
    return this.triggersSection.getByRole('spinbutton');
  }

  /** The "In the last" window select of the only threshold-style trigger. */
  get windowSelect(): Locator {
    return this.triggersSection.getByRole('combobox');
  }

  async setThreshold(value: string): Promise<void> {
    return test.step(`set the trigger threshold to ${value}`, async () => {
      await expect(this.thresholdInput, 'exactly one threshold input on the form').toHaveCount(1);
      await this.thresholdInput.fill(value);
    });
  }

  async selectWindow(label: string): Promise<void> {
    return test.step(`set the trigger window to "${label}"`, async () => {
      await expect(this.windowSelect, 'exactly one window select on the form').toHaveCount(1);
      await this.windowSelect.click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
      await expect(this.windowSelect).toContainText(label);
    });
  }

  async expandExamplePayload(): Promise<void> {
    return test.step('expand the trigger\'s Example payload accordion', async () => {
      await expect(
        this.examplePayloadToggles,
        'exactly one Example payload accordion on the form',
      ).toHaveCount(1);
      await this.examplePayloadToggles.click();
    });
  }

  async fillName(value: string): Promise<void> {
    return test.step(`type "${value}" into the Name field`, async () => {
      await this.nameInput.fill(value);
    });
  }

  async clearName(): Promise<void> {
    return test.step('clear the Name field', async () => {
      await this.nameInput.fill('');
    });
  }

  async fillEndpointUrl(value: string): Promise<void> {
    return test.step(`set the Endpoint URL to "${value}"`, async () => {
      await this.endpointUrlInput.fill(value);
    });
  }

  /**
   * Assert the toast currently on screen says `message`, then dismiss it.
   *
   * Dismissing is not tidiness: the toaster holds one toast at a time and
   * auto-closes after ~5s, so a second click that produced *no* toast would
   * still find the first one's text on screen and pass. Clearing between
   * assertions is what makes each one about the click that preceded it.
   */
  async expectToastAndDismiss(message: string): Promise<void> {
    return test.step(`toast reads "${message}"`, async () => {
      await expect(this.page.getByText(message, { exact: true })).toBeVisible();
      await this.dismissToast();
    });
  }

  /** Close the visible toast, if any, and wait for it to leave the DOM. */
  async dismissToast(): Promise<void> {
    const close = this.page.locator('[toast-close]');
    if ((await close.count()) === 0) return;
    // The close button is `opacity-0` until the toast is hovered — present and
    // hit-testable, just transparent, so the click needs no hover dance.
    await close.first().click();
    await expect(close).toHaveCount(0);
  }

  /**
   * Submit the form and wait for the navigation back to the alerts list, which
   * is what `onSuccess` does — so returning early would race the write.
   */
  async submit(label: 'Create alert' | 'Update alert'): Promise<void> {
    return test.step(`submit the form with "${label}"`, async () => {
      await this.page.getByRole('button', { name: label, exact: true }).click();
      await this.page.waitForURL(/\/alerts$/);
    });
  }
}

function escapeRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}
