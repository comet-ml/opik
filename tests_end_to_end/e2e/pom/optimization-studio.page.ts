import type { Page, Locator } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import { TraceLogsSidebarPage } from './trace-logs-sidebar.page';

export type OptimizerName = 'GEPA optimizer' | 'Hierarchical Reflective';

/** Escape a literal so it can be embedded in a RegExp. Trial labels are plain
 *  today ("Baseline", "Trial #3"), but a POM helper that takes arbitrary text
 *  should not change meaning if that text ever grows a metacharacter. */
const escapeForRegExp = (literal: string): string =>
  literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

export interface StudioRunConfig {
  /** Name of a dataset already associated with the project (shown in the picker). */
  datasetName: string;
  /** User-message prompt content; keep the dataset variable, e.g. `{{text}}`. */
  prompt: string;
  /**
   * System-message content — the instruction the optimizer rewrites. Required
   * because the form seeds a system card and rejects submit while it is empty;
   * see `setSystemPrompt`.
   */
  systemPrompt: string;
  /** Model display name as shown in the picker (e.g. "Claude Haiku 4.5"). */
  modelDisplayName: string;
  /** Equals-metric reference key — the dataset field holding the gold label. */
  referenceKey: string;
  /** Optional optimizer; defaults to the form's GEPA. */
  optimizer?: OptimizerName;
}

/**
 * Drives the v2 Optimization Studio: the new-run form
 * (`/projects/{id}/optimizations/new`) and the read side of the run-detail page
 * (`/projects/{id}/optimizations/{optimizationId}`), including the Trials tab
 * and the Best-trial-configuration panel.
 *
 * Only the shared LLM message editor carries data-testids; everything else is
 * addressed by role/label/text because this POM must run against the deployed
 * image as it renders, without adding product testids. Selectors are kept
 * tolerant of version drift where the UI has renamed things (e.g. the dataset
 * picker is "Select item source" on newer builds, "Select a dataset" on 2.1.x).
 */
