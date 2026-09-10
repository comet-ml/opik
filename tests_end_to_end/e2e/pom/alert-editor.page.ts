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

/**
 * Each window label's wire value, in seconds, mirroring the FE's
 * `WINDOW_OPTIONS`. The form shows a label and the API stores the seconds as a
 * *string*, so an API-level assertion has to state it in these terms.
 */
export const ALERT_WINDOW_SECONDS = {
  '5 mins': '300',
  '15 mins': '900',
  '30 mins': '1800',
  '1 hour': '3600',
  '6 hours': '21600',
  '12 hours': '43200',
  '24 hours': '86400',
  '7 days': '604800',
  '15 days': '1296000',
  '30 days': '2592000',
} as const;

/** Comparison operators a feedback-score condition offers, and their aria-labels. */
export const CONDITION_OPERATOR_LABEL = {
  '>': 'greater than',
  '<': 'less than',
} as const;

export type ConditionOperator = keyof typeof CONDITION_OPERATOR_LABEL;

/** One condition row's four fields, as a spec states them. */
export interface FeedbackScoreCondition {
  score: string;
  operator: ConditionOperator;
  threshold: string;
  window: AlertWindow;
}

/**
 * Rolling windows offered by a threshold trigger, as `WINDOW_OPTIONS` labels
 * them. Derived from `ALERT_WINDOW_SECONDS` so the labels and their wire values
 * cannot drift apart.
 */
