import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AlertCleanupFixtures {
  /**
   * Register an alert id for deletion at teardown.
   *
   * Call it the moment the id exists — the alert specs create their alert
   * through the form and only learn its id afterwards, from the project's
   * alerts list, so a seed fixture cannot know it upfront. Same shape as
   * `registerPromptCleanup` / `registerDatasetCleanup`.
   */
  registerAlertCleanup: (id: string, name: string) => void;
}

/**
 * Teardown for alerts a test created through the UI.
 *
 * Needed because the run-prefix sweep in `global-teardown.ts` matches alerts by
 * `name.startsWith(cujPrefix)`, and the alert-naming spec's alert is named by
 * the feature under test (`Trace errors > 7 in 5 mins +2 more`) — a namespaced
 * name would defeat the assertion. Alerts also do not cascade with their
 * project: `deleteProject` leaves them behind, project-scoped or not.
 *
 * Best-effort: a failed delete warns rather than throws, so cleanup cannot mask
 * the assertion failure that explains the run.
 */
export const test = baseTest.extend<AlertCleanupFixtures>({
  registerAlertCleanup: async ({ backendClient }, use, testInfo) => {
    const registry: Array<{ id: string; name: string }> = [];
    await use((id, name) => {
      registry.push({ id, name });
    });
    if (shouldLeaveArtifacts(testInfo)) {
      if (registry.length > 0) {
        console.warn(
          `[registerAlertCleanup] leaving ${registry.length} alert(s) for debugging`,
        );
      }
      return;
    }
    for (const { id, name } of registry) {
      try {
        await backendClient.deleteAlertsBatch([id]);
      } catch (err) {
        console.warn(`[registerAlertCleanup] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './dashboard-cleanup.fixture';
