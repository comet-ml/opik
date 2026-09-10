import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { AnnotationQueuePage } from '@e2e/pom/annotation-queue.page';

/**
 * The journey the automation feature exists for: configure a queue to collect
 * matching traces, and be able to tell afterwards how each item got there.
 *
 * Both halves fail silently. A queue that stops populating itself looks exactly
 * like a healthy queue that nothing has matched yet, and an item attributed to
 * the wrong source renders a perfectly ordinary pill. So the routing is
 * asserted as a whole membership set — the matching trace present AND the
 * non-matching one absent — rather than by finding the row that should be
 * there, which would pass just as well if the queue had swept up everything.
 *
 * Timing: routing is asynchronous (a debounce plus a flush, observed at ~6s),
 * so membership is polled rather than slept on.
 */
test.describe(
  'Annotation queue — automation routing and item provenance',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'A queue created with automation collects only matching traces and shows how each item arrived',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({
        automationRoutingSeed,
        registerAnnotationQueueCleanup,
        backendClient,
        testNamespace,
        page,
      }) => {
        const {
          projectId,
          feedbackDefinitionName,
          matchingTrace,
          nonMatchingTrace,
          manualTrace,
          threshold,
          matchingScore,
          nonMatchingScore,
        } = automationRoutingSeed;
        const queueName = `${testNamespace}-routed-queue`;

        const sheet = await test.step('Open Add automation → Annotation queue from Logs', async () => {
          const logs = new LogsPage(page);
          await logs.goto(projectId);
          await logs.waitForReady();
          return logs.openAddAutomationQueueForm();
        });

        await test.step('Verify the form arrives configured for the tab it was opened from', async () => {
          // Reaching the form this way is a statement of intent, so automation
          // is already on and the scope is fixed to what the Traces tab lists.
          await expect(sheet.automationSwitch).toBeChecked();
          await expect(sheet.scopeOption('Traces')).toBeChecked();
          await expect(sheet.scopeOption('Traces')).toBeDisabled();
        });

        await test.step('Configure one condition and create the queue', async () => {
          await sheet.nameInput.fill(queueName);
          await sheet.selectConditionScore(feedbackDefinitionName);
          await sheet.conditionOperator('<').click();
          await sheet.conditionThreshold().fill(String(threshold));
          await sheet.submit('Create queue');
        });

        const queueId = await test.step('Resolve the created queue and register it for cleanup', async () => {
          const found = await backendClient.listAnnotationQueuesWithPrefix(queueName);
          expect(found, `exactly one queue should be named ${queueName}`).toHaveLength(1);
          // Registered the moment the id exists: everything below can fail, and
          // a queue does not cascade with the project fixture. The run-prefix
          // sweep in global-teardown would collect it, but only once the whole
          // run is over — until then it is a stray queue every later test sees.
          registerAnnotationQueueCleanup(found[0].id);
          return found[0].id;
        });

        await test.step('Verify the form persisted the automation it was given', async () => {
          const stored = await backendClient.getAnnotationQueueSettings(queueId);
          expect(stored, `queue ${queueId} should be readable after creation`).not.toBeNull();
          expect(stored!.scope).toBe('trace');
          // The whole automation object, not just `enabled`: a form that saved
          // the switch but dropped or mangled the condition would leave a queue
          // that is "on" and matches nothing.
          expect(stored!.automation).toEqual({
            enabled: true,
            groups: [
              {
                conditions: [
                  { score: feedbackDefinitionName, operator: '<', value: threshold },
                ],
              },
            ],
          });
        });

        await test.step('Score one trace under the threshold and one over it', async () => {
          await backendClient.addTraceFeedbackScore({
            traceId: matchingTrace.id,
            name: feedbackDefinitionName,
            value: matchingScore,
          });
          await backendClient.addTraceFeedbackScore({
            traceId: nonMatchingTrace.id,
            name: feedbackDefinitionName,
            value: nonMatchingScore,
          });
        });

        await test.step('Wait for automation to route the matching trace', async () => {
          await expect
            .poll(
              async () => {
                const items = await backendClient.searchAnnotationQueueItems(queueId, [
                  matchingTrace.id,
                  nonMatchingTrace.id,
                ]);
                return items.map((i) => `${i.id}:${i.source}`).sort();
              },
              {
                timeout: 60_000,
                message:
                  `automation on ${queueId} should have collected only ${matchingTrace.name} ` +
                  `(scored ${matchingScore}, under the ${threshold} threshold)`,
              },
            )
            .toEqual([`${matchingTrace.id}:automated`]);
        });

        await test.step('Add a third trace to the queue by hand', async () => {
          await backendClient.addItemsToAnnotationQueue(queueId, [manualTrace.id]);
        });

        await test.step('Verify the final membership of all three seeded traces', async () => {
          // Re-asserted after the manual add, well past the routing debounce, so
          // a non-matching trace that arrives late still fails. The poll above
          // stops at the first match and could not catch that on its own.
          const items = await backendClient.searchAnnotationQueueItems(queueId, [
            matchingTrace.id,
            nonMatchingTrace.id,
            manualTrace.id,
          ]);
          expect(items.map((i) => `${i.id}:${i.source}`).sort()).toEqual(
            [`${matchingTrace.id}:automated`, `${manualTrace.id}:manual`].sort(),
          );
        });

        await test.step('Verify the Source column distinguishes the two ways in', async () => {
          const queuePage = new AnnotationQueuePage(page);
          await queuePage.goto(projectId, queueId);
          await queuePage.waitForItemsReady();

          await expect(queuePage.sourceColumnHeader).toBeVisible();
          await expect(queuePage.itemSourceCell(matchingTrace.id)).toHaveCount(1);
          await expect(queuePage.itemSourceCell(matchingTrace.id)).toHaveText('Automated');
          await expect(queuePage.itemSourceCell(manualTrace.id)).toHaveCount(1);
          await expect(queuePage.itemSourceCell(manualTrace.id)).toHaveText('Manual');
          await expect(queuePage.itemRow(nonMatchingTrace.id)).toHaveCount(0);
        });
      },
    );
  },
);
