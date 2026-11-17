import { expect } from '@playwright/test';
import { test as baseTest } from './studio-unlisted-model-runs.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface QueueFormSeedRef {
  projectId: string;
  projectName: string;
  /** The traces the annotator-view card samples from, in the order they were written. */
  traceIds: readonly string[];
  /** A queue to open the EDIT sheet on, already carrying instructions. */
  queueId: string;
  queueName: string;
  /** Exactly what `queueId`'s stored `instructions` holds before the test edits it. */
  queueInstructions: string;
  /** A second queue the specs never open. Its stored instructions must not move. */
  bystanderQueueId: string;
  bystanderQueueName: string;
  bystanderInstructions: string;
}

export interface QueueFormSeedFixtures {
  queueFormSeed: QueueFormSeedRef;
}

/**
 * The preconditions the create / edit annotation-queue sheet needs —
 * `AddEditAnnotationQueueDialog`, rewritten in opik#9259 and opened by no spec
 * in the estate before this one.
 *
 * ## Why traces, and why more than one
 *
 * `AnnotatorViewCard` renders a different card per sample state, and the
 * interesting one is the state a real project is in:
 * `useAnnotatorTracePreviewSamples` falls back to the PROJECT's traces when the
 * sheet has no queue id (which is every create), so an empty project puts the
 * card on its `hasNoSamples` branch — a Log traces action and the "No traces
 * yet." copy instead of Preview. A spec that wanted the has-samples card and
 * seeded nothing would assert the empty one and never say so.
 *
 * Two rather than one so the card is sampling a list rather than a singleton;
 * the sample navigation is indexed, and a one-row list cannot tell a working
 * index from one pinned at zero.
 *
 * **The sample list is asserted through the API before any browser opens.** A
 * UI assertion over a fixture that silently failed to seed is a test that
 * cannot fail, and the empty-state card is a perfectly plausible render — so
 * "the card is in its has-samples state" has to rest on a fact established
 * here, where a seeding failure is unmistakable.
 *
 * ## Why a queue, and why two
 *
 * The edit sheet can only be reached from an existing queue's row, and seeding
 * it over REST rather than through the create sheet keeps the two halves
 * independent: a create-path regression cannot then be mistaken for an
 * edit-path one. It is seeded WITH instructions, because the edit assertion is
 * that a CHANGED value replaced a stored one — a queue that started with none
 * would make "the instructions changed" true of a save that merely wrote for
 * the first time.
 *
 * The bystander is the second queue, which nothing opens. A PATCH aimed at the
 * wrong id, or one the backend fanned out across the project's queues, would
 * satisfy "the edited queue's instructions changed" exactly as well as the
 * correct save does; the bystander's instructions holding still is what tells
 * them apart. Same role as `bystanderExperiment` and `bystanderPrompt`.
 *
 * ## Teardown
 *
 * Both queues and every trace are deleted explicitly. Neither cascades with
 * the project fixture's `deleteProject`, and `global-teardown.ts`'s run-prefix
 * sweep knows nothing about traces at all.
 */
const TRACE_COUNT = 2;

/** How long the queues may take to become readable after their 201. */
const QUEUE_VISIBLE_TIMEOUT_MS = 60_000;

export const test = baseTest.extend<QueueFormSeedFixtures>({
  queueFormSeed: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];
    const queueId = uuid7();
    const queueName = `${testNamespace}-edit-queue`;
    const queueInstructions = `${testNamespace} seeded instructions`;
    const bystanderQueueId = uuid7();
    const bystanderQueueName = `${testNamespace}-bystander-queue`;
    const bystanderInstructions = `${testNamespace} bystander instructions`;

    try {
      const now = new Date();
      for (let i = 0; i < TRACE_COUNT; i += 1) {
        const id = uuid7();
        await backendClient.createTraceWithSource({
          id,
          projectName: project.name,
          name: `${testNamespace}-sample-${i}`,
          source: 'sdk',
          input: { question: `${testNamespace}-question-${i}` },
          output: { answer: `${testNamespace}-answer-${i}` },
          startTime: now,
          endTime: now,
        });
        traceIds.push(id);
      }

      // The premise of every has-samples assertion downstream, established
      // where a failure names the seed. Polled because the write is
      // eventually consistent, and compared by SET rather than by length so a
      // project that somehow answered someone else's rows fails here.
      await expect
        .poll(
          async () => {
            const ids = await backendClient.listTraceIds({ projectId: project.id, size: 50 });
            return [...ids].sort();
          },
          {
            message: 'the annotator view card samples the project traces, so both must be readable',
            timeout: QUEUE_VISIBLE_TIMEOUT_MS,
            intervals: [500, 1_000, 2_000],
          },
        )
        .toEqual([...traceIds].sort());

      for (const [id, name, instructions] of [
        [queueId, queueName, queueInstructions],
        [bystanderQueueId, bystanderQueueName, bystanderInstructions],
      ] as const) {
        const created = await backendClient.createAnnotationQueue({
          id,
          projectId: project.id,
          name,
          scope: 'trace',
          instructions,
        });
        if (created.status !== 201) {
          throw new Error(
            `queueFormSeed fixture: creating queue ${name} answered ` +
              `${created.status}: ${created.message}`,
          );
        }
      }

      // Both queues readable, and holding the instructions they were given.
      // The read-back is the discriminating half: the edit assertion compares
      // a NEW value against this one, so a seed that stored nothing would make
      // that comparison pass for the wrong reason.
      for (const [id, name, instructions] of [
        [queueId, queueName, queueInstructions],
        [bystanderQueueId, bystanderQueueName, bystanderInstructions],
      ] as const) {
        await expect
          .poll(async () => (await backendClient.getAnnotationQueue(id))?.id ?? null, {
            message: `the queue ${name} must be readable before the sheet is opened`,
            timeout: QUEUE_VISIBLE_TIMEOUT_MS,
            intervals: [500, 1_000, 2_000],
          })
          .toBe(id);
        const form = await backendClient.getAnnotationQueueForm(id);
        expect(form.instructions, `${name} was seeded with its instructions`).toBe(instructions);
      }

      const ref: QueueFormSeedRef = {
        projectId: project.id,
        projectName: project.name,
        traceIds,
        queueId,
        queueName,
        queueInstructions,
        bystanderQueueId,
        bystanderQueueName,
        bystanderInstructions,
      };
      await testInfo.attach('opik.queueFormSeed', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        // Swallow-and-warn, never rethrow: a delete that failed here would
        // replace whichever seeding or assertion error actually broke the test.
        for (const [id, name] of [
          [queueId, queueName],
          [bystanderQueueId, bystanderQueueName],
        ] as const) {
          try {
            await backendClient.deleteAnnotationQueue(id);
          } catch (err) {
            console.warn(`[queueFormSeed fixture] queue delete warning for ${name}:`, err);
          }
        }
        if (traceIds.length > 0) {
          try {
            await backendClient.deleteTraces(traceIds);
          } catch (err) {
            console.warn('[queueFormSeed fixture] trace delete warning:', err);
          }
        }
      }
    }
  },
});

export { expect } from './studio-unlisted-model-runs.fixture';
