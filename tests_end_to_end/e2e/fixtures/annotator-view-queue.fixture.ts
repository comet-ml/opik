import { expect } from '@playwright/test';
import { test as baseTest } from './environment-threads.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface AnnotatorViewQueueRef {
  queueId: string;
  queueName: string;
  projectId: string;
  projectName: string;
  traceId: string;
  traceName: string;
  /** The exact string the trace's `input.question` holds — unique in the workspace. */
  question: string;
  /** The exact string the trace's `output.answer` holds — unique in the workspace. */
  answer: string;
}

export interface AnnotatorViewQueueFixtures {
  annotatorViewQueue: AnnotatorViewQueueRef;
}

/** How long the queue's one item may take to become readable through the queue. */
const QUEUE_ITEM_VISIBLE_TIMEOUT_MS = 60_000;

/**
 * A `scope: 'trace'` annotation queue holding exactly one trace, for the custom
 * annotator view (opik#9079).
 *
 * **One item, deliberately.** The SME flow lands on the first unprocessed item
 * of a per-user deterministic shuffle keyed on the queue and item ids — which
 * are fresh UUIDs per run, so with two items a spec could not say which one the
 * view would be handed. With one, the trace whose fields the view renders is
 * the trace this ref names.
 *
 * **The payload is a `question`/`answer` object, not a bare string.** The kit's
 * `trace.input` is whatever the traced app logged, and a view reaches into it
 * with `kit.get(trace.input, ['question'])` — so a seed that logged a string
 * would exercise the fallback path rather than the documented one. Both values
 * carry the test namespace, so "the view rendered THIS trace's fields" is a
 * claim a page-wide text lookup can make: neither string occurs anywhere else
 * in the workspace.
 *
 * No feedback definitions: the subject is what the annotator is SHOWN, and the
 * scoring half of the SME flow is `sme-trace-queue-annotation.spec.ts`'s.
 *
 * The queue's `annotator_view` is deliberately NOT seeded here. Storing,
 * replacing and clearing it is the thing under test, so it belongs in the test
 * body; the queue starts with none, which is also the state the first
 * assertion needs.
 *
 * Teardown deletes the queue and the trace. Queues cascade with neither the
 * project fixture nor the run-prefix sweep in `global-teardown.ts`.
 */
export const test = baseTest.extend<AnnotatorViewQueueFixtures>({
  annotatorViewQueue: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceId = uuid7();
    const traceName = `${testNamespace}-annotator-view-trace`;
    const queueId = uuid7();
    const queueName = `${testNamespace}-annotator-view-queue`;
    const question = `${testNamespace}-question`;
    const answer = `${testNamespace}-answer`;

    try {
      const now = new Date();
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: traceName,
        source: 'sdk',
        input: { question },
        output: { answer },
        startTime: now,
        endTime: now,
      });

      const created = await backendClient.createAnnotationQueue({
        id: queueId,
        projectId: project.id,
        name: queueName,
        scope: 'trace',
      });
      if (created.status !== 201) {
        throw new Error(
          `annotatorViewQueue fixture: creating queue ${queueName} answered ` +
            `${created.status}: ${created.message}`,
        );
      }

      await backendClient.addAnnotationQueueItems(queueId, [traceId]);

      // The add is a 204 over an eventually-consistent write. Polled on the
      // queue's own item count so a spec that found the SME flow on its
      // no-items state fails here, where the cause is legible, rather than as
      // an absent custom view.
      await expect
        .poll(async () => (await backendClient.getAnnotationQueue(queueId))?.itemsCount ?? 0, {
          message: 'the queue must hold its one trace before the SME flow is opened',
          timeout: QUEUE_ITEM_VISIBLE_TIMEOUT_MS,
          intervals: [1_000, 2_000],
        })
        .toBe(1);

      // The queue starts with no custom view, which is what the first
      // assertion of the spec depends on — asserted rather than assumed, since
      // a queue that somehow arrived with one would make "the tag appeared
      // after the PATCH" true without the PATCH having done anything.
      expect(
        await backendClient.getAnnotationQueueAnnotatorView(queueId),
        'a freshly created queue carries no annotator view',
      ).toBeNull();

      const ref: AnnotatorViewQueueRef = {
        queueId,
        queueName,
        projectId: project.id,
        projectName: project.name,
        traceId,
        traceName,
        question,
        answer,
      };
      await testInfo.attach('opik.annotatorViewQueue', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        try {
          await backendClient.deleteAnnotationQueue(queueId);
        } catch (err) {
          console.warn(`[annotatorViewQueue fixture] queue delete warning for ${queueName}:`, err);
        }
        try {
          await backendClient.deleteTraces([traceId]);
        } catch (err) {
          console.warn(`[annotatorViewQueue fixture] trace delete warning for ${traceName}:`, err);
        }
      }
    }
  },
});

export { expect } from './environment-threads.fixture';
