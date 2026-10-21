import { test as baseTest } from './failure-artifacts.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface TraceRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  input: string;
  output: string;
}

export interface TraceFixtures {
  opikTrace: TraceRef;
  /**
   * Register a project whose traces are to be deleted at teardown.
   *
   * A register-callback fixture rather than a seed one, because the ids it has
   * to clean up do not exist upfront: a spec that writes traces through
   * `@opik.track` never sees their ids — the decorator mints them inside the
   * SDK — so there is nothing to hand a seed fixture. The project IS knowable,
   * and sweeping it is enough.
   *
   * It is needed at all because `deleteProject` does not cascade to traces (the
   * same reason `pagedSpans` deletes its own), and `global-teardown`'s
   * run-prefix sweep only knows about experiments, datasets and projects. A
   * spec that seeds through the SDK and relies on the project fixture alone
   * leaves its traces behind for good.
   *
   * Scoped to a project the caller created: it deletes EVERY trace it finds
   * there, which is only safe for a fresh per-test project — never pass one
   * that predates the test.
   */
  registerProjectTracesCleanup: (projectId: string) => void;
}

const SEED_INPUT = 'seed input';
const SEED_OUTPUT = 'seed output';

export const test = baseTest.extend<TraceFixtures>({
  opikTrace: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-trace`;
    const created = await sdkClient.python.createTrace({
      project_name: project.name,
      name,
      input: SEED_INPUT,
      output: SEED_OUTPUT,
    });
    const ref: TraceRef = {
      id: created.id,
      name: created.name,
      projectId: created.project_id,
      projectName: project.name,
      input: SEED_INPUT,
      output: SEED_OUTPUT,
    };
    await testInfo.attach('opik.trace', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });
    await use(ref);
    // No explicit teardown — the project fixture's deleteProject cascades.
  },

  registerProjectTracesCleanup: async ({ backendClient }, use, testInfo) => {
    const projectIds: string[] = [];
    await use((projectId) => {
      projectIds.push(projectId);
    });
    if (shouldLeaveArtifacts(testInfo)) return;
    for (const projectId of projectIds) {
      try {
        // Listed at teardown rather than remembered as the test went, so a
        // test that failed part-way still has everything it managed to write
        // swept. A generous size: this is a per-test project, and leaving rows
        // behind because the first page was full is the failure mode here.
        const ids = await backendClient.listTraceIds({ projectId, size: 1_000 });
        if (ids.length > 0) await backendClient.deleteTraces(ids);
      } catch (err) {
        // Never rethrow from teardown: a cleanup failure must not replace the
        // test's own error.
        console.warn(
          `[registerProjectTracesCleanup] trace sweep warning for project ${projectId}:`,
          err,
        );
      }
    }
  },
});

export { expect } from './failure-artifacts.fixture';
