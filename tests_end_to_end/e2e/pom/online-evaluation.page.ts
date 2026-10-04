import { test, type Page, type Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { assertAllowedModelDisplayName } from '@e2e/core/llm-model-policy';

export interface CreateRuleDialogLLMJudgeFields {
  name: string;
  /** Canned-template label as shown in the dialog. */
  template: 'Moderation' | 'Hallucination' | 'AnswerRelevance' | 'Custom LLM-as-judge';
  /** Model display name as shown in the model picker (e.g. "Claude Haiku 4.5"). */
  modelDisplayName: string;
}

export interface CreateRuleDialogPythonEqualsFields {
  name: string;
  /** The literal string the trace's output must equal to score 1.0. */
  referenceValue: string;
  /**
   * Sampling rate as the PERCENTAGE shown in the dialog (0-100), not the
   * fraction the API stores. Omit to leave the control at its 100% default.
   */
  samplingRatePercent?: number;
}

/**
 * Build the deterministic Python-Equals metric snippet. The score name is
 * interpolated into the source so the metric's internal
 * `ScoreResult(name=...)` matches the rule's UI-form name (the engine ignores
 * the rule name and uses the metric's score-result name verbatim — confirmed
 * during Phase 2 staging verification).
 *
 * Do NOT import additional BaseMetric subclasses here (e.g. opik's Equals
 * heuristic): the python_evaluator backend's get_metric_class iterates module
 * classes alphabetically and picks the first BaseMetric subclass — an import
 * would shadow the user's class.
 */
function buildPythonEqualsMetric(scoreName: string, reference: string): string {
  return `from typing import Any
from opik.evaluation.metrics import base_metric, score_result

REFERENCE = ${JSON.stringify(reference)}
SCORE_NAME = ${JSON.stringify(scoreName)}

class EqualsRule(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: str, **ignored_kwargs: Any) -> score_result.ScoreResult:
        value = 1.0 if str(output) == REFERENCE else 0.0
        return score_result.ScoreResult(value=value, name=self.name)`;
}

export class OnlineEvaluationPage {
  private projectId: string | null = null;

  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    this.projectId = projectId;
    const env = loadEnvConfig();
    await this.page.goto(
      `${env.baseUrl}/${env.workspace}/projects/${projectId}/online-evaluation`,
    );
  }

  /**
   * Wait for either the empty-state CTA OR a real rule row to be visible —
   * whichever arrives first. (The page loads in either state depending on
   * whether the project has any rules.)
   */
  async waitForReady(): Promise<void> {
    const realRow = this.ruleRows.first();
    const emptyState = this.page.getByText('No online evaluations yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  /**
   * Every rule row the list is currently rendering.
   *
   * `data-row-id` is the shared DataTable's per-entity stamp, so this counts
   * real rows and never the header or an empty-state placeholder.
   */
  get ruleRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  /** Locator for a rule row by name. Uses `data-row-id` row scope + cell-name filter. */
  ruleRow(name: string): Locator {
    return this.ruleRows.filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  /**
   * Open the create-rule dialog. Works against both the empty-state CTA
   * ("Create your first rule") AND the toolbar button ("Create rule") that
   * appears once at least one rule exists.
   */
  async openCreateRuleDialog(): Promise<void> {
    const toolbarButton = this.page.getByTestId('online-evaluation-create-rule-button');
    const emptyStateButton = this.page.getByRole('button', {
      name: 'Create your first rule',
    });
    await toolbarButton.or(emptyStateButton).first().click();
    await this.dialog.waitFor({ state: 'visible' });
  }

  /** Dialog root, scoped by testid. */
  get dialog(): Locator {
    return this.page.getByTestId('add-edit-rule-dialog');
  }

  /**
   * Delete a rule through the row's kebab menu, confirming the destructive
   * dialog. Resolves once the row is gone from the list.
   *
   * The kebab trigger, the menu items and the ConfirmDialog carry no
   * data-testids (ConfirmDialog is a generic shared component); we scope by the
   * row first, then use the accessible names, which are stable strings in
   * RuleRowActionsCell / ConfirmDialog.
   */
  async deleteRuleByName(name: string): Promise<void> {
    return test.step(`delete rule "${name}" via row actions`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Delete' }).click();

      const confirm = this.deleteRuleConfirmDialog;
      await confirm.waitFor({ state: 'visible' });
      await confirm.getByRole('button', { name: 'Delete evaluation rule' }).click();

      await confirm.waitFor({ state: 'hidden' });
      await row.waitFor({ state: 'detached' });
    });
  }

  /**
   * The read-only Status cell for a rule row ("Enabled" / "Disabled"), rendered
   * by RuleEnabledCell. There is no row-level toggle — the only control that
   * changes `enabled` is the switch inside the edit dialog (see
   * `setRuleEnabledByName`).
   */
  ruleStatusCell(name: string, status: 'Enabled' | 'Disabled'): Locator {
    return this.ruleRow(name).getByRole('cell', { name: status, exact: true });
  }

  /**
   * The Sampling rate cell for a rule row, rendered by OnlineEvaluationPage's
   * `sampling_rate` column as a formatted percentage ("50%", "100%") — note the
   * list shows a PERCENTAGE while the API stores a fraction.
   */
  ruleSamplingRateCell(name: string, displayValue: string): Locator {
    return this.ruleRow(name).getByRole('cell', { name: displayValue, exact: true });
  }

  /**
   * The "Filtering & Sampling" accordion inside the add/edit dialog. It renders
   * COLLAPSED by default, and its content is unmounted while collapsed, so the
   * sampling-rate control does not exist until this is expanded.
   */
  get filteringSamplingTrigger(): Locator {
    return this.dialog.getByTestId('add-edit-rule-dialog-filtering-sampling-trigger');
  }

  /**
   * The sampling-rate number input (the percentage box next to the slider).
   * SliderInputControl derives this testid from its `id` prop.
   */
  get samplingRateInput(): Locator {
    return this.dialog.getByTestId('sampling_rate-input');
  }

  /**
   * Expand the Filtering & Sampling accordion, if it is not already open.
   * Idempotent: switching the rule TYPE re-renders the dialog body but leaves
   * the accordion open, so callers can invoke this without tracking state.
   */
  async expandFilteringAndSampling(): Promise<void> {
    return test.step('expand the Filtering & Sampling accordion', async () => {
      const trigger = this.filteringSamplingTrigger;
      await trigger.waitFor({ state: 'visible' });
      if ((await trigger.getAttribute('aria-expanded')) !== 'true') {
        await trigger.click();
      }
      await expect(trigger).toHaveAttribute('aria-expanded', 'true');
      await this.samplingRateInput.waitFor({ state: 'visible' });
    });
  }

  /**
   * Set the sampling rate to a PERCENTAGE (0-100), as the dialog displays it.
   *
   * SliderInputControl writes the form value in `onBlur`
   * (`validateAndHandleChange`), not in `onChange`, so the blur is made
   * explicit here rather than left to whatever the next interaction happens to
   * be. A real user's click on Create blurs the field first, so this mirrors
   * the genuine gesture — it is not working around a product bug.
   *
   * Do not "verify" the value by reading the slider's `aria-valuenow`: the
   * slider mirrors the component's local state, so it reports the typed number
   * before the form value has been written. The trustworthy check is the
   * persisted rate on the created rule — asserted in the test.
   */
  async setSamplingRatePercent(percent: number): Promise<void> {
    return test.step(`set sampling rate to ${percent}%`, async () => {
      await this.expandFilteringAndSampling();
      const input = this.samplingRateInput;
      await input.fill(String(percent));
      // Commit to the form explicitly, rather than relying on a later click.
      await input.blur();
      await expect(input).toHaveValue(String(percent));
    });
  }

  // --- Rule filters (the Filtering & Sampling accordion's filter table) ---

  /**
   * One row of the rule's filter table, by position.
   *
   * Identified by the presence of a column selector rather than by `nth` over
   * every `tr` in the dialog: `FilterRow` renders a SECOND `tr` beneath a row
   * that has a validation error, so a positional index over raw rows silently
   * shifts the moment a filter is invalid — which is exactly when a spec is
   * most likely to be looking at one.
   */
  filterRow(index: number): Locator {
    return this.dialog
      .locator('tr')
      .filter({ has: this.page.locator('[data-testid="filter-column"]') })
      .nth(index);
  }

  /** How many filter rows the dialog is currently showing. */
  get filterRows(): Locator {
    return this.dialog
      .locator('tr')
      .filter({ has: this.page.locator('[data-testid="filter-column"]') });
  }

  /** Append an empty filter row. */
  async addFilterRow(): Promise<void> {
    return test.step('add a filter row', async () => {
      await this.expandFilteringAndSampling();
      const before = await this.filterRows.count();
      await this.dialog.getByRole('button', { name: 'Add filter' }).click();
      await expect(this.filterRows, 'filter rows after Add filter').toHaveCount(before + 1);
    });
  }

  /**
   * Choose a filter row's column by the label the dialog shows — "Duration (s)",
   * "Name", … — which is the user-facing name and the one that carries the
   * UNIT. That matters here: the column is labelled in seconds while the
   * backend stores milliseconds, and the label is the only place the dialog
   * promises which of the two a typed number means.
   *
   * Selecting a column resets the row's operator and value (`createFilter()`),
   * so always set the column first.
   */
  async setFilterColumn(index: number, label: string): Promise<void> {
    return test.step(`set filter ${index + 1}'s column to "${label}"`, async () => {
      await this.filterRow(index)
        .locator('button[role="combobox"]:has([data-testid="filter-column"])')
        .click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
    });
  }

  /** Choose a filter row's operator by its label (">", "contains", …). */
  async setFilterOperator(index: number, label: string): Promise<void> {
    return test.step(`set filter ${index + 1}'s operator to "${label}"`, async () => {
      await this.filterRow(index)
        .locator('button[role="combobox"]:has([data-testid="filter-operator"])')
        .click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
    });
  }

  /**
   * Type a filter row's value.
   *
   * `DebounceInput` commits on a timer, so the blur is explicit rather than
   * left to whatever the next interaction happens to be — the same reasoning as
   * `setSamplingRatePercent`. A real user's click on Create blurs the field
   * first, so this is the genuine gesture, not a workaround.
   */
  async setFilterValue(index: number, value: string): Promise<void> {
    return test.step(`set filter ${index + 1}'s value to "${value}"`, async () => {
      const input = this.filterValueInput(index);
      await input.fill(value);
      await input.blur();
      await expect(input, `filter ${index + 1}'s value box`).toHaveValue(value);
    });
  }

  /**
   * The value the dialog is SHOWING for a filter row.
   *
   * The assertion target for hydration: a rule stored at 5000ms must come back
   * on screen as 5, because the column is labelled "Duration (s)". Reading the
   * input's value rather than any internal state is the point — what the user
   * sees is the whole claim.
   */
  async readFilterValue(index: number): Promise<string> {
    return test.step(`read filter ${index + 1}'s displayed value`, async () => {
      const input = this.filterValueInput(index);
      await expect(input, `filter ${index + 1}'s value box`).toBeVisible();
      return (await input.inputValue()).trim();
    });
  }

  /**
   * A filter row's value box, whichever type the row is.
   *
   * `NumberRow` and `StringRow` stamp different test ids on the same slot;
   * matching either keeps the caller from having to know the column's type to
   * read what is in it, and the `toHaveCount(1)` guards against a row that
   * somehow rendered both.
   */
  private filterValueInput(index: number): Locator {
    return this.filterRow(index).locator(
      '[data-testid="filter-number-input"], [data-testid="filter-string-input"]',
    );
  }

  // --- LLM judge: scope and model picker ---

  /**
   * Choose the rule's Scope (Trace / Thread / Span).
   *
   * Disabled in edit mode by design, so this is only callable while creating.
   * Addressed through the "Scope" label because the trigger carries no test id
   * and its accessible name is whatever is currently selected.
   */
  async setScope(label: 'Trace' | 'Thread' | 'Span'): Promise<void> {
    return test.step(`set the rule scope to ${label}`, async () => {
      const trigger = this.scopeControl;
      await expect(trigger, 'exactly one Scope control').toHaveCount(1);
      await trigger.click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
      await expect(trigger, 'the Scope control').toHaveText(label);
    });
  }

  /**
   * The Scope select's trigger.
   *
   * Identified by the VALUE it displays rather than by its label: the label is
   * a bare `<Label>` with no `htmlFor`, so there is nothing tying it to the
   * control, and walking up to the shared FormItem wrapper matches a stack of
   * anonymous divs whose innermost is not reliably the right one — an earlier
   * attempt at that resolved to the model picker, which is disabled until a
   * type is chosen. Scope is the only combobox in this dialog whose text is one
   * of the three scope names, which makes the value the stable handle.
   */
  private get scopeControl(): Locator {
    return this.dialog
      .locator('button[role="combobox"]')
      .filter({ hasText: /^(Trace|Thread|Span)$/ });
  }

  /** What the Scope control currently reads. */
  async readScope(): Promise<string> {
    return ((await this.scopeControl.textContent()) ?? '').trim();
  }

  /** The LLM-model picker trigger in the judge form. */
  get modelPicker(): Locator {
    return this.dialog.locator('button[role="combobox"]:has([data-testid="select-a-llm-model"])');
  }

  /**
   * Open the model picker and leave it open.
   *
   * Retried as a unit: the option list remounts when the model and
   * provider-key queries resolve, so a click that lands during that window
   * opens nothing. Same reasoning as `fillAndSubmitCreateRuleDialogLLMJudge`.
   */
  async openModelPicker(): Promise<Locator> {
    return test.step('open the LLM model picker', async () => {
      const listbox = this.page.getByRole('listbox');
      await expect(async () => {
        await this.modelPicker.click();
        await expect(listbox).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });
      return listbox;
    });
  }

  /**
   * Type into the OPEN picker's search box and return the options it matched.
   *
   * Returns the locator rather than a count so the caller can assert on
   * emptiness and on membership with the same handle — "no results" is a
   * meaningful answer here, not a missing selector, which is why this does not
   * wait for an option to appear.
   */
  async searchModels(term: string): Promise<Locator> {
    return test.step(`search the model picker for "${term}"`, async () => {
      const listbox = this.page.getByRole('listbox');
      await listbox.getByPlaceholder('Search model').fill(term);
      return listbox.getByRole('option');
    });
  }

  /** Pick a model by its exact label from the OPEN picker. */
  async chooseModel(label: string): Promise<void> {
    return test.step(`choose the model "${label}"`, async () => {
      const listbox = this.page.getByRole('listbox');
      await expect(async () => {
        await listbox.getByPlaceholder('Search model').fill(label);
        const option = listbox.getByRole('option', { name: label, exact: true });
        await expect(option.first()).toBeVisible({ timeout: 2_000 });
        await option.first().click({ timeout: 2_000 });
        await expect(this.modelPicker).toContainText(label, { timeout: 2_000 });
      }).toPass({ timeout: 30_000 });
    });
  }

  /** What the model picker currently reads. */
  async readModelPickerText(): Promise<string> {
    return ((await this.modelPicker.textContent()) ?? '').trim();
  }

  /**
   * The model-parameters gear.
   *
   * Addressed by its tooltip-backed accessible name; `PromptModelConfigs`
   * renders an icon-only `DropdownMenuTrigger` whose only label is the
   * "Model parameters" tooltip. Absent entirely for a decisions model, which
   * is what a caller here is usually checking.
   */
  get modelSettingsButton(): Locator {
    return this.dialog.getByRole('button', { name: 'Model parameters' });
  }

  /** The "Max cost per evaluation (USD)" field's label — absent for a decisions model. */
  get maxCostLabel(): Locator {
    return this.dialog.getByText('Max cost per evaluation (USD)', { exact: true });
  }

  /** The "Enable rule" switch inside the add/edit dialog. */
  get enableRuleSwitch(): Locator {
    return this.dialog.getByRole('switch', { name: 'Enable rule' });
  }

  /**
   * The Trigger scope toggle group inside the add/edit dialog — Production
   * traces / Experiment traces / Both.
   *
   * Only rendered for trace-scope rules; the dialog omits it entirely for span
   * and thread scope. That makes it the control a spec uses to show that a
   * dialog which is missing the filtering section is still the full trace-scope
   * dialog, rather than one that failed to render.
   */
  get triggerScopeControl(): Locator {
    return this.dialog.getByTestId('add-edit-rule-dialog-trigger-scope');
  }

  /**
   * One option of the Trigger scope toggle group, by its accessible name.
   *
   * Radix `ToggleGroupItem` renders a radio whose selected member carries
   * `data-state="on"`, so a caller asserting which scope the dialog hydrated
   * should check that attribute rather than the group's text — every option's
   * label is on screen whichever one is active.
   */
  triggerScopeOption(name: 'Production traces' | 'Experiment traces' | 'Both'): Locator {
    return this.triggerScopeControl.getByRole('radio', { name, exact: true });
  }

  /**
   * Open a rule's edit dialog through the row's kebab → Edit, and resolve once
   * the dialog is on screen. The same trigger/menu-item pair as
   * `deleteRuleByName`, scoped by the row first and then by accessible name
   * (RuleRowActionsCell gives neither a data-testid).
   */
  async openEditRuleDialogByName(name: string): Promise<void> {
    return test.step(`open the edit dialog for rule "${name}"`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      await this.dialog.waitFor({ state: 'visible' });
    });
  }

  /**
   * Submit the add/edit dialog and wait for it to close.
   *
   * Deliberately touches nothing else: a caller that opened the dialog and
   * changed nothing is exercising the "save an unedited rule" path, where the
   * only thing under test is what the form serializes out of the values it
   * hydrated in.
   */
  async submitRuleDialog(): Promise<void> {
    return test.step('submit the rule dialog', async () => {
      await this.dialog.getByTestId('add-edit-rule-dialog-submit').click();
      await this.dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * Flip a rule's enabled state through the row's kebab → Edit → "Enable rule"
   * switch → submit. Resolves once the dialog has closed and the row's Status
   * cell reflects the new state.
   *
   * The switch is asserted into the expected starting state before clicking, so
   * a UI regression that hydrates the dialog from the wrong value fails here
   * rather than silently toggling the rule the wrong way.
   */
  async setRuleEnabledByName(name: string, enabled: boolean): Promise<void> {
    return test.step(`set rule "${name}" enabled=${enabled} via edit dialog`, async () => {
      await this.openEditRuleDialogByName(name);
      const toggle = this.enableRuleSwitch;
      await expect(toggle, 'edit dialog hydrates the switch from the persisted value').toBeChecked({
        checked: !enabled,
      });
      await toggle.click();
      await expect(toggle).toBeChecked({ checked: enabled });

      await this.submitRuleDialog();

      await expect(this.ruleStatusCell(name, enabled ? 'Enabled' : 'Disabled')).toBeVisible();
    });
  }

  /**
   * Open a rule's edit dialog through the row's kebab → Edit, and leave it open.
   *
   * `setRuleEnabledByName` inlines the same gesture because it owns the whole
   * toggle-and-submit flow; this exists for the specs that only want to *read*
   * what the dialog hydrated, without changing anything.
   */
  async openEditDialogByName(name: string): Promise<void> {
    return test.step(`open the edit dialog for rule "${name}"`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      await this.dialog.waitFor({ state: 'visible' });
    });
  }

  /**
   * Submit the add/edit dialog and wait for it to close.
   *
   * Used on its own by the specs that save a rule without changing anything;
   * the `fillAndSubmit…` helpers above do their own submit because they own the
   * whole create gesture.
   */
  async submitDialog(): Promise<void> {
    return test.step('submit the rule dialog', async () => {
      await this.dialog.getByTestId('add-edit-rule-dialog-submit').click();
      await this.dialog.waitFor({ state: 'hidden' });
    });
  }

  /** Close the add/edit dialog via its Cancel button, discarding anything typed. */
  async cancelDialog(): Promise<void> {
    return test.step('close the rule dialog without saving', async () => {
      await this.dialog.getByRole('button', { name: 'Cancel', exact: true }).click();
      await this.dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * The exact text the dialog hydrated into the judge prompt message of the
   * given role.
   *
   * Read from the rendered CodeMirror lines rather than an input value, because
   * that is the only place the text exists — and reading it is the assertion:
   * a prompt the read-back mapper truncated arrives here truncated.
   *
   * `.cm-line` divs are joined with `\n` because that is CodeMirror's document
   * model — one line element per document line, with soft wrapping handled
   * inside a line rather than by splitting it. This is sound for the short
   * judge prompts these specs seed; a document long enough for CodeMirror to
   * virtualise its viewport would render only the visible lines, so do not
   * reach for this to read a multi-screen prompt.
   */
  async readPromptMessageText(role: 'system' | 'user'): Promise<string> {
    return test.step(`read the ${role} judge message out of the dialog`, async () => {
      // `data-role` sits on the same element as the testid, not inside it, so
      // this is an `and()` of two attributes rather than a `filter({ has })`.
      const row = this.dialog
        .getByTestId('playground-message-row')
        .and(this.page.locator(`[data-role="${role}"]`));
      await expect(row, `the dialog must hydrate exactly one ${role} message`).toHaveCount(1);
      const content = row.getByTestId('playground-message-editor').locator('.cm-content');
      await content.waitFor({ state: 'visible' });
      return content.evaluate((el) =>
        Array.from(el.querySelectorAll('.cm-line'))
          .map((line) => line.textContent ?? '')
          .join('\n'),
      );
    });
  }

  /** The destructive confirm dialog raised by the row's Delete action. */
  get deleteRuleConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete evaluation rule' }),
    });
  }

  /**
   * Fill + submit the dialog for an LLM-as-judge rule using a canned template
   * (the canned templates ship their own prompt + variable mapping + score
   * definition; we only set Name, Model, and Template).
   *
   * For the `Moderation` template (and any other template that has a single
   * `{{output}}` variable), we change the variable-mapping for `output` from
   * the default `output` (which the engine serializes as the whole JSON node
   * `{"output": "<value>"}`) to `output.output` so the judge LLM sees the bare
   * string. Without this, the judge scores the JSON wrapper, not the content.
   */
  async fillAndSubmitCreateRuleDialogLLMJudge(
    fields: CreateRuleDialogLLMJudgeFields,
  ): Promise<void> {
    const d = this.dialog;
    await d.getByRole('textbox', { name: 'Rule name' }).fill(fields.name);

    // Pick the template FIRST — selecting it rebuilds the prompt + variable
    // mapping section, so any prior tweaks would be wiped out.
    const promptCombobox = d.getByRole('combobox').filter({
      hasText: /^(Custom LLM-as-judge|Hallucination|Moderation|AnswerRelevance|Structured Output Compliance|Meaning Match)$/,
    });
    await promptCombobox.click();
    await this.page.getByRole('option', { name: fields.template, exact: true }).click();

    // Pick the model. Guarded: an unselected picker defaults to the
    // provider's newest/most expensive entry.
    assertAllowedModelDisplayName(fields.modelDisplayName);
    const modelCombobox = d.getByRole('combobox').filter({
      hasText: /Select an LLM model|claude|gpt|Claude|GPT/i,
    });
    const listbox = this.page.getByRole('listbox');
    await expect(async () => {
      await modelCombobox.click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });

    // The option list remounts when the model/provider-key queries resolve, so
    // an option can detach between resolving and being clicked. Re-filter and
    // re-click until the combobox reflects the selection.
    await expect(async () => {
      await listbox.getByPlaceholder('Search model').fill(fields.modelDisplayName);
      const option = listbox.getByRole('option', { name: fields.modelDisplayName });
      await expect(option.first()).toBeVisible({ timeout: 2_000 });
      await option.first().click({ timeout: 2_000 });
      await expect(modelCombobox).toContainText(fields.modelDisplayName, { timeout: 2_000 });
    }).toPass({ timeout: 30_000 });

    // Change the output variable-mapping from default `output` to `output.output`
    // so the engine extracts the bare string (per the JsonPath semantics in
    // OnlineScoringEngine.toVariableMapping — dot-containing paths get
    // `$.output`, bare paths get `$` which yields the whole JSON node).
    await this.setVariableMapping('output', 'output.output');

    await d.getByTestId('add-edit-rule-dialog-submit').click();
    await d.waitFor({ state: 'hidden' });
  }

  /**
   * Fill + submit the dialog for a Python-code rule using the deterministic
   * Equals snippet. Toggles the TYPE radio to "Code metric" first.
   */
  async fillAndSubmitCreateRuleDialogPythonEquals(
    fields: CreateRuleDialogPythonEqualsFields,
  ): Promise<void> {
    const d = this.dialog;
    await d.getByRole('textbox', { name: 'Rule name' }).fill(fields.name);
    await d.getByRole('radio', { name: 'Code metric' }).click();

    // Replace the default Python template in the CodeMirror editor.
    const editor = d.locator('.cm-content').first();
    await editor.click();
    await this.page.keyboard.press('ControlOrMeta+A');
    await this.page.keyboard.press('Delete');
    await this.page.keyboard.type(buildPythonEqualsMetric(fields.name, fields.referenceValue));

    // FE re-parses the score() signature; for our snippet it produces a
    // single `output` variable-mapping row. Wait for the variable-mapping
    // input to settle to the new shape, then override its path.
    await this.setVariableMapping('output', 'output.output');

    // Set the rate last: the sampling control lives in a collapsed accordion
    // below the code editor, and switching TYPE / re-parsing the snippet
    // re-renders the body above it.
    if (fields.samplingRatePercent !== undefined) {
      await this.setSamplingRatePercent(fields.samplingRatePercent);
    }

    await d.getByTestId('add-edit-rule-dialog-submit').click();
    await d.waitFor({ state: 'hidden' });
  }

  /**
   * Change a variable-mapping cmdk-input for the given parameter name (the
   * left-side label, e.g. `output`) to the given path (e.g. `output.output`).
   * The Variable mapping section renders one row per `score()` parameter; each
   * row has a label adjacent to a cmdk-input that holds the extraction path.
   *
   * The cmdk input is editable as text; we clear it via select-all + delete,
   * then type the new path. The cmdk popover opens on focus; pressing Escape
   * closes it without selecting an option so the typed text persists as the
   * field's value (Enter would try to commit a non-existent listbox option).
   */
  private async setVariableMapping(variableName: string, pathValue: string): Promise<void> {
    // Locate the cmdk-input by its surrounding row's label. Variable-mapping
    // rows look like:  <label>output</label> ... <input cmdk-input ... />
    // so we find the row by label text, then the cmdk-input inside it.
    const row = this.dialog
      .locator('div')
      .filter({ has: this.page.locator(`text=/^${variableName}$/`) })
      .filter({ has: this.page.locator('input[cmdk-input]') })
      .first();
    const input = row.locator('input[cmdk-input]');
    await input.waitFor({ state: 'visible' });
    await input.click();
    await this.page.keyboard.press('ControlOrMeta+A');
    await this.page.keyboard.press('Delete');
    await this.page.keyboard.type(pathValue);
    await this.page.keyboard.press('Escape');
  }
}
