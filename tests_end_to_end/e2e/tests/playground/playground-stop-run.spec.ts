import { test, expect } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import type { BackendClient } from '@e2e/core/backend';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * OPIK-3268 — a run the user stopped must not claim it finished.
 *
 * A dataset run executes on the server: one `/experiments/execute` creates the
 * experiments, and the page learns the run ended by polling them until they are
 * terminal, then raises "Run complete" and advances the name field. A stop
 * cancels them through `/experiments/cancel`, which makes them terminal too, so
 * the stop also has to end the page's watch — a poll left running would read
 * the cancelled experiments as finished and announce. A false "Run complete"
 * plus a silently advanced counter is wrongness a user then acts on: they
 * believe experiments exist under a name that was never used, and their next
 * run lands under `_02` for no reason.
 *
 * Both halves get their own test because they are separate call sites AND
 * separate mechanisms, so a regression in one says nothing about the other:
 *
 * - **Stop all** moves the poll generation and sets `isToStopRef`, which ends
 *   the unscoped poll before its next tick.
 * - **A variant's own Stop** deletes that prompt's id from `scopedAnnounceRef`,
 *   the set a scoped poll that is still ticking consults before it announces.
 *
 * ## What makes the silence mean something
 *
 * Each Stop is pressed only once `/execute` has answered. Before that the page
 * holds no experiment ids, and `useBackendRunCancellation` queues the stop and
 * replays it when they land — a different path from the one named here. Each
 * test then asserts the cancel named exactly the run's experiments, and that
 * the server holds them as `cancelled`: terminal, so a watch the stop had
 * failed to end WOULD announce inside the window below.
 *
 * ## Why the provider is blackholed rather than refusing
 *
 * `createUnreachable` fails every call at once, so its run is over before Stop
 * can be clicked. `createUnresponsive` points at TEST-NET-1, so the server's
 * connect hangs and the run stays open until the test stops it.
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

  /** The experiment ids the next `/experiments/execute` answers with. Arm before clicking Run. */
  const nextExecutedExperimentIds = (page: Page): Promise<string[]> =>
    page
      .waitForResponse(
        (response) =>
          response.request().method() === 'POST' &&
          /\/v1\/private\/experiments\/execute$/.test(new URL(response.url()).pathname),
        { timeout: 60_000 },
      )
      .then(async (response) => {
        expect(response.ok(), `execute answered ${response.status()}`).toBe(true);
        const body = (await response.json()) as { experiments?: { experiment_id: string }[] };
        return (body.experiments ?? []).map((e) => e.experiment_id);
      });

  /** The experiment ids the next `/experiments/cancel` names. Arm before clicking Stop. */
  const nextCancelledExperimentIds = (page: Page): Promise<string[]> =>
    page
      .waitForRequest(
        (request) =>
          request.method() === 'PUT' &&
          /\/v1\/private\/experiments\/cancel$/.test(new URL(request.url()).pathname),
        { timeout: 30_000 },
      )
      .then((request) => ((request.postDataJSON() ?? {}) as { ids?: string[] }).ids ?? []);

  /** Wait until the server holds every one of `experimentIds` as cancelled. */
  const expectCancelled = async (
    backendClient: BackendClient,
    experimentIds: string[],
  ): Promise<void> => {
    await expect
      .poll(
        async () =>
          Promise.all(
            experimentIds.map(async (id) => (await backendClient.getExperimentLifecycle(id)).status),
          ),
        { timeout: 30_000, intervals: [500, 1000, 2000] },
      )
      .toEqual(experimentIds.map(() => 'cancelled'));
  };

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
   * into sweeping different things.
   */
  const sweeperFor = (
    backendClient: BackendClient,
    datasetId: string,
    registerExperimentCleanup: (id: string, name: string) => void,
  ) => {
    const seen = new Set<string>();
    return async (): Promise<void> => {
      const found = await backendClient.listExperimentsForDataset(datasetId);
      for (const experiment of found) {
        if (seen.has(experiment.id)) continue;
        seen.add(experiment.id);
        registerExperimentCleanup(experiment.id, experiment.name);
      }
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
        await playground.duplicateVariant(0);
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
        await playground.setExperimentName(runName);
      });

      const { stoppedAt, experimentIds } = await test.step('Run everything, then stop everything', async () => {
        const executed = nextExecutedExperimentIds(page);
        await playground.clickReRun();
        const ids = await executed;
        await registerCreatedExperiments();
        expect(ids, 'one experiment per variant').toHaveLength(2);

        const cancelled = nextCancelledExperimentIds(page);
        await playground.clickStopAll();
        const at = Date.now();
        expect([...(await cancelled)].sort(), 'the stop cancels exactly this run').toEqual(
          [...ids].sort(),
        );
        return { stoppedAt: at, experimentIds: ids };
      });

      await test.step('The page agrees the run is over', async () => {
        // The output-actions strip swaps the name editor for the progress
        // indicator for the duration of a run, so the editor coming back IS
        // "nothing is running" — and it is the element the name assertion below
        // reads, so that assertion cannot pass against a stale render.
        await playground.waitForRunSettled(60_000);
      });

      await test.step('The server holds both experiments as cancelled, so the silence below means something', async () => {
        await expectCancelled(backendClient, experimentIds);
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
        await playground.duplicateVariant(0);
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
        await playground.waitForRunReady({ expectedRows: 3 });
        await playground.setExperimentName(runName);
      });

      const { stoppedAt, experimentIds } = await test.step("Run variant B's column, then stop that column", async () => {
        const executed = nextExecutedExperimentIds(page);
        await playground.clickVariantRun(1);
        const ids = await executed;
        await registerCreatedExperiments();
        expect(ids, "one experiment, B's").toHaveLength(1);

        const cancelled = nextCancelledExperimentIds(page);
        await playground.clickVariantStop(1);
        const at = Date.now();
        expect(await cancelled, 'the stop cancels exactly this column').toEqual(ids);
        return { stoppedAt: at, experimentIds: ids };
      });

      await test.step('The page agrees the run is over', async () => {
        await playground.waitForRunSettled(60_000);
      });

      await test.step('The server holds the experiment as cancelled, so the silence below means something', async () => {
        await expectCancelled(backendClient, experimentIds);
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
