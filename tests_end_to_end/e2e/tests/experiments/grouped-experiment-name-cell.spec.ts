import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';
import { loadEnvConfig } from '../../config/env.config';

/**
 * The grouped experiments list's Name cell: plain text, and the WHOLE cell
 * opens the experiment (opik#9620 / OPIK-9253).
 *
 * Two changes in one cell, with opposite failure modes:
 *
 *  - **The restyle.** Under grouping the name used to render through
 *    `ResourceLink`'s `tableLink` button, which is underlined and
 *    `font-medium`. The PR added a `variant: "text"` that keeps the link but
 *    renders a plain `span`. A revert here is cosmetic and permanent — nobody
 *    files it — and it is invisible to every other experiments spec.
 *  - **The widened click target.** `generateDataRowCellDef`'s `CellWrapper`
 *    carried `stopClickPropagation`, which killed every click in the cell and
 *    left the anchor as the only way in. Dropping it lets the rest of the cell
 *    reach `onRowClick`. The two risks are opposite: a cell that no longer
 *    navigates at all, or a widened target that has swallowed the selection
 *    checkbox and navigates away instead of ticking it.
 *
 * Why nothing in the estate could see either. The change lands ONLY on the
 * grouped column — `useExperimentsTableConfig` swaps the select and pin columns
 * for this one cell when `groups.length > 0`, and leaves the ungrouped name
 * column untouched. `ExperimentsPage.goto` pins `groups=[]`, so
 * `expectExperimentNameInList` and `openExperimentById` exercise the
 * unchanged `variant="link"` path. The PR's vitest renders `ResourceCell` with
 * the column's meta and mocks `Link`, so it pins the wiring but asserts nothing
 * about a rendered underline, a real navigation, or a checkbox click.
 *
 * Both click paths are driven on TWO experiments rather than one, because the
 * cell is built per row from that row's own `dataset_id` and `id`: a cell wired
 * to the wrong row's data, or a row handler that navigated to a constant, would
 * satisfy a single-row test completely.
 *
 * Fully deterministic and purely UI — every assertion is a computed style, a
 * `data-state`, or the resulting URL. No assertion results are needed for this
 * flow; the seed is reused from the pass-rate specs because it already builds
 * the one thing this needs, several groups holding evaluation-suite experiments
 * under one project.
 *
 * **Pinned to the Test suites tab, since opik#9611.** That PR split this page
 * into Test suites and Datasets tabs, selected by `evaluation_method`, so the
 * fixture's four groups no longer appear together on any one page: three hold
 * evaluation-suite experiments and the fourth holds only a dataset-method one.
 * Naming the tab is what keeps the counts below CLOSED sets — the alternative,
 * letting the page resolve its own starting tab, would make every count depend
 * on a precedence chain that is somebody else's capability and somebody else's
 * spec. The `mixed` group that every click assertion uses is a suite group, so
 * the Test suites tab is where this spec's subject lives.
 */
