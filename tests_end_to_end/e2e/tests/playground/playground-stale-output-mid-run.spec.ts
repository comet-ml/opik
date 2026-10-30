import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Editing the prompt while a dataset run is IN FLIGHT marks the grid stale
 * without resetting it to "No runs yet", and leaves the run running
 * (opik#9166).
 *
 * `playground-stale-output-dataset-grid.spec.ts` covers the SETTLED case: a
 * finished run, then an edit, then dimmed cells under a compact note. This is
 * the case this release made. 2.2.119 moved the dataset run server-side, so the
 * page no longer owns the work it is displaying — the cells are rendered from
 * experiment items the backend is still writing, and the staleness flag is
 * computed over a prompt the user has just changed under them.
 *
 * Two ways that can go wrong, and this asserts both away:
 *
 *  - the grid RESETS to "No runs yet". That is the behaviour opik#8758
 *    replaced, and a reset mid-run is worse than a reset after one: the user
 *    is left looking at a grid indistinguishable from one where nothing has
 *    ever been started, while a run is in fact still going.
 *  - the EDIT ENDS the run. An in-flight server-side run has no browser-side
 *    anchor any more, so a page that reset its output state on an edit could
 *    drop the only handle the user has — no Stop, no progress — while the
 *    backend keeps working through the dataset.
 *
 * ## What this does NOT claim, deliberately
 *
 * Not that the cells keep their previous RESULT. They do not, and they should
 * not: the second run is a re-run of the same rows, and clearing the result it
 * is about to replace is what a re-run means. The exploration candidate this
 * spec came from asked for "every cell still holds its previous output under a
 * stale note", which conflates the edit with the re-run — a finished result
 * surviving an edit is the settled case, and the spec above already pins it.
 * What survives here is the grid's STATE: noted as stale, and not reset.
 *
 * The exploration also read these cells with the dark theme applied. That is
 * left out: the dimming is an `opacity` the estate asserts as a computed style
 * rather than a class (see `outputErrorOpacities`), and opacity does not vary
 * by theme — so a dark-mode repeat would re-assert the same resolved number
 * while adding the first theme-switching machinery in the estate, and a
 * `localStorage` write to restore afterwards, for no extra claim. That belongs
 * in a visual test, which is a different suite.
 *
 * ## Why the two providers
 *
 * The first run must FINISH, so its provider refuses every connection and each
 * row fails in milliseconds — which is what gets the grid out of its
 * never-run state. The second must still be going when the edit lands, so its
 * provider is blackholed (TEST-NET-1, routed nowhere) and the connect hangs.
 * Both are `custom-llm` providers seeded through the API: nothing is generated,
 * nothing is billed, and every assertion is about the grid rather than about
 * what a model said.
 *
 * `waitForRunReady` is not reused after the first run, for the reason the
 * settled spec gives: it requires every cell to be the idle placeholder, and
 * neither a filled nor a stale cell is idle, so it would time out rather than
 * return.
 */

/** The prefix the failure tag puts in front of the provider's own message. */
const FAILURE_PREFIX = 'Run failed:';

const ORIGINAL_PROMPT = 'Summarise this in one sentence: {{input}}';
const EDITED_PROMPT = 'Summarise this in two sentences: {{input}}';

/** What the compact note shows in a grid cell. */
const COMPACT_SUMMARY = 'Prompt changed';

const FINISHING_MODEL = 'unreachable-model';
const HANGING_MODEL = 'unresponsive-model';

test.describe(
  'Playground — output freshness while a dataset run is in flight',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'editing the prompt mid-run notes every cell stale without resetting it, and leaves the run running',
      { tag: ['@cap:playground.output-staleness'] },
      async ({
        page,
        project,
        dataset,
        providerKeys,
        backendClient,
        registerExperimentCleanup,
        testNamespace,
      }) => {
        test.setTimeout(300_000);

        const rowCount = dataset.items.length;
        expect(
          rowCount,
          'more than one row, so a per-cell regression cannot hide behind a single cell',
        ).toBeGreaterThan(1);

        /**
         * Register every experiment the dataset now holds, skipping the ones
         * already seen.
         *
         * The runs here are server-side and auto-named, so their ids do not
         * exist until a run makes them and `global-teardown`'s run-prefix sweep
         * cannot reach them. Called from inside the lister rather than after
         * the assertions, because the assertions can fail — which is exactly
         * when the rows would otherwise be left behind. Deliberately asserts
         * nothing about how many there are: this spec makes no claim about what
         * a run or a stop leaves, and pinning a count would freeze a decision
         * nobody has made.
         */
        const seen = new Set<string>();
        const registerCreatedExperiments = async (): Promise<void> => {
          const found = await backendClient.listExperimentsForDataset(dataset.id);
          for (const experiment of found) {
            if (seen.has(experiment.id)) continue;
            seen.add(experiment.id);
            registerExperimentCleanup(experiment.id, experiment.name);
          }
        };

        await test.step('Seed one provider that fails fast and one that hangs', async () => {
          await providerKeys.createUnreachable({
            providerName: `${testNamespace}-fail`,
            modelName: FINISHING_MODEL,
          });
          await providerKeys.createUnresponsive({
            providerName: `${testNamespace}-hang`,
            modelName: HANGING_MODEL,
          });
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Compose a dataset-templated prompt against the fast-failing model', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: ORIGINAL_PROMPT,
            modelDisplayName: FINISHING_MODEL,
          });
        });

        await test.step(`Load the ${rowCount}-item dataset`, async () => {
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({
            mode: 'dataset',
            entityName: dataset.name,
          });
          // The grid must really hold this dataset's rows before any run
          // starts: running against an empty item list fails every row
          // client-side for a different reason, which would look identical
          // below.
          await playground.waitForRunReady({ expectedRows: rowCount });
        });

        await test.step('Before any run, every cell IS the never-run placeholder', async () => {
          // The positive control for the locator the central assertion is a
          // negative on. "No cell shows No runs yet" is worth nothing unless
          // that lookup is shown to match when it should — and this is the one
          // state where it must: a `data-testid` rename or a reworded
          // placeholder would otherwise make the assertion after the edit pass
          // forever, over a grid that had been wiped.
          await expect(
            playground.noRunsYetPlaceholders(),
            'one never-run placeholder per dataset row',
          ).toHaveCount(rowCount);
        });

        await test.step('Run it to completion against the fast-failing model', async () => {
          await playground.clickReRun();
          await playground.waitForRunsComplete({ expectedRows: rowCount, timeoutMs: 180_000 });
          // `waitForRunsComplete` only watches the CELLS: it returns as soon as
          // every cell carries text, and on a server-side run the header is
          // still showing "Stop all" at that point because `isRunning` tracks
          // the experiment rather than the grid. The step below asserts no run
          // is in flight — which is what makes the post-edit reading
          // attributable to the EDIT — so the run has to be settled by the
          // header's own signal, not merely by the grid's. Without this the
          // "no run is in flight" read failed on the local stack against a
          // header that was still swapped for the progress strip.
          await playground.waitForRunSettled(120_000);
          await registerCreatedExperiments();
        });

        await test.step('Every cell failed, and the grid is out of its never-run state', async () => {
          await expect(
            playground.outputErrorTags(),
            'one failure tag per dataset row',
          ).toHaveCount(rowCount);
          for (const text of await playground.outputErrorTags().allInnerTexts()) {
            expect(text.trim(), 'each cell names itself as a failure').toContain(FAILURE_PREFIX);
          }
          await expect(
            playground.noRunsYetPlaceholders(),
            'and no cell is the never-run placeholder any more',
          ).toHaveCount(0);
          await expect(
            playground.outputCellStaleNotes(),
            'nothing has changed yet, so nothing is noted stale',
          ).toHaveCount(0);
          await expect(
            playground.stopAllButton(),
            'and no run is in flight',
          ).toHaveCount(0);
        });

        await test.step('Point the variant at the hanging model and run again', async () => {
          await playground.selectModel(0, HANGING_MODEL);
          await playground.clickReRun();
        });

        await test.step('A run really is in flight, over a grid that is not reset', async () => {
          // The precondition the whole spec turns on, and the reason the second
          // provider is blackholed rather than refusing: a refused connect ends
          // the run in milliseconds and there is no mid-run to edit in.
          //
          // Both halves of "in flight" are read, because they come from
          // different state — the header swaps Run for Stop all off
          // `isRunning`, and the progress readout mounts off
          // `useShowsRunProgress`. A build where only one of them survived
          // would leave the user with a run they cannot see or cannot stop.
          await expect(
            playground.stopAllButton(),
            'the header offers Stop all, so the page considers a run live',
          ).toBeVisible({ timeout: 60_000 });
          await expect(
            playground.runProgressReadout(),
            'and the progress indicator is reporting on it',
          ).toBeVisible({ timeout: 60_000 });
          // Asserted here as well as after the edit, so the two can be told
          // apart: this is what the re-run alone left, which is what makes the
          // post-edit reading attributable to the EDIT.
          await expect(
            playground.noRunsYetPlaceholders(),
            'starting the re-run did not reset the grid to never-run either',
          ).toHaveCount(0);
          await expect(
            playground.outputCellStaleNotes(),
            'and nothing is noted stale yet — the prompt has not changed',
          ).toHaveCount(0);
          await registerCreatedExperiments();
        });

        await test.step('Edit the prompt while the run is still going', async () => {
          await playground.editUserMessage(EDITED_PROMPT);
          // Read back, so an edit that silently failed to land cannot leave the
          // assertions below asserting over an unchanged prompt — a state in
          // which "nothing was reset" is trivially true.
          expect(
            await playground.messageBodies(),
            'the edited message really holds the new text',
          ).toContain(EDITED_PROMPT);
        });

        await test.step('Every cell is noted stale, and none is reset to never-run', async () => {
          // The two halves of the grid claim. The notes are what the user is
          // told; the absent placeholders are what they are not told, and the
          // regression this guards is the second — a grid reset mid-run reads
          // as a run that never started.
          await expect(
            playground.outputCellStaleNotes(),
            'one note per cell, raised by the edit',
          ).toHaveCount(rowCount);
          await expect(
            playground.outputCellStaleNotes(),
            'the grid shows the compact summary, not the whole sentence',
          ).toHaveText(Array(rowCount).fill(COMPACT_SUMMARY));
          // Asserted over the whole grid rather than per cell, so a single
          // reverted row fails too.
          await expect(
            playground.noRunsYetPlaceholders(),
            'no cell fell back to the never-run placeholder',
          ).toHaveCount(0);
        });

        await test.step('…and the run the user did not touch is still running', async () => {
          // The second half of the change, and the one with no coverage
          // anywhere: the run is server-side now, so a page that reset its
          // output state on an edit would drop the only handle the user has to
          // a run that is still going.
          await expect(
            playground.stopAllButton(),
            'Stop all is still mounted, so the edit did not end the run',
          ).toBeVisible();
          await expect(
            playground.runProgressReadout(),
            'and the progress indicator is still reporting',
          ).toBeVisible();
        });

        await test.step('Stopping it still works, and the grid keeps what it was told', async () => {
          // Not housekeeping dressed as an assertion: it is the last thing a
          // user can do with a run they have edited under, and the one the
          // staleness state could plausibly have broken. Teardown of what the
          // runs created is a fixture's job and does not depend on this step
          // being reached — see `registerCreatedExperiments`.
          await playground.clickStopAll();
          await playground.waitForRunSettled(120_000);
          await expect(playground.stopAllButton(), 'the run is over').toHaveCount(0);
          await expect(
            playground.outputCellStaleNotes(),
            'the stale notes survived the stop as well as the edit',
          ).toHaveCount(rowCount);
          await expect(
            playground.noRunsYetPlaceholders(),
            'and the stop did not reset the grid either',
          ).toHaveCount(0);
          await registerCreatedExperiments();
        });
      },
    );
  },
);
