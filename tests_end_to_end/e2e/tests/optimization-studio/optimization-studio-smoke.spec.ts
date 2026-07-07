import { test, expect } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

/**
 * Sentiment-classification seed: `text` is the prompt variable, `label` is the
 * Equals-metric reference key. Mirrors the python-backend studio regression test
 * (deterministic Equals metric, no LLM judge) so the run stays fast and stable.
 */
const SENTIMENT_ITEMS = [
  { text: 'I absolutely loved this movie, it was fantastic!', label: 'positive' },
  { text: 'Terrible film, a complete waste of time.', label: 'negative' },
  { text: 'Best cinematic experience I have had all year.', label: 'positive' },
  { text: 'Boring and poorly acted, I walked out.', label: 'negative' },
];

const PROMPT = 'Classify the sentiment of this movie review as exactly "positive" or "negative": {{text}}';

test.describe('Optimization Studio — smoke', { tag: ['@t1-smoke', '@optimization-studio'] }, () => {
  test('launches a GEPA + Equals run from the studio UI and it completes end-to-end', async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(300_000);

    const modelDisplayName = await test.step(
      'Ensure an LLM provider is available (Anthropic Haiku from env)',
      async () => ensureModelAvailable(page),
    );

    const datasetName = `${testNamespace}-sentiment`;
    const dataset = await test.step('Seed a sentiment dataset associated with the project', async () => {
      const created = await sdkClient.python.createDataset({
        project_name: project.name,
        name: datasetName,
        description: 'sentiment classification for optimization studio smoke',
        items: SENTIMENT_ITEMS as unknown as Array<Record<string, unknown>>,
      });
      return created;
    });

    const studio = new OptimizationStudioPage(page, project.id);

    const optimizationId = await test.step('Configure and start the run in the studio', async () => {
      await studio.gotoNew();
      await studio.assertDefaults('GEPA optimizer', 'Equals');
      return studio.configureAndStart({
        datasetName,
        prompt: PROMPT,
        modelDisplayName,
        referenceKey: 'label',
      });
    });

    const completed = await test.step('Wait for the run to reach completed (backend poll)', async () => {
      return backendClient.pollOptimizationStatus(optimizationId, 'completed', {
        timeoutMs: 240_000,
      });
    });

    await test.step('Assert the completed run is healthy', async () => {
      expect(completed.status).toBe('completed');
      expect(completed.objectiveName).toBe('equals');
      expect(completed.datasetName).toBe(datasetName);
      expect(completed.numTrials, 'the optimizer ran at least one trial').toBeGreaterThanOrEqual(1);
    });

    await test.step('Confirm the UI reflects completion', async () => {
      await studio.gotoDetail(optimizationId);
      await studio.expectStatus('completed');
    });

    await test.step('Cleanup: delete the optimization and dataset (no project cascade)', async () => {
      await backendClient.deleteOptimization(optimizationId);
      await backendClient.deleteDataset(dataset.id);
    });
  });
});
