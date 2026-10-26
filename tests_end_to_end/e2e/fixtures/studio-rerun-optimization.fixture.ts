import { test as baseTest, expect } from './open-router-provider.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type StudioConfigSeed } from '../core/backend';

/**
 * The model id the seeded run was "saved on".
 *
 * Deliberately a shape no registry knows — the same fabricated id
 * `OptimizationsNewPageContent.test.tsx` uses — rather than a real model the
 * workspace happens to lack a key for. "Unavailable" has to be a property of
 * the id, not of which provider keys this workspace holds: pinned to a real
 * model, the spec would pass on a bare workspace and silently stop testing the
 * replacement path the moment someone added that provider's key.
 */
export const RETIRED_MODEL = 'openai/r3c-retired-model';

/**
 * Model parameters the saved run carried, chosen so that inheriting them is
 * visible whichever model the deployment swaps in.
 *
 * Every value is far from any model's default — nothing in the registry opens a
 * token cap at 300 or `temperature` at 0.9 — so a replacement's panel showing
 * one is proof the saved settings leaked across the swap, which is the bug:
 * OpenRouter's nested `reasoning` makes OpenAI answer 400.
 *
 * All THREE token-cap spellings, because the replacement is whatever this
 * deployment's registry offers first and each provider family reads a different
 * one. `toFormModelConfig` aliases `max_tokens` ↔ `max_completion_tokens` for
 * us, but nothing bridges Gemini's `max_output_tokens` — so pinned to one
 * spelling, the seed would silently stop discriminating the day the first
 * available model became a Gemini one. A run created through the API can hold
 * any parameter set, which is the case the converter's own `pick` exists for.
 */
export const SAVED_MODEL_PARAMETERS = {
  max_tokens: 300,
  max_completion_tokens: 300,
  max_output_tokens: 300,
  temperature: 0.9,
} as const;

/** The reference key the Equals metric scores on, and the dataset field holding it. */
const REFERENCE_KEY = 'label';

const DATASET_ITEMS = [
  { question: 'first review', label: 'positive' },
  { question: 'second review', label: 'negative' },
];

const SYSTEM_PROMPT =
  'Classify the sentiment of the review as exactly "positive" or "negative".';
const USER_PROMPT = '{{question}}';

export interface StudioRerunOptimizationRef {
  optimizationId: string;
  /** The run's own name, as the rerun form seeds it into the Name field. */
  name: string;
  datasetId: string;
  datasetName: string;
  /** The unrunnable model id the run was saved on — what the notice must name. */
  savedModel: string;
  /** The parameters the run was saved with, which the replacement must NOT inherit. */
  savedParameters: Readonly<Record<string, number>>;
}

export interface StudioRerunOptimizationFixtures {
  studioRerunOptimization: StudioRerunOptimizationRef;
}

/**
 * A completed Studio optimization whose saved prompt model no deployment can
 * serve — the state the rerun sidebar has to recover from by naming a
 * replacement.
 *
 * Seeded, not launched. What the spec asserts is what the FORM does with a
 * stored config, which is independent of how an optimizer produced it, so
 * launching a real run would add LLM spend and nondeterminism to buy nothing.
 * That is also the only way this flow is reachable at all right now:
 * `optimization-studio.spec.ts` is `describe.skip`'d on LLM cost, so nothing
 * exercises the new-run form.
 *
 * ## Why the config is read back
 *
 * The create answers 204 with no body and the backend enriches `studio_config`
 * on the way in, so the fixture asserts the stored payload really holds the
 * retired model and both off-default parameters before the spec opens a
 * browser. Without that, a payload the backend dropped would leave "the
 * replacement starts from its own defaults" passing against a run that never
 * had settings to inherit — a test that cannot fail, which reads as coverage
 * forever.
 *
 * ## Teardown
 *
 * Here rather than in the spec so it survives a mid-test failure, and it
 * deletes more than the project does: `ProjectService.delete` removes only the
 * project row, and the run-prefix sweep in `global-teardown.ts` does not know
 * about optimizations.
 */
export const test = baseTest.extend<StudioRerunOptimizationFixtures>({
  studioRerunOptimization: async (
    { sdkClient, backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-rerun-ds`;
    const optimizationId = uuid7();
    const name = `${testNamespace}-rerun-unavailable`;

    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'studio rerun on an unavailable saved model',
      items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
    });

    const studioConfig: StudioConfigSeed = {
      datasetName,
      prompt: {
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: USER_PROMPT },
        ],
      },
      llmModel: { model: RETIRED_MODEL, parameters: { ...SAVED_MODEL_PARAMETERS } },
      evaluation: {
        metrics: [
          { type: 'equals', parameters: { reference_key: REFERENCE_KEY, case_sensitive: false } },
        ],
      },
      optimizer: { type: 'gepa', parameters: {} },
    };

    await backendClient.createOptimization({
      id: optimizationId,
      name,
      datasetName,
      projectName: project.name,
      objectiveName: REFERENCE_KEY,
      status: 'completed',
      studioConfig,
    });

    // The premise, asserted rather than assumed — see the header.
    const stored = await backendClient.getOptimizationStudioConfig(optimizationId);
    expect(
      stored,
      'the seeded run must really carry a studio_config, or the rerun sidebar ignores it',
    ).toBeDefined();
    expect(
      stored!.llmModel.model,
      'the stored prompt model is the retired id the notice has to name',
    ).toBe(RETIRED_MODEL);
    expect(
      stored!.llmModel.parameters,
      'and it really holds the off-default settings a replacement must not inherit',
    ).toMatchObject({ ...SAVED_MODEL_PARAMETERS });

    const ref: StudioRerunOptimizationRef = {
      optimizationId,
      name,
      datasetId: dataset.id,
      datasetName,
      savedModel: RETIRED_MODEL,
      savedParameters: { ...SAVED_MODEL_PARAMETERS },
    };
    await testInfo.attach('opik.studioRerunOptimization', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[studioRerunOptimization fixture] delete warning for ${what}:`, err);
        }
      };
      // The optimization before the dataset it references, so neither is
      // removed from under a still-referencing parent.
      await safe(`optimization ${optimizationId}`, () =>
        backendClient.deleteOptimization(optimizationId),
      );
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect };
