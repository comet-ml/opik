import { test, type Page, type Locator } from '@playwright/test';

/**
 * The thread detail side-panel: a chat-style view of a conversation. Each turn
 * is one trace, rendered as a `[data-trace-message-id={traceId}]` block holding
 * the turn's input (a right-aligned bubble) and output. The panel opens when
 * `?thread={id}` is present in the URL.
 */
export class ThreadPanelPage {
  constructor(
    private readonly page: Page,
    private readonly threadId: string,
  ) {}

  /** Root locator for the panel content, scoped to the panel testid. */
  get root(): Locator {
    return this.page.getByTestId('thread');
  }

  async waitForFullyLoaded(): Promise<void> {
    return test.step(`Wait for thread panel ${this.threadId} to load`, async () => {
      await this.page.waitForURL((url) => url.searchParams.get('thread') === this.threadId);
      await this.root.waitFor({ state: 'visible', timeout: 30_000 });
      await this.turns.first().waitFor({ state: 'visible', timeout: 30_000 });
    });
  }

  /** All conversation turns, in render (chronological) order. */
  get turns(): Locator {
    return this.root.locator('[data-trace-message-id]');
  }

  async countTurns(): Promise<number> {
    return test.step('Count conversation turns', async () => {
      await this.turns.first().waitFor({ state: 'visible' });
      return this.turns.count();
    });
  }

  /** Read the trace ids of the turns in render order. */
  async readTurnTraceIdsInOrder(): Promise<string[]> {
    return test.step('Read turn trace ids in order', async () => {
      await this.turns.first().waitFor({ state: 'visible' });
      const handles = await this.turns.all();
      const ids: string[] = [];
      for (const t of handles) {
        const id = await t.getAttribute('data-trace-message-id');
        if (id) ids.push(id);
      }
      return ids;
    });
  }

  /** A single turn block, located by the trace id it was logged under. */
  turn(traceId: string): Locator {
    return this.root.locator(`[data-trace-message-id="${traceId}"]`);
  }

  /** Locator for the turn's input text within the turn block. */
  turnInput(traceId: string, input: string): Locator {
    return this.turn(traceId).getByText(input, { exact: true });
  }

  /** Locator for the turn's output text within the turn block. */
  turnOutput(traceId: string, output: string): Locator {
    return this.turn(traceId).getByText(output, { exact: true });
  }

  // --- Feedback scores tab ---
  // The panel renders the same score table as the trace panel (Key | Score |
  // Reason), behind a "Feedback scores" tab next to "Messages".

  get feedbackScoresTab(): Locator {
    return this.root.getByRole('tab', { name: 'Feedback scores' });
  }

  get feedbackScoresTabPanel(): Locator {
    return this.root.getByRole('tabpanel', { name: 'Feedback scores' });
  }

  /** Switch to the Feedback scores tab. Idempotent if already selected. */
  async openFeedbackScoresTab(): Promise<void> {
    return test.step('Open the thread Feedback scores tab', async () => {
      await this.feedbackScoresTab.click();
      await this.feedbackScoresTabPanel.waitFor({ state: 'visible' });
    });
  }

  /** Row in the Thread scores table matching the given score name. */
  feedbackScoreRow(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.getByRole('row').filter({ hasText: scoreName });
  }

  /**
   * Read the numeric value rendered in the Score column for the given thread
   * score. Requires the Feedback scores tab to be open. Throws when the cell
   * isn't a parseable number, so a blank or truncated render fails loudly
   * rather than reading as zero.
   */
  async readFeedbackScoreValue(scoreName: string): Promise<number> {
    const row = this.feedbackScoreRow(scoreName);
    await row.waitFor({ state: 'visible' });
    // Columns are: Key | Score | Reason | <actions>
    const cellText = (await row.getByRole('cell').nth(1).textContent()) ?? '';
    const parsed = Number(cellText.trim());
    if (Number.isNaN(parsed)) {
      throw new Error(
        `ThreadPanelPage.readFeedbackScoreValue: cell text "${cellText}" for score "${scoreName}" is not a number`,
      );
    }
    return parsed;
  }
}
