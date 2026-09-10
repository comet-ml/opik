import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuePage } from '@e2e/pom/annotation-queue.page';
import { uuid7, type BackendClient } from '@e2e/core/backend';

/**
 * Queue automation: a queue configured with score conditions pulls in traces on
 * its own, with no reviewer having added them.
 *
 * Everything here is API-seeded and API-driven because the feature has no UI at
 * this commit — the queue `automation` block can only be configured over REST,
 * and the items table has no Source column, so a routed item is visually
 * indistinguishable from one a person added. The UI assertion this file does
 * make is the one the UI can honestly carry: that the routed trace, and only
 * it, reaches the reviewer's Queue items table.
 */

const QUALITY_SCORE = 'quality';
const SAFETY_SCORE = 'safety';

/** The threshold every automation below is configured with. */
const THRESHOLD = 0.5;

/**
 * Wait until `scoreName` on `traceId` reads exactly `value`, then return.
 *
 * Used before every routing wait, for two distinct reasons:
 *
 *  - **It proves the precondition.** A routing assertion over a score that
 *    never landed is a test that cannot fail — "no item appeared" is equally
 *    true when the automation is broken and when nothing was ever scored.
 *  - **It is the only correct wait when re-scoring.** The estate's
 *    `pollTraceForFeedbackScore` returns the first score with a matching name,
 *    so on a trace that is already scored it resolves instantly on the OLD
 *    value, and the routing wait that follows would race the write it was
 *    supposed to wait for.
 *
 * The sentinel strings are deliberate: a missing trace and a missing score are
 * different bugs, and both are more useful in a failure message than
 * `undefined`.
 */
async function expectTraceScore(
  backendClient: BackendClient,
  traceId: string,
  scoreName: string,
  value: number,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const trace = await backendClient.getTrace(traceId);
        if (trace === null) return `<trace ${traceId} not found>`;
        const score = trace.feedbackScores.find((fs) => fs.name === scoreName);
        return score === undefined ? `<no "${scoreName}" score>` : score.value;
      },
      { timeout: 30_000, message: `feedback score "${scoreName}" on trace ${traceId}` },
    )
    .toBe(value);
}

/** The ids and sources of a settled membership set, sorted so a diff reads cleanly. */
function membership(items: Array<{ id: string; source: string }>): string[] {
  return items.map((item) => `${item.id}:${item.source}`).sort();
}

