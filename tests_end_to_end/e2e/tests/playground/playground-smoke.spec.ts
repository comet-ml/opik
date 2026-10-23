import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

/**
 * T1 Playground smoke: run prompts against a seeded dataset → Re-run asks the
 * backend to execute the run, which creates the experiment itself →
 * backendClient confirms the experiment landed.
 *
 * The Playground has no separate "Save as experiment" step — every Re-run click
 * creates a new experiment automatically (verified Phase 3 discovery).
 */
test.describe('Playground — smoke', { tag: ['@t1-smoke', '@llm-daily', '@area:playground', '@cap:playground.compose-run-prompt', '@cap:playground.run-against-dataset'] }, () => {
  test('Run prompts against a dataset auto-creates an experiment', async ({
    dataset,
    project,
    backendClient,
    page,
  }) => {
    test.setTimeout(180_000);

    const modelDisplayName = await test.step('Ensure a model is available via the Configuration UI', async () => {
      return ensureModelAvailable(page);
    });

    const playground = new PlaygroundPage(page, project.id);

    let experimentId!: string;

    await test.step('Navigate to Playground, configure variant, load dataset, run', async () => {
      await playground.goto();
      await playground.waitForReady();
      await playground.configureVariant(0, {
        systemPrompt: 'Always reply with the literal text OK.',
        userPrompt: '{{input}}',
        modelDisplayName,
      });
      await playground.clickRunExperiment();
      await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
      await expect(playground.loadedSourcePill()).toBeVisible();
      await playground.waitForRunReady({ expectedRows: 3 });

      // Re-run makes one POST /experiments/execute (202): the backend creates the
      // experiment and works the dataset items, so the browser no longer POSTs
      // /experiments itself. Latched BEFORE the click, since registering
      // afterwards races the request. 30s is enough: execute answers once the
      // run is queued, not when it finishes.
      const executeResponse = page.waitForResponse(
        (r) =>
          new URL(r.url()).pathname.endsWith('/v1/private/experiments/execute') &&
          r.request().method() === 'POST',
        { timeout: 30_000 },
      );

      await playground.clickReRun();

      const response = await executeResponse;
      expect(response.status()).toBe(202);
      const { experiments } = (await response.json()) as {
        experiments: { experiment_id: string; prompt_index: number }[];
      };
      // One prompt variant, so one experiment.
      expect(experiments).toHaveLength(1);
      experimentId = experiments[0].experiment_id;

      await playground.waitForRunsComplete({ expectedRows: 3, timeoutMs: 120_000 });
      expect(await playground.countCompletedOutputCells()).toBeGreaterThanOrEqual(3);
    });

    await test.step('SDK-verify the experiment landed under the dataset (auto-named)', async () => {
      // The exact experiment execute named, on the seeded dataset. The poll
      // only has to cover read-after-write visibility; the name is
      // auto-generated server-side (e.g. "redundant_landaulet_8244").
      await expect
        .poll(
          async () => {
            const all = await backendClient.listExperimentsWithPrefix('');
            return all.some((e) => e.id === experimentId && e.datasetId === dataset.id);
          },
          { timeout: 60_000, intervals: [500, 1000, 2000, 5000] },
        )
        .toBe(true);
    });
  });
});
