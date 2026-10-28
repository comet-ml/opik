import { test, expect } from '@e2e/fixtures';
import type { BackendClient } from '@e2e/core/backend';
import { uuid7 } from '@e2e/core/backend';
import { OptimizationStudioPage } from '@e2e/pom/optimization-studio.page';
import type { OptimizerName } from '@e2e/pom/optimization-studio.page';

/**
 * The Studio new-run form only ever saves model parameters the backend allows
 * (opik#9245, OPIK-9002).
 *
 * `StudioModelParameters.ALLOWED` is a hand-maintained list of the LiteLLM
 * parameters the Studio runner passes on, kept in step by comment with
 * `_ALLOWED_PARAMS` in the Python backend's `optimizer_runner.py`. Anything else
 * in `llm_model.parameters` — or in `optimizer.parameters.model_parameters` —
 * is refused with a 400 when the optimization is saved, by name.
 *
 * The question that list raises is not whether the rule works; it is whether
 * the real form stays inside it. `OptimizationsResourceTest` asserts one
 * hand-written payload plus a case called "the parameters the Studio UI saves
 * are accepted" — but that payload is written by the test, not built by the
 * form, so a control added to the parameters panel tomorrow would 400 every
 * real user with nothing failing in CI. That is what this spec answers: the
 * payload is captured off the form itself and then replayed at the server.
 *
 * ## Nothing is launched, and nothing can be billed
 *
 * Two independent guards, because the cost of getting this wrong is a real
 * optimizer run:
 *
 *  1. The create POST is route-intercepted and fulfilled in the browser, so the
 *     request never reaches the backend. The route matches on the request's
 *     PATH ending in `/v1/private/optimizations/` — with the trailing slash the
 *     form actually sends (`OPTIMIZATIONS_REST_ENDPOINT`), which a glob written
 *     without it misses while looking correct. The spec asserts the handler
 *     fired, and then asserts server-side that the project still holds no runs,
 *     so a missed intercept fails here instead of quietly launching GEPA.
 *  2. The only selectable model belongs to a Custom provider this test seeds,
 *     whose base URL refuses every connection. Even a run that somehow started
 *     could spend nothing.
 *
 * This is why the form is driven on a seeded provider rather than on whatever
 * the workspace happens to hold: it makes the spec deterministic across
 * deployments AND removes the spend risk, at the price of exercising one
 * provider's parameter panel rather than four. The panel shape is per-provider
 * (`CustomModelConfig` here), so this is a narrower claim than the exploration's
 * seven hand-captured payloads — see "What this does not claim" below.
 *
 * ## What this does not claim
 *
 * - Only the Custom provider's panel is captured. The OpenAI, Anthropic,
 *   Gemini and OpenRouter panels mount different control sets
 *   (`reasoning_effort`, `thinking`, `max_tokens` instead of
 *   `max_completion_tokens`), and selecting one of their models would put a
 *   paid model on the form.
 * - `optimizer.parameters.model_parameters` is driven at the API only. Neither
 *   optimizer the Algorithm dropdown offers puts a `model_parameters` key in
 *   `optimizer.parameters` at all (GEPA sends `{verbose, seed}`, Hierarchical
 *   Reflective `{convergence_threshold, verbose, seed}`), so the form cannot
 *   reach that guarded path and the second test drives it directly.
 */

/**
 * The parameters `StudioModelParameters.ALLOWED` names, verbatim.
 *
 * Duplicated here rather than derived, deliberately: the point of the spec is
 * to compare what the form sends against this list, and a list read from the
 * same place the form reads would compare nothing. If the backend adds a
 * parameter, this copy is meant to need updating — that edit is the review
 * moment the JUnit test does not create.
 */
const ALLOWED_MODEL_PARAMETERS = new Set([
  'model',
  'messages',
  'tools',
  'tool_choice',
  'parallel_tool_calls',
  'response_format',
  'metadata',
  'num_retries',
  'timeout',
  'stream',
  'extra_headers',
  'n',
  'seed',
  'logprobs',
  'top_logprobs',
  'temperature',
  'top_p',
  'max_tokens',
  'max_completion_tokens',
  'stop',
  'presence_penalty',
  'frequency_penalty',
  'reasoning_effort',
  'custom_parameters',
  'top_k',
  'min_p',
  'top_a',
  'repetition_penalty',
  'user',
  'safety_identifier',
  'logit_bias',
  'verbosity',
  'prediction',
  'stream_options',
  'thinking',
]);

