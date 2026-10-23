import { test as baseTest } from './internal-trace-threads.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface SmeInternalThreadQueueRef {
  queueId: string;
  queueName: string;
  projectId: string;
  projectName: string;
  /** The feedback definition the SME view offers as a scoring option. */
  feedbackDefinitionName: string;
  /** `thread_id` of the one thread in the queue — `internalTraceThreads.main`. */
  threadId: string;
}

export interface SmeInternalThreadQueueFixtures {
  smeInternalThreadQueue: SmeInternalThreadQueueRef;
}

/**
 * A `scope: 'thread'` annotation queue holding exactly the one mixed thread
 * from `internalTraceThreads`, so the SME annotation view (`/$workspace/sme`)
 * opens straight onto it.
 *
 * One item, deliberately: the SME flow lands on the first unprocessed item, and
 * with a second thread in the queue which one that is would depend on queue
 * ordering rather than on the fixture.
 *
 * Created over REST rather than through `sdkClient.python.createAnnotationQueue`
 * — the bridge's route adds TRACES to a trace-scope queue and cannot express
 * either `scope: 'thread'` or an add by `thread_model_id`. Queues cascade with
 * neither the project nor a `test.afterEach`, so teardown is here.
 */
export const test = baseTest.extend<SmeInternalThreadQueueFixtures>({
  smeInternalThreadQueue: async (
    { backendClient, internalTraceThreads, feedbackDefinition, testNamespace },
    use,
    testInfo,
  ) => {
    const queueId = uuid7();
    const queueName = `${testNamespace}-sme-thread-queue`;

    const created = await backendClient.createAnnotationQueue({
      id: queueId,
      projectId: internalTraceThreads.projectId,
      name: queueName,
      scope: 'thread',
      feedbackDefinitionNames: [feedbackDefinition.name],
    });
    if (created.status !== 201) {
      throw new Error(
        `smeInternalThreadQueue fixture: creating queue ${queueName} answered ` +
          `${created.status}: ${created.message}`,
      );
    }

    // By `thread_model_id`, not `thread_id`: the endpoint accepts the wrong one
    // with a 204 and adds nothing, which would leave the SME view on its
    // no-items state and read as a product bug.
    await backendClient.addAnnotationQueueItems(queueId, [
      internalTraceThreads.main.threadModelId,
    ]);

    const detail = await backendClient.getAnnotationQueue(queueId);
    if (detail?.itemsCount !== 1) {
      throw new Error(
        `smeInternalThreadQueue fixture: queue ${queueName} reports ` +
          `itemsCount ${detail?.itemsCount ?? 'null'}, expected 1 — the SME view would open ` +
          'on an empty queue.',
      );
    }

    const ref: SmeInternalThreadQueueRef = {
      queueId,
      queueName,
      projectId: internalTraceThreads.projectId,
      projectName: internalTraceThreads.projectName,
      feedbackDefinitionName: feedbackDefinition.name,
      threadId: internalTraceThreads.main.threadId,
    };

    await testInfo.attach('opik.smeInternalThreadQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAnnotationQueue(queueId);
      } catch (err) {
        console.warn(`[smeInternalThreadQueue fixture] delete warning for ${queueName}:`, err);
      }
    }
  },
});

export { expect } from './internal-trace-threads.fixture';