test.describe(
  'Experiments — grouped name cell',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    // Same reason as the pass-rate specs: the shared DataTable slices cells to
    // the rendered column window, and the Name cell's width is what the
    // past-the-link strip is measured out of.
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'the name renders as plain cell text, at rest and under the pointer',
      { tag: ['@cap:experiments.grouped-name-cell'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const experiments = new ExperimentsPage(page);

        await openMixedGroup(experiments, seed);

        // Both experiments in the group, so "no underline" is a property of the
        // column rather than of whichever row happened to be looked at.
        for (const experiment of seed.mixed.experiments) {
          await experiments.groups.expectExperimentNamePlainText(
            experiment.id,
            experiment.name,
          );
          await experiments.groups.expectExperimentNamePlainText(
            experiment.id,
            experiment.name,
            { hover: true },
          );
        }
      },
    );

    test(
      "clicking the name opens that row's own compare view",
      { tag: ['@cap:experiments.grouped-name-cell'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const experiments = new ExperimentsPage(page);

        for (const experiment of seed.mixed.experiments) {
          await test.step(`the name of ${experiment.name} lands on its own compare view`, async () => {
            await openMixedGroup(experiments, seed);
            await experiments.groups.clickExperimentName(experiment.id);
            await expectCompareViewFor(page, seed.projectId, seed.mixed.datasetId, experiment.id);
          });
        }
      },
    );

    test(
      'clicking the cell beside the name opens the same compare view',
      { tag: ['@cap:experiments.grouped-name-cell'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const experiments = new ExperimentsPage(page);

        // The widened target, on both rows. This is the arm that goes through
        // `handleRowClick` rather than the anchor, so landing on the SAME route
        // as the test above is the claim — the two code paths are supposed to
        // agree, and nothing but a test asserts that they do.
        for (const experiment of seed.mixed.experiments) {
          await test.step(`the cell beside ${experiment.name} lands on the same compare view`, async () => {
            await openMixedGroup(experiments, seed);
            await experiments.groups.clickNameCellPastTheLink(experiment.id);
            await expectCompareViewFor(page, seed.projectId, seed.mixed.datasetId, experiment.id);
          });
        }
      },
    );

    test(
      'the row checkbox selects without navigating, and shift-click extends the range',
      { tag: ['@cap:experiments.grouped-name-cell'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const experiments = new ExperimentsPage(page);
        // This tab's groups, not all four: the dataset-method group lives on the
        // Datasets tab since opik#9611, so a range over "every seeded
        // experiment" would be a range over rows this page never renders.
        const groups = seed.suiteGroups;
        const rows = groups.flatMap((group) => group.experiments);

        let listUrl = '';
        await test.step('Open the grouped Test suites tab and expand every group', async () => {
          await experiments.gotoTab(seed.projectId, 'testSuites', { grouped: true });
          await experiments.waitForReady();
          await experiments.groups.waitForGroupRows(groups.length);
          for (const group of groups) {
            await experiments.groups.expandGroup(group.datasetName, group.experiments[0].id);
          }
          // Every seeded experiment has a row, so the range below spans a known
          // population rather than whatever happened to be expanded.
          await expect(
            experiments.groups.experimentRows,
            'one row per seeded experiment, across all groups',
          ).toHaveCount(rows.length, { timeout: 30_000 });
          listUrl = page.url();
        });

        const first = experiments.groups.experimentRows.first();
        const last = experiments.groups.experimentRows.last();
        const firstId = await rowExperimentId(first);
        const lastId = await rowExperimentId(last);

        await test.step('Ticking the first row selects it and stays on the list', async () => {
          await experiments.groups.clickRowCheckbox(firstId);
          await experiments.groups.expectRowSelected(firstId, true);
          // The specific regression a widened click target risks: the checkbox
          // handler's own stopPropagation is now the only thing between a tick
          // and `onRowClick`'s navigate().
          expect(page.url(), 'ticking a checkbox must not navigate').toBe(listUrl);
        });

        await test.step('Shift-clicking the last row checks every row in between', async () => {
          await experiments.groups.clickRowCheckbox(lastId, { shift: true });

          // Asserted over the WHOLE rendered set, not just the two clicked:
          // a range that selected only its endpoints, or one that leaked past
          // them, both keep the endpoints checked.
          const states = await experiments.groups.experimentRows.evaluateAll((trs) =>
            trs.map((tr) => ({
              id: tr.getAttribute('data-row-id'),
              state: tr
                .querySelector('button[role="checkbox"]')
                ?.getAttribute('data-state'),
            })),
          );
          expect(states, 'one checkbox state per experiment row').toHaveLength(rows.length);
          expect(
            states.filter((row) => row.state !== 'checked'),
            'every experiment row in the range must be checked',
          ).toEqual([]);

          expect(page.url(), 'shift-clicking a checkbox must not navigate').toBe(listUrl);
        });
      },
    );
  },
);

/** Open the grouped Test suites tab with the two-experiment group expanded. */
async function openMixedGroup(
  experiments: ExperimentsPage,
  seed: {
    projectId: string;
    suiteGroups: { datasetName: string }[];
    mixed: { datasetName: string; experiments: { id: string }[] };
  },
): Promise<void> {
  // `grouped: true` sends no `groups` param, so the tab arrives on its DEFAULT
  // grouping — which is the view this spec is about. `gotoGrouped` would land
  // on whichever tab the page resolves for itself.
  //
  // `suiteGroups`, not `groups`: the fixture derives that split from the seeded
  // evaluation methods, for the reason its own comment gives.
  await experiments.gotoTab(seed.projectId, 'testSuites', { grouped: true });
  await experiments.waitForReady();
  await experiments.groups.waitForGroupRows(seed.suiteGroups.length);
  await experiments.groups.expandGroup(
    seed.mixed.datasetName,
    seed.mixed.experiments[0].id,
  );
}

/**
 * The compare route's pathname for one dataset, built the way the POMs build
 * every other URL in the estate rather than read off the page.
 *
 * Deliberately not assembled from the anchor's own `href`: comparing where a
 * click landed against the link that was clicked is circular, and a cell
 * linking to the wrong row's dataset would satisfy it.
 */
const compareRoutePath = (projectId: string, datasetId: string): string => {
  const env = loadEnvConfig();
  return new URL(
    `${env.baseUrl}/${env.workspace}/projects/${projectId}/experiments/${datasetId}/compare`,
  ).pathname;
};

/**
 * Assert the browser is on the compare view for exactly one experiment.
 *
 * The landing URL grows the compare page's own defaults (`filters`, `height`),
 * so the pathname and the `experiments` param are asserted rather than the
 * whole string. `experiments` is parsed as the JSON array it is: an id that
 * merely appeared somewhere in the query would satisfy a substring match.
 */
async function expectCompareViewFor(
  page: import('@playwright/test').Page,
  projectId: string,
  datasetId: string,
  experimentId: string,
): Promise<void> {
  await page.waitForURL((url) => url.pathname.endsWith('/compare'));
  const url = new URL(page.url());
  expect(url.pathname, "the click must land on this row's dataset compare view").toBe(
    compareRoutePath(projectId, datasetId),
  );
  // Exactly this experiment and nothing else: a handler that carried the
  // group's whole membership would also "contain" the right id.
  expect(
    selectedExperimentIds(url),
    'the compare view must carry exactly the clicked experiment',
  ).toEqual([experimentId]);
}

/** The experiment ids in a compare URL's `experiments` param. */
function selectedExperimentIds(url: URL): string[] {
  const raw = url.searchParams.get('experiments');
  if (raw === null) {
    throw new Error(`the compare URL carried no experiments param: ${url.toString()}`);
  }
  const parsed: unknown = JSON.parse(raw);
  if (!Array.isArray(parsed)) {
    throw new Error(`the experiments param is not a JSON array: ${raw}`);
  }
  return parsed.map(String);
}

/**
 * The experiment id off a rendered row, for the rows a range selection
 * addresses by position rather than by identity.
 *
 * Under grouping `getExperimentRowId` composes the id as
 * `<experimentId>|<grouping field>:<value>|…`, so the experiment's own id is
 * the first segment. The endpoints of a shift-range are inherently positional
 * — "the first and last rendered row" is what the user shift-clicks — so the
 * id is read back out and every subsequent assertion is made by identity.
 */
async function rowExperimentId(row: import('@playwright/test').Locator): Promise<string> {
  const rowId = await row.getAttribute('data-row-id');
  expect(rowId, 'the row carries a data-row-id').not.toBeNull();
  const experimentId = (rowId ?? '').split('|')[0];
  expect(experimentId, 'the row id starts with the experiment id').not.toBe('');
  return experimentId;
}
