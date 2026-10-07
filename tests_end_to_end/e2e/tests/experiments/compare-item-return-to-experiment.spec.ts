import { test, expect } from '@e2e/fixtures';
import { loadEnvConfig } from '../../config/env.config';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';
import { DatasetItemsPage } from '@e2e/pom/dataset-items.page';
import {
  parseFromParam,
  searchParamMap,
  toRelativeHref,
} from '@e2e/pom/item-return-nav.page';

/**
 * The way back from a dataset item to the experiment it was reached from
 * (OPIK-8600).
 *
 * Before this change the "View in test suite" tag in the compare row-detail
 * panel was a one-way trip: it opened the dataset items page, whose header back
 * button went to the datasets LIST, so a user who had built up a compare view —
 * two experiments, a search, a row open — lost all of it to inspect one item.
 * The fix carries the originating compare href in a `from` query param, which
 * the items page reads back for a "Back to experiment" button and for an
 * "Experiment" button in the item side panel.
 *
 * What makes this worth an e2e spec, given the change ships with two vitest
 * files: both mock `@tanstack/react-router` wholesale. `parseExperimentReturnHref.test.ts`
 * exercises the parser on strings, and `ViewInExperimentButton.test.tsx` proves
 * the button's gating against a stubbed router — so between them NOTHING shows
 * that the real `parseSearch` yields the `experiments` ARRAY the button keys on,
 * after that array has survived being JSON-encoded into a query param, URL-
 * encoded into a second one, and parsed back out twice. That round trip is only
 * observable against a running router.
 *
 * The assertions are on EXACT search params rather than on "the compare page
 * rendered", because the failure worth catching is silent: a back button that
 * returns to a compare view with the search or the filters dropped looks
 * perfectly healthy — it is the right page, with the wrong contents. Comparing
 * the whole param map against the originating view's is what sees it.
 *
 * Deterministic by construction: the `comparison` fixture seeds over the SDK
 * with a fixed task function and an `equals_metric` judge, so no LLM and no
 * wall clock is involved, and every id and name asserted here is one the
 * fixture issued.
 *
 * Paired with `suite-item-return-to-experiment.spec.ts`, which drives the OTHER
 * call site — the evaluation-suite sidebar. The Items tab mounts one or the
 * other on `evaluation_method` and they are separately wired, so neither spec
 * is evidence about the other.
 */
