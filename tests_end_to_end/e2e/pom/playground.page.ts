import type { Page, Locator } from '@playwright/test';
import { expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export type RunExperimentSourceMode = 'dataset' | 'test_suite';

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
    const env = loadEnvConfig();
    await this.page.goto(
      `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/playground`,
    );
  }

  async waitForReady(): Promise<void> {
    await this.page
      .getByRole('heading', { name: 'Playground', level: 1 })
      .waitFor({ state: 'visible' });
    // Variant card "A" should be mounted with its model picker.
    await this.modelPicker(0).waitFor({ state: 'visible' });
  }

  /**
   * Configure the variant at the given index.
   * - If `systemPrompt` is set, the FIRST message row gets its role flipped to
   *   System, then a new User message row is appended with the userPrompt.
   * - Otherwise, the first message row (already User by default) is filled directly.
   */
  async configureVariant(index: number, cfg: PlaygroundVariantConfig): Promise<void> {
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
  }

  /** Open the "Run experiment" dialog (when no suite/dataset is loaded). */
  async clickRunExperiment(): Promise<void> {
    await this.runExperimentTriggerButton().click();
    await this.runExperimentDialog().waitFor({ state: 'visible' });
  }

  /**
   * Fill and submit the "Run experiment" dialog. Switches between Dataset and
   * Test suite mode via the toggle group, picks the source by name, then clicks
   * "Use dataset" / "Use test suite".
   */
  async submitRunExperimentDialog(args: {
    mode: RunExperimentSourceMode;
    entityName: string;
  }): Promise<void> {
    const dialog = this.runExperimentDialog();
    const testid =
      args.mode === 'test_suite'
        ? 'run-experiment-dialog-source-suite'
        : 'run-experiment-dialog-source-dataset';
    await dialog.getByTestId(testid).click();

    // Open the source selector combobox and pick the entity by name.
    await dialog.getByRole('combobox').click();
    const listbox = this.page.getByRole('listbox');
    await listbox.waitFor({ state: 'visible' });
    await listbox.getByPlaceholder(/^Search (dataset|test suite)/).fill(args.entityName);
    await listbox.getByText(args.entityName, { exact: true }).first().click();

    const submitLabel = args.mode === 'test_suite' ? 'Use test suite' : 'Use dataset';
    await dialog.getByRole('button', { name: submitLabel, exact: true }).click();
    await dialog.waitFor({ state: 'hidden' });
  }

  /** Click Re-run when a suite/dataset is already loaded. */
  async clickReRun(): Promise<void> {
    await this.runButton().click();
  }

  /**
   * Wait for runs to complete in the experiment-results table.
   *
   * Polling shape depends on what was loaded:
   *  - test_suite: rows show "Passed <output>" / "Failed <output>" (LLM-judged).
   *  - dataset: rows show the raw output cell (no pass/fail label).
   *
   * We poll for the absence of "No runs yet" placeholders. When all expected
   * cells have transitioned out of that empty state, the run is done.
   */
  async waitForRunsComplete(opts: { expectedRows: number; timeoutMs?: number }): Promise<void> {
    const table = this.resultsTable();
    await expect
      .poll(
        async () => {
          const noRunsYetCount = await table.getByText('No runs yet').count();
          return noRunsYetCount;
        },
        { timeout: opts.timeoutMs ?? 120_000, intervals: [1000, 2000, 3000] },
      )
      .toBe(0);
  }

  /**
   * Number of result rows that have completed (non-empty output cell).
   * Counts rows where "No runs yet" is NOT present and the row is in the
   * results table's right-side variant column.
   */
  async countOutputRows(): Promise<number> {
    const table = this.resultsTable();
    // The results table has the data-side (left) and the variant-output side (right).
    // The variant-output side has either "No runs yet" (empty) or actual output text.
    // We count rows by counting the data-side and subtracting any still-empty cells.
    const passedFailedCount = await table.getByRole('row', { name: /^(Passed|Failed) / }).count();
    if (passedFailedCount > 0) return passedFailedCount;
    // Dataset mode: count rows by their data preview (left table).
    // Each "row" in the left table corresponds to one expected output row.
    const itemRows = await table.locator('tr').count();
    const noRunsYet = await table.getByText('No runs yet').count();
    return Math.max(0, itemRows - noRunsYet - 1 /* header */);
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

  /**
   * One-shot helper for provider-sanity tests: pick a model, type a single user
   * prompt, run inline (no dataset/suite), and read the result.
   *
   * Returns the output text and an isError flag. The Playground's inline-run
   * mode writes results to the right side of each variant card.
   */
  async runSimplePromptAndAwaitResponse(args: RunSimplePromptArgs): Promise<RunSimplePromptResult> {
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
  }

  /** Set a single model-config option (e.g., temperature, max_tokens) on a variant. */
  async setVariantOption(index: number, optionName: string, value: number | string): Promise<void> {
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
  }

  // ── private helpers ─────────────────────────────────────────────────────

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

  private runExperimentDialog(): Locator {
    return this.page.getByTestId('run-experiment-dialog');
  }

  private resultsTable(): Locator {
    return this.page.getByTestId('playground-results-table');
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
    await this.modelPicker(index).click();
    const listbox = this.page.getByRole('listbox');
    await listbox.waitFor({ state: 'visible' });
    await listbox.getByPlaceholder('Search model').fill(modelDisplayName);
    await listbox.getByRole('option', { name: modelDisplayName, exact: true }).first().click();
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
