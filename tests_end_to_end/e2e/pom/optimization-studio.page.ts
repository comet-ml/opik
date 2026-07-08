import type { Page, Locator } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export interface StudioRunConfig {
  /** Name of a dataset already associated with the project (shown in the picker). */
  datasetName: string;
  /** User-message prompt content; keep the dataset variable, e.g. `{{text}}`. */
  prompt: string;
  /** Model display name as shown in the picker (e.g. "Claude Haiku 4.5"). */
  modelDisplayName: string;
  /** Equals-metric reference key — the dataset field holding the gold label. */
  referenceKey: string;
}

/**
 * Drives the v2 Optimization Studio: the new-run form
 * (`/projects/{id}/optimizations/new`) and the read side of the run-detail page
 * (`/projects/{id}/optimizations/{optimizationId}`).
 *
 * The form ships with GEPA + Equals + an Anthropic model preselected, so the
 * happy path only sets the dataset, prompt, model, and Equals reference key.
 * Only the shared LLM message editor carries data-testids; everything else is
 * addressed by role/label/text because this POM must run against the deployed
 * image as it renders, without adding product testids.
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

  async selectDataset(name: string): Promise<void> {
    return test.step(`Select dataset "${name}"`, async () => {
      await this.page.getByRole('button', { name: 'Select a dataset' }).click();
      await this.page.getByTestId('search-input').fill(name);
      await this.page.getByText(name, { exact: true }).click();
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

  async setReferenceKey(key: string): Promise<void> {
    return test.step(`Set Equals reference key to "${key}"`, async () => {
      await this.page.locator('input#reference_key').fill(key);
    });
  }

  /** Assert the form ships with the optimizer/metric this test relies on. */
  async assertDefaults(optimizer: string, metric: string): Promise<void> {
    return test.step(`Assert defaults: ${optimizer} + ${metric}`, async () => {
      await expect(
        this.page.locator('[role="combobox"]', { hasText: optimizer }),
      ).toBeVisible();
      await expect(this.page.getByRole('combobox', { name: 'Metric' })).toContainText(metric);
    });
  }

  /** Click "Optimize prompt" and return the created optimization's id from the URL. */
  async startOptimization(): Promise<string> {
    return test.step('Start the optimization run', async () => {
      const optimizeButton = this.page.getByRole('button', { name: 'Optimize prompt' });
      await expect(optimizeButton).toBeEnabled();
      await optimizeButton.click();
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

  /** Wait for the detail header status tag to read the given status. */
  async expectStatus(status: string, opts: { timeout?: number } = {}): Promise<void> {
    return test.step(`Expect detail-page status "${status}"`, async () => {
      await expect(
        this.page.getByText(status, { exact: true }),
        `optimization detail should show status "${status}"`,
      ).toBeVisible({ timeout: opts.timeout ?? 15_000 });
    });
  }

  private promptEditor(): Locator {
    return this.page.locator('[data-testid="playground-message-editor"] .cm-content');
  }

  private modelCombobox(): Locator {
    // The model picker is the only combobox on the form that renders a provider
    // name (Anthropic/OpenAI/…); the others show "GEPA optimizer" / "Equals".
    return this.page.locator('[role="combobox"]').filter({ hasText: /Anthropic|OpenAI|Gemini|openrouter/ });
  }
}
