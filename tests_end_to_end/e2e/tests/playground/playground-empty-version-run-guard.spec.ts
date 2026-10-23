import { test, expect } from '@e2e/fixtures';
import type { Page, Request } from '@playwright/test';
import type { EmptiedSourceRef } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * opik#8813 — a Playground run is refused when the SELECTED version of the
 * loaded dataset or test suite has no items, on every surface that can start
 * one, and `POST /v1/private/experiments/execute` refuses it too.
 *
 * The shipped unit tests (`PlaygroundHeader.test.tsx`,
 * `PlaygroundRunButton.test.tsx`) both mock `usePlaygroundDataset`, so they
 * assert the gate given an `itemsTotal` rather than that the number ever
 * arrives. The link they cannot reach is `items_total` travelling from
 * `GET /v1/private/datasets/{id}/versions` into the components — and that link
 * is what FAILS OPEN when it breaks: `isDatasetEmpty` is `itemsTotal === 0`, so
 * an absent field reads as "not empty" and Run stays enabled on a genuinely
 * empty source. A dropped or renamed field would leave every unit test green.
 * These tests therefore seed the emptiness for real, through the API, and never
 * tell the page anything.
 *
 * Both Run surfaces are asserted separately because each derives the verdict
 * independently — the header from its `versionItemsTotal` prop, each variant
 * card from its own `usePlaygroundDataset()` call. The per-variant control also
 * only renders once there is more than one variant (`showPerPromptRunControls`),
 * which is why every test here adds a second one.
 *
 * The second test is the same mechanism on a test suite, and it is not a
 * duplicate of the first: the reason is composed from `DATASET_TYPE.TEST_SUITE`
 * in both components, so a suite that announced itself as a "dataset" would be
 * invisible to the dataset test. One string, two code paths, and it is the
 * wording the change's last push altered.
 *
 * The provider is a `custom-llm` one whose base URL refuses every connection.
 * It is a precondition twice over: the Playground's model picker must have
 * SOMETHING selectable, because `PlaygroundHeader` evaluates
 * `!allPromptsHaveModels` BEFORE `isDatasetEmpty` and that reason would shadow
 * the one under test; and `PlaygroundPage` auto-opens a modal "Add provider
 * configuration" dialog on a workspace with no provider key at all. Nothing is
 * ever sent to it — the gate is decided client-side before any completion — so
 * these tests need no provider key and bill nothing.
 */
