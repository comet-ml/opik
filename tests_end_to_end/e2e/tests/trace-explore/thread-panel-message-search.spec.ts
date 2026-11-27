import { test, expect } from '@e2e/fixtures';
import type { ThreadSearchTermRef, ThreadSearchTurnsRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { ThreadPanelPage } from '@e2e/pom/thread-panel.page';

/**
 * The thread detail panel's own "Search messages" box (opik#9329) narrows the
 * conversation to the matching turns, highlights every occurrence inside them,
 * and steps through the occurrences with wrap-around.
 *
 * `useThreadSearch` does two things on one term, and neither implies the other:
 *
 *  - `traceMatchesSearch` decides which TURNS survive, matching over
 *    `prettifyMessage(trace.input)` and `prettifyMessage(trace.output)`.
 *  - `findTextRanges` then walks the surviving `[data-message-content]` nodes
 *    and registers one `Range` per occurrence in the `thread-search-match` CSS
 *    custom highlight, with the selected one in `thread-search-current-match`.
 *
 * They share a regex but not a code path, so they can drift apart: a turn that
 * narrowing kept with nothing highlighted inside it is a match a user cannot
 * find, and a highlight on a turn narrowing dropped cannot be reached at all.
 * Every term below asserts both numbers.
 *
 * ## Why the highlight is read out of `CSS.highlights`
 *
 * The Custom Highlight API paints ranges the browser never materialises as
 * elements — a highlighted match has no class, no attribute and no node of its
 * own — so there is nothing in the DOM to select and the registry is the only
 * record of what was found. `ThreadPanelPage.readSearchHighlights()` reads it,
 * and throws rather than reporting zeroes on a browser without the API, so this
 * spec cannot go green by being unable to look.
 *
 * ## Not covered here
 *
 * The Threads TABLE's "Search by anything" box, which is a server-side `search`
 * param over `ThreadDAO` deciding which threads to list — a different surface,
 * covered by `thread-free-text-search.spec.ts` under
 * `@cap:threads.free-text-search`. The scroll-into-view and `<details>`-reveal
 * branches of `revealRange`/`scrollRangeIntoView`, which are positioning and
 * asserted nowhere. And the colour the match is painted.
 */

/** The whole seed is seconds old, so a 24-hour window holds all of it. */
const TIME_RANGE = 'past24hours';

/**
 * Assert the panel narrowed and highlighted exactly as `term` claims.
 *
 * Both halves in one helper because asserting one without the other is the
 * mistake this spec exists to avoid, and because the turn ids have to be
 * resolved from the seed's indices either way.
 */
async function expectSearchResult(
  panel: ThreadPanelPage,
  seed: ThreadSearchTurnsRef,
  term: ThreadSearchTermRef,
): Promise<void> {
  const expectedTurnIds = term.matchingTurnIndices.map((index) => seed.turns[index].traceId);

  // Polled, because the narrowing and the re-highlight land in two React
  // effects after the debounced term commits — the URL param is written first.
  // Polled on the whole answer at once (which turns, how many ranges) rather
  // than on each number separately, so a transient state where one had updated
  // and the other had not cannot satisfy two sequential assertions.
  await expect
    .poll(
      async () => {
        const turnIds = await panel.readTurnTraceIdsInOrder();
        const highlights = await panel.readSearchHighlights();
        return { turnIds, matchCount: highlights.matchCount };
      },
      {
        message:
          `the panel to narrow to turns ${JSON.stringify(expectedTurnIds)} and register ` +
          `${term.expectedHighlightCount} highlight range(s) for "${term.term}"`,
        timeout: 15_000,
        intervals: [100, 250, 500, 1_000],
      },
    )
    .toEqual({ turnIds: expectedTurnIds, matchCount: term.expectedHighlightCount });

  // The surviving turns bring both of their bodies with them: narrowing is
  // per TURN, not per message, so the body count is a third number and a
  // narrowing that had become per-message would fail here while satisfying the
  // turn-id assertion above.
  await expect(
    panel.messageBodies,
    `each of the ${expectedTurnIds.length} surviving turns renders both of its bodies`,
  ).toHaveCount(expectedTurnIds.length * 2);

  // And every range sits on the term itself. Without this, a count of three is
  // satisfied by three ranges painted over arbitrary characters — which is
  // exactly what an off-by-one in `range.setStart(node, match.index)` produces.
  const highlights = await panel.readSearchHighlights();
  expect(
    highlights.matchTexts.map((text) => text.toLowerCase()),
    `every highlighted range covers "${term.term}" and nothing else — the search is ` +
      'case-insensitive, so the comparison is folded',
  ).toEqual(new Array(term.expectedHighlightCount).fill(term.term.toLowerCase()));

  // Exactly one range is current while there are matches at all. `new
  // Highlight(current)` taking more than one would mean the hook lost track of
  // which match is selected.
  expect(highlights.currentCount, 'exactly one current match').toBe(1);
}

test.describe(
  'Thread panel — searching inside a conversation',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    test(
      'a term in an input and a term in an output each narrow to their own turn and highlight once',
      { tag: ['@cap:threads.panel-message-search'] },
      async ({ threadSearchTurns, project, page }) => {
        const seed = threadSearchTurns;
        const logs = new LogsPage(page);

        const panel = await test.step('Open the thread panel on the whole conversation', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          await logs.waitForThreadsReady(seed.threadId, { timeout: 90_000 });
          const opened = await logs.openThreadById(seed.threadId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('Every turn renders, in the order it was logged', async () => {
          // The baseline, and the premise the match INDICES rest on: the
          // fixture's `matchingTurnIndices` are positions in render order, and
          // the panel sorts its turns by trace id. Asserting the order here
          // means an ordering surprise fails where it happened rather than
          // shifting every index below by one.
          expect(
            await panel.readTurnTraceIdsInOrder(),
            'the panel renders the seeded turns, oldest first',
          ).toEqual(seed.turns.map((t) => t.traceId));
          await expect(panel.messageBodies, 'two bodies per turn').toHaveCount(
            seed.totalMessageBodies,
          );
        });

        await test.step('A term planted only in an INPUT narrows to its turn', async () => {
          // One of the two halves of `traceMatchesSearch`. A narrowing that had
          // stopped matching over inputs answers correctly for the output term
          // below and wrongly here, which is why both are asked.
          await panel.openSearch();
          await panel.searchMessages(seed.inputOnly.term);
          await expectSearchResult(panel, seed, seed.inputOnly);
        });

        await test.step('The same term in a different case narrows identically', async () => {
          // `createSearchRegex` builds with the `gi` flags, so the search is
          // case-insensitive — and the panel commits the term VERBATIM (unlike
          // the Logs search box, which folds it first), so an upper-cased term
          // really does reach the matcher upper-cased.
          await panel.searchMessages(seed.inputOnly.term.toUpperCase());
          await expectSearchResult(panel, seed, seed.inputOnly);
        });

        await test.step('A term planted only in an OUTPUT narrows to its turn', async () => {
          // The other half. Note the surviving turn is a different one, so a
          // panel stuck on the previous answer fails rather than passing twice.
          await panel.searchMessages(seed.outputOnly.term);
          await expectSearchResult(panel, seed, seed.outputOnly);
        });
      },
    );

    test(
      'a term occurring three times across two turns highlights three ranges and steps through them with wrap-around',
      { tag: ['@cap:threads.panel-message-search'] },
      async ({ threadSearchTurns, project, page }) => {
        const seed = threadSearchTurns;
        const logs = new LogsPage(page);
        const multi = seed.multiOccurrence;

        const panel = await test.step('Open the thread panel on the whole conversation', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          await logs.waitForThreadsReady(seed.threadId, { timeout: 90_000 });
          const opened = await logs.openThreadById(seed.threadId);
          await opened.waitForFullyLoaded();
          expect(
            await opened.readTurnTraceIdsInOrder(),
            'the panel renders the seeded turns, oldest first — the indices below are positions ' +
              'in this order',
          ).toEqual(seed.turns.map((t) => t.traceId));
          return opened;
        });

        await test.step('Two turns survive and three ranges are highlighted', async () => {
          // The number that separates narrowing from highlighting: two turns,
          // four bodies, three ranges. No single miscount satisfies all three.
          await panel.openSearch();
          await panel.searchMessages(multi.term);
          await expectSearchResult(panel, seed, multi);
        });

        await test.step('ArrowDown walks 0 -> 1 -> 2 and wraps back to 0', async () => {
          // `findNext` is `(index + 1) % ranges.length`, so an off-by-one or a
          // clamp instead of a modulo is invisible to any count — only the
          // index says it works. The first two steps stay inside turn 0's pair
          // and the third crosses into turn 4's single occurrence, so the walk
          // is also a walk across a turn boundary.
          const readIndex = async (): Promise<number> =>
            (await panel.readSearchHighlights()).currentIndex;

          expect(await readIndex(), 'a fresh search selects the first match').toBe(0);

          for (const expected of [1, 2, 0]) {
            await panel.stepSearchMatch('next');
            await expect
              .poll(readIndex, {
                message: `the current match to advance to index ${expected}`,
                timeout: 10_000,
                intervals: [100, 250, 500],
              })
              .toBe(expected);
          }
        });

        await test.step('ArrowUp wraps the other way, from 0 back to the last match', async () => {
          // `findPrev` is `(index - 1 + len) % len`. Without the `+ len` this
          // is -1, which indexes nothing — the exact shape the wrap guard
          // exists for, and reachable only from index 0.
          await panel.stepSearchMatch('previous');
          await expect
            .poll(async () => (await panel.readSearchHighlights()).currentIndex, {
              message: 'the current match to wrap back to the last range',
              timeout: 10_000,
              intervals: [100, 250, 500],
            })
            .toBe(multi.expectedHighlightCount - 1);
        });
      },
    );

    test(
      'a whitespace-only term is no search, a non-matching term shows the empty state, and Escape restores the conversation',
      { tag: ['@cap:threads.panel-message-search'] },
      async ({ threadSearchTurns, project, page }) => {
        const seed = threadSearchTurns;
        const logs = new LogsPage(page);

        const panel = await test.step('Open the thread panel on the whole conversation', async () => {
          await logs.gotoThreads(project.id, { timeRange: TIME_RANGE, size: 100 });
          await logs.waitForThreadsReady(seed.threadId, { timeout: 90_000 });
          const opened = await logs.openThreadById(seed.threadId);
          await opened.waitForFullyLoaded();
          return opened;
        });

        await test.step('A term that matches narrows the panel first, so the restorations below mean something', async () => {
          // Each of the three guards in this test is "the panel goes back to
          // showing everything". That is also the panel's resting state, so
          // every one of them would pass over a search that never worked —
          // this step is what makes them restorations rather than tautologies.
          await panel.openSearch();
          await panel.searchMessages(seed.multiOccurrence.term);
          await expectSearchResult(panel, seed, seed.multiOccurrence);
        });

        await test.step('A whitespace-only term narrows nothing and highlights nothing', async () => {
          // `ThreadDetailsPanel` trims before either consumer sees it
          // (`const searchTerm = search?.trim()`), so "   " is no search at
          // all. The guard matters because the untrimmed term is still truthy:
          // a panel that passed it through would narrow to the turns containing
          // a space — every one of them — while highlighting every space in
          // them, which looks almost right and is not.
          await panel.searchMessages('   ');
          await expect
            .poll(
              async () => {
                const turnIds = await panel.readTurnTraceIdsInOrder();
                const highlights = await panel.readSearchHighlights();
                return { turns: turnIds.length, matchCount: highlights.matchCount };
              },
              {
                message: 'the panel to show every turn again with no ranges highlighted',
                timeout: 15_000,
                intervals: [100, 250, 500, 1_000],
              },
            )
            .toEqual({ turns: seed.turns.length, matchCount: 0 });
          await expect(panel.messageBodies, 'every body is back').toHaveCount(
            seed.totalMessageBodies,
          );
          await expect(
            panel.noSearchMatches,
            'and this is not the no-matches state — a whitespace term is no search, not a ' +
              'search that failed',
          ).toBeHidden();
        });

        await test.step('A term that matches nothing shows the empty state', async () => {
          await panel.searchMessages(seed.nonMatchingTerm);
          await expect(panel.noSearchMatches).toBeVisible();
          await expect(
            panel.messageBodies,
            'with no turn rendered at all — the empty state replaces the list rather than ' +
              'sitting above it',
          ).toHaveCount(0);
          expect(
            (await panel.readSearchHighlights()).matchCount,
            'and nothing highlighted',
          ).toBe(0);
        });

        await test.step('Escape restores every turn and empties the highlight registry', async () => {
          // Escape is `handleCollapse`, which clears the term as well as
          // closing the box, so this is the one restoration a user reaches for
          // by reflex. The registry is asserted empty as well as the turns
          // restored: the match highlight is torn down by an effect cleanup, so
          // a leaked entry would leave ranges painted over text that is no
          // longer a match.
          await panel.dismissSearch();
          await expect
            .poll(
              async () => {
                const turnIds = await panel.readTurnTraceIdsInOrder();
                const highlights = await panel.readSearchHighlights();
                return {
                  turnIds,
                  matchCount: highlights.matchCount,
                  currentCount: highlights.currentCount,
                };
              },
              {
                message: 'Escape to restore every turn and clear both highlights',
                timeout: 15_000,
                intervals: [100, 250, 500, 1_000],
              },
            )
            .toEqual({
              turnIds: seed.turns.map((t) => t.traceId),
              matchCount: 0,
              currentCount: 0,
            });
          await expect(panel.messageBodies).toHaveCount(seed.totalMessageBodies);
          await expect(panel.noSearchMatches).toBeHidden();
        });
      },
    );
  },
);
