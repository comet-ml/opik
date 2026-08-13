import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuePage, AnnotationQueuesPage } from '@e2e/pom/annotation-queue.page';

test.describe('Annotation queue — delete', { tag: ['@t2-cuj', '@area:annotation-queues', '@cap:annotation-queues.delete-queue'] }, () => {
  test('Deleting a queue removes it from the list and leaves its traces intact', async ({
    annotationQueue,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    // A second queue over the same traces: deleting one queue must remove that
    // row only, and must not take the shared source traces down with it.
    const survivor = await test.step('Seed a sibling queue that must survive', async () => {
      return sdkClient.python.createAnnotationQueue({
        project_name: annotationQueue.projectName,
        name: `${testNamespace}-survivor`,
        trace_ids: annotationQueue.traces.map((t) => t.id),
        feedback_definition_names: [annotationQueue.feedbackDefinitionName],
      });
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

    await test.step('Verify the queue and its items are unreachable via the API', async () => {
      expect(await backendClient.getAnnotationQueue(annotationQueue.id)).toBeNull();
      expect(await backendClient.getAnnotationQueue(survivor.id)).not.toBeNull();
    });

    await test.step('Verify the source traces were not deleted with the queue', async () => {
      for (const seeded of annotationQueue.traces) {
        const trace = await backendClient.getTrace(seeded.id);
        expect(trace, `trace ${seeded.name} should outlive the queue`).not.toBeNull();
      }
    });

    await test.step('Clean up the surviving queue', async () => {
      await backendClient.deleteAnnotationQueue(survivor.id);
    });
  });

  /**
   * Known failure — OPIK-7903. The detail page never consumes `isError` from
   * useAnnotationQueueById, so a deleted queue's URL renders an empty title over
   * a permanent "Loading" placeholder instead of a not-found state. Users reach
   * this by pressing back after a delete, or by opening a shared/bookmarked
   * queue link ("Copy sharing link") after someone else deleted the queue.
   *
   * Asserted against the CORRECT behaviour and marked test.fail(), so it reports
   * as an expected failure while the bug is open. When OPIK-7903 is fixed this
   * starts passing and Playwright fails the run demanding the annotation be
   * removed — which is the intended signal, not a regression.
   */
  test('A deleted queue URL shows a not-found state rather than loading forever', async ({
    annotationQueue,
    backendClient,
    page,
  }) => {
    test.fail();

    await test.step('Delete the seeded queue outright', async () => {
      await backendClient.deleteAnnotationQueue(annotationQueue.id);
      expect(await backendClient.getAnnotationQueue(annotationQueue.id)).toBeNull();
    });

    await test.step('Open the deleted queue URL and expect a not-found state', async () => {
      const detailPage = new AnnotationQueuePage(page);
      await detailPage.goto(annotationQueue.projectId, annotationQueue.id);

      await expect(detailPage.notFoundState).toBeVisible();
      await expect(detailPage.loadingIndicator).toBeHidden();
    });
  });
});
