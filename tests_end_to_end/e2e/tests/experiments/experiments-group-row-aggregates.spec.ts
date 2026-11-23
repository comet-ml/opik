import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';
import { displayedCost } from '@e2e/core/money';
import { displayedDuration } from '@e2e/core/duration';

/**
 * The grouped Experiments list opik#9374 made the page's default, and what its
 * group rows say (OPIK-8654, 2.2.146).
 *
 * The estate renders this view nowhere. `pom/experiments.page.ts` opens the
 * page with `groups=[]` and documents why: the grouped list gives every
 * experiment row a composite `data-row-id`, so the id-based locators the other
 * experiments specs use only hold on the flat list. Every one of them therefore
 * opts out of the default path, and the default path is what every user sees.
 * 9374's own history shows the aggregation cells churned four times before it
 * shipped.
 *
 * ## The claim worth failing over
 *
 * A group row's aggregation cells come from one place and one place only:
 * `GeneralDatasetsTab` puts `GET /v1/private/experiments/groups/aggregations`
 * into the table as `meta.aggregationMap`, keyed by group row id, and every
 * `aggregatedCell` reads out of that map. So a cell and the endpoint are two
 * halves of one claim, and comparing them is not restating the UI.
 *
 * The valuable half is the blank-versus-dash distinction. The endpoint OMITS an
 * aggregate it has nothing to report, so a group whose experiments carry no
 * priced span has no `total_estimated_cost_avg` key and its Cost cell renders
 * EMPTY — while the experiment rows inside that same group render "-". A group
 * row reading blank where it should read a number is indistinguishable from a
 * group that genuinely has no cost, and only a comparison against that group's
 * own aggregation can tell the two apart. That is what this asserts, in both
 * directions, over one priced group and one unpriced one in the same project.
 *
 * ## Two things the 2.2.146 release report got wrong, pinned here
 *
 *  - The default grouping is by `dataset_id`, and the column it groups on is
 *    LABELLED "Item source" (`ITEM_SOURCE_LABEL`). "Grouped by Item source" and
 *    "one group per dataset" are the same statement, not two.
 *  - The report predicted every group collapsed on first load.
 *    `useExpandingConfig` does start at `expanded: {}`, but
 *    `useExperimentsAutoExpandingLogic` fires `generateAutoExpandMap` straight
 *    after and that map deliberately expands the FIRST group. The third test
 *    below pins exactly that, because it is what decides whether a seeded
 *    experiment is visible without a gesture — which the visual suite's
 *    `06: Experiments page` baseline depends on.
 *
 * Deliberately NOT asserted: Pass rate and Feedback scores on group rows. Both
 * need scored experiment items, and both render empty here — consistent with
 * "no data", which is not the same as having asserted a value. Tagging them
 * would read as coverage of aggregates nobody checked.
 */

/**
 * The grouping cell renders NO item-source prefix on this page.
 *
 * It used to read "Dataset <name> N exps". `ItemSourceGroupCell` renders its
 * prefix only when `hidePrefix` is false, and `useExperimentsTableConfig` sets
 * `hidePrefix: Boolean(itemSourceLabel)` — so once opik#9611 gave the
 * Experiments page's tabs an `itemSourceLabel` of their own ("Dataset" /
 * "Test suite"), the prefix inside the cell became a duplicate of the tab's own
 * label and was suppressed. The prompt detail page's Experiments tab passes no
 * label and so still renders it.
 *
 * Kept as a named constant with this comment rather than inlined, because
 * "the cell does not start with 'Dataset'" is a deliberate expectation here and
 * not an omission someone should helpfully fix.
 */
const GROUP_CELL_PREFIX = '';

