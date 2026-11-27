import { test, expect } from '@e2e/fixtures';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/** What the search term narrows the four seeded experiments down to. */
const SEARCH_TERM = 'cmp-a';
const MATCHING_ROWS = 3;

/**
 * The Experiments list's selection action bar, and the toolbar row it replaces
 * (opik#9747, OPIK-9128).
 *
 * Ticking a row swaps the whole toolbar — search, Group, Filters, Columns and
 * Refresh — for a `Selected: N` bar carrying the bulk actions, the way
 * TracesSpansTab already did. Deselecting swaps it back.
 *
 * WHAT THIS ADDS. The swap is new on this page and it is CONDITIONAL
 * (`selectedRows.length > 0 ? bar : toolbar`), which makes two things fail
 * quietly:
 *
 *  - State loss. The search box is unmounted while the bar is up, so a user
 *    who has narrowed the list and then ticks two rows is acting on a list
 *    whose filter they can no longer see. If the swap dropped the `?search=`
 *    param with the input, the list would silently widen under the selection —
 *    and a bulk Delete would then be offered over rows the user had filtered
 *    away. Restoring the toolbar must bring the term back, not an empty box.
 *  - A layout shift. The bar is padded to match the row it replaces
 *    (`py-2` plus the host's own padding), so the table below must not move.
 *    A shift looks correct in a screenshot of either state and shows up only
 *    as the list jumping under the cursor mid-click.
 *
 * Asserted as one journey rather than three tests because the claim is about
 * a round trip: "the controls came back" is only worth anything measured
 * against the same page that lost them.
 *
 * Reuses `compareButtonExperiments` — four experiments over two datasets, with
 * three of them (`-cmp-a1`…`-cmp-a3`) sharing a name fragment the fourth
 * (`-cmp-b1`) does not, which is what makes a search that narrows the list to
 * a known count possible without seeding anything extra.
 */
test.describe(
  'Experiments — the selection bar replaces the toolbar row',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'ticking rows hides the toolbar controls and deselecting restores them with the search intact',
      { tag: ['@cap:experiments.list-experiments'] },
      async ({ compareButtonExperiments, page }) => {
        const seed = compareButtonExperiments;
        const experiments = new ExperimentsPage(page);
        const controls = experiments.toolbarControls;

        await test.step('Open the Datasets tab and narrow the list by search', async () => {
          await experiments.goto(seed.projectId, { tab: 'datasets' });
          await experiments.waitForReady();
          await expect(
            experiments.rows,
            'all four seeded experiments before the search',
          ).toHaveCount(seed.datasetA.length + 1);
          await experiments.search(SEARCH_TERM, MATCHING_ROWS);
        });

        for (const [label, control] of Object.entries(controls)) {
          await expect(control, `${label} is in the toolbar before any selection`).toBeVisible();
        }
        const tableTopBefore = await experiments.tableTop();

        await test.step('Tick two rows: the bar appears and the toolbar goes', async () => {
          await experiments.selectExperiments([seed.datasetA[0].id, seed.datasetA[1].id]);
          await expect(
            experiments.selectedCountLabel,
            'the bar must count the ticked rows',
          ).toHaveText('Selected: 2');
          await expect(
            experiments.deselectAllButton,
            'the bar must offer a way out of the selection',
          ).toBeVisible();
          for (const [label, control] of Object.entries(controls)) {
            await expect(
              control,
              `${label} must be gone while the selection bar is up`,
            ).toHaveCount(0);
          }
        });

        await test.step('The list is still narrowed, and the table has not moved', async () => {
          // The input is hidden; the filter is not dropped. Read off the URL
          // as well as the row count, because a list that happened to hold
          // three rows for another reason would satisfy the count alone.
          expect(
            new URL(page.url()).searchParams.get('search'),
            'the search term must survive the toolbar being swapped out',
          ).toBe(SEARCH_TERM);
          await expect(
            experiments.rows,
            'the list must stay narrowed while rows are selected',
          ).toHaveCount(MATCHING_ROWS);
          expect(
            await experiments.tableTop(),
            'the bar replaces the toolbar row and must not shift the table',
          ).toBe(tableTopBefore);
        });

        await test.step('Deselect all: the toolbar comes back, still holding the term', async () => {
          await experiments.deselectAll();
          for (const [label, control] of Object.entries(controls)) {
            await expect(control, `${label} must be back after deselecting`).toBeVisible();
          }
          await expect(
            experiments.searchInput,
            'the restored search box must hold the term, not be empty',
          ).toHaveValue(SEARCH_TERM);
          await expect(
            experiments.rows,
            'and the list must still be narrowed by it',
          ).toHaveCount(MATCHING_ROWS);
          await expect(
            page.getByRole('checkbox', { name: 'Select row', checked: true }),
            'Deselect all must leave no row ticked',
          ).toHaveCount(0);
          expect(
            await experiments.tableTop(),
            'and the table must be back where it started',
          ).toBe(tableTopBefore);
        });
      },
    );
  },
);
