import { test, expect } from '@e2e/fixtures';
import { AnnotationQueuePage } from '@e2e/pom/annotation-queue.page';

test.describe('Annotation queue — UI scoring, SDK verify', { tag: ['@t2-cuj', '@annotation-queue'] }, () => {
  test('Scoring two items and skipping a third reflects on the source traces', async ({
    annotationQueue,
    backendClient,
    page,
  }) => {
    const scoreName = annotationQueue.feedbackDefinitionName;
    const [firstItem, secondItem, skippedItem] = annotationQueue.traces;
    const queuePage = new AnnotationQueuePage(page);

    await test.step('Open the queue and score the first item', async () => {
      await queuePage.goto(annotationQueue.projectId, annotationQueue.id);
      await queuePage.waitForReady();
      const panel = await queuePage.openItem(
        annotationQueue.projectId,
        annotationQueue.id,
        firstItem.id,
      );
      await panel.openAnnotate();
      await panel.setAnnotateScore(scoreName, 1);
      // The score write is optimistic client-side — the UI tag updates before the
      // PUT resolves. Wait for the server to actually confirm it before setting
      // the reason, otherwise the two PUTs can land out of order and the second
      // (score-only) request wipes the reason from the first.
      await backendClient.pollTraceForFeedbackScore(firstItem.id, scoreName);
      await panel.setAnnotateReason(scoreName, 'Looks correct');
    });

    await test.step('Open the second item and score it with a different value and reason', async () => {
      const panel = await queuePage.openItem(
        annotationQueue.projectId,
        annotationQueue.id,
        secondItem.id,
      );
      await panel.openAnnotate();
      await panel.setAnnotateScore(scoreName, 0);
      await backendClient.pollTraceForFeedbackScore(secondItem.id, scoreName);
      await panel.setAnnotateReason(scoreName, 'Missed a key detail');
    });

    await test.step('Verify the first item scored correctly, reason included', async () => {
      const score = await backendClient.pollTraceForFeedbackScore(firstItem.id, scoreName);
      expect(score.value).toBe(1);
      expect(score.reason).toBe('Looks correct');
    });

    await test.step('Verify the second item scored correctly, reason included', async () => {
      const score = await backendClient.pollTraceForFeedbackScore(secondItem.id, scoreName);
      expect(score.value).toBe(0);
      expect(score.reason).toBe('Missed a key detail');
    });

    await test.step('Verify the skipped item has no feedback score from this queue', async () => {
      const trace = await backendClient.getTrace(skippedItem.id);
      expect(trace?.feedbackScores.find((fs) => fs.name === scoreName)).toBeUndefined();
    });
  });
});
