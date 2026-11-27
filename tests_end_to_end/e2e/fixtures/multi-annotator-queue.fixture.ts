import { expect } from '@playwright/test';
import { test as baseTest } from './evaluator-source-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/** One trace in the queue, and the name the items sidebar previews it by. */
export interface MultiAnnotatorTraceRef {
  id: string;
  /** Also the sidebar's preview label — `trace.name || id.slice(-12)`. */
  name: string;
}

export interface MultiAnnotatorQueueRef {
  queueId: string;
  queueName: string;
  projectId: string;
  projectName: string;
  /** The feedback definition the queue offers, and the only score name it counts. */
  scoreName: string;
  /** Both queued traces, in seed order. Which one the flow lands on is a shuffle. */
  traces: MultiAnnotatorTraceRef[];
  /** The ceiling the queue was configured with, read back from the server. */
  annotatorsPerItem: number;
}

export interface MultiAnnotatorQueueFixtures {
  multiAnnotatorQueue: MultiAnnotatorQueueRef;
}

/**
 * How many annotators an item needs before the flow calls it Completed.
 *
 * Two, which is the smallest value that makes "one person is not enough" a
 * question at all: at the default of one, every state after a single review is
 * legitimately Completed and the bug opik#9474 fixed is unobservable.
 */
const ANNOTATORS_PER_ITEM = 2;

/**
 * How many traces the queue holds.
 *
 * Two, and both load-bearing. The flow lands on the first unprocessed item of a
 * per-user shuffle, so the second is the BYSTANDER: it is what makes
 * `"1 remaining"` a number rather than the only number the header could show,
 * and what shows that annotating one item did not mark the queue's other items
 * as touched.
 */
const QUEUED_TRACE_COUNT = 2;

/** How long the queue and its items may take to become readable. */
const QUEUE_READY_TIMEOUT_MS = 60_000;

/**
 * A `scope: 'trace'` annotation queue configured for TWO annotators per item,
 * over one numerical feedback definition and two traces.
 *
 * The seed for opik#9474 (OPIK-8546). `getItemState` calls an item Completed
 * once `getDistinctAnnotatorCount` reaches the queue's ceiling, and that count
 * used to be the number of `value_by_author` KEYS. Queue-scoped scores are
 * keyed `<author>_<queueId>` while comments and plain scores carry the bare
 * username, so one reviewer who left two artefacts counted as two people and
 * the item left review after a single pass. Every ingredient of that has to be
 * configured, not defaulted: a ceiling above one, a feedback definition the
 * queue actually counts, and a queue id for the composite key to be built from.
 *
 * `annotators_per_item` appears nowhere else in the estate, which is why the
 * value is asserted back off the server below rather than assumed — a queue
 * that silently stored no ceiling would, with the API's `@Nullable` default,
 * behave like a single-annotator queue, and "the item is not Completed yet"
 * can never hold there.
 *
 * Seeded over REST rather than through the queue dialog: the create sheet is
 * covered by `annotation-queue-form-sheet.spec.ts`, and what is under test here
 * is the reviewer flow's arithmetic, not how the queue was made.
 *
 * Teardown deletes the queue and the traces. Queues cascade with neither the
 * project fixture nor the run-prefix sweep in `global-teardown.ts`; the
 * feedback definition is the `feedbackDefinition` fixture's to remove.
 */
