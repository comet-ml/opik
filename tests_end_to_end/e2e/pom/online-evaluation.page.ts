import { test, type Page, type Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { assertAllowedModelDisplayName } from '@e2e/core/llm-model-policy';

export type EvaluatorType = 'LLM-as-a-Judge' | 'Decision model' | 'Code evaluator';

export type RuleScopeLabel = 'Threads' | 'Traces' | 'Spans';

export interface CreateRuleLLMJudgeFields {
  name: string;
  /** Template label as shown in the Templates menu. */
  template: 'Hallucination' | 'Moderation' | 'Answer relevance' | 'Tool correctness';
  /** Model display name as shown in the model picker (e.g. "Claude Haiku 4.5"). */
  modelDisplayName: string;
}

export interface CreateRulePythonEqualsFields {
  name: string;
  /** The literal string the trace's output must equal to score 1.0. */
  referenceValue: string;
  /** Sampling rate as the PERCENTAGE the panel shows (0-100), not the stored fraction. */
  samplingRatePercent?: number;
}

/**
 * A deterministic Equals metric on the standard `output` parameter.
 *
 * The engine hands `output` over as the trace's whole output serialized to
 * JSON, so the SDK's `{"output": "..."}` wrapper is unpacked before comparing.
 * The score name is interpolated because the engine stores the metric's
 * `ScoreResult` name verbatim, not the rule name.
 */
function buildPythonEqualsMetric(scoreName: string, reference: string): string {
  return `import json
from typing import Any
from opik.evaluation.metrics import base_metric, score_result

REFERENCE = ${JSON.stringify(reference)}
SCORE_NAME = ${JSON.stringify(scoreName)}

class EqualsRule(base_metric.BaseMetric):
    def __init__(self, name: str = SCORE_NAME):
        self.name = name

    def score(self, output: Any = None, **ignored_kwargs: Any) -> score_result.ScoreResult:
        value = json.loads(output).get("output") if isinstance(output, str) else output
        return score_result.ScoreResult(value=1.0 if value == REFERENCE else 0.0, name=self.name)`;
}

export class OnlineEvaluationPage {
  constructor(private readonly page: Page) {}

  async goto(projectId: string): Promise<void> {
    const env = loadEnvConfig();
    await this.page.goto(
      `${env.baseUrl}/${env.workspace}/projects/${projectId}/online-evaluation`,
    );
  }

  /** Resolves once either the empty state or a real rule row is on screen. */
  async waitForReady(): Promise<void> {
    await Promise.race([
      this.ruleRows.first().waitFor({ state: 'visible' }),
      this.page.getByText('No online evaluations yet').waitFor({ state: 'visible' }),
    ]);
  }

  /** Every rule row the list renders; `data-row-id` excludes the header and empty-state rows. */
  get ruleRows(): Locator {
    return this.page.locator('tbody tr[data-row-id]');
  }

  ruleRow(name: string): Locator {
    return this.ruleRows.filter({ has: this.page.getByRole('cell', { name, exact: true }) });
  }

  /** The read-only Status cell ("Enabled" / "Disabled"). */
  ruleStatusCell(name: string, status: 'Enabled' | 'Disabled'): Locator {
    return this.ruleRow(name).getByRole('cell', { name: status, exact: true });
  }

  /** The Sampling rate cell, shown as a percentage ("50%") while the API stores a fraction. */
  ruleSamplingRateCell(name: string, displayValue: string): Locator {
    return this.ruleRow(name).getByRole('cell', { name: displayValue, exact: true });
  }

  get deleteRuleConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete evaluation rule' }),
    });
  }

  async cloneRuleByName(name: string): Promise<void> {
    return test.step(`clone rule "${name}" via row actions`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Clone' }).click();
      await this.waitForPanelOpen();
      // The clone panel is pre-named after its source, and that name is how the
      // test tells the two rules apart afterwards — so a panel that opened on
      // the wrong rule (or in edit mode on this one) fails here rather than
      // creating a rule nothing expected.
      await expect(this.nameInput, 'the clone is pre-named after its source').toHaveValue(
        `${name} (Copy)`,
      );
    });
  }

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

  // --- Rule editor panel ---

  /**
   * The rule editor side panel. Its root stays mounted (translated off screen)
   * while closed, so open/closed is read from the form inside it instead.
   */
  get panel(): Locator {
    return this.page.getByTestId('online-evaluation-rule-editor');
  }

  get nameInput(): Locator {
    return this.panel.getByRole('textbox', { name: 'Name', exact: true });
  }

  /** "Create evaluator" when creating, "Save changes" when editing. */
  get submitButton(): Locator {
    return this.panel.getByRole('button', { name: /^(Create evaluator|Save changes)$/ });
  }

  get enabledSwitch(): Locator {
    return this.panel.getByRole('switch', { name: 'Enabled', exact: true });
  }

  async waitForPanelOpen(): Promise<void> {
    await this.nameInput.waitFor({ state: 'visible' });
  }

  async waitForPanelClosed(): Promise<void> {
    await this.nameInput.waitFor({ state: 'detached' });
  }

  async openCreateRulePanel(type: EvaluatorType = 'LLM-as-a-Judge'): Promise<void> {
    return test.step(`open the create panel for a ${type} evaluator`, async () => {
      await this.page.getByTestId('online-evaluation-create-rule-button').click();
      await this.page.getByRole('menuitem', { name: new RegExp(`^${type}`) }).click();
      // The Evaluator menu animates out over the panel; clicks land on it until it is gone.
      await expect(this.page.getByRole('menu')).toHaveCount(0);
      await this.waitForPanelOpen();
    });
  }

  /** Clicking a rule row opens it in edit mode. */
  async openEditRulePanelByName(name: string): Promise<void> {
    return test.step(`open rule "${name}" in the editor`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('cell', { name, exact: true }).click();
      await this.waitForPanelOpen();
      await expect(this.nameInput).toHaveValue(name);
    });
  }

  /** Waits for the panel to close, which the form only does once the save succeeded. */
  async submitPanel(): Promise<void> {
    return test.step('submit the rule editor', async () => {
      await this.submitButton.click();
      await this.waitForPanelClosed();
    });
  }

  /**
   * Submit a CREATE (or a clone) and return the new rule's id, taken from the
   * `Location` header of the POST the panel issues.
   *
   * The header and not a name lookup, for the reason `createLlmJudgeRule`
   * gives and one more that only bites here: the backend silently suffixes a
   * clashing rule name with `-1`, so looking the rule up by the name just typed
   * can return an OLDER rule and read as a save that lost every field.
   *
   * The subscription is armed before the click because the request is in flight
   * the moment the form submits.
   */
  async submitPanelAndCaptureRuleId(): Promise<string> {
    return test.step('submit the rule editor and capture the created id', async () => {
      const created = this.page.waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          new URL(response.url()).pathname.endsWith('/v1/private/automations/evaluators'),
      );
      await this.submitButton.click();
      const response = await created;
      expect(response.status(), `the rule was created (got ${response.status()})`).toBe(201);
      const location = response.headers()['location'] ?? '';
      const id = location.split('/').filter(Boolean).pop() ?? '';
      expect(id, `the 201 carried a usable Location header (got '${location}')`).not.toBe('');
      await this.waitForPanelClosed();
      return id;
    });
  }

  async cancelPanel(): Promise<void> {
    return test.step('close the rule editor without saving', async () => {
      await this.panel.getByRole('button', { name: 'Cancel', exact: true }).click();
      await this.waitForPanelClosed();
    });
  }

  async toggleEnabled(): Promise<void> {
    return test.step('toggle the Enabled switch', async () => {
      const wasChecked = await this.enabledSwitch.isChecked();
      await this.enabledSwitch.click();
      await expect(this.enabledSwitch).toBeChecked({ checked: !wasChecked });
    });
  }

  /**
   * Flip a rule's enabled state through the editor. The switch is asserted into
   * the opposite starting state first, so a wrong hydration fails here instead
   * of toggling the rule the wrong way.
   */
  async setRuleEnabledByName(name: string, enabled: boolean): Promise<void> {
    return test.step(`set rule "${name}" enabled=${enabled} via the editor`, async () => {
      await this.openEditRulePanelByName(name);
      await expect(
        this.enabledSwitch,
        'the editor hydrates the switch from the persisted value',
      ).toBeChecked({ checked: !enabled });
      await this.toggleEnabled();
      await this.submitPanel();
      await expect(this.ruleStatusCell(name, enabled ? 'Enabled' : 'Disabled')).toBeVisible();
    });
  }

  // --- Scope ---

  scopeOption(label: RuleScopeLabel): Locator {
    return this.panel.getByRole('radio', { name: label, exact: true });
  }

  /** Scope is read-only in edit mode. */
  async setScope(label: RuleScopeLabel): Promise<void> {
    return test.step(`set the rule scope to ${label}`, async () => {
      await this.scopeOption(label).click();
      await expect(this.scopeOption(label)).toHaveAttribute('data-state', 'on');
    });
  }

  /** Only rendered at trace scope. */
  get scoredTracesSelect(): Locator {
    return this.panel.getByRole('combobox', { name: 'Score', exact: true });
  }

  // --- Filtering and sampling ---

  /** Collapsed by default, and not rendered at all for an experiment-only rule. */
  get filteringSamplingTrigger(): Locator {
    return this.panel.getByRole('button', { name: 'Filtering and sampling' });
  }

  get samplingRateInput(): Locator {
    return this.panel.getByTestId('sampling_rate-input');
  }

  async expandFilteringAndSampling(): Promise<void> {
    return test.step('expand Filtering and sampling', async () => {
      const trigger = this.filteringSamplingTrigger;
      await trigger.waitFor({ state: 'visible' });
      if ((await trigger.getAttribute('aria-expanded')) !== 'true') {
        // The button's center can land on its info icon, which swallows the click.
        await trigger.getByText('Filtering and sampling', { exact: true }).click();
      }
      await expect(trigger).toHaveAttribute('aria-expanded', 'true');
      await this.samplingRateInput.waitFor({ state: 'visible' });
    });
  }

  /**
   * SliderInputControl writes the form value on blur, so the blur is explicit.
   * The slider's `aria-valuenow` mirrors local state, not the form, so the
   * trustworthy check is the persisted rate.
   */
  async setSamplingRatePercent(percent: number): Promise<void> {
    return test.step(`set sampling rate to ${percent}%`, async () => {
      await this.expandFilteringAndSampling();
      const input = this.samplingRateInput;
      await input.fill(String(percent));
      await input.blur();
      await expect(input).toHaveValue(String(percent));
    });
  }

  /** Each filter is its own condition card holding one row with a column selector. */
  get filterRows(): Locator {
    return this.panel
      .locator('tr')
      .filter({ has: this.page.locator('[data-testid="filter-column"]') });
  }

  filterRow(index: number): Locator {
    return this.filterRows.nth(index);
  }

  /** A new rule already starts with one empty filter row. */
  async addFilterRow(): Promise<void> {
    return test.step('add a filter row', async () => {
      await this.expandFilteringAndSampling();
      const before = await this.filterRows.count();
      await this.panel.getByRole('button', { name: 'Add filter' }).click();
      await expect(this.filterRows, 'filter rows after Add filter').toHaveCount(before + 1);
    });
  }

  /** Selecting a column resets the row's operator and value, so set it first. */
  async setFilterColumn(index: number, label: string): Promise<void> {
    return test.step(`set filter ${index + 1}'s column to "${label}"`, async () => {
      await this.filterRow(index)
        .locator('button[role="combobox"]:has([data-testid="filter-column"])')
        .click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
    });
  }

  async setFilterOperator(index: number, label: string): Promise<void> {
    return test.step(`set filter ${index + 1}'s operator to "${label}"`, async () => {
      await this.filterRow(index)
        .locator('button[role="combobox"]:has([data-testid="filter-operator"])')
        .click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
    });
  }

  /** `DebounceInput` commits on a timer, so the blur is explicit. */
  async setFilterValue(index: number, value: string): Promise<void> {
    return test.step(`set filter ${index + 1}'s value to "${value}"`, async () => {
      const input = this.filterValueInput(index);
      await input.fill(value);
      await input.blur();
      await expect(input, `filter ${index + 1}'s value box`).toHaveValue(value);
    });
  }

  async readFilterValue(index: number): Promise<string> {
    return test.step(`read filter ${index + 1}'s displayed value`, async () => {
      const input = this.filterValueInput(index);
      await expect(input, `filter ${index + 1}'s value box`).toBeVisible();
      return (await input.inputValue()).trim();
    });
  }

  private filterValueInput(index: number): Locator {
    return this.filterRow(index).locator(
      '[data-testid="filter-number-input"], [data-testid="filter-string-input"]',
    );
  }

  // --- LLM-as-a-Judge: model, parameters, template, prompt ---

  get modelPicker(): Locator {
    return this.panel.locator('button[role="combobox"]:has([data-testid="select-a-llm-model"])');
  }

  /**
   * Retried as a unit: the option list remounts when the model and
   * provider-key queries resolve, so an early click can open nothing.
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

  /** Types into the OPEN picker's search box; an empty result is a valid answer here. */
  async searchModels(term: string): Promise<Locator> {
    return test.step(`search the model picker for "${term}"`, async () => {
      const listbox = this.page.getByRole('listbox');
      await listbox.getByPlaceholder('Search model').fill(term);
      return listbox.getByRole('option');
    });
  }

  /** Picks a model from the OPEN picker. */
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

  async selectJudgeModel(modelDisplayName: string): Promise<void> {
    return test.step(`select judge model "${modelDisplayName}"`, async () => {
      assertAllowedModelDisplayName(modelDisplayName);
      await this.openModelPicker();
      await this.chooseModel(modelDisplayName);
    });
  }

  async readModelPickerText(): Promise<string> {
    return ((await this.modelPicker.textContent()) ?? '').trim();
  }

  /**
   * The model-parameters gear. Absent (not disabled) when the model has nothing
   * a rule can set. It has no accessible name (its label is a tooltip), and the
   * Templates button is another menu trigger, hence the icon filter.
   */
  get judgeModelParametersTrigger(): Locator {
    return this.panel
      .locator('button[aria-haspopup="menu"]')
      .filter({ has: this.page.locator('svg.lucide-settings2') });
  }

  get judgeModelParametersPanel(): Locator {
    return this.page.getByRole('menu');
  }

  async openJudgeModelParameters(): Promise<void> {
    return test.step('open the judge model parameters', async () => {
      await expect(this.judgeModelParametersTrigger, 'the model-parameters gear').toHaveCount(1);
      await this.judgeModelParametersTrigger.click();
      await this.judgeModelParametersPanel.waitFor({ state: 'visible' });
    });
  }

  async mountedJudgeParameterIds(): Promise<string[]> {
    return test.step('read the controls mounted in the judge parameters panel', async () => {
      return this.judgeModelParametersPanel
        .locator('input[data-testid$="-input"]')
        .evaluateAll((els) =>
          els.map((e) => (e.getAttribute('data-testid') ?? '').replace(/-input$/, '')),
        );
    });
  }

  /** LLM-as-a-Judge only, and not at span scope. */
  get costLimitLabel(): Locator {
    return this.panel.getByText('Cost limit (USD)', { exact: true });
  }

  /**
   * The Cost limit box itself. Free text with a decimal filter, not a number
   * input.
   *
   * Addressed by its placeholder because it has no other name: `CostLimitField`
   * wraps a local `CostInput` in `FormControl`, and `CostInput` does not spread
   * the props it is cloned with, so the `id` that `FormLabel`'s `htmlFor`
   * points at never reaches the `<input>`. The accessible name is therefore the
   * placeholder, and `getByLabel('Cost limit (USD)')` matches nothing.
   *
   * The right fix is a `data-testid` on that input (or forwarding the id),
   * added in the same change — the estate's conventions say so. This branch
   * cannot carry it: the QA harvest branch may only touch `tests_end_to_end/`,
   * and a change under `apps/opik-frontend/` would stop it being pushed at all.
   * Flagged here so it is picked up with the FE change rather than lost.
   */
  get costLimitInput(): Locator {
    return this.panel.getByPlaceholder('No limit');
  }

  /**
   * Type a cost limit. `fill` rather than `clear` + type: the control rejects
   * any keystroke that would leave a non-decimal string, so a partial value is
   * never committed and the assertion below is on what the form actually took.
   */
  async setCostLimit(value: string): Promise<void> {
    return test.step(`set the cost limit to ${value}`, async () => {
      const input = this.costLimitInput;
      await input.fill(value);
      await expect(input, 'the cost limit box').toHaveValue(value);
    });
  }

  /** Empty the Cost limit box, which is what sends `max_cost_usd: null`. */
  async clearCostLimit(): Promise<void> {
    return test.step('clear the cost limit', async () => {
      const input = this.costLimitInput;
      await input.fill('');
      await expect(input, 'the cost limit box').toHaveValue('');
    });
  }

  async readCostLimit(): Promise<string> {
    return test.step('read the cost limit out of the panel', async () => {
      const input = this.costLimitInput;
      await expect(input, 'the cost limit box').toBeVisible();
      return input.inputValue();
    });
  }

  /** Replaces the prompt and scores, and (when creating) sets the name to the template label. */
  async applyJudgeTemplate(label: CreateRuleLLMJudgeFields['template']): Promise<void> {
    return test.step(`apply the "${label}" judge template`, async () => {
      await this.panel.getByRole('button', { name: 'Templates' }).click();
      await this.page.getByRole('menuitem', { name: new RegExp(`^${label}`) }).click();
      await expect(this.page.getByRole('menu')).toHaveCount(0);
    });
  }

  /** The panel's one CodeMirror editor: the prompt, or the code of a code evaluator. */
  get editor(): Locator {
    return this.panel.locator('.cm-content');
  }

  /**
   * The prompt text, joined from CodeMirror's line elements. Fine for the short
   * prompts specs seed; a document long enough to virtualise would be cut.
   */
  async readPromptText(): Promise<string> {
    return test.step('read the prompt out of the editor', async () => {
      await this.editor.waitFor({ state: 'visible' });
      return this.editor.evaluate((el) =>
        Array.from(el.querySelectorAll('.cm-line'))
          .map((line) => line.textContent ?? '')
          .join('\n'),
      );
    });
  }

  // --- Decision model ---

  get decisionModelSelect(): Locator {
    return this.panel.locator('button[role="combobox"]:has([data-testid="decision-model-select"])');
  }

  async chooseDecisionModel(model: string): Promise<void> {
    return test.step(`choose the decision model "${model}"`, async () => {
      await this.decisionModelSelect.click();
      await this.page.getByRole('option', { name: model, exact: true }).click();
      await expect(this.decisionModelSelect).toHaveText(model);
    });
  }

  get mainQuestionInput(): Locator {
    return this.panel.getByPlaceholder('For example: Does the output fully answer the input?').first();
  }

  /**
   * Replace the judge prompt.
   *
   * The same editor `replaceCode` drives, and the same reason for inserting in
   * one go rather than typing: the prompt editor opens a variable-picker
   * popover on the SECOND `{` keystroke, so a typed `{{input.question}}` would
   * be racing an autocomplete list. `insertText` is a single input event and
   * reaches none of the keymap handlers.
   */
  async replacePromptText(prompt: string): Promise<void> {
    return test.step('replace the judge prompt', async () => {
      await this.replaceEditorContent(prompt);
    });
  }

  // --- Code evaluator ---

  /** Inserted in one go: typed line by line, CodeMirror's auto-indent would re-indent it. */
  async replaceCode(code: string): Promise<void> {
    return test.step('replace the evaluator code', async () => {
      await this.replaceEditorContent(code);
    });
  }

  private async replaceEditorContent(text: string): Promise<void> {
    await this.editor.click();
    await this.page.keyboard.press('ControlOrMeta+A');
    await this.page.keyboard.press('Delete');
    await this.page.keyboard.insertText(text);
  }

  // --- Whole create flows ---

  /**
   * Create an LLM-as-a-Judge rule from a template. The template goes first
   * because applying it renames the rule after itself. The rule name becomes the
   * main score's name.
   */
  async fillAndSubmitCreateRuleLLMJudge(fields: CreateRuleLLMJudgeFields): Promise<void> {
    return test.step(`create LLM-as-a-Judge rule "${fields.name}"`, async () => {
      await this.openCreateRulePanel('LLM-as-a-Judge');
      await this.applyJudgeTemplate(fields.template);
      await this.nameInput.fill(fields.name);
      await this.selectJudgeModel(fields.modelDisplayName);
      await this.submitPanel();
    });
  }

  /** Returns the new rule's id, for a caller that needs to address it by id. */
  async fillAndSubmitCreateRulePythonEquals(
    fields: CreateRulePythonEqualsFields,
  ): Promise<string> {
    return test.step(`create code evaluator rule "${fields.name}"`, async () => {
      await this.openCreateRulePanel('Code evaluator');
      await this.nameInput.fill(fields.name);
      await this.replaceCode(buildPythonEqualsMetric(fields.name, fields.referenceValue));
      if (fields.samplingRatePercent !== undefined) {
        await this.setSamplingRatePercent(fields.samplingRatePercent);
      }
      return this.submitPanelAndCaptureRuleId();
    });
  }
}
