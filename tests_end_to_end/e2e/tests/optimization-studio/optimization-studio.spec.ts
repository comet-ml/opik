import { test, expect } from '@e2e/fixtures';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';

/**
 * Sentiment-classification seed: `text` is the prompt variable, `label` is the
 * Equals-metric reference key. Deterministic Equals metric (no LLM judge) keeps
 * the run fast and stable. NOTE: the objective score can legitimately be 0 on a
 * healthy run (a weak model won't emit exact-match labels), so tests assert the
 * run is *healthy and complete*, never that it *improved*.
 */
const SENTIMENT_ITEMS = [
  { text: 'I absolutely loved this movie, it was fantastic!', label: 'positive' },
  { text: 'Terrible film, a complete waste of time.', label: 'negative' },
  { text: 'Best cinematic experience I have had all year.', label: 'positive' },
  { text: 'Boring and poorly acted, I walked out.', label: 'negative' },
];

const PROMPT = 'Classify the sentiment of this movie review as exactly "positive" or "negative": {{text}}';

type SdkClient = Parameters<Parameters<typeof test>[2]>[0]['sdkClient'];

function seedSentimentDataset(sdkClient: SdkClient, projectName: string, name: string) {
  return sdkClient.python.createDataset({
    project_name: projectName,
    name,
    description: 'sentiment classification for optimization studio smoke',
    items: SENTIMENT_ITEMS as unknown as Array<Record<string, unknown>>,
  });
}

test.describe('Optimization Studio — core', { tag: ['@t2-cuj', '@t1-stsaas', '@optimization-studio'] }, () => {
  test('the new-run form renders its sections and enables Optimize only once valid', async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    // A form-only test still needs a project-associated dataset for the picker.
    const dataset = await test.step('Seed a dataset for the picker', async () =>
      seedSentimentDataset(sdkClient, project.name, `${testNamespace}-form-ds`));

    const studio = new OptimizationStudioPage(page, project.id);
    await studio.gotoNew();

    await test.step('Form renders with GEPA + Equals defaults and Optimize disabled', async () => {
      await studio.assertFormRenders();
    });

    await test.step('Optimize enables once dataset + prompt + reference key are set', async () => {
      await studio.selectDataset(dataset.name);
      await studio.setUserPrompt(PROMPT);
      await studio.setReferenceKey('label');
      await expect(page.getByRole('button', { name: 'Optimize prompt' })).toBeEnabled();
    });

    await test.step('Cleanup', async () => {
      await backendClient.deleteDataset(dataset.id);
    });
  });

  test('launches a GEPA + Equals run from the studio UI and it completes end-to-end', async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    envConfig,
    page,
  }) => {
    test.setTimeout(300_000);

    const modelDisplayName = await test.step(
      'Ensure an LLM provider is available',
      async () => ensureModelAvailable(page),
    );

    const datasetName = `${testNamespace}-sentiment`;
    const dataset = await test.step('Seed a sentiment dataset associated with the project', async () =>
      seedSentimentDataset(sdkClient, project.name, datasetName));

    const studio = new OptimizationStudioPage(page, project.id);

    const optimizationId = await test.step('Configure and start the run in the studio', async () => {
      await studio.gotoNew();
      await studio.assertFormRenders();
      return studio.configureAndStart({
        datasetName,
        prompt: PROMPT,
        modelDisplayName,
        referenceKey: 'label',
      });
    });

    const completed = await test.step('Wait for the run to reach completed (backend poll)', async () =>
      backendClient.pollOptimizationStatus(optimizationId, 'completed', { timeoutMs: 240_000 }));

    await test.step('Assert the completed run is healthy (structural, not improvement)', async () => {
      expect(completed.status).toBe('completed');
      expect(completed.objectiveName).toBe('equals');
      expect(completed.datasetName).toBe(datasetName);
      expect(completed.numTrials, 'the optimizer ran at least one trial').toBeGreaterThanOrEqual(1);
      // Scores are produced and in range — NOT that best > baseline (a healthy
      // run can score 0 with a weak model).
      for (const s of [completed.baselineObjectiveScore, completed.bestObjectiveScore]) {
        expect(s, 'objective score present').not.toBeNull();
        expect(s as number).toBeGreaterThanOrEqual(0);
        expect(s as number).toBeLessThanOrEqual(1);
      }
    });

    await test.step('Confirm the UI detail page reflects a healthy completed run', async () => {
      await studio.gotoDetail(optimizationId);
      await studio.expectStatus('completed');
      await studio.expectBestTrialConfig({ algorithm: 'GEPA optimizer', metric: 'Equals' });
      await studio.openTrialsTab();
      expect(await studio.trialRowCount(), 'at least one trial row').toBeGreaterThanOrEqual(1);
      await studio.expectBestTrial();
    });

    await test.step('Studio logs are downloadable and show the optimizer ran', async () => {
      const logs = await backendClient.getOptimizationLogs(optimizationId);
      // The backend must always produce a logs URL for a completed run.
      expect(logs.url, 'backend returned a presigned logs URL').toBeTruthy();

      // On local OSS the presigned URL uses the internal `minio:9000` host, which
      // is genuinely unreachable from a runner outside the compose network — no
      // deployment config can change that from here, so we can't enforce the
      // download locally. On EVERY OTHER target (cloud / self-hosted / stsaas)
      // the presign host MUST be reachable and the log content MUST download —
      // if it doesn't, the deployment's object-store config is wrong and the
      // in-app logs viewer would be broken for users, so we FAIL, not skip.
      if (envConfig.deployment === 'oss') {
        if (!logs.urlReachable) {
          // eslint-disable-next-line no-console
          console.warn(
            `[optimization-studio] local OSS: logs URL not reachable from the runner ` +
              `(${logs.url}) — expected for MinIO's internal host; content check skipped.`,
          );
        }
        return;
      }

      expect(
        logs.urlReachable,
        `logs presigned URL must be reachable on a ${envConfig.deployment} deployment — ` +
          `if this fails, the object-store presign host (${logs.url}) is not client-reachable ` +
          `and the in-app logs viewer will be broken; the Studio object-store config needs fixing`,
      ).toBe(true);
      expect(logs.content, 'logs content downloaded').toBeTruthy();
      expect(logs.content!.length, 'logs are non-empty').toBeGreaterThan(0);
      // Lenient markers: the optimizer subprocess ran end-to-end and reported
      // success — without coupling to the exact log format.
      expect(
        /Opik Optimizer SDK/i.test(logs.content!) || /"success":\s*true/.test(logs.content!),
        'logs contain the optimizer banner or a success marker',
      ).toBe(true);
    });

    await test.step('Cleanup: delete the optimization and dataset', async () => {
      await backendClient.deleteOptimization(optimizationId);
      await backendClient.deleteDataset(dataset.id);
    });
  });
});

