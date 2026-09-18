import { test, expect } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-3268 — a run the user stopped must not claim it finished.
 *
 * `stopAll` and `stopSingle` both revoke the announcer's claim so a late
 * completion cannot raise the "Run complete" toast, and the name field — which
 * only advances when that toast fires — stays on what the user typed. A false
 * "Run complete" plus a silently advanced counter is wrongness a user then acts
 * on: they believe experiments exist under a name that was never used, and
 * their next run lands under `_02` for no reason.
 *
 * Both halves get their own test because they are separate call sites AND
 * separate mechanisms, so a regression in one says nothing about the other:
 *
 * - **Stop all**, against a whole-page run, clears `announcePendingRef` (the
 *   flag `runAllViaFrontend`'s `createCompletionAnnouncer` callback checks) and
 *   sets `isToStopRef`, which is what makes the unscoped poll return early.
 *   `stopAll` also clears `scopedAnnounceRef`, but that set is empty on an
 *   unscoped run and plays no part in this test.
 * - **A variant's own Stop**, against a per-column run, deletes that prompt's
 *   id from `scopedAnnounceRef` — the set `runSingleViaFrontend` added it to,
 *   and the one the scoped poll's `delete()` gates the announce on.
 *
 * The pure state machine behind the claim is unit-tested; neither piece of
 * wiring that revokes it is exercised anywhere else.
 *
 * ## Why these assertions are load-bearing, and the one way they could stop being
 *
 * `createCompletionAnnouncer` only calls its callback once
 * `registered >= expected`, where `registered` comes from
 * `experimentsQueue.drain()` — so if a stop happened before the run had
 * registered one experiment PER VARIANT, the announcer would short-circuit on
 * the count and never reach the revoked claim at all. These tests would then
 * pass without exercising the wiring they name.
 *
 * That is not what happens, and it was checked rather than assumed: listing the
 * dataset's experiments immediately after each stop shows 2 after Stop all
 * (`_a` and `_b`) and 1 more after the single-column stop. The count condition
 * is satisfied, `fire()` proceeds, and the revoked claim is the only thing left
 * between the run and the toast — which is what makes the silence below
 * evidence about `stopAll`/`stopSingle` rather than about arithmetic.
 *
 * If a future change makes experiment creation lazier — deferred until a
 * completion actually returns, say — that stops being true silently. The guard
 * against it is the experiment count itself, asserted below.
 *
 * ## Why the provider is blackholed rather than refusing
 *
 * The rest of the Playground suite seeds `createUnreachable`, whose base URL
 * REFUSES every connection: the run fails in milliseconds, which is what makes
 * a naming assertion deterministic. It is unusable here — there is no window in
 * which to click Stop. `createUnresponsive` points at TEST-NET-1, so the
 * connect hangs and the run stays open until this test ends it.
 *
 * ## Why each test ends by running to completion
 *
 * The assertion that matters here is a negative one, and a negative assertion
 * over a recorder that silently failed to install would pass having verified
 * nothing — forever, and invisibly. So after the stop assertions each test
 * re-points the variants at the refusing provider and runs for real: the toast
 * that run raises proves the recorder was watching the whole time, and that the
 * silence before it was the feature rather than the harness.
 */
test.describe('Playground — stopping a run', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  /**
   * How long after a Stop a completion toast is still considered "late enough
   * to be a defect".
   *
   * A dwell, not a wait-for-state: there is no state whose arrival proves a
   * toast will never come, so the only way to assert one did not appear is to
   * hold for a window and look. It is measured from the Stop click and overlaps
   * the backend sweep below, so it usually costs nothing in wall-clock, and it
   * can only ever make this test slower — never flakier — which is the opposite
   * of the fixed sleep the conventions warn about.
   */
  const ANNOUNCE_WINDOW_MS = 20_000;

  const HANGING_MODEL = 'unresponsive-model';
  const FINISHING_MODEL = 'unreachable-model';

  /**
   * A sweeper that registers every experiment currently recorded against the
   * dataset, skipping the ones it has already seen.
   *
   * A stopped run still leaves behind the experiments it created before the
   * first completion was attempted, and those are not swept by anything else
   * here — the ids only exist once the run has made them, so a fixture cannot
   * know them up front. Deliberately asserts nothing about how many there are:
   * that a stop leaves them is a product behaviour these tests observed, not one
   * they are claiming, and pinning a count would freeze a decision the team has
   * not made.
   *
   * Shared by both tests rather than written out twice, so the two cannot drift
   * into sweeping different things. Returns the names it found, so a caller can
   * assert the run got far enough to register experiments at all — see the
   * load-bearing note in the describe docblock.
   */
  const sweeperFor = (
    backendClient: BackendClient,
    datasetId: string,
    registerExperimentCleanup: (id: string, name: string) => void,
  ) => {
    const seen = new Set<string>();
    return async (): Promise<string[]> => {
      const found = await backendClient.listExperimentsForDataset(datasetId);
      for (const experiment of found) {
        if (seen.has(experiment.id)) continue;
        seen.add(experiment.id);
        registerExperimentCleanup(experiment.id, experiment.name);
      }
      return found.map((e) => e.name);
    };
  };

  test(
    'Stopping every column announces nothing and leaves the typed name alone',
    { tag: ['@cap:playground.run-against-dataset'] },
    async ({
      project,
      dataset,
      providerKeys,
      backendClient,
      registerExperimentCleanup,
      testNamespace,
      page,
    }) => {
      test.setTimeout(300_000);

      const runName = `${testNamespace}-stopall`;
      const playground = new PlaygroundPage(page, project.id);

      const registerCreatedExperiments = sweeperFor(
        backendClient,
        dataset.id,
        registerExperimentCleanup,
      );

      await test.step('Seed one provider that hangs and one that fails fast', async () => {
        await providerKeys.createUnresponsive({
          providerName: `${testNamespace}-hang`,
          modelName: HANGING_MODEL,
        });
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-fail`,
          modelName: FINISHING_MODEL,
        });
      });

      await test.step('Open the Playground on the seeded dataset with two variants', async () => {
        await playground.startRecordingToasts();
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName: HANGING_MODEL,
        });
        await playground.duplicateLastVariant();
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
        await playground.setExperimentName(runName);
      });

      const stoppedAt = await test.step('Run everything, then stop everything', async () => {
        await playground.clickReRun();
        // "Stop all" replacing Run in the header is the page's own statement
        // that a run is in flight — clicking it before that would be a click on
        // a button that is not there yet.
        await playground.clickStopAll();
        return Date.now();
      });

      await test.step('The page agrees the run is over', async () => {
        // The output-actions strip swaps the name editor for the progress
        // indicator for the duration of a run, so the editor coming back IS
        // "nothing is running" — and it is the element the name assertion below
        // reads, so that assertion cannot pass against a stale render.
        await playground.waitForRunSettled(60_000);
      });

      await test.step('The stopped run had registered both variants, so the silence below means something', async () => {
        // Not a claim about how many experiments a stop SHOULD leave — that is a
        // product decision the team has not made, and this does not pin it. It
        // is the precondition that makes the next step load-bearing: the
        // announcer only consults the revoked claim once one experiment per
        // variant is registered, so with fewer than two it would stay silent
        // for a reason that has nothing to do with `stopAll`.
        expect((await registerCreatedExperiments()).length).toBeGreaterThanOrEqual(2);
      });

      await test.step('Nothing announced completion, and the name is untouched', async () => {
        const remaining = ANNOUNCE_WINDOW_MS - (Date.now() - stoppedAt);
        if (remaining > 0) await page.waitForTimeout(remaining);

        expect(await playground.recordedRunCompletionToasts()).toEqual([]);
        // The counter advances only when the toast fires, so this is a second,
        // independent witness on the same claim — and it is the one the user
        // carries into their next run.
        expect(await playground.readExperimentName()).toBe(runName);
      });

      await test.step('A run that really finishes still announces, so the silence above was real', async () => {
        await playground.selectModel(0, FINISHING_MODEL);
        await playground.selectModel(1, FINISHING_MODEL);
        await playground.clickReRun();
        await expect
          .poll(() => playground.recordedRunCompletionToasts(), {
            timeout: 180_000,
            intervals: [500, 1000, 2000],
          })
          .toHaveLength(1);

        const [toast] = await playground.recordedRunCompletionToasts();
        expect(toast).toContain('2 experiments created');
        expect(toast).toContain(`${runName}_a`);
        await registerCreatedExperiments();
      });
    },
  );

  test(
    'Stopping one column announces nothing and leaves the typed name alone',
    { tag: ['@cap:playground.run-against-dataset'] },
    async ({
      project,
      dataset,
      providerKeys,
      backendClient,
      registerExperimentCleanup,
      testNamespace,
      page,
    }) => {
      test.setTimeout(300_000);

      const runName = `${testNamespace}-stopone`;
      const playground = new PlaygroundPage(page, project.id);

      const registerCreatedExperiments = sweeperFor(
        backendClient,
        dataset.id,
        registerExperimentCleanup,
      );

      await test.step('Seed one provider that hangs and one that fails fast', async () => {
        await providerKeys.createUnresponsive({
          providerName: `${testNamespace}-hang`,
          modelName: HANGING_MODEL,
        });
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-fail`,
          modelName: FINISHING_MODEL,
        });
      });

      await test.step('Open the Playground on the seeded dataset with two variants', async () => {
        await playground.startRecordingToasts();
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName: HANGING_MODEL,
        });
        await playground.duplicateLastVariant();
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
        await playground.setExperimentName(runName);
      });

      const stoppedAt = await test.step("Run variant B's column, then stop that column", async () => {
        await playground.clickVariantRun(1);
        // The card's Run swapping to Stop is that variant's own statement that
        // it is running, which is also what scopes the claim being cleared.
        await playground.clickVariantStop(1);
        return Date.now();
      });

      await test.step('The page agrees the run is over', async () => {
        await playground.waitForRunSettled(60_000);
      });

      await test.step('The stopped column had registered its experiment, so the silence below means something', async () => {
        // Same precondition as the Stop-all test, at this path's own threshold:
        // `runSingleViaFrontend` builds its announcer with `expected` of 1, so
        // one registered experiment is what puts `scopedAnnounceRef` on the
        // critical path rather than the count.
        expect((await registerCreatedExperiments()).length).toBeGreaterThanOrEqual(1);
      });

      await test.step('Nothing announced completion, and the name is untouched', async () => {
        const remaining = ANNOUNCE_WINDOW_MS - (Date.now() - stoppedAt);
        if (remaining > 0) await page.waitForTimeout(remaining);

        expect(await playground.recordedRunCompletionToasts()).toEqual([]);
        expect(await playground.readExperimentName()).toBe(runName);
      });

      await test.step('A run that really finishes still announces, so the silence above was real', async () => {
        await playground.selectModel(1, FINISHING_MODEL);
        await playground.clickVariantRun(1);
        await expect
          .poll(() => playground.recordedRunCompletionToasts(), {
            timeout: 180_000,
            intervals: [500, 1000, 2000],
          })
          .toHaveLength(1);

        const [toast] = await playground.recordedRunCompletionToasts();
        expect(toast).toContain('1 experiment created');
        expect(toast).toContain(`${runName}_b`);
        await registerCreatedExperiments();
      });
    },
  );
});
