import { test as baseTest } from './trace.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface DatasetItemSeed {
  input: string;
  expected_output: string;
}

export interface DatasetRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  description: string | null;
  items: DatasetItemSeed[];
}

export interface DatasetFixtures {
  dataset: DatasetRef;
}

const SEED_ITEMS: DatasetItemSeed[] = [
  { input: 'seed input 1', expected_output: 'seed output 1' },
  { input: 'seed input 2', expected_output: 'seed output 2' },
  { input: 'seed input 3', expected_output: 'seed output 3' },
];

export const test = baseTest.extend<DatasetFixtures>({
  dataset: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-ds`;
    const description = `seeded by ${testInfo.title}`;
    // Registered the moment the dataset exists: a failure in the steps below
    // must still tear it down, so it cannot wait for the ref.
    let datasetId: string | null = null;
    try {
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name,
        description,
        items: SEED_ITEMS as unknown as Array<Record<string, unknown>>,
      });
      datasetId = created.id;
      const ref: DatasetRef = {
        id: created.id,
        name: created.name,
        projectId: project.id,
        projectName: project.name,
        description,
        items: SEED_ITEMS,
      };
      await testInfo.attach('opik.dataset', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });
      await use(ref);
    } finally {
      // Cleanup is governed by shouldLeaveArtifacts alone, so OPIK_LEAVE_FAILURES
      // keeps one meaning across the suite. The id is captured on create, so a
      // seed that fails partway still deletes what it made; anything a
      // leave-failures run keeps is still caught by global-teardown's run-prefix
      // sweep, which covers datasets.
      if (datasetId !== null && !shouldLeaveArtifacts(testInfo)) {
        /** Datasets don't cascade with project deletion — explicit delete required. */
        try {
          await backendClient.deleteDataset(datasetId);
        } catch (err) {
          console.warn(`[dataset fixture] delete warning for ${name}:`, err);
        }
      }
    }
  },
});

export { expect } from './trace.fixture';
