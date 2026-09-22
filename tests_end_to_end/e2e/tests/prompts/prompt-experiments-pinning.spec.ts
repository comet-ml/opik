import { test, expect, EXPERIMENT_COUNT, PAGE_SIZE } from '@e2e/fixtures';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * Pinning on the prompt's Experiments tab (OPIK-3345).
 *
 * `prompts.experiments-tab` had no test of any kind, and this change built a
 * new fetch-by-id-and-prepend path on it. Two failure modes, both quiet:
 *
 *  - **Duplication.** A pinned row that is off the current page is fetched by
 *    id and prepended. A pinned row that is already ON the page must not be —
 *    it is already in the row model, and prepending it again renders the same
 *    experiment twice. "It is at the top" is satisfied by both, so the
 *    assertion has to be "exactly once", by row id.
 *  - **A leaked storage key.** Pins here are keyed per PROMPT
 *    (`prompt-experiments-pinned-experiments-<promptId>`), while the project
 *    Experiments page keys them per PROJECT. A wrong key looks perfectly
 *    correct on the surface that wrote it, and surfaces only as a stranger's
 *    row pinned to the top of the other one.
 *
 * Fully seedable and deterministic: twelve experiments linked to one prompt
 * version, no evaluation run, no LLM, no wall-clock dependence. The natural
 * order is read off the tab itself rather than assumed, so the spec asserts
 * pinning without also freezing whatever sort the product chooses to default
 * to.
 */

/** Pins written by the project Experiments page, for the leak check. */
const projectPinsKey = (projectId: string) => `experiments-pinned-experiments-${projectId}`;

/** Pins written by the prompt detail page's Experiments tab. */
const promptPinsKey = (promptId: string) => `prompt-experiments-pinned-experiments-${promptId}`;

/** How many rows page 2 holds at this page size. */
const LAST_PAGE_ROWS = EXPERIMENT_COUNT - PAGE_SIZE;

/**
 * The pins a storage key holds, with "absent" and "present but empty" folded
 * together.
 *
 * Both surfaces declare their key with `useLocalStorageState(..., {
 * defaultValue: [] })`, which writes `[]` on first mount — so whether the key
 * exists at all depends only on whether that page has ever been opened, and
 * says nothing about pinning. The claim worth asserting is the same either
 * way: this key holds no pinned ids. Folding them here rather than at the POM
 * is deliberate — `pinnedIdsInStorage` keeps the distinction for a caller who
 * needs it.
 */
const pins = (stored: string[] | null): string[] => stored ?? [];

test.describe('Prompt — Experiments tab pinning', { tag: ['@t2-cuj', '@area:prompts'] }, () => {
  test.use({ viewport: { width: 1600, height: 900 } });

  test(
    'a pinned experiment is prepended exactly once and stays per-prompt',
    { tag: ['@cap:prompts.experiments-tab'] },
    async ({ page, promptExperiments }) => {
      test.setTimeout(180_000);

      const prompt = new PromptDetailPage(page);
      let pageOneNatural: string[] = [];
      let target = '';

      await test.step('The tab lists the prompt\'s experiments, one page at a time', async () => {
        await prompt.gotoExperimentsTab(promptExperiments.projectId, promptExperiments.promptId, {
          size: PAGE_SIZE,
        });
        await prompt.waitForExperimentRows(PAGE_SIZE);
        pageOneNatural = await prompt.experimentRowIds();
        expect(
          new Set(pageOneNatural).size,
          'page 1 lists ten distinct experiments',
        ).toBe(PAGE_SIZE);
      });

      await test.step('Page 2 holds the rest', async () => {
        await prompt.goToPage('next');
        await prompt.waitForExperimentRows(LAST_PAGE_ROWS);
        const pageTwo = await prompt.experimentRowIds();
        // The two pages must not overlap — an id on both would make every
        // "exactly once" assertion below ambiguous before pinning even starts.
        expect(
          pageTwo.filter((id) => pageOneNatural.includes(id)),
          'no experiment appears on both pages',
        ).toEqual([]);
        target = pageTwo[pageTwo.length - 1];
      });

      await test.step('Pinning a row that is already on this page does not duplicate it', async () => {
        await prompt.setExperimentPinned(target, true);
        await prompt.waitForExperimentRows(LAST_PAGE_ROWS);
        const ids = await prompt.experimentRowIds();
        expect(
          ids.filter((id) => id === target),
          'the pinned row is already loaded here, so it must not also be prepended',
        ).toEqual([target]);
        expect(ids[0], 'it is hoisted to the top of its own page').toBe(target);
      });

      await test.step('Off its page, it is fetched by id and prepended — once', async () => {
        await prompt.goToPage('previous');
        // One more row than the page size: the pinned experiment is an extra,
        // fetched separately, not a replacement for one of page 1's own.
        await prompt.waitForExperimentRows(PAGE_SIZE + 1);
        const ids = await prompt.experimentRowIds();
        expect(ids[0], 'the pinned experiment is the first row').toBe(target);
        expect(
          ids.filter((id) => id === target),
          'and it appears exactly once',
        ).toEqual([target]);
        expect(
          [...ids].sort(),
          'page 1 keeps all of its own rows, each exactly once',
          // Sorted because the assertion is about membership and multiplicity,
          // not about where the pin pushed the others to.
        ).toEqual([...pageOneNatural, target].sort());
      });

      await test.step('The pin is stored against the prompt, not the project', async () => {
        expect(
          await prompt.pinnedIdsInStorage(promptPinsKey(promptExperiments.promptId)),
          'the prompt-scoped key holds the pinned id',
        ).toEqual([target]);
        expect(
          pins(await prompt.pinnedIdsInStorage(projectPinsKey(promptExperiments.projectId))),
          'the project-scoped key holds nothing',
        ).toEqual([]);
      });

      await test.step('The project Experiments page is unaffected', async () => {
        const experiments = new ExperimentsPage(page);
        await experiments.goto(promptExperiments.projectId);
        await experiments.waitForReady();
        await expect(
          experiments.rowById(target),
          'the experiment is listed on the project page',
        ).toHaveCount(1);

        // The project page renders the same shared `DataTable`, so the row and
        // storage readers on the prompt POM read it just as well — they are
        // about the table and localStorage, not about which page mounted them.
        const detail = new PromptDetailPage(page);
        const ids = await detail.experimentRowIds();
        expect(ids.length, 'the project page lists every seeded experiment').toBe(
          EXPERIMENT_COUNT,
        );
        expect(
          ids[0],
          'nothing is pinned here — the prompt tab\'s pin must not have leaked across',
        ).not.toBe(target);
        expect(
          pins(await detail.pinnedIdsInStorage(projectPinsKey(promptExperiments.projectId))),
          'and the project page\'s own pin key still holds nothing',
        ).toEqual([]);
      });

      await test.step('Unpinning returns it to its natural position', async () => {
        await prompt.gotoExperimentsTab(promptExperiments.projectId, promptExperiments.promptId, {
          size: PAGE_SIZE,
        });
        await prompt.waitForExperimentRows(PAGE_SIZE + 1);
        await prompt.setExperimentPinned(target, false);

        await prompt.waitForExperimentRows(PAGE_SIZE);
        expect(
          await prompt.experimentRowIds(),
          'page 1 is back to exactly the rows it started with, in order',
        ).toEqual(pageOneNatural);
        expect(
          await prompt.pinnedIdsInStorage(promptPinsKey(promptExperiments.promptId)),
          'and the prompt-scoped key is emptied',
        ).toEqual([]);
      });
    },
  );
});
