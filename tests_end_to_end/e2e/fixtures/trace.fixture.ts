import { test as baseTest } from './failure-artifacts.fixture';

export interface TraceRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
}

export interface TraceFixtures {
  opikTrace: TraceRef;
}

export const test = baseTest.extend<TraceFixtures>({
  opikTrace: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-trace`;
    const created = await sdkClient.python.createTrace({
      project_name: project.name,
      name,
      input: 'seed input',
      output: 'seed output',
    });
    await testInfo.attach('opik.trace', {
      body: JSON.stringify(
        {
          id: created.id,
          name: created.name,
          projectId: created.project_id,
          projectName: project.name,
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });
    await use({
      id: created.id,
      name: created.name,
      projectId: created.project_id,
      projectName: project.name,
    });
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './failure-artifacts.fixture';
