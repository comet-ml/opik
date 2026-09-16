import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export type RunExperimentSourceMode = 'dataset' | 'test_suite';

const escapeForRegExp = (literal: string): string =>
  literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** Empty-state text an output cell shows until its row has been run. */
const IDLE_CELL_TEXT = 'No runs yet';

/**
 * A failed run surfaces as the output cell's own value — `processCombination` catches and
 * writes `error.message` there, with no flag to distinguish it from a real completion, so
 * text is the only signal available. Kept narrow on purpose: these two are strings Opik
 * itself emits, whereas a broad /error/i would fire on legitimate model output. Provider
 * errors are free-form and stay undetected here by design.
 */
const RUN_ERROR_TEXT = /\bnot defined\b|returned an empty response/i;

/**
 * Whether a cell has finished.
 *
 * Deliberately does NOT require a test-suite cell's Passed/Failed verdict. A suite cell does
 * leave the idle state before its verdict lands, but the verdict comes from
 * `PlaygroundOutputAssertionStatus`, which polls the experiment for up to its own 5-minute
 * ceiling — far past the 120s callers allow — so requiring it times the wait out on a run
 * that is working. It also overshoots what these specs claim, which is that a run produces
 * output, not that its assertions were scored. A spec that wants the verdict should wait on
 * it explicitly, with a budget to match.
 *
 * Text is the only handle here: the idle placeholder carries no testid, so output that
 * itself contained "No runs yet" would read as idle. Adding one would not help — these specs
 * run against deployed Opik, so it would not exist in the version under test.
 */
const hasProducedOutput = (text: string): boolean =>
  text.trim() !== '' && !text.includes(IDLE_CELL_TEXT);

export interface PlaygroundVariantConfig {
  /** Optional system prompt — if set, first message is converted to role=system then a User message is appended. */
  systemPrompt?: string;
  /** User prompt body. Supports `{{column}}` templating against suite/dataset items. */
  userPrompt: string;
  /** Model display name as shown in the model picker (e.g., "gpt-5-nano (free)"). */
  modelDisplayName?: string;
}

export interface RunSimplePromptArgs {
  modelDisplayName: string;
  prompt: string;
  timeoutMs?: number;
}

export interface RunSimplePromptResult {
  outputText: string;
  isError: boolean;
}

/** One instant's view of the Playground's Metrics picker. */
export interface MetricsPickerState {
  /** The "<selected> of <total> selected" footer, verbatim. */
  summary: string;
  /** One entry per rule row, in the order the picker lists them. */
  rules: Array<{
    name: string;
    checked: boolean;
    /**
     * Whether the row's checkbox is disabled — which the picker uses to mark a
     * rule that scores every dataset run whether or not it was chosen, so it
     * cannot be given back.
     */
    locked: boolean;
  }>;
}

/**
 * Prompt Playground page object. Two distinct user-modes:
 *   - "free" (no dataset/suite loaded): variants run against ad-hoc prompts; results
 *     appear as individual outputs below each variant card.
 *   - "experiment" (dataset or test-suite loaded): variants run against all items;
 *     results appear in the table at the bottom. Every Re-run creates a new
 *     experiment server-side automatically (no separate "Save as experiment" step).
 */
