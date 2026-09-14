import { test, expect } from '@playwright/test';
import type { Page, Locator } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

export class PromptDetailPage {
  constructor(private readonly page: Page) {}

  /**
   * Open a prompt's detail page directly, optionally deep-linked to one of its
   * versions via `activeVersionId` — the query param the page resolves
   * independently of whichever versions its paginated timeline has loaded.
   *
   * `tab=prompt` is set explicitly rather than left to the page's own effect,
   * which replaces the URL a render later; navigating straight to the final URL
   * keeps `waitForReady` from racing that replacement.
   */
  async goto(
    projectId: string,
    promptId: string,
    opts: { activeVersionId?: string } = {},
  ): Promise<void> {
    return test.step(`open prompt ${promptId}${opts.activeVersionId ? ` at version ${opts.activeVersionId}` : ''}`, async () => {
      const env = loadEnvConfig();
      const query = new URLSearchParams({ tab: 'prompt' });
      if (opts.activeVersionId) query.set('activeVersionId', opts.activeVersionId);
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${projectId}/prompts/${promptId}?${query}`,
      );
    });
  }

  async waitForReady(): Promise<void> {
    return test.step('wait for prompt detail to load', async () => {
      // Edit button renders only after the loading skeleton is replaced with real content
      await this.page.getByRole('button', { name: 'Edit' }).waitFor({ state: 'visible' });
    });
  }

  promptNameHeading(): Locator {
    return this.page.getByRole('heading', { level: 1 });
  }

  textContent(): Locator {
    return this.page.getByTestId('prompt-text-content');
  }

  chatMessages(): Locator {
    return this.page.getByTestId('prompt-chat-messages');
  }

  activeVersionLabel(): Locator {
    return this.page.getByTestId('active-version-label');
  }

  versionHistoryItem(label: string): Locator {
    return this.page.getByTestId(`version-history-item-${label}`);
  }

  async editTextPrompt(newTemplate: string): Promise<void> {
    return test.step(`edit text prompt template`, async () => {
      await this.page.getByRole('button', { name: 'Edit' }).click();
      const sheet = this.page.getByRole('dialog');
      await sheet.waitFor({ state: 'visible' });
      const editor = sheet.getByPlaceholder('Type your prompt...');
      await editor.fill(newTemplate);
      await sheet.getByRole('button', { name: 'Create new version' }).click();
      await sheet.waitFor({ state: 'hidden' });
    });
  }

  async editChatFirstMessage(newContent: string): Promise<void> {
    return test.step(`edit first chat message`, async () => {
      await this.page.getByRole('button', { name: 'Edit' }).click();
      const sheet = this.page.getByRole('dialog');
      await sheet.waitFor({ state: 'visible' });
      const firstMessageRow = sheet.getByTestId('playground-message-row').first();
      const editor = firstMessageRow.getByTestId('playground-message-editor').locator('.cm-content').first();
      await editor.click();
      await editor.press('ControlOrMeta+a');
      await editor.pressSequentially(newContent);
      await sheet.getByRole('button', { name: 'Create new version' }).click();
      await sheet.waitFor({ state: 'hidden' });
    });
  }

  /** The version-history timeline in the right sidebar (xl breakpoint only). */
  versionTimeline(): Locator {
    return this.page.getByTestId('version-history-timeline');
  }

  /** Every version currently rendered in the timeline — one entry per loaded version. */
  versionTimelineItems(): Locator {
    return this.versionTimeline().locator('[data-testid^="version-history-item-"]');
  }

  /**
   * The timeline's version labels, top to bottom.
   *
   * Read off each item's own `data-testid` rather than its rendered text: the
   * item also renders a change description and a relative timestamp, so its
   * text is not the label. Order is the assertion here, which is why this reads
   * positionally at all — the labels themselves are still identities, not
   * indices.
   */
  async readVersionTimelineLabels(): Promise<string[]> {
    return test.step('read version timeline labels in order', async () => {
      const testIds = await this.versionTimelineItems().evaluateAll((els) =>
        els.map((el) => el.getAttribute('data-testid') ?? ''),
      );
      return testIds.map((id) => id.replace('version-history-item-', ''));
    });
  }

  /**
   * Scroll the timeline's last rendered version into view, which is what brings
   * its load-more sentinel into the viewport and triggers the next page.
   */
  async scrollVersionTimelineToEnd(): Promise<void> {
    return test.step('scroll the version timeline to its end', async () => {
      await this.versionTimelineItems().last().scrollIntoViewIfNeeded();
    });
  }

  /**
   * Open the "Diff" menu and return its content.
   *
   * The menu lists every version except the active one, and keeps paging the
   * version list for as long as it is open — so callers must assert on its
   * contents with a retrying assertion rather than reading it once.
   */
  async openDiffMenu(): Promise<Locator> {
    return test.step('open the Diff (compare against) menu', async () => {
      await this.page.getByRole('button', { name: 'Diff' }).click();
      const menu = this.page.getByRole('menu');
      await menu.waitFor({ state: 'visible' });
      return menu;
    });
  }

  /** The entries offered by an open Diff menu. */
  diffMenuItems(menu: Locator): Locator {
    return menu.getByRole('menuitem');
  }

  /**
   * The label element of one Diff menu entry, matched whole.
   *
   * Anchored and exact because these labels are prefixes of one another: a
   * substring match on `v1` also matches `v10` through `v19`, which would make
   * "the page-2 versions are listed" pass on a menu that only ever loaded page
   * 1. The label sits in its own element, so an exact match on element text
   * addresses it without depending on its position among the entry's parts.
   */
  diffMenuVersionLabel(menu: Locator, label: string): Locator {
    return menu.getByText(label, { exact: true });
  }

  async selectVersion(label: string): Promise<void> {
    return test.step(`select version "${label}" from history timeline`, async () => {
      const item = this.versionHistoryItem(label);
      await item.waitFor({ state: 'visible' });
      await item.click();
      await expect(this.activeVersionLabel()).toHaveText(label);
    });
  }

  /** Open the "Use" dropdown and click "Load in Prompt playground", then wait for the Playground URL.
   * A confirmation dialog may appear if the playground is not empty — handle it if present. */
  async loadInPlayground(): Promise<void> {
    return test.step('load prompt into Playground', async () => {
      await this.page.getByRole('button', { name: 'Use' }).click();
      await this.page.getByRole('menuitem', { name: 'Load in Prompt playground' }).click();
      // A confirmation dialog appears only when the playground already has content.
      // If it shows up within a short window, click through it; otherwise proceed.
      const dialog = this.page.getByRole('dialog', { name: 'Load prompt' });
      const confirmBtn = dialog.getByRole('button', { name: 'Load prompt' });
      const appeared = await confirmBtn.isVisible().catch(() => false);
      if (!appeared) {
        await confirmBtn.waitFor({ state: 'visible', timeout: 3000 }).catch(() => {});
      }
      if (await confirmBtn.isVisible().catch(() => false)) {
        await confirmBtn.click();
      }
      await this.page.waitForURL((url) => url.pathname.includes('/playground'));
    });
  }
}