/** A key no provider list holds, so the refusal is about the ALLOWLIST. */
const REFUSED_PARAMETER = 'service_tier';

/**
 * A `custom_parameters` entry that picks a credential.
 *
 * `custom_parameters` is itself allowed — it is the escape hatch for
 * provider-specific body keys — so its CONTENTS are screened separately against
 * `DESTINATION_KEYS`: a run whose extra body could set `api_key` or `api_base`
 * would be able to retarget the gateway at a host of the author's choosing.
 */
const REFUSED_CUSTOM_PARAMETER = 'api_key';

/** A `custom_parameters` entry that picks nothing, as the accepted control. */
const ALLOWED_CUSTOM_PARAMETER = 'qa_marker';

/** Temperature and max-output-tokens values off the Custom panel's defaults (0 and 4000). */
const NUDGED_TEMPERATURE = 0.42;
const NUDGED_MAX_TOKENS = 1234;

/** The optimizers the Algorithm dropdown offers on this build. */
const OPTIMIZERS: readonly OptimizerName[] = ['GEPA optimizer', 'Hierarchical Reflective'];

/** The create POST's body, as the form builds it. */
interface CapturedCreate {
  optimizer: OptimizerName;
  studioConfig: {
    llm_model?: { model?: string; parameters?: Record<string, unknown> };
    optimizer?: { type?: string; parameters?: Record<string, unknown> };
    [key: string]: unknown;
  };
}

/** Every key of `parameters` that `ALLOWED_MODEL_PARAMETERS` does not name. */
function refusedKeys(parameters: Record<string, unknown> | undefined): string[] {
  return Object.keys(parameters ?? {})
    .filter((key) => !ALLOWED_MODEL_PARAMETERS.has(key))
    .sort();
}

/**
 * A `studio_config` the server accepts, with `llm_model.parameters` replaced.
 *
 * Built from the shape `convertFormDataToStudioConfig` sends rather than from a
 * captured payload, so the negative test does not depend on the browser half
 * having run — the two tests fail independently, which is the point of them
 * being two.
 */
function studioConfigWith(
  datasetName: string,
  model: string,
  parameters: Record<string, unknown>,
  optimizerParameters: Record<string, unknown> = { verbose: 1, seed: 42 },
): Record<string, unknown> {
  return {
    dataset_name: datasetName,
    prompt: {
      messages: [
        { role: 'system', content: 'Answer with the label only.' },
        { role: 'user', content: '{{input}}' },
      ],
    },
    llm_model: { model, parameters },
    evaluation: { metrics: [{ type: 'equals', parameters: { reference_key: 'expected_output' } }] },
    optimizer: { type: 'gepa', parameters: optimizerParameters },
  };
}

/**
 * Assert the project holds no optimization runs, registering anything it does
 * hold for deletion first.
 *
 * The real guard behind the route intercept. A create the intercept missed
 * reaches the backend and launches an optimizer, and the browser cannot tell
 * the difference — the fake 201 and a real one both navigate. Registering
 * before asserting means a leak is cleaned up even though this step then fails.
 */
async function expectNoRunsCreated(
  backendClient: BackendClient,
  projectId: string,
  registerCleanup: (id: string, label: string) => void,
  context: string,
): Promise<void> {
  const runs = await backendClient.listOptimizations({ projectId });
  for (const run of runs) registerCleanup(run.id, `leaked run ${run.name}`);
  expect(
    runs.map((r) => r.name),
    `${context}: the create POST was intercepted, so no optimization reached the backend`,
  ).toEqual([]);
}

