import { test as baseTest } from './settled-compare-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { ProjectRef } from '../core/backend';

export interface SecondProjectFixtures {
  /**
   * A second project, independent of `project`.
   *
   * For the scoping questions one project cannot pose: whether a name is unique
   * per project or per workspace, whether a read narrowed to one project leaks
   * the other's rows. Deliberately plain — same shape and lifecycle as
   * `project`, just a second one — because what the specs needing it have in
   * common is only that there are TWO, not anything about what is in them.
   *
   * Not a bystander: `bystanderExperiment` and friends exist so a destructive
   * test can prove the blast radius was one entity. This is the other half of a
   * pair the test touches on purpose.
   */
  secondProject: ProjectRef;
}

export const test = baseTest.extend<SecondProjectFixtures>({
  secondProject: async ({ sdkClient, backendClient, testNamespace }, use, testInfo) => {
    // `-proj-b` against `project`'s `-proj`, so the two sort together under the
    // run prefix and a leak is obvious in the teardown log. Both are swept by
    // prefix as a backstop; this deletes its own at teardown.
    const name = `${testNamespace}-proj-b`;
    const created = await sdkClient.python.createProject({ name });
    await testInfo.attach('opik.second-project', {
      body: JSON.stringify({ id: created.id, name: created.name }, null, 2),
      contentType: 'application/json',
    });
    await use({ id: created.id, name: created.name });
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteProject(created.id);
      } catch (err) {
        console.warn(`[secondProject fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './settled-compare-experiments.fixture';
