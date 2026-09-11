import { test as baseTest } from './json-output-experiment.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** The `data` key the filter scopes on. */
export const GROUP_COLUMN = 'group';

/** The group value the filter selects. Three of the six items carry it. */
export const TARGET_GROUP = 'beta';

export interface GroupedDatasetRef {
  id: string;
  name: string;
  projectId: string;
  /** Ids of the three items whose `data.group` is `beta` — the filter's scope. */
  targetItemIds: string[];
  /** Ids of the three items outside the filter, which must survive untouched. */
  bystanderItemIds: string[];
  /** Every seeded item id. */
  allItemIds: string[];
}

export interface GroupedDatasetFixtures {
  groupedDataset: GroupedDatasetRef;
}

/**
 * Six items across four groups, three of them `beta`.
 *
 * The decoys matter as much as the targets: with a single non-matching group a
 * filter that over-matched by one row would still leave a plausible-looking
 * result. Three distinct bystander groups, interleaved with the targets rather
 * than appended after them, mean an over- or under-matching filter changes the
 * surviving set in a way the assertions below name exactly.
 */
const SEED_ROWS: Array<{ label: string; group: string }> = [
  { label: 'keep-alpha', group: 'alpha' },
  { label: 'target-one', group: TARGET_GROUP },
  { label: 'keep-gamma', group: 'gamma' },
  { label: 'target-two', group: TARGET_GROUP },
  { label: 'keep-delta', group: 'delta' },
  { label: 'target-three', group: TARGET_GROUP },
];

/**
 * A dataset whose items are split into a filter-matching set and a set of
 * bystanders, for the filter-scoped delete and batch-update paths that
 * OPIK-7923 routed through `FiltersFactory`.
 *
 * Seeded through the SDK bridge; the dataset is deleted here rather than in the
 * test, so a mid-test failure cannot leak it. The items go with the dataset.
 */
export const test = baseTest.extend<GroupedDatasetFixtures>({
  groupedDataset: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-grouped-ds`;

    const created = await sdkClient.python.createDataset({
      project_name: project.name,
      name,
      description: 'filter-scoped dataset item mutations',
      items: SEED_ROWS.map((row) => ({
        label: row.label,
        [GROUP_COLUMN]: row.group,
        input: `question for ${row.label}`,
        expected_output: `answer for ${row.label}`,
      })) as unknown as Array<Record<string, unknown>>,
    });

    const storedItems = await backendClient.getDatasetItems(created.id);
    if (storedItems.length !== SEED_ROWS.length) {
      throw new Error(
        `[groupedDataset fixture] expected ${SEED_ROWS.length} items, got ${storedItems.length}`,
      );
    }

    const idsForGroup = (predicate: (group: string) => boolean): string[] =>
      storedItems
        .filter((item) => {
          const group = item.data[GROUP_COLUMN];
          if (typeof group !== 'string') {
            throw new Error(
              `[groupedDataset fixture] item ${item.id} has no string "${GROUP_COLUMN}"`,
            );
          }
          return predicate(group);
        })
        .map((item) => item.id);

    const targetItemIds = idsForGroup((group) => group === TARGET_GROUP);
    const bystanderItemIds = idsForGroup((group) => group !== TARGET_GROUP);
    const expectedTargets = SEED_ROWS.filter((row) => row.group === TARGET_GROUP).length;
    if (targetItemIds.length !== expectedTargets) {
      throw new Error(
        `[groupedDataset fixture] expected ${expectedTargets} "${TARGET_GROUP}" items, got ${targetItemIds.length}`,
      );
    }

    const ref: GroupedDatasetRef = {
      id: created.id,
      name: created.name,
      projectId: project.id,
      targetItemIds,
      bystanderItemIds,
      allItemIds: storedItems.map((item) => item.id),
    };

    await testInfo.attach('opik.groupedDataset', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteDataset(created.id);
      } catch (err) {
        console.warn(`[groupedDataset fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './json-output-experiment.fixture';
