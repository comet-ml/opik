import { test, expect } from '@e2e/fixtures';
import {
  DATASET_TRUNCATION_SIZE,
  SLIM_STRING_MAX_LENGTH,
  SLIM_TRUNCATION_SUFFIX,
} from '@e2e/fixtures';
import type { CompareItemRef } from '@e2e/core/backend';
import { CompareExperimentsPage } from '@e2e/pom/compare-experiments.page';

/**
 * Exporting experiment-comparison rows downloads the whole value, not the
 * truncated copy the grid renders (OPIK-8125).
 *
 * The export used to reuse the table's own query, so it inherited the table's
 * `truncate` flag and wrote cut values into the file. What makes this worth a
 * permanent test is that it is silent: the grid looks identical either way — it
 * is *supposed* to show truncated text — and the only place the defect appears
 * is inside a downloaded file, on the path people take precisely when they want
 * the data whole. Nothing errors, nothing is missing, and a 1 000-character
 * answer is long enough to look complete.
 *
 * `experiments.export-comparison` was previously uncovered.
 * `experiments-compare.spec.ts` drives the same grid but seeds single-character
 * outputs, so it would pass unchanged against a truncating export.
 *
 * Two limits are exercised because they are two different mechanisms and the
 * fix has to hold for both: the experiment output is slimmed at 1 000
 * characters with a `...` suffix, and the dataset item's data map is cut at
 * 10 001 with no suffix at all. See the fixture for the details.
 *
 * The first step asserts, through the API, that the truncated read really does
 * cut these values before the browser is opened — without it a green export
 * assertion would prove nothing, because a seed that fell under both limits
 * round-trips whole through a broken build too.
 */

/** Every list read the compare grid issues, as its `truncate` query param. */
const COMPARE_ITEMS_PATH = /\/items\/experiments\/items$/;

const rowById = (rows: CompareItemRef[], itemId: string): CompareItemRef => {
  const row = rows.find((candidate) => candidate.id === itemId);
  expect(row, `the compare read returned a row for dataset item ${itemId}`).toBeDefined();
  return row as CompareItemRef;
};

/** The dataset item's `input` as one compare row carries it. */
const datasetInput = (row: CompareItemRef): string => {
  const value = row.data.input;
  expect(typeof value, `row ${row.id} carries a string data.input`).toBe('string');
  return value as string;
};

/** The `output.output` string one compare row carries for its single experiment item. */
const experimentOutput = (row: CompareItemRef): string => {
  expect(row.experimentItems, `row ${row.id} has exactly one experiment item`).toHaveLength(1);
  const output = row.experimentItems[0].output;
  expect(output, `row ${row.id} carries an output payload`).toBeTruthy();
  const value = (output as Record<string, unknown>).output;
  expect(typeof value, `row ${row.id} carries a string output.output`).toBe('string');
  return value as string;
};

/** The rows of a JSON export, asserted into shape rather than cast into it. */
const jsonExportRows = (body: string): Array<Record<string, unknown>> => {
  const parsed: unknown = JSON.parse(body);
  expect(Array.isArray(parsed), 'the JSON export is an array of rows').toBe(true);
  return parsed as Array<Record<string, unknown>>;
};

const exportedField = (row: Record<string, unknown>, key: string): string => {
  const value = row[key];
  expect(typeof value, `the exported row carries a string "${key}"`).toBe('string');
  return value as string;
};

