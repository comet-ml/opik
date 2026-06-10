import { test as baseTest } from './failure-artifacts.fixture';

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
});

export { expect } from './failure-artifacts.fixture';
