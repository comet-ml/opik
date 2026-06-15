import type { Page, FrameLocator, Locator } from '@playwright/test';
import { test, expect } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';

// The chat UI lives inside a separately-deployed iframe we don't control, so
// every locator goes through frameLocator() and matches on accessible role/name
// or the iframe's own data hooks — no host-side data-testids are reachable.
export class OlliePage {
  constructor(
    private readonly page: Page,
    private readonly projectId: string,
  ) {}

  async goto(): Promise<void> {
    return test.step('open the Ollie page', async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/ollie`,
      );
    });
  }

  /**
   * Wait until Ollie is fully loaded and ready to take a prompt.
   *
   * On a cold project the assistant pod is provisioned on demand: the host polls
   * `/health/ready` for up to ~2 minutes while showing the "Ollie is waking up…"
   * loader before the iframe mounts. So this waits on a generous budget for the
   * in-iframe greeting and input, not just the iframe element.
   */
  async waitForReady(timeoutMs = 150_000): Promise<void> {
    return test.step('wait for Ollie to be ready', async () => {
      await this.iframeElement().waitFor({ state: 'visible', timeout: timeoutMs });
      // The usable input is the surface-agnostic readiness signal: it's present
      // once Ollie has fully loaded, regardless of whether the project is empty
      // (onboarding greeting) or already has traces (chat greeting).
      await expect(this.inputTextbox()).toBeVisible({ timeout: timeoutMs });
      await expect(this.greeting()).toBeVisible({ timeout: timeoutMs });
    });
  }

  /** Send a prompt and wait for a non-empty assistant reply to render. */
  async sendMessageAndAwaitReply(prompt: string, timeoutMs = 90_000): Promise<string> {
    return test.step(`ask Ollie "${prompt}"`, async () => {
      const before = await this.messages().count();
      await this.inputTextbox().fill(prompt);
      await this.sendButton().click();

      // The user echo and the assistant reply each mount as a [data-message-id]
      // node. Wait for both to land (count grows by 2), then for the reply's
      // text to be non-empty (it streams in after the bubble appears).
      await expect
        .poll(async () => this.messages().count(), {
          timeout: timeoutMs,
          intervals: [500, 1000, 2000],
        })
        .toBeGreaterThanOrEqual(before + 2);

      const reply = this.messages().last();
      await expect
        .poll(async () => ((await reply.textContent()) ?? '').trim().length, {
          timeout: timeoutMs,
          intervals: [500, 1000, 2000],
        })
        .toBeGreaterThan(0);

      return ((await reply.textContent()) ?? '').trim();
    });
  }

  /** Reset to a fresh conversation (clears history back to the greeting). */
  async startNewChat(): Promise<void> {
    return test.step('start a new Ollie chat', async () => {
      await this.frame().getByRole('button', { name: 'New chat' }).click();
      await expect(this.greeting()).toBeVisible();
    });
  }

  greeting(): Locator {
    // "Hi there!" opens both greetings — the empty-project onboarding state
    // ("I'm Ollie, your AI coding assistant…") and the chat state on a project
    // that already has traces ("How can I help you today?").
    return this.frame().getByText('Hi there!');
  }

  inputTextbox(): Locator {
    // Ollie's own `data-chat-input-textarea` hook — stable across the page vs
    // sidebar surfaces (whose placeholder copy differs: "Ask Ollie about your
    // agent..." vs "Send a message..."). This attribute lives inside the
    // ollie-assist iframe, which is a separate deploy we can't add testids to;
    // it's the most durable selector available there.
    return this.frame().locator('[data-chat-input-textarea]');
  }

  /** All chat message bubbles (user echoes + assistant replies), in order. */
  messages(): Locator {
    return this.frame().locator('[data-message-id]');
  }

  // ── private helpers ─────────────────────────────────────────────────────

  // Anchor on the testid added to the host iframe in AssistantSidebar.tsx, OR
  // on the `title="Assistant"` attribute already present on deployed builds.
  // The `,` selector keeps the POM working against a cloud env running an older
  // FE bundle that predates the testid, while preferring the testid once the FE
  // change ships. Both resolve to the same single iframe.
  private static readonly IFRAME_SELECTOR =
    '[data-testid="ollie-assistant-iframe"], iframe[title="Assistant"]';

  private iframeElement(): Locator {
    return this.page.locator(OlliePage.IFRAME_SELECTOR);
  }

  private frame(): FrameLocator {
    return this.page.frameLocator(OlliePage.IFRAME_SELECTOR);
  }

  private sendButton(): Locator {
    return this.frame().getByRole('button', { name: 'Send message' });
  }
}