test.describe(
  'Optimization Studio — the new-run form stays inside the model-parameter allowlist',
  { tag: ['@area:optimization-studio'] },
  () => {
    test(
      'every model parameter the form saves is one the runner passes on, and the server takes it on create and on rerun',
      { tag: ['@t2-cuj', '@cap:optimization-studio.new-run-form-validation'] },
      async ({
        project,
        dataset,
        providerKeys,
        backendClient,
        registerOptimizationCleanup,
        testNamespace,
        page,
      }) => {
        test.setTimeout(300_000);

        const providerName = `${testNamespace}-allowlist`;
        const modelName = 'qa-allowlist-model';

        await test.step('Seed a provider whose model can be selected but never called', async () => {
          const offered = await providerKeys.createWithModels({
            providerName,
            modelNames: [modelName],
          });
          expect(
            offered,
            'the provider offers exactly the one model this form will select',
          ).toEqual([modelName]);
        });

        const captured: CapturedCreate[] = [];
        /** Every create POST the page issued, intercepted or not. */
        const postedCreates: string[] = [];
        page.on('request', (request) => {
          if (
            request.method() === 'POST' &&
            new URL(request.url()).pathname.endsWith('/v1/private/optimizations/')
          ) {
            postedCreates.push(request.url());
          }
        });

        let currentOptimizer: OptimizerName = OPTIMIZERS[0];
        await page.route(
          (url) => url.pathname.endsWith('/v1/private/optimizations/'),
          async (route) => {
            if (route.request().method() !== 'POST') return route.fallback();
            captured.push({
              optimizer: currentOptimizer,
              studioConfig: (JSON.parse(route.request().postData() ?? '{}') as {
                studio_config?: CapturedCreate['studioConfig'];
              }).studio_config ?? {},
            });
            // A 201 with a Location header is the only thing
            // `useOptimizationCreateMutation` reads — it extracts the id from
            // the header and navigates. The id is a real uuid so the route the
            // form navigates to parses, but it names nothing: no row was
            // created, which is the next step's assertion.
            await route.fulfill({
              status: 201,
              headers: { location: `/v1/private/optimizations/${uuid7()}` },
              body: '',
            });
          },
        );

        const studio = new OptimizationStudioPage(page, project.id);

        for (const optimizer of OPTIMIZERS) {
          currentOptimizer = optimizer;
          await test.step(`Fill and submit the form on ${optimizer}`, async () => {
            await studio.gotoNewBlank();
            await studio.selectDataset(dataset.name);
            await studio.setSystemPrompt('Answer with the label only.');
            await studio.setUserPrompt('{{input}}');
            await studio.selectModelByName(modelName);
            if (optimizer !== 'GEPA optimizer') await studio.selectOptimizer(optimizer);
            await studio.setReferenceKey('expected_output');

            // Moved off their defaults so the payload carries values that could
            // only have come from these controls: a key present at its default
            // says nothing about which control owns it.
            const temperature = await studio.setModelParameter('temperature', NUDGED_TEMPERATURE);
            const maxTokens = await studio.setModelParameter(
              'maxCompletionTokens',
              NUDGED_MAX_TOKENS,
            );
            // Read back rather than assumed: `SliderInputControl` clamps to its
            // own [min, max], so a nudge outside the range would otherwise look
            // like a value the form had sent.
            expect(Number(temperature), 'the temperature control took the nudged value').toBe(
              NUDGED_TEMPERATURE,
            );
            expect(Number(maxTokens), 'the max-output-tokens control took the nudged value').toBe(
              NUDGED_MAX_TOKENS,
            );
            await studio.setExtraBodyParameters(`{"${ALLOWED_CUSTOM_PARAMETER}": "qa"}`);
            await studio.dismissModelParameters();

            await studio.startOptimization();
          });
        }

        await test.step('Both submits were intercepted, so neither reached the backend', async () => {
          expect(
            postedCreates.length,
            'the form issued one create POST per optimizer',
          ).toBe(OPTIMIZERS.length);
          expect(
            captured.length,
            'and the route handler saw every one of them — an unmatched route would have ' +
              'launched a real optimizer run',
          ).toBe(OPTIMIZERS.length);
          await expectNoRunsCreated(
            backendClient,
            project.id,
            registerOptimizationCleanup,
            'after both submits',
          );
        });

        await test.step('Each payload carries the nudged values under allowed keys', async () => {
          for (const create of captured) {
            const parameters = create.studioConfig.llm_model?.parameters;
            expect(
              parameters,
              `${create.optimizer}: the payload carries llm_model.parameters at all`,
            ).toBeTruthy();
            expect(
              refusedKeys(parameters),
              `${create.optimizer}: every llm_model.parameters key is one the Studio runner ` +
                'passes on',
            ).toEqual([]);

            // The keys are only meaningful if the values came from the panel.
            expect(parameters!.temperature, `${create.optimizer}: temperature`).toBe(
              NUDGED_TEMPERATURE,
            );
            expect(parameters!.max_completion_tokens, `${create.optimizer}: max output tokens`).toBe(
              NUDGED_MAX_TOKENS,
            );
            // The extra body lands NESTED under custom_parameters. Flattened to
            // the top level it would be refused by name, which is the failure
            // this pins.
            expect(
              parameters!.custom_parameters,
              `${create.optimizer}: the extra body is nested under custom_parameters`,
            ).toEqual({ [ALLOWED_CUSTOM_PARAMETER]: 'qa' });

            expect(
              create.studioConfig.llm_model?.model,
              `${create.optimizer}: the model is the one selected`,
            ).toContain(modelName);
          }
        });

        await test.step('The optimizer parameters are inside the allowlist too', async () => {
          for (const create of captured) {
            const optimizerParameters = create.studioConfig.optimizer?.parameters;
            expect(
              optimizerParameters,
              `${create.optimizer}: the payload carries optimizer.parameters`,
            ).toBeTruthy();
            // `validate` only screens `optimizer.parameters.model_parameters`,
            // so the rest of this object is unconstrained — asserted here as
            // the reason the form never reaches that path, which is what the
            // second test then drives directly.
            expect(
              optimizerParameters,
              `${create.optimizer}: this optimizer sends no model_parameters, so the form ` +
                'cannot exercise the second guarded path',
            ).not.toHaveProperty('model_parameters');
          }
        });

        await test.step('The server accepts each captured payload on create AND on rerun', async () => {
          // The assertion the JUnit test cannot make: not that a hand-written
          // payload is accepted, but that THIS form's is — on the create path
          // and on the upsert path a rerun saves through.
          for (const create of captured) {
            const id = uuid7();
            const name = `${testNamespace}-replay-${create.optimizer.split(' ')[0].toLowerCase()}`;
            registerOptimizationCleanup(id, name);

            const created = await backendClient.writeOptimizationRaw('POST', {
              id,
              name,
              datasetName: dataset.name,
              projectName: project.name,
              objectiveName: 'equals',
              studioConfig: create.studioConfig,
            });
            expect(
              created.status,
              `${create.optimizer}: POST refused the form's own payload: ${created.message}`,
            ).toBe(201);

            const upserted = await backendClient.writeOptimizationRaw('PUT', {
              id,
              name,
              datasetName: dataset.name,
              projectName: project.name,
              objectiveName: 'equals',
              studioConfig: create.studioConfig,
            });
            expect(
              upserted.status,
              `${create.optimizer}: PUT refused the form's own payload: ${upserted.message}`,
            ).toBe(201);
          }
        });

        await test.step('…and what it stored is the payload the form sent', async () => {
          // A 201 says the allowlist let the payload through; it does not say
          // the parameters survived. A server that accepted the request and
          // dropped `parameters` would pass every status assertion above while
          // running the optimizer on defaults.
          const runs = await backendClient.listOptimizations({ projectId: project.id });
          expect(runs, `${captured.length} replayed runs, and nothing else`).toHaveLength(
            captured.length,
          );
          for (const run of runs) {
            const stored = await backendClient.getOptimizationStudioConfig(run.id);
            expect(
              stored?.llmModel?.parameters,
              `${run.name} stored the nudged parameters`,
            ).toMatchObject({
              temperature: NUDGED_TEMPERATURE,
              max_completion_tokens: NUDGED_MAX_TOKENS,
              custom_parameters: { [ALLOWED_CUSTOM_PARAMETER]: 'qa' },
            });
          }
        });
      },
    );

    test(
      'the allowlist refuses a parameter by name, and a destination key inside custom_parameters, on create and on rerun',
      // The same capability as the test above, which is the one that drives the
      // form: this is the server-side half of the rule the form must mirror,
      // and a 400 the dialog never provokes is only reachable here. Driven at
      // the API because the form has no control that can send a key the panel
      // does not offer — asserting the refusal through a browser would be
      // asserting a toast, not the rule.
      { tag: ['@t2-cuj', '@cap:optimization-studio.new-run-form-validation'] },
      async ({
        project,
        dataset,
        backendClient,
        registerOptimizationCleanup,
        testNamespace,
      }) => {
        const model = `custom-llm/${testNamespace}-never/qa-model`;
        const base = { temperature: 0.5, max_completion_tokens: 100 };

        /** One write, named for the report. */
        const write = async (
          method: 'POST' | 'PUT',
          label: string,
          studioConfig: Record<string, unknown>,
          id = uuid7(),
        ) => {
          const name = `${testNamespace}-${label}`;
          // Registered unconditionally, before the write: a create that is
          // supposed to be refused and is NOT would otherwise leak, and that is
          // exactly the case where the assertion below fails.
          registerOptimizationCleanup(id, name);
          return {
            id,
            result: await backendClient.writeOptimizationRaw(method, {
              id,
              name,
              datasetName: dataset.name,
              projectName: project.name,
              objectiveName: 'equals',
              studioConfig,
            }),
          };
        };

        const acceptedId = uuid7();
        await test.step('The control: the same payload without a refused key is accepted', async () => {
          // First, so every 400 below is known to be about the key that was
          // added rather than about the shape of the request. Without it, a
          // malformed body would make the whole test pass while asserting
          // nothing about the allowlist.
          const config = studioConfigWith(dataset.name, model, {
            ...base,
            custom_parameters: { [ALLOWED_CUSTOM_PARAMETER]: 'qa' },
          });
          const created = await write('POST', 'allowlist-control', config, acceptedId);
          expect(
            created.result.status,
            `the control payload was refused: ${created.result.message}`,
          ).toBe(201);
          const upserted = await write('PUT', 'allowlist-control', config, acceptedId);
          expect(
            upserted.result.status,
            `the control payload was refused on upsert: ${upserted.result.message}`,
          ).toBe(201);
        });

        for (const method of ['POST', 'PUT'] as const) {
          await test.step(`${method}: a parameter the runner does not pass on is refused by name`, async () => {
            const { result } = await write(
              method,
              `refused-param-${method.toLowerCase()}`,
              studioConfigWith(dataset.name, model, { ...base, [REFUSED_PARAMETER]: 'flex' }),
            );
            expect(result.status).toBe(400);
            // The message, not just the status: "refused for the wrong reason"
            // is indistinguishable from "refused" without it, and naming the
            // key is the documented behaviour a user depends on to fix it.
            expect(result.message).toContain(
              "llm_model.parameters has keys the Studio runner doesn't pass on, " +
                `keys '[${REFUSED_PARAMETER}]'`,
            );
          });

          await test.step(`${method}: a custom_parameters key that picks a credential is refused`, async () => {
            const { result } = await write(
              method,
              `refused-destination-${method.toLowerCase()}`,
              studioConfigWith(dataset.name, model, {
                ...base,
                custom_parameters: { [REFUSED_CUSTOM_PARAMETER]: 'sk-qa-never-sent' },
              }),
            );
            expect(result.status).toBe(400);
            expect(result.message).toContain(
              "llm_model.parameters.custom_parameters can't pick a destination or a credential, " +
                `keys '[${REFUSED_CUSTOM_PARAMETER}]'`,
            );
          });

          await test.step(`${method}: the optimizer's own model_parameters are screened too`, async () => {
            // The second guarded path, which the form never reaches: neither
            // offered optimizer puts `model_parameters` in its parameters at
            // all, so without this the branch would be untested by anything
            // outside the JUnit suite.
            const { result } = await write(
              method,
              `refused-optimizer-param-${method.toLowerCase()}`,
              studioConfigWith(dataset.name, model, base, {
                verbose: 1,
                seed: 42,
                model_parameters: { ...base, [REFUSED_PARAMETER]: 'flex' },
              }),
            );
            expect(result.status).toBe(400);
            expect(result.message).toContain(
              "optimizer.parameters.model_parameters has keys the Studio runner doesn't pass on, " +
                `keys '[${REFUSED_PARAMETER}]'`,
            );
          });
        }

        await test.step('No refused write created or changed a run', async () => {
          // The point of a 400. Six refusals ran against this project; if any
          // of them had written, the project would hold more than the one run
          // the control deliberately created.
          const runs = await backendClient.listOptimizations({ projectId: project.id });
          expect(
            runs.map((r) => r.id),
            'only the control payload was stored',
          ).toEqual([acceptedId]);
        });
      },
    );
  },
);
