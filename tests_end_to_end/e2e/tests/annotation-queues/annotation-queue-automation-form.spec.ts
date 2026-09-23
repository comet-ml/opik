import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';
import {
  AnnotationQueueFormSheet,
  AnnotationQueuesPage,
} from '@e2e/pom/annotation-queue.page';

/**
 * The queue automation *form* — the half of OPIK-6303 a user actually touches.
 *
 * `annotation-queue-automation-config.spec.ts` already pins the stored shape
 * over REST, and deliberately says so in its own docstring: at the commit it was
 * written against there was no UI to drive. There is one now, and the two
 * failures this covers are invisible from the API side:
 *
 *  - the form serialises a condition the user configured into the payload, and
 *  - the form rebuilds that condition from the payload when the queue is
 *    reopened.
 *
 * Both are round trips, so each test asserts the API AND the rendered builder.
 * An assertion on only one end passes for a form that writes correctly and
 * rehydrates to blank — which is the shape that quietly deletes a user's
 * conditions the next time they edit anything else about the queue.
 *
 * Deterministic and clean-state reachable: a workspace feedback definition makes
 * the score option exist without any scored data, so nothing here waits on
 * scoring, routing or a provider. In particular this needs nothing from
 * `ANNOTATION_QUEUE_ROUTING_ENABLED` — what is asserted is the configuration,
 * never that a matching trace was collected.
 */

const THRESHOLD = '0.5';
const SECOND_THRESHOLD = '0.9';

