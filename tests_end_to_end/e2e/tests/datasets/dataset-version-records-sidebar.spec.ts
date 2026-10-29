import {
  test,
  expect,
  ANSWER_FIELD,
  BYSTANDER_MARKER,
  V1_MARKER,
  V2_EDITED_MARKER,
  V2_NEW_MARKER,
} from '@e2e/fixtures';
import { DatasetItemsPage } from '@e2e/pom/dataset-items.page';

/**
 * Opening an older version from the Version history tab shows a read-only
 * sheet of THAT version's records. The failure the sheet can have is silent: a
 * table wired to the dataset's latest items renders a completely healthy
 * -looking page with the wrong rows in it, and nothing on screen says so.
 *
 * Nothing in the estate catches that today. `dataset-version-counters` and
 * `dataset-list-version-count` assert the version row's own cells — Item count
 * and the Changes tags — which a sheet serving the latest items would leave
 * untouched. So would every other datasets spec: they all read the Records tab,
 * which is *supposed* to show the latest.
 *
 * What makes this assertable is a seed whose two versions differ in their
 * CONTENT and not only in their size (`versionedDataset`): v1's three rows all
 * carry `V1ORIGINAL`, and v2 rewrites two of them to `V2EDITED` and adds two
 * `V2NEW` ones. A row count alone would be satisfied by a read that scoped the
 * page size but not the version; asserting that no `V2EDITED`/`V2NEW` string
 * appears anywhere in the sheet would not.
 */
