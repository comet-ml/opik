import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import type { AlertEventType } from '../fixtures/alert.fixture';

/**
 * Longer than the 15s default action timeout: the first read after an idle
 * backend routinely takes ~20s, and the edit route waits on an alert fetch.
 */
const FORM_READY_TIMEOUT_MS = 30_000;

/** Destinations offered by the editor's `DestinationSelector`. */
export type AlertDestination = 'General' | 'Slack' | 'PagerDuty';

/** Rolling windows offered by a threshold trigger, as `WINDOW_OPTIONS` labels them. */
export type AlertWindow =
  | '5 mins'
  | '15 mins'
  | '30 mins'
  | '1 hour'
  | '6 hours'
  | '12 hours'
  | '24 hours'
  | '7 days'
  | '15 days'
  | '30 days';

/**
 * The alert form at /projects/$projectId/alerts/new and /alerts/$alertId.
 *
 * One class for both routes: they render the same `AlertForm`, differing only
 * in heading and submit label, and the create→edit round trip is what the
 * specs assert on.
 */
export class AlertEditorPage {
  constructor(private readonly page: Page) {}

  async gotoEdit(projectId: string, alertId: string): Promise<void> {
    return test.step(`Open the edit form for alert ${alertId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/alerts/${alertId}`,
      );
      await this.waitForEditReady();
    });
  }

  async waitForCreateReady(timeoutMs = FORM_READY_TIMEOUT_MS): Promise<void> {
    return test.step('Wait for the create form ready', async () => {
      await this.page
        .getByRole('heading', { name: 'Create a new alert' })
        .waitFor({ timeout: timeoutMs });
    });
  }

  /**
   * The edit route renders a `<Loader>` in place of the form until the alert
   * resolves, so the heading appearing already means the fields are hydrated.
   */
  async waitForEditReady(timeoutMs = FORM_READY_TIMEOUT_MS): Promise<void> {
    return test.step('Wait for the edit form ready', async () => {
      await this.page
        .getByRole('heading', { name: 'Edit alert' })
        .waitFor({ timeout: timeoutMs });
    });
  }

  get nameInput(): Locator {
    return this.page.getByTestId('alert-name-input');
  }

  /**
   * Endpoint URL. Needs a testid: its `<Label>` renders without `htmlFor` and
   * the form-item id is React-generated, so the field's only accessible name
   * would be its placeholder copy.
   */
  get webhookUrlInput(): Locator {
    return this.page.getByTestId('alert-webhook-url-input');
  }

  get enableAlertSwitch(): Locator {
    return this.page.getByRole('switch', { name: 'Enable alert' });
  }

  /** The toggle group renders its options as radios, one per destination. */
  destinationOption(destination: AlertDestination): Locator {
    return this.page.getByRole('radio', { name: destination });
  }

  /** "Create alert" on /new, "Update alert" on /$alertId. */
  get submitButton(): Locator {
    return this.page.getByRole('button', { name: /^(Create|Update) alert$/ });
  }

  /**
   * A selected trigger's config block.
   *
   * Scoped by testid rather than by the trigger's visible title: the title also
   * appears in the Test-alert panel's accordion, so a text lookup resolves
   * there instead and finds none of the config controls.
   */
  triggerConfig(eventType: AlertEventType): Locator {
    // One trigger per event type, so this is deliberately not `.first()`: the
    // editor's popover binds each checkbox to `selectedEventTypes.has(type)`
    // and so cannot add a second, but `POST /v1/private/alerts` accepts a
    // duplicate pair. An alert seeded that way renders two identical blocks,
    // and a strict-mode violation naming this method is the right outcome —
    // `.first()` would silently drive one of two indistinguishable triggers.
    // `assertSingleTriggerConfig` turns that into a legible message.
    return this.page.getByTestId(this.triggerTestId(eventType));
  }

  /**
   * Mirrors `alertTriggerTestId` in the alerts page helpers: the wire values
   * carry `:`, which is normalized to `-` for the selector.
   */
  private triggerTestId(eventType: AlertEventType): string {
    return `alert-trigger-${eventType.replace(/:/g, '-')}`;
  }

  /**
   * Fails with an explicit message unless exactly one config block is present:
   * none means the trigger was never added, several mean the alert was seeded
   * through the API with a duplicate pair (the editor cannot make one).
   */
  private async assertSingleTriggerConfig(eventType: AlertEventType): Promise<void> {
    const count = await this.triggerConfig(eventType).count();
    if (count === 1) return;
    throw new Error(
      count === 0
        ? `no "${eventType}" trigger on this alert — add it before configuring it.`
        : `alert has ${count} "${eventType}" triggers, so its config block is ambiguous. ` +
          'The editor cannot create duplicates; seed one trigger per event type.',
    );
  }

  async fillName(name: string): Promise<void> {
    return test.step(`fill the alert name "${name}"`, async () => {
      await this.nameInput.fill(name);
    });
  }

  async fillWebhookUrl(url: string): Promise<void> {
    return test.step(`fill the endpoint URL "${url}"`, async () => {
      await this.webhookUrlInput.fill(url);
    });
  }

  async selectDestination(destination: AlertDestination): Promise<void> {
    return test.step(`select the "${destination}" destination`, async () => {
      await this.destinationOption(destination).click();
      await expect(this.destinationOption(destination)).toBeChecked();
    });
  }

  /**
   * Flips the enable switch to `enabled`.
   *
   * Asserts the starting state before clicking, so a regression that hydrates
   * the form from the wrong value fails here rather than silently toggling the
   * alert the wrong way.
   */
  async setEnabled(enabled: boolean): Promise<void> {
    return test.step(`${enabled ? 'enable' : 'disable'} the alert`, async () => {
      const toggle = this.enableAlertSwitch;
      await expect(toggle, 'form hydrates the switch from the persisted value').toBeChecked({
        checked: !enabled,
      });
      await toggle.click();
      await expect(toggle).toBeChecked({ checked: enabled });
    });
  }

  /**
   * Ticks an event type in the "Add trigger" popover, then closes it.
   *
   * Each popover row is one `<label>` wrapping the title and its description,
   * so the title is matched on the label and the checkbox reached through it —
   * the checkboxes carry no distinguishing name of their own.
   */
  async addTrigger(triggerTitle: string): Promise<void> {
    return test.step(`add the "${triggerTitle}" trigger`, async () => {
      await this.page.getByRole('button', { name: 'Add trigger' }).click();
      const popover = this.page.locator('[data-radix-popper-content-wrapper]');
      await popover.waitFor({ state: 'visible' });
      await popover.locator('label').filter({ hasText: triggerTitle }).getByRole('checkbox').click();
      await this.page.keyboard.press('Escape');
      await popover.waitFor({ state: 'detached' });
    });
  }

  /**
   * Removes a selected trigger through the `X` beside its config block.
   *
   * Asserts the block is gone rather than returning immediately: the form's
   * derived state (the suggested name among it) recomputes off the trigger
   * array, so a caller asserting on that needs the removal to have landed.
   */
  async removeTrigger(eventType: AlertEventType): Promise<void> {
    return test.step(`remove the ${eventType} trigger`, async () => {
      await this.assertSingleTriggerConfig(eventType);
      await this.page.getByTestId(`${this.triggerTestId(eventType)}-remove`).click();
      await expect(this.triggerConfig(eventType)).toHaveCount(0);
    });
  }

  /**
   * Fills a threshold trigger's threshold and rolling window. Only
   * `trace:cost`, `trace:latency` and `trace:errors` render these controls.
   */
  async configureThresholdTrigger(
    eventType: AlertEventType,
    threshold: string,
    window: AlertWindow,
  ): Promise<void> {
    return test.step(`configure ${eventType} at ${threshold} over ${window}`, async () => {
      await this.setTriggerThreshold(eventType, threshold);
      await this.setTriggerWindow(eventType, window);
    });
  }

  /**
   * Sets a threshold trigger's threshold, leaving its window alone.
   *
   * Split out of `configureThresholdTrigger` for tests that assert on state
   * derived from one field at a time — the suggested alert name recomputes on
   * each, and setting both at once would hide a field that stopped feeding it.
   */
  async setTriggerThreshold(eventType: AlertEventType, threshold: string): Promise<void> {
    return test.step(`set the ${eventType} threshold to ${threshold}`, async () => {
      await this.assertSingleTriggerConfig(eventType);
      await this.triggerConfig(eventType).locator('input[type="number"]').fill(threshold);
    });
  }

  /** Sets a threshold trigger's rolling window, leaving its threshold alone. */
  async setTriggerWindow(eventType: AlertEventType, window: AlertWindow): Promise<void> {
    return test.step(`set the ${eventType} window to ${window}`, async () => {
      await this.assertSingleTriggerConfig(eventType);
      await this.triggerConfig(eventType).getByRole('combobox').click();
      await this.page.getByRole('option', { name: window, exact: true }).click();
    });
  }

  /**
   * Submits the form and waits for the redirect back to the list.
   *
   * `AlertForm` navigates only in the mutation's `onSuccess`, so the settled
   * list URL is proof the write was accepted — not merely that the click landed.
   */
  async submit(): Promise<void> {
    return test.step('submit the alert form', async () => {
      await this.submitButton.click();
      await this.page.waitForURL(/\/alerts(\?|$)/);
    });
  }
}
