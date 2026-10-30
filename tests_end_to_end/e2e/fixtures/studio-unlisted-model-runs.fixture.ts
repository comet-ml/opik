import { test as baseTest, expect } from './unlisted-registry-model.fixture';
import { RETIRED_MODEL } from './studio-rerun-optimization.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type StudioConfigSeed } from '../core/backend';

/**
 * The one model parameter every seeded run carries.
 *
 * `temperature` because `StudioModelParameters.ALLOWED` (opik#9245, OPIK-9002)
 * names it, so `POST /v1/private/optimizations` accepts it on every provider —
 * and a `studio_config` carrying an empty parameter map is a shape no real run
 * has, which is not the thing these specs want to be testing. The value is far
 * from any model's default so that a panel showing it is unambiguous, for
 * whoever extends this to assert the settings half.
 */
export const SAVED_TEMPERATURE = 0.9;

/** The reference key the Equals metric scores on, and the dataset field holding it. */
const REFERENCE_KEY = 'label';

const DATASET_ITEMS = [
  { question: 'first review', label: 'positive' },
  { question: 'second review', label: 'negative' },
];

const SYSTEM_PROMPT =
  'Classify the sentiment of the review as exactly "positive" or "negative".';
const USER_PROMPT = '{{question}}';

/** One seeded run, and the model its `studio_config` was written with. */
export interface StudioUnlistedRunRef {
  optimizationId: string;
  /** The run's own name, as the rerun form seeds it into the Name field. */
  name: string;
  /** The model id stored in `studio_config.llm_model.model`, verbatim. */
  savedModel: string;
}

export interface StudioUnlistedModelRunsRef {
  /** Saved on a model the registry SERVES and no picker lists. */
  unlisted: StudioUnlistedRunRef;
  /** Saved on a model the pickers list — the "nothing was swapped" control. */
  listed: StudioUnlistedRunRef;
  /**
   * Saved on an id no registry knows — the POSITIVE control, which must be
   * replaced. Without it, "the unlisted run kept its model" is equally
   * satisfied by a build whose availability check never fires at all.
   */
  retired: StudioUnlistedRunRef;
  datasetName: string;
  datasetId: string;
}

export interface StudioUnlistedModelRunsFixtures {
  studioUnlistedModelRuns: StudioUnlistedModelRunsRef;
}

/**
 * Three completed Studio optimizations over one dataset, differing in their
 * saved prompt model and in nothing else.
 *
 * Seeded, never launched. What the spec asserts is what the rerun FORM does
 * with a stored config, which is independent of how an optimizer produced it,
 * so launching a real run would add LLM spend and nondeterminism to buy
 * nothing. It is also the only way this flow is reachable:
 * `optimization-studio.spec.ts` is `describe.skip`'d on LLM cost, so nothing
 * else exercises the new-run form.
 *
 * ## Why three and not one
 *
 * `NewRunSidebar` decides whether a saved model survives a rerun by asking
 * whether it is in `seedModels` — the picker's options PLUS the saved models a
 * configured provider still serves. The interesting failure is that check
 * reading the dropdown-visible list instead of the full registry, which would
 * silently re-point every run saved on one of the eight ids opik#9277 hid. One
 * run cannot tell that apart from a deployment that keeps every saved model or
 * from one that replaces every saved model, so all three verdicts are seeded at
 * once: kept-and-unlisted, kept-and-listed, replaced.
 *
 * ## Why the configs are read back
 *
 * The create answers 204 with no body and the backend enriches `studio_config`
 * on the way in, so the fixture asserts each stored payload really holds the
 * model it was seeded with before a browser opens. Without that, "the form
 * hydrated the saved id" would pass against a run whose id the backend had
 * normalised to something else — a test that cannot fail, which reads as
 * coverage forever.
 *
 * ## Teardown
 *
 * Here rather than in the spec so it survives a mid-test failure, and it
 * deletes more than the project does: `ProjectService.delete` removes only the
 * project row, and the run-prefix sweep in `global-teardown.ts` does not know
 * about optimizations.
 */
export const test = baseTest.extend<StudioUnlistedModelRunsFixtures>({
  studioUnlistedModelRuns: async (
    { sdkClient, backendClient, project, testNamespace, unlistedRegistryModel },
    use,
    testInfo,
  ) => {
    const datasetName = `${testNamespace}-unlisted-ds`;
    const dataset = await sdkClient.python.createDataset({
      project_name: project.name,
      name: datasetName,
      description: 'studio rerun on a model the pickers do not list',
      items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
    });

    const studioConfigFor = (model: string): StudioConfigSeed => ({
      datasetName,
      prompt: {
        messages: [
          { role: 'system', content: SYSTEM_PROMPT },
          { role: 'user', content: USER_PROMPT },
        ],
      },
      llmModel: { model, parameters: { temperature: SAVED_TEMPERATURE } },
      evaluation: {
        metrics: [
          { type: 'equals', parameters: { reference_key: REFERENCE_KEY, case_sensitive: false } },
        ],
      },
      optimizer: { type: 'gepa', parameters: {} },
    });

    const seed = async (key: string, model: string): Promise<StudioUnlistedRunRef> => {
      const optimizationId = uuid7();
      const name = `${testNamespace}-${key}`;
      await backendClient.createOptimization({
        id: optimizationId,
        name,
        datasetName,
        projectName: project.name,
        objectiveName: REFERENCE_KEY,
        status: 'completed',
        studioConfig: studioConfigFor(model),
      });

      // The premise, asserted rather than assumed — see the header.
      const stored = await backendClient.getOptimizationStudioConfig(optimizationId);
      expect(
        stored,
        `the '${key}' run must really carry a studio_config, or the rerun sidebar ignores it`,
      ).toBeDefined();
      expect(
        stored!.llmModel.model,
        `and it must name '${model}' verbatim, or the hydration assertions test the wrong string`,
      ).toBe(model);

      return { optimizationId, name, savedModel: model };
    };

    const ref: StudioUnlistedModelRunsRef = {
      unlisted: await seed('unlisted', unlistedRegistryModel.unlisted),
      listed: await seed('listed', unlistedRegistryModel.listed),
      retired: await seed('retired', RETIRED_MODEL),
      datasetName,
      datasetId: dataset.id,
    };

    await testInfo.attach('opik.studioUnlistedModelRuns', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      const safe = async (what: string, fn: () => Promise<unknown>): Promise<void> => {
        try {
          await fn();
        } catch (err) {
          console.warn(`[studioUnlistedModelRuns fixture] delete warning for ${what}:`, err);
        }
      };
      // The optimizations before the dataset they reference, so neither is
      // removed from under a still-referencing parent.
      for (const run of [ref.unlisted, ref.listed, ref.retired]) {
        await safe(`optimization ${run.optimizationId}`, () =>
          backendClient.deleteOptimization(run.optimizationId),
        );
      }
      await safe(`dataset ${datasetName}`, () => backendClient.deleteDataset(dataset.id));
    }
  },
});

export { expect };
