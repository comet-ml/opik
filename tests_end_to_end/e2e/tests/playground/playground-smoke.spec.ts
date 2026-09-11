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
test.describe('Playground — smoke', { tag: ['@t1-smoke', '@area:playground', '@cap:playground.compose-run-prompt', '@cap:playground.run-against-dataset'] }, () => {
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

    let experimentCreated!: Promise<unknown>;

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

      // The frontend queues experiment creation independently of the trace/span
      // batches that paint the result rows, so the POST can land before or well
      // after the table finishes rendering. Latch it BEFORE clicking Re-run —
      // registering afterwards races the request and can miss it entirely.
      // Match the collection endpoint exactly: `items` and `finish` share the prefix.
      experimentCreated = page.waitForResponse(
        (r) =>
          /\/v1\/private\/experiments\/?$/.test(new URL(r.url()).pathname) &&
          r.request().method() === 'POST' &&
          r.ok(),
        { timeout: 120_000 },
      );

      await playground.clickReRun();
      await playground.waitForRunsComplete({ expectedRows: 3, timeoutMs: 120_000 });
      expect(await playground.countOutputRows()).toBeGreaterThanOrEqual(3);
    });

    await test.step('SDK-verify an experiment landed under the project (auto-named)', async () => {
      // Wait on the creation request itself rather than guessing how long the
      // write takes; the poll below then only has to cover read-after-write
      // visibility. We match on datasetId since the name is auto-generated
      // server-side (e.g. "redundant_landaulet_8244").
      await experimentCreated;

      await expect
        .poll(
          async () => {
            const all = await backendClient.listExperimentsWithPrefix('');
            return all.filter((e) => e.datasetId === dataset.id).length;
          },
          { timeout: 60_000, intervals: [500, 1000, 2000, 5000] },
        )
        .toBeGreaterThanOrEqual(1);
    });
  });
});
