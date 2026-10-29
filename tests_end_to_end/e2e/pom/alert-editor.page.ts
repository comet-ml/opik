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

/** The comparison operators a feedback-score condition offers — the pair the alerts API accepts. */
export type ConditionOperator = '>' | '<';

/**
 * `OPERATOR_LABELS` in pages-shared/feedback-score-conditions/constants.ts.
 *
 * The toggle renders the operator glyph as its text and the word as its
 * `aria-label`, and the glyph alone is a poor accessible name to select on —
 * `<` and `>` appear all over a form. The label is the stable handle.
 */
const OPERATOR_LABEL: Record<ConditionOperator, string> = {
  '>': 'greater than',
  '<': 'less than',
};

/**
 * One condition row of the shared `FeedbackScoreConditions` builder, resolved
 * to the four controls a spec drives and reads back.
 *
 * Every control is reached from the threshold `<input>`, which is the one
 * element in the row the DOM gives a stable identity: react-hook-form stamps
 * the field path onto `name`
 * (`triggers.0.groups.1.conditions.0.threshold`), so a row is addressed by the
 * group and condition index it actually holds rather than by its position among
 * siblings. The remaining controls carry no name, id or testid of their own —
 * the FormItem ids are React-generated — so they are reached by hopping two
 * levels to the shared field container and then selecting on role. A testid on
 * the row would be better and belongs in the frontend; it cannot be relied on
 * here because this spec runs against an already-built deployment.
 */
