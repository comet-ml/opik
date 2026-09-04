import { test, type Page, type Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

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
    const realRow = this.page.locator('tbody tr[data-row-id]').first();
    const emptyState = this.page.getByText('No online evaluations yet');
    await Promise.race([
      realRow.waitFor({ state: 'visible' }),
      emptyState.waitFor({ state: 'visible' }),
    ]);
  }

  /** Locator for a rule row by name. Uses `data-row-id` row scope + cell-name filter. */
  ruleRow(name: string): Locator {
    return this.page
      .locator('tbody tr[data-row-id]')
      .filter({ has: this.page.getByRole('cell', { name, exact: true }) });
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

  /** The "Enable rule" switch inside the add/edit dialog. */
  get enableRuleSwitch(): Locator {
    return this.dialog.getByRole('switch', { name: 'Enable rule' });
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
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();

      await this.dialog.waitFor({ state: 'visible' });
      const toggle = this.enableRuleSwitch;
      await expect(toggle, 'edit dialog hydrates the switch from the persisted value').toBeChecked({
        checked: !enabled,
      });
      await toggle.click();
      await expect(toggle).toBeChecked({ checked: enabled });

      await this.dialog.getByTestId('add-edit-rule-dialog-submit').click();
      await this.dialog.waitFor({ state: 'hidden' });

      await expect(this.ruleStatusCell(name, enabled ? 'Enabled' : 'Disabled')).toBeVisible();
    });
  }

  /**
   * Open a rule's edit dialog through the row's kebab → Edit.
   *
   * `setRuleEnabledByName` drives the same two clicks inline; it is left as it
   * is rather than rewired through this method, so that a spec added later
   * cannot change how the enable/disable spec behaves.
   */
  async openEditRuleDialog(name: string): Promise<void> {
    return test.step(`open the edit dialog for rule "${name}"`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      await row.getByRole('button', { name: 'Actions menu' }).click();
      await this.page.getByRole('menuitem', { name: 'Edit' }).click();
      await this.dialog.waitFor({ state: 'visible' });
    });
  }

  /** Submit the add/edit dialog and wait for it to close. */
  async submitRuleDialog(): Promise<void> {
    return test.step('submit the rule dialog', async () => {
      await this.dialog.getByTestId('add-edit-rule-dialog-submit').click();
      await this.dialog.waitFor({ state: 'hidden' });
    });
  }

  /**
   * Pick the LLM-judge model, scoped to the picker group of the provider that
   * offers it.
   *
   * The group scope is not optional: Gemini and Vertex AI publish the SAME
   * display labels ("Gemini 3.5 Flash Lite" is both `gemini-3.5-flash-lite`
   * and `vertex_ai/gemini-3.5-flash-lite`), so on a workspace with both
   * providers configured an unscoped lookup matches two options and taking
   * `.first()` would silently test whichever the picker happened to render
   * first. Both the group and the option are asserted to resolve to exactly
   * one element.
   */
  async selectLLMJudgeModel(providerGroupLabel: string, modelDisplayName: string): Promise<void> {
    return test.step(`select model "${providerGroupLabel} / ${modelDisplayName}"`, async () => {
      const modelCombobox = this.llmJudgeModelCombobox;
      const listbox = this.page.getByRole('listbox');

      // The option list remounts when the model/provider-key queries resolve,
      // so the trigger can swallow a click that arrives mid-render.
      await expect(async () => {
        await modelCombobox.click();
        await expect(listbox).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });

      await listbox.getByPlaceholder('Search model').fill(modelDisplayName);

      // Groups are labelled by the provider; the label text is exact, while an
      // option's text is not, so `has: getByText(exact)` cannot match a model
      // row by accident.
      const group = listbox
        .getByRole('group')
        .filter({ has: this.page.getByText(providerGroupLabel, { exact: true }) });
      await expect(group, `exactly one "${providerGroupLabel}" provider group`).toHaveCount(1);

      const option = group.getByRole('option', { name: modelDisplayName, exact: true });
      await expect(
        option,
        `exactly one "${modelDisplayName}" under "${providerGroupLabel}"`,
      ).toHaveCount(1);
      await option.click();

      await expect(modelCombobox).toContainText(modelDisplayName);
    });
  }

  /**
   * The LLM-judge model picker's trigger.
   *
   * Scoped by the `select-a-llm-model` test id that `PromptModelSelect` already
   * puts on its value span, rather than by the trigger's text: the text is what
   * distinguishes this combobox from the dialog's others (template, message
   * roles), but it changes once a model is chosen, so matching it means
   * enumerating provider names — which silently stops matching the day a
   * provider outside that list is picked.
   */
  private get llmJudgeModelCombobox(): Locator {
    return this.dialog
      .getByRole('combobox')
      .filter({ has: this.page.getByTestId('select-a-llm-model') });
  }

  /**
   * The gear that opens the model-parameters popover (`PromptModelConfigs`).
   *
   * It is an icon-only button with no accessible name — its "Model parameters"
   * string lives in a tooltip, which contributes nothing to the accessible
   * name — so it carries a `data-testid` added alongside this POM. Addressing
   * it by its lucide icon class instead would tie the suite to an internal
   * class name that a lucide version bump renames without warning.
   *
   * `openModelParameters` asserts the locator resolves to exactly one element,
   * so it fails loudly rather than opening some other popover if a second
   * config trigger ever appears in the dialog.
   */
  private get modelParametersTrigger(): Locator {
    return this.dialog.getByTestId('model-parameters-trigger');
  }

  /** The popover the gear opens. Portalled, so it is NOT inside `dialog`. */
  private get modelParametersMenu(): Locator {
    return this.page.getByRole('menu');
  }

  /** Open the model-parameters popover next to the LLM-judge model picker. */
  async openModelParameters(): Promise<void> {
    return test.step('open the model-parameters popover', async () => {
      await expect(
        this.modelParametersTrigger,
        'exactly one model-parameters gear in the rule dialog',
      ).toHaveCount(1);
      await this.modelParametersTrigger.click();
      await this.modelParametersMenu.waitFor({ state: 'visible' });
    });
  }

  /**
   * Close the model-parameters popover.
   *
   * Escape is pressed until the popover is gone rather than once: if a select
   * inside it is still open, the first Escape closes that select and leaves
   * the popover up. Retrying on the observed state beats guessing how many
   * presses the current sub-state needs.
   */
  async closeModelParameters(): Promise<void> {
    return test.step('close the model-parameters popover', async () => {
      await expect(async () => {
        await this.page.keyboard.press('Escape');
        await expect(this.modelParametersMenu).toBeHidden({ timeout: 1_000 });
      }).toPass({ timeout: 10_000 });
    });
  }

  /**
   * The "Thinking level" select inside the model-parameters popover. Rendered
   * only for Gemini/Vertex models that expose a level (`GeminiModelConfigs`).
   */
  get thinkingLevelSelect(): Locator {
    return this.modelParametersMenu.getByRole('combobox', { name: 'Thinking level' });
  }

  /** The level the popover currently shows, e.g. `None`. */
  async readThinkingLevel(): Promise<string> {
    return test.step('read the thinking level', async () => {
      await expect(this.thinkingLevelSelect).toHaveCount(1);
      return ((await this.thinkingLevelSelect.textContent()) ?? '').trim();
    });
  }

  /**
   * Every level this model offers, in the order the control lists them.
   *
   * The list is the assertion for a model's *available* levels — which levels
   * a model may be set to is as much a part of this control's contract as the
   * one it preselects.
   */
  async listThinkingLevels(): Promise<string[]> {
    return test.step('list the offered thinking levels', async () => {
      await this.thinkingLevelSelect.click();
      const options = this.page.getByRole('listbox').getByRole('option');
      const labels = (await options.allTextContents()).map((t) => t.trim());
      // Dismiss the select without choosing, so reading the options cannot
      // change the value the caller is about to assert on.
      await this.page.keyboard.press('Escape');
      await expect(this.page.getByRole('listbox')).toBeHidden();
      return labels;
    });
  }

  /** Choose a thinking level by its displayed label. */
  async setThinkingLevel(label: string): Promise<void> {
    return test.step(`set thinking level to "${label}"`, async () => {
      await this.thinkingLevelSelect.click();
      await this.page.getByRole('listbox').getByRole('option', { name: label, exact: true }).click();
      await expect(this.thinkingLevelSelect).toHaveText(label);
    });
  }

  /** The destructive confirm dialog raised by the row's Delete action. */
  get deleteRuleConfirmDialog(): Locator {
    return this.page.getByRole('dialog').filter({
      has: this.page.getByRole('heading', { name: 'Delete evaluation rule' }),
    });
  }

  /**
   * Fill the name / template / model of an LLM-as-judge rule and STOP, leaving
   * the dialog open for a caller that has to inspect or change the model
   * parameters before submitting.
   *
   * The canned template ships its own prompt, variable mapping and score
   * definition, and the default `output` mapping submits fine — the mapping
   * override in `fillAndSubmitCreateRuleDialogLLMJudge` exists to make the
   * judge see a bare string, which only matters to a rule that will actually
   * score traces.
   */
  async fillLLMJudgeRuleBasics(fields: {
    name: string;
    template: CreateRuleDialogLLMJudgeFields['template'];
    /** Provider group label in the model picker, e.g. `Gemini`. */
    providerGroupLabel: string;
    modelDisplayName: string;
  }): Promise<void> {
    return test.step(`fill LLM-judge rule "${fields.name}"`, async () => {
      const d = this.dialog;
      await d.getByRole('textbox', { name: 'Rule name' }).fill(fields.name);

      // Template first — selecting it rebuilds the prompt + variable mapping
      // section, which would wipe out anything set before it.
      const promptCombobox = d.getByRole('combobox').filter({
        hasText: /^(Custom LLM-as-judge|Hallucination|Moderation|AnswerRelevance|Structured Output Compliance|Meaning Match)$/,
      });
      await promptCombobox.click();
      await this.page.getByRole('option', { name: fields.template, exact: true }).click();

      await this.selectLLMJudgeModel(fields.providerGroupLabel, fields.modelDisplayName);
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

    // Pick the model.
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