test.describe('Experiments — the default grouping and its group-row aggregates', {
  tag: ['@t2-cuj', '@area:experiments'],
}, () => {
  test(
    'the page groups by Item source by default, one row per dataset, labelled and counted',
    { tag: ['@cap:experiments.group-row-aggregates'] },
    async ({ groupedDatasetExperiments, page }) => {
      const { projectId, priced, unpriced, all } = groupedDatasetExperiments;
      const experiments = new ExperimentsPage(page);

      await test.step('Open the Experiments page with no stored grouping', async () => {
        await experiments.gotoDefaultGrouped(projectId);
        await experiments.waitForGroupedReady();
      });

      await test.step('The grouping control reports exactly one active group', async () => {
        await expect(
          experiments.groupsButton,
          'the page must arrive grouped, not flat',
        ).toHaveText('Group (1)');
      });

      await test.step('One group row per dataset, and no others', async () => {
        // The count, not just membership: a grouping that had fallen back to
        // some other field would still produce group rows, and only the count
        // and the labels together say it grouped by dataset.
        await expect(experiments.groupRows, 'group rows in a two-dataset project').toHaveCount(
          all.length,
        );
        for (const group of all) {
          await expect(
            experiments.groupRowFor(group.datasetName),
            `exactly one group row for dataset '${group.datasetName}'`,
          ).toHaveCount(1);
        }
      });

      await test.step('Each group row names its dataset and its own count', async () => {
        // The counts differ between the two groups on purpose, so "N exps" is
        // a verdict rather than a string that happens to be the same for both.
        for (const group of all) {
          const cell = experiments.groupRowCell(group.datasetName, '$__grouping___dataset_id_');
          await expect(cell, `the grouping cell of '${group.datasetName}'`).toHaveCount(1);
          // Polled, because the "N exps" half comes from `aggregationMap`,
          // which `ItemSourceGroupCell` renders only once the
          // groups/aggregations query has answered — so the cell legitimately
          // renders the name alone for a render pass or two, and reading it
          // once races that.
          //
          // Still `innerText`, and not `toHaveText`, which would retry on its
          // own: the name and the count are separate flex children with no
          // whitespace text node between them, so `textContent` (what
          // `toHaveText` compares) runs them together as "<name>2 exps".
          // `innerText` is the rendered text, which is the separation a reader
          // actually sees — and it is what this assertion is about.
          await expect
            .poll(async () => (await cell.innerText()).replace(/\s+/g, ' ').trim(), {
              message: `group row for '${group.datasetName}'`,
              timeout: 30_000,
            })
            .toBe(
              `${GROUP_CELL_PREFIX}${group.datasetName} ${group.experiments.length} ` +
                `${group.experiments.length === 1 ? 'exp' : 'exps'}`,
            );
        }
      });
    },
  );

  test(
    'a group row’s Cost and Duration cells carry that group’s own aggregates, and a group with no cost renders blank while its experiment rows render a dash',
    { tag: ['@cap:experiments.group-row-aggregates'] },
    async ({ groupedDatasetExperiments, backendClient, page }) => {
      const { projectId, priced, unpriced, traceDurationMs } = groupedDatasetExperiments;
      const experiments = new ExperimentsPage(page);

      const aggregations = await test.step('Read the aggregations the table renders out of', async () => {
        return backendClient.getExperimentGroupAggregations({ projectId });
      });

      // Asserted, not optional-chained: the fixture already proved these are
      // present, and a `?? 0` here would turn a missing aggregate into a
      // comparison against a default that the UI would never render.
      const pricedCostAvg = aggregations[priced.datasetId]?.totalEstimatedCostAvg;
      expect(
        pricedCostAvg,
        `the priced group '${priced.datasetName}' must report an average cost`,
      ).toBeDefined();
      expect(
        aggregations[unpriced.datasetId]?.totalEstimatedCostAvg,
        `the unpriced group '${unpriced.datasetName}' must report no average cost — that absence ` +
          'is what the blank cell below is a rendering of',
      ).toBeUndefined();

      await test.step('Open the grouped Experiments page', async () => {
        await experiments.gotoDefaultGrouped(projectId);
        await experiments.waitForGroupedReady();
      });

      await test.step('Show the Cost per trace (avg.) column, which this tab does not by default', async () => {
        // Asked for rather than assumed, since opik#9611: `TAB_CONFIG` gives
        // `total_estimated_cost_avg` to the Test suites tab's default columns
        // and Feedback Scores to the Datasets tab's, and these experiments are
        // dataset-method ones, so the page lands on the tab WITHOUT it. The
        // column is still offered — which `enableColumn` asserts by waiting for
        // its header — so this is a precondition, not a workaround: a tab that
        // had dropped the column from its offered set entirely would fail here
        // rather than in a cell assertion that could never have passed.
        await experiments.enableColumn('Cost per trace (avg.)');
      });

      await test.step('The priced group’s Cost cell names the amount the endpoint reported', async () => {
        const cell = experiments.groupRowCell(priced.datasetName, 'total_estimated_cost_avg');
        await expect(cell, `Cost cell of '${priced.datasetName}'`).toHaveCount(1);
        await expect(cell).toHaveText(displayedCost(pricedCostAvg!));
      });

      await test.step('The unpriced group’s Cost cell is EMPTY, not a dash and not a zero', async () => {
        const cell = experiments.groupRowCell(unpriced.datasetName, 'total_estimated_cost_avg');
        await expect(cell, `Cost cell of '${unpriced.datasetName}'`).toHaveCount(1);
        await expect(cell).toHaveText('');
      });

      await test.step('Both group rows still render their Duration, so blank is specific to cost', async () => {
        // The half that makes the blank Cost cell a statement about cost rather
        // than about a group row that rendered no aggregates at all.
        const expected = displayedDuration(traceDurationMs);
        for (const group of [priced, unpriced]) {
          expect(
            aggregations[group.datasetId]?.durationP50,
            `group '${group.datasetName}' reports a p50`,
          ).toBe(traceDurationMs);
          await expect(
            experiments.groupRowCell(group.datasetName, 'duration_p50'),
            `Duration cell of '${group.datasetName}'`,
          ).toHaveText(expected);
        }
      });

      await test.step('Inside the groups, the experiment rows render a dash and an amount', async () => {
        // The other side of the distinction: the same missing cost reads "-" on
        // a data row and "" on a group row, and a build that rendered one of
        // them the other way would be telling a reader the opposite thing.
        // `ensureGroupExpanded`, not a click: the page auto-expands the first
        // group, so clicking both toggles would close whichever one that is.
        await experiments.ensureGroupExpanded(unpriced.datasetName);
        const unpricedRow = experiments.groupedExperimentRow(unpriced.experiments[0].id);
        await expect(unpricedRow, 'the unpriced group’s experiment row').toHaveCount(1);
        await expect(
          experiments.groupedExperimentCell(
            unpriced.experiments[0].id,
            'total_estimated_cost_avg',
          ),
        ).toHaveText('-');

        await experiments.ensureGroupExpanded(priced.datasetName);
        for (const experiment of priced.experiments) {
          await expect(
            experiments.groupedExperimentRow(experiment.id),
            `the priced group’s experiment row ${experiment.name}`,
          ).toHaveCount(1);
          // Not compared against the group's average: this is the experiment's
          // own cost per trace, and the point here is only that a priced row
          // does NOT read "-" — so "-" above is a fact about the cost rather
          // than what every row in this table says.
          await expect(
            experiments.groupedExperimentCell(experiment.id, 'total_estimated_cost_avg'),
          ).not.toHaveText('-');
        }
      });
    },
  );

  test(
    'the first group arrives expanded, the rest collapsed, and clearing the grouping flattens the table',
    { tag: ['@cap:experiments.group-row-aggregates'] },
    async ({ groupedDatasetExperiments, page }) => {
      const { projectId, all } = groupedDatasetExperiments;
      const experiments = new ExperimentsPage(page);

      await test.step('Open the Experiments page with no stored grouping', async () => {
        await experiments.gotoDefaultGrouped(projectId);
        await experiments.waitForGroupedReady();
        await expect(experiments.groupRows).toHaveCount(all.length);
      });

      const firstGroupName = await test.step('Read which group row is first', async () => {
        // In DOM order rather than by name: which dataset sorts first is the
        // grouping's own ordering ("Sorted by last experiment created"), a
        // separate claim from the auto-expansion this test is about. Pinning
        // the name here would make a sort change read as an expansion bug.
        const text = await experiments.groupRows.first().innerText();
        const match = all.find((group) => text.includes(group.datasetName));
        expect(
          match,
          `the first group row must be one of the seeded datasets (it reads: ${text})`,
        ).toBeDefined();
        return match!.datasetName;
      });

      await test.step('Exactly the first group’s experiment rows are on screen', async () => {
        // `generateAutoExpandMap` expands `flattenGroups[0]` and nothing else.
        // Asserted twice over: the toggle's own state, and the rows that state
        // is supposed to produce. The second is what a user sees; the first is
        // what tells a chevron stuck on the wrong icon from rows that failed
        // to render.
        for (const group of all) {
          const expanded = group.datasetName === firstGroupName;
          expect(
            await experiments.groupIsExpanded(group.datasetName),
            `group '${group.datasetName}' on first load`,
          ).toBe(expanded);
          for (const experiment of group.experiments) {
            await expect(
              experiments.groupedExperimentRow(experiment.id),
              `${experiment.name} is ${expanded ? 'visible under the auto-expanded first group' : 'inside a collapsed group'}`,
            ).toHaveCount(expanded ? 1 : 0);
          }
        }
      });

      await test.step('Expanding a collapsed group reveals its own rows and keeps the other’s', async () => {
        const collapsed = all.find((group) => group.datasetName !== firstGroupName)!;
        await experiments.groupExpandToggle(collapsed.datasetName).click();
        for (const group of all) {
          for (const experiment of group.experiments) {
            await expect(
              experiments.groupedExperimentRow(experiment.id),
              `${experiment.name} after expanding '${collapsed.datasetName}'`,
            ).toHaveCount(1);
          }
        }
      });

      await test.step('Clearing the grouping returns a flat list addressable by experiment id', async () => {
        await experiments.groupsButton.click();
        await page.getByRole('button', { name: 'Clear all' }).click();
        await expect(experiments.groupsButton).toHaveText('Group (0)');
        await page.keyboard.press('Escape');
        await expect(experiments.groupRows, 'no group rows once the grouping is cleared').toHaveCount(
          0,
        );
        for (const group of all) {
          for (const experiment of group.experiments) {
            // The bare-id locator, which only resolves on the flat list — so
            // this asserts the row ids lost their group suffix as well as that
            // the group rows went away.
            await expect(
              experiments.rowById(experiment.id),
              `${experiment.name} on the flat list`,
            ).toHaveCount(1);
          }
        }
      });
    },
  );
});
