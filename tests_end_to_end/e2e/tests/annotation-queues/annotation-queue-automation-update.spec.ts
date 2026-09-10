import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';
import type {
  AnnotationQueueAutomationRef,
  BackendClient,
  ScoreConditionsRef,
} from '@e2e/core/backend';

/**
 * `PATCH /v1/private/annotation-queues/{id}` reads `automation` as three
 * distinct states, and the difference between them is the load-bearing design
 * decision in opik#8258:
 *
 *  - **key absent** — leave the stored automation exactly as it is;
 *  - **`{"enabled": false}`** — disable it, keeping its conditions (so
 *    enable/disable is idempotent and two clients racing on the toggle cannot
 *    clobber each other's rules);
 *  - **a full object** — replace the conditions wholesale, not merge.
 *
 * The UI half is not optional here. The queue Edit dialog PATCHes the whole
 * form — name, scope, instructions, feedback definitions, annotator settings —
 * and omits `automation` entirely, which lands on the first branch. If either
 * side of that ever changes (the dialog starting to send an automation-shaped
 * payload, or the backend's "absent means leave alone" branch regressing),
 * every user's automation config is wiped by an unrelated name edit, silently.
 * An API-only test passes straight through that, which is why the second test
 * drives the real dialog and reads the result back over the API.
 *
 * `max_items_in_queue` is out of scope throughout: it arrived in the second
 * commit of opik#8258 and is absent from the build these assertions were proven
 * against.
 */

/** Deliberately one group where the seed has two, so a merge cannot pass as a replace. */
const REPLACEMENT_CONDITIONS: ScoreConditionsRef = {
  groups: [{ conditions: [{ score: 'helpfulness', operator: '>', value: 0.9 }] }],
};

const REPLACEMENT_AUTOMATION: AnnotationQueueAutomationRef = {
  enabled: true,
  conditions: REPLACEMENT_CONDITIONS,
};

/**
 * Read a queue back, failing here if it is gone.
 *
 * Deliberately not an optional chain at each call site: a queue that vanished
 * mid-test is a failure, and `record?.automation` would quietly compare
 * `undefined` against `undefined` two steps later and call that agreement.
 */
async function readQueue(backendClient: BackendClient, id: string) {
  const record = await backendClient.getAnnotationQueueRecord(id);
  expect(record, `annotation queue '${id}' must exist to assert about`).not.toBeNull();
  return record!;
}

test.describe(
  'Annotation queue — editing a queue and its automation',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'The three PATCH states of automation: toggle, leave alone, replace',
      { tag: ['@cap:annotation-queues.edit-queue'] },
      async ({ automatedQueue, backendClient }) => {
        const seededConditions = automatedQueue.automation.conditions;

        await test.step('Disabling the automation keeps its conditions', async () => {
          const patched = await backendClient.updateAnnotationQueue(automatedQueue.id, {
            automation: { enabled: false },
          });
          expect(patched.status, patched.message).toBe(204);

          const record = await readQueue(backendClient, automatedQueue.id);
          expect(
            record.automation,
            'a toggle-only request must flip enabled and touch nothing else',
          ).toEqual({ enabled: false, conditions: seededConditions });
        });

        await test.step('A PATCH that never mentions automation leaves it untouched', async () => {
          const description = 'edited by a request carrying no automation key';
          const patched = await backendClient.updateAnnotationQueue(automatedQueue.id, {
            description,
          });
          expect(patched.status, patched.message).toBe(204);

          const record = await readQueue(backendClient, automatedQueue.id);
          // Both halves matter. Without the description assertion, a PATCH the
          // backend ignored entirely would satisfy the automation assertion
          // perfectly.
          expect(record.description, 'the edit the request did ask for landed').toBe(description);
          expect(
            record.automation,
            'an unrelated field edit must not disturb the automation',
          ).toEqual({ enabled: false, conditions: seededConditions });
        });

        await test.step('A full automation object replaces the conditions wholesale', async () => {
          const patched = await backendClient.updateAnnotationQueue(automatedQueue.id, {
            automation: { enabled: true, conditions: REPLACEMENT_CONDITIONS },
          });
          expect(patched.status, patched.message).toBe(204);

          const record = await readQueue(backendClient, automatedQueue.id);
          // Exactly the replacement, so a backend that appended the new group to
          // the seeded two — three groups, all of them still matching — fails
          // here rather than reading as a successful edit.
          expect(
            record.automation,
            'a full object replaces the conditions; it does not merge into them',
          ).toEqual(REPLACEMENT_AUTOMATION);
        });
      },
    );

    test(
      'Renaming a queue through the Edit dialog leaves its automation intact',
      { tag: ['@cap:annotation-queues.edit-queue'] },
      async ({ automatedQueue, backendClient, page }) => {
        const queuesPage = new AnnotationQueuesPage(page);
        const renamed = `${automatedQueue.name}-renamed`;

        await test.step('Open the queues list with the automated queue present', async () => {
          await queuesPage.goto(automatedQueue.projectId);
          await queuesPage.waitForReady();
          await expect(queuesPage.queueRow(automatedQueue.id)).toHaveCount(1);
        });

        await test.step('Rename the queue through the Edit dialog', async () => {
          await queuesPage.renameQueue(automatedQueue.id, renamed);
        });

        await test.step('The rename landed and the automation came through it unchanged', async () => {
          const record = await readQueue(backendClient, automatedQueue.id);
          expect(record.name, 'the dialog saved the new name').toBe(renamed);
          expect(
            record.automation,
            'the Edit dialog omits automation entirely — the stored config must be left alone',
          ).toEqual(automatedQueue.automation);
        });
      },
    );
  },
);