test.describe('Experiment comparison export — truncation', { tag: ['@t2-cuj', '@area:experiments'] }, () => {
  /**
   * Seeding ~28 000 characters of dataset and trace payload, waiting for the
   * experiment linkage to settle, then driving two downloads runs past the
   * default budget against a cloud backend — and the overrun surfaces as a
   * timeout on whichever step happened to be last, which reads like a product
   * failure and is not one. Declared on the describe so it covers fixture setup.
   */
  test.slow();

  test(
    'exporting the selected rows writes the whole value the grid shows truncated',
    { tag: ['@cap:experiments.export-comparison'] },
    async ({ longValueExperiment, project, backendClient, page }) => {
      const { datasetId, experimentId, rows, short, long, huge } = longValueExperiment;

      const readCompareItems = (truncate: boolean): Promise<CompareItemRef[]> =>
        backendClient.listCompareItems({ datasetId, experimentIds: [experimentId], truncate });

      await test.step('The fixture discriminates: the truncated read really does cut these values', async () => {
        // The experiment-item linkage is eventually consistent, so poll the
        // untruncated read until every seeded row is joined to its trace output.
        // Polling the *joined* read covers both writes at once: this shape can
        // only appear once the linkage exists and the trace output is queryable.
        await expect
          .poll(
            async () => {
              const items = await readCompareItems(false);
              return items.filter((item) => item.experimentItems.length === 1).length;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(rows.length);

        const whole = await readCompareItems(false);
        expect(whole, 'the untruncated read returns exactly the seeded rows').toHaveLength(rows.length);
        for (const row of rows) {
          const read = rowById(whole, row.itemId);
          expect(datasetInput(read), `${row.label} input, read untruncated`).toBe(row.input);
          expect(experimentOutput(read), `${row.label} output, read untruncated`).toBe(row.output);
        }

        const cut = await readCompareItems(true);
        expect(cut, 'the truncated read returns exactly the seeded rows').toHaveLength(rows.length);

        // The experiment-output axis: over 1 000 characters is slimmed to
        // 1 000 + a literal "..." suffix.
        for (const row of [long, huge]) {
          const truncatedOutput = experimentOutput(rowById(cut, row.itemId));
          expect(truncatedOutput, `${row.label} output, read truncated`).toHaveLength(
            SLIM_STRING_MAX_LENGTH + SLIM_TRUNCATION_SUFFIX.length,
          );
          expect(truncatedOutput.endsWith(SLIM_TRUNCATION_SUFFIX), `${row.label} output carries the "..." suffix`)
            .toBe(true);
          expect(truncatedOutput, `${row.label} output is not the whole value`).not.toBe(row.output);
        }

        // The dataset-input axis: a different limit, ten times larger, with no
        // suffix — so only the `huge` row is cut, and it is cut to exactly the
        // limit.
        expect(datasetInput(rowById(cut, huge.itemId)), 'huge input, read truncated').toHaveLength(
          DATASET_TRUNCATION_SIZE,
        );
        expect(datasetInput(rowById(cut, long.itemId)), 'long input sits under the dataset limit and is left whole')
          .toBe(long.input);
        expect(experimentOutput(rowById(cut, short.itemId)), 'the short output is left whole either way')
          .toBe(short.output);
      });

      // Record the `truncate` flag of every compare-items list read the page
      // issues, so the export can be asserted at its cause (its own untruncated
      // request) and not only at its symptom (the file's contents).
      const listReadFlags: Array<string | null> = [];
      page.on('request', (request) => {
        const url = new URL(request.url());
        if (COMPARE_ITEMS_PATH.test(url.pathname)) {
          listReadFlags.push(url.searchParams.get('truncate'));
        }
      });

      const compare = new CompareExperimentsPage(page, project.id, datasetId, [experimentId]);

      await test.step('Open the compare Results tab', async () => {
        await compare.gotoResults();
        await compare.waitForResultsReady();
        expect(await compare.countItemRows(), 'every seeded item has a row').toBe(rows.length);
        expect(listReadFlags, 'the grid asks for its rows truncated').toContain('true');
      });

      await test.step('The grid renders the truncated copy, on both axes', async () => {
        const renderedOutput = await compare.readCellText(long.itemId, 'output_output');
        expect(renderedOutput, 'the long output cell shows the slimmed value').toHaveLength(
          SLIM_STRING_MAX_LENGTH + SLIM_TRUNCATION_SUFFIX.length,
        );
        expect(renderedOutput.endsWith(SLIM_TRUNCATION_SUFFIX), 'the long output cell ends in "..."').toBe(true);

        expect(
          await compare.readCellText(huge.itemId, 'data_input'),
          'the huge input cell shows the value cut at the dataset limit',
        ).toHaveLength(DATASET_TRUNCATION_SIZE);
      });

      await test.step('Select the two long rows, leaving the short row unselected', async () => {
        await compare.selectItemRow(long.itemId);
        await compare.selectItemRow(huge.itemId);
      });

      await test.step('The JSON export holds exactly those two rows, whole', async () => {
        const flagsBefore = listReadFlags.length;
        const body = await compare.exportAs('JSON');
        expect(
          listReadFlags.slice(flagsBefore),
          'the export issued its own untruncated read rather than reusing the grid\'s',
        ).toContain('false');

        const exported = jsonExportRows(body);
        expect(exported, 'only the selected rows are exported').toHaveLength(2);

        const byInput = new Map(exported.map((row) => [exportedField(row, 'dataset.input'), row]));
        expect(
          [...byInput.keys()].sort(),
          'the exported inputs are exactly the two selected ones, byte for byte',
        ).toEqual([long.input, huge.input].sort());

        for (const row of [long, huge]) {
          const exportedRow = byInput.get(row.input);
          expect(exportedRow, `the ${row.label} row is in the export`).toBeDefined();
          expect(
            exportedField(exportedRow as Record<string, unknown>, 'output.output'),
            `${row.label} output, exported`,
          ).toBe(row.output);
        }

        expect(body.includes(short.input), 'the unselected row must not leak into the export').toBe(false);
        expect(
          exported.some((row) =>
            Object.values(row).some(
              (value) => typeof value === 'string' && value.endsWith(SLIM_TRUNCATION_SUFFIX),
            ),
          ),
          'no exported value carries a truncation suffix',
        ).toBe(false);
      });

      await test.step('The CSV export holds the same two rows, whole', async () => {
        const flagsBefore = listReadFlags.length;
        const body = await compare.exportAs('CSV');
        expect(
          listReadFlags.slice(flagsBefore),
          'the export issued its own untruncated read rather than reusing the grid\'s',
        ).toContain('false');

        // The seeded values carry no comma, quote or newline, so the writer has
        // nothing to escape and they appear in the file verbatim — which is why
        // a substring check is a fair test of "arrived whole" here.
        expect(body.trim().split('\n'), 'a header row and exactly the two selected rows').toHaveLength(3);
        for (const row of [long, huge]) {
          expect(body.includes(row.input), `${row.label} input, exported whole`).toBe(true);
          expect(body.includes(row.output), `${row.label} output, exported whole`).toBe(true);
        }
        expect(body.includes(short.input), 'the unselected row must not leak into the export').toBe(false);
        expect(body.includes(SLIM_TRUNCATION_SUFFIX), 'no exported value carries a truncation suffix').toBe(false);
      });
    },
  );
});
