import { test, expect, ANSWER_FIELD, V2_NEW_MARKER } from '@e2e/fixtures';
import { TestSuiteItemsPage } from '@e2e/pom/test-suite-items.page';

/**
 * On a test suite, the version sheet renders more than a table: a version's
 * own Suite assertions and Execution policy, and a per-test-case assertion and
 * policy column. None of that is covered anywhere — `test-suites-smoke` and the
 * dedup specs read the live Test cases tab, which is supposed to show the
 * latest.
 *
 * This is the shape of the failure that matters here, and it is worse than the
 * dataset one. A suite's versions often hold the SAME test cases and differ
 * only in how strictly they are judged, so a sheet reading the latest version
 * renders a page with the right rows, the right count, and a wrong number on
 * it — "2 of 3 must pass" where the version being looked at said "1 of 2".
 * Nothing on screen contradicts it.
 *
 * So the seed (`versionedSuite`) makes the two versions differ on exactly that
 * axis as well as on their cases: the opened version carries two assertions and
 * a 1-of-2 policy, the latest carries one different assertion and a 2-of-3
 * policy. Every assertion below would pass against a sheet wired to the latest
 * if the seed were symmetric.
 */
test.describe('Test suite version records sidebar', { tag: ['@area:test-suites'] }, () => {
  test(
    "An older version's sheet renders that version's suite meta and test cases",
    { tag: ['@t2-cuj', '@cap:test-suites.version-history-view'] },
    async ({ versionedSuite, backendClient, page }) => {
      const { older, latest, olderItems, latestItems } = versionedSuite;

      await test.step('The two versions really do differ in their meta and their cases', async () => {
        // Established over the API before the browser opens: a UI assertion
        // over a seed that failed to produce the contrast is a test that
        // cannot fail, and the contrast is the whole point here.
        // The by-hash read the sheet itself makes. It throws rather than
        // answering a nullable version, so a hash that resolves to nothing
        // fails here, naming the endpoint, instead of arriving below as a
        // comparison against an absent value.
        const olderVersion = await backendClient.getDatasetVersionByHash({
          datasetId: versionedSuite.id,
          versionHash: older.hash,
        });
        const latestVersion = await backendClient.getDatasetVersionByHash({
          datasetId: versionedSuite.id,
          versionHash: latest.hash,
        });

        expect(olderVersion.versionName).toBe(older.name);
        expect(latestVersion.versionName).toBe(latest.name);
        expect(olderVersion.assertionNames).toEqual(versionedSuite.olderAssertions);
        expect(olderVersion.executionPolicy).toEqual(versionedSuite.olderPolicy);
        expect(latestVersion.assertionNames).toEqual(versionedSuite.latestAssertions);
        expect(latestVersion.executionPolicy).toEqual(versionedSuite.latestPolicy);
        expect(latestVersion.isLatest).toBe(true);
        expect(olderVersion.isLatest).toBe(false);

        const atOlder = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedSuite.id,
          versionHash: older.hash,
        });
        expect(atOlder.total, 'the opened version holds 2 cases').toBe(olderItems.length);
        expect(atOlder.items.map((item) => item.id).sort()).toEqual(
          olderItems.map((seed) => seed.id).sort(),
        );
        const atLatest = await backendClient.listDatasetItemsAtVersion({
          datasetId: versionedSuite.id,
        });
        expect(atLatest.total, 'while the suite now holds 4').toBe(latestItems.length);
      });

      const suite = new TestSuiteItemsPage(page, versionedSuite.projectId, versionedSuite.id);
      const sidebar = await test.step('Open the older version from the Version history tab', async () => {
        await suite.goto();
        await suite.waitForReady();
        await suite.openVersionHistory();
        return suite.openVersionRecords(older.name);
      });

      await test.step('The meta block carries the opened version’s suite assertions', async () => {
        await expect(sidebar.title()).toContainText(`${older.name} · ${versionedSuite.name}`);
        await expect(sidebar.suiteAssertions()).toHaveText(versionedSuite.olderAssertions);
        for (const assertion of versionedSuite.latestAssertions) {
          await expect(
            sidebar.root,
            'and none of the assertions only the latest version carries',
          ).not.toContainText(assertion);
        }
      });

      await test.step('And the opened version’s execution policy, formatted for a reader', async () => {
        const { passThreshold, runsPerItem } = versionedSuite.olderPolicy;
        await expect(sidebar.executionPolicy()).toContainText(
          `${passThreshold} of ${runsPerItem} must pass`,
        );
        const stricter = versionedSuite.latestPolicy;
        // The assertion this spec exists for: the suite's CURRENT policy is
        // `2 of 3`, so a sheet reading the latest version renders that number
        // here while showing the opened version's cases.
        await expect(sidebar.root).not.toContainText(
          `${stricter.passThreshold} of ${stricter.runsPerItem} must pass`,
        );
      });

      await test.step('The table holds exactly that version’s test cases', async () => {
        await expect(sidebar.itemRows()).toHaveCount(olderItems.length);
        expect(
          (await sidebar.itemRowIds()).sort(),
          'by identity — the Test cases grid has no data column to match on',
        ).toEqual(olderItems.map((seed) => seed.id).sort());
        await expect(sidebar.showingLabel()).toHaveText(
          `Showing 1-${olderItems.length} of ${olderItems.length}`,
        );
        // The two cases the later version added carry a marker of their own.
        await expect(sidebar.root).not.toContainText(V2_NEW_MARKER);
      });

      await test.step('Each row reports the version’s own policy, and no custom assertions', async () => {
        const { passThreshold, runsPerItem } = versionedSuite.olderPolicy;
        for (const seed of olderItems) {
          await expect(sidebar.suiteCell(seed.id, 'execution_policy')).toHaveText(
            `${passThreshold} of ${runsPerItem} must pass`,
          );
          // These cases were seeded with no per-item assertions, so the column
          // must show none. A cell reporting the SUITE's two here would be the
          // per-item column counting the wrong evaluator.
          await expect(sidebar.suiteCell(seed.id, 'assertions')).toHaveText('—');
        }
      });

      await test.step('Opening a test case shows its data, assertions and policy, read-only', async () => {
        const [firstCase] = olderItems;
        await sidebar.openItem(firstCase.id);

        await expect(sidebar.panelSection('Data')).toContainText(
          String(firstCase.data[ANSWER_FIELD]),
        );
        await expect(sidebar.panelSection('Assertions')).toContainText('Only suite assertions');
        const { passThreshold, runsPerItem } = versionedSuite.olderPolicy;
        // Inherited from the version, and marked as inherited — the panel
        // falls back to the OPENED version's policy, not the suite's current
        // one, which is the same substitution the grid cell can get wrong.
        await expect(sidebar.panelSection('Execution policy')).toContainText(
          `${passThreshold} of ${runsPerItem} runs must pass (suite default)`,
        );
      });

      await test.step('Closing the sheet leaves the Test cases tab on the latest version', async () => {
        await sidebar.closeItemPanel();
        await sidebar.close();
        expect(sidebar.dvsParams()).toEqual({});

        await page.getByRole('tab', { name: 'Test cases' }).click();
        await suite.waitForReady();
        await expect(suite.itemRows()).toHaveCount(latestItems.length);
      });
    },
  );
});
