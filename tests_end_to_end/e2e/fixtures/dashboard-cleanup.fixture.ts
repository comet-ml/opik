import { test as baseTest } from './trace-attachments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface DashboardCleanupFixtures {
  /**
   * Registers a dashboard for deletion at teardown.
   *
   * A register-callback rather than a seed fixture because a dashboard built
   * through the UI has no id until the create dialog closes and the router
   * lands on it — nothing a fixture could know upfront. The test calls this the
   * moment the id exists, and the registry is drained after the test whatever
   * its outcome.
   */
  registerDashboardCleanup: (id: string) => void;
}

/**
 * Teardown for dashboards a test creates.
 *
 * Needed because nothing else removes them: a dashboard belongs to the
 * workspace rather than to a project, so no project delete cascades to it, and
 * `global-teardown`'s run-prefix sweep covers experiments, datasets and
 * projects only. Left alone they accumulate on every shared environment the
 * suite runs against.
 *
 * Best-effort: a failed delete warns rather than throws, so cleanup cannot mask
 * the assertion failure that explains the run.
 */
export const test = baseTest.extend<DashboardCleanupFixtures>({
  registerDashboardCleanup: async ({ backendClient }, use, testInfo) => {
    const ids: string[] = [];
    await use((id: string) => {
      ids.push(id);
    });

    if (shouldLeaveArtifacts(testInfo)) {
      if (ids.length > 0) {
        console.warn(`[registerDashboardCleanup] leaving ${ids.join(', ')} for debugging`);
      }
      return;
    }

    for (const id of ids) {
      try {
        await backendClient.deleteDashboard(id);
      } catch (err) {
        // Keep going: one undeletable dashboard must not orphan the rest.
        console.warn(`[registerDashboardCleanup] delete warning for ${id}:`, err);
      }
    }
  },
});

export { expect } from './trace-attachments.fixture';