test.describe('Annotation queues — automation form', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  test(
    'Creating a queue from Logs → Add automation stores the condition the builder shows',
    { tag: ['@cap:annotation-queues.create-queue'] },
    async ({
      project,
      feedbackDefinition,
      sdkClient,
      backendClient,
      registerAnnotationQueueCleanup,
      testNamespace,
      page,
    }) => {
      await test.step('Seed a trace so Logs renders its table rather than an empty state', async () => {
        await sdkClient.python.createTrace({
          project_name: project.name,
          name: `${testNamespace}-trace`,
          input: 'seed input',
          output: 'seed output',
        });
      });

      const logs = new LogsPage(page);
      await test.step('Open Logs on the Traces tab', async () => {
        await logs.gotoTraces(project.id);
        await logs.waitForReady();
        // Asserted, not assumed: the entry point under test prefills the queue's
        // scope from the active tab, so a spec that opened the form from the
        // wrong tab would go on to assert a thread-scoped queue's behaviour
        // while claiming to test a trace-scoped one.
        await expect(logs.tracesTab, 'the Traces tab is active').toHaveAttribute(
          'aria-checked',
          'true',
        );
      });

      const sheet = new AnnotationQueueFormSheet(page, 'create');
      await test.step('Open the annotation queue option and check what it prefilled', async () => {
        await logs.openAddAutomation('Annotation queue');
        await sheet.waitForReady();

        await expect(sheet.scopeOption('Traces'), 'scope is prefilled to Traces').toHaveAttribute(
          'aria-checked',
          'true',
        );
        await expect(
          sheet.scopeOption('Traces'),
          'and locked — the tab chose it, not the user',
        ).toBeDisabled();
        await expect(
          sheet.automationSwitch,
          'arriving from "Add automation" opens with automation already on',
        ).toBeChecked();
        await expect(
          sheet.automationDescription,
          'the copy names traces, matching the scope',
        ).toContainText('matching traces');
      });

      const queueName = `${testNamespace}-from-logs`;
      await test.step('Configure one condition and create the queue', async () => {
        await sheet.nameInput.fill(queueName);
        await expect(
          sheet.groupCaptions,
          'the builder opens with exactly one group to fill in',
        ).toHaveCount(1);
        await sheet.fillLastCondition({
          scoreName: feedbackDefinition.name,
          operator: '<',
          threshold: THRESHOLD,
        });
        await sheet.submit();
      });

      const queueId = await test.step('The queue exists', async () => {
        const id = await backendClient.findAnnotationQueueIdByName(project.id, queueName);
        expect(id, `a queue named ${queueName} must exist in project ${project.id}`).not.toBeNull();
        registerAnnotationQueueCleanup(id!, queueName);
        return id!;
      });

      await test.step('And carries exactly the automation the form was showing', async () => {
        // Compared whole rather than field by field: a stored automation that
        // also carried a group nobody configured would satisfy every individual
        // lookup, and filling a review queue from a condition the user never
        // wrote is exactly the failure worth catching. It is also how the blank
        // placeholder row the builder starts from would surface if the form
        // serialised it alongside the real one.
        expect(await backendClient.getAnnotationQueueAutomation(queueId)).toEqual({
          enabled: true,
          maxItemsInQueue: null,
          groups: [
            [
              {
                scoreName: feedbackDefinition.name,
                operator: '<',
                value: Number(THRESHOLD),
              },
            ],
          ],
        });
      });

      await test.step('And the queues list reports it as automated', async () => {
        const queuesPage = new AnnotationQueuesPage(page);
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();
        await expect(queuesPage.automationCell(queueId)).toHaveText('On');
      });
    },
  );

  test(
    'The edit form rehydrates a stored condition and can OR a second group onto it',
    { tag: ['@cap:annotation-queues.edit-queue'] },
    async ({
      project,
      feedbackDefinition,
      backendClient,
      registerAnnotationQueueCleanup,
      testNamespace,
      page,
    }) => {
      const queueId = uuid7();
      const queueName = `${testNamespace}-edit-me`;

      await test.step('Seed an automated queue over REST', async () => {
        // Seeded rather than created through the form: what this test is about
        // is the form reading a stored automation back, so the stored value has
        // to come from somewhere the form had no hand in writing.
        registerAnnotationQueueCleanup(queueId, queueName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: queueName,
          scope: 'trace',
          automation: {
            enabled: true,
            groups: [
              [
                {
                  scoreName: feedbackDefinition.name,
                  operator: '<',
                  value: Number(THRESHOLD),
                },
              ],
            ],
          },
        });
        expect(status, `creating the automated queue answered: ${message}`).toBe(201);
      });

      const queuesPage = new AnnotationQueuesPage(page);
      const sheet = new AnnotationQueueFormSheet(page, 'edit');

      await test.step('Open the queue for editing', async () => {
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();
        await expect(queuesPage.automationCell(queueId)).toHaveText('On');
        await queuesPage.openEditForm(queueId);
      });

      await test.step('The builder comes back showing the stored condition', async () => {
        await expect(sheet.automationSwitch, 'automation reads as on').toBeChecked();
        await expect(
          sheet.groupCaptions,
          'one stored group, one rendered group',
        ).toHaveCount(1);
        await expect(
          sheet.conditionScores(feedbackDefinition.name),
          'the score the condition names',
        ).toHaveCount(1);
        await expect(
          sheet.conditionOperators('<'),
          'the operator it was stored with',
        ).toHaveAttribute('aria-checked', 'true');
        await expect(sheet.conditionThresholds, 'and its threshold').toHaveValue(THRESHOLD);
      });

      await test.step('Add a second OR-ed group and save', async () => {
        await sheet.addOrGroup();
        await sheet.fillLastCondition({
          scoreName: feedbackDefinition.name,
          operator: '>',
          threshold: SECOND_THRESHOLD,
        });
        await sheet.submit();
      });

      await test.step('Both groups are stored, in order, with the original untouched', async () => {
        expect(await backendClient.getAnnotationQueueAutomation(queueId)).toEqual({
          enabled: true,
          maxItemsInQueue: null,
          groups: [
            [{ scoreName: feedbackDefinition.name, operator: '<', value: Number(THRESHOLD) }],
            [
              {
                scoreName: feedbackDefinition.name,
                operator: '>',
                value: Number(SECOND_THRESHOLD),
              },
            ],
          ],
        });
      });

      await test.step('And reopening renders both of them', async () => {
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();
        await queuesPage.openEditForm(queueId);

        // Pinned by exhaustion as well as by value: a rehydration that also
        // rendered a third, blank group would satisfy any per-row lookup while
        // being exactly the state that makes the next save fail validation.
        await expect(sheet.groupCaptions).toHaveText(['Group 1', 'Group 2']);
        await expect(sheet.conditionThresholds).toHaveCount(2);
        await expect(sheet.conditionThresholds.nth(0)).toHaveValue(THRESHOLD);
        await expect(sheet.conditionThresholds.nth(1)).toHaveValue(SECOND_THRESHOLD);
        await expect(sheet.conditionScores(feedbackDefinition.name)).toHaveCount(2);
        await expect(sheet.emptyConditionScores, 'no blank condition rows').toHaveCount(0);
      });
    },
  );
});
