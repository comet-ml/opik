import { test, type Page, type Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { AutomationLogsPage } from './automation-logs.page';

/**
 * Template labels the dialog offers. The trace-scope set and the thread-scope
 * set are disjoint — the Scope select swaps the template list — so picking a
 * thread template requires `scope: 'Thread'`.
 */
export type RuleTemplate =
  | 'Moderation'
  | 'Hallucination'
  | 'AnswerRelevance'
  | 'Structured Output Compliance'
  | 'Meaning Match'
  | 'Custom LLM-as-judge'
  | 'Conversational coherence'
  | 'User frustration'
  | 'Custom LLM-as-judge (thread)';

export interface CreateRuleDialogLLMJudgeFields {
  name: string;
  /** Canned-template label as shown in the dialog. */
  template: RuleTemplate;
  /** Model display name as shown in the model picker (e.g. "Claude Haiku 4.5"). */
  modelDisplayName: string;
  /** Evaluation scope. Defaults to Trace, the dialog's own default. */
  scope?: 'Trace' | 'Thread';
  /**
   * Variable-mapping overrides, keyed by the template variable name. Templates
   * ship a default mapping for some variables and leave others blank (e.g.
   * Meaning Match's `ground_truth`); a blank one must be filled or the rule
   * renders the prompt with an empty value. Pass `{}` (or omit) to keep the
   * `output → output.output` default the trace templates need.
   */
  variableMappings?: Record<string, string>;
}

export interface CreateRuleDialogPythonEqualsFields {
  name: string;
  /** The literal string the trace's output must equal to score 1.0. */
  referenceValue: string;
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

    // Scope BEFORE the template: changing it resets the whole rule (the FE
    // warns as much) and swaps the template list for the scope's own set.
    if (fields.scope && fields.scope !== 'Trace') {
      await this.scopeCombobox.click();
      await this.page.getByRole('option', { name: fields.scope, exact: true }).click();
      await expect(this.scopeCombobox).toContainText(fields.scope);
    }

    // Pick the template FIRST — selecting it rebuilds the prompt + variable
    // mapping section, so any prior tweaks would be wiped out.
    const promptCombobox = d.getByRole('combobox').filter({
      hasText: /^(Custom LLM-as-judge|Hallucination|Moderation|AnswerRelevance|Structured Output Compliance|Meaning Match|Conversational coherence|User frustration)$/,
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
    // Thread templates have no per-trace variables to remap: their single
    // `{{context}}` is the conversation the engine injects itself.
    const mappings = fields.variableMappings ?? { output: 'output.output' };
    for (const [variableName, pathValue] of Object.entries(mappings)) {
      await this.setVariableMapping(variableName, pathValue);
    }

    await d.getByTestId('add-edit-rule-dialog-submit').click();
    await d.waitFor({ state: 'hidden' });
  }

  /**
   * The Scope select. It carries no testid, so it's identified by the only
   * value it can hold — the scope labels — which no other combobox in the
   * dialog renders.
   */
  private get scopeCombobox(): Locator {
    return this.dialog.getByRole('combobox').filter({ hasText: /^(Trace|Thread|Span)$/ });
  }

  /**
   * Open a rule's "Show logs" action and return the Automation logs page it
   * lands on. The link targets a new tab, so the popup is awaited rather than
   * the current page navigated.
   */
  async openLogsForRule(name: string): Promise<AutomationLogsPage> {
    return test.step(`open logs for rule "${name}"`, async () => {
      const row = this.ruleRow(name);
      await row.waitFor({ state: 'visible' });
      const [popup] = await Promise.all([
        this.page.context().waitForEvent('page'),
        row.getByRole('link', { name: 'Show logs' }).click(),
      ]);
      await popup.waitForLoadState('domcontentloaded');
      return new AutomationLogsPage(popup);
    });
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
    //
    // `.last()`, not `.first()`: `has:` matches every *ancestor* of the label
    // too, and with more than one variable the outermost match is the section
    // wrapping all the rows — which holds every row's cmdk-input. Elements come
    // back in document order, so the last match is the innermost div holding
    // both this label and an input, i.e. the row itself. A single-variable
    // template hides this (section and row hold the same one input).
    const row = this.dialog
      .locator('div')
      .filter({ has: this.page.locator(`text=/^${variableName}$/`) })
      .filter({ has: this.page.locator('input[cmdk-input]') })
      .last();
    const input = row.locator('input[cmdk-input]');
    await expect(input, `variable "${variableName}" resolves to exactly one mapping input`).toHaveCount(1);
    await input.waitFor({ state: 'visible' });
    await input.click();
    await this.page.keyboard.press('ControlOrMeta+A');
    await this.page.keyboard.press('Delete');
    await this.page.keyboard.type(pathValue);
    await this.page.keyboard.press('Escape');
  }
}
