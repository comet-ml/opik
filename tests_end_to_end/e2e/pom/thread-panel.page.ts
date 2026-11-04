import { test, expect, type Page, type Locator } from '@playwright/test';

import {
  measureTraceMessages,
  type TraceMessagesGeometry,
} from './trace-messages-geometry';

/**
 * Copy the thread views show instead of a blank Messages tab when every trace
 * in the thread is an internal call and all of them are hidden.
 *
 * Mirrors `ONLY_INTERNAL_CALLS_MESSAGE` in
 * `apps/opik-frontend/src/v2/pages-shared/traces/hiddenSpans.ts`. Duplicated
 * rather than imported — the e2e suite does not build against the frontend's
 * sources — so a copy-edit there turns this into a failing assertion, which is
 * the right outcome: the string IS the empty state.
 */
export const ONLY_INTERNAL_CALLS_MESSAGE = 'Only internal calls in this thread';

/**
 * Copy the panel shows instead of its turns when a search term matches nothing.
 *
 * Mirrors `NO_SEARCH_MATCHES_MESSAGE` in `ThreadDetailsPanel.tsx`, duplicated
 * for the same reason as the constant above.
 */
export const NO_SEARCH_MATCHES_MESSAGE = 'No messages match your search';

/**
 * The two CSS Custom Highlight names `useThreadSearch` registers.
 *
 * Mirrors `MATCH_HIGHLIGHT` / `CURRENT_MATCH_HIGHLIGHT` in
 * `useThreadSearch.ts`. These are the only observable record of what the search
 * highlighted: the Custom Highlight API paints `Range`s that have no element
 * and no attribute of their own, so there is nothing in the DOM to select and
 * `readSearchHighlights()` below has to go through `CSS.highlights`.
 */
export const SEARCH_MATCH_HIGHLIGHT = 'thread-search-match';
export const SEARCH_CURRENT_MATCH_HIGHLIGHT = 'thread-search-current-match';

/** What the panel's search highlighted, read out of the highlight registry. */
export interface ThreadSearchHighlights {
  /** Ranges in the `thread-search-match` highlight, in registration order. */
  matchCount: number;
  /**
   * The text each matched range covers, in the same order.
   *
   * Carried so a caller can assert the ranges really sit on the search term
   * rather than merely counting to the right number — an offset bug paints the
   * right number of ranges over the wrong characters.
   */
  matchTexts: string[];
  /**
   * How many ranges are in `thread-search-current-match`. Exactly one while a
   * match is selected; asserted rather than assumed, because `new
   * Highlight(current)` taking more than one range would mean the hook lost
   * track of which match is current.
   */
  currentCount: number;
  /**
   * The current match's position among `matchCount`, or -1 when there is no
   * single current range to place. `findNext`/`findPrev` are modular over the
   * range list, so this index — not the count — is what says the wrap-around
   * works.
   */
  currentIndex: number;
}

/**
 * The accessible name of the `Internal` tag a revealed internal trace carries,
 * from `TraceMessage.tsx`'s `INTERNAL_TRACE_TOOLTIP`. The tag is a `role="note"`
 * whose `aria-label` is this text, which is the only non-structural handle on
 * it — the visible word "Internal" is a two-character-wide element with no role
 * of its own.
 */
export const INTERNAL_TRACE_TAG_LABEL =
  'Internal call (e.g. title generation), hidden by default';

/** The messages-chip tooltip while nothing is hidden. */
export const PLAIN_MESSAGE_COUNT_TOOLTIP = 'Number of messages in the thread';

/**
 * The messages-chip tooltip while traces ARE hidden — the one mitigation for
 * the header disagreeing with every other count of the same thread, so a spec
 * asserting the reduced number has to assert this alongside it.
 */
export const hiddenIncludedMessageCountTooltip = (rawCount: number): string =>
  `${rawCount} messages in the thread, hidden ones included`;

