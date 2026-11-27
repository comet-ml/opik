import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';
import { ExperimentsFilterControl } from '@e2e/pom/experiments-filter-control';
import { DatasetsPage } from '@e2e/pom/datasets.page';

/**
 * The "+ Add filter" chip on the project Experiments page (opik#9691 /
 * OPIK-9252).
 *
 * The PR added an `"add"` layout to the shared `FiltersButton` — a pill-shaped
 * chip while no filter is applied, falling back to the funnel-with-count once
 * one is — and opted exactly two call sites into it: this page and the prompt
 * detail page's Experiments tab (covered by its own spec, which shares this
 * fixture).
 *
 * **What a weaker spec would assert, and why it would be worthless.** "The
 * chip is visible" passes on a build where clicking it does nothing, where the
 * filter it builds selects the wrong item source, and where the chip never
 * collapses once a filter is applied. So what is asserted here is the whole
 * state machine — chip, popover, narrowed rows, funnel-with-count, cleared,
 * chip again — and the rows at each stage as a CLOSED set.
 *
 * **Why closed sets.** The failure mode of a filter is a plausible-looking
 * shorter table, not an error. Finding the two expected rows inside the answer
 * would pass equally well over a filter that also left the twelve rows it
 * should have excluded — which is the bug such a spec exists to catch. Every
 * row assertion here compares the WHOLE rendered row id set against a
 * population the fixture already pinned server-side.
 *
 * **Why the counts are 14 / 12 / 2.** They have to be pairwise distinct or the
 * assertions cannot fail: equal-sized sources would make "narrowed to the right
 * one" indistinguishable from "narrowed to the wrong one", and a target holding
 * everything would make it indistinguishable from no filter. The fixture
 * asserts that distinctness rather than leaving it to these numbers.
 *
 * **The negative half.** A shared component is the risk here: the PR left every
 * other `FiltersButton` call site on its old layout, and a change that opted
 * them all in would look identical on this page. So the last test asserts that
 * the project Logs page and the Datasets page render NO chip — while still
 * rendering a filter control, so "no chip" cannot be satisfied by a page that
 * failed to render a toolbar at all.
 *
 * Opened on the Datasets tab explicitly: opik#9611 split this page into Test
 * suites and Datasets tabs, and the seeded experiments are dataset-method ones.
 * Naming the tab keeps the spec about the chip rather than about which tab the
 * page resolves for itself, which is its own capability and its own spec.
 */
test.describe(
  'Experiments — the "+ Add filter" chip',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    // The toolbar has to be inside the rendered window, and the row set has to
    // fit one page: the fixture seeds 14 and the deployment default page size
    // is smaller, so the size is pinned at each `goto`.
    test.use({ viewport: { width: 1600, height: 900 } });

    /** Every seeded experiment fits well inside this, so no row is paged out. */
    const PAGE_SIZE = 50;

    test(
      'the chip filters the list to one item source and collapses to the funnel',
      { tag: ['@cap:experiments.list-filter-chip'] },
      async ({ page, experimentsFilterChip }) => {
        test.setTimeout(180_000);
        const seed = experimentsFilterChip;

        const experiments = new ExperimentsPage(page);
        // This page passes `itemSourceLabel: "Dataset"`, which relabels the
        // column and overrides the picker's placeholder with it.
        const filters = new ExperimentsFilterControl(page, {
          itemSourceLabel: 'Dataset',
          valuePlaceholder: 'Select a dataset',
        });

        await test.step('Open the Datasets tab with no filter applied', async () => {
          await experiments.gotoTab(seed.projectId, 'datasets', { size: PAGE_SIZE });
          await experiments.waitForReady();
          await experiments.expectListedExperiments(
            seed.allExperimentIds,
            'the unfiltered Datasets tab',
          );
        });

        await test.step('The control is the chip, and Group comes before it', async () => {
          await filters.expectChipState();
          await filters.expectGroupBeforeFilterControl();
        });

        await test.step('Narrow to the target item source through the chip', async () => {
          await filters.open();
          await filters.filterByItemSource({
            datasetName: seed.target.datasetName,
            expectedDatasetId: seed.target.datasetId,
          });
          await filters.commitAndClose();
        });

        await test.step('Exactly the target source\'s two experiments remain', async () => {
          await experiments.expectListedExperiments(
            seed.target.experimentIds,
            'the Datasets tab filtered to the target item source',
          );
        });

        await test.step('And the chip has collapsed to the funnel reading 1', async () => {
          await filters.expectFunnelState(1);
        });

        await test.step('Clear all restores the whole population and the chip', async () => {
          await filters.open();
          await filters.clearAll();
          await experiments.expectListedExperiments(
            seed.allExperimentIds,
            'the Datasets tab after Clear all',
          );
          await filters.expectChipState();
        });
      },
    );

    test(
      'a FiltersButton call site left alone still renders the bare funnel',
      { tag: ['@cap:experiments.list-filter-chip'] },
      async ({ page, experimentsFilterChip }) => {
        test.setTimeout(120_000);
        const seed = experimentsFilterChip;

        // The project Datasets page, and deliberately not the Logs page: the
        // exploration checked Logs too, but Logs runs the `filter-chips`
        // components (FilterChipBar / FilterManagerPopover) and no
        // FiltersButton at all, so it could not witness a regression in the
        // component this PR changed. The Datasets page is the same
        // FiltersButton on its old `layout="icon"`, in the same project nav —
        // which is what makes it able to tell "this page opted in" apart from
        // "the shared component changed for everyone".
        const datasets = new DatasetsPage(page);
        await test.step('Open the project Datasets page', async () => {
          await datasets.goto(seed.projectId);
          await datasets.waitForReady();
        });

        await test.step('It renders a filter control, and that control is the funnel', async () => {
          // Asserted PRESENT first: "no chip" would also be true of a page
          // whose toolbar never rendered, which is a different failure and not
          // the one this test is about.
          await expect(
            page.locator('button:has(svg.lucide-filter)'),
            "the Datasets page's own filter control",
          ).toHaveCount(1);
        });

        await test.step('And no "Add filter" chip anywhere on it', async () => {
          await expect(
            page.getByRole('button', { name: 'Add filter', exact: true }),
            'an "Add filter" chip on the Datasets page',
          ).toHaveCount(0);
        });
      },
    );
  },
);
