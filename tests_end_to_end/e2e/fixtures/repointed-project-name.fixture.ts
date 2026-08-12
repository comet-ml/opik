import { test as baseTest } from './scoring-error-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface RepointedProjectNameRef {
  /** A project name unique to this test. */
  name: string;
  /** Id of the project currently holding `name`. Changes after `repoint()`. */
  currentId: string;
  /** Every id this name has ever pointed at, oldest first. */
  history: string[];
  /**
   * Delete the project currently holding `name` and create a brand-new one
   * under the same name. Returns the new id.
   */
  repoint(): Promise<string>;
}

export interface RepointedProjectNameFixtures {
  repointedProjectName: RepointedProjectNameRef;
}

/**
 * A project name that can be re-pointed at a new project id mid-test.
 *
 * This is an action fixture rather than a pre-seeded one because the re-pointing
 * IS the thing under test — the name has to resolve to one id before it resolves
 * to another, with the browser session watching. The fixture still owns the
 * lifecycle: it tracks every id the name has held and sweeps all of them, so a
 * test that fails halfway through leaves nothing behind.
 */
export const test = baseTest.extend<RepointedProjectNameFixtures>({
  repointedProjectName: async ({ sdkClient, backendClient, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-repointed`;
    const history: string[] = [];

    const created = await sdkClient.python.createProject({ name });
    history.push(created.id);

    const ref: RepointedProjectNameRef = {
      name,
      currentId: created.id,
      history,
      async repoint() {
        await backendClient.deleteProject(ref.currentId);
        const recreated = await sdkClient.python.createProject({ name });
        history.push(recreated.id);
        ref.currentId = recreated.id;
        return recreated.id;
      },
    };

    await testInfo.attach('opik.repointed-project-name', {
      body: JSON.stringify({ name, history }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      // Sweep every id, not just the current one: an assertion that fails
      // between the delete and the re-create would otherwise orphan the rest.
      // deleteProject tolerates a 404, so already-deleted ids are free.
      for (const id of history) {
        try {
          await backendClient.deleteProject(id);
        } catch (err) {
          console.warn(`[repointed-project-name fixture] delete warning for ${id}:`, err);
        }
      }
    }
  },
});

export { expect } from './scoring-error-experiment.fixture';