export const test = baseTest.extend<MultiAnnotatorQueueFixtures>({
  multiAnnotatorQueue: async (
    { backendClient, project, feedbackDefinition, testNamespace },
    use,
    testInfo,
  ) => {
    const queueId = uuid7();
    const queueName = `${testNamespace}-multi-annotator-queue`;
    const traces: MultiAnnotatorTraceRef[] = Array.from(
      { length: QUEUED_TRACE_COUNT },
      (_, i) => ({ id: uuid7(), name: `${testNamespace}-item-${i}` }),
    );

    const seededTraceIds: string[] = [];
    let createdQueue = false;
    try {
      for (const trace of traces) {
        const now = new Date();
        await backendClient.createTraceWithSource({
          id: trace.id,
          projectName: project.name,
          name: trace.name,
          source: 'sdk',
          input: { question: `${trace.name} question` },
          output: { answer: `${trace.name} answer` },
          startTime: now,
          endTime: now,
        });
        seededTraceIds.push(trace.id);
      }

      const created = await backendClient.createAnnotationQueue({
        id: queueId,
        projectId: project.id,
        name: queueName,
        scope: 'trace',
        annotatorsPerItem: ANNOTATORS_PER_ITEM,
        feedbackDefinitionNames: [feedbackDefinition.name],
      });
      if (created.status !== 201) {
        throw new Error(
          `multiAnnotatorQueue fixture: creating queue ${queueName} answered ` +
            `${created.status}: ${created.message}`,
        );
      }
      createdQueue = true;

      // The create answers 201 over an eventually-consistent write, so the
      // queue is not necessarily readable when the next call runs — on a cloud
      // deployment under parallel load `addAnnotationQueueItems` straight after
      // a 201 has answered `404 Annotation queue not found`. Polled before the
      // add so a create that did not take fails here, naming the create.
      await expect
        .poll(async () => (await backendClient.getAnnotationQueue(queueId))?.id ?? null, {
          message: `the queue ${queueName} must be readable before items are added`,
          timeout: QUEUE_READY_TIMEOUT_MS,
          intervals: [500, 1_000, 2_000],
        })
        .toBe(queueId);

      await backendClient.addAnnotationQueueItems(
        queueId,
        traces.map((t) => t.id),
      );

      await expect
        .poll(async () => (await backendClient.getAnnotationQueue(queueId))?.itemsCount ?? 0, {
          message: 'the queue must hold both traces before the SME flow is opened',
          timeout: QUEUE_READY_TIMEOUT_MS,
          intervals: [1_000, 2_000],
        })
        .toBe(QUEUED_TRACE_COUNT);

      // The seed's discriminating properties, asserted before any browser
      // opens. Without the ceiling the flow has no reason to withhold
      // Completed; without the definition name `getDistinctAnnotatorCount`
      // skips every score the reviewer writes and counts nobody. Either way the
      // spec would pass or fail for reasons that have nothing to do with 9474.
      const form = await backendClient.getAnnotationQueueForm(queueId);
      expect(form.annotatorsPerItem, 'the queue stored the two-annotator ceiling').toBe(
        ANNOTATORS_PER_ITEM,
      );
      expect(
        form.feedbackDefinitionNames,
        'and counts exactly the one feedback definition the reviewer will score',
      ).toEqual([feedbackDefinition.name]);

      // And that no item arrives already annotated: every assertion about a
      // state CHANGE needs the starting point to be the unprocessed one.
      for (const trace of traces) {
        const scores = await backendClient.getTraceScoreAuthors(trace.id);
        expect(scores, `${trace.name} starts with no feedback scores`).toEqual([]);
        expect(
          await backendClient.listTraceComments(trace.id),
          `${trace.name} starts with no comments`,
        ).toEqual([]);
      }

      const ref: MultiAnnotatorQueueRef = {
        queueId,
        queueName,
        projectId: project.id,
        projectName: project.name,
        scoreName: feedbackDefinition.name,
        traces,
        annotatorsPerItem: ANNOTATORS_PER_ITEM,
      };

      await testInfo.attach('opik.multiAnnotatorQueue', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        if (createdQueue) {
          try {
            await backendClient.deleteAnnotationQueue(queueId);
          } catch (err) {
            console.warn(`[multiAnnotatorQueue fixture] queue delete warning for ${queueName}:`, err);
          }
        }
        if (seededTraceIds.length > 0) {
          try {
            await backendClient.deleteTraces(seededTraceIds);
          } catch (err) {
            console.warn('[multiAnnotatorQueue fixture] trace delete warning:', err);
          }
        }
      }
    }
  },
});

export { expect } from './evaluator-source-traces.fixture';
