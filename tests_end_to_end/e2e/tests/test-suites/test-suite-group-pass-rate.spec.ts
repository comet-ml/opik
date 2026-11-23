import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * The pass rate an Experiments GROUP row shows (opik#9646 / OPIK-9234).
 *
 * The group row's cell is `PassRateCell.Aggregation`, which is a different
 * component, a different payload and a different code path from the
 * per-experiment `PassRateCell` beside it — and this is the first spec in the
 * estate to render a group row at all: both `ExperimentsPage.goto` and
 * `PromptDetailPage.gotoExperimentsTab` pin `groups=[]` because their row
 * locators need the flat list.
 *
 * What was broken, and why it is quiet. The cell read `pass_rate`,
 * `passed_count` and `total_count` off the group aggregations, and the endpoint
 * answers `pass_rate_avg`, `passed_count_sum` and `total_count_sum` — names it
 * has never not answered. So the cell rendered BLANK: a column of empty cells on
 * a page that is otherwise perfectly healthy, which no row-count or
 * page-renders assertion can see. The PR's own vitest hardcodes the new names in
 * its mock, so it cannot catch a rename on either side of the wire; this spec
 * reads the endpoint and the cell in the same test, which can.
 *
 * Three ways to render a plausible but wrong number, all covered here:
 *
 *  - **The pooled ratio instead of the mean.** The group's pass rate is
 *    `avg(pass_rate)` over its experiments, while the tooltip's counts are the
 *    pooled items. The `mixed` group holds a 1/1 and a 1/3 run, so the mean is
 *    67% and the pooled ratio is 50% — a cell wired to the counts cannot pass.
 *  - **Plain rounding instead of the cap.** `Math.round(0.995 * 100)` is 100,
 *    so 199 of 200 items passing rendered "100%" beside a red dot before this
 *    PR's `formatPercent`. The label and the dot are therefore asserted
 *    together, on the group row and on the experiment row inside it.
 *  - **0% instead of nothing.** A group with no evaluation-suite experiment has
 *    no pass rate at all, and the endpoint omits the keys rather than sending
 *    zeroes. The cell must be empty.
 *
 * Everything is REST-seeded with chosen assertion verdicts and no model call —
 * see the `groupedPassRateExperiments` fixture for why a real `opik.run_tests`
 * run could not stand in, and for the server-side precondition check that
 * refuses a seed these assertions could not fail against.
 *
 * **Pinned to a named tab, since opik#9611.** That PR split the Experiments
 * page into Test suites and Datasets tabs on `evaluation_method`, so the seed's
 * four groups no longer appear together on one page: the three suite groups are
 * on Test suites (`seed.suiteGroups`) and `datasetOnly` is on Datasets. Each
 * test therefore names the tab it needs and counts that tab's groups, which is
 * what keeps the group-row counts closed sets rather than making them depend on
 * the page's starting-tab precedence — somebody else's capability and somebody
 * else's spec. The last test is the one the split changed in substance; see its
 * own comment.
 */
test.describe(
  'Test suites — Experiments group pass rate',
  { tag: ['@t2-cuj', '@area:test-suites'] },
  () => {
    // The Pass rate column has to be inside the rendered column window: the
    // shared DataTable slices cells to the visible span, so a narrower viewport
    // can leave the cell out of the DOM entirely.
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'group row shows the mean of the experiments pass rates, not the pooled passed/total',
      { tag: ['@cap:test-suites.group-pass-rate-display'] },
      async ({ page, backendClient, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;

        await test.step('The aggregations endpoint answers the mean, and the pooled counts disagree with it', async () => {
          const groups = await backendClient.experimentGroupAggregations({
            projectId: seed.projectId,
          });

          const mixed = groups[seed.mixed.datasetId];
          expect(mixed, `an aggregation group for item source '${seed.mixed.datasetName}'`).toBeDefined();
          // The whole answer, not just this group: a read that also carried
          // groups from outside the project would mean the page is rendering a
          // population this spec never seeded.
          expect(Object.keys(groups).sort(), 'the groups the endpoint answers').toEqual(
            seed.groups.map((group) => group.datasetId).sort(),
          );

          expect(mixed.aggregations.pass_rate_avg, 'pass_rate_avg on the mixed group').toBe(
            seed.mixed.expectedPassRateAvg,
          );
          expect(mixed.aggregations.passed_count_sum, 'passed_count_sum on the mixed group').toBe(
            seed.mixed.expectedPassedSum,
          );
          expect(mixed.aggregations.total_count_sum, 'total_count_sum on the mixed group').toBe(
            seed.mixed.expectedTotalSum,
          );

          // The names the pre-9646 frontend read. Asserted absent, because that
          // is what makes "the cell renders the mean" a claim about the wire
          // contract rather than about one build's field names: if these ever
          // appeared, a cell reading them would look correct here while being
          // wrong against the endpoint this page actually calls.
          for (const legacy of ['pass_rate', 'passed_count', 'total_count']) {
            expect(
              Object.keys(mixed.aggregations),
              `the group aggregations carry no '${legacy}' key`,
            ).not.toContain(legacy);
          }
        });

        const experiments = new ExperimentsPage(page);
        await test.step('Open the Experiments page on its default grouped view', async () => {
          await experiments.gotoTab(seed.projectId, 'testSuites', { grouped: true });
          await experiments.waitForReady();
          await experiments.groups.waitForGroupRows(seed.suiteGroups.length);
        });

        await test.step('The mixed group reads 67% — the mean — with a failing dot', async () => {
          // 67%, not 50% and not empty: `toHaveText` is an equality, so this one
          // assertion rules out both of the wrong answers.
          expect(seed.mixed.expectedGroupLabel, 'the mean the seed expects').toBe('67%');
          await experiments.groups.expectGroupPassRate(seed.mixed.datasetName, {
            label: seed.mixed.expectedGroupLabel,
            dot: 'failed',
          });
        });
      },
    );

    test(
      'group pill tooltip names the average and reports the pooled item counts',
      { tag: ['@cap:test-suites.group-pass-rate-display'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;

        const experiments = new ExperimentsPage(page);
        await experiments.gotoTab(seed.projectId, 'testSuites', { grouped: true });
        await experiments.waitForReady();
        await experiments.groups.waitForGroupRows(seed.suiteGroups.length);

        // Read on two groups whose means and pooled ratios differ by different
        // amounts. One group could be satisfied by wording that restated the
        // percentage; two cannot, because the counts have to track the group.
        await test.step('On a group whose mean (67%) is not its pooled ratio (50%)', async () => {
          await experiments.groups.expectGroupPassRateTooltip(seed.mixed.datasetName, {
            passed: requireCount(seed.mixed.expectedPassedSum, seed.mixed.datasetName),
            total: requireCount(seed.mixed.expectedTotalSum, seed.mixed.datasetName),
          });
        });

        await test.step('And on the group one item short of perfect', async () => {
          await experiments.groups.expectGroupPassRateTooltip(seed.cap.datasetName, {
            passed: requireCount(seed.cap.expectedPassedSum, seed.cap.datasetName),
            total: requireCount(seed.cap.expectedTotalSum, seed.cap.datasetName),
          });
        });
      },
    );

    test(
      'a group one item short of perfect reads 99%, and an all-passing one reads 100% with a passing dot',
      {
        tag: [
          '@cap:test-suites.group-pass-rate-display',
          '@cap:test-suites.pass-rate-display',
        ],
      },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;

        const experiments = new ExperimentsPage(page);
        await experiments.gotoTab(seed.projectId, 'testSuites', { grouped: true });
        await experiments.waitForReady();
        await experiments.groups.waitForGroupRows(seed.suiteGroups.length);

        const capped = seed.cap.experiments[0];

        await test.step('199 of 200 items reads 99% on the group row, never 100%', async () => {
          // The assertion only means anything if plain rounding would have given
          // 100%, so that premise is stated rather than trusted: this is the one
          // ratio where `Math.round(rate * 100)` and the cap disagree.
          expect(seed.cap.expectedPassRateAvg, "the cap group's mean").toBe(0.995);
          expect(
            Math.round(requireRate(seed.cap.expectedPassRateAvg) * 100),
            'plain rounding would read this group as 100%',
          ).toBe(100);
          expect(seed.cap.expectedGroupLabel, 'the label the cap produces').toBe('99%');

          await experiments.groups.expectGroupPassRate(seed.cap.datasetName, {
            label: seed.cap.expectedGroupLabel,
            dot: 'failed',
          });
        });

        await test.step("And 99% on the capped experiment's own row inside the group", async () => {
          await experiments.groups.expandGroup(seed.cap.datasetName, capped.id);
          expect(capped.expectedRowLabel, "the capped experiment's own label").toBe('99%');
          await experiments.groups.expectExperimentPassRate(capped.id, capped.expectedRowLabel);
        });

        await test.step('A group where every item passed reads 100% with a passing dot', async () => {
          expect(seed.green.expectedPassRateAvg, "the all-passing group's mean").toBe(1);
          await experiments.groups.expectGroupPassRate(seed.green.datasetName, {
            label: '100%',
            dot: 'passed',
          });
        });
      },
    );

    test(
      'a group holding only a dataset-method experiment is never shown a pass rate to get wrong',
      { tag: ['@cap:test-suites.group-pass-rate-display'] },
      async ({ page, backendClient, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;

        await test.step('The endpoint sends no pass-rate keys for that group', async () => {
          const groups = await backendClient.experimentGroupAggregations({
            projectId: seed.projectId,
          });
          const node = groups[seed.datasetOnly.datasetId];
          expect(node, `an aggregation group for '${seed.datasetOnly.datasetName}'`).toBeDefined();
          // Absent, not zero. A zero would be a legitimate pass rate and the
          // cell would be right to render "0%" for it, so the distinction this
          // test is about lives in the keys rather than in their values.
          for (const key of ['pass_rate_avg', 'passed_count_sum', 'total_count_sum']) {
            expect(
              Object.keys(node.aggregations),
              `the dataset-method group carries no '${key}'`,
            ).not.toContain(key);
          }
          // The same read proves the group is otherwise alive, so an empty cell
          // below cannot be a group the endpoint simply failed to answer for.
          expect(node.aggregations.experiment_count, 'the group still reports its experiments').toBe(
            seed.datasetOnly.experiments.length,
          );
        });

        const experiments = new ExperimentsPage(page);

        // Since opik#9611 this group is on the DATASETS tab, and that tab has no
        // Pass rate column at all — `TAB_CONFIG` leaves it out of the defaults
        // and the tab filters it out of the offered column set, so a user cannot
        // re-enable it either (that exclusion is `experiments.tab-columns`'
        // own claim, asserted by experiments-tab-columns.spec.ts).
        //
        // So the UI half of this test changed shape. It used to be "the cell is
        // empty rather than 0%" on a page that showed all four groups together.
        // The page cannot show them together any more, and on the tab where this
        // group lives there is no cell to be wrong in — which is a stronger
        // guarantee of the same thing, and is what is asserted below. The
        // same-render "empty beside a real one" comparison still exists in the
        // estate, on the one surface that still renders both kinds of group
        // together: prompt-experiments-tab-group-pass-rate.spec.ts.
        await test.step('On the Datasets tab, where that group now lives, there is no Pass rate column', async () => {
          await experiments.gotoTab(seed.projectId, 'datasets', { grouped: true });
          await experiments.waitForReady();
          // Its own group row is asserted present FIRST: "no pass rate" must be
          // a fact about the column, not about a table that rendered nothing.
          await experiments.groups.waitForGroupRows(
            seed.groups.length - seed.suiteGroups.length,
          );
          await expect(
            experiments.groupRowFor(seed.datasetOnly.datasetName),
            `the group row for '${seed.datasetOnly.datasetName}'`,
          ).toHaveCount(1);
          await expect(
            experiments.columnHeader('Pass rate'),
            'the Datasets tab must offer no Pass rate column for a dataset-method group',
          ).toHaveCount(0);
        });

        await test.step('While the Test suites tab still renders one, so the column has not simply vanished', async () => {
          // The control. Without it, "no Pass rate column" would be satisfied by
          // a build that had lost the column everywhere — which is the pre-9646
          // defect wearing a different hat.
          await experiments.gotoTab(seed.projectId, 'testSuites', { grouped: true });
          await experiments.waitForReady();
          await experiments.groups.waitForGroupRows(seed.suiteGroups.length);
          await expect(
            experiments.columnHeader('Pass rate'),
            'the Test suites tab still has its Pass rate column',
          ).toHaveCount(1);
          await experiments.groups.expectGroupPassRate(seed.green.datasetName, {
            label: '100%',
            dot: 'passed',
          });
        });
      },
    );
  },
);

/**
 * A pooled count the seed must have.
 *
 * The fixture types these nullable because a dataset-method group genuinely has
 * none, so a caller that needs one has to assert it is there rather than
 * default it — a `?? 0` would turn a seed that never computed its counts into a
 * tooltip assertion about "0 / 0".
 */
function requireCount(value: number | null, group: string): number {
  expect(value, `group '${group}' has a pooled count to assert`).not.toBeNull();
  return value as number;
}

/** The same, for a group's mean pass rate. */
function requireRate(value: number | null): number {
  expect(value, 'the group has a mean pass rate to assert').not.toBeNull();
  return value as number;
}
