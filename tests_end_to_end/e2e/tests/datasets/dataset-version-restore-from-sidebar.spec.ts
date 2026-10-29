import { test, expect, ANSWER_FIELD, BYSTANDER_MARKER, V1_MARKER } from '@e2e/fixtures';
import { DatasetItemsPage } from '@e2e/pom/dataset-items.page';

/**
 * "Restore this version" moved inside the read-only version sheet, and it is
 * the one control there that WRITES. What it must do is exact: commit a new
 * version holding the OPENED version's items, leave every earlier version in
 * the history, and leave the rest of the workspace alone.
 *
 * Three things make it worth its own spec rather than a line in the sidebar
 * one. The scope of a destructive-ish write is the part that fails silently —
 * restoring the wrong version still produces a plausible new latest. The
 * confirmation names the version it is about to restore, which is the user's
 * only chance to notice. And both the Version history tab and the Records tab
 * have to follow without a reload, which is a cache-invalidation path that
 * nothing else exercises.
 *
 * The seed (`versionedDataset`) gives v1 three `V1ORIGINAL` rows and v2 five —
 * two of them rewritten, two of them new — so restoring v1 has to move the
 * latest from 5 rows back to 3 specific ones, and the counters it cuts
 * (+0 ~2 −2) are different from the counters restoring any other version would.
 * It also seeds a second dataset nobody touches, so a restore that reached
 * beyond the dataset it was invoked on is visible.
 */
