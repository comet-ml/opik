import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-3268 — a run the user stopped must not claim it finished.
 *
 * `stopAll` and `stopSingle` clear the announcer's claim (`scopedAnnounceRef`)
 * so a late completion cannot raise the "Run complete" toast, and the name
 * field — which only advances when that toast fires — stays on what the user
 * typed. The pure state machine behind the claim is unit-tested; the wiring
 * that clears it is not exercised anywhere. A false "Run complete" plus a
 * silently advanced counter is wrongness a user then acts on: they believe
 * experiments exist under a name that was never used, and their next run lands
 * under `_02` for no reason.
 *
 * Both halves of the wiring get their own test because they are separate call
 * sites — the header's Stop all and a variant card's own Stop — and a
 * regression in one says nothing about the other.
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

      /**
       * Register every experiment now recorded against the dataset.
       *
       * A stopped run still leaves behind the experiments it created before the
       * first completion was attempted, and those are not swept by anything
       * else here. Deliberately asserts nothing about how many there are: that
       * a stop leaves them is a product behaviour this test observed, not one
       * it is claiming, and pinning a count would freeze a decision the team
       * has not made.
       */
      const seen = new Set<string>();
      const registerCreatedExperiments = async (): Promise<void> => {
        for (const experiment of await backendClient.listExperimentsForDataset(dataset.id)) {
          if (seen.has(experiment.id)) continue;
          seen.add(experiment.id);
          registerExperimentCleanup(experiment.id, experiment.name);
        }
      };

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

      await test.step('Sweep whatever the stopped run had already created', registerCreatedExperiments);

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

      const seen = new Set<string>();
      const registerCreatedExperiments = async (): Promise<void> => {
        for (const experiment of await backendClient.listExperimentsForDataset(dataset.id)) {
          if (seen.has(experiment.id)) continue;
          seen.add(experiment.id);
          registerExperimentCleanup(experiment.id, experiment.name);
        }
      };

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

      await test.step('Sweep whatever the stopped run had already created', registerCreatedExperiments);

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
