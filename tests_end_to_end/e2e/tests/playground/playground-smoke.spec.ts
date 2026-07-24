import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

/**
 * T1 Playground smoke: run prompts against a seeded dataset → Re-run auto-creates
 * an experiment server-side → backendClient confirms the experiment landed.
 *
 * The Playground has no separate "Save as experiment" step — every Re-run click
 * creates a new experiment automatically (verified Phase 3 discovery).
 */
test.describe('Playground — smoke', { tag: ['@t1-smoke', '@playground'] }, () => {
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
      await playground.clickReRun();
      await playground.waitForRunsComplete({ expectedRows: 3, timeoutMs: 120_000 });
      expect(await playground.countOutputRows()).toBeGreaterThanOrEqual(3);
    });

    await test.step('SDK-verify an experiment landed under the project (auto-named)', async () => {
      // The playground generates experiment names server-side (e.g.
      // "redundant_landaulet_8244"), and the experiment record is written
      // shortly AFTER the result rows render. Poll briefly to ride out that
      // window. We match on datasetId since the name is auto-generated.
      await expect
        .poll(
          async () => {
            const all = await backendClient.listExperimentsWithPrefix('');
            return all.filter((e) => e.datasetId === dataset.id).length;
          },
          { timeout: 15_000, intervals: [500, 1000, 2000] },
        )
        .toBeGreaterThanOrEqual(1);
    });
  });
});