test.describe(
  'Playground — the empty-version run guard',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    const DATASET_REASON =
      'This dataset version has no items. Add items or select another version to run an experiment';
    const TEST_SUITE_REASON =
      'This test suite version has no items. Add items or select another version to run an experiment';
    const EXECUTE_REASON = (name: string) =>
      `Dataset '${name}' has no items in the selected version. Add items to it before running an experiment`;

    /**
     * Every request that would mean a run had started, from before the page is
     * opened.
     *
     * All three paths, because the gate has to close every way a run can start:
     * a dataset or suite run posts one `/experiments/execute`, a run without
     * one streams completions from the browser, and the bare `/experiments`
     * collection is the browser-side dataset path from before runs moved
     * server-side, kept so a build that fell back to it is still caught.
     * Anchored on the end of the path — `/experiments`, `/experiments/items`
     * and `/experiments/execute` share a prefix.
     */
    const collectRunRequests = (page: Page): string[] => {
      const seen: string[] = [];
      const isRunRequest = (request: Request): boolean => {
        if (request.method() !== 'POST') return false;
        const { pathname } = new URL(request.url());
        return (
          /\/v1\/private\/experiments\/?$/.test(pathname) ||
          /\/v1\/private\/experiments\/execute$/.test(pathname) ||
          /\/v1\/private\/chat\/completions$/.test(pathname)
        );
      };
      page.on('request', (request) => {
        if (isRunRequest(request)) seen.push(`${request.method()} ${new URL(request.url()).pathname}`);
      });
      return seen;
    };

    /**
     * Open the Playground on `source` with two variants, and return once its
     * emptied version is the one in front of the user.
     *
     * The wait is on the pill's version label rather than on the pill itself.
     * The pill commits with the SELECTION, which is strictly before the versions
     * response that carries `items_total` — and in that window the gate reads an
     * absent count as "not empty", so Run is legitimately still enabled. The
     * label comes out of that same response, so it being painted is what makes
     * every assertion below a statement about the emptied version rather than
     * about a page still loading it.
     */
    const openOnEmptiedSource = async (
      playground: PlaygroundPage,
      source: EmptiedSourceRef,
      mode: 'dataset' | 'test_suite',
      modelDisplayName: string,
    ): Promise<void> => {
      await playground.goto();
      await playground.waitForReady();
      await playground.configureVariant(0, { userPrompt: '{{input}}', modelDisplayName });
      await playground.duplicateLastVariant();
      await playground.clickRunExperiment();
      await playground.selectRunExperimentSource({ mode, entityName: source.name });
      await expect(playground.loadedSourcePill()).toContainText(source.name);
      await expect(playground.loadedSourceVersionLabel()).toHaveText(source.emptied.name);
    };

    /**
     * Assert that pressing the Run-all shortcut started nothing.
     *
     * The bounded wait is on a real event rather than a sleep: if the keypress
     * did reach `onRunAll`, the first request arrives within it and this fails
     * naming it. `collected` is read as well as waited on, so a request made in
     * the gap between the keypress and this call is still caught.
     *
     * The negative is only meaningful because the same gesture is shown to WORK
     * elsewhere: `playground-run-gate-selected-version.spec.ts` presses
     * Shift+Enter on a version that does have items and asserts a run starts.
     * Without that control, a shortcut that had been removed entirely would pass
     * here.
     */
    const expectNoRunStarted = async (page: Page, collected: string[]): Promise<void> => {
      const sawRequest = await page
        .waitForRequest(
          (request) =>
            request.method() === 'POST' &&
            /\/v1\/private\/(experiments|chat\/completions)/.test(new URL(request.url()).pathname),
          { timeout: 5_000 },
        )
        .then(() => true)
        .catch(() => false);
      expect(sawRequest).toBe(false);
      expect(collected).toEqual([]);
    };

    test(
      'an emptied dataset version disables both Run surfaces, and execute rejects it',
      { tag: ['@cap:playground.empty-source-run-guard'] },
      async ({
        project,
        emptiedDataset,
        providerKeys,
        backendClient,
        testNamespace,
        page,
      }) => {
        test.setTimeout(180_000);
        const modelDisplayName = 'unreachable-model';

        await test.step('Seed a selectable provider that refuses every connection', async () => {
          await providerKeys.createUnreachable({
            providerName: `${testNamespace}-provider`,
            modelName: modelDisplayName,
          });
        });

        const playground = new PlaygroundPage(page, project.id);
        const runRequests = collectRunRequests(page);

        await test.step('Load the emptied dataset into the Playground', async () => {
          await openOnEmptiedSource(playground, emptiedDataset, 'dataset', modelDisplayName);
        });

        await test.step('The header Run is disabled, and says why', async () => {
          await expect(playground.headerRunButton()).toBeDisabled();
          // The whole reason, compared exactly. A substring match would also
          // pass on the generic "Run experiment on dataset" tooltip's absence,
          // and the point of the change is this specific sentence.
          expect(await playground.headerRunTooltipText()).toBe(DATASET_REASON);
        });

        await test.step("Each variant's own Run is disabled, and says why", async () => {
          // Both, not one: `PlaygroundRunButton` is mounted per variant and
          // computes `isDatasetEmpty` itself, so a card that read a stale or
          // per-card count would still disable the one card a `.first()` check
          // happened to pick.
          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeDisabled();
            expect(await playground.variantRunTooltipText(index)).toBe(DATASET_REASON);
          }
        });

        await test.step('The results grid holds no rows to run against', async () => {
          // The state the gate exists to describe, asserted rather than assumed:
          // a grid that had rendered rows would mean the emptied version was not
          // the one loaded, and every tooltip above would be about something
          // else.
          expect(await playground.mountedRowIds()).toEqual([]);
        });

        await test.step('The Run-all shortcut starts nothing either', async () => {
          await playground.pressRunAllHotkey();
          await expectNoRunStarted(page, runRequests);
        });

        await test.step('execute refuses the same version, naming the dataset', async () => {
          const result = await backendClient.executeExperiments({
            datasetName: emptiedDataset.name,
            datasetId: emptiedDataset.id,
            projectName: project.name,
            versionHash: emptiedDataset.emptied.hash,
            datasetVersionId: emptiedDataset.emptied.id,
            prompts: [
              {
                model: `custom-llm/${testNamespace}-provider/${modelDisplayName}`,
                messages: [{ role: 'user', content: '{{input}}' }],
                experimentName: `${testNamespace}-must-not-exist`,
              },
            ],
          });
          expect(result.status).toBe(400);
          expect(result.message).toBe(EXECUTE_REASON(emptiedDataset.name));
          // Nothing was created, asserted rather than assumed: a rejection that
          // fired after the experiment row had already been written would leave
          // an experiment behind and still answer 400.
          expect(result.experiments).toEqual([]);
        });

        await test.step('The dataset carries no experiment at all', async () => {
          // The full set for this dataset rather than a lookup of the name
          // above: the failure worth catching is a run that started under ANY
          // name. The dataset is fixture-seeded and nothing else writes to it.
          expect(await backendClient.listExperimentsForDataset(emptiedDataset.id)).toEqual([]);
        });
      },
    );

    test(
      'an emptied test suite version names the test suite in the blocked reason',
      { tag: ['@cap:playground.empty-source-run-guard'] },
      async ({
        project,
        emptiedTestSuite,
        providerKeys,
        backendClient,
        testNamespace,
        page,
      }) => {
        test.setTimeout(180_000);
        const modelDisplayName = 'unreachable-model';

        await test.step('Seed a selectable provider that refuses every connection', async () => {
          await providerKeys.createUnreachable({
            providerName: `${testNamespace}-provider`,
            modelName: modelDisplayName,
          });
        });

        const playground = new PlaygroundPage(page, project.id);
        const runRequests = collectRunRequests(page);

        await test.step('Load the emptied test suite into the Playground', async () => {
          await openOnEmptiedSource(playground, emptiedTestSuite, 'test_suite', modelDisplayName);
        });

        await test.step('Both Run surfaces are disabled, and name the test suite', async () => {
          await expect(playground.headerRunButton()).toBeDisabled();
          // The assertion the suite branch exists for, compared exactly: a
          // build that lost `DATASET_TYPE.TEST_SUITE` would say "dataset
          // version" here, and only an exact comparison fails on it — the two
          // reasons differ in one word and share the rest of the sentence.
          expect(await playground.headerRunTooltipText()).toBe(TEST_SUITE_REASON);

          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeDisabled();
            expect(await playground.variantRunTooltipText(index)).toBe(TEST_SUITE_REASON);
          }
        });

        await test.step('The Run-all shortcut starts nothing', async () => {
          expect(await playground.mountedRowIds()).toEqual([]);
          await playground.pressRunAllHotkey();
          await expectNoRunStarted(page, runRequests);
        });

        await test.step('execute refuses the suite too, and creates nothing', async () => {
          const result = await backendClient.executeExperiments({
            datasetName: emptiedTestSuite.name,
            datasetId: emptiedTestSuite.id,
            projectName: project.name,
            versionHash: emptiedTestSuite.emptied.hash,
            datasetVersionId: emptiedTestSuite.emptied.id,
            prompts: [
              {
                model: `custom-llm/${testNamespace}-provider/${modelDisplayName}`,
                messages: [{ role: 'user', content: '{{question}}' }],
                experimentName: `${testNamespace}-must-not-exist`,
              },
            ],
          });
          expect(result.status).toBe(400);
          // The backend says "Dataset '<name>'" even for a suite, deliberately:
          // `ExperimentsResourceExecuteTest` is parameterized over both
          // `DatasetType` values and asserts this one string for each. Pinned
          // here as the contract it is, so that the UI's correct "test suite"
          // wording above is not read as a claim about the API's.
          expect(result.message).toBe(EXECUTE_REASON(emptiedTestSuite.name));
          expect(result.experiments).toEqual([]);
          expect(await backendClient.listExperimentsForDataset(emptiedTestSuite.id)).toEqual([]);
        });
      },
    );
  },
);