/**
 * The `InternalTracesToggle` label, which is also its `aria-label`. Action-
 * labelled: it names what clicking will do, not the current state.
 */
export const internalCallsToggleLabel = (
  action: 'Show' | 'Hide',
  count: number,
): string => `${action} internal calls (${count})`;

/**
 * localStorage key holding the shared "hidden things are collapsed" preference,
 * from `hiddenSpans.ts`. The thread panel's internal-calls toggle and the trace
 * tree's own eye control are two controls over this ONE key, which is why a
 * spec pins it directly as well as pinning what the panel renders: the shared
 * key is the contract between them, and a panel that kept its state privately
 * would render identically while silently breaking the trace tree.
 */
export const TRACE_HIDE_SPANS_KEY = 'trace-hide-spans';

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

  /**
   * Wait for the panel to load a thread whose every trace is hidden, and so
   * renders its empty state rather than any turn.
   *
   * The sibling of `waitForFullyLoaded`, which cannot serve here: it gates on
   * the first turn becoming visible, and the whole claim about this thread is
   * that no turn does.
   */
  async waitForOnlyInternalCalls(): Promise<void> {
    return test.step(`Wait for thread panel ${this.threadId} to load with no visible turns`, async () => {
      await this.page.waitForURL((url) => url.searchParams.get('thread') === this.threadId);
      await this.root.waitFor({ state: 'visible', timeout: 30_000 });
      await this.onlyInternalCallsMessage.waitFor({ state: 'visible', timeout: 30_000 });
    });
  }

  /** All conversation turns, in render (chronological) order. */
  get turns(): Locator {
    return this.root.locator('[data-trace-message-id]');
  }

  // --- Internal calls (traces the SDK flagged `metadata._opik.is_internal`) ---
  //
  // Messages drops these outright rather than collapsing them — one trace is
  // one turn, so there is no tree to re-parent — and subtracts them from the
  // header's message count while they are hidden. Three things are therefore
  // assertable and none implies another: which turns render, what the header
  // says, and what the control offers to do.

  /**
   * The internal-calls toggle, matched on the exact label it must carry.
   *
   * Both halves are load-bearing and neither is cosmetic. The action word is
   * the control's own state — "Show" means hiding is ON — and the count is the
   * number of traces it would reveal, which deliberately EXCLUDES a trace the
   * URL is keeping listed. Matching the whole label as an accessible name is
   * what makes "the toggle flipped" distinguishable from "the toggle re-rendered
   * with a different count".
   */
  internalCallsToggle(action: 'Show' | 'Hide', count: number): Locator {
    return this.root.getByRole('button', {
      name: internalCallsToggleLabel(action, count),
      exact: true,
    });
  }

  /**
   * Any internal-calls control, whatever its label — for asserting the control
   * is ABSENT.
   *
   * `InternalTracesToggle` returns null at a count of zero, so "no control
   * renders" is a real state with its own meaning: every internal trace in this
   * thread is one the view is keeping listed anyway, so there is nothing to
   * reveal. Asserting the absence of one specific label would pass just as
   * happily against a control that rendered with the wrong count.
   */
  get anyInternalCallsToggle(): Locator {
    return this.root.getByRole('button', { name: /internal calls \(\d+\)/ });
  }

  /** Click the internal-calls toggle and wait for the label to flip. */
  async toggleInternalCalls(action: 'Show' | 'Hide', count: number): Promise<void> {
    return test.step(`Click "${internalCallsToggleLabel(action, count)}"`, async () => {
      const button = this.internalCallsToggle(action, count);
      await expect(button, `exactly one "${action} internal calls (${count})" control`).toHaveCount(
        1,
      );
      await button.click();
      // The label is derived from the same preference the click writes, so the
      // flip is the settle signal — no fixed wait, and no reading the turn list
      // before the re-render that the click causes.
      const flipped = action === 'Show' ? 'Hide' : 'Show';
      await expect(
        this.internalCallsToggle(flipped, count),
        `the control reads "${internalCallsToggleLabel(flipped, count)}" after clicking`,
      ).toBeVisible();
    });
  }

  /**
   * The `Internal` tag on one turn, located by the turn's trace id.
   *
   * Scoped to the turn rather than looked up panel-wide on purpose: a badge
   * rendered on the wrong turn is exactly the regression a panel-wide "one tag
   * exists" assertion would miss.
   */
  internalTag(traceId: string): Locator {
    return this.turn(traceId).getByRole('note', { name: INTERNAL_TRACE_TAG_LABEL, exact: true });
  }

  /** Every `Internal` tag in the panel — for pinning how many turns are badged. */
  get internalTags(): Locator {
    return this.root.getByRole('note', { name: INTERNAL_TRACE_TAG_LABEL, exact: true });
  }

  /**
   * The empty state shown when the thread has traces but every one of them is
   * hidden. Distinct from a thread with no traces at all, which is the point:
   * before this existed the tab went blank and read as a load failure.
   */
  get onlyInternalCallsMessage(): Locator {
    return this.root.getByText(ONLY_INTERNAL_CALLS_MESSAGE, { exact: true });
  }

  /**
   * Assert the messages chip's hover tooltip reads `expected`.
   *
   * The tooltip is the only thing on the page that explains a header count
   * disagreeing with every other count of the same thread, so a spec asserting
   * the reduced number asserts this too — a reduced count with the plain
   * tooltip is a number with no explanation, which is the part a user would
   * report as a bug.
   *
   * Radix portals the content out of the panel, so it is looked up at page
   * scope by role and asserted to be the only one, the same way
   * `ItemReturnNavPage.expectBackTooltip` does. `TooltipWrapper` opens on a
   * timer, so the wait is on the tooltip appearing rather than on a delay.
   *
   * No dismissal afterwards, deliberately. Radix shares one tooltip provider
   * across the page, so hovering the next trigger swaps the content rather than
   * adding to it and the `toHaveCount(1)` below keeps holding. Trying to close
   * it instead is what does not work: a `pointerleave` synthesised by moving
   * the mouse away leaves the tooltip open and visible, and `Escape` — which
   * `TooltipWrapper` does honour — also unmounts the side panel's content, so
   * the dismissal would cost the assertions after it.
   */
  async expectMessagesChipTooltip(count: number, expected: string): Promise<void> {
    await test.step(`the "${count} messages" chip's tooltip reads "${expected}"`, async () => {
      const chip = this.messagesChip(count);
      await expect(chip, `exactly one "${count} messages" chip in the header`).toHaveCount(1);
      await chip.hover();
      const tooltip = this.page.getByRole('tooltip');
      await expect(tooltip, 'exactly one tooltip raised by the messages chip').toHaveCount(1, {
        timeout: 10_000,
      });
      await expect(tooltip, "the messages chip's tooltip").toHaveText(expected, {
        timeout: 15_000,
      });
    });
  }

  /**
   * Read the shared hide preference out of localStorage.
   *
   * Returns the raw stored string, not a boolean: `use-local-storage-state`
   * stores JSON, and an absent key (the default-value case, which READS as
   * hidden) is a different fact from a stored `"true"`. A caller that needs the
   * preference to have been persisted has to be able to tell those apart —
   * collapsing both to `true` is how "the toggle never wrote anything" passes
   * as "the toggle persisted hiding".
   */
  async readHideSpansPreference(): Promise<string | null> {
    return test.step(`Read localStorage["${TRACE_HIDE_SPANS_KEY}"]`, async () =>
      this.page.evaluate((key) => window.localStorage.getItem(key), TRACE_HIDE_SPANS_KEY));
  }

  /**
   * Measure the boxes of the panel's message list — see
   * `measureTraceMessages` for why geometry rather than a screenshot.
   *
   * Lives on the POM rather than being called with a raw locator from the spec
   * so the SME view's identical method and this one stay visibly the same
   * question asked of two components that do not share code.
   */
  async measureMessages(): Promise<TraceMessagesGeometry> {
    return measureTraceMessages(this.turns);
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

  /**
   * The header's duration chip, matched on the formatted string it must show.
   *
   * The chip is an unlabelled `<div>` holding a clock icon and the output of
   * `formatDuration(thread.duration, false)`; its only accessible name is a
   * hover tooltip ("Thread duration") rendered in a portal, so there is no
   * role, label or testid to select it by. Matching the expected text exactly
   * and scoped to the panel is the most stable handle available on a deployed
   * build — the FE should grow a `data-testid="thread-duration"` here, and this
   * should move to it.
   *
   * Exact, not substring: "0.005s" is a substring of nothing else here, but
   * "1h 15.3s" would also match a hypothetical "1h 15.3s ago", and a duration
   * assertion that passes on a longer string is not asserting the format.
   */
  durationChip(formatted: string): Locator {
    return this.root.getByText(formatted, { exact: true });
  }

  /**
   * The header's "N messages" chip, matched on the string it must show.
   *
   * Same shape and same caveat as `durationChip`: an unlabelled `<div>` holding
   * a hash icon and `` `${thread.number_of_messages} messages` ``, whose only
   * accessible name is a portal-rendered tooltip ("Number of messages in the
   * thread"). There is no role, label or testid to select it by, and this spec
   * ships into a repo where the frontend cannot be touched in the same change,
   * so the exact text scoped to the panel is the most stable handle available.
   * The FE should grow a `data-testid="thread-message-count"` here.
   *
   * Exact, so "4 messages" cannot be satisfied by "14 messages" — the
   * substring match is precisely the one that would hide a leaked aggregate.
   */
  messagesChip(count: number): Locator {
    return this.root.getByText(`${count} messages`, { exact: true });
  }

  /**
   * The header's estimated-cost chip.
   *
   * `formatCost` floors to two decimals and renders anything under $0.01 as the
   * literal "<$0.01", so a caller asserting a real number has to seed a thread
   * above that floor — below it every thread in the project renders the same
   * string and the chip can no longer tell them apart.
   */
  costChip(formatted: string): Locator {
    return this.root.getByText(formatted, { exact: true });
  }

  // --- In-panel message search (opik#9329) ---
  //
  // `ExpandableSearchInput` in the panel's own toolbar, feeding `useThreadSearch`.
  // Two separate things happen on one term and neither implies the other: the
  // turn list narrows to the traces `traceMatchesSearch` accepts, and the
  // surviving bodies get `Range`s registered in the CSS Custom Highlight
  // registry. A spec has to read both.
  //
  // Note this is NOT the Threads table's "Search by anything" box
  // (`LogsPage.searchFor`): that one is a server-side `search` param over
  // `ThreadDAO`, deciding which THREADS to list. This one never leaves the
  // browser.

  /**
   * Every rendered message body in the panel.
   *
   * `[data-message-content]` is the FE's own marker and the exact selector
   * `useThreadSearch` searches within, so counting these is counting the thing
   * the search narrowed. `TraceMessage` stamps two per turn (the input bubble
   * and the output block), which is why the body count and the turn count are
   * different numbers and both worth asserting.
   */
  get messageBodies(): Locator {
    return this.root.locator('[data-message-content]');
  }

  /**
   * The toolbar button that expands the search box.
   *
   * Icon-only: no text, no `aria-label`, no `data-testid` — the "Search
   * messages" string is a `TooltipWrapper` content rendered in a portal only
   * while hovered, so it is not an accessible name. The lucide class of the
   * icon it carries is the only handle, scoped to the panel, the same way
   * `LogsPage`'s pagination buttons are addressed and for the same reason.
   * **A `data-testid` belongs on this button**; it is not added in this change
   * because these specs are verified against a deployed environment, which a
   * front-end change in the same PR would not reach — so the spec could not be
   * run before review.
   */
  get searchTrigger(): Locator {
    return this.root.locator('button:has(svg.lucide-search)');
  }

  /**
   * The search box itself, once expanded.
   *
   * Addressed by its placeholder, which `ThreadDetailsPanel` passes explicitly
   * ("Search messages") and is the FE's most stable handle here short of a
   * testid. Scoped to the panel: the Logs page behind it mounts its own
   * `SearchInput`, and the annotate side-panel can mount more.
   */
  get searchInput(): Locator {
    return this.root.getByPlaceholder('Search messages');
  }

  /** The panel's "no search matches" empty state. */
  get noSearchMatches(): Locator {
    return this.root.getByText(NO_SEARCH_MATCHES_MESSAGE, { exact: true });
  }

  /**
   * Expand the search box and leave it focused.
   *
   * The box exists in the DOM while collapsed — `overlayExpand` renders it into
   * a zero-width, `inert` overlay — so "the input is attached" says nothing.
   * Gated on it being editable, which is the state a `fill()` needs and the one
   * the overlay withholds.
   */
  async openSearch(): Promise<void> {
    return test.step('Expand the thread panel search box', async () => {
      await expect(
        this.searchTrigger,
        'exactly one search trigger in the thread panel toolbar',
      ).toHaveCount(1);
      await this.searchTrigger.click();
      await expect(this.searchInput, 'exactly one search box in the panel').toHaveCount(1);
      await expect(this.searchInput, 'the expanded search box is editable').toBeEditable();
    });
  }

  /**
   * Type `term` into the search box and wait until the panel has taken it.
   *
   * The settle is the `thread_panel_search` URL param, which is the panel's
   * single source of truth for the term and what both the narrowing and the
   * highlighting are derived from. `DebounceInput` holds the keystrokes for
   * 300ms, so a caller that read the turn list straight after typing would read
   * the pre-search answer; waiting on the committed param rather than on a
   * fixed delay is what keeps that deterministic.
   *
   * Compared VERBATIM, not folded: unlike the Logs search box, this panel
   * commits the raw string (`setSearch(value)`), which is what lets a
   * whitespace-only term be a real, assertable state.
   *
   * Does NOT wait for any particular turn count — what the panel then shows is
   * the assertion, and a POM that waited for an expected count would be
   * deciding the answer before the spec got to.
   */
  async searchMessages(term: string): Promise<void> {
    return test.step(`Search the thread panel for "${term}"`, async () => {
      await this.searchInput.fill(term);
      await this.page.waitForURL(
        (url) => url.searchParams.get('thread_panel_search') === term,
        { timeout: 15_000 },
      );
    });
  }

  /**
   * Step the current match with the keyboard.
   *
   * ArrowDown is `findNext`, ArrowUp is `findPrev`, both handled by
   * `ExpandableSearchInput`'s own `onKeyDown` — so the key has to land on the
   * INPUT, not on the panel. Pressed through the input locator for that reason.
   *
   * There is deliberately no settle here: `setCurrentIndex` is synchronous
   * React state and the registry is rewritten in the effect that follows it, so
   * the caller polls `readSearchHighlights()` for the index it expects rather
   * than this method guessing one.
   */
  async stepSearchMatch(direction: 'next' | 'previous'): Promise<void> {
    return test.step(`Step to the ${direction} search match`, async () => {
      await this.searchInput.press(direction === 'next' ? 'ArrowDown' : 'ArrowUp');
    });
  }

  /**
   * Dismiss the search with Escape, and wait for the term to leave the URL.
   *
   * Escape is `handleCollapse`, which clears the term as well as closing the
   * box (`onChange?.("")`), so the panel must come back to every turn. The
   * param is set to the empty string rather than dropped, so the wait accepts
   * either — "absent" and "empty" are the same answer to the panel
   * (`searchTerm` is falsy for both) and pinning one of the two here would be
   * asserting `use-query-params`' serialisation, not the panel's behaviour.
   */
  async dismissSearch(): Promise<void> {
    return test.step('Dismiss the thread panel search with Escape', async () => {
      await this.searchInput.press('Escape');
      await this.page.waitForURL(
        (url) => {
          const value = url.searchParams.get('thread_panel_search');
          return value === null || value === '';
        },
        { timeout: 15_000 },
      );
    });
  }

  /**
   * What the search highlighted, read out of `CSS.highlights`.
   *
   * There is no DOM alternative. The Custom Highlight API paints ranges the
   * browser never materialises as elements, so a highlighted match carries no
   * class, no attribute and no node of its own — the registry is the only
   * place the panel records what it found.
   *
   * Throws rather than reporting zeroes when the browser has no registry: "the
   * highlight API is missing" and "the search highlighted nothing" are
   * different answers, and collapsing them would make every assertion here
   * pass on a browser that cannot highlight at all.
   *
   * `currentIndex` is resolved by comparing boundary points rather than by
   * identity: the current-match highlight holds the same `Range` object the
   * match list does today, but that is an implementation detail of the hook,
   * and position is what the assertion is actually about.
   */
  async readSearchHighlights(): Promise<ThreadSearchHighlights> {
    const raw = await this.page.evaluate(
      ([matchName, currentName]) => {
        const registry = (
          CSS as unknown as { highlights?: { get(name: string): Iterable<Range> | undefined } }
        ).highlights;
        if (!registry || typeof registry.get !== 'function') return null;

        const rangesOf = (name: string): Range[] => {
          const highlight = registry.get(name);
          return highlight ? Array.from(highlight) : [];
        };
        const matches = rangesOf(matchName);
        const current = rangesOf(currentName);
        const samePosition = (a: Range, b: Range): boolean =>
          a.compareBoundaryPoints(Range.START_TO_START, b) === 0 &&
          a.compareBoundaryPoints(Range.END_TO_END, b) === 0;

        return {
          matchCount: matches.length,
          matchTexts: matches.map((range) => range.toString()),
          currentCount: current.length,
          currentIndex:
            current.length === 1 ? matches.findIndex((r) => samePosition(r, current[0])) : -1,
        };
      },
      [SEARCH_MATCH_HIGHLIGHT, SEARCH_CURRENT_MATCH_HIGHLIGHT] as const,
    );

    if (raw === null) {
      throw new Error(
        'ThreadPanelPage.readSearchHighlights: this browser exposes no CSS.highlights registry, ' +
          'so the thread panel cannot highlight a search match at all and nothing here is ' +
          'assertable. Reporting zero matches would read as "the search found nothing".',
      );
    }
    return raw;
  }

  // --- Feedback scores tab ---
  //
  // The panel's second tab. It renders the same ConfigurableFeedbackScoreTable
  // the trace panel uses, so the locators mirror TracePanelPage's — but the
  // scores themselves are the THREAD's, which is where thread-level metrics
  // (evaluate_threads) write and where none of the row's own columns show them.

  /** The "Feedback scores" tab trigger inside the panel. */
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
   * Row in the thread scores table for a given score name. Matched on the row's
   * DOM text rather than its accessible name: the Key cell truncates long names
   * with CSS ellipsis, so the accessible name reads as "cuj-…".
   */
  feedbackScoreRow(scoreName: string): Locator {
    return this.feedbackScoresTabPanel.getByRole('row').filter({ hasText: scoreName });
  }

  /**
   * Read the number in the Score column for a named score. Requires the tab to
   * be open. Throws — rather than returning a sentinel — if the cell is not a
   * number, so a renamed column fails loudly instead of comparing as NaN.
   */
  async readFeedbackScoreValue(scoreName: string): Promise<number> {
    return test.step(`Read thread score "${scoreName}"`, async () => {
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
    });
  }
}
