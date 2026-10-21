import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import {
  PLAIN_MESSAGE_COUNT_TOOLTIP,
  hiddenIncludedMessageCountTooltip,
} from '@e2e/pom/thread-panel.page';

/**
 * Thread Messages hides traces the SDK marked internal — an integration's
 * title-generation call and the like — and subtracts them from the header's
 * message count while they are hidden (opik#9073).
 *
 * The axis here is silent wrongness, which is why the API half is not
 * decoration: the backend is unchanged, so `number_of_messages` still counts a
 * flat two per trace INCLUDING the hidden ones, and the panel's header
 * deliberately disagrees with it. Every assertion below pins one side of that
 * disagreement against the other. A spec that only counted rendered turns would
 * pass against a panel that hid the right traces and then showed a count for a
 * conversation nobody can see.
 *
 * `threads.thread-message-count` is already covered at t1, but by
 * `thread-logging-smoke` and `thread-moved-trace-aggregate`, which both seed
 * conversations with no internal trace at all — so both stay green against a
 * completely broken toggle. This is the hidden/internal axis of that capability,
 * not a second test of the clean one.
 */
test.describe('Thread Messages — internal traces', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test(
    'hides an internal trace, reduces the header count, and the toggle round-trips',
    { tag: ['@cap:threads.thread-message-count', '@cap:threads.turn-order-io'] },
    async ({ internalTraceThreads, backendClient, page }) => {
      const { main } = internalTraceThreads;
      const [internalTurn] = main.internalTurns;
      // 3 traces, one of them hidden: 6 - 2 = 4.
      const hiddenCount = 4;
      const logs = new LogsPage(page);

      const panel = await test.step('Open the thread panel with hiding at its default', async () => {
        await logs.gotoThreads(internalTraceThreads.projectId);
        await logs.waitForThreadsReady(main.threadId);
        const panel = await logs.openThreadById(main.threadId);
        await panel.waitForFullyLoaded();
        return panel;
      });

      await test.step('Only the conversation turns render, and the internal trace is absent', async () => {
        await expect(panel.turns, 'one turn per non-internal trace').toHaveCount(
          main.plainTurns.length,
        );
        expect(await panel.readTurnTraceIdsInOrder()).toEqual(
          main.plainTurns.map((t) => t.traceId),
        );
        await expect(
          panel.turn(internalTurn.traceId),
          'the internal trace renders no turn while hiding is on',
        ).toHaveCount(0);
        // Nothing is badged while nothing internal is on screen — a tag on a
        // hidden turn would mean the turn leaked into the DOM invisibly.
        await expect(panel.internalTags, 'no Internal tag while hiding is on').toHaveCount(0);
      });

      await test.step('The header reports the reduced count, and says it is reduced', async () => {
        await expect(
          panel.messagesChip(hiddenCount),
          'the header subtracts two messages per hidden trace',
        ).toBeVisible();
        await expect(
          panel.messagesChip(main.rawMessageCount),
          'the header does NOT show the backend\'s unreduced count',
        ).toHaveCount(0);
        await panel.expectMessagesChipTooltip(
          hiddenCount,
          hiddenIncludedMessageCountTooltip(main.rawMessageCount),
        );
      });

      await test.step('The backend still reports the unreduced count the header disagrees with', async () => {
        // Read while the panel is on screen showing 4, so the disagreement is
        // pinned as simultaneous rather than as something that was once true.
        // This is the half that makes the UI number meaningful: a backend that
        // had started subtracting too would turn the panel's 4 into a correct
        // echo rather than the deliberate divergence this release introduced,
        // and every assertion above would still pass.
        const thread = await backendClient.getThread({
          projectId: internalTraceThreads.projectId,
          threadId: main.threadId,
        });
        expect(
          thread.numberOfMessages,
          'the backend counts two messages per trace, hidden ones included',
        ).toBe(main.rawMessageCount);
        expect(thread.numberOfMessages).not.toBe(hiddenCount);
      });

      await test.step('Revealing lists every trace in order, badges the internal one, and restores the count', async () => {
        await panel.toggleInternalCalls('Show', main.internalTurns.length);

        await expect(panel.turns, 'one turn per trace once revealed').toHaveCount(
          main.turns.length,
        );
        expect(await panel.readTurnTraceIdsInOrder()).toEqual(main.turns.map((t) => t.traceId));
        await expect(
          panel.internalTags,
          'exactly one turn is badged Internal',
        ).toHaveCount(main.internalTurns.length);
        await expect(
          panel.internalTag(internalTurn.traceId),
          'the badge sits on the internal turn, not a neighbour',
        ).toBeVisible();

        await expect(
          panel.messagesChip(main.rawMessageCount),
          'the header returns to the backend count once nothing is hidden',
        ).toBeVisible();
        await panel.expectMessagesChipTooltip(
          main.rawMessageCount,
          PLAIN_MESSAGE_COUNT_TOOLTIP,
        );
      });

      await test.step('The revealed state persists across a reload, through the shared preference', async () => {
        // The preference is the contract between this control and the trace
        // tree's own eye toggle. Pinned as the stored string: an absent key
        // READS as hidden, so "no write happened" and "hiding was stored" must
        // not be collapsible into one assertion.
        expect(
          await panel.readHideSpansPreference(),
          'revealing stores hiding-off in the shared preference',
        ).toBe('false');

        await page.reload();
        await panel.waitForFullyLoaded();
        await expect(panel.turns, 'the reload keeps every trace listed').toHaveCount(
          main.turns.length,
        );
        await expect(
          panel.internalTag(internalTurn.traceId),
          'the badge survives the reload',
        ).toBeVisible();
        await expect(panel.messagesChip(main.rawMessageCount)).toBeVisible();
      });

      await test.step('Hiding again returns to the reduced view', async () => {
        await panel.toggleInternalCalls('Hide', main.internalTurns.length);
        await expect(panel.turns).toHaveCount(main.plainTurns.length);
        expect(await panel.readTurnTraceIdsInOrder()).toEqual(
          main.plainTurns.map((t) => t.traceId),
        );
        await expect(panel.internalTags).toHaveCount(0);
        await expect(panel.messagesChip(hiddenCount)).toBeVisible();
        expect(
          await panel.readHideSpansPreference(),
          'hiding stores hiding-on in the shared preference',
        ).toBe('true');
      });
    },
  );

  test(
    'explains a thread whose every trace is internal instead of rendering a blank tab',
    { tag: ['@cap:threads.thread-message-count'] },
    async ({ internalTraceThreads, page }) => {
      const { allInternal } = internalTraceThreads;
      const logs = new LogsPage(page);

      const panel = await test.step('Open the all-internal thread with hiding at its default', async () => {
        await logs.gotoThreads(internalTraceThreads.projectId);
        await logs.waitForThreadsReady(allInternal.threadId);
        const panel = await logs.openThreadById(allInternal.threadId);
        await panel.waitForOnlyInternalCalls();
        return panel;
      });

      await test.step('The empty state explains the absence, and the header reads zero', async () => {
        await expect(panel.turns, 'no turn renders').toHaveCount(0);
        await expect(panel.onlyInternalCallsMessage).toBeVisible();
        // 4 - 2*2 = 0, and `Math.max(..., 0)` is what keeps a thread of more
        // hidden traces than messages off a negative number.
        await expect(
          panel.messagesChip(0),
          'the header reads 0 messages, not the backend\'s 4',
        ).toBeVisible();
        await expect(panel.messagesChip(allInternal.rawMessageCount)).toHaveCount(0);
        await panel.expectMessagesChipTooltip(
          0,
          hiddenIncludedMessageCountTooltip(allInternal.rawMessageCount),
        );
      });

      await test.step('Revealing clears the empty state and lists every internal trace, badged', async () => {
        await panel.toggleInternalCalls('Show', allInternal.turns.length);
        await expect(panel.turns).toHaveCount(allInternal.turns.length);
        expect(await panel.readTurnTraceIdsInOrder()).toEqual(
          allInternal.turns.map((t) => t.traceId),
        );
        await expect(
          panel.internalTags,
          'every revealed trace is badged Internal',
        ).toHaveCount(allInternal.turns.length);
        await expect(
          panel.onlyInternalCallsMessage,
          'the empty state is gone once the traces are listed',
        ).toHaveCount(0);
        await expect(panel.messagesChip(allInternal.rawMessageCount)).toBeVisible();
      });

      await test.step('Hiding restores the empty state', async () => {
        await panel.toggleInternalCalls('Hide', allInternal.turns.length);
        await expect(panel.turns).toHaveCount(0);
        await expect(panel.onlyInternalCallsMessage).toBeVisible();
        await expect(panel.messagesChip(0)).toBeVisible();
      });
    },
  );
});
