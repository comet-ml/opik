import type { Page, Locator } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export type OptimizerName = 'GEPA optimizer' | 'Hierarchical Reflective';

export interface StudioRunConfig {
  /** Name of a dataset already associated with the project (shown in the picker). */
  datasetName: string;
  /** User-message prompt content; keep the dataset variable, e.g. `{{text}}`. */
  prompt: string;
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
    return test.step('Open the Optimization Studio new-run page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/optimizations/new`,
      );
      await this.page.getByRole('heading', { name: 'Optimize a prompt' }).waitFor();
    });
  }

  /** Assert the form's core sections render with the expected defaults. */
  async assertFormRenders(opts: { optimizer?: string; metric?: string } = {}): Promise<void> {
    return test.step('Assert the studio form renders its sections', async () => {
      await expect(this.promptEditor()).toBeVisible();
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
      await this.page.getByTestId('search-input').fill(name);
      await this.page.getByRole('dialog').getByText(name, { exact: true }).click();
      await expect(this.page.getByRole('button', { name })).toBeVisible();
    });
  }

  async setUserPrompt(content: string): Promise<void> {
    return test.step('Type the user-message prompt', async () => {
      const editor = this.promptEditor();
      await editor.click();
      await editor.fill(content);
      await expect(editor).toContainText(content);
    });
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
      await search.fill(displayName);
      await this.page.getByRole('option', { name: displayName }).click();
      await expect(this.modelCombobox()).toContainText(displayName);
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
      await this.page.waitForURL(/\/optimizations\/[0-9a-f-]+$/);
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

  /** Assert the Best-trial-configuration panel shows the given algorithm + metric. */
  async expectBestTrialConfig(opts: { algorithm: string; metric: string }): Promise<void> {
    return test.step('Assert Best-trial configuration panel', async () => {
      const overview = this.page.getByRole('tabpanel');
      await expect(overview).toContainText(opts.algorithm);
      await expect(overview).toContainText(opts.metric);
    });
  }

  private promptEditor(): Locator {
    return this.page.locator('[data-testid="playground-message-editor"] .cm-content');
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
    // name; the others show "GEPA optimizer" / "Equals".
    return this.page
      .locator('[role="combobox"]')
      .filter({ hasText: /Anthropic|OpenAI|Gemini|openrouter|free|gpt-|claude|GPT|Claude/i });
  }

  private trialsTable(): Locator {
    return this.page.locator('main table');
  }
}
