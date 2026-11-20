import { test, expect, SCORED_GROUP_SCORE_NAME } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * The Feedback Scores aggregate on a group row of the Experiments list in its
 * DEFAULT grouped state, and the two permission-gated reads that state makes
 * (opik#9492, OPIK-9159, 2.2.147).
 *
 * ## Why this exists beside `experiments-group-row-aggregates.spec.ts`
 *
 * That spec covers the same view and names its own gap in as many words:
 * "Deliberately NOT asserted: Pass rate and Feedback scores on group rows. Both
 * need scored experiment items, and both render empty here." The taxonomy note
 * for `experiments.group-row-aggregates` repeats it as NOT covered. This is the
 * feedback-score half, with the seed it needs — reusing that spec's page object
 * wholesale, because the grouped-row locators are the part that was expensive.
 *
 * ## Why it is a release-2.2.147 spec and not a tidy-up
 *
 * `findExperimentGroups` and `findGroupsAggregations` are two of the 45
 * endpoints opik#9492 newly put behind `@RequiredPermissions` — here
 * `EXPERIMENT_VIEW`. The release report's own finding is that the whole PR is
 * INERT on the estate's default OSS target, because `AuthServiceImpl` never
 * reads `contextInfo.requiredPermissions()` when `authentication.enabled=false`
 * — so a green OSS run says nothing about it. On a cloud-configured target the
 * annotations are live, and a gate naming a permission that does not exist, or
 * one no role holds, answers 403 on the daily path. The first test below asserts
 * both statuses explicitly for exactly that reason, and the client helper
 * returns the status rather than throwing on it so a 403 reads as a failed
 * comparison naming the endpoint rather than as a fixture error.
 *
 * Driven through the UI as well as the API, because the two halves disagree
 * independently: `GeneralDatasetsTab` puts the aggregations response into the
 * table as `meta.aggregationMap` keyed by group row id, and
 * `FeedbackScoreListCell.Aggregation` renders out of that map and nothing else.
 * A cell and that endpoint are therefore two halves of one claim rather than a
 * restatement of each other.
 *
 * ## The claim worth failing over
 *
 * A group row's feedback average is the mean of its experiments' OWN averages,
 * over the experiments that have the score — not the mean over their traces,
 * and not a mean that counts an unscored experiment as a zero. The fixture
 * seeds five traces at 1.0, two at 0.0 and one unscored precisely so the three
 * readings give three different answers (0.5, 0.71, 0.33), and proves each
 * experiment's own average and the group's population by API before the browser
 * opens. A wrong denominator here is the least visible kind of wrong: the cell
 * still renders a plausible number, under the right name, on the page every
 * user lands on.
 *
 * Deliberately NOT asserted: Pass rate on group rows, which needs experiments
 * with pass/fail items rather than scored ones and is a separate seed; and the
 * dynamic per-score columns (`feedback_scores.<name>`), which are off by
 * default and are their own claim.
 */

/**
 * What the UI renders for a score value.
 *
 * Mirrors `formatScoreDisplay` in `apps/opik-frontend/src/lib/feedback-scores.tsx`
 * — `String(round(value, SCORE_DISPLAY_PRECISION))` at precision 2. Mirrored
 * rather than approximated because the assertion is an exact cell comparison,
 * and `toFixed(2)` would read "0.50" where the page shows "0.5": JS number
 * formatting drops the trailing zero.
 *
 * Local to this spec, following `displayedDuration` in the sibling spec — if a
 * second score assertion lands, this moves to `core/`.
 */
const displayedScore = (value: number): string => String(Math.round(value * 100) / 100);

/** The default grouping's prefix for a group whose value is a dataset. */
const GROUP_PREFIX = 'Dataset';

test.describe(
  'Experiments — the feedback-score aggregate on a default-grouped group row',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'both permission-gated group reads answer 200, and the group row counts every experiment it expands to',
      { tag: ['@cap:experiments.group-row-aggregates'] },
      async ({ scoredGroupExperiments, backendClient, page }) => {
        const { projectId, datasetId, datasetName, all, traceCount } = scoredGroupExperiments;

        await test.step('GET /experiments/groups answers 200 and names the seeded group', async () => {
          // The status, asserted rather than assumed: this endpoint is one of
          // opik#9492's newly annotated ones (EXPERIMENT_VIEW), and a wrong or
          // unknown permission name answers 403 on the page's default load.
          const response = await backendClient.getExperimentGroups({ projectId });
          expect(
            response.status,
            `GET /v1/private/experiments/groups must answer 200 for a permitted caller ` +
              `(got ${response.status}: ${response.message})`,
          ).toBe(200);
          expect(response.groups, 'the 200 must carry a content envelope').not.toBeNull();
          // The whole answer, not just membership: a fresh project holding a
          // second group means the grouping picked up something unseeded and
          // every count below is about a different population.
          expect(
            response.groups,
            'the fresh project holds exactly the one seeded group, labelled by its dataset',
          ).toEqual({ [datasetId]: datasetName });
        });

        const aggregations = await test.step(
          'GET /experiments/groups/aggregations answers 200 with this group’s numbers',
          async () => {
            // `getExperimentGroupAggregations` throws on any non-200, so
            // reaching the assertions below is the status claim for this one.
            const agg = await backendClient.getExperimentGroupAggregations({ projectId });
            expect(Object.keys(agg), 'exactly the seeded group is aggregated').toEqual([datasetId]);
            return agg[datasetId];
          },
        );

        const experiments = new ExperimentsPage(page);

        await test.step('Open the Experiments page with no stored grouping', async () => {
          await experiments.gotoDefaultGrouped(projectId);
          await experiments.waitForGroupedReady();
        });

        await test.step('The page arrives grouped, with one row for the seeded dataset', async () => {
          await expect(
            experiments.groupsButton,
            'the page must arrive grouped, not flat',
          ).toHaveText('Group (1)');
          await expect(experiments.groupRows, 'group rows in a one-dataset project').toHaveCount(1);
          await expect(
            experiments.groupRowFor(datasetName),
            `exactly one group row for dataset '${datasetName}'`,
          ).toHaveCount(1);
        });

        await test.step('The group row’s label counts all three experiments, unscored included', async () => {
          // The count the API reported and the count the label renders, in one
          // step: the first is what the second is supposed to be a rendering
          // of, and the unscored experiment being inside this number is what
          // makes its absence from the average a claim about the AVERAGE.
          expect(
            aggregations.experimentCount,
            'the aggregation counts every experiment in the group',
          ).toBe(all.length);
          expect(
            aggregations.traceCount,
            "the aggregation counts the unscored experiment's trace too",
          ).toBe(traceCount);

          const cell = experiments.groupRowCell(datasetName, '$__grouping___dataset_id_');
          await expect(cell, `the grouping cell of '${datasetName}'`).toHaveCount(1);
          const text = (await cell.innerText()).replace(/\s+/g, ' ').trim();
          expect(text, `group row for '${datasetName}'`).toBe(
            `${GROUP_PREFIX} ${datasetName} ${all.length} exps`,
          );
        });

        await test.step('The group expands to exactly the three seeded experiment rows', async () => {
          await experiments.ensureGroupExpanded(datasetName, GROUP_PREFIX);
          for (const experiment of all) {
            await expect(
              experiments.groupedExperimentRow(experiment.id),
              `${experiment.name} inside the expanded group`,
            ).toHaveCount(1);
          }
        });
      },
    );

    test(
      'the group row averages its experiments’ own scores and ignores the unscored one, while that experiment’s row renders a dash',
      { tag: ['@cap:experiments.group-row-aggregates'] },
      async ({ scoredGroupExperiments, backendClient, page }) => {
        const { projectId, datasetId, datasetName, high, low, unscored, expectedGroupAverage } =
          scoredGroupExperiments;

        const groupAverage = await test.step(
          'The aggregation the table renders out of reports the mean over the SCORED experiments',
          async () => {
            const aggregations = await backendClient.getExperimentGroupAggregations({ projectId });
            const scores = aggregations[datasetId]?.feedbackScores;
            // Asserted, not optional-chained: the fixture already proved this
            // map is present and holds exactly one name, and a `?? 0` here
            // would compare the cell against a default the UI never renders.
            expect(scores, `group '${datasetName}' must report a feedback-score map`).toBeDefined();
            expect(
              Object.keys(scores!),
              'exactly the one seeded score name, so the cell comparison below is a whole answer',
            ).toEqual([SCORED_GROUP_SCORE_NAME]);

            const value = scores![SCORED_GROUP_SCORE_NAME];
            // The arithmetic claim. 0.5 is the mean of the two per-experiment
            // means; 5/7 = 0.714… would be the mean over the seven scored
            // TRACES, and 1/3 = 0.333… would be a mean that counted the
            // unscored experiment as a zero. The fixture's asymmetric trace
            // counts are what make these three numbers different.
            expect(
              value,
              `the group average must be the mean of the per-experiment means ` +
                `((${high.scoreValue} + ${low.scoreValue}) / 2 = ${expectedGroupAverage}), not a ` +
                'mean over scored traces and not one that counts the unscored experiment as zero',
            ).toBe(expectedGroupAverage);
            return value;
          },
        );

        const experiments = new ExperimentsPage(page);

        await test.step('Open the Experiments page with no stored grouping', async () => {
          await experiments.gotoDefaultGrouped(projectId);
          await experiments.waitForGroupedReady();
        });

        await test.step('The group row’s Feedback Scores cell names that average, exactly', async () => {
          // `FeedbackScoreListCell.Aggregation` joins every entry of the map as
          // `"<name> (avg): <value>"`, so an exact comparison asserts the whole
          // cell rather than that the right number appears somewhere in it.
          const cell = experiments.groupRowCell(datasetName, 'feedback_scores');
          await expect(cell, `Feedback Scores cell of '${datasetName}'`).toHaveCount(1);
          await expect(cell).toHaveText(
            `${SCORED_GROUP_SCORE_NAME} (avg): ${displayedScore(groupAverage)}`,
          );
        });

        await test.step('Inside the group, the unscored experiment’s row renders a dash and the scored ones do not', async () => {
          // The other side of the distinction: the same absent score reads "-"
          // on a data row, where the group row's average simply left it out.
          // Without this half, a group average that had silently dropped a
          // scored experiment would look the same as one that correctly
          // excluded an unscored one.
          // `ensureGroupExpanded`, not a click: the page auto-expands the first
          // group, so clicking its toggle would close it.
          await experiments.ensureGroupExpanded(datasetName, GROUP_PREFIX);

          const unscoredCell = experiments.groupedExperimentCell(unscored.id, 'feedback_scores');
          await expect(
            unscoredCell,
            `Feedback Scores cell of the unscored experiment ${unscored.name}`,
          ).toHaveCount(1);
          await expect(unscoredCell).toHaveText('-');

          for (const experiment of [high, low]) {
            const cell = experiments.groupedExperimentCell(experiment.id, 'feedback_scores');
            await expect(
              cell,
              `Feedback Scores cell of the scored experiment ${experiment.name}`,
            ).toHaveCount(1);
            // "Not a dash" rather than the tag's own text, and the limit is the
            // CELL and not the data: `FeedbackScoreListCell` runs its score
            // tags through `useVisibleItemsByWidth`, which at the Feedback
            // Scores column's default width renders none of them and shows the
            // overflow counter "+1" instead. Comparing text here would be
            // asserting a column width, and would redden the day someone
            // changes the default — while the VALUES on these two rows are
            // already pinned by API in the fixture (1 and 0, read off each
            // experiment's own aggregate) and the group row's own cell is
            // compared exactly above.
            await expect(cell).not.toHaveText('-');
            await expect(cell, 'a scored row renders something, not an empty cell').not.toHaveText(
              '',
            );
          }
        });
      },
    );
  },
);
