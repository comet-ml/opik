import { test, expect } from '@e2e/fixtures';
import { loadEnvConfig } from '../../config/env.config';
import { SuiteExperimentPanelPage } from '@e2e/pom/suite-experiment-panel.page';
import { TestSuiteItemsPage } from '@e2e/pom/test-suite-items.page';
import {
  parseFromParam,
  searchParamMap,
  toRelativeHref,
} from '@e2e/pom/item-return-nav.page';

/**
 * The way back from an EVALUATION-SUITE item to the experiment (OPIK-8600).
 *
 * The second of the change's two call sites, and the one it is named after:
 * "View evaluation item" in the evaluation-suite item sidebar. Its sibling spec
 * `compare-item-return-to-experiment.spec.ts` drives the dataset-method one.
 * Neither is evidence about the other, for the reason the taxonomy already
 * argues for this panel's media axis: the compare route's Items tab mounts
 * `TestSuiteExperimentPanel` or `CompareExperimentsPanel` on the first
 * experiment's `evaluation_method`, the two are separately wired, and they land
 * on DIFFERENT pages — `/test-suites/$suiteId/items` here, whose header falls
 * back to "Back to test suites" rather than "Back to datasets". A regression in
 * one leaves the other working.
 *
 * This side also pins the SINGULAR branch of the Experiment button's tooltip
 * ("in experiment:", one name), which the dataset-side spec — always two
 * compared experiments — never reaches. The branch is a bare
 * `experimentsIds.length > 1` ternary, so it is exactly the kind of thing that
 * stays wrong indefinitely if only the plural path is ever exercised.
 *
 * Deterministic and LLM-free: `suiteItemNav` creates the suite and its items
 * through the SDK and then forges the run by writing traces, an
 * `evaluation_method: 'evaluation_suite'` experiment and the experiment items
 * directly — the recipe `suite-experiment-run-media.fixture.ts` establishes and
 * explains. A real suite run would need an LLM judge for its assertions.
 */
