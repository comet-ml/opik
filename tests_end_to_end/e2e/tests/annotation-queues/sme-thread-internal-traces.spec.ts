import { test, expect } from '@e2e/fixtures';
import { SmeFlowPage } from '@e2e/pom/sme-flow.page';

/**
 * The SME annotation view hides a thread's internal traces with the same toggle
 * and the same `Internal` badge as the thread side panel (opik#9073).
 *
 * Why this is its own spec rather than a case on the panel's: the two views do
 * not share a component. `ThreadDataViewer` duplicates the panel's decision
 * over the same `useVisibleTraces`/`InternalTracesToggle` helpers, and the two
 * call them with different arguments — the panel keeps the trace its URL
 * selected, the SME view keeps the one its own side panel has open, and the SME
 * view passes `last(visibleTraces)` where it used to pass `last(traces)`. A
 * green panel spec says nothing about any of that, which is exactly how the two
 * drift apart unnoticed.
 *
 * This is also the first spec in the estate to reach `/sme` at all, so it
 * carries the route's first page object. `annotation-queues.sme-flow` was
 * `covered: false` with no POM behind it.
 */
test.describe(
  'SME annotation view — internal traces',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'hides a thread\'s internal traces, with the same toggle and Internal badge',
      { tag: ['@cap:annotation-queues.sme-flow'] },
      async ({ smeInternalThreadQueue, internalTraceThreads, page }) => {
        const { main } = internalTraceThreads;
        const [internalTurn] = main.internalTurns;
        const sme = new SmeFlowPage(page);

        await test.step('Open the SME flow and enter the annotation view', async () => {
          await sme.goto(smeInternalThreadQueue.queueId);
          await expect(
            sme.welcomeHeading(smeInternalThreadQueue.queueName),
            'the instructions page names the queue the link points at',
          ).toBeVisible({ timeout: 30_000 });
          await sme.startAnnotating();
          await sme.waitForThreadReady();
        });

        await test.step('Only the conversation turns are listed, the internal trace absent', async () => {
          await expect(
            sme.turns,
            'one turn per non-internal trace, as in the thread panel',
          ).toHaveCount(main.plainTurns.length);
          expect(await sme.readTurnTraceIdsInOrder()).toEqual(
            main.plainTurns.map((t) => t.traceId),
          );
          await expect(
            sme.turn(internalTurn.traceId),
            'a reviewer is not shown a title-generation call as something to score',
          ).toHaveCount(0);
          await expect(sme.internalTags, 'nothing is badged while nothing is shown').toHaveCount(0);
        });

        await test.step('The same labelled control offers to reveal the one internal call', async () => {
          await expect(
            sme.internalCallsToggle('Show', main.internalTurns.length),
            'the SME header carries the same control, with the same count',
          ).toBeVisible();
        });

        await test.step('Revealing lists every trace in order and badges the internal one', async () => {
          await sme.toggleInternalCalls('Show', main.internalTurns.length);
          await expect(sme.turns).toHaveCount(main.turns.length);
          expect(await sme.readTurnTraceIdsInOrder()).toEqual(main.turns.map((t) => t.traceId));
          await expect(
            sme.internalTags,
            'exactly one turn is badged Internal',
          ).toHaveCount(main.internalTurns.length);
          await expect(
            sme.internalTag(internalTurn.traceId),
            'the badge sits on the internal turn, not a neighbour',
          ).toBeVisible();
        });

        await test.step('Hiding returns to the conversation turns alone', async () => {
          await sme.toggleInternalCalls('Hide', main.internalTurns.length);
          await expect(sme.turns).toHaveCount(main.plainTurns.length);
          expect(await sme.readTurnTraceIdsInOrder()).toEqual(
            main.plainTurns.map((t) => t.traceId),
          );
          await expect(sme.internalTags).toHaveCount(0);
        });
      },
    );
  },
);
