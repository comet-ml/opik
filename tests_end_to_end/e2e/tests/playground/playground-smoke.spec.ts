import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ConfigurationPage } from '@e2e/pom/configuration.page';

/**
 * Pick an Anthropic-or-OpenAI model from env; provision the matching provider key
 * via the UI if it isn't already configured. Returns the model display name to use
 * with the playground.
 */
async function ensureModelAvailable(page: import('@playwright/test').Page): Promise<string> {
  const anthropic = process.env.ANTHROPIC_API_KEY;
  const openai = process.env.OPENAI_API_KEY;
  if (!anthropic && !openai) {
    test.skip(true, 'Neither ANTHROPIC_API_KEY nor OPENAI_API_KEY is set');
    return ''; // unreachable
  }
  const cfg = new ConfigurationPage(page);
  await cfg.gotoAiProviders();
  if (anthropic) {
    await cfg.ensureProviderConfigured('Anthropic', anthropic);
    return 'Claude Haiku 4.5';
  }
  await cfg.ensureProviderConfigured('OpenAI', openai!);
  return 'GPT 4o Mini';
}

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
      await playground.submitRunExperimentDialog({ mode: 'dataset', entityName: dataset.name });
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