test.describe(
  'Evaluation-suite item sidebar — the way back to the experiment',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    /** Same 120s seed budget and reason as the sibling suite-sidebar spec. */
    test.slow();

    /**
     * The workspace segment every in-app path starts with. Read from the
     * suite's config rather than sliced off the current URL, so the
     * expectations below cannot agree with a wrong answer.
     */
    const { workspace } = loadEnvConfig();

    test(
      'the "View evaluation item" tag carries the compare view, and the suite items page reads it back',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ suiteItemNav, project, page }) => {
        const seed = suiteItemNav;
        const sidebar = new SuiteExperimentPanelPage(page, project.id, seed.suiteId, [
          seed.experimentId,
        ]);
        const openItemId = seed.items[0].datasetItemId;

        let originHref = '';
        let fromParam = '';

        await test.step('Open the compare Items tab with the suite item sidebar mounted', async () => {
          await sidebar.gotoItemRow(openItemId);
          originHref = toRelativeHref(page.url());
        });

        await test.step('The tag targets the suite items page, carrying `row` and `from`', async () => {
          const href = await sidebar.readEvaluationItemTagHref();
          const target = new URL(href, 'http://tag.invalid');

          // The suite route, not the dataset one — the two items pages are the
          // same React component behind different prefixes, and this call site
          // must pick the suite prefix.
          expect(target.pathname, 'the tag targets the test-suite items page').toBe(
            `/${workspace}/projects/${project.id}/test-suites/${seed.suiteId}/items`,
          );
          expect(target.searchParams.get('row'), 'the tag carries the open row').toBe(openItemId);

          const from = target.searchParams.get('from');
          // Asserted before use: a missing `from` is the regression itself.
          expect(from, 'the tag carries a `from`').not.toBeNull();
          fromParam = from as string;

          const returned = parseFromParam(fromParam);
          const origin = parseFromParam(originHref);
          expect(returned.pathname, '`from` points at the compare route').toBe(origin.pathname);
          // The whole param map, for the same reason as the dataset-side spec:
          // a `from` that kept `experiments` and lost the rest would satisfy any
          // membership check while losing the user's view.
          expect(
            searchParamMap(returned.search),
            '`from` carries every search param of the view it was built from',
          ).toEqual(searchParamMap(origin.search));
          expect(
            searchParamMap(returned.search),
            '`from` names the experiment and the Items tab',
          ).toMatchObject({
            experiments: JSON.stringify([seed.experimentId]),
            tab: 'items',
          });
        });

        await test.step('Clicking through lands on the suite items page with both intact', async () => {
          await sidebar.clickEvaluationItemTag();
          const landed = new URL(page.url());
          expect(landed.searchParams.get('row'), 'the open row survived the hop').toBe(openItemId);
          expect(landed.searchParams.get('from'), '`from` survived the hop unchanged').toBe(
            fromParam,
          );
        });
      },
    );

    test(
      '"Back to experiment" restores the compare view from the suite items page',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ suiteItemNav, project, page }) => {
        const seed = suiteItemNav;
        const sidebar = new SuiteExperimentPanelPage(page, project.id, seed.suiteId, [
          seed.experimentId,
        ]);
        const items = new TestSuiteItemsPage(page, project.id, seed.suiteId);

        let originHref = '';
        let originPathname = '';
        let originSearch: Record<string, string> = {};

        await test.step('Record the compare view to return to', async () => {
          await sidebar.gotoItemRow(seed.items[0].datasetItemId);
          const settled = new URL(page.url());
          originHref = toRelativeHref(page.url());
          originPathname = settled.pathname;
          originSearch = searchParamMap(settled.search);
          // The view has to carry state worth losing, or a back button that
          // dropped everything would still pass below.
          expect(
            Object.keys(originSearch).sort(),
            'the view being returned to carries search state worth losing',
          ).toEqual(expect.arrayContaining(['experiments', 'row', 'tab']));
        });

        await test.step('Open the suite items page from it, with no row expanded', async () => {
          // No `row`: the item panel's scrim covers the header, so the back
          // button is only hoverable with the panel closed.
          await items.gotoWithReturn({ from: originHref });
          await items.waitForReady();
        });

        const nav = items.headerReturnNav();

        await test.step('The header offers the experiment, not the test-suites list', async () => {
          await nav.expectBackTooltip('Back to experiment');
          expect(
            await nav.readBackHref(),
            'the back button points at the compare view it was handed',
          ).toBe(originHref);
        });

        await test.step('Following it restores every search param of that view', async () => {
          await nav.clickBackToCompare();
          // Settle on the sidebar the restored `row` must reopen, before reading
          // the URL: the compare route writes its own params as it mounts.
          await expect(sidebar.root, 'the suite item sidebar reopened').toBeVisible({
            timeout: 60_000,
          });

          const restored = new URL(page.url());
          expect(restored.pathname, 'back on the compare route').toBe(originPathname);
          expect(
            searchParamMap(restored.search),
            'the restored view is the one that was left',
          ).toEqual(originSearch);
        });
      },
    );

    test(
      'the suite panel\'s Experiment button is singular-labelled and follows the panel',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ suiteItemNav, project, page }) => {
        const seed = suiteItemNav;
        const sidebar = new SuiteExperimentPanelPage(page, project.id, seed.suiteId, [
          seed.experimentId,
        ]);
        const items = new TestSuiteItemsPage(page, project.id, seed.suiteId);

        let renderedIds: string[] = [];

        await test.step('Read the rows the suite items grid renders', async () => {
          await items.gotoWithReturn({});
          await items.waitForReady();
          await expect(items.itemRows(), 'both seeded suite items on one page').toHaveCount(
            seed.items.length,
          );
          renderedIds = await items.itemRowIds();
          // Exhaustive, as a set — the steps below open the FIRST rendered row
          // and assert it stepped to the SECOND, so those positions only mean
          // something if the grid is showing the seeded items and nothing else.
          // Compared sorted because which item the grid puts first is the
          // backend's to decide.
          expect([...renderedIds].sort(), 'the grid shows exactly the seeded items').toEqual(
            [...seed.items.map((item) => item.datasetItemId)].sort(),
          );
        });

        // The FIRST rendered row, so `Next` is always available whichever order
        // the backend returned the two items in.
        const startRow = renderedIds[0];
        const expectedRow = renderedIds[1];

        let originHref = '';

        await test.step('Record a compare view open on that first row', async () => {
          // Built around `startRow` deliberately, not around a fixed seed item.
          // The whole point below is that clicking Experiment after stepping
          // goes to the item the PANEL moved to and not to the one `from`
          // carries — so the two have to be different items, and with only two
          // rows the only way to guarantee that is to pin `from` to the row the
          // panel starts on. An arbitrary seed item would, half the time, BE
          // the row the panel steps to, and the assertion would then be unable
          // to tell a correct build from a broken one while still passing.
          await sidebar.gotoItemRow(startRow);
          originHref = toRelativeHref(page.url());
          expect(
            new URL(originHref, 'http://origin.invalid').searchParams.get('row'),
            '`from` carries the row the panel will start on, so stepping away is observable',
          ).toBe(startRow);
        });

        await test.step('Open that first row\'s panel from the compare view', async () => {
          await items.gotoWithReturn({ from: originHref, row: startRow });
        });

        const nav = await items.itemPanelReturnNav();

        await test.step('The tooltip names the one experiment, in the singular', async () => {
          // Singular "experiment", one name. The dataset-side spec only ever
          // sees the plural branch, so this is the only place the ternary's
          // other arm is exercised at all.
          await nav.expectExperimentTooltip(
            `View this item in experiment: ${seed.experimentName}`,
          );
        });

        await test.step('Step the panel to the other suite item', async () => {
          const stepped = await nav.stepToNextItem(startRow);
          expect(stepped, 'stepping moved the panel to the other item').toBe(expectedRow);
        });

        await test.step('The button reopens compare on the stepped-to item', async () => {
          await nav.clickExperiment();
          const landed = new URL(page.url());

          expect(landed.searchParams.get('row'), 'compare reopened on the item the panel showed').toBe(
            expectedRow,
          );
          // Stated as the negative too: navigating by the `row` baked into
          // `from` is the specific regression the change's last commit fixed.
          expect(
            landed.searchParams.get('row'),
            'compare did NOT reopen on the row baked into `from`',
          ).not.toBe(startRow);
          expect(
            searchParamMap(landed.search),
            'the rest of the originating view came back with it',
          ).toMatchObject({
            experiments: JSON.stringify([seed.experimentId]),
            tab: 'items',
          });
        });

        await test.step('And the sidebar that opened is that item\'s', async () => {
          // The URL alone would be satisfied by a build that navigated correctly
          // and rendered the wrong row; the sidebar's own outgoing tag names the
          // item it is showing.
          const href = await sidebar.readEvaluationItemTagHref();
          expect(
            new URL(href, 'http://tag.invalid').searchParams.get('row'),
            'the suite item sidebar is showing the stepped-to item',
          ).toBe(expectedRow);
        });
      },
    );

    test(
      'an absent or off-site `from` falls back to the test-suites list with no Experiment button',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ suiteItemNav, project, page }) => {
        const seed = suiteItemNav;
        const sidebar = new SuiteExperimentPanelPage(page, project.id, seed.suiteId, [
          seed.experimentId,
        ]);
        const items = new TestSuiteItemsPage(page, project.id, seed.suiteId);
        const openItemId = seed.items[0].datasetItemId;

        let goodFrom = '';

        await test.step('Record a good `from`, to be the control', async () => {
          await sidebar.gotoItemRow(openItemId);
          goodFrom = toRelativeHref(page.url());
        });

        /**
         * The reject cases, and the good one beside them.
         *
         * The control is load-bearing: it runs the same two locators against a
         * page that must show the button, so a zero for the reject cases is a
         * real absence rather than a selector matching nothing. The fallback
         * label differs from the dataset side — "Back to test suites" — which is
         * the half of this change that only this route can show.
         */
        const cases: Array<{
          label: string;
          from: string | null;
          backTooltip: string;
          expectButton: boolean;
        }> = [
          { label: 'a good compare href (control)', from: goodFrom, backTooltip: 'Back to experiment', expectButton: true },
          { label: 'no `from` at all', from: null, backTooltip: 'Back to test suites', expectButton: false },
          {
            label: 'an off-site absolute URL',
            from: `https://evil.example.com/${workspace}/projects/${project.id}/experiments/${seed.suiteId}/compare`,
            backTooltip: 'Back to test suites',
            expectButton: false,
          },
          {
            label: 'a protocol-relative URL',
            from: `//evil.example.com/${workspace}/projects/${project.id}/experiments/${seed.suiteId}/compare`,
            backTooltip: 'Back to test suites',
            expectButton: false,
          },
          {
            // The href parses, so the back button resolves and is labelled for
            // the experiment — but with no `experiments` array there is nothing
            // for the Experiment button to open. The one case where the two
            // gates must disagree.
            label: 'a valid compare href carrying no `experiments`',
            from: `/${workspace}/projects/${project.id}/experiments/${seed.suiteId}/compare?tab=items`,
            backTooltip: 'Back to experiment',
            expectButton: false,
          },
        ];

        for (const { label, from, backTooltip, expectButton } of cases) {
          await test.step(`With ${label}, the back button reads "${backTooltip}"`, async () => {
            await items.gotoWithReturn({ from });
            await items.waitForReady();
            await items.headerReturnNav().expectBackTooltip(backTooltip);
          });

          await test.step(
            `With ${label}, the Experiment button is ${expectButton ? 'offered' : 'absent'}`,
            async () => {
              // Two navigations, because the controls cannot be read in one
              // state: the button needs the panel open, and the panel's scrim
              // makes the header back button un-hoverable.
              await items.gotoWithReturn({ from, row: openItemId });
              const nav = await items.itemPanelReturnNav();
              await expect(
                nav.experimentButton,
                `Experiment buttons in the suite item panel for ${label}`,
              ).toHaveCount(expectButton ? 1 : 0);
            },
          );
        }
      },
    );
  },
);
