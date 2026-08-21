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

/**
 * Placeholder every Variable-mapping path input carries
 * (`TracesOrSpansPathsAutocomplete`, worded by the rule's scope). It is the
 * only stable handle on those rows: they render no data-testid, and their tags
 * carry only the variable name.
 */
const VARIABLE_PATH_PLACEHOLDER = /^Select a key from recent (trace|span)$/;

/** Escape a variable name for interpolation into an anchored `hasText` regex. */
function escapeForRegExp(value: string): string {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
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
   * Open a rule's edit dialog through the row's kebab menu, leaving every field
   * as persisted. `setRuleEnabledByName` drives the same route but also flips
   * the switch and submits; this is the read-only door for specs that assert on
   * how the dialog hydrates.
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

  /** Dismiss the add/edit dialog without saving. */
  async cancelDialog(): Promise<void> {
    return test.step('cancel the add/edit rule dialog', async () => {
      await this.dialog.getByRole('button', { name: 'Cancel' }).click();
      await this.dialog.waitFor({ state: 'hidden' });
    });
  }

  /** Submit the add/edit dialog and wait for it to close. */
  async submitDialog(): Promise<void> {
    return test.step('submit the add/edit rule dialog', async () => {
      await this.dialog.getByTestId('add-edit-rule-dialog-submit').click();
      await this.dialog.waitFor({ state: 'hidden' });
    });
  }

  /** The Scope select in the add/edit dialog (disabled once a rule exists). */
  get scopeSelect(): Locator {
    return this.dialog.getByRole('combobox').filter({ hasText: /^(Trace|Thread|Span)$/ });
  }

  /**
   * Change the rule's Scope.
   *
   * `expectResetConfirm` is required rather than sniffed, because the dialog
   * only raises "You're about to lose your changes" when the judge details are
   * dirty. Probing for the confirm with an `isVisible()` check would race the
   * dialog's mount and silently pick the wrong branch; naming the expectation
   * makes a change in that rule fail here instead.
   *
   * Confirming RESETS the judge details to the new scope's defaults — prompt,
   * model and variable mapping included — so a caller that needs a specific
   * prompt on the new scope has to set it again afterwards.
   */
  async selectScope(
    scope: 'Trace' | 'Thread' | 'Span',
    { expectResetConfirm }: { expectResetConfirm: boolean },
  ): Promise<void> {
    return test.step(`select the ${scope} scope`, async () => {
      await this.scopeSelect.click();
      await this.page.getByRole('option', { name: scope, exact: true }).click();

      if (expectResetConfirm) {
        const confirm = this.page.getByRole('dialog').filter({
          has: this.page.getByRole('heading', { name: /about to lose your changes/ }),
        });
        await confirm.waitFor({ state: 'visible' });
        await confirm.getByRole('button', { name: 'Reset and continue' }).click();
        await confirm.waitFor({ state: 'hidden' });
      }

      await expect(this.scopeSelect).toHaveText(scope);
    });
  }

  /**
   * Replace the LLM-judge prompt with `prompt`.
   *
   * The text is inserted with `insertText` rather than typed key-by-key:
   * CodeMirror's default bracket-closing would turn a typed `{` into `{}`, and
   * every variable this spec cares about is written `{{name}}`. `insertText`
   * dispatches the text as a single input event, so what lands is what was
   * asked for.
   *
   * Asserts a single message editor first — the canned templates are
   * one-message, and a two-message template would otherwise have this silently
   * edit whichever came first.
   */
  async setLLMJudgePrompt(prompt: string): Promise<void> {
    return test.step('replace the LLM-judge prompt', async () => {
      const editor = this.dialog.getByTestId('playground-message-editor');
      await expect(editor, 'the judge prompt is a single message').toHaveCount(1);

      const content = editor.locator('.cm-content');
      await content.click();
      await this.page.keyboard.press('ControlOrMeta+A');
      await this.page.keyboard.press('Delete');
      await this.page.keyboard.insertText(prompt);
      await expect(content).toHaveText(prompt);
    });
  }

  /**
   * The "Variable mapping (N)" header of the add/edit dialog, present only on
   * trace and span scope — thread-scope rules take no variables and render no
   * section at all.
   */
  get variableMappingHeader(): Locator {
    return this.dialog.getByText(/^Variable mapping \(\d+\)$/);
  }

  /**
   * One Variable-mapping row, addressed by the variable's name.
   *
   * The row carries no data-testid, so it is identified by what it contains: a
   * path input — nothing else in the dialog offers "a key from a recent
   * trace/span" — beside a tag whose whole text is the variable name. The name
   * match is anchored, so `span` cannot resolve the `spans` row.
   *
   * The trailing `hasNot` keeps only the INNERMOST such div. Without it the
   * row's ancestors (the row list, the section) match too whenever they wrap a
   * single row, since they contain the same input and the same text — an
   * ambiguity between an element and its own ancestor, which no amount of
   * anchoring the name fixes.
   *
   * A `data-testid` on the row would be better and belongs in the frontend.
   * It is deliberately not added here: these specs are verified against an
   * already-deployed build, which a test-only markup change cannot reach.
   */
  variableMappingRow(name: string): Locator {
    const rowOrAncestor = (root: Page | Locator): Locator =>
      root
        .locator('div')
        .filter({ has: this.page.getByPlaceholder(VARIABLE_PATH_PLACEHOLDER) })
        .filter({ hasText: new RegExp(`^${escapeForRegExp(name)}$`) });

    return rowOrAncestor(this.dialog).filter({ hasNot: rowOrAncestor(this.page) });
  }

  /** The extraction-path input inside a Variable-mapping row. */
  variableMappingInput(name: string): Locator {
    return this.variableMappingRow(name).getByPlaceholder(VARIABLE_PATH_PLACEHOLDER);
  }

  /** Every rendered Variable-mapping path input — one per visible row. */
  private get variableMappingInputs(): Locator {
    return this.dialog.getByPlaceholder(VARIABLE_PATH_PLACEHOLDER);
  }

  /**
   * Assert the dialog renders exactly the `names` Variable-mapping rows, and no
   * others.
   *
   * All three checks earn their place. The header is the frontend's own tally
   * of the filtered list; the input count is the rows actually rendered (they
   * agree only if the list and its header were derived from the same array);
   * and the per-name lookups prove WHICH rows survived the filter, each
   * asserting `toHaveCount(1)` so an ambiguous match fails loudly rather than
   * quietly testing the wrong row.
   */
  async expectVariableMappingRows(names: string[]): Promise<void> {
    return test.step(`expect variable mapping rows [${names.join(', ')}]`, async () => {
      await expect(this.variableMappingHeader).toHaveText(
        `Variable mapping (${names.length})`,
      );
      await expect(this.variableMappingInputs).toHaveCount(names.length);
      for (const name of names) {
        await expect(this.variableMappingRow(name), `row "${name}" is rendered`).toHaveCount(1);
      }
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
