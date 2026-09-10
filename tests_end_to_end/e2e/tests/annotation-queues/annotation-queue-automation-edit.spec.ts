import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

/**
 * Editing a queue that has automation configured.
 *
 * Two regressions here are silent in the UI and visible only in what was
 * stored:
 *
 *  - the edit form losing a saved condition, so a queue that looked configured
 *    quietly stops matching anything;
 *  - `comments_enabled` being flipped back on by a field the form no longer
 *    renders, which no screen would show afterwards.
 *
 * Both are asserted against the persisted queue rather than the form, because
 * the form is exactly what would be lying in either case.
 */
test.describe(
  'Annotation queue — edit automation',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'Editing a queue round-trips its automation and leaves comments disabled',
      { tag: ['@cap:annotation-queues.edit-queue'] },
      async ({ automationEditQueue, backendClient, page }) => {
        const { id, projectId, condition } = automationEditQueue;
        const queues = new AnnotationQueuesPage(page);

        const sheet = await test.step('Open the queue edit form', async () => {
          await queues.goto(projectId);
          await queues.waitForReady();
          return queues.openEditQueueForm(id);
        });

        await test.step('Verify the form loaded the saved automation', async () => {
          await expect(sheet.automationSwitch).toBeChecked();
          // The select's accessible name is the score it is showing, so
          // resolving to exactly one control named after the saved score is the
          // assertion that the right score came back.
          await expect(sheet.conditionScoreSelect(condition.score)).toHaveCount(1);
          // The saved operator must be the selected one AND the others not, so
          // a group that highlighted everything could not pass.
          await expect(sheet.conditionOperator('<')).toBeChecked();
          await expect(sheet.conditionOperator('>')).not.toBeChecked();
          await expect(sheet.conditionOperator('=')).not.toBeChecked();
          await expect(sheet.conditionThreshold()).toHaveValue(String(condition.value));
        });

        await test.step('Verify Scope is locked on an existing queue', async () => {
          await expect(sheet.scopeOption('Traces')).toBeDisabled();
          await expect(sheet.scopeOption('Threads')).toBeDisabled();
          await expect(sheet.scopeOption('Traces')).toBeChecked();
        });

        await test.step('Turn automation off and save', async () => {
          await sheet.automationSwitch.click();
          await expect(sheet.automationSwitch).not.toBeChecked();
          await sheet.submit('Update queue');
        });

        await test.step('Verify the disable persisted without wiping the conditions', async () => {
          const stored = await backendClient.getAnnotationQueueSettings(id);
          expect(stored, `queue ${id} should still exist after the edit`).not.toBeNull();
          expect(stored!.automation, 'the edit must not drop the automation object').not.toBeNull();
          expect(stored!.automation!.enabled).toBe(false);
          // The whole groups structure, not just "a condition is in there":
          // switching automation off must leave what was configured untouched,
          // so an edit that silently rewrote or emptied a group fails here.
          expect(stored!.automation!.groups).toEqual([{ conditions: [condition] }]);
        });

        await test.step('Verify the edit did not re-enable comments', async () => {
          const stored = await backendClient.getAnnotationQueueSettings(id);
          expect(stored!.commentsEnabled).toBe(false);
        });
      },
    );
  },
);
