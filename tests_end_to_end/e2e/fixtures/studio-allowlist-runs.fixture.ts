import { test as baseTest } from './paged-optimization-runs.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface StudioAllowlistRunsFixtures {
  /**
   * Delete an optimization run the test caused to exist, rather than seeded.
   *
   * A register-callback fixture because the ids here only appear mid-test: the
   * allowlist spec replays a captured Studio payload through
   * `POST /v1/private/optimizations/` and the run exists from that moment, so a
   * seed fixture could not know about it and a trailing cleanup step in the
   * test body would be skipped the instant an earlier assertion threw —
   * exactly when the workspace has been left dirtiest.
   *
   * Registering is deliberately separate from the write: the caller registers
   * the id it is ABOUT to use, so a create that 201s and then fails an
   * assertion is still cleaned up. Mirrors `registerPromptCleanup` and
   * `registerDatasetCleanup`.
   *
   * The run-prefix sweep in `global-teardown.ts` does cover optimizations, so
   * this is not the only net — but that sweep runs once at the end of the whole
   * suite, which leaves a prefix-named run visible to every spec that lists the
   * project in between. The ones this registers are gone before the next test
   * starts.
   */
  registerOptimizationCleanup: (id: string, label: string) => void;
}

export const test = baseTest.extend<StudioAllowlistRunsFixtures>({
  registerOptimizationCleanup: async ({ backendClient }, use, testInfo) => {
    const registry: Array<{ id: string; label: string }> = [];
    await use((id, label) => {
      registry.push({ id, label });
    });
    if (!shouldLeaveArtifacts(testInfo)) {
      for (const { id, label } of registry) {
        try {
          await backendClient.deleteOptimization(id);
        } catch (err) {
          console.warn(`[registerOptimizationCleanup] delete warning for ${label} (${id}):`, err);
        }
      }
    }
  },
});

export { expect } from './paged-optimization-runs.fixture';
