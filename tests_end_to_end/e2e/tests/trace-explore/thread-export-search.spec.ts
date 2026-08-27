import { test, expect, THREAD_FIRST_MESSAGE_COLUMN, EXPORT_SELECTION_SIZE } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { parseCsv, csvColumn } from '@e2e/core/export';

/**
 * The Threads actions panel — selecting threads and exporting them — driven
 * with the search box filled.
 *
 * Not a copy of the traces path. `ThreadsActionsPanel` is a different
 * component with a different export column set, and it reads through
 * `GET /v1/private/traces/threads`, where a thread is an aggregate over the
 * traces sharing a thread_id rather than a row of its own. The failure mode is
 * the same shape and just as quiet: `getDataForExport` re-issues the threads
 * query under the current search term and intersects it with the ticked ids,
 * so a term the two reads disagree about yields a file short of rows the table
 * was showing, with no error anywhere.
 *
 * Four matching threads and three decoys, two ticked, so the wrong answers stay
 * distinguishable: 7 exported rows means the search was dropped, 4 means the
 * selection was, fewer than 2 means the term reached the query unnormalised.
 */
test.describe('Threads export under search', { tag: ['@t2-cuj', '@area:threads'] }, () => {
  test(
    'Exporting selected threads under a padded search term yields exactly those threads',
    { tag: ['@cap:threads.thread-actions-panel'] },
    async ({ exportSearchThreads, project, page }) => {
      const logs = new LogsPage(page);
      const { all, matching, decoys, token } = exportSearchThreads;
      const selected = matching.slice(0, EXPORT_SELECTION_SIZE);

      await test.step('Open the Threads tab and confirm all seven seeded threads are listed', async () => {
        // logsType is passed explicitly rather than clicking the tab: the Logs
        // page restores whichever view it was last on, so navigating without it
        // can land on Traces.
        await logs.gotoThreads(project.id);
        await logs.waitForThreadsReady(all[0].threadId);
        await expect(logs.traceRows).toHaveCount(all.length);
      });

      await test.step(`Search for "${token}" with a leading space`, async () => {
        await logs.search(` ${token}`);

        await expect(logs.traceRows).toHaveCount(matching.length);
        for (const thread of matching) {
          await expect(logs.threadRow(thread.threadId)).toBeVisible();
        }
        for (const thread of decoys) {
          await expect(logs.threadRow(thread.threadId)).toBeHidden();
        }
      });

      await test.step('Tick two of the four matching threads', async () => {
        for (const thread of selected) {
          await logs.selectThread(thread.threadId);
        }
        await logs.expectSelectedCount(selected.length);
      });

      await test.step('Export as CSV and assert it holds exactly the two selected threads', async () => {
        const csv = parseCsv(await logs.exportSelectedAs('CSV'));

        expect(csv.rows).toHaveLength(selected.length);
        // The Threads export carries no id column by default — the exported
        // columns are the table's currently selected ones — so identity is
        // asserted on first_message, which the fixture seeds per thread and
        // csvColumn() proves is actually present.
        expect(csvColumn(csv, THREAD_FIRST_MESSAGE_COLUMN).sort()).toEqual(
          selected.map((t) => t.firstMessage).sort(),
        );
      });

      await test.step('Export as JSON and assert the same two threads', async () => {
        const rows = JSON.parse(await logs.exportSelectedAs('JSON')) as Array<{
          first_message?: { query?: string };
        }>;

        expect(rows).toHaveLength(selected.length);
        expect(rows.map((r) => r.first_message?.query).sort()).toEqual(
          selected.map((t) => t.firstMessage).sort(),
        );
      });
    },
  );
});
