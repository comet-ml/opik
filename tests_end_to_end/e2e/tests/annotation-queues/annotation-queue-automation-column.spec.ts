import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

/**
 * The Automation column on the queues list.
 *
 * The column is the only place a user can see whether a queue populates itself
 * without opening it, and a pill reading the wrong queue's state is invisible:
 * every row still renders a perfectly ordinary On or Off. The fixture seeds the
 * two queues to differ in `automation.enabled` alone — the disabled one keeps a
 * complete, valid condition — so a cell that reports "is anything configured?"
 * instead of "is it on?" fails here.
 */
test.describe(
  'Annotation queues — automation column',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      "The Automation column reads each queue's own automation state",
      { tag: ['@cap:annotation-queues.list-queues'] },
      async ({ automationQueuePair, page }) => {
        const { enabledQueue, disabledQueue } = automationQueuePair;
        const queues = new AnnotationQueuesPage(page);

        await test.step('Open the project annotation queues list', async () => {
          await queues.goto(automationQueuePair.projectId);
          await queues.waitForReady();
          await expect(queues.automationColumnHeader).toBeVisible();
        });

        await test.step('Verify the automation-enabled queue reads On', async () => {
          // toHaveCount(1) before the text assertion: the cell is found through
          // the row's queue id, and a lookup that resolved to zero rows (or to
          // two) would otherwise surface as a confusing text mismatch rather
          // than "the row I asked for is not there".
          await expect(queues.automationCell(enabledQueue.id)).toHaveCount(1);
          await expect(queues.automationCell(enabledQueue.id)).toHaveText('On');
        });

        await test.step('Verify the automation-disabled queue reads Off', async () => {
          await expect(queues.automationCell(disabledQueue.id)).toHaveCount(1);
          await expect(queues.automationCell(disabledQueue.id)).toHaveText('Off');
        });
      },
    );
  },
);
