import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import {
  AnnotationQueueFormSheet,
  AnnotationQueuesPage,
} from '@e2e/pom/annotation-queue.page';

/**
 * The automation-OFF path: creating a queue without automation, and editing a
 * queue that has none. Both are the default, ordinary path — automation is
 * opt-in, and every queue created before OPIK-6303 (and every queue that
 * `POST /v1/private/annotation-queues` still produces without an `automation`
 * key) has that shape.
 *
 * The second and third tests are `test.fail()` against a defect this spec was
 * written to pin. `AddEditAnnotationQueueDialog` builds
 * `automation.conditions.groups` unconditionally from `automation_groups`,
 * whose default is a single placeholder row with a blank score name, and the
 * form's zod schema deliberately skips validating those rows while the toggle
 * is off. The backend does not skip them — `ScoreCondition.scoreName` is
 * `@NotBlank` — so the write answers
 *
 *   422 {"errors":["automation.conditions.groups[0].conditions[0].scoreName
 *        must not be blank"]}
 *
 * and the sheet stays open having written nothing. `AnnotationQueueAutomation`
 * documents the shape the form should send: *"Nullable so flipping the toggle
 * off is a one-field request: {"enabled": false} keeps the stored conditions"*.
 * So the fix is to omit `conditions` — not to send an empty `groups` array,
 * which the backend refuses too (`groups must not be empty`).
 *
 * The defect only bites while a group still holds an UNTOUCHED placeholder,
 * which is why toggling automation off on a queue whose conditions are filled
 * in works fine: the placeholder has been replaced by a valid row. That is
 * exactly every create-with-automation-off and every edit of a queue with none.
 *
 * Both failing tests are asserted against the CORRECT behaviour and marked
 * `test.fail()`, so they report as expected failures while the defect is open
 * and fail with "Expected to fail, but passed" the moment it is fixed —
 * which is the prompt to delete the annotations. Same arrangement
 * `annotation-queue-delete.spec.ts` uses for OPIK-7903, guard test included: a
 * blanket `test.fail()` swallows every failure inside its test, so a broken POM
 * or a page that never loaded would report as the expected failure and this
 * spec would go on "passing" while testing nothing. The first test holds up
 * everything the other two depend on, and is asserted normally.
 *
 * Neither `create-queue` nor `edit-queue` is flipped to `covered: true` on the
 * strength of the two `test.fail()` tests — a known failure is not coverage.
 * They are covered by `annotation-queue-automation-form.spec.ts` instead.
 */

test.describe('Annotation queues — automation off', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  /**
   * Guard for the two known failures below, and the list page's side of the
   * new Automation column in its own right.
   *
   * Two queues, not one: a column asserted only against a queue that has no
   * automation is satisfied by a cell that renders "Off" unconditionally, which
   * would hide precisely the regression the column exists to catch.
   */
  test(
    'The Automation column tells an automated queue from a plain one',
    { tag: ['@cap:annotation-queues.list-queues'] },
    async ({
      project,
      feedbackDefinition,
      backendClient,
      registerAnnotationQueueCleanup,
      testNamespace,
      page,
    }) => {
      const plainId = uuid7();
      const plainName = `${testNamespace}-plain`;
      const automatedId = uuid7();
      const automatedName = `${testNamespace}-automated`;

      await test.step('Seed one queue with no automation key at all', async () => {
        registerAnnotationQueueCleanup(plainId, plainName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: plainId,
          projectId: project.id,
          name: plainName,
          scope: 'trace',
        });
        expect(status, `creating the plain queue answered: ${message}`).toBe(201);
        // The precondition the rest of this file rests on, asserted rather than
        // assumed: `null` is the API confirming the queue carries no automation
        // block at all, which is the shape the edit dialog has to handle.
        expect(
          await backendClient.getAnnotationQueueAutomation(plainId),
          'a queue created without an automation key stores none',
        ).toBeNull();
      });

      await test.step('And one with automation enabled', async () => {
        registerAnnotationQueueCleanup(automatedId, automatedName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: automatedId,
          projectId: project.id,
          name: automatedName,
          scope: 'trace',
          automation: {
            enabled: true,
            groups: [[{ scoreName: feedbackDefinition.name, operator: '<', value: 0.5 }]],
          },
        });
        expect(status, `creating the automated queue answered: ${message}`).toBe(201);
      });

      const queuesPage = new AnnotationQueuesPage(page);
      await test.step('The list renders a row for each, reporting its own state', async () => {
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();
        await expect(queuesPage.queueRow(plainId)).toHaveCount(1);
        await expect(queuesPage.queueRow(automatedId)).toHaveCount(1);
        await expect(queuesPage.automationCell(plainId)).toHaveText('Off');
        await expect(queuesPage.automationCell(automatedId)).toHaveText('On');
      });

      await test.step('And the create form opens with automation off and no builder', async () => {
        const sheet = await queuesPage.openCreateForm();
        await expect(
          sheet.automationSwitch,
          'automation is opt-in from the list page',
        ).not.toBeChecked();
        // The builder is mounted only while the switch is on, so its absence is
        // what "the user configured nothing" looks like on screen — which is
        // precisely the state the form fails to serialise correctly below.
        await expect(sheet.groupCaptions).toHaveCount(0);
      });
    },
  );

  /**
   * Known failure — the automation-off create. Reproduced three times through
   * the UI and once directly against the API during the opik#8481 exploration;
   * not yet filed as a ticket, which it needs to be. Kept minimal, because a
   * blanket `test.fail()` swallows everything inside it and the guard test
   * above already covers the rest.
   */
  test(
    'A queue saves with the automation toggle left off',
    { tag: ['@cap:annotation-queues.create-queue'] },
    async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace, page }) => {
      test.fail();

      const queueName = `${testNamespace}-no-automation`;

      const queuesPage = new AnnotationQueuesPage(page);
      await queuesPage.goto(project.id);
      await queuesPage.waitForReady();

      const sheet = await queuesPage.openCreateForm();
      await sheet.nameInput.fill(queueName);
      await sheet.submit();

      const id = await backendClient.findAnnotationQueueIdByName(project.id, queueName);
      expect(id, `a queue named ${queueName} must exist after an accepted create`).not.toBeNull();
      // Reached only once the defect is fixed and this test starts passing —
      // at which point the queue is real and has to be cleaned up.
      registerAnnotationQueueCleanup(id!, queueName);
    },
  );

  /**
   * Known failure — the same defect from the other side: editing a queue that
   * has no automation. Wider blast radius than the create path, because that is
   * every queue predating OPIK-6303.
   *
   * The assertion is that the write is ACCEPTED, which is what
   * `AnnotationQueueFormSheet.submit()` waits for: the sheet closes only in the
   * mutation's `onSuccess`, so a 422 leaves it open and fails there.
   */
  test(
    'A queue with no automation can still be edited',
    { tag: ['@cap:annotation-queues.edit-queue'] },
    async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace, page }) => {
      test.fail();

      const queueId = uuid7();
      const queueName = `${testNamespace}-plain-edit`;

      registerAnnotationQueueCleanup(queueId, queueName);
      const { status } = await backendClient.createAnnotationQueue({
        id: queueId,
        projectId: project.id,
        name: queueName,
        scope: 'trace',
      });
      expect(status).toBe(201);

      const queuesPage = new AnnotationQueuesPage(page);
      await queuesPage.goto(project.id);
      await queuesPage.waitForReady();

      await queuesPage.openEditForm(queueId);
      const sheet = new AnnotationQueueFormSheet(page, 'edit');
      await sheet.instructionsInput.fill('Score anything that looks off.');
      await sheet.submit();
    },
  );
});
