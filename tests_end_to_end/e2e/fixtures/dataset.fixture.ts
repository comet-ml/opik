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
    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description,
      items: SEED_ITEMS as unknown as Array<Record<string, unknown>>,
    });
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
    // Datasets do NOT cascade with project deletion — they outlive their
    // parent project (the project_id becomes a dangling reference). Delete
    // explicitly before the project fixture tears down.
    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[dataset fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './trace.fixture';
