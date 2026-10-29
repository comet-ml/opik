import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * A dataset-grid output cell whose prompt has changed keeps its result, dimmed,
 * under a compact note — instead of clearing back to "No runs yet" (opik#8758).
 *
 * This closes a gap the estate wrote down for itself.
 * `playground-dataset-run-failure.spec.ts` says in its own header: *"Also
 * deliberately absent: what a stale failed cell looks like after the prompt is
 * edited. On 2.2.75 the grid CLEARS it to 'No runs yet' while free mode dims it,
 * and which of those is correct is a product decision a human has to settle —
 * pinning either would freeze a guess. See the release exploration report for
 * the evidence."* opik#8758 settles it: the grid dims and explains, like free
 * mode. This is the spec that was waiting on that decision.
 *
 * The grid cell is a different component from the single-prompt panel, with its
 * own `hasOutput` and its own `compact` note, so the two regress apart — which
 * is why this exists as well as, not instead of,
 * `playground-stale-output.spec.ts`. `compact` is the substantive difference and
 * not a styling detail: the cell shows a two-word summary and moves the full
 * sentence into a tooltip, so the grid's explanation is only legible on hover
 * and has to be asserted there.
 *
 * ## Deterministic, with no provider involved
 *
 * The run fails because the seeded provider's base URL refuses the connection
 * before a request is written, so every row fails the same way, immediately —
 * no LLM call, no provider key, no network flake, no wall-clock dependence.
 *
 * ## Why `waitForRunReady` is not reused after the edit
 *
 * It requires EVERY output cell to be the idle placeholder, and a stale cell is
 * deliberately no longer idle — so calling it after the edit would time out
 * rather than returning. The assertions below wait on the cells themselves.
 * Nothing in this spec re-runs, so the question of a run-completion signal over
 * stale cells does not arise; a spec that did re-run would have to solve it.
 */

/** The prefix the failure tag puts in front of the provider's own message. */
const FAILURE_PREFIX = 'Run failed:';

const ORIGINAL_PROMPT = 'Summarise this in one sentence: {{input}}';
const EDITED_PROMPT = 'Summarise this in two sentences: {{input}}';

/** What the compact note shows in the cell, and what its tooltip spells out. */
const COMPACT_SUMMARY = 'Prompt changed';
const FULL_SENTENCE = 'Prompt changed since the last run. Re-run to update results.';

test.describe(
  'Playground — output freshness in the dataset grid',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'Editing the prompt dims every output cell under a compact note instead of clearing it',
      { tag: ['@cap:playground.output-staleness'] },
      async ({ page, project, dataset, providerKeys, testNamespace }) => {
        test.setTimeout(180_000);

        const rowCount = dataset.items.length;
        expect(
          rowCount,
          'more than one row, so a per-cell regression cannot hide behind a single cell',
        ).toBeGreaterThan(1);

        const modelId = await test.step('Seed an unreachable custom provider', async () =>
          providerKeys.createUnreachable({
            providerName: `${testNamespace}-unreachable`,
            modelName: `${testNamespace}-dead-model`,
          }));

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Compose a dataset-templated prompt against the dead model', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: ORIGINAL_PROMPT,
            modelDisplayName: modelId.split('/').pop(),
          });
        });

        await test.step(`Load the ${rowCount}-item dataset and run it`, async () => {
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({
            mode: 'dataset',
            entityName: dataset.name,
          });
          // The grid must really hold this dataset's rows before the run starts:
          // running against an empty item list fails every row client-side for a
          // different reason, which would look identical below.
          await playground.waitForRunReady({ expectedRows: rowCount });
          await playground.clickReRun();
          await playground.waitForRunsComplete({ expectedRows: rowCount, timeoutMs: 120_000 });
        });

        await test.step('Every cell failed, undimmed, with nothing to explain', async () => {
          // The baseline. Every assertion after the edit is about a change, so
          // cells that were already dimmed and already noted would satisfy all
          // of them having proved nothing.
          await expect(
            playground.outputErrorTags(),
            'one failure tag per dataset row',
          ).toHaveCount(rowCount);
          expect(
            await playground.outputErrorOpacities(),
            'every fresh cell is at full opacity',
          ).toEqual(Array(rowCount).fill(1));
          await expect(
            playground.outputCellStaleNotes(),
            'nothing has changed yet',
          ).toHaveCount(0);
        });

        await test.step('Edit the prompt text', async () => {
          await playground.editUserMessage(EDITED_PROMPT);
        });

        await test.step('Every cell keeps its failure tag, now dimmed', async () => {
          // "Keeps" is the assertion the change exists for: the behaviour this
          // replaced cleared the cells, so the user lost results they never
          // invalidated and could not tell the grid from one that had never run.
          await expect(
            playground.outputErrorTags(),
            'the edit took no cell off the grid',
          ).toHaveCount(rowCount);

          const texts = await playground.outputErrorTags().allInnerTexts();
          for (const text of texts) {
            expect(text.trim(), 'each cell still names itself as a failure').toContain(
              FAILURE_PREFIX,
            );
          }

          const opacities = await playground.outputErrorOpacities();
          expect(opacities, 'one opacity reading per row').toHaveLength(rowCount);
          for (const opacity of opacities) {
            expect(
              opacity,
              'each stale cell is visibly dimmed (the level itself is a design token)',
            ).toBeLessThan(1);
          }
        });

        await test.step('Each cell carries the compact note, and no cell reverted', async () => {
          await expect(
            playground.outputCellStaleNotes(),
            'one note per stale cell',
          ).toHaveCount(rowCount);
          await expect(
            playground.outputCellStaleNotes(),
            'the grid shows the summary, not the whole sentence',
          ).toHaveText(Array(rowCount).fill(COMPACT_SUMMARY));
          // The whole point of the change over the previous behaviour: the cell
          // is not cleared. Asserted over the grid rather than per cell, so a
          // single reverted row fails too.
          await expect(
            playground.noRunsYetPlaceholders(),
            'no cell fell back to the never-run placeholder',
          ).toHaveCount(0);
        });

        await test.step('The tooltip is where the grid spells the reason out', async () => {
          // `compact` is why this needs asserting separately: the visible text is
          // two words, so without the tooltip the grid never tells the user to
          // re-run.
          const tooltip = await playground.staleNoteTooltipText(0);
          expect(
            tooltip.replace(/\s+/g, ' '),
            'the tooltip carries the full sentence the panel shows inline',
          ).toBe(FULL_SENTENCE);
        });
      },
    );
  },
);
