import { test, expect } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ExperimentsPage } from '@e2e/pom/experiments.page';

/**
 * OPIK-3268 — the flask menu on each Playground variant sets the name of the
 * experiment that variant's run creates.
 *
 * `playground-smoke.spec.ts` already covers running against a dataset, but it
 * matches the experiment it created by `datasetId`, explicitly "since the name
 * is auto-generated server-side". That was true before this change and is the
 * reason a dropped name, a name trimmed away to nothing, or a name attached to
 * the wrong variant all still pass it. This spec joins the two halves: the name
 * a user typed into a specific variant, and the name that variant's experiment
 * ends up carrying.
 *
 * Deliberately driven through the UI rather than the API. The name-to-variant
 * binding lives in the frontend store (`setPromptExperimentName` keyed on
 * promptId) and in `createLogPlaygroundProcessor`, which decides whether to put
 * `name` on the POST at all — neither is observable from a hand-built request.
 *
 * The provider is a `custom-llm` one whose base URL refuses every connection.
 * The model has to be selectable for Run to enable, but its output is not under
 * test here: the experiment is created when the run starts, so it is named,
 * posted and persisted regardless of what the provider does with the
 * completion. That is what keeps this deterministic and free of a provider key.
 */
test.describe('Playground — experiment naming', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  interface PostedExperiment {
    name?: string;
    dataset_name?: string;
  }

  /**
   * Collect every experiment-creation POST the page makes, from before the run
   * starts. Registered eagerly rather than awaited per-request: the frontend
   * queues experiment creation independently of the trace batches that paint
   * the result rows, so the two POSTs can land in either order and well apart.
   *
   * Anchored on the end of the path — `/experiments/items` and
   * `/experiments/execute` share the prefix, and only the bare collection
   * endpoint is the dataset-mode write path.
   */
  const collectExperimentPosts = (page: Page): PostedExperiment[] => {
    const posted: PostedExperiment[] = [];
    page.on('request', (request) => {
      if (request.method() !== 'POST') return;
      if (!/\/v1\/private\/experiments\/?$/.test(new URL(request.url()).pathname)) return;
      posted.push((request.postDataJSON() ?? {}) as PostedExperiment);
    });
    return posted;
  };

  test(
    'Each variant\'s flask name lands on that variant\'s experiment',
    { tag: ['@cap:playground.run-against-dataset'] },
    async ({ project, dataset, providerKeys, backendClient, testNamespace, page }) => {
      test.setTimeout(180_000);

      const nameA = `${testNamespace}-variant-a`;
      const nameB = `${testNamespace}-variant-b`;
      const modelDisplayName = 'unreachable-model';

      await test.step('Seed a selectable provider that refuses every connection', async () => {
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-provider`,
          modelName: modelDisplayName,
        });
      });

      const playground = new PlaygroundPage(page, project.id);
      const posted = collectExperimentPosts(page);

      await test.step('Open the Playground on the seeded dataset', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName,
        });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
      });

      await test.step('Name variant A, then duplicate it into variant B', async () => {
        await playground.setExperimentName(0, nameA);
        await playground.duplicateLastVariant();
        // The duplicate copies the model and the messages but must NOT copy the
        // name: two experiments created under one name in a single run are
        // indistinguishable in the Experiments list, which is the whole problem
        // this feature exists to fix.
        expect(await playground.readExperimentName(1)).toBe('');
        await playground.setExperimentName(1, nameB);
        expect(await playground.readExperimentName(0)).toBe(nameA);
      });

      await test.step('Re-run and verify both POSTs carry their own variant\'s name', async () => {
        await playground.clickReRun();
        await expect
          .poll(() => posted.length, { timeout: 120_000, intervals: [500, 1000, 2000] })
          .toBeGreaterThanOrEqual(2);
        // Sorted rather than indexed: the two POSTs race each other, so their
        // arrival order carries no meaning and asserting on it would be flake.
        // Which name went with which variant is established server-side below,
        // via the experiment each POST actually created.
        expect(posted.map((p) => p.name).sort()).toEqual([nameA, nameB].sort());
        expect(posted.map((p) => p.dataset_name)).toEqual([dataset.name, dataset.name]);
      });

      const experiments = await test.step('The dataset carries exactly those two experiments', async () => {
        // The full set for this dataset, not a `find()` of the two expected
        // names: a run that also wrote a third, auto-named experiment would
        // satisfy a lookup-by-name and is exactly the regression worth failing
        // on. The dataset is fixture-seeded, so nothing else writes to it.
        await expect
          .poll(
            async () =>
              (await backendClient.listExperimentsWithPrefix(''))
                .filter((e) => e.datasetId === dataset.id)
                .map((e) => e.name)
                .sort(),
            { timeout: 60_000, intervals: [500, 1000, 2000, 5000] },
          )
          .toEqual([nameA, nameB].sort());

        return (await backendClient.listExperimentsWithPrefix('')).filter(
          (e) => e.datasetId === dataset.id,
        );
      });

      await test.step('Both names render on the project Experiments page', async () => {
        const experimentsPage = new ExperimentsPage(page);
        await experimentsPage.goto(project.id);
        await experimentsPage.waitForReady();
        for (const experiment of experiments) {
          await experimentsPage.expectExperimentNameInList(experiment.id, experiment.name);
        }
      });
    },
  );

  test(
    'A whitespace-only name is sent as no name at all, and the experiment is auto-named',
    { tag: ['@cap:playground.run-against-dataset'] },
    async ({ project, dataset, providerKeys, backendClient, testNamespace, page }) => {
      test.setTimeout(180_000);

      const modelDisplayName = 'unreachable-model';

      await test.step('Seed a selectable provider that refuses every connection', async () => {
        await providerKeys.createUnreachable({
          providerName: `${testNamespace}-provider`,
          modelName: modelDisplayName,
        });
      });

      const playground = new PlaygroundPage(page, project.id);
      const posted = collectExperimentPosts(page);

      await test.step('Open the Playground on the seeded dataset', async () => {
        await playground.goto();
        await playground.waitForReady();
        await playground.configureVariant(0, {
          userPrompt: '{{input}}',
          modelDisplayName,
        });
        await playground.clickRunExperiment();
        await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
        await expect(playground.loadedSourcePill()).toBeVisible();
      });

      await test.step('Fill the name with spaces only', async () => {
        await playground.setExperimentName(0, '   ');
        // Read back before running: the run below asserts what the frontend
        // does with a blank-after-trim value, which is only meaningful if the
        // input really is holding one and did not reject the keystrokes.
        expect(await playground.readExperimentName(0)).toBe('   ');
      });

      await test.step('Re-run and verify the POST omits `name` entirely', async () => {
        await playground.clickReRun();
        await expect
          .poll(() => posted.length, { timeout: 120_000, intervals: [500, 1000, 2000] })
          .toBeGreaterThanOrEqual(1);
        expect(posted).toHaveLength(1);
        // `undefined`, not `''` or `'   '`: the backend rejects a blank name
        // with 422, so a frontend that forwarded the whitespace would fail the
        // user's run outright rather than falling back to a generated name.
        expect(posted[0].name).toBeUndefined();
      });

      await test.step('The experiment landed under a generated name', async () => {
        await expect
          .poll(
            async () =>
              (await backendClient.listExperimentsWithPrefix('')).filter(
                (e) => e.datasetId === dataset.id,
              ).length,
            { timeout: 60_000, intervals: [500, 1000, 2000, 5000] },
          )
          .toBe(1);

        const [experiment] = (await backendClient.listExperimentsWithPrefix('')).filter(
          (e) => e.datasetId === dataset.id,
        );
        expect(experiment.name.trim()).not.toBe('');
        // The generated names are `<adjective>_<animal>_<digits>`; anything
        // carrying the run namespace would mean the whitespace was forwarded
        // and stored rather than replaced.
        expect(experiment.name).not.toContain(testNamespace);
      });
    },
  );
});
