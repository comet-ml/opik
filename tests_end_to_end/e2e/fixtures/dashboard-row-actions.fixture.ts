import { expect } from '@playwright/test';
import { test as baseTest } from './openrouter-cache-rate-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { DashboardRef } from '../core/backend';

export interface DashboardRowActionsRef {
  /**
   * The name prefix both dashboards share, and which the list is filtered to.
   *
   * Inside the run prefix, so `global-teardown`'s sweep is a backstop for
   * whatever this fixture fails to delete.
   */
  namePrefix: string;
  /** The dashboard the spec deletes through its row-actions menu. */
  target: DashboardRef;
  /**
   * A second dashboard the spec never touches.
   *
   * The bystander. Asserting only that the target row disappeared would read
   * identically if the delete had taken every dashboard in the workspace with
   * it — `useDashboardBatchDeleteMutation` posts an `ids` array, so "the wrong
   * ids were sent" is a live failure mode and an empty list looks like success.
   */
  control: DashboardRef;
}

export interface DashboardRowActionsFixtures {
  dashboardRowActions: DashboardRowActionsRef;
}

/**
 * Two workspace dashboards sharing a name prefix: one to delete, one to survive.
 *
 * Seeded through `POST /v1/private/dashboards` rather than the create dialog —
 * what the spec is about is the row-actions menu on the LIST, not how a
 * dashboard comes into being, and a UI seed would make a create-dialog
 * regression present as a delete failure.
 *
 * Both are torn down here rather than in the test, so the delete runs on pass,
 * fail and timeout alike. The target is deleted by the spec mid-test, so its
 * teardown is expected to find nothing: `backendClient.deleteDashboard`
 * swallows a 404, which is what makes a plain seed fixture the right shape here
 * instead of a register-callback one — both ids exist before the test starts.
 *
 * A dashboard hangs off the workspace, not off a project, so no project delete
 * cascades to either of these.
 */
export const test = baseTest.extend<DashboardRowActionsFixtures>({
  dashboardRowActions: async ({ backendClient, testNamespace }, use, testInfo) => {
    const namePrefix = `${testNamespace}-rowdel`;
    const created: DashboardRef[] = [];

    try {
      // Pushed as each one lands rather than collected into a single
      // assignment: if the second create throws, the first still has to be
      // deletable, and an `await Promise.all` would lose it.
      for (const suffix of ['target', 'control'] as const) {
        created.push(
          await backendClient.createDashboard({
            name: `${namePrefix}-${suffix}`,
            description: `row-actions delete ${suffix} for ${testNamespace}`,
          }),
        );
      }

      const [target, control] = created;

      // The premise the spec's "exactly one row went" verdict rests on: two
      // dashboards really exist under this prefix, and they are distinct. A
      // seed that silently produced one would leave the spec asserting that one
      // row disappeared from a one-row list, which is satisfied by a delete
      // that took everything.
      const listed = (await backendClient.listDashboardsWithPrefix(namePrefix)).map((d) => d.id);
      expect(
        listed.slice().sort(),
        `the workspace must hold exactly the two seeded dashboards under '${namePrefix}' before the spec runs`,
      ).toEqual([target.id, control.id].sort());
      expect(target.id, 'the target and the bystander must be different dashboards').not.toBe(
        control.id,
      );

      const ref: DashboardRowActionsRef = { namePrefix, target, control };
      await testInfo.attach('opik.dashboardRowActions', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        for (const dashboard of created) {
          try {
            // Swallow-and-warn: the target is already gone on a passing run,
            // and one undeletable dashboard must not orphan the other or
            // replace the assertion error that explains the run.
            await backendClient.deleteDashboard(dashboard.id);
          } catch (err) {
            console.warn(
              `[dashboardRowActions fixture] delete warning for ${dashboard.name}:`,
              err,
            );
          }
        }
      }
    }
  },
});

export { expect } from './openrouter-cache-rate-spans.fixture';
