import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * Each of the two Experiments tabs renders its own column set (opik#9611):
 * the item source's version under the tab's own label, Pass rate on Test suites
 * only, and feedback/per-score columns on Datasets only.
 *
 * Two things are asserted that the table alone cannot say:
 *
 *   The version CELL is read straight off `dataset_version_summary.version_name`
 *   by `DatasetVersionCell`, so this is written through the API and read back
 *   through the page — an API-only check would miss a cell rendering the dataset
 *   NAME, or a dash, just as happily.
 *
 *   The per-tab exclusions are asserted in the COLUMNS PICKER as well as in the
 *   table, because the table cannot tell "not selected by default" from "not
 *   available". The product means the second: an excluded column is filtered out
 *   of the column set before the picker sees it, so a user cannot re-enable a
 *   column the tab is not meant to have.
 *
 * NOT covered here, and deliberately: Pass rate VALUES. `pass_rate` is computed
 * from assertion rows that only exist after a real test-suite run judged by an
 * LLM, so on a seeded experiment every cell reads "-". The column's presence and
 * its per-tab exclusion are what this spec claims.
 */
test.describe('Experiments — per-tab columns', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  test(
    'The Test suites tab labels the version column for suites, shows Pass rate, and offers no score columns',
    { tag: ['@cap:experiments.tab-columns'] },
    async ({ experimentsTabSplit, backendClient, page }) => {
      const experiments = new ExperimentsPage(page);
      const { mixed } = experimentsTabSplit;
      const suite = mixed.suiteExperiment;

      await test.step('Open the Test suites tab', async () => {
        await experiments.gotoTab(mixed.projectId, 'testSuites');
        await experiments.expectActiveTab('testSuites');
        await experiments.expectListedExperiments([suite.id], 'the Test suites tab');
      });

      await test.step('The version column is labelled for test suites and sits right after Name', async () => {
        const headers = await experiments.columnHeaderLabels();
        const nameAt = headers.indexOf('Name');
        expect(nameAt, `the Name header, among [${headers.join(', ')}]`).toBeGreaterThanOrEqual(0);
        expect(
          headers.slice(nameAt, nameAt + 2),
          'Name followed immediately by the suite-flavoured version header',
        ).toEqual(['Name', 'Test suite version']);
        expect(headers, 'the Datasets tab’s version label must not appear here').not.toContain(
          'Dataset version',
        );
      });

      await test.step('The version cell renders the version the API linked, not the item source’s name', async () => {
        const { experiments: rows } = await backendClient.listExperimentsForProject({
          projectId: mixed.projectId,
          evaluationMethod: { operator: '=', value: 'evaluation_suite' },
        });
        const row = rows.find((candidate) => candidate.id === suite.id);
        expect(row, `experiment ${suite.id} in the = evaluation_suite answer`).toBeDefined();
        const versionName = row!.datasetVersionName;
        expect(
          versionName,
          'dataset_version_summary.version_name on the wire — the cell has nothing to ' +
            'render without it, so an absent value would make the comparison below vacuous',
        ).not.toBeNull();

        expect(
          await experiments.experimentCellText(suite.id, 'dataset_version'),
          'the Test suite version cell against the API’s version_name',
        ).toBe(versionName);
        // The named cell is NOT the item source's name. Stated as its own
        // assertion because that is the plausible wrong value: the column sits
        // where the old Dataset column did, and both come off the same object.
        expect(
          await experiments.experimentCellText(suite.id, 'dataset_version'),
          'the version cell must not be rendering the test suite’s name',
        ).not.toBe(suite.datasetName);
      });

      await test.step('Pass rate is in the table and in the Columns picker', async () => {
        await expect(experiments.columnHeader('Pass rate'), 'the Pass rate header').toHaveCount(1);
        await expect(
          experiments.experimentCell(suite.id, 'pass_rate'),
          'the Pass rate cell of the suite experiment',
        ).toHaveCount(1);
        expect(
          await experiments.columnPickerLabels(),
          'the Columns picker on the Test suites tab',
        ).toContain('Pass rate');
      });

      await test.step('No score column is offered on this tab, in the table or the picker', async () => {
        // The per-score entry is the load-bearing half. The project HAS a
        // feedback score — the fixture asserted the experiments score-names
        // endpoint reports exactly it — so its absence from this tab is the
        // tab's exclusion and not an empty project.
        const picker = await experiments.columnPickerLabels();
        expect(picker, 'the static Feedback Scores column').not.toContain('Feedback Scores');
        expect(
          picker.filter((label) => label.includes(mixed.feedbackScoreName)),
          `per-score entries for '${mixed.feedbackScoreName}' on the Test suites tab`,
        ).toEqual([]);

        const headers = await experiments.columnHeaderLabels();
        expect(headers, 'Feedback Scores must not be a header here').not.toContain(
          'Feedback Scores',
        );
        expect(
          headers.filter((label) => label.includes(mixed.feedbackScoreName)),
          'per-score headers on the Test suites tab',
        ).toEqual([]);
      });
    },
  );

  test(
    'The Datasets tab labels the version column for datasets, shows the scores, and offers no Pass rate',
    { tag: ['@cap:experiments.tab-columns'] },
    async ({ experimentsTabSplit, backendClient, page }) => {
      const experiments = new ExperimentsPage(page);
      const { mixed } = experimentsTabSplit;
      const dataset = mixed.datasetExperiment;

      await test.step('Open the Datasets tab', async () => {
        await experiments.gotoTab(mixed.projectId, 'datasets');
        await experiments.expectActiveTab('datasets');
        await experiments.expectListedExperiments(
          [dataset.id, mixed.legacyExperiment.id],
          'the Datasets tab',
        );
      });

      await test.step('The version column is labelled for datasets and sits right after Name', async () => {
        const headers = await experiments.columnHeaderLabels();
        const nameAt = headers.indexOf('Name');
        expect(nameAt, `the Name header, among [${headers.join(', ')}]`).toBeGreaterThanOrEqual(0);
        expect(
          headers.slice(nameAt, nameAt + 2),
          'Name followed immediately by the dataset-flavoured version header',
        ).toEqual(['Name', 'Dataset version']);
        expect(
          headers,
          'the Test suites tab’s version label must not appear here',
        ).not.toContain('Test suite version');
      });

      await test.step('The version cell renders the version the API linked', async () => {
        const { experiments: rows } = await backendClient.listExperimentsForProject({
          projectId: mixed.projectId,
          evaluationMethod: { operator: '!=', value: 'evaluation_suite' },
        });
        const row = rows.find((candidate) => candidate.id === dataset.id);
        expect(row, `experiment ${dataset.id} in the != evaluation_suite answer`).toBeDefined();
        const versionName = row!.datasetVersionName;
        expect(
          versionName,
          'dataset_version_summary.version_name on the wire',
        ).not.toBeNull();

        expect(
          await experiments.experimentCellText(dataset.id, 'dataset_version'),
          'the Dataset version cell against the API’s version_name',
        ).toBe(versionName);
        expect(
          await experiments.experimentCellText(dataset.id, 'dataset_version'),
          'the version cell must not be rendering the dataset’s name',
        ).not.toBe(dataset.datasetName);
      });

      await test.step('Feedback Scores and the project’s own score column are both offered', async () => {
        await expect(
          experiments.columnHeader('Feedback Scores'),
          'the Feedback Scores header',
        ).toHaveCount(1);
        await expect(
          experiments.experimentCell(dataset.id, 'feedback_scores'),
          'the Feedback Scores cell of the dataset experiment',
        ).toHaveCount(1);

        const picker = await experiments.columnPickerLabels();
        expect(picker, 'the Columns picker on the Datasets tab').toContain('Feedback Scores');
        expect(
          picker.filter((label) => label.includes(mixed.feedbackScoreName)),
          `the per-score entry for '${mixed.feedbackScoreName}', which only this tab offers`,
        ).toHaveLength(1);
      });

      await test.step('Pass rate is in neither the table nor the picker', async () => {
        const headers = await experiments.columnHeaderLabels();
        expect(headers, 'Pass rate must not be a header here').not.toContain('Pass rate');
        await expect(
          experiments.experimentCell(dataset.id, 'pass_rate'),
          'a Pass rate cell on the Datasets tab',
        ).toHaveCount(0);
        expect(
          await experiments.columnPickerLabels(),
          'the Columns picker on the Datasets tab — Pass rate is excluded, not merely unselected',
        ).not.toContain('Pass rate');
      });
    },
  );
});
