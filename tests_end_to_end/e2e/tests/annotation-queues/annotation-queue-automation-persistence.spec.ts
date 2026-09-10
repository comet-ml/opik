import { test, expect } from '@e2e/fixtures';
import { uuid7, type AnnotationQueueAutomationRef } from '@e2e/core/backend';

/**
 * An annotation queue's automation config — the rules that decide which traces
 * get pulled into the queue by their feedback scores — must survive the trip to
 * storage and back, and must not outlive the queue it belongs to.
 *
 * API-level throughout, deliberately. No page renders automation today: the
 * frontend has no reference to it on annotation queues, and the create/edit
 * dialog has no automation controls, so there is no UI reading of this to make.
 * That is also why neither test claims to cover the create dialog — see the
 * `@cap:` note in the PR that added this spec.
 *
 * Two read paths, not one. `GET /{id}` and the LIST endpoint resolve a queue's
 * automation through different queries (`findByQueueId` vs `findByQueueIds`),
 * so a regression can take one and leave the other working — the queue would
 * still open correctly while the list quietly reported every queue as
 * unautomated. Asserting the two agree is what catches that.
 *
 * `max_items_in_queue` is deliberately absent from everything below. It landed
 * in the second commit of opik#8258 and is not in the build these assertions
 * were proven against, so a spec asserting on it would be a guess.
 */

/**
 * Two groups, three operators, two conditions in one group. The shape matters:
 * a single condition in a single group would still round-trip on a backend
 * that dropped every group past the first, or that flattened a conjunction
 * into a disjunction.
 */
const AUTOMATION: AnnotationQueueAutomationRef = {
  enabled: true,
  conditions: {
    groups: [
      {
        conditions: [
          { score: 'relevance', operator: '>', value: 0.5 },
          { score: 'toxicity', operator: '<', value: 0.2 },
        ],
      },
      { conditions: [{ score: 'verdict', operator: '=', value: 1 }] },
    ],
  },
};

test.describe(
  'Annotation queue — automation config persistence',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'A queue created with an automation returns it unchanged from both the by-id read and the list',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace }) => {
        const queueId = uuid7();
        const queueName = `${testNamespace}-created-with-automation`;
        // Registered before the request, not after: the id is chosen here, so
        // teardown can reach a queue that was created by a call whose response
        // never arrived.
        registerAnnotationQueueCleanup(queueId, queueName);

        await test.step('Create a queue carrying a two-group automation', async () => {
          const created = await backendClient.createAnnotationQueue({
            id: queueId,
            projectId: project.id,
            name: queueName,
            description: 'seeded with an automation config',
            automation: AUTOMATION,
          });
          expect(created.status, created.message).toBe(201);
        });

        const byId = await test.step('The by-id read returns exactly what was sent', async () => {
          const record = await backendClient.getAnnotationQueueRecord(queueId);
          expect(record, 'the queue just created must be readable back').not.toBeNull();
          // toEqual, not a field-by-field walk: group order, condition order
          // within a group, the operator strings and the numeric values are all
          // part of the config's meaning. A backend that reordered the groups
          // would evaluate the same rules, but a user who wrote them in that
          // order would not recognise what the API hands back.
          expect(
            record!.automation,
            'the stored automation must match the one sent, ordering included',
          ).toEqual(AUTOMATION);
          return record!;
        });

        await test.step('The list endpoint resolves the same automation', async () => {
          const listed = await backendClient.listAnnotationQueueRecords(project.id);
          // The whole answer, not just "ours is in there": the project fixture
          // gives this test its own project, so anything else in this listing
          // is the filter leaking queues from elsewhere in the workspace.
          expect(listed.total, 'the project holds exactly the queue this test created').toBe(1);
          expect(listed.queues, 'one row for one queue').toHaveLength(1);
          expect(listed.queues[0].id).toBe(queueId);
          expect(
            listed.queues[0].automation,
            'the list must agree with the by-id read, not merely return something',
          ).toEqual(byId.automation);
        });
      },
    );

    test(
      "Deleting a queue takes its automation with it and leaves another queue's alone",
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({
        project,
        backendClient,
        automatedQueue,
        bystanderAutomatedQueue,
        registerAnnotationQueueCleanup,
        testNamespace,
      }) => {
        await test.step('Both seeded queues hold their own automation', async () => {
          // The precondition the whole test rests on. Without it, a delete that
          // did nothing at all would still satisfy every assertion below if the
          // seeds had never stored an automation in the first place.
          const target = await backendClient.getAnnotationQueueRecord(automatedQueue.id);
          const bystander = await backendClient.getAnnotationQueueRecord(
            bystanderAutomatedQueue.id,
          );
          expect(target?.automation).toEqual(automatedQueue.automation);
          expect(bystander?.automation).toEqual(bystanderAutomatedQueue.automation);
          expect(
            bystanderAutomatedQueue.automation,
            'the two seeds must be distinguishable, or a mix-up would read as success',
          ).not.toEqual(automatedQueue.automation);
        });

        await test.step('Delete the automated queue', async () => {
          await backendClient.deleteAnnotationQueue(automatedQueue.id);
          expect(await backendClient.getAnnotationQueueRecord(automatedQueue.id)).toBeNull();
        });

        await test.step('A new queue in the same project starts with no automation', async () => {
          // The automation row is keyed on the queue, in a different store from
          // the queue itself. A delete that dropped only the queue would leave
          // the config behind for the next queue in that project to inherit.
          const freshId = uuid7();
          const freshName = `${testNamespace}-after-delete`;
          registerAnnotationQueueCleanup(freshId, freshName);
          const created = await backendClient.createAnnotationQueue({
            id: freshId,
            projectId: project.id,
            name: freshName,
          });
          expect(created.status, created.message).toBe(201);

          const record = await backendClient.getAnnotationQueueRecord(freshId);
          expect(record, 'the new queue must exist to assert about').not.toBeNull();
          expect(
            record!.automation,
            'a queue created without an automation must not inherit a deleted one',
          ).toBeNull();
        });

        await test.step('The untouched queue kept its automation', async () => {
          const bystander = await backendClient.getAnnotationQueueRecord(
            bystanderAutomatedQueue.id,
          );
          expect(bystander, 'the bystander queue must survive the delete').not.toBeNull();
          expect(
            bystander!.automation,
            'deleting one queue must not clear automations across the project',
          ).toEqual(bystanderAutomatedQueue.automation);
        });
      },
    );
  },
);
