import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

/**
 * The create/edit annotation-queue dialog (OPIK-7957 / opik PR 8056).
 *
 * Every other queue in this estate is created over the API, so the dialog itself
 * — the only way a user makes one — was driven by no spec at all. The three
 * things asserted here are the three the PR changed: the name is trimmed before
 * it is stored, a blank name is rejected WITHOUT throwing away the rest of the
 * form, and a submit already in flight cannot be submitted again.
 *
 * The data-loss half is the one that matters: a regression there re-loses
 * whatever the user had typed, which is invisible in any assertion that only
 * checks whether a queue exists.
 *
 * Queue names are NOT unique in a project, so the dialog's remaining branch —
 * "the server rejected your payload, keep the form" — cannot be provoked from
 * outside the app and is not covered here.
 */
test.describe(
  'Annotation queue — create & edit dialog',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'A blank name is rejected with the form intact, and a padded name is stored trimmed',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({ project, backendClient, annotationQueueSweep, page }) => {
        const instructions = 'keep-me-instructions';
        const typedName = `  ${annotationQueueSweep.name('trim')}  `;
        const storedName = annotationQueueSweep.name('trim');

        const queuesPage = new AnnotationQueuesPage(page);
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();

        const dialog = await queuesPage.openCreateDialog();

        await test.step('A whitespace-only name is rejected without losing the form', async () => {
          await dialog.fill({ name: '   ', instructions });
          await dialog.submit();

          await expect(dialog.nameRequiredMessage).toBeVisible();
          await expect(dialog.root, 'the dialog must stay open on a rejected name').toBeVisible();
          await expect(
            dialog.instructionsInput,
            'the rest of the form must survive the rejection',
          ).toHaveValue(instructions);
        });

        await test.step('A padded name submits, and the dialog closes', async () => {
          await dialog.fill({ name: typedName });
          await dialog.submit();
          await dialog.expectClosed();
        });

        await test.step('Exactly one queue exists, stored under the trimmed name', async () => {
          // Also the assertion that the rejected submit above created nothing:
          // a queue from that attempt would show up here as a second row, and it
          // cannot still be in flight because this create has already answered.
          const queues = await backendClient.listAnnotationQueuesWithPrefix(
            annotationQueueSweep.prefix,
          );
          expect(queues.map((q) => q.name)).toEqual([storedName]);
        });

        await test.step('The list renders the trimmed name', async () => {
          const [created] = await backendClient.listAnnotationQueuesWithPrefix(
            annotationQueueSweep.prefix,
          );
          await expect(queuesPage.queueRow(created.id)).toContainText(storedName);
        });
      },
    );

    test(
      'Double-submitting the create dialog creates exactly one queue',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({ project, backendClient, annotationQueueSweep, page }) => {
        const name = annotationQueueSweep.name('once');

        const queuesPage = new AnnotationQueuesPage(page);
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();

        const dialog = await queuesPage.openCreateDialog();
        await dialog.fill({ name });
        await dialog.doubleSubmit();
        await dialog.expectClosed();

        await test.step('Only one queue was created', async () => {
          // Read the API only after the list page has re-fetched and painted the
          // new row: that round trip is ordered after both clicks, so a second
          // create cannot still be in flight and pass this by arriving late.
          await queuesPage.goto(project.id);
          await queuesPage.waitForReady();

          const queues = await backendClient.listAnnotationQueuesWithPrefix(
            annotationQueueSweep.prefix,
          );
          expect(queues.map((q) => q.name)).toEqual([name]);
          await expect(queuesPage.queueRow(queues[0].id)).toBeVisible();
        });
      },
    );

    test(
      'Renaming a queue from the row actions stores the trimmed name',
      { tag: ['@cap:annotation-queues.edit-queue'] },
      async ({ annotationQueue, backendClient, testNamespace, page }) => {
        const storedName = `${testNamespace}-renamed`;

        const queuesPage = new AnnotationQueuesPage(page);
        await queuesPage.goto(annotationQueue.projectId);
        await queuesPage.waitForReady();

        const dialog = await queuesPage.openEditDialog(annotationQueue.id);

        await test.step('The edit dialog opens on the queue as stored', async () => {
          await expect(dialog.nameInput).toHaveValue(annotationQueue.name);
        });

        await test.step('Save a padded name', async () => {
          await dialog.fill({ name: `   ${storedName}   ` });
          await dialog.submit();
          await dialog.expectClosed();
        });

        await test.step('The stored name is the trimmed one', async () => {
          const updated = await backendClient.getAnnotationQueue(annotationQueue.id);
          expect(updated).not.toBeNull();
          expect(updated?.name).toBe(storedName);
        });

        await test.step('The list row shows the trimmed name', async () => {
          await expect(queuesPage.queueRow(annotationQueue.id)).toContainText(storedName);
        });
      },
    );
  },
);
