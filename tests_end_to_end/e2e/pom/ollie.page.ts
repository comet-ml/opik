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
   * Open a project page that hosts the Ollie *sidebar* surface, rather than the
   * dedicated `/ollie` page route. The sidebar is rendered by the Opik host on
   * every project page EXCEPT `/ollie` (see `PageLayout` / `isOlliePage`), so any
   * other project sub-route works; the default is `logs`. The same iframe and
   * Ollie deploy back both surfaces — only the surrounding chrome and the input
   * placeholder differ.
   */
  async gotoSidebarSurface(subRoute = 'logs'): Promise<void> {
    return test.step(`open the ${subRoute} page (Ollie sidebar surface)`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(
        `${env.baseUrl}/${env.workspace}/projects/${this.projectId}/${subRoute}`,
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
      // (onboarding greeting) or already has traces (chat greeting). Key
      // readiness on it, not on a specific greeting line — the greeting copy
      // varies (onboarding vs chat vs "Ollie connected") and can render split
      // across nodes, which makes an exact-text wait flake when the pod is slow
      // to provision.
      await expect(this.inputTextbox()).toBeVisible({ timeout: timeoutMs });
      await expect(this.inputTextbox()).toBeEnabled({ timeout: timeoutMs });
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

  /**
   * Wait until the Ollie sidebar surface has mounted and is ready to take a
   * prompt. The sidebar provisions on demand exactly like the page route, so
   * this keys on the same surface-agnostic signal: the iframe is visible and the
   * input is present. Greeting copy is intentionally not asserted here — see
   * `waitForReady`.
   */
  async waitForSidebarReady(timeoutMs = 150_000): Promise<void> {
    return test.step('wait for the Ollie sidebar to be ready', async () => {
      await this.iframeElement().waitFor({ state: 'visible', timeout: timeoutMs });
      await expect(this.inputTextbox()).toBeVisible({ timeout: timeoutMs });
      await expect(this.inputTextbox()).toBeEnabled({ timeout: timeoutMs });
    });
  }

  /** True if the Ollie iframe is mounted on the current host page. */
  async isMounted(): Promise<boolean> {
    return test.step('check the Ollie iframe is mounted', async () => {
      return (await this.iframeElement().count()) > 0;
    });
  }

  /**
   * The number Ollie reports for the active project's trace context, read from
   * the "Traces: N" badge in the greeting block. Ollie derives this from the
   * project it's scoped to, so it should track the seeded trace count. Returns
   * null if the badge isn't present (e.g. the conversation has moved past the
   * greeting).
   */
  async contextTraceCount(timeoutMs = 60_000): Promise<number | null> {
    return test.step('read the Ollie "Traces: N" context badge', async () => {
      const badge = this.traceCountBadge();
      await expect(badge).toBeVisible({ timeout: timeoutMs });
      const text = (await badge.textContent()) ?? '';
      const match = text.match(/Traces:\s*(\d+)/);
      return match ? Number(match[1]) : null;
    });
  }

  /**
   * Wait for an Explain popover's "Continue conversation" hand-off to land in
   * the chat. The bridge posts the question and the popover's already-settled
   * answer as a pair of new messages (see `chat:continue` in explainStore.ts
   * — "carries the verbatim Q&A already shown"), not a fresh generation, so
   * this only waits for them to render, not for streaming. `beforeCount` is
   * the message count read just before clicking "Continue conversation".
   * Returns the last message's (the answer's) text.
   */
  async awaitContinuedConversation(beforeCount: number, timeoutMs = 30_000): Promise<string> {
    return test.step('wait for the continued conversation to render in the sidebar', async () => {
      await expect
        .poll(async () => this.messages().count(), {
          timeout: timeoutMs,
          intervals: [300, 600, 1200],
        })
        .toBeGreaterThanOrEqual(beforeCount + 2);

      const reply = this.messages().last();
      await expect
        .poll(async () => ((await reply.textContent()) ?? '').trim().length, {
          timeout: timeoutMs,
          intervals: [300, 600, 1200],
        })
        .toBeGreaterThan(0);

      return ((await reply.textContent()) ?? '').trim();
    });
  }

  /**
   * Run the `/analyze` flow from the greeting action button and wait for a
   * non-empty assistant response to render. Ollie is a non-deterministic agent,
   * so callers should assert structurally (a reply landed, no error state), not
   * on exact wording.
   */
  async runAnalyze(timeoutMs = 120_000): Promise<string> {
    return test.step('run /analyze and await a response', async () => {
      const before = await this.messages().count();
      await this.analyzeButton().click();
      await expect
        .poll(async () => this.messages().count(), {
          timeout: timeoutMs,
          intervals: [1000, 2000, 5000],
        })
        .toBeGreaterThan(before);

      const reply = this.messages().last();
      await expect
        .poll(async () => ((await reply.textContent()) ?? '').trim().length, {
          timeout: timeoutMs,
          intervals: [1000, 2000, 5000],
        })
        .toBeGreaterThan(0);
      return ((await reply.textContent()) ?? '').trim();
    });
  }

  /**
   * Open the pairing URL emitted by a running `opik connect` daemon and wait
   * for the pairing page to confirm the connection. The page lands on one of
   * three states: connected ("…connected to your codebase"), an invalid-link
   * error, or an unreachable error — this asserts the success heading and fails
   * fast on the error ones.
   *
   * `pairUrl` is the `…/opik/pair/v1?…#<fragment>` URL scraped from the connect
   * daemon's stdout. The fragment carries the activation key, so it must be the
   * full, un-truncated URL (Rich wraps it in an OSC-8 hyperlink — see the
   * connect helper that scrapes it).
   */
  async pairConnectRunner(pairUrl: string, timeoutMs = 30_000): Promise<void> {
    return test.step('open the pairing link and confirm the runner connects', async () => {
      await this.page.goto(pairUrl);
      await expect(
        this.page.getByRole('heading', { name: /invalid|couldn't reach/i }),
      ).toHaveCount(0, { timeout: timeoutMs });
      await expect(
        this.page.getByRole('heading', { name: /connected to your codebase/i }),
      ).toBeVisible({ timeout: timeoutMs });
    });
  }

  /** Click `/instrument` to kick off the instrumentation flow on a connected runner. */
  async startInstrument(): Promise<void> {
    return test.step('run /instrument', async () => {
      await this.instrumentButton().click();
    });
  }

  /** Click `/improve` to kick off the improvement flow on a connected runner. */
  async startImprove(): Promise<void> {
    return test.step('run /improve', async () => {
      await this.improveButton().click();
    });
  }

  /**
   * Drive Ollie's agentic loop to completion by approving each tool action as it
   * comes up. Ollie gates tool calls (`connect_bash`, edits, runs) behind an
   * approval row — it re-prompts for new distinct actions even after "Always
   * allow", so this polls and clicks until no approval has appeared for
   * `quietMs`, or the overall budget expires.
   */
  async approveUntilSettled(opts: { budgetMs?: number; quietMs?: number } = {}): Promise<void> {
    const budgetMs = opts.budgetMs ?? 300_000;
    const quietMs = opts.quietMs ?? 30_000;
    return test.step('approve tool actions until the agent settles', async () => {
      const deadline = Date.now() + budgetMs;
      let lastApproval = Date.now();
      while (Date.now() < deadline) {
        const approve = this.approveButton();
        if (await approve.count()) {
          await approve.first().click();
          lastApproval = Date.now();
        } else if (Date.now() - lastApproval > quietMs) {
          return; // no approval prompt for a while — the agent has settled
        }
        await this.page.waitForTimeout(2000);
      }
    });
  }

  /**
   * The `/improve` flow ends its proposal with a chat prompt — "Reply `apply` to
   * apply this fix" — rather than an accept button. This waits for that prompt
   * (the agent has finished analysing and proposed an edit), then sends "apply"
   * to authorise it. Returns once the apply message is in the log; the caller
   * should then `approveUntilSettled()` again to drive the edit/run tools.
   */
  async applyProposal(timeoutMs = 240_000): Promise<void> {
    return test.step('approve the proposed fix by replying "apply"', async () => {
      await expect(this.frame().getByText(/reply\s+.?apply/i).first()).toBeVisible({
        timeout: timeoutMs,
      });
      await this.inputTextbox().fill('apply');
      await this.sendButton().click();
    });
  }

  /** The greeting-block action button that kicks off the `/analyze` flow. */
  analyzeButton(): Locator {
    return this.frame().getByRole('button', { name: /Run \/analyze/ });
  }

  /**
   * The `/instrument` action button. On a connected runner its name is the bare
   * `/instrument`; before connecting it reads "…— run /connect first", so match
   * on the `/instrument` token either way.
   */
  instrumentButton(): Locator {
    return this.frame().getByRole('button', { name: /\/instrument/ });
  }

  /** A pending tool-approval action ("Always allow" / "Allow once"). */
  approveButton(): Locator {
    return this.frame().getByRole('button', { name: /Always allow|Allow once/ });
  }

  /** The greeting-block action button that kicks off the `/improve` flow. */
  improveButton(): Locator {
    return this.frame().getByRole('button', { name: /Run \/improve/ });
  }

  /** The "Connect Ollie locally" entry point for the Local Runner (`opik connect`) flow. */
  connectButton(): Locator {
    return this.frame().getByRole('button', { name: 'Connect Ollie locally' });
  }

  /** The "Traces: N" context badge in the greeting block. */
  traceCountBadge(): Locator {
    // Bare <span> with no testid/class inside the ollie-assist iframe; text is
    // the only stable hook. Scope to the badge text rather than a structural path.
    return this.frame().getByText(/Traces:\s*\d+/);
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