export class PlaygroundPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('navigate to Playground', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/playground`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('wait for Playground to be ready', async () => {
      await this.page
        .getByRole('heading', { name: 'Playground', level: 1 })
        .waitFor({ state: 'visible' });
      // Variant card "A" should be mounted with its model picker.
      await this.modelPicker(0).waitFor({ state: 'visible' });
    });
  }

  /**
   * Configure the variant at the given index.
   * - If `systemPrompt` is set, the FIRST message row gets its role flipped to
   *   System, then a new User message row is appended with the userPrompt.
   * - Otherwise, the first message row (already User by default) is filled directly.
   */
  async configureVariant(index: number, cfg: PlaygroundVariantConfig): Promise<void> {
    return test.step(`configure variant ${index}`, async () => {
      if (cfg.modelDisplayName) {
        await this.setModelForVariant(index, cfg.modelDisplayName);
      }

      const messages = this.variantMessages(index);

      if (cfg.systemPrompt !== undefined) {
        // Flip first message role to System and fill it.
        const firstMessage = messages.first();
        await firstMessage.getByRole('button', { name: 'User' }).click();
        await this.page.getByRole('menuitemcheckbox', { name: 'System' }).click();
        await this.fillMessageBody(firstMessage, cfg.systemPrompt);

        // Add a new message; defaults to User.
        await this.variantCard(index).getByRole('button', { name: 'Message' }).click();
        const userMessage = messages.nth(1);
        await this.fillMessageBody(userMessage, cfg.userPrompt);
      } else {
        const firstMessage = messages.first();
        await this.fillMessageBody(firstMessage, cfg.userPrompt);
      }
    });
  }

  /** Open the "Run experiment" entry menu (when no suite/dataset is loaded). */
  async clickRunExperiment(): Promise<void> {
    return test.step('click Run experiment', async () => {
      await this.runExperimentTriggerButton().click();
      await this.page
        .getByTestId('run-experiment-source-dataset')
        .waitFor({ state: 'visible' });
    });
  }

  /**
   * Pick a source from the inline "Run experiment" control. Chooses Dataset or
   * Test suite from the entry menu, then selects the entity by name from the
   * inline dropdown (which auto-opens). Selection is live — clicking the entity
   * row picks its latest version and commits, so there is no submit button.
   */
  async selectRunExperimentSource(args: {
    mode: RunExperimentSourceMode;
    entityName: string;
  }): Promise<void> {
    return test.step(`select ${args.mode} "${args.entityName}" for run experiment`, async () => {
      const optionTestId =
        args.mode === 'test_suite'
          ? 'run-experiment-source-test-suite'
          : 'run-experiment-source-dataset';
      await this.page.getByTestId(optionTestId).click();

      // The inline source selector auto-opens. Search and pick the entity by
      // name; clicking the row selects the latest version and commits.
      const listbox = this.page.getByRole('listbox');
      await listbox.waitFor({ state: 'visible' });
      await listbox.getByPlaceholder('Search').fill(args.entityName);
      await listbox.getByText(args.entityName, { exact: true }).first().click();

      await expect(this.loadedSourcePill()).toContainText(args.entityName);
    });
  }

  /** Click Re-run when a suite/dataset is already loaded. */
  async clickReRun(): Promise<void> {
    return test.step('click Re-run', async () => {
      await this.runButton().click();
    });
  }

  /**
   * Wait until a loaded dataset/suite can actually be run.
   *
   * The source pill commits with the *selection*, which is strictly before
   * `useDatasetItemsList` resolves. Running in that window builds the
   * prompt/item combinations from an empty item list, so every row fails
   * client-side with "<var> not defined" — no LLM call, no experiment, and a
   * permanent error state that no later wait can recover. The output cells come
   * from the same query as the runner's items, so their presence is the signal
   * that the run has something to iterate.
   */
  async waitForRunReady(opts: { expectedRows: number; timeoutMs?: number }): Promise<void> {
    return test.step(`wait for ${opts.expectedRows} row(s) to be run-ready`, async () => {
      await expect
        .poll(
          async () => {
            const total = await this.outputCells().count();
            return total >= opts.expectedRows && (await this.idleOutputCells().count()) === total;
          },
          { timeout: opts.timeoutMs ?? 30_000, intervals: [250, 500, 1000] },
        )
        .toBe(true);
    });
  }

  /**
   * Wait for every output cell to finish.
   *
   * A run that fails writes the failure into the cell as its value, so "no longer idle"
   * does not mean "succeeded". The failure scan runs inside the poll, not after it: one
   * errored cell next to one that never ran would otherwise hold the predicate false for
   * the full timeout and report a bare poll timeout instead of the failure.
   */
  async waitForRunsComplete(opts: { expectedRows: number; timeoutMs?: number }): Promise<void> {
    return test.step(`wait for ${opts.expectedRows} run(s) to complete`, async () => {
      let failures: string[] = [];
      await expect
        .poll(
          async () => {
            const texts = await this.outputCells().allInnerTexts();
            failures = texts.filter((t) => RUN_ERROR_TEXT.test(t));
            if (failures.length > 0) return true;
            return (
              texts.length >= opts.expectedRows &&
              texts.every(hasProducedOutput)
            );
          },
          { timeout: opts.timeoutMs ?? 120_000, intervals: [1000, 2000, 3000] },
        )
        .toBe(true);

      if (failures.length > 0) {
        throw new Error(
          `Playground run failed in ${failures.length} cell(s): ${failures[0].trim()}`,
        );
      }
    });
  }

  /**
   * Output cells that have produced content — one per dataset row *per variant*, so this
   * exceeds the row count whenever more than one variant is configured.
   */
  async countCompletedOutputCells(): Promise<number> {
    return (await this.outputCells().allInnerTexts()).filter(hasProducedOutput).length;
  }

  /**
   * Read the "<N>% pass rate" badge from the Prompt A column header.
   * Returns null if no run has completed yet.
   */
  async passRateText(): Promise<string | null> {
    const badge = this.resultsTable().getByText(/^\d+% pass rate$/);
    if ((await badge.count()) === 0) return null;
    return (await badge.first().textContent()) ?? null;
  }

  /** Confirm the suite/dataset pill is showing with the expected entity name. */
  loadedSourcePill(): Locator {
    return this.page.getByTestId('playground-loaded-source-pill');
  }

  // ── metrics picker ──────────────────────────────────────────────────────
  //
  // The Metrics control only exists once a DATASET is loaded (test suites do
  // not render it), and it carries no testid: `MetricSelector`'s
  // `PopoverTrigger asChild` wraps a bare `div tabIndex={0}`, so it is neither a
  // `<button>` nor named — `getByRole('button', { name: /Metrics/ })` finds
  // nothing. It does sit inside `playground-loaded-source-pill`, which is
  // stamped, and Radix marks the trigger `aria-haspopup="dialog"`; the pill's
  // only other controls are the dataset-version combobox and the "Clear
  // selection" button, neither of which opens a dialog. That pair is the
  // stablest description available. A `data-testid` on the trigger would be
  // better, but these specs run against a deployed Opik, so an attribute added
  // alongside them would not exist in the version under test.

  /** The Metrics popover trigger, inside the loaded-dataset pill. */
  metricsPickerTrigger(): Locator {
    return this.loadedSourcePill().locator('[aria-haspopup="dialog"]');
  }

  /**
   * The open Metrics popover.
   *
   * Radix renders `PopoverContent` portalled with `role="dialog"`, so it cannot
   * be scoped through the pill. It is identified by the select-all summary row
   * it owns — the only `"<n> of <m> selected"` text on the page — rather than by
   * being "the open dialog", which would also match the add/edit rule dialog
   * this same component mounts.
   */
  metricsPopover(): Locator {
    return this.page
      .getByRole('dialog')
      .filter({ has: this.page.getByText(/^\d+ of \d+ selected$/) });
  }

  /**
   * Open the Metrics popover if it is not already open.
   *
   * **The popover does not stay open.** On 2.2.66 it closes on its own roughly
   * a second after opening, with no interaction at all — measured on staging by
   * polling its presence once a second: present at t=0, gone at t=1 and every
   * second after. It reopens cleanly and keeps its state, and one interaction
   * comfortably fits inside the window, which is why this is usable at all.
   *
   * So every reader and every gesture below reopens first rather than assuming
   * the popover it saw a moment ago is still there. That is a workaround for a
   * product behaviour, not for a flaky locator — and it is deliberately NOT
   * hidden inside a blanket retry of the assertions, which would let a genuine
   * regression in what the picker SHOWS retry itself into passing.
   */
  async ensureMetricsPickerOpen(): Promise<void> {
    if ((await this.metricsPopover().count()) > 0) return;
    await this.metricsPickerTrigger().click();
    await this.metricsPopover().waitFor({ state: 'visible' });
  }

  /**
   * One rule row in the Metrics popover, addressed by the rule's name.
   *
   * Matched anchored and exact: the namespaced rule names in these specs share
   * a long prefix, so a substring filter would match every sibling — and
   * `-pg-picked` would also match `-pg-picked-2`.
   */
  metricRow(ruleName: string): Locator {
    return this.metricsPopover()
      .locator('div')
      .filter({ has: this.page.getByRole('checkbox') })
      .filter({ hasText: new RegExp(`^\\s*${escapeForRegExp(ruleName)}\\s*$`) });
  }

  /**
   * The picker's whole visible state, captured in a single DOM evaluation.
   *
   * One atomic read rather than an assertion per control, because the popover
   * can vanish between two of them (see `ensureMetricsPickerOpen`): a sequence
   * of `expect(locator)` calls would be reading a surface that is disappearing
   * underneath them, and each retry would re-open the popover and re-measure
   * from a different moment. Everything a caller asserts therefore comes from
   * the same instant.
   *
   * Rows are found from their checkboxes rather than by class, and the
   * select-all footer row is excluded by its summary text — it carries a
   * checkbox too, but it is not a rule.
   */
  async readMetricsPicker(): Promise<MetricsPickerState> {
    return test.step('read the Metrics picker state', async () => {
      await this.ensureMetricsPickerOpen();
      return this.metricsPopover().evaluate((root): MetricsPickerState => {
        const SUMMARY = /^\s*\d+ of \d+ selected\s*$/;
        const rules: MetricsPickerState['rules'] = [];
        let summary = '';

        for (const checkbox of Array.from(root.querySelectorAll('[role="checkbox"]'))) {
          // Walk out to the first ancestor that carries text: for an ordinary
          // rule that is the row itself, and for an always-run one it is the
          // row above the tooltip <span> its checkbox is wrapped in.
          let row: Element | null = checkbox;
          while (row && !(row.textContent ?? '').trim()) row = row.parentElement;
          if (!row) continue;

          const text = (row.textContent ?? '').trim();
          if (SUMMARY.test(text)) {
            summary = text;
            continue;
          }
          rules.push({
            name: text,
            checked: checkbox.getAttribute('aria-checked') === 'true',
            locked:
              (checkbox as HTMLButtonElement).disabled === true ||
              checkbox.hasAttribute('data-disabled'),
          });
        }
        return { summary, rules };
      });
    });
  }

  /**
   * Tick or untick a rule, and confirm the picker agrees afterwards.
   *
   * The whole row is the click target — `MetricSelector` puts `onClick` on the
   * row div, not on the checkbox — so clicking the checkbox would depend on the
   * event reaching the row by propagation rather than by contract.
   *
   * Retried because the popover may close mid-gesture, and made IDEMPOTENT so
   * retrying is safe: each attempt re-reads the state first and returns if the
   * rule already holds the wanted value. Without that guard a click that landed
   * but could not be confirmed would be replayed, toggling the rule back.
   *
   * A rule that will not move is a failure, not something to retry away: the
   * `toPass` window is what absorbs the popover closing, and a locked rule
   * simply never reaches the wanted state, so this fails with the last
   * comparison rather than silently continuing.
   */
  async setMetricPicked(ruleName: string, picked: boolean): Promise<void> {
    return test.step(`${picked ? 'pick' : 'unpick'} metric "${ruleName}"`, async () => {
      await expect(async () => {
        const before = await this.readMetricsPicker();
        const current = before.rules.find((rule) => rule.name === ruleName);
        expect(current, `the picker lists exactly one rule named "${ruleName}"`).toBeDefined();
        if (current!.checked === picked) return;

        await this.ensureMetricsPickerOpen();
        await this.metricRow(ruleName).click({ timeout: 2_000 });

        const after = await this.readMetricsPicker();
        expect(
          after.rules.find((rule) => rule.name === ruleName)?.checked,
          `"${ruleName}" is ${picked ? 'ticked' : 'unticked'} after clicking its row`,
        ).toBe(picked);
      }).toPass({ timeout: 45_000, intervals: [250, 500, 1000] });
    });
  }


  /**
   * One-shot helper for provider-sanity tests: pick a model, type a single user
   * prompt, run inline (no dataset/suite), and read the result.
   *
   * Returns the output text and an isError flag. The Playground's inline-run
   * mode writes results to the right side of each variant card.
   */
  async runSimplePromptAndAwaitResponse(args: RunSimplePromptArgs): Promise<RunSimplePromptResult> {
    return test.step('run simple prompt and await response', async () => {
      await this.setModelForVariant(0, args.modelDisplayName);
      const messages = this.variantMessages(0);
      await this.fillMessageBody(messages.first(), args.prompt);

      // Use the top-right Run button (playground-run-button testid) for inline runs.
      await this.runButton().click();

      const timeoutMs = args.timeoutMs ?? 60_000;
      // After clicking Run, the variant card shows the model's response inline.
      // Wait for any non-empty text that wasn't there before, scoped to the
      // variant card's bottom half (output area).
      const errorIndicator = this.variantCard(0).getByText(/error|failed/i);
      await expect
        .poll(
          async () => {
            // Detect completion via the "No runs yet" empty state disappearing OR
            // the page rendering an error.
            const noRunsYet = await this.page.getByText('No runs yet').count();
            const errored = (await errorIndicator.count()) > 0;
            return noRunsYet === 0 || errored;
          },
          { timeout: timeoutMs, intervals: [500, 1000, 2000] },
        )
        .toBeTruthy();

      // Grab the rendered response text. The Playground emits output to a region
      // that follows the variant card. We use the variant card root and trim its
      // textContent — this includes the model picker label etc., but a non-empty
      // result string is enough for sanity assertion.
      const cardText = ((await this.variantCard(0).textContent()) ?? '').trim();
      const isError = (await errorIndicator.count()) > 0;
      return { outputText: cardText, isError };
    });
  }

  /** Set the model for a variant — public wrapper for setModelForVariant. */
  async selectModel(index: number, modelDisplayName: string): Promise<void> {
    return test.step(`select model "${modelDisplayName}" for variant ${index}`, async () => {
      await this.setModelForVariant(index, modelDisplayName);
    });
  }

  /**
   * Click Run (free mode) and wait for the "No runs yet" placeholder to disappear.
   * The prompt content must already be loaded — this does NOT fill a message body.
   */
  async runFreeMode(timeoutMs = 120_000): Promise<void> {
    return test.step('run prompt (free mode)', async () => {
      await this.runButton().click();
      const errorText = this.page.getByText(/error|failed/i);
      await expect
        .poll(
          async () => {
            const noRunsYet = await this.page.getByText('No runs yet').count();
            const errored = (await errorText.count()) > 0;
            return noRunsYet === 0 || errored;
          },
          { timeout: timeoutMs, intervals: [500, 1000, 2000] },
        )
        .toBeTruthy();
    });
  }

  /**
   * Open the prompt library menu in the first variant card, hover the named prompt
   * to reveal the version submenu, and click the specified version label (e.g. "v1").
   */
  private async navigateLibraryMenuToVersion(promptName: string, versionLabel: string): Promise<void> {
    const menu = this.page.getByTestId('prompt-library-menu');
    await menu.waitFor({ state: 'visible' });

    await menu.getByPlaceholder('Search').fill(promptName);

    const promptRow = menu.getByRole('button').filter({ hasText: promptName }).first();
    await promptRow.waitFor({ state: 'visible' });
    await promptRow.hover();

    const versionSubmenu = this.page.getByTestId('prompt-versions-submenu');
    await versionSubmenu.waitFor({ state: 'visible' });

    await versionSubmenu
      .getByRole('button')
      .filter({ has: this.page.getByText(versionLabel, { exact: true }) })
      .first()
      .click();
  }

  async loadPromptVersionFromLibrary(promptName: string, versionLabel: string): Promise<void> {
    return test.step(`load prompt "${promptName}" version "${versionLabel}" from library`, async () => {
      // The button is in a div that only expands on group-hover; hover the card first.
      await this.variantCard(0).hover();
      await this.variantCard(0).getByTestId('load-prompt-button').click();

      await this.navigateLibraryMenuToVersion(promptName, versionLabel);
    });
  }

  /**
   * Open the text-prompt library menu in the first message row of variant 0,
   * hover the named prompt to reveal the version submenu, and click the specified
   * version label (e.g. "v1"). The button lives inside the message-row actions
   * area which is hidden until the row is hovered.
   */
  async loadTextPromptVersionFromLibrary(promptName: string, versionLabel: string): Promise<void> {
    return test.step(`load text prompt "${promptName}" version "${versionLabel}" from message-row library`, async () => {
      const messageRow = this.variantMessages(0).first();
      await messageRow.hover();
      await messageRow.getByTestId('load-text-prompt-button').click();

      await this.navigateLibraryMenuToVersion(promptName, versionLabel);
    });
  }

  /** Assert the LoadedPromptDisplay in variant 0 shows the given prompt name and version label. */
  async waitForLoadedPromptVersion(promptName: string, versionLabel: string): Promise<void> {
    return test.step(`wait for prompt "${promptName}" at version "${versionLabel}" to be loaded`, async () => {
      const card = this.variantCard(0);
      // For text prompts the loaded-prompt chip is inside the message-row actions
      // area which is only visible on hover (invisible group-hover:visible).
      // Hovering the first message row reveals it without affecting chat-prompt cards.
      await this.variantMessages(0).first().hover();
      await expect(card.getByText(promptName)).toBeVisible();
      await expect(card.getByText(versionLabel, { exact: true })).toBeVisible();
    });
  }

  /** Edit the content of the first message in variant 0 directly in the Playground editor. */
  async editFirstMessage(newContent: string): Promise<void> {
    return test.step('edit first message in Playground', async () => {
      const editor = this.variantMessages(0).first().locator('.cm-content').first();
      await editor.click();
      await editor.fill(newContent);
    });
  }

  private async submitSaveDialog(promptName?: string): Promise<void> {
    const dialog = this.page.getByRole('dialog', { name: 'Save to prompt library' });
    await dialog.waitFor({ state: 'visible' });
    if (promptName) {
      await dialog.getByLabel('Name').fill(promptName);
    }
    await dialog.getByRole('button', { name: 'Save to library' }).click();
    await dialog.waitFor({ state: 'hidden' });
  }

  /**
   * Click the Save button (disk icon) in the first message row of variant 0 and submit the
   * "Save to prompt library" dialog in "Update existing" mode (text prompts).
   * Assumes a text prompt is already loaded so the dialog defaults to update mode.
   */
  async saveTextPromptToLibrary(): Promise<void> {
    return test.step('save text prompt to library from Playground', async () => {
      const messageRow = this.variantMessages(0).first();
      await messageRow.hover();
      await messageRow.getByTestId('save-text-prompt-button').click();
      await this.submitSaveDialog();
    });
  }

  /**
   * Click the Save button in the first message row of variant 0 and submit the
   * "Save to prompt library" dialog as a new text prompt.
   * Fills the given name and clicks "Save to library".
   */
  async saveNewTextPromptToLibrary(promptName: string): Promise<void> {
    return test.step(`save new text prompt "${promptName}" to library from Playground`, async () => {
      const messageRow = this.variantMessages(0).first();
      await messageRow.hover();
      await messageRow.getByTestId('save-text-prompt-button').click();
      await this.submitSaveDialog(promptName);
    });
  }

  /**
   * Click the Save button in variant 0 and submit the "Save to prompt library"
   * dialog in "Save as new" mode — for prompts NOT imported from the library.
   * Fills the given name and clicks "Save to library".
   */
  async saveNewChatPromptToLibrary(promptName: string): Promise<void> {
    return test.step(`save new chat prompt "${promptName}" to library from Playground`, async () => {
      await this.variantCard(0).hover();
      await this.page.getByTestId('playground-save-prompt-button').click();
      await this.submitSaveDialog(promptName);
    });
  }

  /**
   * Click the Save button (disk icon) in variant 0 and submit the
   * "Save to prompt library" dialog in "Update existing" mode.
   * Assumes a chat prompt is already loaded so the dialog defaults to update mode.
   */
  async savePromptToLibrary(): Promise<void> {
    return test.step('save prompt to library from Playground', async () => {
      await this.variantCard(0).hover();
      await this.page.getByTestId('playground-save-prompt-button').click();
      await this.submitSaveDialog();
    });
  }

  /** Click the "Go to logs" icon button to open the Playground logs sidebar. */
  async openLogsPanel(): Promise<void> {
    return test.step('open Playground logs panel', async () => {
      await this.page.getByTestId('playground-logs-sidebar-button').click();
    });
  }

  /** Set a single model-config option (e.g., temperature, max_tokens) on a variant. */
  async setVariantOption(index: number, optionName: string, value: number | string): Promise<void> {
    return test.step(`set variant ${index} option "${optionName}" to ${value}`, async () => {
      // The options pane is collapsed by default; expand it first. Implementation
      // is exploratory — Phase 4 will refine if discovery reveals a different shape.
      const card = this.variantCard(index);
      // Try to open the options pane via the gear/settings affordance.
      const settingsButton = card.getByRole('button', { name: /settings|options/i });
      if ((await settingsButton.count()) > 0) {
        await settingsButton.first().click();
      }
      // Fill the named input. Provider-sanity tests use a small whitelist of
      // option names that map to spinbutton inputs.
      const input = card.getByRole('spinbutton', { name: new RegExp(`^${optionName}$`, 'i') });
      if ((await input.count()) > 0) {
        await input.first().fill(String(value));
      }
    });
  }

  /**
   * Whether the model picker offers a model, by display name. Leaves the picker
   * closed either way.
   *
   * The option list comes from the deployment's own model registry and from the
   * provider keys configured on the workspace, so a model this suite names may
   * simply not be there. Probing lets a spec skip on that rather than spend
   * `setModelForVariant`'s retry budget failing to click an option that will
   * never appear.
   */
  async isModelOffered(index: number, modelDisplayName: string): Promise<boolean> {
    return test.step(`check whether "${modelDisplayName}" is offered`, async () => {
      const listbox = this.page.getByRole('listbox');
      await expect(async () => {
        await this.modelPicker(index).click();
        await expect(listbox).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });

      await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
      const offered = await listbox
        .getByRole('option', { name: modelDisplayName, exact: true })
        .first()
        .waitFor({ state: 'visible', timeout: 5_000 })
        .then(() => true)
        .catch(() => false);

      await this.page.keyboard.press('Escape');
      await expect(listbox).toBeHidden();
      return offered;
    });
  }

  /** Open a variant's model-parameters popover and wait for it to render. */
  async openModelParameters(index: number): Promise<void> {
    return test.step(`open model parameters for variant ${index}`, async () => {
      await this.modelParametersTrigger(index).click();
      await this.modelParametersPanel().waitFor({ state: 'visible' });
    });
  }

  /** Close the model-parameters popover, and wait until it is really gone. */
  async closeModelParameters(): Promise<void> {
    return test.step('close model parameters', async () => {
      await this.page.keyboard.press('Escape');
      await this.modelParametersPanel().waitFor({ state: 'hidden' });
    });
  }

  /**
   * The open model-parameters popover.
   *
   * One is open at a time — it is a `DropdownMenu`, and Radix unmounts the
   * content of a closed one — so this needs no variant scoping.
   */
  modelParametersPanel(): Locator {
    return this.page.getByRole('menu');
  }

  /**
   * The Sampling choice's two options. Anthropic takes Temperature or Top P and
   * never both, so which one carries `data-state="on"` is the panel's claim
   * about what the request will contain.
   */
  samplingOption(label: 'Temperature' | 'Top P'): Locator {
    return this.modelParametersPanel().getByRole('radio', { name: label, exact: true });
  }

  /** Every Sampling option currently selected — asserted to be exactly one. */
  selectedSamplingOptions(): Locator {
    return this.modelParametersPanel().locator('[role="radio"][data-state="on"]');
  }

  /**
   * The number input of a named slider control, e.g. `temperature` or `topP`.
   *
   * Presence is the assertion, not just the value: the panel renders the live
   * half of the sampling pair and unmounts the other, so a control that is
   * merely dimmed — or two that are both mounted — is the regression.
   */
  sliderInput(controlId: string): Locator {
    return this.page.getByTestId(`${controlId}-input`);
  }

  /** The Thinking effort dropdown. Its text is the effort the panel claims. */
  thinkingEffortSelect(): Locator {
    return this.modelParametersPanel().getByLabel('Thinking effort');
  }

  /** Pick a Thinking effort by its displayed label. */
  async selectThinkingEffort(label: string): Promise<void> {
    return test.step(`select thinking effort "${label}"`, async () => {
      await this.thinkingEffortSelect().click();
      await this.page.getByRole('option', { name: label, exact: true }).click();
      await expect(this.thinkingEffortSelect()).toHaveText(label);
    });
  }

  /** Type a prompt into variant 0's first message row. */
  async fillFirstMessage(text: string): Promise<void> {
    return test.step('fill the first message of variant 0', async () => {
      await this.fillMessageBody(this.variantMessages(0).first(), text);
    });
  }

  /** Click Run (free mode) without waiting for the completion to come back. */
  async clickRun(): Promise<void> {
    return test.step('click Run', async () => {
      await this.runButton().click();
    });
  }

  // ── private helpers ─────────────────────────────────────────────────────

  /**
   * The gear button that opens a variant's model parameters.
   *
   * Anchored to the model picker rather than addressed directly: the trigger
   * carries no testid and no accessible name (its tooltip is a Radix
   * `TooltipContent`, not an `aria-label`), and the variant card holds other
   * `aria-haspopup="menu"` buttons — every message row's role selector is one.
   * "The menu button immediately after the model picker" is the one stable
   * description available. A `data-testid` on `PromptModelConfigs`' trigger
   * would be better, but these specs run against a deployed Opik, where an
   * attribute added alongside them would not exist in the version under test.
   */
  private modelParametersTrigger(index: number): Locator {
    return this.variantCard(index).locator(
      'button:has(> [data-testid="select-a-llm-model"]) + button[aria-haspopup="menu"]',
    );
  }

  private runExperimentTriggerButton(): Locator {
    return this.page
      .getByTestId('playground-run-button')
      .and(this.page.locator('[data-mode="experiment-trigger"]'));
  }

  private runButton(): Locator {
    // The actual run button is whichever is currently rendered: experiment-trigger,
    // run, or re-run. They all share the playground-run-button testid; this method
    // returns the live "run" or "re-run" variant.
    return this.page
      .getByTestId('playground-run-button')
      .and(this.page.locator('[data-mode="run"], [data-mode="re-run"]'));
  }

  /**
   * The Playground's own page scroller. Row virtualization measures the table's offset
   * inside this element, so scrolling for virtualization assertions must drive it rather
   * than the window.
   */
  scrollContainer(): Locator {
    return this.page.getByTestId('playground-scroll-container');
  }

  /**
   * The output grid is two side-by-side `StickyScrollTable`s — dataset variables on the
   * left, prompt outputs on the right — each split into a sticky header half and a
   * scrollable body half. Both bodies render the same rows, so virtualization assertions
   * must name one surface rather than querying the grid as a whole.
   */
  variablesPanel(half: 'header' | 'body'): Locator {
    return this.page.getByTestId(`playground-variables-table-${half}`);
  }

  outputsPanel(half: 'header' | 'body'): Locator {
    return this.page.getByTestId(`playground-outputs-table-${half}`);
  }

  /** Scroll the Playground page body to a ratio of its scrollable height (0 = top, 1 = bottom). */
  async scrollResultsTo(ratio: number): Promise<void> {
    return test.step(`scroll results to ${ratio} of the page height`, async () => {
      await this.scrollContainer().evaluate((el, r) => {
        el.scrollTop = (el.scrollHeight - el.clientHeight) * r;
      }, ratio);
      await this.settle();
    });
  }

  /**
   * Row ids currently mounted in the outputs body. The grid does not set `getRowId`, so
   * these are TanStack's positional ids within the page, not dataset item ids — enough to
   * tell one mounted window from another, which is all the virtualization assertions need.
   * Callers should not assume a dataset ordering: the grid renders items newest-first.
   */
  async mountedRowIds(): Promise<string[]> {
    return test.step('read mounted row ids', async () => {
      return this.outputsPanel('body')
        .locator('tbody:not(.comet-table-body-loading-overlay) tr[data-row-id]')
        .evaluateAll((rows) =>
          rows.map((r) => r.getAttribute('data-row-id')).filter((v): v is string => Boolean(v)),
        );
    });
  }

  /**
   * Whether a gap sits between the top of the scroller's viewport and the first mounted row,
   * once the grid itself has been scrolled past. That is what a stale table offset looks
   * like: the virtual window is positioned from the wrong origin, so the rows it renders
   * land below where the scroll position says they should.
   */
  async hasBlankBandAboveRows(): Promise<boolean> {
    return test.step('check for a blank band above the mounted rows', async () => {
      const viewportTop = await this.scrollContainer().evaluate(
        (el) => el.getBoundingClientRect().top,
      );

      return this.outputsPanel('body').evaluate((body, top) => {
        const wrapper = body.querySelector('[data-table-wrapper]');
        const firstRow = body.querySelector('tbody tr[data-row-id]');
        if (!(wrapper instanceof HTMLElement) || !(firstRow instanceof HTMLElement)) return false;

        // Only meaningful once the grid's own top has scrolled above the viewport.
        if (wrapper.getBoundingClientRect().top >= top) return false;

        return firstRow.getBoundingClientRect().top > top + 1;
      }, viewportTop);
    });
  }

  /**
   * Drive a horizontal scroll on one panel's body half and report what it actually reached.
   * Returns the achieved `scrollLeft`, so a caller can fail loudly when the panel is too
   * narrow to overflow instead of silently comparing two zeroes.
   */
  async scrollPanelHorizontallyTo(
    panel: 'variables' | 'outputs',
    offset: number,
  ): Promise<number> {
    return test.step(`scroll the ${panel} panel horizontally to ${offset}px`, async () => {
      const body = panel === 'variables' ? this.variablesPanel('body') : this.outputsPanel('body');
      const reached = await body.evaluate((el, x) => {
        el.scrollLeft = x;
        return el.scrollLeft;
      }, offset);
      await this.settle();
      return reached;
    });
  }

  /** The `scrollLeft` of a panel's sticky header half and its body half. */
  async panelScrollOffsets(
    panel: 'variables' | 'outputs',
  ): Promise<{ header: number; body: number }> {
    return test.step(`read ${panel} panel header/body scroll offsets`, async () => {
      const half = (h: 'header' | 'body') =>
        panel === 'variables' ? this.variablesPanel(h) : this.outputsPanel(h);
      const [header, body] = await Promise.all([
        half('header').evaluate((el) => el.scrollLeft),
        half('body').evaluate((el) => el.scrollLeft),
      ]);
      return { header, body };
    });
  }

  /**
   * Choose a "rows per page" value from the results pagination, then wait for the
   * replacement body. Changing the size refetches, and `mountedRowIds()` deliberately
   * ignores the loading tbody, so returning early would let a caller assert against an
   * empty or stale window.
   */
  async setPageSize(size: number): Promise<void> {
    return test.step(`set page size to ${size}`, async () => {
      await this.pageSizeTrigger().click();
      await this.page.getByRole('menuitemcheckbox', { name: String(size), exact: true }).click();

      await expect(this.pageSizeTrigger()).toHaveText(String(size));
      await expect(
        this.outputsPanel('body').locator(
          'tbody:not(.comet-table-body-loading-overlay) tr[data-row-id]',
        ),
      ).not.toHaveCount(0);
      await this.settle();
    });
  }

  /** The current "rows per page" value shown by the pagination trigger. */
  async pageSize(): Promise<number> {
    return test.step('read the current page size', async () => {
      return Number((await this.pageSizeTrigger().innerText()).trim());
    });
  }

  /**
   * Scrollable height of the page scroller. Under virtualization this tracks the row count
   * the virtualizer is sizing for, so it moves when the page size changes even though the
   * mounted window stays the same size.
   */
  async resultsScrollHeight(): Promise<number> {
    return test.step('read the results scroll height', async () => {
      return this.scrollContainer().evaluate((el) => el.scrollHeight);
    });
  }

  private pageSizeTrigger(): Locator {
    return this.resultsTable()
      .locator('..')
      .getByRole('button', { name: /^(10|50|100|200|500|1000)$/ });
  }

  /** Two frames: one for the scroll event to dispatch, one for the virtualizer to re-render. */
  private async settle(): Promise<void> {
    await this.page.evaluate(
      () => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))),
    );
  }

  private resultsTable(): Locator {
    return this.page.getByTestId('playground-results-table');
  }

  /**
   * Output cells of the experiment-results table — one per dataset row, per
   * variant. The table is the shared `DataTable` split into sticky-header and
   * scrollable-body halves, so body cells carry `data-cell-id="<rowId>_<colId>"`
   * and the variant columns are `output-<promptId>`.
   */
  private outputCells(): Locator {
    // The `:not(.comet-table-body-loading-overlay)` matters: switching sources keeps the
    // previous dataset's rows on screen (`keepPreviousData`) while the new items load, and
    // those rows are idle, so counting them would report ready for the wrong dataset.
    return this.resultsTable().locator(
      'tbody:not(.comet-table-body-loading-overlay) tr[data-row-id] td[data-cell-id*="_output-"]',
    );
  }

  /** Output cells whose row has not been run yet. */
  private idleOutputCells(): Locator {
    return this.outputCells().filter({ hasText: IDLE_CELL_TEXT });
  }

  private variantCard(index: number): Locator {
    return this.page.locator(
      `[data-testid="playground-variant-card"][data-variant-index="${index}"]`,
    );
  }

  private variantMessages(index: number): Locator {
    return this.variantCard(index).getByTestId('playground-message-row');
  }

  private modelPicker(index: number): Locator {
    return this.page.getByTestId('select-a-llm-model').nth(index);
  }

  private async setModelForVariant(index: number, modelDisplayName: string): Promise<void> {
    const listbox = this.page.getByRole('listbox');
    // The trigger occasionally swallows the first click as a hover (surfacing a
    // tooltip instead of opening the popover), so retry the click until the
    // listbox actually opens rather than firing once and waiting.
    await expect(async () => {
      await this.modelPicker(index).click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });

    // The option list remounts when /llm/models or /provider-keys resolves, and
    // the suite configures a provider immediately before opening the Playground —
    // so the dropdown routinely opens inside that refetch window and options are
    // detached mid-click. Re-filter and re-click until the popover actually
    // closes, which is the only reliable signal the selection registered.
    await expect(async () => {
      await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
      const option = listbox.getByRole('option', { name: modelDisplayName, exact: true });
      await expect(option.first()).toBeVisible({ timeout: 2_000 });
      await option.first().click({ timeout: 2_000 });
      await expect(listbox).toBeHidden({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
  }

  private async fillMessageBody(messageRow: Locator, text: string): Promise<void> {
    // CodeMirror editor: contenteditable div under `.cm-content`. Playwright's
    // fill() on the role=textbox works for simple strings; pressSequentially is
    // safer for multi-line / templated input.
    const editor = messageRow.locator('.cm-content').first();
    await editor.click();
    await editor.fill(text);
  }
}