export interface AlertConditionRow {
  /** `LoadableSelectBox` trigger: shows "Select score" until one is picked, then the score name. */
  scoreSelect: Locator;
  /** The `>` / `<` toggle buttons, by operator. */
  operator: (op: ConditionOperator) => Locator;
  thresholdInput: Locator;
  /** `SelectBox` trigger; renders "In the last <label>" once a window is set. */
  windowSelect: Locator;
  removeButton: Locator;
}

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

  /**
   * "Test connection", on the Endpoint URL row.
   *
   * By role rather than a testid: the button renders its own literal copy and
   * nothing else on the form carries that accessible name. The per-trigger
   * "Test trigger" buttons are separate controls on the trigger blocks and go
   * through the same `useWebhookTest` hook, so they are NOT this locator.
   */
  get testConnectionButton(): Locator {
    return this.page.getByRole('button', { name: 'Test connection' });
  }

  /**
   * Clicks "Test connection". Assertions on what came back — the toast, the
   * mutation's response — belong to the caller, as they do for the AI
   * provider dialog's own test button.
   */
  async clickTestConnection(): Promise<void> {
    return test.step('click Test connection', async () => {
      await this.testConnectionButton.click();
    });
  }

  /**
   * A toast carrying `text`, scoped to the notifications region.
   *
   * Scoped rather than a bare `getByText`, for the reason
   * `PlaygroundPage.completionToast` documents: Radix also renders a
   * visually-hidden `role="status"` announcer carrying the same copy, so an
   * unscoped lookup matches twice and trips strict mode instead of asserting
   * anything. Radix auto-dismisses on its own 5s default, so assert on a toast
   * as soon as the action that raises it has settled.
   */
  toast(text: string | RegExp): Locator {
    return this.page
      .getByRole('region', { name: 'Notifications (F8)' })
      .getByRole('status')
      .filter({ hasText: text });
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
   * Scoped by testid rather than by the trigger's visible title: the titles are
   * not unique enough to select on — the "Add trigger" popover lists every
   * event type under the same copy, so a text lookup made while it is open
   * resolves there instead of on the block holding the config controls.
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

  /**
   * The validation message under the Name field.
   *
   * Zod's message rather than a testid: `FormMessage` renders no stable hook
   * of its own, and the copy is what a user actually reads.
   */
  get nameError(): Locator {
    return this.page.getByText('Alert name is required');
  }

  /**
   * Empties the name field.
   *
   * Distinct from `fillName('')` only in intent: the form treats an empty name
   * as untouched and resumes suggesting one from the triggers, so this is the
   * step that hands naming back rather than a way to blank the field.
   */
  async clearName(): Promise<void> {
    return test.step('clear the alert name', async () => {
      await this.nameInput.fill('');
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
   * the checkboxes carry no distinguishing name of their own. `check` rather
   * than a click because the picker toggles: a trigger already added stays added.
   */
  async addTrigger(triggerTitle: string): Promise<void> {
    return test.step(`add the "${triggerTitle}" trigger`, async () => {
      await this.inTriggerPicker(triggerTitle, (checkbox) => checkbox.check());
    });
  }

  /**
   * Unticks an event type in the same popover. `uncheck`, so a trigger that was
   * never added is left alone rather than added.
   */
  async removeTrigger(triggerTitle: string): Promise<void> {
    return test.step(`remove the "${triggerTitle}" trigger`, async () => {
      await this.inTriggerPicker(triggerTitle, (checkbox) => checkbox.uncheck());
    });
  }

  private async inTriggerPicker(
    triggerTitle: string,
    act: (checkbox: Locator) => Promise<void>,
  ): Promise<void> {
    await this.page.getByRole('button', { name: 'Add trigger' }).click();
    const popover = this.page.locator('[data-radix-popper-content-wrapper]');
    await popover.waitFor({ state: 'visible' });
    await act(popover.locator('label').filter({ hasText: triggerTitle }).getByRole('checkbox'));
    await this.page.keyboard.press('Escape');
    await popover.waitFor({ state: 'detached' });
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
   * The condition rows of a feedback-score trigger, addressed by group and
   * condition index.
   *
   * Only `trace:feedback_score` and `trace_thread:feedback_score` render these;
   * every other trigger has no groups at all.
   */
  conditionRow(
    eventType: AlertEventType,
    groupIndex: number,
    conditionIndex: number,
  ): AlertConditionRow {
    // Suffix-matched rather than exact: the full path is prefixed with the
    // trigger's index in the form's `triggers` array, which depends on the
    // order a test added its triggers. Scoped to this trigger's own block the
    // suffix is unambiguous, and it cannot collide across indices either —
    // `.conditions.10.threshold` does not end with `.conditions.1.threshold`.
    const thresholdInput = this.triggerConfig(eventType).locator(
      `input[name$=".groups.${groupIndex}.conditions.${conditionIndex}.threshold"]`,
    );
    // input -> its FormItem -> the row's shared field container.
    const fields = thresholdInput.locator('../..');
    return {
      // The only `aria-haspopup="dialog"` control in the row: the window select
      // beside it is a Radix Select (`role="combobox"`), so this stays correct
      // once a score is picked and the button's text stops being "Select score".
      scoreSelect: fields.locator('button[aria-haspopup="dialog"]'),
      operator: (op) => fields.getByRole('radio', { name: OPERATOR_LABEL[op], exact: true }),
      thresholdInput,
      windowSelect: fields.getByRole('combobox'),
      removeButton: fields.locator('..').getByRole('button', { name: 'Remove condition' }),
    };
  }

  /**
   * The "Remove group" buttons, one per OR group — so their count is the number
   * of groups.
   *
   * The builder always renders the group header for an alert (`singleGroup` is
   * off), disabling the button rather than hiding it while only one group
   * exists, so a single-group alert counts as 1 and not 0.
   */
  groupRemoveButtons(eventType: AlertEventType): Locator {
    return this.triggerConfig(eventType).getByRole('button', { name: 'Remove group' });
  }

  /** The "Group 1", "Group 2"… labels, in render order. */
  groupLabels(eventType: AlertEventType): Locator {
    return this.triggerConfig(eventType).getByText(/^Group \d+$/);
  }

  /** Appends an OR group, whose one condition starts blank. */
  async addOrGroup(eventType: AlertEventType): Promise<void> {
    return test.step(`add an OR group to the ${eventType} trigger`, async () => {
      const before = await this.groupRemoveButtons(eventType).count();
      await this.triggerConfig(eventType)
        .getByRole('button', { name: 'Add OR group' })
        .click();
      await expect(this.groupRemoveButtons(eventType)).toHaveCount(before + 1);
    });
  }

  /**
   * Appends an AND condition to one group.
   *
   * By index among the per-group "Add AND condition" buttons — one per group,
   * in group order. Positional, but the position IS the identity here: a group
   * has no id, its index is what the form model and the persisted
   * `group_index` both mean by it.
   */
  async addAndCondition(eventType: AlertEventType, groupIndex: number): Promise<void> {
    return test.step(`add an AND condition to group ${groupIndex + 1} of ${eventType}`, async () => {
      await this.triggerConfig(eventType)
        .getByRole('button', { name: 'Add AND condition' })
        .nth(groupIndex)
        .click();
    });
  }

  /**
   * Picks a feedback score name in a condition row, through the picker's own
   * search box.
   *
   * Searched rather than scrolled: the picker merges the workspace's feedback
   * DEFINITIONS with the project's observed score names, so on a shared
   * workspace the list is as long as the workspace is old.
   */
  async selectConditionScore(
    eventType: AlertEventType,
    groupIndex: number,
    conditionIndex: number,
    scoreName: string,
  ): Promise<void> {
    return test.step(`pick the "${scoreName}" score in group ${groupIndex + 1} condition ${conditionIndex + 1}`, async () => {
      const row = this.conditionRow(eventType, groupIndex, conditionIndex);
      // Retried, because the popover moves under the pointer mid-click. On a
      // row far enough down the form, Radix re-anchors the open popover by one
      // option height the moment it takes the mousedown: the element passes
      // every actionability check, `pointerdown` and `mousedown` land on the
      // option, and then `mouseup` arrives where the option no longer is — so
      // no `click` is synthesised and the picker simply stays open, none the
      // wiser. The second attempt lands, because by then the popover has
      // settled at its new position. A fixed wait would not help: the shift is
      // caused BY the mousedown, so there is nothing to wait for beforehand.
      //
      // The retry drives the whole open-search-pick cycle rather than just the
      // click, so an attempt that closed the picker without taking cannot leave
      // the next one clicking into nothing. It is not papering over a missing
      // wait — the loop exits on the value being set, which is the thing the
      // caller asked for.
      await expect(async () => {
        if ((await row.scoreSelect.getAttribute('data-state')) !== 'open') {
          await this.openConditionScorePicker(row, scoreName);
        }
        const listbox = this.page.getByRole('listbox');
        await listbox.getByRole('option', { name: scoreName, exact: true }).click();
        await expect(row.scoreSelect).toHaveText(scoreName, { timeout: 2_000 });
      }).toPass({ timeout: 30_000, intervals: [250, 500, 1_000] });
    });
  }

  /**
   * Opens a condition row's score picker and returns its listbox, optionally
   * narrowed by a search term.
   *
   * Separate from `selectConditionScore` because asserting what the picker
   * OFFERS is its own subject: the trace and thread triggers render the same
   * component against different score sources, and "this trigger lists the
   * other entity's scores" is a defect no round trip would reveal.
   */
  async openConditionScorePicker(row: AlertConditionRow, search?: string): Promise<Locator> {
    return test.step(`open a condition's score picker${search ? ` and search "${search}"` : ''}`, async () => {
      await row.scoreSelect.click();
      const listbox = this.page.getByRole('listbox');
      await listbox.waitFor({ state: 'visible' });
      if (search !== undefined) {
        // Scoped to the open popover: the search box belongs to the picker, and
        // an unscoped lookup would find any other "Search" field the page
        // happens to render behind it.
        await this.page
          .locator('[data-radix-popper-content-wrapper]')
          .getByPlaceholder('Search')
          .fill(search);
      }
      return listbox;
    });
  }

  /** Closes an open score picker without choosing anything. */
  async closeConditionScorePicker(): Promise<void> {
    return test.step('close the score picker', async () => {
      await this.page.keyboard.press('Escape');
      await this.page.getByRole('listbox').waitFor({ state: 'hidden' });
    });
  }

  /**
   * Fills one condition row end to end.
   *
   * Each control is asserted to have taken the value before the next is
   * touched: the builder writes through react-hook-form, and a click that
   * landed on a re-rendering row silently does nothing — which would otherwise
   * surface much later as a condition the save dropped.
   */
  async fillCondition(
    eventType: AlertEventType,
    groupIndex: number,
    conditionIndex: number,
    values: {
      scoreName: string;
      operator: ConditionOperator;
      threshold: string;
      window: AlertWindow;
    },
  ): Promise<void> {
    return test.step(
      `fill group ${groupIndex + 1} condition ${conditionIndex + 1}: ` +
        `${values.scoreName} ${values.operator} ${values.threshold} in the last ${values.window}`,
      async () => {
        const row = this.conditionRow(eventType, groupIndex, conditionIndex);
        await expect(
          row.thresholdInput,
          `group ${groupIndex} condition ${conditionIndex} exists exactly once`,
        ).toHaveCount(1);

        await this.selectConditionScore(eventType, groupIndex, conditionIndex, values.scoreName);

        await row.operator(values.operator).click();
        await expect(row.operator(values.operator)).toBeChecked();

        await row.thresholdInput.fill(values.threshold);
        await expect(row.thresholdInput).toHaveValue(values.threshold);

        await this.selectConditionWindow(row, values.window);
      },
    );
  }

  /** Picks a rolling window in a condition row's `SelectBox`. */
  async selectConditionWindow(row: AlertConditionRow, window: AlertWindow): Promise<void> {
    return test.step(`set the condition window to ${window}`, async () => {
      await row.windowSelect.click();
      await this.page.getByRole('option', { name: window, exact: true }).click();
      await expect(row.windowSelect).toHaveText(this.windowTriggerText(window));
    });
  }

  /**
   * What a condition's window select reads once set.
   *
   * The trigger is rendered by `renderTrigger`, which prefixes the label with a
   * muted "In the last" — so the label alone never matches the element's text.
   */
  windowTriggerText(window: AlertWindow): RegExp {
    return new RegExp(`^In the last\\s*${window.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}$`);
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
