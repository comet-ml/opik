import { test, expect, TRACE_INPUT_COLUMN, EXPORT_SELECTION_SIZE } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { parseCsv, csvColumn } from '@e2e/core/export';

/**
 * Exporting a selection while the Logs search box is filled.
 *
 * The export does not serialise the rows already on screen. `ExportToButton`
 * calls one `getData()` which re-issues the list query — under the *current*
 * search term, with `truncate: false` so payloads come back whole — and then
 * intersects the result with the ticked row ids
 * (`TracesSpansTab.getDataForExport`). Two independent reads therefore have to
 * agree about what the search term means, and only one of them is the one the
 * user is looking at.
 *
 * When they disagree the intersection comes back short and the user gets a
 * file quietly missing rows the table was visibly showing. Nothing turns red:
 * no error toast, no empty state, just fewer rows in a CSV nobody diffs. That
 * silence is why this is worth a permanent spec, and it is why every assertion
 * below pins the exact exported rows rather than a count — an export holding
 * the right *number* of wrong rows would read as a pass.
 *
 * The seeded set is deliberately 4 matching + 3 decoys with only 2 ticked, so
 * the three wrong answers are all distinguishable: 7 rows means the search was
 * dropped, 4 means the selection was, 0 or 1 means the term reached the query
 * unnormalised.
 */
test.describe('Traces export under search', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Exporting a selection under a padded search term yields exactly the selected traces',
    { tag: ['@cap:traces.export-traces'] },
    async ({ exportSearchTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { all, matching, decoys, token } = exportSearchTraces;
      const selected = matching.slice(0, EXPORT_SELECTION_SIZE);

      await test.step('Open Logs and confirm all seven seeded traces are listed', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      await test.step(`Search for "${token}" with surrounding whitespace`, async () => {
        await logs.search(` ${token} `);

        // The padded term must narrow to exactly the four matches. If the
        // client sent it through unnormalised the backend would still trim it
        // (StringUtils.trimToNull) and this would pass — the table is not
        // where the divergence shows, which is precisely why the export
        // assertion below is the load-bearing one.
        await expect(logs.traceRows).toHaveCount(matching.length);
        for (const trace of matching) {
          await expect(logs.traceRow(trace.id)).toBeVisible();
        }
        for (const trace of decoys) {
          await expect(logs.traceRow(trace.id)).toBeHidden();
        }
      });

      await test.step('Tick two of the four matching rows', async () => {
        for (const trace of selected) {
          await logs.selectTrace(trace.id);
        }
        await logs.expectSelectedCount(selected.length);
      });

      await test.step('Export as CSV and assert it holds exactly the two selected traces', async () => {
        const csv = parseCsv(await logs.exportSelectedAs('CSV'));

        expect(csv.rows).toHaveLength(selected.length);
        expect(csvColumn(csv, TRACE_INPUT_COLUMN).sort()).toEqual(selected.map((t) => t.input).sort());
      });

      await test.step('Export as JSON and assert the same two traces', async () => {
        // Both menu items share one getData(), so this is not a copy of the
        // assertion above — it is the check that the shared fetch is what was
        // right, rather than the CSV writer.
        // The JSON export keeps the input as the object it was logged as,
        // where the CSV flattens it to a dotted column — same data, so the
        // seeded query string is read out of `input.query` either way.
        const rows = JSON.parse(await logs.exportSelectedAs('JSON')) as Array<{
          input?: { query?: string };
        }>;

        expect(rows).toHaveLength(selected.length);
        expect(rows.map((r) => r.input?.query).sort()).toEqual(
          selected.map((t) => t.input).sort(),
        );
      });
    },
  );

  test(
    'Padding and case in the search term do not change the exported traces',
    { tag: ['@cap:traces.export-traces'] },
    async ({ exportSearchTraces, project, page }) => {
      const logs = new LogsPage(page);
      const { matching, token } = exportSearchTraces;
      const selected = matching.slice(0, EXPORT_SELECTION_SIZE);
      const expectedInputs = selected.map((t) => t.input).sort();

      // The bare term is the control; each variant differs from it only in
      // whitespace, in case, or in both — the two halves of the normalisation
      // the export query is supposed to apply.
      const variants = [token, ` ${token}`, `${token} `, ` ${token.toUpperCase()} `];

      await test.step('Open Logs', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
      });

      for (const variant of variants) {
        await test.step(`Export with the search term ${JSON.stringify(variant)}`, async () => {
          // Each iteration starts with nothing selected, because the selection
          // action bar replaces the filter toolbar and the search box is not on
          // screen while any row is ticked.
          await logs.search(variant);
          await expect(logs.traceRows).toHaveCount(matching.length);

          for (const trace of selected) {
            await logs.selectTrace(trace.id);
          }
          await logs.expectSelectedCount(selected.length);

          const csv = parseCsv(await logs.exportSelectedAs('CSV'));

          expect(csv.rows).toHaveLength(selected.length);
          expect(csvColumn(csv, TRACE_INPUT_COLUMN).sort()).toEqual(expectedInputs);

          await logs.deselectAll();
        });
      }
    },
  );
});
