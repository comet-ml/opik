import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';
import type { AnnotationQueueSettingsRef } from '@e2e/core/backend';

/**
 * Two stored shapes both mean "no automation": no block at all (how every queue
 * predating the feature is stored) and a block that is switched off. Written out
 * rather than as `automation?.enabled ?? false` so a third, unexpected shape
 * cannot be swallowed into a pass.
 */
const automationEnabled = (settings: AnnotationQueueSettingsRef): boolean =>
  settings.automation === null ? false : settings.automation.enabled;

/**
 * The two paths through the queue form that never touch automation: creating a
 * plain queue, and editing a queue that has none.
 *
 * Both assert the form saves. On this branch both FAIL, and the failure is the
 * app's: `AddEditAnnotationQueueDialog.getQueue()` serialises `automation_groups`
 * unconditionally, the field's default is one empty condition row
 * (`DEFAULT_UNWINDOWED_CONDITION`, `score_name: ""`), and the schema's
 * `superRefine` returns early when `automation_enabled` is false — so nothing
 * client-side stops the empty row being sent. The backend's
 * `ScoreCondition.scoreName` is `@NotBlank` under a `@Valid` conditions list and
 * answers 422 even though `enabled` is false.
 *
 * Confirmed at the API, independently of the form: posting a queue WITH that
 * automation block answers 422 `automation.conditions.groups[0].conditions[0].
 * scoreName must not be blank`, and posting the same queue WITHOUT the block
 * answers 201.
 *
 * Deliberately NOT marked `test.fail()`. The defect is in the pull request this
 * spec is proposed against rather than in a shipped release, so the right
 * outcome is a red run that goes green when the form stops sending the empty
 * row — not an expected-failure annotation somebody has to remember to remove.
 */
test.describe(
  'Annotation queue — saving with automation off',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'A queue created with the automation switch off saves and lists as Off',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({
        project,
        backendClient,
        registerAnnotationQueueCleanup,
        testNamespace,
        page,
      }) => {
        const queueName = `${testNamespace}-plain-queue`;
        const queuesPage = new AnnotationQueuesPage(page);

        await test.step('Open the create form on an empty project', async () => {
          await queuesPage.goto(project.id);
          await queuesPage.waitForReady();
        });

        const form = await queuesPage.openCreateForm();

        await test.step('Fill only the name, leaving automation switched off', async () => {
          await form.fillName(queueName);
          // Asserted rather than assumed: if the form ever defaulted automation
          // ON, the rest of this test would be exercising a different path and
          // would pass for the wrong reason.
          await expect(
            form.automationSwitch,
            'the create form defaults automation off',
          ).not.toBeChecked();
        });

        await test.step('Submit and verify the create was accepted', async () => {
          await form.submitExpectingSuccess();
        });

        const queueId = await test.step('Verify exactly one queue was created', async () => {
          const ids = await backendClient.findAnnotationQueuesByName(project.id, queueName);
          expect(ids, `exactly one queue named ${queueName}`).toHaveLength(1);
          registerAnnotationQueueCleanup(ids[0], queueName);
          return ids[0];
        });

        await test.step('Verify the list row reports Automation Off', async () => {
          await queuesPage.goto(project.id);
          await queuesPage.waitForReady();
          await queuesPage.waitForQueueRow(queueId);
          await expect(queuesPage.automationCell(queueId)).toHaveText('Off');
        });

        await test.step('Verify the API stored no enabled automation', async () => {
          const settings = await backendClient.getAnnotationQueueSettings(queueId);
          if (settings === null) throw new Error(`annotation queue ${queueId} no longer exists`);
          expect(automationEnabled(settings), 'no automation must be enabled').toBe(false);
        });
      },
    );

    test(
      'Editing a queue that has no automation saves and leaves comments disabled',
      { tag: ['@cap:annotation-queues.edit-queue'] },
      async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace, page }) => {
        const originalName = `${testNamespace}-preexisting-queue`;
        const editedName = `${originalName}-edited`;

        const queueId = await test.step('Seed a queue with no automation and comments off', async () => {
          const id = await backendClient.createAnnotationQueueRaw({
            projectId: project.id,
            name: originalName,
            scope: 'trace',
            commentsEnabled: false,
            feedbackDefinitionNames: [],
          });
          registerAnnotationQueueCleanup(id, originalName);

          // The fixture has to prove it produced the state under test: a queue
          // that arrived WITH an automation block would not be the
          // pre-automation shape this test is about.
          const seeded = await backendClient.getAnnotationQueueSettings(id);
          if (seeded === null) throw new Error(`seeded annotation queue ${id} was not found`);
          expect(seeded.automation, 'seeded queue carries no automation block').toBeNull();
          expect(seeded.commentsEnabled, 'seeded queue has comments disabled').toBe(false);
          return id;
        });

        const queuesPage = new AnnotationQueuesPage(page);

        await test.step('Open the queue in the edit form', async () => {
          await queuesPage.goto(project.id);
          await queuesPage.waitForReady();
          await queuesPage.waitForQueueRow(queueId);
        });

        const form = await queuesPage.openEditForm(queueId);

        await test.step('Change only the name', async () => {
          await expect(
            form.automationSwitch,
            'a queue with no automation hydrates the switch off',
          ).not.toBeChecked();
          await form.fillName(editedName);
        });

        await test.step('Submit and verify the update was accepted', async () => {
          await form.submitExpectingSuccess();
        });

        await test.step('Verify the rename landed and nothing else moved', async () => {
          const queue = await backendClient.getAnnotationQueue(queueId);
          expect(queue?.name).toBe(editedName);

          const settings = await backendClient.getAnnotationQueueSettings(queueId);
          if (settings === null) throw new Error(`annotation queue ${queueId} no longer exists`);
          // Pins the `comments_enabled ?? true` read: the earlier `|| true`
          // silently re-enabled comments on any edit of a queue that had them off.
          expect(settings.commentsEnabled, 'an edit must not re-enable comments').toBe(false);
          expect(automationEnabled(settings), 'an edit must not enable automation').toBe(false);
        });
      },
    );
  },
);
