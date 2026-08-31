import { test as baseTest } from './dashboard-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { BackendClient } from '../core/backend';

/** A seeded dataset (or test suite) and the item total its list row must show. */
export interface CountedDatasetRef {
  id: string;
  name: string;
  /** How many items were seeded — the number the "Item count" column must render. */
  itemCount: number;
}

export interface CountedDatasetsRef {
  projectId: string;
  projectName: string;
  datasets: CountedDatasetRef[];
}

export interface CountedDatasetsFixtures {
  countedDatasets: CountedDatasetsRef;
  countedTestSuites: CountedDatasetsRef;
}

/**
 * Item counts for the datasets, in creation order.
 *
 * Two properties matter and neither is incidental:
 *
 *  - **Collisions.** 5 and 13 each appear twice, so a row that borrowed another
 *    row's number cannot pass by coincidence — an off-by-one in the enrichment's
 *    id→count map would still have to land on the right pair.
 *  - **Zero-item rows in the middle.** A dataset that never received an item has
 *    no version at all, so its count is the only one the backend cannot take
 *    from `latest_version.items_total` and must answer from the legacy
 *    `dataset_items` scan. Those rows sit at positions 1 and 4, not at an edge,
 *    so a subset built from the wrong slice of the page changes which rows read
 *    0 rather than merely truncating.
 */
const DATASET_ITEM_COUNTS = [5, 0, 13, 1, 0, 5, 13];

/** Four suites, one of them empty — the shape the Test suites grid renders. */
const TEST_SUITE_ITEM_COUNTS = [2, 0, 9, 4];

function datasetItems(name: string, count: number): Array<Record<string, unknown>> {
  return Array.from({ length: count }, (_, index) => ({
    input: `${name} question ${index}`,
    expected_output: `${name} answer ${index}`,
  }));
}

/**
 * Seeds `counts.length` datasets of the given type and proves the seed landed.
 *
 * The proving is the point: every assertion downstream compares a rendered or
 * returned number against `itemCount`, so a seed that silently inserted the
 * wrong number of items would produce a spec that agrees with itself and can
 * never fail. Each dataset's stored items are counted back before the browser
 * is opened.
 */
async function seedCountedDatasets(args: {
  backendClient: BackendClient;
  namePrefix: string;
  counts: number[];
  createOne: (name: string, items: Array<Record<string, unknown>>) => Promise<{ id: string }>;
}): Promise<CountedDatasetRef[]> {
  const seeded: CountedDatasetRef[] = [];

  for (const [index, itemCount] of args.counts.entries()) {
    const name = `${args.namePrefix}-${index}`;
    const created = await args.createOne(name, datasetItems(name, itemCount));
    seeded.push({ id: created.id, name, itemCount });
  }

  for (const dataset of seeded) {
    const storedIds = await args.backendClient.listDatasetItemIds(dataset.id);
    if (storedIds.length !== dataset.itemCount) {
      throw new Error(
        `[countedDatasets fixture] "${dataset.name}" was seeded with ${dataset.itemCount} ` +
          `items but stores ${storedIds.length} — the item-count assertions would be vacuous.`,
      );
    }
  }

  return seeded;
}

async function deleteSeeded(
  backendClient: BackendClient,
  seeded: CountedDatasetRef[],
  label: string,
): Promise<void> {
  for (const dataset of seeded) {
    try {
      await backendClient.deleteDataset(dataset.id);
    } catch (err) {
      // Keep going: one undeletable row must not orphan the rest.
      console.warn(`[${label} fixture] delete warning for ${dataset.name}:`, err);
    }
  }
}

/**
 * Datasets and test suites seeded with known, deliberately colliding item
 * counts, for the "Item count" column on the two list grids.
 *
 * Both are scoped to the run's own `project`, which is what makes the list
 * assertions safe to state in absolutes: the project-scoped list endpoint the
 * grids call returns these rows and nothing else, so a spec can assert the
 * page's `total` and row count rather than merely finding its own rows in a
 * shared workspace.
 */
export const test = baseTest.extend<CountedDatasetsFixtures>({
  countedDatasets: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasets = await seedCountedDatasets({
      backendClient,
      namePrefix: `${testNamespace}-count-ds`,
      counts: DATASET_ITEM_COUNTS,
      createOne: (name, items) =>
        sdkClient.python.createDataset({
          project_name: project.name,
          name,
          description: 'item-count column seed',
          ...(items.length ? { items } : {}),
        }),
    });

    const ref: CountedDatasetsRef = {
      projectId: project.id,
      projectName: project.name,
      datasets,
    };
    await testInfo.attach('opik.countedDatasets', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Datasets don't cascade with project deletion — explicit delete required. */
    if (!shouldLeaveArtifacts(testInfo)) {
      await deleteSeeded(backendClient, datasets, 'countedDatasets');
    }
  },

  countedTestSuites: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasets = await seedCountedDatasets({
      backendClient,
      namePrefix: `${testNamespace}-count-suite`,
      counts: TEST_SUITE_ITEM_COUNTS,
      createOne: (name, items) =>
        sdkClient.python.createTestSuite({
          project_name: project.name,
          name,
          description: 'item-count column seed',
          ...(items.length
            ? { items: items.map((item) => ({ data: item })) }
            : {}),
        }),
    });

    const ref: CountedDatasetsRef = {
      projectId: project.id,
      projectName: project.name,
      datasets,
    };
    await testInfo.attach('opik.countedTestSuites', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    /** Suites share the datasets table and don't cascade with the project either. */
    if (!shouldLeaveArtifacts(testInfo)) {
      await deleteSeeded(backendClient, datasets, 'countedTestSuites');
    }
  },
});

export { expect } from './dashboard-cleanup.fixture';
