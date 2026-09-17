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
    // The id is registered the moment the dataset exists: a failure in the
    // steps below must still tear it down, so it cannot wait for the ref.
    let datasetId: string | null = null;
    let ref: DatasetRef | null = null;
    try {
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name,
        description,
        items: SEED_ITEMS as unknown as Array<Record<string, unknown>>,
      });
      datasetId = created.id;
      ref = {
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
      // A fully built fixture follows shouldLeaveArtifacts (keep failed-test
      // resources for debugging); a partially built one is garbage that
      // poisons later runs' empty-state assertions and is always removed.
      if (datasetId !== null && (ref === null || !shouldLeaveArtifacts(testInfo))) {
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
