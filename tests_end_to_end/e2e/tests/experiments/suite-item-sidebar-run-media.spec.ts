import { test, expect } from '@e2e/fixtures';
import { SuiteExperimentPanelPage } from '@e2e/pom/suite-experiment-panel.page';

/**
 * Output media in the evaluation-suite item sidebar, per run.
 *
 * OPIK-4954 wired `useExperimentItemMedia` into TWO panels, and this is the
 * other one. `experiment-compare-image-output.spec.ts` and
 * `experiment-compare-output-attachment.spec.ts` both drive
 * `CompareExperimentsPanel`, which the Items tab mounts for a dataset-method
 * experiment; an `evaluation_suite` experiment gets `TestSuiteExperimentPanel`
 * instead, and the two are fed differently. The compare panel takes its
 * project_id from `useExperimentById`; this one reads it out of the experiments
 * LIST, via `experimentProjectIdMap`. Neither call site proves the other: a
 * change to one hook consumer leaves the other untouched, and the failure is
 * silent either way — the strip simply renders empty.
 *
 * The multi-run axis is what makes this worth its own spec rather than a copy
 * of the compare one. `MultiRunTabs` exists only here: an item run more than
 * once gets one tab per run, and each tab has to show THAT run's output and
 * THAT run's picture. Run 2 rendering run 1's image is the shape of wrongness a
 * reviewer would never spot — the panel looks entirely normal — so the spec
 * asserts the PAIRING, not the presence: each tab's marker text and its
 * thumbnail must come from the same seeded run, and the two tabs must not land
 * on the same one.
 *
 * Deliberately not asserting which trace is Run 1. The tab order follows the
 * order the compare read returns `experiment_items` in, which is the backend's
 * to choose and not part of what this spec is about; pinning it would make the
 * test fail for a reason that has nothing to do with media. What IS pinned is
 * that between them the two tabs cover both runs exactly once, which is the
 * property a correct panel must have however it orders them.
 *
 * Deterministic by construction: two fixed 1x1 images written down as byte
 * strings, so every assertion compares a `src` against a data URL known in
 * full. No wall clock, no model output, no pixel comparison.
 */
test.describe(
  'Evaluation-suite item sidebar — per-run output media',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    /** Same 120s seed budget and same reason as the compare media specs. */
    test.slow();

    test(
      'each run shows its own picture beside its own output',
      { tag: ['@cap:experiments.suite-item-sidebar'] },
      async ({ suiteExperimentRunMedia, project, page }) => {
        const seed = suiteExperimentRunMedia;
        const panel = new SuiteExperimentPanelPage(page, project.id, seed.datasetId, [
          seed.experimentId,
        ]);

        await test.step('Open the item that was run twice', async () => {
          await panel.gotoItemRow(seed.datasetItemId);
        });

        await test.step('The panel offers one tab per run', async () => {
          // Exactly two. One tab would mean the sidebar collapsed the runs (or
          // that only one experiment item landed, which the fixture has already
          // ruled out server-side); three would mean it invented one.
          await expect(panel.runTabs, 'run tabs for an item run twice').toHaveCount(
            seed.runs.length,
          );
        });

        /**
         * Which seeded run each tab turned out to be showing.
         *
         * Resolved from the rendered text rather than assumed from the tab
         * index, for the reason in the header: the order is the backend's.
         */
        const shownByTabIndex: string[] = [];
        /** The previous tab's rendered text, so the next switch can settle on a change. */
        let previousText: string | undefined;

        // Indexed over the tab positions, not over the seed pairs: which seeded
        // run sits at which tab is resolved from the rendered text below.
        for (let index = 0; index < seed.runs.length; index++) {
          await test.step(`Run ${index + 1} pairs its own text with its own picture`, async () => {
            // Settle on a rendered run body before reading anything off it.
            // `MultiRunTabs` swaps the body in place, so a read taken straight
            // after the click can still show the previous run — the very bleed
            // under test, which would otherwise be indistinguishable from a
            // race. The wait is deliberately on "a run output line exists",
            // not on which run it is: waiting for the expected marker would
            // make the bleed time out here rather than fail the comparison
            // below with both values named.
            await panel.selectRun(index, previousText);

            const text = await panel.readRunOutputText();
            previousText = text;
            const shown = seed.runs.find((run) => text.includes(run.marker));
            // Asserted, not defaulted: a tab whose text matches NEITHER run is
            // a real failure, and a `?? runs[index]` here would turn it into a
            // confusing mismatch further down instead of naming it.
            expect(
              shown,
              `the text Run ${index + 1} rendered (${JSON.stringify(text)}) must belong to one of ` +
                `the seeded runs [${seed.runs.map((r) => r.marker).join(', ')}]`,
            ).toBeDefined();
            const run = shown as (typeof seed.runs)[number];
            shownByTabIndex.push(run.marker);

            // The whole rendered line, so an output that lost its surrounding
            // text while resolving the image still fails.
            expect(text, `Run ${index + 1}'s output text`).toContain(run.expectedText);
            // And no raw base64 survived into the text — without this, a build
            // that rendered the bytes AND a thumbnail would pass everything
            // else here.
            expect(
              text,
              `raw base64 left in Run ${index + 1}'s rendered output`,
            ).not.toContain(run.rawOutput.slice(-48));

            // The assertion this spec exists for: the picture on screen is the
            // one belonging to the run whose text is on screen beside it.
            expect(
              await panel.readRunMediaSrc(),
              `the picture Run ${index + 1} showed must be ${run.marker}'s own, not the other run's`,
            ).toBe(run.expectedUrl);
          });
        }

        await test.step('Between them the tabs covered both runs, once each', async () => {
          // The half a per-tab check cannot see: two tabs that each paired
          // their own text with their own picture would still be wrong if both
          // showed the SAME run. Compared as the whole collection rather than
          // by membership, so a duplicate fails.
          expect(
            [...shownByTabIndex].sort(),
            'the runs the two tabs rendered between them',
          ).toEqual([...seed.runs.map((run) => run.marker)].sort());
        });
      },
    );
  },
);