test.describe(
  'Annotation queue — automation routing',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    /**
     * Routing is debounced by design (5s window, swept by a 2s job), so every
     * assertion in this file is a settle wait of ~12s and several tests hold
     * three or four of them.
     */
    test.slow();

    test(
      'Automation routes the sub-threshold SDK trace and leaves the others out',
      { tag: ['@cap:annotation-queues.add-traces-to-queue'] },
      async ({ project, backendClient, createRoutingQueue, testNamespace, page }) => {
        const queue = await createRoutingQueue({
          projectId: project.id,
          suffix: 'routing-queue',
          automation: {
            enabled: true,
            groups: [[{ score: QUALITY_SCORE, operator: '<', value: THRESHOLD }]],
          },
        });

        const belowThreshold = uuid7();
        const aboveThreshold = uuid7();
        const experimentTrace = uuid7();
        const seeded = [belowThreshold, aboveThreshold, experimentTrace];

        await test.step('Seed two SDK traces and one experiment trace', async () => {
          // The two axes the automation decides on, one per trace: the score's
          // value, and where the trace came from. `source` is explicit on all
          // three — a trace written over REST without one stores `unknown` and
          // routes through the deliberate legacy fallback, which would leave
          // the actual `sdk` branch unasserted.
          for (const [id, source, name] of [
            [belowThreshold, 'sdk', 'below-threshold'],
            [aboveThreshold, 'sdk', 'above-threshold'],
            [experimentTrace, 'experiment', 'experiment-source'],
          ] as const) {
            await backendClient.createTraceWithSource({
              id,
              projectName: project.name,
              name: `${testNamespace}-${name}`,
              source,
              input: { question: `${name} input` },
              output: { answer: `${name} output` },
              endTime: new Date(),
            });
          }
        });

        await test.step('Score all three, the experiment trace exactly like the SDK ones', async () => {
          // The experiment trace is scored through the same endpoint as the SDK
          // traces on purpose. Scoring it any other way would leave "it did not
          // route" open to a second explanation — that its scoring path never
          // published a routing event at all — and the exclusion under test is
          // the trace's source, not the write that carried the score.
          await backendClient.addTraceFeedbackScore({
            traceId: belowThreshold,
            name: QUALITY_SCORE,
            value: 0.2,
          });
          await backendClient.addTraceFeedbackScore({
            traceId: aboveThreshold,
            name: QUALITY_SCORE,
            value: 0.9,
          });
          await backendClient.addTraceFeedbackScore({
            traceId: experimentTrace,
            name: QUALITY_SCORE,
            value: 0.2,
          });

          await expectTraceScore(backendClient, belowThreshold, QUALITY_SCORE, 0.2);
          await expectTraceScore(backendClient, aboveThreshold, QUALITY_SCORE, 0.9);
          await expectTraceScore(backendClient, experimentTrace, QUALITY_SCORE, 0.2);
        });

        await test.step('Verify only the sub-threshold SDK trace became a queue item', async () => {
          const items = await backendClient.waitForQueueItemsSettled(queue.id, seeded);
          expect(membership(items)).toEqual([`${belowThreshold}:automated`]);

          // The membership lookup can only answer for the ids it was given, so
          // it would be blind to a fourth item routed from somewhere else. The
          // queue's own count is the whole answer.
          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(1);
        });

        await test.step('Verify the reviewer sees exactly that one item', async () => {
          const queuePage = new AnnotationQueuePage(page);
          await queuePage.goto(project.id, queue.id);
          await queuePage.waitForItemsReady();

          await expect(queuePage.itemRow(belowThreshold)).toHaveCount(1);
          await expect(queuePage.itemRows).toHaveCount(1);
          await expect(queuePage.itemRow(aboveThreshold)).toHaveCount(0);
          await expect(queuePage.itemRow(experimentTrace)).toHaveCount(0);
        });
      },
    );

    test(
      'An AND group does not route while one of its two scores is absent',
      { tag: ['@cap:annotation-queues.add-traces-to-queue'] },
      async ({ project, backendClient, createRoutingQueue, testNamespace }) => {
        // API-only: the discriminating assertion is queue membership, and the
        // first half of this test asserts an ABSENCE — an empty items table is
        // weak evidence for it, since an empty table is also what a queue that
        // failed to load shows.
        const queue = await createRoutingQueue({
          projectId: project.id,
          suffix: 'and-group-queue',
          automation: {
            enabled: true,
            groups: [
              [
                { score: QUALITY_SCORE, operator: '<', value: THRESHOLD },
                { score: SAFETY_SCORE, operator: '<', value: THRESHOLD },
              ],
            ],
          },
        });

        const traceId = uuid7();

        await test.step('Seed one SDK trace', async () => {
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: `${testNamespace}-and-group`,
            source: 'sdk',
            input: { question: 'and-group input' },
            output: { answer: 'and-group output' },
            endTime: new Date(),
          });
        });

        await test.step('Score only the first of the two conditions', async () => {
          await backendClient.addTraceFeedbackScore({
            traceId,
            name: QUALITY_SCORE,
            value: 0.2,
          });
          await expectTraceScore(backendClient, traceId, QUALITY_SCORE, 0.2);
        });

        await test.step('Verify a half-satisfied group does not route', async () => {
          // The failure this guards is a missing score being read as a match:
          // an absent `safety` treated as 0, which is below the threshold. It
          // is invisible from the page until the queue is already full of items
          // nobody meant to review.
          const items = await backendClient.waitForQueueItemsSettled(queue.id, [traceId]);
          expect(membership(items)).toEqual([]);

          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(0);
        });

        await test.step('Score the second condition', async () => {
          await backendClient.addTraceFeedbackScore({
            traceId,
            name: SAFETY_SCORE,
            value: 0.2,
          });
          await expectTraceScore(backendClient, traceId, SAFETY_SCORE, 0.2);
        });

        await test.step('Verify the now-complete group routes the trace', async () => {
          // The positive half is what stops the negative half above from
          // passing for the wrong reason: an automation that never routes
          // anything would satisfy the absence assertion perfectly.
          const items = await backendClient.waitForQueueItemsSettled(queue.id, [traceId]);
          expect(membership(items)).toEqual([`${traceId}:automated`]);

          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(1);
        });
      },
    );

    test(
      'Automation never re-adds an item a reviewer removed from the queue',
      { tag: ['@cap:annotation-queues.add-traces-to-queue'] },
      async ({ project, backendClient, createRoutingQueue, testNamespace }) => {
        const queue = await createRoutingQueue({
          projectId: project.id,
          suffix: 'no-re-add-queue',
          automation: {
            enabled: true,
            groups: [[{ score: QUALITY_SCORE, operator: '<', value: THRESHOLD }]],
          },
        });

        const dismissed = uuid7();
        // A second routed item the test never removes. Without it, "the queue
        // is down to nothing" would pass just as well for a removal that took
        // every item in the queue, and the re-add assertion that follows would
        // then be asserting over an empty queue rather than a surviving one.
        const bystander = uuid7();
        const seeded = [dismissed, bystander];

        await test.step('Seed two SDK traces that both match the automation', async () => {
          for (const [id, name] of [
            [dismissed, 'dismissed'],
            [bystander, 'bystander'],
          ] as const) {
            await backendClient.createTraceWithSource({
              id,
              projectName: project.name,
              name: `${testNamespace}-${name}`,
              source: 'sdk',
              input: { question: `${name} input` },
              output: { answer: `${name} output` },
              endTime: new Date(),
            });
          }
        });

        await test.step('Score both below the threshold', async () => {
          await backendClient.addTraceFeedbackScore({
            traceId: dismissed,
            name: QUALITY_SCORE,
            value: 0.2,
          });
          await backendClient.addTraceFeedbackScore({
            traceId: bystander,
            name: QUALITY_SCORE,
            value: 0.3,
          });
          await expectTraceScore(backendClient, dismissed, QUALITY_SCORE, 0.2);
          await expectTraceScore(backendClient, bystander, QUALITY_SCORE, 0.3);
        });

        await test.step('Verify both routed', async () => {
          const items = await backendClient.waitForQueueItemsSettled(queue.id, seeded);
          expect(membership(items)).toEqual(
            [`${bystander}:automated`, `${dismissed}:automated`].sort(),
          );

          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(2);
        });

        await test.step('Re-score a held item and verify it is not duplicated', async () => {
          await backendClient.addTraceFeedbackScore({
            traceId: dismissed,
            name: QUALITY_SCORE,
            value: 0.1,
          });
          await expectTraceScore(backendClient, dismissed, QUALITY_SCORE, 0.1);

          const items = await backendClient.waitForQueueItemsSettled(queue.id, seeded);
          expect(membership(items)).toEqual(
            [`${bystander}:automated`, `${dismissed}:automated`].sort(),
          );

          // Membership is keyed on the entity, so a second routing of the same
          // trace shows up here rather than in the id set: the count moves, the
          // ids do not.
          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(2);
        });

        await test.step('Remove one item, leaving the bystander', async () => {
          await backendClient.removeAnnotationQueueItems(queue.id, [dismissed]);

          const items = await backendClient.waitForQueueItemsSettled(queue.id, seeded);
          expect(membership(items)).toEqual([`${bystander}:automated`]);

          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(1);
        });

        await test.step('Re-score the removed item and verify it stays out', async () => {
          // The loop the queue's item history exists to prevent: a reviewer
          // dismisses an item, any later score on it changes, and automation
          // serves it straight back.
          await backendClient.addTraceFeedbackScore({
            traceId: dismissed,
            name: QUALITY_SCORE,
            value: 0.05,
          });
          await expectTraceScore(backendClient, dismissed, QUALITY_SCORE, 0.05);

          const items = await backendClient.waitForQueueItemsSettled(queue.id, seeded);
          expect(membership(items)).toEqual([`${bystander}:automated`]);

          const detail = await backendClient.getAnnotationQueue(queue.id);
          expect(detail).not.toBeNull();
          expect(detail?.itemsCount).toBe(1);
        });
      },
    );
  },
);