test.describe(
  'Compare row detail — the way back to the experiment',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    /** Same 120s seed budget as the sibling compare specs: two SDK experiments. */
    test.slow();

    /**
     * The workspace segment every in-app path starts with.
     *
     * Read from the suite's own config rather than sliced off whatever URL the
     * browser happens to be on: the expectations below are about the path the
     * product BUILDS, and deriving it from the current location would make them
     * agree with a wrong answer.
     */
    const { workspace } = loadEnvConfig();

    test(
      'the dataset-item tag carries the whole compare view as `from`',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ comparison, project, page }) => {
        const [expA, expB] = comparison.experiments;
        const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
          expA.experimentId,
          expB.experimentId,
        ]);
        // The item whose input is 'q1', so the `search=q1` below leaves exactly
        // this row in the grid rather than filtering the open row away.
        const openItemId = comparison.itemIds[0];

        let originHref = '';
        let fromParam = '';

        await test.step('Open the compare view with a search, a row height and a row open', async () => {
          await compare.gotoResultsView({ search: 'q1', height: 'large', row: openItemId });
          // The browser's own settled URL, not the pieces above: the compare
          // route adds params for itself on mount (`filters`), and `from` has to
          // carry the view as it actually is.
          originHref = toRelativeHref(page.url());
        });

        await test.step('The tag points at this item on the items page, carrying `from`', async () => {
          const href = await compare.readDatasetItemTagHref();
          const target = new URL(href, 'http://tag.invalid');

          expect(target.pathname, 'the tag targets the dataset items page').toBe(
            `/${workspace}/projects/${project.id}/datasets/${comparison.datasetId}/items`,
          );
          expect(target.searchParams.get('row'), 'the tag carries the open row').toBe(openItemId);

          const from = target.searchParams.get('from');
          // Asserted before use: a missing `from` is the whole regression, and
          // an optional chain here would turn it into a confusing comparison
          // below instead of naming it.
          expect(from, 'the tag carries a `from`').not.toBeNull();
          fromParam = from as string;
        });

        await test.step('`from` IS the originating compare view, search and all', async () => {
          const returned = parseFromParam(fromParam);
          const origin = parseFromParam(originHref);

          expect(returned.pathname, '`from` points at the compare route').toBe(origin.pathname);
          // The WHOLE param map, not a spot check of the ones this spec set:
          // a `from` that carried `experiments` and dropped `search` would
          // satisfy any membership test while losing the user's view, and that
          // is the shape of the bug.
          expect(
            searchParamMap(returned.search),
            '`from` carries every search param of the view it was built from',
          ).toEqual(searchParamMap(origin.search));
          // Named explicitly as well, so a regression that emptied BOTH sides
          // of the comparison above still fails here.
          expect(
            searchParamMap(returned.search),
            '`from` names both compared experiments, the tab, the search and the height',
          ).toMatchObject({
            experiments: JSON.stringify([expA.experimentId, expB.experimentId]),
            tab: 'items',
            search: 'q1',
            height: 'large',
          });
        });

        await test.step('Clicking it lands on the items page with `row` and `from` intact', async () => {
          await compare.clickDatasetItemTag();
          const landed = new URL(page.url());
          expect(landed.pathname, 'landed on the dataset items page').toBe(
            `/${workspace}/projects/${project.id}/datasets/${comparison.datasetId}/items`,
          );
          expect(landed.searchParams.get('row'), 'the open row survived the hop').toBe(openItemId);
          // Verbatim: the items page appends its own view params, but it must
          // not rewrite the one it was handed — a re-encoded `from` is what
          // makes the back button parse it into something else.
          expect(landed.searchParams.get('from'), '`from` survived the hop unchanged').toBe(
            fromParam,
          );
        });
      },
    );

    test(
      '"Back to experiment" restores the compare view it came from',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ comparison, project, page }) => {
        const [expA, expB] = comparison.experiments;
        const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
          expA.experimentId,
          expB.experimentId,
        ]);
        const items = new DatasetItemsPage(page, project.id, comparison.datasetId);
        const openItemId = comparison.itemIds[0];

        let originHref = '';
        let originPathname = '';
        let originSearch: Record<string, string> = {};

        await test.step('Record the compare view to return to', async () => {
          await compare.gotoResultsView({ search: 'q1', height: 'large', row: openItemId });
          const settled = new URL(page.url());
          originHref = toRelativeHref(page.url());
          originPathname = settled.pathname;
          originSearch = searchParamMap(settled.search);
          // The view has to be worth restoring for the assertion to mean
          // anything: if it carried nothing but `experiments`, a back button
          // that dropped everything else would still pass below.
          expect(
            Object.keys(originSearch).sort(),
            'the view being returned to carries search state worth losing',
          ).toEqual(expect.arrayContaining(['experiments', 'height', 'row', 'search', 'tab']));
        });

        await test.step('Open the items page from it, with no row expanded', async () => {
          // No `row`: the item side panel lays a scrim over the header, so the
          // back button is only hoverable with the panel closed. The row the
          // user had open lives in `from` and is restored by the trip back.
          await items.gotoWithReturn({ from: originHref });
          await items.waitForReady();
        });

        const nav = items.headerReturnNav();

        await test.step('The header back button offers to return to the experiment', async () => {
          await nav.expectBackTooltip('Back to experiment');
          expect(
            await nav.readBackHref(),
            'the back button points at the compare view it was handed',
          ).toBe(originHref);
        });

        await test.step('Following it restores every search param of that view', async () => {
          await nav.clickBackToCompare();
          // Settle on the grid before reading the URL: the compare route writes
          // its own params as it mounts, so a read taken on navigation sees a
          // half-built search and would fail for timing rather than for loss.
          await compare.waitForResultsReady();

          const restored = new URL(page.url());
          expect(restored.pathname, 'back on the compare route').toBe(originPathname);
          // Equality, not containment, in both directions: a view that came
          // back with the search dropped fails, and so does one that came back
          // carrying a param the original never had.
          expect(
            searchParamMap(restored.search),
            'the restored view is the one that was left',
          ).toEqual(originSearch);
        });
      },
    );

    test(
      'the panel\'s Experiment button opens the item the panel is showing',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ comparison, project, page }) => {
        const [expA, expB] = comparison.experiments;
        const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
          expA.experimentId,
          expB.experimentId,
        ]);
        const items = new DatasetItemsPage(page, project.id, comparison.datasetId);

        let renderedIds: string[] = [];

        await test.step('Read the rows the items grid renders', async () => {
          await items.gotoWithReturn({});
          await items.waitForReady();
          await expect(items.itemRows(), 'every seeded item on one page').toHaveCount(
            comparison.itemIds.length,
          );
          renderedIds = await items.itemRowIds();
          // Exhaustive, as a set: the steps below open the FIRST rendered row
          // and assert it stepped to the SECOND, so the grid has to be showing
          // the seeded items and nothing else for those positions to mean
          // anything. Compared sorted because the order is the backend's.
          expect([...renderedIds].sort(), 'the grid shows exactly the seeded items').toEqual(
            [...comparison.itemIds].sort(),
          );
        });

        // The FIRST rendered row, so `Next` is always available — which row the
        // grid puts first is the backend's to decide, but a first row always
        // has a next one.
        const startRow = renderedIds[0];
        const expectedRow = renderedIds[1];

        let originHref = '';

        await test.step('Record a compare view open on that first row', async () => {
          // Two deliberate choices here.
          //
          // `row: startRow` — the point below is that clicking Experiment after
          // stepping goes to the item the PANEL moved to, not the one `from`
          // carries, so the two have to be different items. Pinning `from` to
          // the row the panel starts on guarantees that for any grid order; a
          // fixed seed item would sometimes BE the row the panel steps to, and
          // the assertion would then pass while unable to tell a correct build
          // from a broken one.
          //
          // No `search`, unlike the two tests above: this test steps to a
          // DIFFERENT item, and a search baked into `from` would filter that
          // item out of the compare grid it returns to — making the view that
          // comes back empty for a reason that has nothing to do with the
          // navigation under test.
          await compare.gotoResultsView({ height: 'large', row: startRow });
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

        await test.step('The button names both experiments it would open', async () => {
          // Plural "experiments", both names, in the order `from` listed them.
          await nav.expectExperimentTooltip(
            `View this item in experiments: ${expA.experimentName}, ${expB.experimentName}`,
          );
        });

        await test.step('Step the panel to the next item', async () => {
          const stepped = await nav.stepToNextItem(startRow);
          expect(stepped, 'stepping moved the panel to the next rendered item').toBe(expectedRow);
        });

        await test.step('The button reopens compare on the stepped-to item', async () => {
          await nav.clickExperiment();
          const landed = new URL(page.url());

          expect(landed.searchParams.get('row'), 'compare reopened on the item the panel showed').toBe(
            expectedRow,
          );
          // The same fact stated as the negative, because it is the specific
          // regression the change's last commit fixed: navigating by the `row`
          // baked into `from` rather than by the panel's own item.
          expect(
            landed.searchParams.get('row'),
            'compare did NOT reopen on the row baked into `from`',
          ).not.toBe(startRow);
          expect(
            searchParamMap(landed.search),
            'the rest of the originating view came back with it',
          ).toMatchObject({
            experiments: JSON.stringify([expA.experimentId, expB.experimentId]),
            tab: 'items',
            height: 'large',
          });
        });

        await test.step('And the panel that opened is that item\'s', async () => {
          // The URL alone would be satisfied by a build that navigated correctly
          // and rendered the wrong row. The panel's own outgoing tag names the
          // item it is showing, so reading it back closes that gap.
          const href = await compare.readDatasetItemTagHref();
          expect(
            new URL(href, 'http://tag.invalid').searchParams.get('row'),
            'the row-detail panel is showing the stepped-to item',
          ).toBe(expectedRow);
        });
      },
    );

    test(
      'an absent, off-site or non-compare `from` falls back with no Experiment button',
      { tag: ['@cap:experiments.return-to-experiment'] },
      async ({ comparison, project, page }) => {
        const [expA, expB] = comparison.experiments;
        const compare = new CompareExperimentsPage(page, project.id, comparison.datasetId, [
          expA.experimentId,
          expB.experimentId,
        ]);
        const items = new DatasetItemsPage(page, project.id, comparison.datasetId);
        const openItemId = comparison.itemIds[0];

        let goodFrom = '';

        await test.step('Record a good `from`, to be the control', async () => {
          await compare.gotoResultsView({ height: 'large', row: openItemId });
          goodFrom = toRelativeHref(page.url());
        });

        /**
         * Each case: the `from` to hand the items page, the back-button label it
         * must fall back to, and whether the Experiment button may appear.
         *
         * The GOOD case is in the list on purpose and is not decoration: it runs
         * the same two locators against a build that must show the button, so a
         * zero recorded for the reject cases is a real absence rather than a
         * selector that matches nothing. Without it this whole test would pass
         * against a page that rendered no buttons at all.
         */
        const cases: Array<{
          label: string;
          from: string | null;
          backTooltip: string;
          expectButton: boolean;
        }> = [
          { label: 'a good compare href (control)', from: goodFrom, backTooltip: 'Back to experiment', expectButton: true },
          { label: 'no `from` at all', from: null, backTooltip: 'Back to datasets', expectButton: false },
          {
            label: 'an off-site absolute URL',
            from: `https://evil.example.com/${workspace}/projects/${project.id}/experiments/${comparison.datasetId}/compare`,
            backTooltip: 'Back to datasets',
            expectButton: false,
          },
          {
            // Starts with `/`, so a naive same-origin check passes it, and the
            // browser would read it as a host. The parser's `url.origin` guard
            // is what rejects it.
            label: 'a protocol-relative URL',
            from: `//evil.example.com/${workspace}/projects/${project.id}/experiments/${comparison.datasetId}/compare`,
            backTooltip: 'Back to datasets',
            expectButton: false,
          },
          {
            label: 'an in-app path that is not a compare view',
            from: `/${workspace}/projects/${project.id}/datasets`,
            backTooltip: 'Back to datasets',
            expectButton: false,
          },
          {
            // The interesting middle case, and the only one where the two gates
            // disagree: the href parses, so the back button resolves and is
            // labelled for the experiment — but there is no `experiments` array,
            // so there is nothing for the Experiment button to open and it is
            // correctly hidden. A build that gated both on the same condition
            // fails exactly here and nowhere else above.
            label: 'a valid compare href carrying no `experiments`',
            from: `/${workspace}/projects/${project.id}/experiments/${comparison.datasetId}/compare?tab=items`,
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
              // A second navigation, because the two controls cannot be read in
              // one state: the Experiment button needs the item panel open, and
              // the panel's scrim makes the header back button un-hoverable.
              await items.gotoWithReturn({ from, row: openItemId });
              const nav = await items.itemPanelReturnNav();
              await expect(
                nav.experimentButton,
                `Experiment buttons in the item panel for ${label}`,
              ).toHaveCount(expectButton ? 1 : 0);
            },
          );
        }
      },
    );
  },
);
