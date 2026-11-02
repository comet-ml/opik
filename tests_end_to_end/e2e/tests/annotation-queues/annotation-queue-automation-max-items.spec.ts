import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

/**
 * `max_items_in_queue` — the ceiling that stands between a badly scoped
 * automation condition and a review queue nobody can work.
 *
 * Newly load-bearing as of opik #8687, which turned queue automation on by
 * default: before it, nothing filled a queue automatically, so the ceiling had
 * nothing to bind. Nothing in the estate asserts it today.
 *
 * Three claims, and each one fails differently:
 *
 *  1. **The ceiling binds.** Five matching traces into a queue of 2 leave 2.
 *  2. **It picks deterministically.** `AnnotationQueueService.fillToMaxItems`
 *     sorts the eligible ids and truncates, precisely so which items survive is
 *     not hash order. Asserting the count alone would pass over a build that
 *     admitted an arbitrary two.
 *  3. **It binds AUTOMATED adds only.** A person adding a trace by hand to an
 *     already-full queue still gets it in — the cap governs what automation may
 *     do, not what the queue may hold. Reading it as a hard size limit would
 *     turn a full queue into one a reviewer cannot add to, which is the more
 *     damaging failure of the two.
 *
 * Then the list's `Cap reached` pill, on the UI surface, which is a genuinely
 * separate assertion rather than a restatement of the API's numbers:
 * `AutomationCell` derives it in the frontend from `items_count >=
 * max_items_in_queue`, so a queue at its ceiling server-side can still render
 * `On`.
 *
 * On determinism: the backend documents this ceiling as approximate, but only
 * under concurrent consumers draining the same queue. A single scored batch,
 * which is what this seeds, is exact.
 */

const SCORE_THRESHOLD = 0.5;
const MATCHING_SCORE = 0.1;
const MAX_ITEMS_IN_QUEUE = 2;
/** Enough to overflow the ceiling with room to spare, so truncation really runs. */
const MATCHING_TRACE_COUNT = 5;

/**
 * An item is routed between `debounceDelay` (15s) and `debounceDelay +
 * jobInterval` (20s) after its last score, per `annotationQueueRouting` in
 * config.yml. The budget is several times that so a loaded CI box queueing the
 * flush job behind other work reads as slow, not as broken.
 */
const ROUTING_TIMEOUT_MS = 120_000;

