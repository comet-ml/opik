import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuePage, AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

/**
 * Name handling in the create/edit annotation queue dialog (opik#8056, OPIK-7957).
 *
 * Two behaviours that live ONLY in the frontend, which is why they need a UI
 * test rather than an API one. Measured against this environment's backend
 * before the specs were written:
 *
 *   - `POST /v1/private/annotation-queues` with `name: "   "` answers 422
 *     (`@NotBlank` on AnnotationQueue.name). The dialog must never send it —
 *     pre-fix it did, and then closed anyway, discarding what the user typed.
 *   - `POST` with `name: "  padded  "` answers 201 and reads back WITH the
 *     padding. The backend does not trim, so a trimmed record can only have
 *     come from the dialog's own `name: formData.name.trim()`.
 *
 * Each test therefore drives the dialog and reads the result back through the
 * API: the queues list truncates the Name column, so a row label cannot tell a
 * padded name from its trimmed twin.
 */
test.describe('Annotation queue — name validation and trimming', { tag: ['@t2-cuj', '@area:annotation-queues'] }, () => {
  const WHITESPACE_NAME = '     ';

  test(
    'Creating a queue rejects a whitespace-only name and trims a padded one',
    { tag: ['@cap:annotation-queues.create-queue'] },
    async ({ project, backendClient, registerAnnotationQueueCleanup, testNamespace, page }) => {
      const paddedName = `  ${testNamespace}-created  `;
      const trimmedName = `${testNamespace}-created`;
      const queuesPage = new AnnotationQueuesPage(page);

      await test.step('Confirm the seeded project starts with no queues', async () => {
        // The baseline every later count is read against. If the project were
        // not actually empty, "exactly one queue, and it is mine" below could
        // pass over someone else's row.
        const before = await backendClient.listAnnotationQueuesForProject(project.id);
        expect(before.total).toBe(0);
        expect(before.queues).toHaveLength(0);
      });

      const dialog = await test.step('Open the create queue dialog', async () => {
        await queuesPage.goto(project.id);
        await queuesPage.waitForReady();
        return queuesPage.openCreateDialog();
      });

      await test.step('Submitting a whitespace-only name is blocked in the dialog', async () => {
        await dialog.fillName(WHITESPACE_NAME);
        await dialog.submit();

        await expect(dialog.nameRequiredError).toBeVisible();
        await expect(dialog.root).toBeVisible();
        // Asserted after the inline error, deliberately. The error only renders
        // because zod rejected the value client-side, so by the time it is up no
        // create request can still be in flight — which is what makes this read
        // a real check rather than a race the API happens to win.
        const after = await backendClient.listAnnotationQueuesForProject(project.id);
        expect(after.total).toBe(0);
        expect(after.queues).toHaveLength(0);
      });

      await test.step('Submitting a padded name closes the dialog', async () => {
        await dialog.fillName(paddedName);
        await dialog.submitAndExpectClosed();
      });

      const created = await test.step('The stored queue name carries no padding', async () => {
        await expect
          .poll(
            async () => (await backendClient.listAnnotationQueuesForProject(project.id)).total,
            { message: 'the created queue should be the only queue in the project' },
          )
          .toBe(1);

        const listed = await backendClient.listAnnotationQueuesForProject(project.id);
        expect(listed.queues).toHaveLength(1);
        const [queue] = listed.queues;
        registerAnnotationQueueCleanup(queue.id, queue.name);
        expect(queue.name).toBe(trimmedName);
        return queue;
      });

      await test.step('The queue detail header renders the trimmed name', async () => {
        const detailPage = new AnnotationQueuePage(page);
        await detailPage.goto(project.id, created.id);
        await detailPage.waitForReady();
        await expect(detailPage.queueNameHeading).toHaveCount(1);
        await expect(detailPage.queueNameHeading).toHaveText(trimmedName);
      });
    },
  );

  test(
    'Editing a queue from the list rejects a whitespace-only name and trims a padded one',
    { tag: ['@cap:annotation-queues.edit-queue'] },
    async ({ annotationQueue, backendClient, testNamespace, page }) => {
      const paddedName = `  ${testNamespace}-renamed  `;
      const trimmedName = `${testNamespace}-renamed`;
      const queuesPage = new AnnotationQueuesPage(page);

      const dialog = await test.step('Open the edit dialog from the queue row', async () => {
        await queuesPage.goto(annotationQueue.projectId);
        await queuesPage.waitForReady();
        return queuesPage.openEditDialog(annotationQueue.id);
      });

      await test.step('The dialog is prefilled with the current name', async () => {
        await expect(dialog.nameInput).toHaveValue(annotationQueue.name);
      });

      await test.step('Submitting a whitespace-only name is blocked and changes nothing', async () => {
        await dialog.fillName(WHITESPACE_NAME);
        await dialog.submit();

        await expect(dialog.nameRequiredError).toBeVisible();
        await expect(dialog.root).toBeVisible();
        const unchanged = await backendClient.getAnnotationQueue(annotationQueue.id);
        expect(unchanged).not.toBeNull();
        expect(unchanged?.name).toBe(annotationQueue.name);
      });

      await test.step('Submitting a padded name closes the dialog', async () => {
        await dialog.fillName(paddedName);
        await dialog.submitAndExpectClosed();
      });

      await test.step('The renamed queue is stored without padding', async () => {
        await expect
          .poll(
            async () => (await backendClient.getAnnotationQueue(annotationQueue.id))?.name,
            { message: 'the edit should land on the queue record' },
          )
          .toBe(trimmedName);

        // The rename must not have forked a second queue in the project: the
        // seeded one, renamed, is the whole answer.
        const listed = await backendClient.listAnnotationQueuesForProject(
          annotationQueue.projectId,
        );
        expect(listed.total).toBe(1);
        expect(listed.queues).toHaveLength(1);
        expect(listed.queues[0].id).toBe(annotationQueue.id);
      });
    },
  );

  test(
    'Editing a queue from its detail page trims a padded name in the header',
    { tag: ['@cap:annotation-queues.edit-queue'] },
    async ({ annotationQueue, backendClient, testNamespace, page }) => {
      // A second mount of the same dialog, from a different entry point and over
      // a different header. The list row cannot show this at all (the Name
      // column truncates); the detail h1 renders the name in full, so it is the
      // one place a padded name is visibly distinguishable from a trimmed one.
      const paddedName = `  ${testNamespace}-detail  `;
      const trimmedName = `${testNamespace}-detail`;
      const detailPage = new AnnotationQueuePage(page);

      await test.step('Open the queue detail page on its seeded name', async () => {
        await detailPage.goto(annotationQueue.projectId, annotationQueue.id);
        await detailPage.waitForReady();
        await expect(detailPage.queueNameHeading).toHaveCount(1);
        await expect(detailPage.queueNameHeading).toHaveText(annotationQueue.name);
      });

      await test.step('Rename it with a padded name from the detail Edit button', async () => {
        const dialog = await detailPage.openEditDialog();
        await expect(dialog.nameInput).toHaveValue(annotationQueue.name);
        await dialog.fillName(paddedName);
        await dialog.submitAndExpectClosed();
      });

      await test.step('The header renders the trimmed name', async () => {
        await expect(detailPage.queueNameHeading).toHaveText(trimmedName);
      });

      await test.step('The stored record carries no padding either', async () => {
        const stored = await backendClient.getAnnotationQueue(annotationQueue.id);
        expect(stored).not.toBeNull();
        expect(stored?.name).toBe(trimmedName);
      });
    },
  );
});
