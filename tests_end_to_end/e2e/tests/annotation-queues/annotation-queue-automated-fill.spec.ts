import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { AnnotationQueuePage } from '@e2e/pom/annotation-queue.page';

/**
 * Queue automation filling a queue from a feedback score — the behaviour opik
 * #8687 turned on by default.
 *
 * The feature itself already shipped; #8687 flipped
 * `serviceToggles.annotationQueueAutomationEnabled` to true, so this is the
 * first build where the listener, the Redis flush job and the routing consumer
 * all run on a default install. That makes the fill newly load-bearing and
 * newly untested: `annotation-queue-automation-config.spec.ts` asserts the
 * config round-trips and carries a comment saying the fill was not wired on its
 * build, and `annotation-queue-item-search.spec.ts` asserts membership for
 * MANUAL adds only. A dead routing path would leave both of them green.
 *
 * Driven on both surfaces deliberately. The membership and the `automated`
 * source are server-side facts, but the Source column is what a reviewer
 * actually reads to tell an auto-routed item from one a colleague added by
 * hand — and `QueueItemSourceCell` renders nothing at all when its membership
 * lookup is in flight or fails, so an API-only assertion would pass over a
 * column that silently shows blank for every row.
 *
 * The negative half (a trace scored ABOVE the threshold never joins) is
 * anchored on the positive one rather than on a timeout. Both traces are scored
 * in a single `PUT /v1/private/traces/feedback-scores`, so both enter the
 * routing buffer in the same instant and leave it in the same flush cycle;
 * once the matching trace is in the queue, the non-matching one has been
 * through the same batch and been rejected. There is no arbitrary wait to tune.
 */

const SCORE_THRESHOLD = 0.5;
const MATCHING_SCORE = 0.2;
const NON_MATCHING_SCORE = 0.9;

/**
 * An item is routed between `debounceDelay` (15s) and `debounceDelay +
 * jobInterval` (20s) after its last score, per `annotationQueueRouting` in
 * config.yml. The budget is several times that so a loaded CI box queueing the
 * flush job behind other work reads as slow, not as broken.
 */
const ROUTING_TIMEOUT_MS = 120_000;

test.describe('Annotation queues — automated fill', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  test(
    'A matching feedback score routes its trace in as automated, and a non-matching score keeps its trace out',
    { tag: ['@cap:annotation-queues.automated-queue-fill'] },
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
      const queueName = `${testNamespace}-automated-fill-queue`;

      const { matching, nonMatching } = await test.step(
        'Seed the trace that should be routed in and the one that should not',
        async () => {
          const routed = await sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-matching`,
            input: 'input matching',
            output: 'output matching',
          });
          const excluded = await sdkClient.python.createTrace({
            project_name: project.name,
            name: `${testNamespace}-non-matching`,
            input: 'input non-matching',
            output: 'output non-matching',
          });
          return { matching: routed, nonMatching: excluded };
        },
      );

      await test.step('Create a trace-scope queue that fills itself below the threshold', async () => {
        registerAnnotationQueueCleanup(queueId, queueName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: queueName,
          scope: 'trace',
          automation: {
            enabled: true,
            groups: [[{ scoreName, operator: '<', value: SCORE_THRESHOLD }]],
          },
        });
        expect(status, `creating the automated queue answered: ${message}`).toBe(201);
      });

      await test.step('The queue really stored the condition the scores below are aimed at', async () => {
        // Proved before any score is written. Everything after this asserts
        // what automation did with a condition — over a queue that silently
        // failed to store one, the routed item would never appear and the
        // non-match assertion would pass for the wrong reason, which is a test
        // that cannot fail reading as coverage forever.
        const automation = await backendClient.getAnnotationQueueAutomation(queueId);
        expect(automation, 'the queue must read back with an automation block').not.toBeNull();
        expect(automation).toEqual({
          enabled: true,
          maxItemsInQueue: null,
          groups: [[{ scoreName, operator: '<', value: SCORE_THRESHOLD }]],
        });
      });

      await test.step('Score both traces in one batch, one side of the threshold each', async () => {
        // One request on purpose: it is what makes the exclusion assertion
        // below a contract rather than a race (see the file comment).
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: [
            { traceId: matching.id, name: scoreName, value: MATCHING_SCORE },
            { traceId: nonMatching.id, name: scoreName, value: NON_MATCHING_SCORE },
          ],
        });
      });

      await test.step('Automation routes the matching trace in, and only it', async () => {
        // The lookup is asked about BOTH ids every poll, and the assertion
        // compares the whole answer. Searching for the matching id alone — or
        // finding it inside a longer response — would pass just as well on a
        // build that routed every scored trace in regardless of the condition,
        // which is the regression this exists to catch.
        await expect
          .poll(
            async () =>
              (
                await backendClient.searchAnnotationQueueItems(queueId, [
                  matching.id,
                  nonMatching.id,
                ])
              ).items.length,
            {
              message:
                'the trace scored below the threshold should be routed into the queue once the routing buffer has flushed',
              timeout: ROUTING_TIMEOUT_MS,
            },
          )
          .toBe(1);

        const { status, message, items } = await backendClient.searchAnnotationQueueItems(queueId, [
          matching.id,
          nonMatching.id,
        ]);
        expect(status, `the membership lookup answered: ${message}`).toBe(200);
        expect(items).toEqual([{ id: matching.id, source: 'automated' }]);
      });

      await test.step('And the queue holds exactly that one item', async () => {
        // Separate from the lookup above: that one only reports on the two ids
        // it was asked about, so a queue that had also swept in traces from
        // elsewhere in the project would satisfy it.
        const queue = await backendClient.getAnnotationQueue(queueId);
        expect(queue, 'the queue must still be readable').not.toBeNull();
        expect(queue!.itemsCount).toBe(1);
      });

      await test.step('The items tab shows the routed trace as Automated and no row for the other', async () => {
        const queuePage = new AnnotationQueuePage(page);
        await queuePage.goto(project.id, queueId);
        await queuePage.waitForItemsReady();

        // Exactly one element, not `.first()`: an ambiguous match should fail
        // loudly rather than quietly assert against whichever row came back.
        await expect(queuePage.itemRow(matching.id)).toHaveCount(1);
        const sourceCell = queuePage.itemSourceCell(matching.id);
        await expect(sourceCell).toHaveCount(1);
        // Anchored and exact. The pill's own text is the claim — an empty cell
        // means the membership lookup never resolved, and a substring match
        // would also accept copy that said the opposite.
        await expect(sourceCell).toHaveText(/^\s*Automated\s*$/);

        await expect(queuePage.itemRow(nonMatching.id)).toHaveCount(0);
      });
    },
  );
});