test.describe('Annotation queues — automation ceiling', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  test(
    'max_items_in_queue caps automated adds while a manual add still bypasses it',
    { tag: ['@cap:annotation-queues.automation-max-items'] },
    async ({
      project,
      sdkClient,
      backendClient,
      registerAnnotationQueueCleanup,
      testNamespace,
      page,
    }) => {
      // Namespaced so the condition names a score no other run could write, and
      // so nothing already in this workspace can satisfy it by accident.
      const scoreName = `${testNamespace}-relevance`;
      const queueId = uuid7();
      const queueName = `${testNamespace}-capped-queue`;

      const matchingIds = await test.step(
        `Seed ${MATCHING_TRACE_COUNT} traces that all satisfy the condition`,
        async () => {
          const ids: string[] = [];
          for (let i = 0; i < MATCHING_TRACE_COUNT; i += 1) {
            const created = await sdkClient.python.createTrace({
              project_name: project.name,
              name: `${testNamespace}-matching-${i}`,
              input: `input ${i}`,
              output: `output ${i}`,
            });
            ids.push(created.id);
          }
          return ids;
        },
      );

      await test.step(`Create a trace-scope queue with a ceiling of ${MAX_ITEMS_IN_QUEUE}`, async () => {
        registerAnnotationQueueCleanup(queueId, queueName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: queueName,
          scope: 'trace',
          automation: {
            enabled: true,
            maxItemsInQueue: MAX_ITEMS_IN_QUEUE,
            groups: [[{ scoreName, operator: '<', value: SCORE_THRESHOLD }]],
          },
        });
        expect(status, `creating the capped queue answered: ${message}`).toBe(201);
      });

      await test.step('The queue really stored both the condition and the ceiling', async () => {
        // Proved before any score is written: a queue that stored no ceiling
        // would take all five traces, and a queue that stored no condition
        // would take none — and the second of those passes a count assertion
        // for entirely the wrong reason.
        const automation = await backendClient.getAnnotationQueueAutomation(queueId);
        expect(automation, 'the queue must read back with an automation block').not.toBeNull();
        expect(automation).toEqual({
          enabled: true,
          maxItemsInQueue: MAX_ITEMS_IN_QUEUE,
          groups: [[{ scoreName, operator: '<', value: SCORE_THRESHOLD }]],
        });
      });

      await test.step('Score every one of them below the threshold, in one batch', async () => {
        // One request so all five enter the routing buffer together and are
        // offered to the queue in a single flush — which is what makes the
        // truncation below one deterministic decision rather than a race
        // between several.
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: matchingIds.map((traceId) => ({
            traceId,
            name: scoreName,
            value: MATCHING_SCORE,
          })),
        });
      });

      await test.step(`Automation adds exactly ${MAX_ITEMS_IN_QUEUE}, and they are the lowest ids`, async () => {
        await expect
          .poll(async () => (await backendClient.getAnnotationQueue(queueId))?.itemsCount ?? null, {
            message: `the ceiling should admit exactly ${MAX_ITEMS_IN_QUEUE} of the ${MATCHING_TRACE_COUNT} matching traces`,
            timeout: ROUTING_TIMEOUT_MS,
          })
          .toBe(MAX_ITEMS_IN_QUEUE);

        // Which two, not just how many. `fillToMaxItems` sorts the eligible ids
        // and truncates, so the survivors are the two lowest.
        //
        // Sorted as strings: the backend compares java.util.UUID, which orders
        // on the signed most-significant bits first, and for v7 ids minted
        // moments apart in the same era those bits are positive and ascending —
        // so the two orderings agree here. (They would not for ids spanning the
        // sign flip, which no test run can produce.)
        const expectedMembers = [...matchingIds].sort().slice(0, MAX_ITEMS_IN_QUEUE);

        const { status, message, items } = await backendClient.searchAnnotationQueueItems(
          queueId,
          matchingIds,
        );
        expect(status, `the membership lookup answered: ${message}`).toBe(200);
        // The whole answer, as a set: the server's order is not the contract,
        // but the membership is, and a response carrying a third trace would
        // satisfy any assertion that merely looked the two expected ids up.
        const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id);
        expect(items.map((item) => ({ id: item.id, source: item.source })).sort(byId)).toEqual(
          expectedMembers.map((id) => ({ id, source: 'automated' })).sort(byId),
        );
      });

      const manualId = await test.step('A person adds one more trace to the already-full queue', async () => {
        const created = await sdkClient.python.createTrace({
          project_name: project.name,
          name: `${testNamespace}-manual`,
          input: 'input manual',
          output: 'output manual',
        });
        // Throws on anything but 204 — the add succeeding is the claim, and the
        // ceiling must not refuse it.
        await backendClient.addAnnotationQueueItems(queueId, [created.id]);
        return created.id;
      });

      await test.step('The queue now holds three: two automated and the manual one', async () => {
        await expect
          .poll(async () => (await backendClient.getAnnotationQueue(queueId))?.itemsCount ?? null, {
            message: 'the manual add should land despite the queue already being at its ceiling',
            timeout: 30_000,
          })
          .toBe(MAX_ITEMS_IN_QUEUE + 1);

        const expectedMembers = [...matchingIds].sort().slice(0, MAX_ITEMS_IN_QUEUE);
        const { status, message, items } = await backendClient.searchAnnotationQueueItems(queueId, [
          ...matchingIds,
          manualId,
        ]);
        expect(status, `the membership lookup answered: ${message}`).toBe(200);
        // Sources compared alongside their ids rather than as parallel lists,
        // so `manual` stays tied to the item a person added. The whole point is
        // that the three items did not all get there the same way.
        const byId = (a: { id: string }, b: { id: string }) => a.id.localeCompare(b.id);
        expect(items.map((item) => ({ id: item.id, source: item.source })).sort(byId)).toEqual(
          [
            ...expectedMembers.map((id) => ({ id, source: 'automated' })),
            { id: manualId, source: 'manual' },
          ].sort(byId),
        );
      });

      await test.step("The queues list reports the queue as 'Cap reached'", async () => {
        const queuesPage = new AnnotationQueuesPage(page);
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();

        await expect(queuesPage.queueRow(queueId)).toHaveCount(1);
        const automationCell = queuesPage.automationCell(queueId);
        await expect(automationCell).toHaveCount(1);
        // Anchored and exact: 'On' is a substring of nothing here, but 'Cap
        // reached' and 'On' are the two states this cell chooses between, and
        // an unanchored match would accept a cell that rendered both.
        await expect(automationCell).toHaveText(/^\s*Cap reached\s*$/);
      });
    },
  );
});
