import { test, expect } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';

/**
 * A trial's Logs overlay is locked to that trial (OPIK_6739 / OPIK_7842).
 *
 * The overlay narrows the project's traces to the open trial's experiments via
 * a *locked* scope — one the filter bar cannot express and therefore cannot
 * hold if it is ever routed through the editable filter key again. The failure
 * mode that matters is attribution: if the lock stops holding, the overlay
 * presents every optimization trace in the project as this trial's, and there
 * is nothing on screen that says otherwise.
 *
 * So this asserts exact trace ids, not a row count, against the decoys the
 * `optimizationRun` fixture seeds — same project, same `source=optimization`, no
 * experiment — so a leak fails loudly instead of passing on a lucky count.
 */

test.describe('Optimization trial logs — CUJ', { tag: ['@t2-cuj', '@area:optimization-studio'] }, () => {
  test('each trial\'s Logs overlay lists exactly that trial\'s traces', { tag: ['@cap:optimization-studio.trial-detail'] }, async ({
    backendClient,
    project,
    optimizationRun,
    page,
  }) => {
    test.setTimeout(300_000);

    const { trials, decoyTraceIds } = optimizationRun;

    await test.step('The decoys really are in scope for the project (fixture sanity)', async () => {
      // If the decoys were not visible to an unscoped optimization read, the
      // whole leak-detection premise of this test would be vacuous.
      await expect
        .poll(
          async () => {
            const ids = await backendClient.listTraceIds({ projectId: project.id });
            return decoyTraceIds.filter((d) => ids.includes(d)).length;
          },
          { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
        )
        .toBe(decoyTraceIds.length);
    });

    const studio = new OptimizationStudioPage(page, project.id);

    await test.step('Open the run and its Trials tab', async () => {
      await studio.gotoDetail(optimizationRun.optimizationId);
      await studio.openTrialsTab();
      await expect
        .poll(async () => studio.trialRowCount(), {
          timeout: 60_000,
          intervals: [1_000, 2_000, 5_000],
        })
        .toBe(trials.length);
    });

    // Every trial asserted the same way, so a scope leak in either direction
    // fails: each overlay must list its own traces and none of any other's.
    for (const trial of trials) {
      const foreignTraceIds = [
        ...decoyTraceIds,
        ...trials.filter((t) => t !== trial).flatMap((t) => t.traceIds),
      ];

      await test.step(`${trial.label}'s Logs overlay lists exactly its ${trial.traceIds.length} traces`, async () => {
        await studio.openTrialByLabel(trial.label);
        const overlay = await studio.openTrialLogs();

        await overlay.waitForTraceRows(trial.traceIds.length);
        const listed = await overlay.readTraceIds();
        expect(
          [...listed].sort(),
          `the overlay lists exactly ${trial.label}'s traces`,
        ).toEqual([...trial.traceIds].sort());
        for (const id of foreignTraceIds) {
          expect(
            listed,
            `trace ${id} belongs to another scope and must not appear under ${trial.label}`,
          ).not.toContain(id);
        }

        await expect(
          overlay.scopeChip(trial.label),
          'the locked-scope chip names the trial — without it the narrowing is silent',
        ).toBeVisible();

        await overlay.close();
      });
    }
  });
});
