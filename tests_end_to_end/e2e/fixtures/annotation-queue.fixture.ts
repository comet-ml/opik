import { test as baseTest } from './conversation.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AnnotationQueueTraceRef {
  id: string;
  name: string;
}

export interface AnnotationQueueRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  feedbackDefinitionName: string;
  /** Seeded in queue order — traces[2] is left unscored by the spec. */
  traces: AnnotationQueueTraceRef[];
}

export interface AnnotationQueueFixtures {
  annotationQueue: AnnotationQueueRef;

  /**
   * Teardown for queues a test creates itself, rather than through the
   * `annotationQueue` fixture above.
   *
   * Register as soon as the id exists; the registry drains after `use()`, so a
   * queue created moments before a failing assertion is still deleted. Queues
   * cascade with neither the project fixture nor a `test.afterEach` that an
   * earlier throw skips, so this is the only cleanup that holds on a red run.
   * Mirrors `registerDatasetCleanup` and `registerPromptCleanup`.
   */
  registerAnnotationQueueCleanup: (id: string, name: string) => void;
}

const TRACE_COUNT = 3;

export const test = baseTest.extend<AnnotationQueueFixtures>({
  annotationQueue: async (
    { sdkClient, backendClient, project, feedbackDefinition, testNamespace },
    use,
    testInfo,
  ) => {
    const traces: AnnotationQueueTraceRef[] = [];
    for (let i = 0; i < TRACE_COUNT; i += 1) {
      const name = `${testNamespace}-item-${i}`;
      const created = await sdkClient.python.createTrace({
        project_name: project.name,
        name,
        input: `seed input ${i}`,
        output: `seed output ${i}`,
      });
      traces.push({ id: created.id, name: created.name });
    }

    const queueName = `${testNamespace}-queue`;
    const created = await sdkClient.python.createAnnotationQueue({
      project_name: project.name,
      name: queueName,
      trace_ids: traces.map((t) => t.id),
      feedback_definition_names: [feedbackDefinition.name],
    });

    const ref: AnnotationQueueRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      projectName: project.name,
      feedbackDefinitionName: feedbackDefinition.name,
      traces,
    };
    await testInfo.attach('opik.annotationQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Annotation queues don't cascade with project deletion — explicit delete required. */
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAnnotationQueue(created.id);
      } catch (err) {
        console.warn(`[annotationQueue fixture] delete warning for ${queueName}:`, err);
      }
    }
  },

  registerAnnotationQueueCleanup: async ({ backendClient }, use, testInfo) => {
    const registry: Array<{ id: string; name: string }> = [];
    await use((id, name) => {
      registry.push({ id, name });
    });
    if (shouldLeaveArtifacts(testInfo)) return;
    for (const { id, name } of registry) {
      try {
        await backendClient.deleteAnnotationQueue(id);
      } catch (err) {
        // Best-effort: one undeletable queue must not mask the failure that
        // explains the run, nor orphan the queues registered after it.
        console.warn(`[registerAnnotationQueueCleanup] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './conversation.fixture';
