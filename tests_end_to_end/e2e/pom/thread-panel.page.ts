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

  // --- Feedback scores tab: thread-level scores ---

  /** The panel's Feedback scores tab trigger, beside Messages. */
  get feedbackScoresTab(): Locator {
    return this.root.getByRole('tab', { name: 'Feedback scores' });
  }

  /** The Feedback scores tab's content area (the scores table). */
  get feedbackScoresTabPanel(): Locator {
    return this.root.getByRole('tabpanel', { name: 'Feedback scores' });
  }

  /** Switch to the Feedback scores tab. Idempotent if already selected. */
  async openFeedbackScoresTab(): Promise<void> {
    return test.step('Open the thread panel Feedback scores tab', async () => {
      await this.feedbackScoresTab.click();
      await this.feedbackScoresTabPanel.waitFor({ state: 'visible' });
    });
  }

  /**
   * A row of the thread's scores table, keyed by score name. The shared
   * DataTable stamps the score name as `data-row-id`, so this addresses the row
   * by identity — the Key cell truncates with a CSS ellipsis and the columns are
   * user-reorderable, which rules out both text and position.
   */
  feedbackScoreRow(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.locator(`tr[data-row-id="${scoreName}"]`);
  }

  /** The Score cell of a named score's row. */
  feedbackScoreValueCell(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.locator(`[data-cell-id="${scoreName}_value"]`);
  }

  /** The Reason cell of a named score's row. */
  feedbackScoreReasonCell(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.locator(`[data-cell-id="${scoreName}_reason"]`);
  }
}
