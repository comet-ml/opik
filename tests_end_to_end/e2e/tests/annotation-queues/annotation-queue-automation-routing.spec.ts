import { test, expect, QUALITY_THRESHOLD, SAFETY_THRESHOLD } from '@e2e/fixtures';
import { AnnotationQueuePage } from '@e2e/pom/annotation-queue.page';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A queue whose automation is `quality > 7 AND safety < 3` collects the traces
 * that match and nothing else, and the items table says how each one arrived.
 *
 * Three traces, so the assertion cannot pass by accident:
 *  - `matching`    — scored above both thresholds, must arrive as "Automated";
 *  - `nonMatching` — scored the wrong side of both, the negative control that
 *                    makes "everything gets routed" fail;
 *  - `manual`      — never scored, added by hand from Logs, which is what stops
 *                    a hardcoded "Automated" label passing.
 *
 * The scores are written AFTER the queue exists because routing fires on the
 * score write; a trace scored first would never be evaluated. They are written
 * through the API, which is also how the feature is reached in practice — the
 * queue matches on scores whatever wrote them.
 *
 * NOTE for whoever rebases this: routing is only on because this branch's
 * `chore(config): enable annotation queue routing by default, temporarily`
 * flipped `ANNOTATION_QUEUE_ROUTING_ENABLED`. If that default goes back to off,
 * this spec needs the flag set on the environment rather than deleting.
 */
test.describe(
  'Annotation queue — automation routing',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'Automation collects only the matching trace, and the items table reports how each arrived',
      { tag: ['@cap:annotation-queues.add-traces-to-queue'] },
      async ({ automatedQueue, backendClient, page }) => {
        const { matching, nonMatching, manual } = automatedQueue;

        await test.step('Verify the seeded queue really carries the automation under test', async () => {
          // A UI assertion over a fixture that silently failed to configure
          // automation is a test that cannot fail, so the precondition is
          // asserted at the API before the browser is opened.
          const settings = await backendClient.getAnnotationQueueSettings(automatedQueue.id);
          if (settings === null) {
            throw new Error(`seeded annotation queue ${automatedQueue.id} was not found`);
          }
          expect(settings.automation).not.toBeNull();
          expect(settings.automation?.enabled).toBe(true);
          expect(settings.automation?.groups).toEqual([
            [
              {
                scoreName: automatedQueue.qualityScoreName,
                operator: '>',
                value: QUALITY_THRESHOLD,
              },
              { scoreName: automatedQueue.safetyScoreName, operator: '<', value: SAFETY_THRESHOLD },
            ],
          ]);
        });

        await test.step('Score one trace above the thresholds and one below', async () => {
          for (const [trace, quality, safety] of [
            [matching, 9, 1],
            [nonMatching, 2, 8],
          ] as const) {
            await backendClient.addTraceFeedbackScore({
              traceId: trace.id,
              name: automatedQueue.qualityScoreName,
              value: quality,
            });
            await backendClient.addTraceFeedbackScore({
              traceId: trace.id,
              name: automatedQueue.safetyScoreName,
              value: safety,
            });
          }
        });

        await test.step('Add the third trace to the queue by hand from Logs', async () => {
          const logs = new LogsPage(page);
          await logs.goto(automatedQueue.projectId);
          await logs.waitForReady();
          await logs.selectTrace(manual.id);
          await logs.addSelectedTracesToQueue(automatedQueue.name);
        });

        await test.step('Verify membership and provenance at the API', async () => {
          // Routing is asynchronous, so poll — but poll on the WHOLE answer.
          // Asserting only that `matching` turned up would pass just as well if
          // the non-matching trace had been collected too, which is the bug
          // worth catching.
          await expect
            .poll(
              async () =>
                backendClient.getAnnotationQueueItemSources(automatedQueue.id, [
                  matching.id,
                  nonMatching.id,
                  manual.id,
                ]),
              {
                message:
                  'automation routes the matching trace and only the matching trace. ' +
                  'An answer carrying only the manual trace means nothing was routed at all — ' +
                  'check ANNOTATION_QUEUE_ROUTING_ENABLED is true on the environment (see the ' +
                  'NOTE above: this branch flips the default in a commit marked "revert before merge").',
              },
            )
            .toEqual({
              [matching.id]: 'automated',
              [manual.id]: 'manual',
            });

          const queue = await backendClient.getAnnotationQueue(automatedQueue.id);
          expect(queue?.itemsCount, 'the queue holds exactly the two collected traces').toBe(2);
        });

        await test.step('Verify the Queue items table reports the same provenance', async () => {
          const queuePage = new AnnotationQueuePage(page);
          await queuePage.goto(automatedQueue.projectId, automatedQueue.id);
          await queuePage.waitForReady();
          await queuePage.waitForItemRow(matching.id);
          await queuePage.waitForItemRow(manual.id);

          // The Source cell renders empty while its membership lookup is in
          // flight, so these wait on the text rather than on the cell.
          await expect(queuePage.itemSourceCell(matching.id)).toHaveText('Automated');
          await expect(queuePage.itemSourceCell(manual.id)).toHaveText('Manual');
          await expect(
            queuePage.itemRow(nonMatching.id),
            'the below-threshold trace is not in the queue at all',
          ).toHaveCount(0);
        });
      },
    );
  },
);
