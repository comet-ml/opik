import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuePage, AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

test.describe('Annotation queue — delete', { tag: ['@t2-cuj', '@area:annotation-queues', '@cap:annotation-queues.delete-queue'] }, () => {
  /**
   * Queues created inside a test, cleaned up after it whatever the outcome.
   * Annotation queues cascade with neither the project fixture nor the
   * prefix sweep in global-teardown, so a test that fails mid-way would
   * otherwise orphan them permanently.
   */
  const queuesToClean: string[] = [];

  test.afterEach(async ({ backendClient }) => {
    while (queuesToClean.length) {
      const id = queuesToClean.pop()!;
      try {
        await backendClient.deleteAnnotationQueue(id);
      } catch (err) {
        console.warn(`[annotation-queue-delete] cleanup warning for ${id}:`, err);
      }
    }
  });

  test('Deleting a queue removes it from the list and leaves its traces intact', async ({
    annotationQueue,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    // A second queue over the same traces: deleting one queue must remove that
    // row only, and must not take the shared source traces (or the sibling's own
    // item rows) down with it.
    const survivor = await test.step('Seed a sibling queue that must survive', async () => {
      const created = await sdkClient.python.createAnnotationQueue({
        project_name: annotationQueue.projectName,
        name: `${testNamespace}-survivor`,
        trace_ids: annotationQueue.traces.map((t) => t.id),
        feedback_definition_names: [annotationQueue.feedbackDefinitionName],
      });
      queuesToClean.push(created.id);
      return created;
    });

    const queuesPage = new AnnotationQueuesPage(page);

    await test.step('Open the queues list with both queues present', async () => {
      await queuesPage.goto(annotationQueue.projectId);
      await queuesPage.waitForReady();
      await expect(queuesPage.queueRow(annotationQueue.id)).toBeVisible();
      await expect(queuesPage.queueRow(survivor.id)).toBeVisible();
    });

    await test.step('Delete the queue through the row actions menu', async () => {
      await queuesPage.deleteQueue(annotationQueue.id);
    });

    await test.step('Verify only the deleted queue left the list', async () => {
      await expect(queuesPage.queueRow(annotationQueue.id)).toHaveCount(0);
      await expect(queuesPage.queueRow(survivor.id)).toBeVisible();
    });

    await test.step('Verify the deleted queue survives a reload as gone', async () => {
      await queuesPage.goto(annotationQueue.projectId);
      await queuesPage.waitForReady();
      await expect(queuesPage.queueRow(annotationQueue.id)).toHaveCount(0);
      await expect(queuesPage.queueRow(survivor.id)).toBeVisible();
    });

    await test.step('Verify the deleted queue is gone from the API', async () => {
      expect(await backendClient.getAnnotationQueue(annotationQueue.id)).toBeNull();
    });

    await test.step("Verify the sibling queue kept its item membership", async () => {
      // itemsCount is the item-membership signal the public surface exposes:
      // if deleting one queue collaterally removed shared annotation_queue_items
      // rows, the sibling would still resolve but report fewer than its 3 items.
      const kept = await backendClient.getAnnotationQueue(survivor.id);
      expect(kept).not.toBeNull();
      expect(kept?.itemsCount).toBe(annotationQueue.traces.length);
    });

    await test.step('Verify the source traces were not deleted with the queue', async () => {
      for (const seeded of annotationQueue.traces) {
        const trace = await backendClient.getTrace(seeded.id);
        expect(trace, `trace ${seeded.name} should outlive the queue`).not.toBeNull();
      }
    });
  });

  /**
   * Guard for the known-failure test below: everything about the deleted-queue
   * route EXCEPT the not-found state, asserted normally so it fails loudly. Its
   * counterpart carries a blanket test.fail(), which would otherwise mask a
   * failed delete or a POM navigating somewhere else entirely as an "expected"
   * failure and keep reporting green while testing nothing.
   *
   * Note the URL assertion: the detail shell (tabs, breadcrumb chrome) renders
   * for ANY id, valid or not, so it cannot tell a live queue from a dead one —
   * only the resolved URL confirms the POM landed on the deleted queue's route.
   */
  test('The deleted queue detail route resolves to the deleted queue', async ({
    annotationQueue,
    backendClient,
    page,
  }) => {
    await backendClient.deleteAnnotationQueue(annotationQueue.id);
    expect(await backendClient.getAnnotationQueue(annotationQueue.id)).toBeNull();

    const detailPage = new AnnotationQueuePage(page);
    await detailPage.goto(annotationQueue.projectId, annotationQueue.id);

    await expect(page).toHaveURL(
      new RegExp(`/projects/${annotationQueue.projectId}/annotation-queues/${annotationQueue.id}(\\?|$)`),
    );
    await expect(page.getByRole('tab', { name: 'Queue items' })).toBeVisible();
  });

  /**
   * Known failure — OPIK-7903. The detail page never consumes `isError` from
   * useAnnotationQueueById, so a deleted queue's URL renders an empty title over
   * a permanent "Loading" placeholder instead of a not-found state. Users reach
   * this by pressing back after a delete, or by opening a shared/bookmarked
   * queue link ("Copy sharing link") after someone else deleted the queue.
   *
   * Asserted against the CORRECT behaviour and marked test.fail(), so it reports
   * as an expected failure while the bug is open, then fails with "Expected to
   * fail, but passed" once OPIK-7903 lands — prompting removal of test.fail().
   *
   * Kept deliberately minimal: a blanket test.fail() swallows every failure in
   * its test, so the only things inside it are the navigation and the single
   * assertion under investigation. The sibling test above covers the rest.
   */
  test('A deleted queue URL shows a not-found state rather than loading forever', async ({
    annotationQueue,
    backendClient,
    page,
  }) => {
    test.fail();

    await backendClient.deleteAnnotationQueue(annotationQueue.id);

    const detailPage = new AnnotationQueuePage(page);
    await detailPage.goto(annotationQueue.projectId, annotationQueue.id);

    await expect(detailPage.notFoundState).toBeVisible();
  });
});
