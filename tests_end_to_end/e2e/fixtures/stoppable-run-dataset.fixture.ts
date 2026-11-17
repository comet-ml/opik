import { expect } from '@playwright/test';
import { test as baseTest } from './playground-logs-traces.fixture';

export interface StoppableRunDatasetRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /**
   * How many items the dataset holds, PROVEN against the API before any run
   * starts — not the number requested.
   *
   * Load-bearing for the spec that consumes this: its claim is that a stopped
   * run produced strictly fewer items than it was given, and the comparison is
   * only meaningful against a count that is really there. An insert that
   * silently landed three items would make "fewer than the total" true for the
   * wrong reason.
   */
  itemCount: number;
}

export interface StoppableRunDatasetFixtures {
  stoppableRunDataset: StoppableRunDatasetRef;
}

/**
 * Items beyond the three the `dataset` fixture already seeds.
 *
 * Sized so a run over it takes long enough for a stop to land in the middle of
 * it. Measured against staging at 2.2.122: ~83 items drain in ~10s, and a
 * cancel issued straight after the `202` landed with ~20 of them written — so
 * 120 leaves roughly a 15s window either side of a cancel that costs under a
 * second. Larger would widen the margin further, but every item is a real
 * dispatch and the run is paid for in wall-clock on every execution of the
 * spec.
 */
const EXTRA_ITEM_COUNT = 117;

/**
 * A dataset big enough that a run over it can be STOPPED rather than merely
 * waited out.
 *
 * Built on the `dataset` fixture rather than creating a second dataset, so the
 * delete that fixture already owns tears this down too — datasets do not
 * cascade with their project, and a dataset this size left behind is the
 * expensive kind of leak.
 *
 * The items carry no `task_output`: nothing here is scored by the bridge's
 * `evaluate` route. The consumer drives `POST /v1/private/experiments/execute`,
 * which runs each item through a prompt template server-side, so `input` is the
 * only field the run reads.
 */
export const test = baseTest.extend<StoppableRunDatasetFixtures>({
  stoppableRunDataset: async (
    { dataset, project, sdkClient, backendClient },
    use,
    testInfo,
  ) => {
    const extra = Array.from({ length: EXTRA_ITEM_COUNT }, (_, i) => ({
      input: `stoppable run item ${i}`,
      expected_output: `stoppable run output ${i}`,
    }));

    const inserted = await sdkClient.python.insertDatasetItems({
      dataset_name: dataset.name,
      project_name: project.name,
      items: extra as unknown as Array<Record<string, unknown>>,
    });
    // Asserted, not trusted: `insertDatasetItems` deduplicates by content hash,
    // so a seed whose rows collided would insert fewer than it was handed and
    // the run would be shorter than the window the spec needs.
    expect(
      inserted.inserted,
      'the extra items were all inserted (a content-hash collision would drop some)',
    ).toBe(EXTRA_ITEM_COUNT);
    expect(
      inserted.value_error,
      'the bridge reported no validation error on the insert',
    ).toBeNull();

    const expectedTotal = dataset.items.length + EXTRA_ITEM_COUNT;

    // Read back through the API the run itself streams from, before the spec
    // asserts anything about how far a run got. A dataset that holds fewer
    // items than this says would make "the stop prevented work" pass over a
    // run that simply had little to do.
    const itemIds = await backendClient.listDatasetItemIds(dataset.id);
    expect(
      itemIds.length,
      `the dataset holds all ${expectedTotal} items the run will be given`,
    ).toBe(expectedTotal);

    const ref: StoppableRunDatasetRef = {
      id: dataset.id,
      name: dataset.name,
      projectId: project.id,
      projectName: project.name,
      itemCount: itemIds.length,
    };

    await testInfo.attach('opik.stoppable-run-dataset', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    // No teardown of its own: every item written here belongs to the `dataset`
    // fixture's dataset, which that fixture deletes after `use()`.
    await use(ref);
  },
});

export { expect } from './playground-logs-traces.fixture';
