import { test as baseTest } from './optimization-rollup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AnnotationQueueSweep {
  /**
   * Name every queue the test creates with this prefix. Teardown deletes exactly
   * the queues whose names start with it, so naming and cleanup cannot drift.
   */
  prefix: string;
  /** `${prefix}-${suffix}` — the name to type into the create dialog. */
  name(suffix: string): string;
}

export interface AnnotationQueueCleanupFixtures {
  annotationQueueSweep: AnnotationQueueSweep;
}

/**
 * Teardown for annotation queues a test creates through the UI.
 *
 * A sweep by name prefix rather than a register-callback registry, because the
 * create dialog never hands the test an id — and because the thing under test
 * includes "a double-submit must not create a second queue". A registry can only
 * clean up the queues the test knew about, which is precisely the wrong set when
 * the assertion that fails is "an extra queue exists".
 *
 * Annotation queues cascade with neither the `project` fixture (which deletes
 * only the project row) nor the run-prefix sweep in `global-teardown.ts`, so
 * without this a mid-test failure orphans them permanently.
 */
export const test = baseTest.extend<AnnotationQueueCleanupFixtures>({
  annotationQueueSweep: async ({ backendClient, testNamespace }, use, testInfo) => {
    // Deliberately not `${testNamespace}-queue`: that is the name the
    // `annotationQueue` fixture gives its SDK-seeded queue, and a sweep prefix
    // that also matched it would delete a queue this fixture does not own.
    const prefix = `${testNamespace}-uiq`;

    await use({ prefix, name: (suffix: string) => `${prefix}-${suffix}` });

    if (shouldLeaveArtifacts(testInfo)) return;

    try {
      const queues = await backendClient.listAnnotationQueuesWithPrefix(prefix);
      await backendClient.deleteAnnotationQueuesBatch(queues.map((q) => q.id));
    } catch (err) {
      console.warn(`[annotationQueueSweep] cleanup warning for ${prefix}:`, err);
    }
  },
});

export { expect } from './optimization-rollup.fixture';
