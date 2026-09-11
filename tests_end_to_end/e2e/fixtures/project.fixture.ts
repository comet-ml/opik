import { test as baseTest } from './base.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { ProjectRef } from '../core/backend';

export interface ProjectFixtures {
  project: ProjectRef;
}

export const test = baseTest.extend<ProjectFixtures>({
  project: async ({ sdkClient, backendClient, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-proj`;
    const created = await sdkClient.python.createProject({ name });
    await testInfo.attach('opik.project', {
      body: JSON.stringify({ id: created.id, name: created.name }, null, 2),
      contentType: 'application/json',
    });
    await use({ id: created.id, name: created.name });
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteProject(created.id);
      } catch (err) {
        console.warn(`[project fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './base.fixture';