test.describe('Dataset version records sidebar', { tag: ['@area:datasets'] }, () => {
  test(
    "Opening an older version serves that version's records, not the dataset's latest",
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ versionedDataset, backendClient, page }) => {
      const { older, latest, olderItems, latestItems } = versionedDataset;

      await test.step('The two versions really do hold different rows', async () => {
        // Before the browser opens. A UI assertion over a seed that silently
        // failed to produce two differing versions is a test that cannot fail,
        // and would read as coverage forever.
        const atOlder = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedDataset.id,
          versionHash: older.hash,
        });
        const atLatest = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedDataset.id,
        });

        // The count and the collection, not just "my rows are in there": a
        // version read that also returned rows it should have excluded is the
        // whole failure, and a `find()` over the response would pass anyway.
        expect(atOlder.total, 'the older version holds 3 items').toBe(olderItems.length);
        expect(atOlder.items).toHaveLength(olderItems.length);
        expect(atOlder.items.map((item) => item.id).sort()).toEqual(
          olderItems.map((seed) => seed.id).sort(),
        );
        expect(
          atOlder.items.map((item) => item.data[ANSWER_FIELD]).sort(),
          'and every one of them carries the v1 marker',
        ).toEqual(olderItems.map((seed) => seed.data[ANSWER_FIELD]).sort());

        expect(atLatest.total, 'while the unversioned read holds 5').toBe(latestItems.length);
        expect(atLatest.items.map((item) => item.data[ANSWER_FIELD]).sort()).toEqual(
          latestItems.map((seed) => seed.data[ANSWER_FIELD]).sort(),
        );
      });

      const items = new DatasetItemsPage(page, versionedDataset.projectId, versionedDataset.id);
      const sidebar = await test.step('Open the older version from the Version history tab', async () => {
        await items.goto();
        await items.waitForReady();
        await items.openVersionHistory();
        return items.openVersionRecords(older.name);
      });

      await test.step('The sheet names the opened version and declares itself read-only', async () => {
        await expect(sidebar.title()).toContainText(`${older.name} · ${versionedDataset.name}`);
        await expect(sidebar.readOnlyTag()).toBeVisible();
        // The sheet addresses its version by hash, in its own `dvs_` namespace.
        expect(sidebar.dvsParams()['dvs_version']).toBe(older.hash);
      });

      await test.step("The sheet's counters are the opened version's, not the latest's", async () => {
        // `3 items · +3 ~0 −0` for v1, against v2's `5 items · +2 ~2 −0`. This
        // is the one place a sheet that loaded the wrong version by hash shows
        // a wrong NUMBER rather than wrong rows.
        await expect(sidebar.meta()).toContainText(
          `· ${older.itemsTotal} items · +${olderItems.length} ~0 −0`,
        );
        await expect(sidebar.showingLabel()).toHaveText(
          `Showing 1-${olderItems.length} of ${olderItems.length}`,
        );
      });

      await test.step("It renders exactly the older version's rows, with their original values", async () => {
        await expect(sidebar.itemRows()).toHaveCount(olderItems.length);
        expect(
          (await sidebar.itemRowIds()).sort(),
          'by identity, not by the text the cells happen to show',
        ).toEqual(olderItems.map((seed) => seed.id).sort());

        for (const seed of olderItems) {
          await expect(sidebar.itemCell(seed.id, ANSWER_FIELD)).toHaveText(
            seed.data[ANSWER_FIELD],
          );
        }
      });

      await test.step('And nothing the later version introduced appears anywhere in it', async () => {
        // The assertion that separates a version-scoped read from a
        // page-size-scoped one. Each marker is whole-string unique to the
        // version that introduced it, so a single leaked row fails here.
        await expect(sidebar.root).toContainText(V1_MARKER);
        await expect(sidebar.root).not.toContainText(V2_EDITED_MARKER);
        await expect(sidebar.root).not.toContainText(V2_NEW_MARKER);
        // The decoy dataset in the same project. A sheet that read items
        // without scoping to its own dataset shows up here and nowhere else.
        await expect(sidebar.root).not.toContainText(BYSTANDER_MARKER);
      });

      await test.step('Closing it clears every param it owns and leaves the Records tab on the latest', async () => {
        await sidebar.close();
        expect(sidebar.dvsParams()).toEqual({});

        await items.openRecords();
        await expect(items.itemRows()).toHaveCount(latestItems.length);
        for (const seed of latestItems) {
          await expect(items.itemCell(seed.id, ANSWER_FIELD)).toHaveText(seed.data[ANSWER_FIELD]);
        }
        // The dataset is unchanged by having been looked at — the sheet is
        // read-only, so the latest version is still the one the seed cut.
        const versions = await backendClient.getDatasetVersions(versionedDataset.id);
        expect(versions.map((v) => v.versionName).sort()).toEqual(
          [older.name, latest.name].sort(),
        );
      });
    },
  );

  test(
    "A row's detail panel shows the opened version's content, not the item's current content",
    { tag: ['@t2-cuj', '@cap:datasets.version-history-view'] },
    async ({ versionedDataset, backendClient, page }) => {
      const { older, olderItems, latestItems } = versionedDataset;

      // The item the second version rewrote: same id in both versions,
      // different content. It is the only shape that can tell the panel's two
      // possible sources apart, because the item-by-id endpoint the editor
      // normally uses always answers with an item's CURRENT content — so a
      // panel built that way renders `V2EDITED` while sitting inside a sheet
      // that is showing v1.
      const edited = olderItems.find((seed) => {
        const current = latestItems.find((other) => other.id === seed.id);
        return current !== undefined && current.data[ANSWER_FIELD] !== seed.data[ANSWER_FIELD];
      });
      expect(edited, 'the seed holds an item whose content changed between the versions').toBeDefined();
      if (!edited) throw new Error('unreachable: asserted above');
      const currentValue = latestItems.find((other) => other.id === edited.id)?.data[ANSWER_FIELD];
      expect(currentValue, 'and a current value to contrast it against').toBeDefined();

      await test.step('The item really does read back differently by id today', async () => {
        const stored = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedDataset.id,
        });
        const live = stored.items.find((item) => item.id === edited.id);
        expect(live, 'the edited item is still in the dataset').toBeDefined();
        expect(live?.data[ANSWER_FIELD]).toBe(currentValue);
      });

      const items = new DatasetItemsPage(page, versionedDataset.projectId, versionedDataset.id);
      await test.step("Open the older version and then the edited row's panel", async () => {
        await items.goto();
        await items.waitForReady();
        await items.openVersionHistory();
        const sidebar = await items.openVersionRecords(older.name);
        await sidebar.openItem(edited.id);

        await expect(
          sidebar.panelSection('Data'),
          "the panel shows the value the opened version held",
        ).toContainText(edited.data[ANSWER_FIELD]);
        await expect(
          sidebar.panelSection('Data'),
          'and not the value the item carries today',
        ).not.toContainText(String(currentValue));
      });
    },
  );
});
