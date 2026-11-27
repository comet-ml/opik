import { test, expect } from '@e2e/fixtures';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';
import { ExperimentsFilterControl } from '@e2e/pom/experiments-filter-control';

/**
 * The "+ Add filter" chip on prompt detail → Experiments tab (opik#9691 /
 * OPIK-9252), and the Group-before-Filters order the PR's last commit fixed
 * here specifically.
 *
 * The second of the two call sites opik#9691 opted into the shared
 * `FiltersButton`'s `"add"` layout. It is NOT a duplicate of the Experiments
 * page's spec, for three reasons:
 *
 *  1. **A different read.** This tab queries by PROMPT, not by project:
 *     `generatePromptFilters` sends `prompt_ids contains <promptId>` as a
 *     filter against `/projects/{id}/experiments`. So the tab's own narrowing
 *     has to CONJOIN with that filter, and a filter bar that replaced the
 *     query's filters rather than adding to them would empty this tab while
 *     leaving the Experiments page correct.
 *  2. **A different label.** This tab takes the default "Item source" where the
 *     Experiments page passes "Dataset", and the value picker's placeholder is
 *     derived from it. A driver that assumed one page's copy would time out on
 *     the other.
 *  3. **The DOM order is only a fix here.** Before the PR's last commit this
 *     tab rendered Filters BEFORE Group, while the Experiments page rendered it
 *     after. Asserting the order on the page that was already right would
 *     assert nothing.
 *
 * Shares the `experimentsFilterChip` fixture with the Experiments page spec, so
 * the two assert the same fourteen experiments through two different reads
 * rather than two differently-seeded populations. The fixture pins this tab's
 * population — and the same population already narrowed by the same Item source
 * filter — server-side before the browser opens, so a UI assertion of two rows
 * cannot be satisfied by a filter that never reached the wire.
 */
test.describe(
  'Prompts — Experiments tab "+ Add filter" chip',
  { tag: ['@t2-cuj', '@area:prompts'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    /** Every seeded experiment fits well inside this, so no row is paged out. */
    const PAGE_SIZE = 50;

    test(
      'the chip filters the tab to one item source and collapses to the funnel',
      { tag: ['@cap:prompts.experiments-tab-filter-chip'] },
      async ({ page, backendClient, experimentsFilterChip }) => {
        test.setTimeout(180_000);
        const seed = experimentsFilterChip;

        const prompt = new PromptDetailPage(page);
        // This tab passes no `itemSourceLabel`, which decides BOTH strings and
        // not in the same way: the column falls back to "Item source", while
        // the picker keeps the generic `placeholder="value"` that `StringRow`
        // hands every value component — because the per-row override that would
        // have replaced it is only built when a label is given. Hence "value"
        // here and "Select a dataset" on the Experiments page.
        const filters = new ExperimentsFilterControl(page, {
          itemSourceLabel: 'Item source',
          valuePlaceholder: 'value',
        });

        await test.step('Open the prompt Experiments tab with no filter applied', async () => {
          await prompt.gotoExperimentsTab(seed.projectId, seed.promptId, { size: PAGE_SIZE });
          await prompt.waitForExperimentRows(seed.allExperimentIds.length);
          expect(
            (await prompt.experimentRowIds()).sort(),
            'the unfiltered Experiments tab lists exactly the prompt\'s experiments',
          ).toEqual([...seed.allExperimentIds].sort());
        });

        await test.step('The control is the chip, and Group comes before it', async () => {
          await filters.expectChipState();
          // The half of opik#9691 that is only a fix on this page.
          await filters.expectGroupBeforeFilterControl();
        });

        await test.step('Narrow to the target item source through the chip', async () => {
          await filters.open();
          await filters.filterByItemSource({
            datasetName: seed.target.datasetName,
            expectedDatasetId: seed.target.datasetId,
          });
          await filters.commitAndClose();
        });

        await test.step("Exactly the target source's two experiments remain", async () => {
          await prompt.waitForExperimentRows(seed.target.experimentIds.length);
          // The whole rendered set, not "mine are in there": the failure this
          // catches is a filter that narrowed to the wrong source or not at
          // all, and both leave a table that looks perfectly healthy.
          expect(
            (await prompt.experimentRowIds()).sort(),
            'the rows the filtered Experiments tab lists',
          ).toEqual([...seed.target.experimentIds].sort());
        });

        await test.step('And the chip has collapsed to the funnel reading 1', async () => {
          await filters.expectFunnelState(1);
        });

        await test.step('The prompt filter is still conjoined, not replaced', async () => {
          // The claim this page can make and the Experiments page cannot. If the
          // bar had replaced the query's filters with its own, the tab would
          // have shown every project experiment on the target source rather
          // than only this prompt's — identical on screen here, because the
          // fixture links all fourteen to the one prompt. So it is asserted
          // where it is observable: the server's own answer for prompt AND
          // item source is exactly the two rows the page is showing.
          const narrowed = await backendClient.listExperimentsForPrompt({
            projectId: seed.projectId,
            promptId: seed.promptId,
            datasetId: seed.target.datasetId,
          });
          expect(narrowed.total, 'the prompt-and-item-source population').toBe(
            seed.target.experimentIds.length,
          );
          expect([...narrowed.ids].sort(), 'its ids').toEqual(
            [...seed.target.experimentIds].sort(),
          );
        });

        await test.step('Clear all restores the whole population and the chip', async () => {
          await filters.open();
          await filters.clearAll();
          await prompt.waitForExperimentRows(seed.allExperimentIds.length);
          expect(
            (await prompt.experimentRowIds()).sort(),
            'the rows after Clear all',
          ).toEqual([...seed.allExperimentIds].sort());
          await filters.expectChipState();
        });
      },
    );
  },
);
