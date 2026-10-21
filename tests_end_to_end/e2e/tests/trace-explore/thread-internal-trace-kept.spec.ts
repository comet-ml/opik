import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { PLAIN_MESSAGE_COUNT_TOOLTIP } from '@e2e/pom/thread-panel.page';

/**
 * The one place the hide rule is deliberately overridden: a trace named in the
 * URL stays listed even when it is internal, because the panel has to be able
 * to scroll to the turn the link selected (opik#9073).
 *
 * Two things a reader would care about being wrong, and both are URL-driven, so
 * there is no timing or data dependence in either:
 *
 *  - a deep link that hides the very trace it selected, leaving the panel
 *    scrolled to nothing;
 *  - a count that double-counts the trace it is keeping — the toggle's count
 *    excludes the kept trace, because toggling can never hide it, so a control
 *    offering to "show internal calls (1)" that would reveal nothing is a
 *    visual no-op that still flips a shared preference.
 *
 * Both branches are invisible to the frontend's own vitest suite, which mocks
 * the hooks the `keepTraceId` decision is made from.
 */
test.describe(
  'Thread Messages — URL-selected internal trace',
  { tag: ['@t2-cuj', '@area:threads'] },
  () => {
    test(
      'keeps the selected internal trace listed and badged, and out of the toggle count',
      { tag: ['@cap:threads.turn-order-io', '@cap:threads.thread-message-count'] },
      async ({ internalTraceThreads, page }) => {
        const { main } = internalTraceThreads;
        const [internalTurn] = main.internalTurns;
        const logs = new LogsPage(page);

        const panel = await test.step('Open the thread on its internal trace, hiding still on', async () => {
          await logs.gotoThreads(internalTraceThreads.projectId);
          await logs.waitForThreadsReady(main.threadId);
          const panel = await logs.openThreadById(main.threadId, {
            traceId: internalTurn.traceId,
          });
          await panel.waitForFullyLoaded();
          // Nothing here clicked the toggle: this is the hide rule being
          // overridden for one trace, not the preference being off. Asserted,
          // because if hiding were simply off then every assertion below would
          // pass while exercising nothing at all.
          //
          // `'true'` rather than `null` — `useLocalStorageState` persists its
          // own default on mount, so the key exists by the time the panel has
          // rendered a turn. The value is what matters either way.
          expect(
            await panel.readHideSpansPreference(),
            'hiding is still the active preference',
          ).toBe('true');
          return panel;
        });

        await test.step('Every trace is listed in order, the selected internal one included', async () => {
          await expect(
            panel.turns,
            'the selected internal trace is listed alongside the conversation',
          ).toHaveCount(main.turns.length);
          expect(await panel.readTurnTraceIdsInOrder()).toEqual(main.turns.map((t) => t.traceId));
          await expect(
            panel.turn(internalTurn.traceId),
            'the trace the URL selected renders its turn',
          ).toHaveCount(1);
        });

        await test.step('The kept trace is still badged Internal', async () => {
          // Kept is not the same as reclassified: it is still an internal call,
          // and a reader landing on this link needs to be told so rather than
          // reading a title-generation prompt as something the user said.
          await expect(panel.internalTags, 'exactly one badged turn').toHaveCount(1);
          await expect(panel.internalTag(internalTurn.traceId)).toBeVisible();
        });

        await test.step('No internal-calls control renders, and the header reads the raw count', async () => {
          await expect(
            panel.anyInternalCallsToggle,
            'the only internal trace is the kept one, so there is nothing to reveal',
          ).toHaveCount(0);
          await expect(
            panel.messagesChip(main.rawMessageCount),
            'nothing is hidden, so the header matches the backend',
          ).toBeVisible();
          await panel.expectMessagesChipTooltip(
            main.rawMessageCount,
            PLAIN_MESSAGE_COUNT_TOOLTIP,
          );
        });
      },
    );

    test(
      'counts only the internal traces it is not keeping',
      { tag: ['@cap:threads.thread-message-count'] },
      async ({ internalTraceThreads, page }) => {
        const { allInternal } = internalTraceThreads;
        const [keptTurn, hiddenTurn] = allInternal.turns;
        const logs = new LogsPage(page);

        // Both traces are internal and one is kept, so the control must come
        // back with a count of exactly 1 — the other one. This is the assertion
        // the sibling test above cannot make: there, "no control" and "a control
        // counting the kept trace" are told apart by an absence, and an absence
        // cannot show that the count is computed rather than merely suppressed.
        const panel = await test.step('Open the all-internal thread on one of its traces', async () => {
          await logs.gotoThreads(internalTraceThreads.projectId);
          await logs.waitForThreadsReady(allInternal.threadId);
          const panel = await logs.openThreadById(allInternal.threadId, {
            traceId: keptTurn.traceId,
          });
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('Only the kept trace is listed, and the control offers the other one', async () => {
          await expect(panel.turns, 'only the trace the URL selected').toHaveCount(1);
          expect(await panel.readTurnTraceIdsInOrder()).toEqual([keptTurn.traceId]);
          await expect(
            panel.turn(hiddenTurn.traceId),
            'the internal trace nobody selected stays hidden',
          ).toHaveCount(0);
          await expect(
            panel.internalCallsToggle('Show', 1),
            'the count excludes the kept trace and includes the other',
          ).toBeVisible();
        });

        await test.step('The header subtracts only the trace that is actually hidden', async () => {
          // 4 - 1*2 = 2. Reading 0 here would mean the panel subtracted the
          // trace it is showing; reading 4 would mean it subtracted nothing.
          await expect(panel.messagesChip(2)).toBeVisible();
          await expect(panel.messagesChip(0)).toHaveCount(0);
          await expect(panel.messagesChip(allInternal.rawMessageCount)).toHaveCount(0);
        });

        await test.step('Revealing lists both, each badged, and restores the raw count', async () => {
          await panel.toggleInternalCalls('Show', 1);
          await expect(panel.turns).toHaveCount(allInternal.turns.length);
          expect(await panel.readTurnTraceIdsInOrder()).toEqual(
            allInternal.turns.map((t) => t.traceId),
          );
          await expect(panel.internalTags).toHaveCount(allInternal.turns.length);
          await expect(panel.messagesChip(allInternal.rawMessageCount)).toBeVisible();
        });
      },
    );
  },
);