export class OptimizationStudioPage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async gotoNew(): Promise<void> {
    return test.step('Open the Optimization Studio new-run panel', async () => {
      const env = loadEnvConfig();
      // The new-run flow is a side panel over the runs list
      // (`/optimizations?new=true`). `/optimizations/new` is kept as a redirect
      // to it, so navigating the legacy path also covers that back-compat route.
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations/new`,
      );
      await this.newRunPanel().waitFor();
    });
  }

  /** Assert the form's core sections render with the expected defaults. */
  async assertFormRenders(opts: { optimizer?: string; metric?: string } = {}): Promise<void> {
    return test.step('Assert the studio form renders its sections', async () => {
      // A new run seeds two message cards — instructions in the system message
      // (the only role the run makes optimizable) and template variables in the
      // user message. Assert both, so a regression back to a single seeded card
      // fails here rather than surfacing as a stuck submit later.
      await expect(this.promptEditor('system')).toBeVisible();
      await expect(this.promptEditor('user')).toBeVisible();
      await expect(this.modelCombobox()).toBeVisible();
      await expect(this.datasetPickerButton()).toBeVisible();
      await expect(
        this.page.locator('[role="combobox"]', { hasText: opts.optimizer ?? 'GEPA optimizer' }),
      ).toBeVisible();
      await expect(this.page.getByRole('combobox', { name: 'Metric' })).toContainText(
        opts.metric ?? 'Equals',
      );
      await expect(this.optimizeButton()).toBeDisabled();
    });
  }

  async selectDataset(name: string): Promise<void> {
    return test.step(`Select dataset "${name}"`, async () => {
      await this.datasetPickerButton().click();
      const dialog = this.page.getByRole('dialog');
      // Scope to the dialog: the run panel behind it also renders a
      // `search-input`, so an unscoped testid matches two elements.
      await dialog.getByTestId('search-input').fill(name);
      await dialog.getByText(name, { exact: true }).click();
      await expect(this.page.getByRole('button', { name })).toBeVisible();
    });
  }

  async setUserPrompt(content: string): Promise<void> {
    return test.step('Type the user-message prompt', async () => {
      await this.fillMessage('user', content);
    });
  }

  /**
   * Fill the system message. The form seeds an empty system card alongside the
   * user one and its schema requires every message to be non-empty, so a run
   * that leaves this blank never submits — the Optimize button goes enabled
   * (its disabled gate doesn't cover message content) but validation rejects
   * the submit, so the panel simply stays open.
   */
  async setSystemPrompt(content: string): Promise<void> {
    return test.step('Type the system-message prompt', async () => {
      await this.fillMessage('system', content);
    });
  }

  private async fillMessage(role: 'system' | 'user', content: string): Promise<void> {
    const editor = this.promptEditor(role);
    await editor.click();
    await editor.fill(content);
    await expect(editor).toContainText(content);
  }

  async selectModel(displayName: string): Promise<void> {
    return test.step(`Select model "${displayName}"`, async () => {
      const search = this.page.getByRole('textbox', { name: 'Search model' });
      // The trigger occasionally swallows the first click as a hover, so retry
      // until the search box (i.e. the open popover) actually appears.
      await expect(async () => {
        await this.modelCombobox().click();
        await expect(search).toBeVisible({ timeout: 2_000 });
      }).toPass({ timeout: 15_000 });

      // The option list remounts when the model/provider-key queries resolve, so
      // an option can detach between resolving and being clicked. Re-filter and
      // re-click until the combobox reflects the selection.
      await expect(async () => {
        await search.fill(displayName);
        const option = this.page.getByRole('option', { name: displayName });
        await expect(option.first()).toBeVisible({ timeout: 2_000 });
        await option.first().click({ timeout: 2_000 });
        await expect(this.modelCombobox()).toContainText(displayName, { timeout: 2_000 });
      }).toPass({ timeout: 30_000 });
    });
  }

  async selectOptimizer(optimizer: OptimizerName): Promise<void> {
    return test.step(`Select optimizer "${optimizer}"`, async () => {
      const combo = this.page
        .locator('[role="combobox"]')
        .filter({ hasText: /GEPA optimizer|Hierarchical Reflective/ });
      await combo.click();
      await this.page.getByRole('option', { name: optimizer }).click();
      await expect(combo).toContainText(optimizer);
    });
  }

  async setReferenceKey(key: string): Promise<void> {
    return test.step(`Set Equals reference key to "${key}"`, async () => {
      await this.page.locator('input#reference_key').fill(key);
    });
  }

  /** Click "Optimize prompt" and return the created optimization's id from the URL. */
  async startOptimization(): Promise<string> {
    return test.step('Start the optimization run', async () => {
      await expect(this.optimizeButton()).toBeEnabled();
      await this.optimizeButton().click();
      // The button's disabled gate doesn't cover message content, so an empty
      // message card lets the click through and validation silently rejects the
      // submit — the panel just stays open. Surface that as the actual cause
      // instead of an unexplained navigation timeout.
      try {
        await this.page.waitForURL(/\/optimizations\/[0-9a-f-]+$/);
      } catch (error) {
        const validationError = this.newRunPanel().getByText('Message is required').first();
        if (await validationError.isVisible().catch(() => false)) {
          throw new Error(
            'Submit was rejected: a message card is empty ("Message is required"). ' +
              'The form seeds system + user messages and requires content in both.',
          );
        }
        throw error;
      }
      const match = this.page.url().match(/\/optimizations\/([0-9a-f-]+)$/);
      if (!match) {
        throw new Error(`Could not extract optimization id from URL: ${this.page.url()}`);
      }
      return match[1];
    });
  }

  /** Configure and launch a run in one call; returns the optimization id. */
  async configureAndStart(config: StudioRunConfig): Promise<string> {
    return test.step('Configure and start an optimization run', async () => {
      await this.selectDataset(config.datasetName);
      await this.setSystemPrompt(config.systemPrompt);
      await this.setUserPrompt(config.prompt);
      await this.selectModel(config.modelDisplayName);
      if (config.optimizer && config.optimizer !== 'GEPA optimizer') {
        await this.selectOptimizer(config.optimizer);
      }
      await this.setReferenceKey(config.referenceKey);
      return this.startOptimization();
    });
  }

  async gotoDetail(optimizationId: string): Promise<void> {
    return test.step('Open the optimization detail page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations/${optimizationId}`,
      );
    });
  }

  /** Wait for the detail header status tag to read the given status (case-insensitive). */
  async expectStatus(
    status: 'completed' | 'running' | 'error',
    opts: { timeout?: number } = {},
  ): Promise<void> {
    return test.step(`Expect detail-page status "${status}"`, async () => {
      await expect(
        this.page.getByText(new RegExp(`^${status}$`, 'i')).first(),
        `optimization detail should show status "${status}"`,
      ).toBeVisible({ timeout: opts.timeout ?? 15_000 });
    });
  }

  /** Open the Trials tab and wait for its table to render. */
  async openTrialsTab(): Promise<void> {
    return test.step('Open the Trials tab', async () => {
      await this.page.getByRole('tab', { name: 'Trials' }).click();
      await this.trialsTable().waitFor({ state: 'visible' });
    });
  }

  async trialRowCount(): Promise<number> {
    return test.step('Count trial rows', async () => {
      return this.trialsTable().locator('tbody tr').count();
    });
  }

  /** Assert at least one trial row carries the "Best" status tag. */
  async expectBestTrial(): Promise<void> {
    return test.step('Expect a Best trial row', async () => {
      await expect(
        this.trialsTable().getByText('Best', { exact: true }).first(),
      ).toBeVisible();
    });
  }

  /**
   * Open a trial row by the label its "Trial" column renders — "Baseline" for
   * the run's step-0 candidate, "Trial #N" for the numbered ones. Addressed by
   * label rather than row index because the table's sort order is a product
   * decision this POM should not encode.
   *
   * Returns the trial side panel's locator so callers can scope to it.
   */
  async openTrialByLabel(label: string): Promise<Locator> {
    return test.step(`Open trial "${label}"`, async () => {
      // Match the "Trial #" cell EXACTLY, not the row's text. A substring match
      // on the whole row makes "Trial #1" also select "Trial #10" (and any other
      // row sharing the prefix), and the label can collide with unrelated cell
      // text elsewhere in the row. `TrialNumberCell` renders the label as the
      // full text of the first cell, so an anchored match there is unambiguous.
      const row = this.trialsTable()
        .locator('tbody tr[data-row-id]')
        .filter({
          has: this.page.locator('td:first-child').filter({
            hasText: new RegExp(`^\\s*${escapeForRegExp(label)}\\s*$`),
          }),
        });
      await expect(row, `exactly one trial row labelled "${label}"`).toHaveCount(1);
      await row.click();
      const panel = this.trialSidebar();
      await expect(panel, `trial side panel for "${label}"`).toBeVisible();
      return panel;
    });
  }

  /**
   * Click "Logs" in the open trial side panel and hand back the shared logs
   * overlay. It is a clickable `Tag`, not a button, so it is addressed by text
   * within the panel rather than by role.
   */
  async openTrialLogs(): Promise<TraceLogsSidebarPage> {
    return test.step('Open the trial\'s Logs overlay', async () => {
      await this.trialSidebar().getByText('Logs', { exact: true }).first().click();
      const overlay = new TraceLogsSidebarPage(this.page);
      await overlay.waitForReady();
      return overlay;
    });
  }

  /** The trial side panel. `ResizableSidePanel` renders its `panelId` as a
   *  data-testid, which is the one purpose-built hook on it. */
  private trialSidebar(): Locator {
    return this.page.locator('[data-testid="optimization-trial-sidebar"]');
  }

  /**
   * Assert the run's configuration shows the given algorithm + metric. These
   * render as pills in the detail page's header, which sits outside the tab
   * structure — so scope to `main`, not to the Overview tabpanel.
   */
  async expectBestTrialConfig(opts: { algorithm: string; metric: string }): Promise<void> {
    return test.step('Assert the run configuration pills', async () => {
      const header = this.page.locator('main');
      await expect(header).toContainText(opts.algorithm);
      await expect(header).toContainText(opts.metric);
    });
  }

  /**
   * The new-run side panel. `ResizableSidePanel` renders its `panelId` as a
   * data-testid, so this is the one purpose-built hook on the panel — the
   * header is a plain span ("New optimization run"), not a heading.
   */
  private newRunPanel(): Locator {
    return this.page.locator('[data-testid="new-optimization-run-sidebar"]');
  }

  /**
   * The message editor for a given role. The form seeds one card per message
   * (system + user), and each card renders its own `playground-message-editor`,
   * so an unscoped testid matches every editor on the page. Scope to the panel
   * and to the card's `data-role` — the row testid plus `data-role` is the
   * stable per-message hook, and the same pattern is used by the prompts POMs.
   */
  private promptEditor(role: 'system' | 'user'): Locator {
    return this.newRunPanel()
      .locator(`[data-testid="playground-message-row"][data-role="${role}"]`)
      .getByTestId('playground-message-editor')
      .locator('.cm-content');
  }

  private optimizeButton(): Locator {
    return this.page.getByRole('button', { name: 'Optimize prompt' });
  }

  /**
   * The dataset/source picker button. Newer builds label it "Select item
   * source"; 2.1.x labels it "Select a dataset". Match either, or the button
   * that has become the selected dataset name.
   */
  private datasetPickerButton(): Locator {
    return this.page.getByRole('button', { name: /Select item source|Select a dataset/ });
  }

  private modelCombobox(): Locator {
    // The model picker is the only combobox on the form that renders a provider
    // name; the others show "GEPA optimizer" / "Equals". With no provider key
    // configured it renders its "Select an LLM model" placeholder instead, so
    // match that too — the form must still be assertable in that state.
    return this.page
      .locator('[role="combobox"]')
      .filter({
        hasText:
          /Anthropic|OpenAI|Gemini|openrouter|free|gpt-|claude|GPT|Claude|Select an LLM model/i,
      });
  }

  private trialsTable(): Locator {
    return this.page.locator('main table');
  }
}
