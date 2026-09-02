import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AlertsCleanupFixtures {
  /**
   * Deletes the alerts a test created under its fixture project.
   *
   * Requesting this fixture is the whole API — there is nothing to call. Alerts
   * are discovered from the project at teardown rather than registered by the
   * test, which is what an id-registration API could not do here: an alert
   * created through the create form is *named by the product* (the name is
   * suggested from the triggers), so the test knows neither its id nor its name
   * until the row appears — and an alert created moments before a failure would
   * be exactly the one a registration API missed.
   */
  alertsCleanup: void;
}

/**
 * Teardown for alerts created against the `project` fixture.
 *
 * Needed for two independent reasons:
 *
 *   1. `global-teardown`'s run-prefix sweep only recognises names starting with
 *      the run's `cuj-` prefix. An alert the create form named for the user
 *      ("Cost > 7 in 1 hour") carries no prefix at all, so the sweep cannot see
 *      it however the run ends.
 *   2. Even a prefixed alert outlives its project: nothing in the alerts specs
 *      may assume a project delete cascades to them.
 *
 * The fixture depends on `project`, so Playwright tears it down *before* the
 * project fixture — the alerts are still addressable when this runs.
 *
 * Best-effort throughout: a cleanup failure warns rather than throws, so it
 * cannot mask the assertion failure that explains the run.
 */
export const test = baseTest.extend<AlertsCleanupFixtures>({
  alertsCleanup: [
    async ({ project, backendClient }, use, testInfo) => {
      await use();

      if (shouldLeaveArtifacts(testInfo)) {
        console.warn(
          `[alertsCleanup] leaving alerts under ${project.name} for debugging`,
        );
        return;
      }

      let alerts;
      try {
        alerts = await backendClient.listAlertsForProject(project.id);
      } catch (err) {
        console.warn('[alertsCleanup] could not list alerts:', err);
        return;
      }

      for (const alert of alerts) {
        try {
          await backendClient.deleteAlert(alert.id);
        } catch (err) {
          // Keep going: one undeletable alert must not orphan the rest.
          console.warn(`[alertsCleanup] could not delete ${alert.name}:`, err);
        }
      }
    },
    // `auto: false` — a spec opts in by naming the fixture, which keeps the
    // teardown's cost off every other test in the suite.
    { auto: false },
  ],
});

export { expect } from './dashboard-cleanup.fixture';