test.describe('Optimization Studio — variant', { tag: ['@t2-cuj', '@t1-stsaas', '@optimization-studio'] }, () => {
  test('launches a Hierarchical Reflective + Equals run and it completes end-to-end', async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(360_000);

    const modelDisplayName = await test.step(
      'Ensure an LLM provider is available',
      async () => ensureModelAvailable(page),
    );

    const datasetName = `${testNamespace}-hr-sentiment`;
    const dataset = await test.step('Seed a sentiment dataset', async () =>
      seedSentimentDataset(sdkClient, project.name, datasetName));

    const studio = new OptimizationStudioPage(page, project.id);

    const optimizationId = await test.step('Configure and start a Hierarchical Reflective run', async () => {
      await studio.gotoNew();
      return studio.configureAndStart({
        datasetName,
        prompt: PROMPT,
        modelDisplayName,
        referenceKey: 'label',
        optimizer: 'Hierarchical Reflective',
      });
    });

    const completed = await test.step('Wait for the run to reach completed', async () =>
      backendClient.pollOptimizationStatus(optimizationId, 'completed', { timeoutMs: 300_000 }));

    await test.step('Assert the completed run is healthy', async () => {
      expect(completed.status).toBe('completed');
      expect(completed.objectiveName).toBe('equals');
      expect(completed.numTrials).toBeGreaterThanOrEqual(1);
    });

    await test.step('Confirm the UI shows Hierarchical Reflective completed', async () => {
      await studio.gotoDetail(optimizationId);
      await studio.expectStatus('completed');
      await studio.expectBestTrialConfig({ algorithm: 'Hierarchical Reflective', metric: 'Equals' });
    });

    await test.step('Cleanup', async () => {
      await backendClient.deleteOptimization(optimizationId);
      await backendClient.deleteDataset(dataset.id);
    });
  });
});
