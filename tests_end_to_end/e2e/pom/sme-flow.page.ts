import { expect, test, type FrameLocator, type Locator, type Page } from '@playwright/test';
import { loadEnvConfig } from '../config/env.config';
import {
  INTERNAL_TRACE_TAG_LABEL,
  ONLY_INTERNAL_CALLS_MESSAGE,
  internalCallsToggleLabel,
} from './thread-panel.page';

/**
 * `AnnotatorViewFallbackAlert`'s message, verbatim.
 *
 * Exported so the queue-detail POM and this one cannot drift apart on it, and
 * so a spec asserting the alert is ABSENT names the same string as one
 * asserting it is present.
 */
export const ANNOTATOR_VIEW_FALLBACK_MESSAGE =
  "This queue's custom view failed to load, so the default view is shown.";

const escapeForRegExp = (literal: string): string =>
  literal.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

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

  // --- Trace queues (scope: 'trace') ---
  //
  // A trace queue mounts `TraceDataViewer` rather than `ThreadDataViewer`:
  // a `Trace:` header naming one trace, and either the default view's
  // Input/Output/Metadata blocks or the queue's custom annotator view in a
  // sandboxed iframe. opik#9079 rewrote the default half onto the shared
  // `DefaultTraceView` and added the custom half.

  /**
   * The trace id the viewer pane's header names, as a page-wide exact-text
   * lookup.
   *
   * Page-scoped on purpose, and strict about it: the items sidebar previews
   * every queued item, but it previews them by NAME (`trace.name || id.slice(-12)`)
   * — a full trace id appears on this route only in the header of the item
   * actually being reviewed. So "exactly one element matches this id" is itself
   * the claim that the viewer is on this trace, and "no element matches" is the
   * claim that it is not on a sibling. That holds without a structural selector,
   * which this route still has none of (see the class note).
   */
  traceHeaderIdentifier(traceId: string): Locator {
    return this.page.getByText(traceId, { exact: true });
  }

  /**
   * Which of `candidateIds` the viewer is currently showing.
   *
   * The SME flow picks its first item — and its Next order — from a per-user
   * deterministic shuffle keyed on the queue and item ids (`shuffledItemIds`),
   * and those are fresh UUIDs per run. A spec therefore cannot know the visit
   * order up front; it has to ask. Throws unless exactly one candidate is on
   * screen, so "the viewer is showing two items at once" and "it is showing
   * none of them" are failures here rather than later.
   */
  async currentTraceId(candidateIds: readonly string[]): Promise<string> {
    return test.step('Read which trace the SME viewer is showing', async () => {
      const present: string[] = [];
      for (const id of candidateIds) {
        if ((await this.traceHeaderIdentifier(id).count()) > 0) present.push(id);
      }
      if (present.length !== 1) {
        throw new Error(
          `SmeFlowPage.currentTraceId: expected exactly one of the queue's traces to be named ` +
            `on screen, found ${present.length} (${present.join(', ') || 'none'})`,
        );
      }
      return present[0];
    });
  }

  /**
   * One collapsible `CodeBlock` of the default trace view — "Input", "Output"
   * or "Metadata".
   *
   * The block renders no `data-testid` (`DefaultTraceView` passes none), and
   * its header button is the only labelled thing in it, so the block is reached
   * by walking up from that button to the nearest ancestor that holds the
   * body's rendered content. The predicate is `comet-markdown` rather than
   * `cm-line`, which is what `trace-panel.page.ts`'s identical lookup uses:
   * `DefaultTraceView` passes a `prettifyConfig` for Input and Output, so their
   * default mode is `pretty` and the body is markdown, not CodeMirror.
   * Addressed by that predicate rather than by depth so a wrapper added between
   * the header row and the block does not silently retarget the lookup.
   *
   * Page-scoped, and safe only while the viewer's own trace side panel is
   * closed — it renders its own Input/Output blocks. `waitForTraceReady`
   * asserts exactly one block per title for that reason; call it first.
   */
  codeBlock(title: 'Input' | 'Output' | 'Metadata'): Locator {
    return this.page
      .getByRole('button', { name: title, exact: true })
      .locator('xpath=ancestor::div[.//*[contains(@class, "comet-markdown")]][1]');
  }

  /**
   * Wait for the default trace view to be ready to read, and establish the
   * precondition the page-scoped block locators depend on.
   *
   * The one-block-per-title check is an assertion, not a wait: with a second
   * set of blocks on the page the locators would still resolve — to the wrong
   * elements — so this has to fail loudly rather than settle.
   */
  async waitForTraceReady(): Promise<void> {
    return test.step('Wait for the SME trace annotation view', async () => {
      await expect(this.readInstructionsButton).toBeVisible({ timeout: 30_000 });
      await expect(
        this.codeBlock('Input'),
        'exactly one Input block on the page, so the block locators resolve to the ' +
          'annotation view alone and not to an open trace side panel',
      ).toHaveCount(1, { timeout: 30_000 });
      await expect(this.codeBlock('Output'), 'exactly one Output block').toHaveCount(1);
    });
  }

  // --- Annotating controls ---

  /**
   * The annotate score row for a named feedback definition.
   *
   * The same `annotate-score-row-<name>` testid the trace panel's annotate
   * section carries: `CommentAndScoreViewer` renders the shared
   * `FeedbackScoresEditor`, so the handle is the component's, not the route's.
   */
  scoreRow(definitionName: string): Locator {
    return this.page.getByTestId(`annotate-score-row-${definitionName}`);
  }

  /** Set the numeric value in a named annotate score row. */
  async setScore(definitionName: string, value: number): Promise<void> {
    return test.step(`Set ${definitionName} to ${value} in the SME view`, async () => {
      const row = this.scoreRow(definitionName);
      await expect(row, `exactly one "${definitionName}" score row`).toHaveCount(1);
      const input = row.getByTestId('annotate-score-input');
      await input.fill(String(value));
      // The SME flow saves on a 1s debounce (`useAnnotationPersistence`), and a
      // blur is what flushes the input itself. The caller still has to wait on
      // the server — `backendClient.pollTraceForFeedbackScore` — because the
      // write is a mutation this does not await.
      await input.blur();
    });
  }

  /**
   * The footer's advance control.
   *
   * Reads "Finish annotating" instead of "Next" once every item has left the
   * default state, and is DISABLED when the current item is the last one still
   * unprocessed — `nextDefaultItem` skips the current item, so a queue with one
   * remaining default has nowhere to advance to. Both names are accepted so a
   * caller asserting it is disabled does not have to know which it got.
   */
  get advanceButton(): Locator {
    // Anchored at the START only, not the end: the button also renders its
    // `HotkeyDisplay`, so the accessible name is "Next Ctrl+⏎" on
    // Linux/Windows and "Next ⌘+⏎" on a Mac. Matching the hotkey would make
    // the locator platform-dependent, and dropping the leading anchor would let
    // it match a future "Next page" control.
    return this.page.getByRole('button', { name: /^(Next|Finish annotating)\b/ });
  }

  /**
   * Advance to the next unprocessed item, resolving once the viewer has moved
   * off `fromTraceId`.
   *
   * Gated on the header no longer naming the trace we left rather than on the
   * click: the item swap is a React state change over data already in the
   * cache, so a caller that read straight after the click can see the previous
   * item's fields and conclude the viewer showed the wrong trace.
   */
  async advancePast(fromTraceId: string): Promise<void> {
    return test.step(`Advance past trace ${fromTraceId}`, async () => {
      const button = this.advanceButton;
      await expect(button, 'exactly one advance control').toHaveCount(1);
      await expect(button, 'the advance control is enabled').toBeEnabled();
      await button.click();
      await expect(
        this.traceHeaderIdentifier(fromTraceId),
        'the viewer moved off the item we advanced past',
      ).toHaveCount(0, { timeout: 30_000 });
    });
  }

  // --- Custom annotator view (opik#9079) ---

  /**
   * The sandboxed frame a queue's custom annotator view renders in.
   *
   * `SandboxedView` gives the iframe its `title` and nothing else to address it
   * by — no testid, and `srcDoc` + `sandbox="allow-scripts"` means it is
   * cross-origin, so the title is both the only handle and the stable one.
   */
  get annotatorViewFrame(): Locator {
    return this.page.locator('iframe[title="Annotator view"]');
  }

  /** The contents of the sandboxed view, for reading what it rendered. */
  get annotatorViewContent(): FrameLocator {
    return this.page.frameLocator('iframe[title="Annotator view"]');
  }

  /**
   * A kit card's header inside the sandboxed view, by its exact title.
   *
   * Addressed by the kit's own `kit-card-header` class rather than by a role:
   * the kit builds cards out of plain divs, so there is no heading to match,
   * and the class is part of the published v1 kit contract (`REFERENCE.md`)
   * that a view is written against.
   */
  annotatorViewCardHeader(title: string): Locator {
    return this.annotatorViewContent
      .locator('.kit-card-header')
      .filter({ hasText: new RegExp(`^\\s*${escapeForRegExp(title)}\\s*$`) });
  }

  /** Every `kit-text` block the sandboxed view rendered, for reading its body. */
  get annotatorViewTextBlocks(): Locator {
    return this.annotatorViewContent.locator('.kit-text');
  }

  /**
   * The alert shown when a stored custom view could not be rendered.
   *
   * The string is `AnnotatorViewFallbackAlert`'s, matched in full: "the default
   * view is shown" is the half that distinguishes a graceful fallback from a
   * blank frame, and a substring match on "failed to load" would accept an
   * alert that said nothing about what the annotator is looking at instead.
   */
  get annotatorViewFallbackAlert(): Locator {
    return this.page.getByText(ANNOTATOR_VIEW_FALLBACK_MESSAGE, { exact: true });
  }
}