test.describe('Dataset version restore from the sidebar', { tag: ['@area:datasets'] }, () => {
  test(
    'Restoring from inside the version sheet commits a new version holding the opened version’s items',
    { tag: ['@t2-cuj', '@cap:datasets.restore-version'] },
    async ({ versionedDataset, backendClient, page }) => {
      const { older, latest, olderItems, latestItems, bystander } = versionedDataset;

      await test.step('Before the restore: the latest version is the five-row one', async () => {
        // The restore's effect is a change, so the starting state has to be
        // established rather than assumed — otherwise "the latest now holds
        // v1's rows" would also pass against a dataset that never held
        // anything else.
        const versions = await backendClient.getDatasetVersions(versionedDataset.id);
        expect(versions.map((v) => v.versionName).sort()).toEqual(
          [older.name, latest.name].sort(),
        );
        expect(versions.find((v) => v.isLatest)?.versionName).toBe(latest.name);
        const atLatest = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedDataset.id,
        });
        expect(atLatest.total).toBe(latestItems.length);
      });

      const items = new DatasetItemsPage(page, versionedDataset.projectId, versionedDataset.id);
      const sidebar = await test.step('Open the older version and ask to restore it', async () => {
        await items.goto();
        await items.waitForReady();
        await items.openVersionHistory();
        const opened = await items.openVersionRecords(older.name);
        await opened.restoreButton().click();
        await opened.restoreDialog().waitFor({ state: 'visible' });
        return opened;
      });

      await test.step('The confirmation names the version that was opened', async () => {
        // The user's only chance to notice a mis-wired restore before it
        // writes, so it is asserted before confirming rather than after.
        await expect(sidebar.restoreDialog()).toContainText(
          `will create a new version based on version ${older.name}`,
        );
        await expect(sidebar.restoreDialog()).toContainText(
          'All previous versions will stay in your history',
        );
      });

      await test.step('Confirming closes the sheet and clears every param it owned', async () => {
        await sidebar.confirmRestore();
        expect(sidebar.dvsParams()).toEqual({});
      });

      const restored = await test.step('A third version is now latest, holding the restored rows', async () => {
        const versions = await backendClient.getDatasetVersions(versionedDataset.id);
        expect(versions, 'the restore added a version rather than replacing one').toHaveLength(3);
        // Every earlier version survives — what the dialog promised.
        expect(versions.map((v) => v.versionName)).toContain(older.name);
        expect(versions.map((v) => v.versionName)).toContain(latest.name);

        const latestNow = versions.filter((v) => v.isLatest);
        expect(latestNow, 'exactly one version is latest').toHaveLength(1);
        const [newVersion] = latestNow;
        expect(newVersion.versionName).not.toBe(older.name);
        expect(newVersion.versionName).not.toBe(latest.name);
        // The counters say WHICH version was restored: reverting the two
        // rewritten rows and dropping the two added ones is the v1 answer, and
        // no other version in this history produces it.
        expect(
          {
            itemsTotal: newVersion.itemsTotal,
            itemsAdded: newVersion.itemsAdded,
            itemsModified: newVersion.itemsModified,
            itemsDeleted: newVersion.itemsDeleted,
          },
          'the new version reverted 2 rows and dropped 2',
        ).toEqual({
          itemsTotal: olderItems.length,
          itemsAdded: 0,
          itemsModified: 2,
          itemsDeleted: 2,
        });

        const atLatest = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedDataset.id,
        });
        expect(atLatest.total).toBe(olderItems.length);
        expect(atLatest.items.map((item) => item.id).sort()).toEqual(
          olderItems.map((seed) => seed.id).sort(),
        );
        expect(
          atLatest.items.map((item) => item.data[ANSWER_FIELD]).sort(),
          'with the values the opened version held',
        ).toEqual(olderItems.map((seed) => seed.data[ANSWER_FIELD]).sort());

        return newVersion;
      });

      await test.step('The Version history tab follows without a reload', async () => {
        await expect(items.versionHistoryRow(restored.versionName)).toHaveCount(1);
        await expect(items.versionItemCount(restored.versionName)).toHaveText(
          String(olderItems.length),
        );

        // The new version is the un-openable one now, and the version that USED
        // to be latest has become openable. Asserted behaviourally: clicking
        // the new latest must not open a sheet, and the proof is that the very
        // next click — on the previous latest's row — still lands. A sheet
        // opened by the first click is modal, so it would swallow the second.
        await items.versionHistoryRow(restored.versionName).click();
        const previousLatest = await items.openVersionRecords(latest.name);
        await expect(previousLatest.title()).toContainText(
          `${latest.name} · ${versionedDataset.name}`,
        );
        await expect(previousLatest.itemRows()).toHaveCount(latestItems.length);
        await previousLatest.close();
      });

      await test.step('And the Records tab serves the restored rows', async () => {
        await items.openRecords();
        await expect(items.itemRows()).toHaveCount(olderItems.length);
        expect((await items.itemRowIds()).sort()).toEqual(
          olderItems.map((seed) => seed.id).sort(),
        );
        for (const seed of olderItems) {
          await expect(items.itemCell(seed.id, ANSWER_FIELD)).toHaveText(seed.data[ANSWER_FIELD]);
        }
        await expect(items.versionLabel(3)).toBeVisible();
      });

      await test.step('The bystander dataset is untouched', async () => {
        // Asserting only that the subject changed correctly would pass equally
        // well for a restore that rewrote every dataset in the project.
        const versions = await backendClient.getDatasetVersions(bystander.id);
        expect(versions, 'the decoy still has its own two versions').toHaveLength(
          bystander.versionCount,
        );
        const stored = await backendClient.listDatasetItemsAtVersion({
          datasetId: bystander.id,
        });
        expect(stored.total).toBe(bystander.items.length);
        expect(stored.items.map((item) => item.id).sort()).toEqual(
          bystander.items.map((seed) => seed.id).sort(),
        );
        expect(stored.items.map((item) => item.data[ANSWER_FIELD]).sort()).toEqual(
          bystander.items.map((seed) => seed.data[ANSWER_FIELD]).sort(),
        );
        expect(
          stored.items.every((item) => String(item.data[ANSWER_FIELD]).startsWith(BYSTANDER_MARKER)),
          'and none of the subject’s rows leaked into it',
        ).toBe(true);
        expect(
          stored.items.some((item) => String(item.data[ANSWER_FIELD]).includes(V1_MARKER)),
        ).toBe(false);
      });
    },
  );
});
