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

  /**
   * Every validation message the editor is currently rendering, in DOM order.
   *
   * The whole set rather than one message, because the claim a submit-block
   * spec needs to make is an exhaustive one: "this is the only thing wrong" is
   * what separates a refinement that names the missing field from one that also
   * flags a field the user has not been offered yet (which is what the start
   * cards hid, and what #9143's last commit stopped). A membership check on one
   * message cannot see a second one beside it.
   *
   * By class, which the conventions put last and which is justified here the
   * same way `costLimitInput`'s placeholder is: `FormErrorSkeleton` is the one
   * component every form error in this panel renders through, and it carries no
   * test id, no role and no accessible name — only `text-destructive`. The
   * right fix is a `data-testid` on it, added with the FE change; this branch
   * may only touch `tests_end_to_end/`, so it is flagged here instead.
   *
   * `div` is load-bearing, not decoration: `ScoreNameTitle` paints an errored
   * score NAME with the same class on a `<span>`, and that span is the name
   * itself rather than a message — counting it would report the same error
   * twice and break the exhaustive comparison above.
   *
   * Deliberately NOT wrapped in a `test.step`, unlike most of this POM:
   * validation renders a tick after the submit, so callers read this through
   * `expect.poll`, and a step per poll attempt buries the step the assertion
   * belongs to under a dozen identical ones. Same reason
   * `readModelPickerText` has none.
   */
  async panelErrorMessages(): Promise<string[]> {
    const messages = await this.panel.locator('div.text-destructive').allInnerTexts();
    return messages.map((message) => message.trim());
  }

  /**
   * Opens the create panel. A new judge or decision model starts on cards, so
   * this also picks one: the given template, or the card to write your own.
   */
  async openCreateRulePanel(type: EvaluatorType = 'LLM-as-a-Judge', startCard?: string): Promise<void> {
    return test.step(`open the create panel for a ${type} evaluator`, async () => {
      await this.openEvaluatorMenuItem(type);
      if (type !== 'Code evaluator') {
        await this.chooseStartCard(startCard ?? (type === 'Decision model' ? 'Write your own' : 'Type your prompt'));
      }
    });
  }

  /**
   * Opens the create panel and STOPS on the start cards, choosing none.
   *
   * For the specs whose subject is the card screen itself — which card set a
   * scope offers, what each card applies, and the submit the cards block. Every
   * other caller wants `openCreateRulePanel`, which goes on to pick one,
   * because the rest of the form does not exist until a card is chosen.
   *
   * The cards are asserted present rather than assumed: a panel that opened
   * already past them (a default prompt coming back, an edit panel opening by
   * mistake) would otherwise surface as the caller's later card click never
   * finding its button, several steps from the cause.
   */
  async openCreateRulePanelOnStartCards(
    type: Exclude<EvaluatorType, 'Code evaluator'>,
  ): Promise<void> {
    return test.step(`open the create panel for a ${type} evaluator, on its start cards`, async () => {
      await this.openEvaluatorMenuItem(type);
      await expect(
        this.startCards,
        `a new ${type} rule opens on the start cards`,
      ).toHaveCount(1);
    });
  }

  private async openEvaluatorMenuItem(type: EvaluatorType): Promise<void> {
    await this.page.getByTestId('online-evaluation-create-rule-button').click();
    await this.page.getByRole('menuitem', { name: new RegExp(`^${type}`) }).click();
    // The Evaluator menu animates out over the panel; clicks land on it until it is gone.
    await expect(this.page.getByRole('menu')).toHaveCount(0);
    await this.waitForPanelOpen();
  }

  get startCards(): Locator {
    return this.panel.getByTestId('rule-editor-start-cards');
  }

  async chooseStartCard(label: string): Promise<void> {
    return test.step(`start from the "${label}" card`, async () => {
      await this.startCards.getByRole('button', { name: new RegExp(`^${label}`) }).click();
      await expect(this.startCards).toHaveCount(0);
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

  /**
   * The sampling-rate reset arrow, which `SliderInputControl` renders beside
   * its number input only once the value has moved off the default.
   *
   * Anchored on the input's OWN test id rather than on a position, so it is not
   * the `nth-child` kind of structural selector: it moves with the control, and
   * it resolves to nothing — loudly — if the button ever stops being the
   * input's sibling, instead of silently addressing a different button.
   */
  get samplingRateResetButton(): Locator {
    return this.panel.locator(
      'xpath=.//input[@data-testid="sampling_rate-input"]/preceding-sibling::button',
    );
  }

  /**
   * The row holding the "Sampling rate" label, its reset arrow and its value
   * box — the box whose height is the subject of the steady-row assertion.
   */
  get samplingRateControlRow(): Locator {
    return this.panel.locator(
      'xpath=.//input[@data-testid="sampling_rate-input"]' +
        '/ancestor::div[contains(@class,"justify-between")][1]',
    );
  }

  /**
   * Click the sampling-rate reset arrow and resolve once the input shows the
   * default again.
   *
   * Deliberately asserts nothing about the editor panel: whether the panel is
   * still open afterwards is the behaviour under test, and a POM that waited
   * for it would absorb the failure it exists to expose.
   */
  async resetSamplingRate(): Promise<void> {
    return test.step('click the sampling-rate reset arrow', async () => {
      await this.samplingRateResetButton.click();
      await expect(this.samplingRateInput).toHaveValue('100');
    });
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

  /**
   * Pick the judge model from a NAMED provider group, and stop there.
   *
   * `selectJudgeModel` takes the first option matching the display name, which
   * is ambiguous for a built-in OpenRouter id: the picker renders the id itself
   * as the display name, and a Custom provider called `openrouter` — which
   * `ensureModelAvailable` and the `@provider-sanity` matrix both create —
   * offers models under the same labels. Taking `.first()` would then read the
   * custom provider's panel and call it OpenRouter's.
   *
   * While the search box holds text the picker renders a flat list grouped by
   * provider, each group labelled, which is what makes the scope available.
   */
  async selectJudgeModelFromProvider(
    providerGroup: string,
    modelDisplayName: string,
  ): Promise<void> {
    return test.step(
      `select judge model "${modelDisplayName}" from the ${providerGroup} group`,
      async () => {
        assertAllowedModelDisplayName(modelDisplayName);
        const listbox = await this.openModelPicker();

        // Re-filtered and re-clicked for the same reason as `chooseModel`: the
        // option list remounts when /llm/models resolves, detaching options
        // mid-click.
        await expect(async () => {
          await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
          // By the group's accessible NAME, which Radix takes from its
          // `SelectLabel`. Exact and asserted to be unique, so a provider
          // label that stopped being unique fails here rather than selecting
          // from whichever group matched first.
          const group = listbox.getByRole('group', { name: providerGroup, exact: true });
          await expect(group, `the ${providerGroup} provider group`).toHaveCount(1);
          const option = group.getByRole('option', { name: modelDisplayName, exact: true });
          await expect(option, `"${modelDisplayName}" under ${providerGroup}`).toHaveCount(1);
          await option.click({ timeout: 2_000 });
          await expect(this.modelPicker).toContainText(modelDisplayName, { timeout: 2_000 });
        }).toPass({ timeout: 30_000 });
      },
    );
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

  /**
   * The thinking-effort select the judge parameters panel mounts for a model
   * that declares effort levels.
   *
   * By its accessible name, not a testid: `AnthropicModelConfigs` renders a
   * `<Label htmlFor="thinkingEffort">` beside a `SelectBox id="thinkingEffort"`,
   * so the label is the one handle on the control that is not a utility class.
   * `PlaygroundPage.thinkingEffortTrigger` addresses the same component, though
   * by role rather than by label.
   *
   * Not covered by `mountedJudgeParameterIds`, which reads `SliderInputControl`
   * number inputs — this is a Radix select and has none, so a spec asserting on
   * the effort has to come through here.
   *
   * Its ABSENCE is as much the assertion as its text. The control renders only
   * while the chosen model declares effort levels, so a model whose
   * capabilities row lists none drops it from the panel entirely rather than
   * showing it empty — and a rule switched onto such a model should have no
   * stored effort left either.
   */
  get judgeThinkingEffortSelect(): Locator {
    return this.judgeModelParametersPanel.getByLabel('Thinking effort');
  }

  /**
   * Close the judge parameters menu by toggling its own gear, and prove the
   * EDITOR survived.
   *
   * Explicitly NOT Escape, which is what the old modal edit dialog took: in the
   * rule editor side panel a single Escape closes the parameters menu AND
   * dismisses the whole editor with it. Measured on staging 2.2.116 — after one
   * press the menu, the name input and the model picker are all gone from the
   * DOM — so an Escape here surfaces much later and misleadingly, as the next
   * control on the form never opening. Clicking the gear again closes only the
   * menu, which is what every caller actually wants.
   *
   * The editor is asserted still open rather than assumed, because that is the
   * postcondition the callers depend on and the one that regressed.
   *
   * Needed by any spec that reads the parameters and then goes back to the form
   * — changing the model, naming the rule, saving. A spec that only reads them
   * and stops should open a fresh editor instead.
   */
  async closeJudgeModelParameters(): Promise<void> {
    return test.step('close the judge model parameters', async () => {
      // `force`, which is load-bearing rather than a shortcut: the menu is a
      // MODAL Radix dropdown, so while it is open everything outside it —
      // including its own gear — is `pointer-events: none` and an ordinary click
      // waits for a hit target it will never get. The press still reaches
      // Radix's dismiss layer, which is what closes the menu. Checked against
      // the alternatives on staging: a forced click on the name input and a
      // raw `mouse.click` at its coordinates both leave the menu open.
      await this.judgeModelParametersTrigger.click({ force: true });
      // Gone from the DOM, not merely hidden — the same wait
      // `openCreateRulePanel` makes on the Evaluator menu, and for the same
      // reason it gives: the menu animates out OVER the editor, and clicks land
      // on it until it is really gone.
      await expect(this.judgeModelParametersPanel).toHaveCount(0);
      await expect(
        this.nameInput,
        'closing the parameters menu left the rule editor open',
      ).toBeVisible();
    });
  }

  /**
   * A named control's label inside the open judge parameters panel.
   *
   * Exact, and the assertion in its own right for the effort control: the
   * panel renders "Effort" rather than "Thinking effort" for a Claude model
   * declaring `effortWithoutThinking`, where the level changes answer length
   * and tool use without turning thinking on. "Thinking level" is the Gemini
   * spelling of the same slot.
   */
  judgeParameterLabel(label: string): Locator {
    return this.judgeModelParametersPanel.getByText(label, { exact: true });
  }

  /**
   * The note that replaces the temperature while Claude can think.
   *
   * The WORDING is load-bearing, which is why this returns the element rather
   * than a boolean. A rule cannot store a Top P — `RULE_UNSUPPORTED_PARAMS`
   * strips it — so this surface must render the temperature-only sentence and
   * not the playground's two-parameter one, which would tell the user to go
   * and set a Top P control that is not there.
   */
  get judgeSamplingOffNote(): Locator {
    return this.judgeModelParametersPanel.getByText(/(?:are|is) off while Claude can think/);
  }

  /**
   * The effort / thinking-level `Select` the open judge parameters panel mounts.
   *
   * Addressed by role, not by its label: `SelectBox` does put the id on the
   * trigger, but the label is itself under test and differs per provider
   * ("Thinking effort", "Effort", "Thinking level"). The panel's only other
   * controls are `SliderInputControl` number inputs, so its one combobox is
   * this dropdown — asserted `toHaveCount(1)` before every read rather than
   * taken as `.first()`.
   */
  get judgeEffortTrigger(): Locator {
    return this.judgeModelParametersPanel.getByRole('combobox');
  }

  /** The level the judge parameters panel currently DISPLAYS. */
  async readJudgeEffort(): Promise<string> {
    return test.step('read the displayed judge effort', async () => {
      await expect(
        this.judgeEffortTrigger,
        'the judge parameters panel mounts exactly one effort control',
      ).toHaveCount(1);
      return ((await this.judgeEffortTrigger.textContent()) ?? '').trim();
    });
  }

  /**
   * Every level the judge effort control OFFERS, in the order it lists them.
   *
   * The whole list rather than one membership check: "Off is gone" and "the
   * levels are gone" are different regressions, and for Gemini the absence of
   * Off is the model's own constraint — Google answers a zero budget with
   * "Budget 0 is invalid. This model only works in thinking mode."
   */
  async judgeEffortOptions(): Promise<string[]> {
    return test.step('read the offered judge effort levels', async () => {
      const current = ((await this.judgeEffortTrigger.textContent()) ?? '').trim();
      const listbox = await this.openJudgeEffortListbox();
      const options = (await listbox.getByRole('option').allTextContents()).map((o) => o.trim());

      // Closed by re-picking the level already in force, which is a no-op for
      // the rule and the only dismissal that leaves the form standing. Not
      // Escape, for the reason `closeJudgeModelParameters` spells out — in the
      // editor side panel it takes the parameters menu and the editor with it,
      // so a caller that then read the effort found no control to read. Not the
      // trigger either: while a Radix Select is open its trigger is not a hit
      // target at all, and even a forced click waits for one that never comes.
      const same = listbox.getByRole('option', { name: current, exact: true });
      await expect(
        same,
        `the displayed level "${current}" is one of the levels offered`,
      ).toHaveCount(1);
      await same.click();
      await expect(listbox).toHaveCount(0);
      await expect(
        this.judgeModelParametersPanel,
        'reading the offered levels left the parameters menu open',
      ).toHaveCount(1);
      return options;
    });
  }

  /** Pick a judge effort / thinking level by its rendered label, e.g. `Low`. */
  async selectJudgeEffort(label: string): Promise<void> {
    return test.step(`select judge effort "${label}"`, async () => {
      const listbox = await this.openJudgeEffortListbox();
      const option = listbox.getByRole('option', { name: label, exact: true });
      // Exactly one, not `.first()`: "High" and "xHigh" are both offered on
      // some models, and an ambiguous match would store the wrong level.
      await expect(option, `"${label}" is offered`).toHaveCount(1);
      await option.click();
      await expect(listbox).toBeHidden();
      await expect(this.judgeEffortTrigger).toHaveText(label);
    });
  }

  private async openJudgeEffortListbox(): Promise<Locator> {
    await expect(
      this.judgeEffortTrigger,
      'the judge parameters panel mounts exactly one effort control',
    ).toHaveCount(1);
    const listbox = this.page.getByRole('listbox');
    // Retried: the panel remounts when the model and provider-key queries
    // resolve, which detaches the trigger mid-click.
    await expect(async () => {
      await this.judgeEffortTrigger.click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });
    return listbox;
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

  /**
   * The Questions card's question boxes, in render order — one per score a
   * decision-model rule declares.
   *
   * By placeholder because the boxes have no label of their own: the heading
   * above each one is the editable score NAME (`ScoreNameTitle`), not a
   * `<label>`, so there is nothing for `getByLabel` to match.
   */
  get questionInputs(): Locator {
    return this.panel.getByPlaceholder('For example: Does the output fully answer the input?');
  }

  get mainQuestionInput(): Locator {
    return this.questionInputs.first();
  }

  /**
   * The question text of every score the Questions card shows, in order.
   *
   * The whole list, because a template that applied only its first question is
   * the regression: reading just the main box would pass for a Support reply
   * that silently lost two of its three questions.
   *
   * Read in one `evaluateAll` rather than as several `inputValue()` round trips
   * so the list cannot be stitched together across a re-render, the same reason
   * `mountedJudgeParameterIds` does it that way.
   */
  async readQuestions(): Promise<string[]> {
    return test.step('read the question list out of the Questions card', async () => {
      return this.questionInputs.evaluateAll((els) =>
        els.map((el) => (el as HTMLTextAreaElement).value),
      );
    });
  }

  /**
   * The score name each question block DISPLAYS, in order.
   *
   * Displayed, not stored, and the difference is the point: a template leaves
   * its main question unnamed and `ScoreNameTitle` falls back to the rule name
   * for it, so what is on screen for the first block is the rule's name while
   * the named extras ("Polite tone") show their own. A spec asserting the
   * fallback is asserting the thing the author actually sees.
   */
  async readQuestionNames(): Promise<string[]> {
    return test.step('read the score names the Questions card shows', async () => {
      const names = await this.panel
        .getByRole('button', { name: 'Edit score name' })
        .allInnerTexts();
      return names.map((name) => name.trim());
    });
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
      await this.fillCreateRuleLLMJudge(fields);
      await this.submitPanel();
    });
  }

  /**
   * Fill the ALREADY-OPEN create form, stopping short of the submit.
   *
   * Deliberately does NOT open the panel — its caller does, and a caller that
   * has opened it cannot un-open it: the editor covers the create button, so a
   * second `openCreateRulePanel` waits on a target that is never hit-testable
   * again and reports as the create button "never visible, enabled and stable".
   *
   * For a spec that has to touch the form between filling it and saving —
   * setting a model parameter through the gear, say. The order matters and is
   * why this is one method rather than a caller's own sequence: applying the
   * template renames the rule after itself, so the name has to be set after it,
   * and the model has to be chosen before its parameters panel exists at all.
   * A caller adjusting model parameters does it AFTER this returns.
   */
  async fillCreateRuleLLMJudge(fields: CreateRuleLLMJudgeFields): Promise<void> {
    return test.step(`fill the LLM-as-a-Judge form for "${fields.name}"`, async () => {
      await this.waitForPanelOpen();
      await this.applyJudgeTemplate(fields.template);
      await this.nameInput.fill(fields.name);
      await this.selectJudgeModel(fields.modelDisplayName);
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
