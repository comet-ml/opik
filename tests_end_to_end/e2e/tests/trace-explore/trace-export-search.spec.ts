import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A downloaded export must contain the rows the table was showing (opik PR 8032).
 *
 * The table and the export are two separate reads of the same endpoint, and
 * before this fix only one of them trimmed and lower-cased the search term — so
 * a term with stray whitespace, which is what pasting or a trailing space gives
 * you, returned one set of rows on screen and a different set in the file. Wrong
 * rows in a downloaded file are silent: nobody reconciles a CSV against the
 * screen they downloaded it from.
 *
 * Seeded names are the only input, so each term's expected set is known before
 * the browser opens — and the assertion is made against that known set as well
 * as against the table, because "the file agrees with the table" would also hold
 * if both were wrong.
 *
 * Export is behind the `EXPORT_ENABLED` feature flag. Where it is off the button
 * is disabled and this fails at the click rather than silently passing.
 */

const UUID_PATTERN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi;

/**
 * Trace ids present anywhere in the exported CSV.
 *
 * The `id` column is the only UUID-shaped field in the export (`thread_id` is
 * unset on these traces), so scanning for the pattern is exact — and it avoids
 * hand-rolling a CSV parser to reach one column past `input`/`output` values
 * that legitimately contain commas and quotes.
 */
function traceIdsInCsv(csv: string): string[] {
  return [...new Set(csv.match(UUID_PATTERN) ?? [])].map((id) => id.toLowerCase());
}

test.describe('Trace export — search term', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Exported rows are exactly the rows the searched table showed',
    { tag: ['@cap:traces.export-traces'] },
    async ({ project, exportSearchTraces, scratchDir, testNamespace, page }) => {
      const logs = new LogsPage(page);

      await test.step('Open Logs with all seeded traces listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(exportSearchTraces.all.length);
      });

      await test.step('Show the ID column so table and export share an identifier', async () => {
        // The Logs table ships with neither ID nor Name selected, and the export
        // is built from the selected columns — so with the defaults the file has
        // no field that identifies a row at all.
        await logs.showColumn('ID');
      });

      const cases = [
        {
          label: 'padded, mixed case',
          term: `  ${testNamespace}-ExportAlpha  `,
          expected: exportSearchTraces.alpha,
        },
        {
          label: 'all upper case',
          term: `${testNamespace}-EXPORTALPHA`,
          expected: exportSearchTraces.alpha,
        },
        {
          label: 'padded, lower case',
          term: `  ${testNamespace}-exportbeta `,
          expected: exportSearchTraces.beta,
        },
      ];

      for (const testCase of cases) {
        await test.step(`Search "${testCase.term}" (${testCase.label}) and export the result`, async () => {
          // A fresh load per term: selecting rows swaps the filter bar (and with
          // it the search box) for the selection actions bar, so the previous
          // case's selection has to be gone before the next term is typed.
          await logs.goto(project.id);
          await logs.waitForReady();
          await logs.search(testCase.term);

          const expectedIds = testCase.expected.map((t) => t.id).sort();

          await expect(
            logs.traceRows,
            `"${testCase.term}" should narrow the table to ${testCase.expected.length} rows`,
          ).toHaveCount(testCase.expected.length);

          const renderedIds = (await logs.readTraceIdsInOrder()).sort();
          expect(renderedIds, 'the table shows exactly the matching traces').toEqual(expectedIds);

          await logs.selectAllRenderedTraces(renderedIds);
          const csv = await logs.exportSelectionAsCsv(scratchDir.path);

          expect(
            traceIdsInCsv(csv).sort(),
            'the exported file holds exactly the rows the table showed',
          ).toEqual(expectedIds);
        });
      }
    },
  );
});
