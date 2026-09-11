import { test as baseTest } from './summarised-datasets.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { AnnotationAutomationSeed } from '../core/backend';

export interface RoutingQueueRef {
  id: string;
  name: string;
}

export interface CreateRoutingQueueArgs {
  projectId: string;
  /** Distinguishes several queues inside one test; the fixture supplies the namespace. */
  suffix: string;
  automation: AnnotationAutomationSeed;
  scope?: 'trace' | 'thread';
}

export interface AnnotationQueueRoutingFixtures {
  createRoutingQueue: (args: CreateRoutingQueueArgs) => Promise<RoutingQueueRef>;
}

/**
 * A factory for annotation queues that carry an `automation` block, cleaned up
 * after the test whatever its outcome.
 *
 * A factory rather than a seed fixture because the automation IS the subject:
 * every spec in this area needs different conditions (a single threshold, an
 * AND pair, a thread scope), and a fixture that had to pick one up front could
 * only serve one of them. The register-callback shape is the same one
 * `registerPromptCleanup` uses, for the same reason — the id does not exist
 * until the test asks for the queue.
 *
 * Queues are deleted in reverse order of creation, so a failure part-way
 * through a multi-queue test still tears down what it built.
 *
 * The queue is named from `testNamespace`, so the name carries the
 * `cuj-<runId>-` prefix that `global-teardown.ts` already sweeps annotation
 * queues by. That sweep is the backstop for every queue this fixture cannot
 * delete itself: a hard-killed worker runs no teardown at all, and a create
 * whose response is lost after the POST has already committed throws before
 * `created` ever learns the id. Neither leaks past the end of the run.
 */
export const test = baseTest.extend<AnnotationQueueRoutingFixtures>({
  createRoutingQueue: async ({ backendClient, testNamespace }, use, testInfo) => {
    const created: RoutingQueueRef[] = [];

    await use(async ({ projectId, suffix, automation, scope }) => {
      const name = `${testNamespace}-${suffix}`;
      const id = await backendClient.createAnnotationQueueWithAutomation({
        projectId,
        name,
        automation,
        ...(scope ? { scope } : {}),
      });
      const ref: RoutingQueueRef = { id, name };
      created.push(ref);
      await testInfo.attach(`opik.routingQueue.${suffix}`, {
        body: JSON.stringify({ ...ref, scope: scope ?? 'trace', automation }, null, 2),
        contentType: 'application/json',
      });
      return ref;
    });

    if (!shouldLeaveArtifacts(testInfo)) {
      while (created.length) {
        const queue = created.pop()!;
        try {
          await backendClient.deleteAnnotationQueue(queue.id);
        } catch (err) {
          console.warn(`[createRoutingQueue] delete warning for ${queue.name}:`, err);
        }
      }
    }
  },
});

export { expect } from './summarised-datasets.fixture';
