import { expect, test, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import {
  INTERNAL_TRACE_TAG_LABEL,
  ONLY_INTERNAL_CALLS_MESSAGE,
  internalCallsToggleLabel,
} from './thread-panel.page';

/**
 * The SME (subject-matter expert) annotation flow at `/$workspaceName/sme` —
 * the standalone, reviewer-facing view of an annotation queue, reached by a
 * shared link rather than from the product nav.
 *
 * It has three states, and the queue decides which one mounts: an instructions
 * page ("Start annotating"), the annotation view itself, and a completion view.
 * This POM covers the first two.
 *
 * **Selector note.** The whole route carries no `data-testid` anywhere — not on
 * the layout, the annotation view, or the thread header — so every locator here
 * is a role or the message text, in that order of preference. The one structural
 * selector is `[data-trace-message-id]`, which is a deliberate, stable hook the
 * shared `TraceMessages` component stamps per turn and which the thread panel's
 * POM already addresses turns by. The frontend should grow testids on this
 * route (`sme-annotation-view`, `sme-thread-header`); this POM should move to
 * them when it does. They could not be added alongside this spec: the QA branch
 * this ships on may only touch `tests_end_to_end/`.
 */
export class SmeFlowPage {
  constructor(private readonly page: Page) {}

  /**
   * Open the SME flow for one queue.
   *
   * `queueId` rides in the query string, not the path — `SMEFlowContext` reads
   * it with a non-strict `useSearch`, so a missing or unknown id renders a
   * no-queue state rather than a 404.
   */
  async goto(queueId: string): Promise<void> {
    return test.step(`Open the SME flow for queue ${queueId}`, async () => {
      const env = loadEnvConfig();
      await this.page.goto(`${env.baseUrl}/${env.workspace}/sme?queueId=${queueId}`);
    });
  }

  /** The instructions page's heading, which names the queue. */
  welcomeHeading(queueName: string): Locator {
    return this.page.getByRole('heading', { name: `Welcome to ${queueName}`, exact: true });
  }

  /**
   * The button that enters the annotation view. Reads "Resume annotating"
   * instead once anything in the queue has been processed, so a POM that
   * matched only "Start" would hang on a partly-reviewed queue; both are
   * accepted and the caller does not care which it got.
   */
  get startAnnotatingButton(): Locator {
    return this.page.getByRole('button', { name: /^(Start|Resume) annotating$/ });
  }

  /** Wait for the instructions page to mount, then enter the annotation view. */
  async startAnnotating(): Promise<void> {
    return test.step('Start annotating', async () => {
      await expect(
        this.startAnnotatingButton,
        'exactly one start/resume annotating button',
      ).toHaveCount(1, { timeout: 30_000 });
      await this.startAnnotatingButton.click();
      await expect(
        this.readInstructionsButton,
        'the annotation view mounted (its header offers "Read instructions")',
      ).toBeVisible({ timeout: 30_000 });
    });
  }

  /**
   * The annotation view's header button back to the instructions. Used as the
   * view's mounted-signal: it renders only inside `AnnotatingHeader`, and
   * unlike the turns it is there whether or not anything is visible to review.
   */
  get readInstructionsButton(): Locator {
    return this.page.getByRole('button', { name: 'Read instructions' });
  }

  /**
   * All conversation turns in the thread being annotated, in render
   * (chronological) order.
   *
   * Page-scoped, which is safe only while the view's own trace and thread side
   * panels are closed — they render `TraceMessages` too, so an open one would
   * put a second set of turns on the page and this locator would silently
   * conflate them. `waitForThreadReady` asserts they are closed for exactly
   * that reason; call it before reading this.
   */
  get turns(): Locator {
    return this.page.locator('[data-trace-message-id]');
  }

  /** A single turn, located by the trace it was logged under. */
  turn(traceId: string): Locator {
    return this.page.locator(`[data-trace-message-id="${traceId}"]`);
  }

  /** Read the trace ids of the rendered turns in order. */
  async readTurnTraceIdsInOrder(): Promise<string[]> {
    return test.step('Read SME turn trace ids in order', async () => {
      const handles = await this.turns.all();
      const ids: string[] = [];
      for (const t of handles) {
        const id = await t.getAttribute('data-trace-message-id');
        if (id) ids.push(id);
      }
      return ids;
    });
  }

  /**
   * Wait for the thread annotation view to be ready to read turns from, and
   * establish the precondition the page-scoped locators above depend on.
   *
   * The side-panel check is an assertion, not a wait: if a panel were open, the
   * turn locators would still resolve — to the wrong elements — so this has to
   * fail loudly rather than settle.
   */
  async waitForThreadReady(): Promise<void> {
    return test.step('Wait for the SME thread annotation view', async () => {
      await expect(this.readInstructionsButton).toBeVisible({ timeout: 30_000 });
      // Either turns or the all-internal empty state — a thread of only hidden
      // traces legitimately renders no turn, so gating on a turn would hang on
      // exactly the case worth testing.
      await expect
        .poll(
          async () =>
            (await this.turns.count()) > 0 ||
            (await this.onlyInternalCallsMessage.count()) > 0,
          { timeout: 30_000, intervals: [250, 500, 1000] },
        )
        .toBe(true);
      // The side panels stay MOUNTED while closed — `[data-testid="thread"]` is
      // on the page throughout — so their presence says nothing. What matters is
      // that neither is rendering turns of its own, because that is the only way
      // a page-scoped `[data-trace-message-id]` could conflate two sets.
      for (const panelId of ['thread', 'trace']) {
        await expect(
          this.page.locator(`[data-testid="${panelId}"] [data-trace-message-id]`),
          `the ${panelId} side panel renders no turns, so the page-scoped turn ` +
            'locators resolve to the annotation view alone',
        ).toHaveCount(0);
      }
    });
  }

  // --- Internal calls ---
  //
  // The SME view duplicates the thread panel's hide logic in a second component
  // (`ThreadDataViewer`) over the same shared helpers, which is where the two
  // can drift apart unnoticed. The locators mirror `ThreadPanelPage`'s and the
  // label/message constants are imported from it, so a copy-edit cannot leave
  // the two POMs asserting different strings.

  /** The internal-calls toggle in the thread header, by its exact label. */
  internalCallsToggle(action: 'Show' | 'Hide', count: number): Locator {
    return this.page.getByRole('button', {
      name: internalCallsToggleLabel(action, count),
      exact: true,
    });
  }

  /** Any internal-calls control, for asserting absence. */
  get anyInternalCallsToggle(): Locator {
    return this.page.getByRole('button', { name: /internal calls \(\d+\)/ });
  }

  /** Click the internal-calls toggle and wait for its label to flip. */
  async toggleInternalCalls(action: 'Show' | 'Hide', count: number): Promise<void> {
    return test.step(`Click "${internalCallsToggleLabel(action, count)}" in the SME view`, async () => {
      const button = this.internalCallsToggle(action, count);
      await expect(button, `exactly one "${action} internal calls (${count})" control`).toHaveCount(
        1,
      );
      await button.click();
      const flipped = action === 'Show' ? 'Hide' : 'Show';
      await expect(
        this.internalCallsToggle(flipped, count),
        `the control reads "${internalCallsToggleLabel(flipped, count)}" after clicking`,
      ).toBeVisible();
    });
  }

  /** The `Internal` tag on one turn, located by trace id. */
  internalTag(traceId: string): Locator {
    return this.turn(traceId).getByRole('note', { name: INTERNAL_TRACE_TAG_LABEL, exact: true });
  }

  /** Every `Internal` tag on the page. */
  get internalTags(): Locator {
    return this.page.getByRole('note', { name: INTERNAL_TRACE_TAG_LABEL, exact: true });
  }

  /** The all-internal empty state. */
  get onlyInternalCallsMessage(): Locator {
    return this.page.getByText(ONLY_INTERNAL_CALLS_MESSAGE, { exact: true });
  }
}
