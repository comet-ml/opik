import {
  test,
  expect,
  VERSION_HISTORY_PAGE_SIZE,
  VERSIONED_PROMPT_VERSION_COUNT,
  versionMarker,
} from '../../fixtures/versioned-prompt.fixture';
import { PromptsPage } from '@e2e/pom/prompts.page';
import type { PromptDetailPage } from '@e2e/pom/prompt-detail.page';
import type { Page } from '@playwright/test';

/**
 * Prompt version history is an infinite query over pages of 25, and every
 * surface that names a version — the sidebar, the Diff menu, the compare sheet,
 * the "Deploy to" menu — has to agree on that name across the page boundary.
 *
 * All of it fails silently. A bad page boundary shows up as a duplicated or
 * missing row, a label derived from "how many versions are loaded" shows up as
 * a plausible-but-wrong `vN`, and a menu that doesn't page far enough just
 * omits a label — so a reader concludes nothing is deployed. None of it errors,
 * which is why these are assertions on the values a human reads rather than on
 * the page having rendered.
 *
 * The existing prompt specs never cross the boundary: prompt-library-smoke gets
 * to v2, prompt-playground-save to v4. Everything here needs a prompt with more
 * versions than one page holds.
 */
test.describe('Prompt version history — pagination', { tag: ['@t2-cuj', '@area:prompts'] }, () => {
  // The version-history sidebar is xl-only (`hidden xl:block`); below that
  // width the page renders the compact version dropdown instead.
  test.use({ viewport: { width: 1600, height: 900 } });

  async function openPromptDetail(
    page: Page,
    projectId: string,
    promptName: string,
  ): Promise<PromptDetailPage> {
    const prompts = new PromptsPage(page);
    await prompts.goto(projectId);
    await prompts.waitForReady();
    const detail = await prompts.openPromptByName(promptName);
    await detail.waitForReady();
    return detail;
  }

  test(
    'Version history pages through every version once, in order, and a background refetch leaves it alone',
    { tag: ['@cap:prompts.version-history'] },
    async ({ project, versionedPrompt, backendClient, page }) => {
      // 30 seeded versions, then a wait for the 30s background refetch.
      test.slow();

      const allLabels = versionedPrompt.labels;
      const firstPageLabels = allLabels.slice(0, VERSION_HISTORY_PAGE_SIZE);

      await test.step('The API pages the versions newest-first, 25 to a page', async () => {
        const firstPage = await backendClient.listPromptVersions(versionedPrompt.id, {
          page: 1,
          size: VERSION_HISTORY_PAGE_SIZE,
        });
        expect(firstPage.total).toBe(VERSIONED_PROMPT_VERSION_COUNT);
        expect(firstPage.versions.map((v) => v.label)).toEqual(firstPageLabels);
      });

      const detail = await openPromptDetail(page, project.id, versionedPrompt.name);

      await test.step('The sidebar opens on the first page, newest version active', async () => {
        await expect(detail.activeVersionLabel()).toHaveText(allLabels[0]);
        await expect(detail.versionHistoryItems()).toHaveCount(VERSION_HISTORY_PAGE_SIZE);
        expect(await detail.versionHistoryLabels()).toEqual(firstPageLabels);
      });

      await test.step('Scrolling to the end loads every remaining version, once each, in order', async () => {
        await detail.loadAllVersions(VERSIONED_PROMPT_VERSION_COUNT);
        // Compared as a whole list, not searched for the ones we expect: a
        // duplicated or skipped row at the page boundary is exactly what a
        // "does v7 appear?" check would miss.
        expect(await detail.versionHistoryLabels()).toEqual(allLabels);
      });

      await test.step('The 30s background refetch does not duplicate or reorder the loaded pages', async () => {
        // Waiting on the refetch landing, not on the clock: the query refetches
        // every 30s, and re-merging the pages it already holds is where a
        // double-append would show up.
        await page.waitForResponse(
          (response) =>
            response.url().includes(`/prompts/${versionedPrompt.id}/versions`) &&
            response.status() === 200,
          { timeout: 60_000 },
        );
        await expect(detail.versionHistoryItems()).toHaveCount(VERSIONED_PROMPT_VERSION_COUNT);
        expect(await detail.versionHistoryLabels()).toEqual(allLabels);
      });
    },
  );

  test(
    'Diff menu reaches versions past the first page and the compare sheet labels them as the sidebar does',
    { tag: ['@cap:prompts.compare-versions'] },
    async ({ project, versionedPrompt, page }) => {
      const allLabels = versionedPrompt.labels;
      const activeLabel = allLabels[0];
      // A version far enough down to be on the second page — the case where a
      // label derived from the loaded-version count stops matching the sidebar.
      const comparedVersion = 2;
      const comparedLabel = `v${comparedVersion}`;

      const detail = await openPromptDetail(page, project.id, versionedPrompt.name);

      await test.step('Only the first page is loaded, so the compared version is not in the sidebar', async () => {
        await expect(detail.versionHistoryItems()).toHaveCount(VERSION_HISTORY_PAGE_SIZE);
        await expect(detail.versionHistoryItem(comparedLabel)).toHaveCount(0);
      });

      await test.step('The Diff menu offers every version except the active one', async () => {
        await detail.openDiffMenu();
        // Opening the menu is what makes it page to the end, so the count
        // assertion is what waits for the second page to land.
        await expect(detail.diffMenuVersionItems()).toHaveCount(VERSIONED_PROMPT_VERSION_COUNT - 1);
        expect(await detail.diffMenuVersionLabels()).toEqual(allLabels.slice(1));
      });

      await test.step(`Comparing against ${comparedLabel} names both panes by their own version numbers`, async () => {
        await detail.compareAgainstVersion(comparedLabel);
        await expect(detail.compareSheetHeading()).toHaveText(
          `Compare ${comparedLabel} → ${activeLabel}`,
        );
        await expect(detail.compareSheet()).toContainText(
          new RegExp(`\\b${versionMarker(comparedVersion)}\\b`),
        );
        await expect(detail.compareSheet()).toContainText(
          new RegExp(`\\b${versionMarker(VERSIONED_PROMPT_VERSION_COUNT)}\\b`),
        );
        // The whole answer, not just "the two I want are here": a sheet
        // labelling its panes from the number of loaded versions renders a
        // different vN, and that extra label is the regression.
        expect(await detail.compareSheetVersionLabels()).toEqual([comparedLabel, activeLabel].sort());
      });
    },
  );

  test(
    'Deploy to menu names the deployed version even when it is on a later page',
    { tag: ['@cap:prompts.version-history'] },
    async ({ project, versionedPrompt, deployedOldVersion, page }) => {
      const detail = await openPromptDetail(page, project.id, versionedPrompt.name);

      await test.step('The deployed version is not among the versions the page loaded', async () => {
        await expect(detail.versionHistoryItems()).toHaveCount(VERSION_HISTORY_PAGE_SIZE);
        await expect(detail.versionHistoryItem(deployedOldVersion.version.label)).toHaveCount(0);
      });

      await test.step('Opening "Deploy to" still names the environment owner', async () => {
        await detail.openDeployMenu();
        const row = detail.deployMenuEnvironmentRow(deployedOldVersion.environment);
        await expect(row).toHaveCount(1);
        // The menu has to page to the version that owns the environment by
        // itself. Without that it renders no note at all — no error, just an
        // environment that reads as having nothing deployed to it.
        await expect(
          row.getByText(`Currently ${deployedOldVersion.version.label}`, { exact: true }),
        ).toHaveCount(1);
      });

      await test.step('No other environment claims a deployed version', async () => {
        await expect(detail.deployMenuCurrentlyLabels()).toHaveCount(1);
      });
    },
  );
});