export type AlertWindow = keyof typeof ALERT_WINDOW_SECONDS;

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
    // Mirrors `alertTriggerTestId` in the alerts page helpers: the wire values
    // carry `:`, which is normalized to `-` for the selector.
    //
    // One trigger per event type, so this is deliberately not `.first()`: the
    // editor's popover binds each checkbox to `selectedEventTypes.has(type)`
    // and so cannot add a second, but `POST /v1/private/alerts` accepts a
    // duplicate pair. An alert seeded that way renders two identical blocks,
    // and a strict-mode violation naming this method is the right outcome —
    // `.first()` would silently drive one of two indistinguishable triggers.
    // `assertSingleTriggerConfig` turns that into a legible message.
    return this.page.getByTestId(`alert-trigger-${eventType.replace(/:/g, '-')}`);
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
   * Fills a threshold trigger's threshold and rolling window. Only
   * `trace:cost`, `trace:latency` and `trace:errors` render these controls.
   */
  async configureThresholdTrigger(
    eventType: AlertEventType,
    threshold: string,
    window: AlertWindow,
  ): Promise<void> {
    return test.step(`configure ${eventType} at ${threshold} over ${window}`, async () => {
      await this.assertSingleTriggerConfig(eventType);
      const config = this.triggerConfig(eventType);
      await config.locator('input[type="number"]').fill(threshold);
      await config.getByRole('combobox').click();
      await this.page.getByRole('option', { name: window, exact: true }).click();
    });
  }

  /**
   * The OR-groups / AND-conditions builder a feedback-score trigger renders.
   *
   * `triggerIndex` is the trigger's position in the form's `triggers` array —
   * the order they were added in — because that index is baked into every
   * field path the shared component registers. It is not derivable from the
   * event type, so the caller states it.
   */
  feedbackScoreConditions(
    eventType: AlertEventType,
    triggerIndex: number,
  ): FeedbackScoreConditionsSection {
    return new FeedbackScoreConditionsSection(
      this.page,
      this.triggerConfig(eventType),
      `triggers.${triggerIndex}.groups`,
    );
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

/**
 * The feedback-score condition builder inside one trigger's config block.
 *
 * Groups and conditions are addressed by index rather than by any label,
 * because the index *is* the identity the feature is about: it becomes
 * `group_index` on the wire and the `triggers.N.groups.G.conditions.C.*` path
 * the form registers, so "the value landed on the wrong row" is only
 * expressible in those terms. Everything is scoped to the trigger block, so a
 * form carrying both a trace and a thread trigger addresses two separate
 * builders without ambiguity.
 */
export class FeedbackScoreConditionsSection {
  constructor(
    private readonly page: Page,
    private readonly root: Locator,
    private readonly groupsPath: string,
  ) {}

  /** `triggers.N.groups.G.conditions.` — the prefix every field in a group shares. */
  private conditionPathPrefix(groupIndex: number): string {
    return `${this.groupsPath}.${groupIndex}.conditions.`;
  }

  /**
   * Every OR group currently rendered, counted by its own delete button.
   *
   * The group header is the only per-group markup with a stable handle, and it
   * carries exactly one `Remove group` button — so this is a count of groups,
   * not an approximation of one.
   */
  get groups(): Locator {
    return this.root.getByRole('button', { name: 'Remove group' });
  }

  /**
   * One group's container.
   *
   * Anchored on a field the group owns and walked up to the nearest ancestor
   * that also holds a `Remove group` button, which is the group container by
   * construction: the delete button lives in the group header, a sibling
   * subtree of the conditions, so no closer ancestor can contain both.
   *
   * Positional CSS (`:nth-child`) is deliberately avoided — but the group's
   * *index* is not positional trivia here, it is the identity the feature is
   * about: it becomes `group_index` on the wire and the `groups.G.conditions.C`
   * path the form registers.
   */
  group(groupIndex: number): Locator {
    return this.root.locator(
      `xpath=(.//input[starts-with(@name, "${this.conditionPathPrefix(groupIndex)}")])[1]` +
        '/ancestor::div[.//button[@aria-label="Remove group"]][1]',
    );
  }

  /** The group's own header label — "Group 1", "Group 2", … (1-based in the UI). */
  groupLabel(groupIndex: number): Locator {
    return this.root.getByText(`Group ${groupIndex + 1}`, { exact: true });
  }

  /**
   * Every condition row in a group, counted by its threshold input.
   *
   * React Hook Form stamps the path it registered onto that input's `name`, so
   * the set of inputs under `groups.G.conditions.` *is* the set of conditions
   * the form believes group G has — a stronger statement than counting rendered
   * boxes.
   */
  conditions(groupIndex: number): Locator {
    return this.root.locator(`input[name^="${this.conditionPathPrefix(groupIndex)}"]`);
  }

  /**
   * One condition row, anchored on its threshold input's registered path and
   * walked up to the nearest ancestor holding a `Remove condition` button —
   * which is the row, since that button is the row's own trailing control.
   */
  condition(groupIndex: number, conditionIndex: number): Locator {
    return this.root.locator(
      `xpath=.//input[@name="${this.expectedThresholdFieldPath(groupIndex, conditionIndex)}"]` +
        '/ancestor::div[.//button[@aria-label="Remove condition"]][1]',
    );
  }

  get addGroupButton(): Locator {
    return this.root.getByRole('button', { name: 'Add OR group' });
  }

  addConditionButton(groupIndex: number): Locator {
    return this.group(groupIndex).getByRole('button', { name: 'Add AND condition' });
  }

  removeGroupButton(groupIndex: number): Locator {
    return this.group(groupIndex).getByRole('button', { name: 'Remove group' });
  }

  removeConditionButton(groupIndex: number, conditionIndex: number): Locator {
    return this.condition(groupIndex, conditionIndex).getByRole('button', {
      name: 'Remove condition',
    });
  }

  /**
   * The score select's trigger button.
   *
   * Matched on `aria-haspopup="dialog"`, which is the popover trigger's own
   * contract and unique in the row: its accessible name is whatever score is
   * currently picked, so a name lookup would have to know the answer already,
   * and the window select beside it is a `combobox` rather than a popover.
   */
  scoreSelect(groupIndex: number, conditionIndex: number): Locator {
    return this.condition(groupIndex, conditionIndex).locator(
      'button[aria-haspopup="dialog"]',
    );
  }

  operatorOption(
    groupIndex: number,
    conditionIndex: number,
    operator: ConditionOperator,
  ): Locator {
    return this.condition(groupIndex, conditionIndex).getByRole('radio', {
      name: CONDITION_OPERATOR_LABEL[operator],
    });
  }

  /**
   * The threshold input, addressed by the very field path the component
   * registered it under — so a wrong `groupsPath` fails here as "no such
   * element" rather than as a value that quietly went missing.
   */
  thresholdInput(groupIndex: number, conditionIndex: number): Locator {
    return this.root.locator(
      `input[name="${this.expectedThresholdFieldPath(groupIndex, conditionIndex)}"]`,
    );
  }

  /** The rolling-window select, which renders as "In the last <label>". */
  windowSelect(groupIndex: number, conditionIndex: number): Locator {
    return this.condition(groupIndex, conditionIndex).getByRole('combobox');
  }

  /**
   * The React Hook Form path a condition's threshold input is registered under,
   * and the anchor everything else in the row is found from.
   *
   * The component builds every field path at runtime from a `groupsPath` prop
   * and casts it, so a wrong path registers a field nobody reads rather than
   * throwing — the value is silently dropped or lands on another row. RHF
   * stamps the registered path onto the input's `name`, which is why selecting
   * on it is both the most stable handle in this widget and the earliest
   * evidence the path is right.
   */
  expectedThresholdFieldPath(groupIndex: number, conditionIndex: number): string {
    return `${this.groupsPath}.${groupIndex}.conditions.${conditionIndex}.threshold`;
  }

  /** Separator badges between the OR groups: one fewer than the group count. */
  get orSeparators(): Locator {
    return this.root.getByText('OR', { exact: true });
  }

  /** Separator badges between a group's AND conditions. */
  andSeparators(groupIndex: number): Locator {
    return this.group(groupIndex).getByText('AND', { exact: true });
  }

  async addGroup(): Promise<void> {
    return test.step('add an OR group', async () => {
      const before = await this.groups.count();
      await this.addGroupButton.click();
      await expect(this.groups).toHaveCount(before + 1);
    });
  }

  async addCondition(groupIndex: number): Promise<void> {
    return test.step(`add an AND condition to group ${groupIndex + 1}`, async () => {
      const before = await this.conditions(groupIndex).count();
      await this.addConditionButton(groupIndex).click();
      await expect(this.conditions(groupIndex)).toHaveCount(before + 1);
    });
  }

  async selectScore(
    groupIndex: number,
    conditionIndex: number,
    scoreName: string,
  ): Promise<void> {
    return test.step(`pick score "${scoreName}"`, async () => {
      await this.scoreSelect(groupIndex, conditionIndex).click();
      await this.page.getByRole('option', { name: scoreName, exact: true }).click();
      await expect(this.scoreSelect(groupIndex, conditionIndex)).toHaveText(scoreName);
    });
  }

  async selectOperator(
    groupIndex: number,
    conditionIndex: number,
    operator: ConditionOperator,
  ): Promise<void> {
    return test.step(`pick operator "${operator}"`, async () => {
      await this.operatorOption(groupIndex, conditionIndex, operator).click();
      await expect(this.operatorOption(groupIndex, conditionIndex, operator)).toBeChecked();
    });
  }

  async selectWindow(
    groupIndex: number,
    conditionIndex: number,
    window: AlertWindow,
  ): Promise<void> {
    return test.step(`pick window "${window}"`, async () => {
      await this.windowSelect(groupIndex, conditionIndex).click();
      await this.page.getByRole('option', { name: window, exact: true }).click();
      await expect(this.windowSelect(groupIndex, conditionIndex)).toHaveText(
        `In the last ${window}`,
      );
    });
  }

  /** Fills all four fields of one condition row. */
  async fillCondition(
    groupIndex: number,
    conditionIndex: number,
    condition: FeedbackScoreCondition,
  ): Promise<void> {
    return test.step(
      `fill group ${groupIndex + 1} condition ${conditionIndex + 1}`,
      async () => {
        await this.selectScore(groupIndex, conditionIndex, condition.score);
        await this.selectOperator(groupIndex, conditionIndex, condition.operator);
        await this.thresholdInput(groupIndex, conditionIndex).fill(condition.threshold);
        await this.selectWindow(groupIndex, conditionIndex, condition.window);
      },
    );
  }

  /** Asserts every field of one condition row, plus the RHF path it registered under. */
  async expectCondition(
    groupIndex: number,
    conditionIndex: number,
    condition: FeedbackScoreCondition,
  ): Promise<void> {
    return test.step(
      `verify group ${groupIndex + 1} condition ${conditionIndex + 1}`,
      async () => {
        // Exactly one input registered under this path: the row exists, and it
        // is not one of two fields fighting over the same path.
        await expect(this.thresholdInput(groupIndex, conditionIndex)).toHaveCount(1);
        await expect(this.scoreSelect(groupIndex, conditionIndex)).toHaveText(condition.score);
        await expect(
          this.operatorOption(groupIndex, conditionIndex, condition.operator),
        ).toBeChecked();
        await expect(this.thresholdInput(groupIndex, conditionIndex)).toHaveValue(
          condition.threshold,
        );
        await expect(this.windowSelect(groupIndex, conditionIndex)).toHaveText(
          `In the last ${condition.window}`,
        );
      },
    );
  }

  async removeCondition(groupIndex: number, conditionIndex: number): Promise<void> {
    return test.step(
      `remove group ${groupIndex + 1} condition ${conditionIndex + 1}`,
      async () => {
        await this.removeConditionButton(groupIndex, conditionIndex).click();
      },
    );
  }

  /**
   * Reads the tooltip a disabled remove button carries.
   *
   * Hover has to land on the wrapping span, not the button: the Button variants
   * give a disabled button `pointer-events: none`, which is exactly why the
   * component wraps it — so Playwright's actionability check fails on the
   * button itself. Radix renders the tooltip's accessible copy once, as the
   * only `role="tooltip"` node.
   *
   * Dismissal is an explicit Escape rather than moving the pointer away:
   * Radix keeps the tooltip open across a bare `mouse.move`, so without this
   * a second read would find two identically-worded tooltips and fail strict
   * mode instead of comparing anything.
   */
  async disabledTooltipText(button: Locator): Promise<string> {
    return test.step('read the disabled control tooltip', async () => {
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip).toHaveCount(0);
      await button.locator('xpath=..').hover();
      await expect(tooltip).toHaveCount(1);
      const text = (await tooltip.textContent()) ?? '';
      await this.page.keyboard.press('Escape');
      await expect(tooltip).toHaveCount(0);
      return text;
    });
  }
}
