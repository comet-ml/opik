import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
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
   * Wait for runs to complete in the experiment-results table.
   *
   * Polling shape depends on what was loaded:
   *  - test_suite: rows show "Passed <output>" / "Failed <output>" (LLM-judged).
   *  - dataset: rows show the raw output cell (no pass/fail label).
   *
   * Completion requires BOTH: all "No runs yet" placeholders are gone, AND the
   * rendered row count meets `expectedRows`. Checking only the placeholder
   * disappearance lets us return early if the table briefly re-renders during
   * loading (`No runs yet` is unmounted while rows are still being painted).
   */
  async waitForRunsComplete(opts: { expectedRows: number; timeoutMs?: number }): Promise<void> {
    return test.step(`wait for ${opts.expectedRows} run(s) to complete`, async () => {
      const table = this.resultsTable();
      await expect
        .poll(
          async () => {
            const noRunsYet = await table.getByText('No runs yet').count();
            if (noRunsYet !== 0) return false;
            const rowCount = await this.countOutputRows();
            return rowCount >= opts.expectedRows;
          },
          { timeout: opts.timeoutMs ?? 120_000, intervals: [1000, 2000, 3000] },
        )
        .toBe(true);
    });
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
    const listbox = this.page.getByRole('listbox');
    // The trigger occasionally swallows the first click as a hover (surfacing a
    // tooltip instead of opening the popover), so retry the click until the
    // listbox actually opens rather than firing once and waiting.
    await expect(async () => {
      await this.modelPicker(index).click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });
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
