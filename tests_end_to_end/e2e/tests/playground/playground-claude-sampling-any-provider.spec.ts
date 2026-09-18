import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Claude's temperature-or-Top-P rule belongs to the MODEL, not to the provider routing it.
 *
 * The same Claude models arrive through Anthropic, Bedrock, OpenRouter and OpenAI-compatible
 * proxies, and they reject a request carrying both parameters wherever they are served from.
 * A panel that keyed the rule off the provider would offer two independent sliders for a
 * Claude behind a custom gateway, and the request would then be refused upstream — or, worse,
 * look correct on screen while carrying a parameter the user never asked for.
 *
 * Both halves are asserted here because neither catches the defect alone:
 *   - the panel can render the exclusive choice over a request builder that still sends both;
 *   - the request can be right while the panel offers a control the user cannot reach.
 *
 * Deterministic, and no provider key of any kind:
 *   - The gateway is a `custom-llm` provider whose base URL refuses every connection. Every
 *     assertion is on what the BROWSER sends or renders, never on a completion, so an upstream
 *     that cannot answer costs nothing — the POST is observable before the refusal.
 *   - The gateway is deliberately named `<prefix>-claude-gw`. If the implementation ever let
 *     the provider NAME stand in for the model, `mistral-large-2411` on this gateway would
 *     pick up the exclusive choice and the first test would fail — which is the contamination
 *     `isClaudeModel` exists to avoid.
 *   - Provider keys are workspace-global and the picker labels a custom gateway's models with
 *     the bare model name, so the model is selected through the run-prefixed PROVIDER name
 *     (see `selectModelFromProvider`) and the outbound request's `model` is asserted to be
 *     this run's fully-qualified id.
 */

/**
 * The Claude under test, and the non-Claude it is compared against on the same gateway.
 *
 * `claude-opus-4-6` on purpose: it is a Claude that still DECLARES `supportsSamplingParams`,
 * so it takes the exclusive choice rather than no sampling control at all. A Claude without
 * that declaration renders neither half — a different behaviour, and not the one these tests
 * are about.
 *
 * `mistral-large-2411` is not a model this gateway can actually serve, and does not need to
 * be: it is a model NAME the panel reads, and the point is that nothing but the model segment
 * differs between the two observations.
 */
const CLAUDE_MODEL = 'claude-opus-4-6';
const NON_CLAUDE_MODEL = 'mistral-large-2411';

/**
 * A Claude with NO `supportsSamplingParams` row in `ANTHROPIC_MODEL_CAPABILITIES`.
 *
 * The map's default is "takes neither", so a recognised Claude that is not listed as
 * sampling-capable renders no sampling control at all — a third outcome, distinct from both
 * the exclusive choice above and the two independent sliders a non-Claude gets. That
 * three-way split is the whole of the model-level classification, and `claude-sonnet-5` is
 * the row that exercises the branch `supportsSamplingParams` returns `false` from.
 */
const CLAUDE_MODEL_WITHOUT_SAMPLING = 'claude-sonnet-5';

/**
 * A Claude that DOES declare `supportsSamplingParams`, used as the contrast against
 * {@link CLAUDE_MODEL_WITHOUT_SAMPLING} on the same gateway.
 *
 * Deliberately a different model from {@link CLAUDE_MODEL}: the point of these tests is that
 * the classification is read off the model id, so the two Claudes have to differ in nothing
 * but that id — same gateway, same base URL, same provider name.
 */
const CLAUDE_MODEL_WITH_SAMPLING = 'claude-sonnet-4-6';

/** The completion proxy, on both the `/opik/api` and bare `/api` mounts. */
function isChatCompletion(url: string): boolean {
  return new URL(url).pathname.endsWith('/v1/private/chat/completions');
}

test.describe(
  'Playground — Claude sampling params on a custom gateway',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'The temperature-or-Top-P choice follows the model, not the gateway that serves it',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, providerKeys, testNamespace, page }) => {
        const providerName = `${testNamespace}-claude-gw`;

        await test.step('Seed one custom gateway serving a Claude and a non-Claude model', async () => {
          await providerKeys.createUnreachableModels({
            providerName,
            modelNames: [CLAUDE_MODEL, NON_CLAUDE_MODEL],
          });
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step("Open the Playground on the gateway's Claude model", async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModelFromProvider(0, providerName, CLAUDE_MODEL);
        });

        await test.step('The Claude model gets the exclusive choice, with one live slider', async () => {
          await playground.openModelParameters(0);

          await expect(
            playground.samplingOption('Temperature'),
            'a Claude takes one of the pair, so the panel must offer the choice — even on a custom gateway',
          ).toHaveCount(1);
          await expect(playground.samplingOption('Top P')).toHaveCount(1);
          await expect(
            playground.selectedSamplingOptions(),
            'exactly one half may be selected — two selected is the request the provider rejects',
          ).toHaveCount(1);
          await expect(
            playground.samplingOption('Temperature'),
            'temperature is the half the resolver makes live for a config carrying both',
          ).toHaveAttribute('data-state', 'on');

          // Presence, not enabled-ness: the outgoing half is unmounted, so a build that merely
          // dimmed it would still be holding a value the request builder could pick up.
          await expect(playground.sliderInput('temperature')).toHaveCount(1);
          await expect(
            playground.sliderInput('topP'),
            'Top P has no control at all while Temperature is live',
          ).toHaveCount(0);

          await playground.closeModelParameters();
        });

        await test.step('The non-Claude model on the SAME gateway keeps two independent sliders', async () => {
          await playground.selectModelFromProvider(0, providerName, NON_CLAUDE_MODEL);
          await playground.openModelParameters(0);

          await expect(
            playground.samplingOption('Temperature'),
            'the gateway is named "-claude-gw": a Sampling choice here means the provider name stood in for the model',
          ).toHaveCount(0);
          await expect(playground.samplingOption('Top P')).toHaveCount(0);

          // Both mounted at once — the state the exclusive choice exists to prevent, and the
          // correct one for a model that has no such constraint.
          await expect(playground.sliderInput('temperature')).toHaveCount(1);
          await expect(playground.sliderInput('topP')).toHaveCount(1);
        });
      },
    );

    test(
      'A Claude on a custom gateway sends exactly the sampling parameter the panel displays, in either position',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, providerKeys, testNamespace, page }) => {
        const providerName = `${testNamespace}-claude-gw`;

        const [claudeModelId] = await test.step(
          'Seed a custom gateway serving the Claude model',
          async () => {
            return providerKeys.createUnreachableModels({
              providerName,
              modelNames: [CLAUDE_MODEL],
            });
          },
        );

        const playground = new PlaygroundPage(page, project.id);

        await test.step("Open the Playground on the gateway's Claude model", async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModelFromProvider(0, providerName, CLAUDE_MODEL);
        });

        const topPDisplayed = await test.step(
          'Choosing Top P swaps the live control rather than adding one',
          async () => {
            await playground.openModelParameters(0);
            await playground.samplingOption('Top P').click();

            await expect(playground.samplingOption('Top P')).toHaveAttribute('data-state', 'on');
            await expect(
              playground.selectedSamplingOptions(),
              'still exactly one selected after the switch',
            ).toHaveCount(1);
            await expect(playground.sliderInput('topP')).toHaveCount(1);
            await expect(
              playground.sliderInput('temperature'),
              'Temperature must leave the DOM, not linger with a stale value',
            ).toHaveCount(0);

            const displayed = await playground.sliderInput('topP').inputValue();
            expect(displayed, 'the Top P control displays a number').toMatch(/^\d+(\.\d+)?$/);
            return Number(displayed);
          },
        );

        await test.step('Close the panel and write a one-line prompt', async () => {
          await playground.closeModelParameters();
          await playground.fillFirstMessage('Reply with the single word OK.');
        });

        const topPRequest = await test.step('Run, and capture the outbound request', async () => {
          // Armed before the click: the POST is in flight the moment Run is pressed, so
          // subscribing afterwards would race it.
          const sent = page.waitForRequest(
            (r) => r.method() === 'POST' && isChatCompletion(r.url()),
            { timeout: 60_000 },
          );
          await playground.clickRun();
          return sent;
        });

        await test.step('The request carries top_p, at the displayed value, and no temperature', async () => {
          const body = topPRequest.postDataJSON() as Record<string, unknown>;

          expect(
            body.model,
            "the request names THIS run's gateway model, not a same-named model behind a leftover provider key",
          ).toBe(claudeModelId);
          expect(body.top_p, 'the live control is what the request carries').toBe(topPDisplayed);
          expect(
            Object.keys(body),
            'no temperature alongside top_p — Claude refuses a request holding both, whoever serves it',
          ).not.toContain('temperature');
        });

        const temperatureDisplayed = await test.step(
          'Switching back to Temperature swaps the live control again',
          async () => {
            await playground.waitForRunIdle();
            await playground.openModelParameters(0);
            await playground.samplingOption('Temperature').click();

            await expect(playground.samplingOption('Temperature')).toHaveAttribute(
              'data-state',
              'on',
            );
            await expect(playground.sliderInput('temperature')).toHaveCount(1);
            await expect(playground.sliderInput('topP')).toHaveCount(0);

            const displayed = await playground.sliderInput('temperature').inputValue();
            expect(displayed, 'the Temperature control displays a number').toMatch(
              /^\d+(\.\d+)?$/,
            );
            await playground.closeModelParameters();
            return Number(displayed);
          },
        );

        const temperatureRequest = await test.step(
          'Run again, and capture the outbound request',
          async () => {
            const sent = page.waitForRequest(
              (r) => r.method() === 'POST' && isChatCompletion(r.url()),
              { timeout: 60_000 },
            );
            await playground.clickRun();
            return sent;
          },
        );

        await test.step('The second request carries temperature and no top_p', async () => {
          const body = temperatureRequest.postDataJSON() as Record<string, unknown>;

          expect(body.model).toBe(claudeModelId);
          expect(body.temperature, 'the live control is what the request carries').toBe(
            temperatureDisplayed,
          );
          expect(
            Object.keys(body),
            'the Top P the user switched away from must not survive in the payload',
          ).not.toContain('top_p');
        });

        // Let the run settle before the fixture deletes the gateway. Provider keys are
        // workspace-global, so a teardown that lands while the backend is still resolving
        // this one leaves a confusing error in the server log — nothing this spec asserts
        // on, but nothing worth leaving behind either.
        await playground.waitForRunIdle();
      },
    );
  },
);

/**
 * The other half of the same rule: WHICH sampling parameters a Claude takes at all, and
 * whether a deliberate choice survives being persisted and re-hydrated.
 *
 * The describe above asserts that a Claude takes ONE of temperature/top_p rather than both.
 * This one asserts the two cases that sit either side of it:
 *
 *   - A recognised Claude that is not sampling-capable takes NEITHER. `supportsSamplingParams`
 *     defaults to false for a listed-but-not-capable id, so the panel must drop the whole
 *     Sampling block and the request must carry no `temperature` and no `top_p`. On a build
 *     without that inversion the same model renders a live Temperature slider and sends its
 *     value — a request the provider refuses outright.
 *   - A Top P-only config must still be Top P-only after a reload. `restoreMissingConfigKeys`
 *     fills a stored config's missing keys from the provider defaults on every hydration, so
 *     without its `isClaudeModel` exemption a user's deliberate Top P quietly becomes
 *     temperature-at-default on the next page load, and the request follows the config rather
 *     than the choice. That is the symptom in this change a user would hit without noticing,
 *     because nothing about the panel looks wrong afterwards — it is simply showing a setting
 *     they did not pick.
 *
 * Same gateway construction as above, and for the same reasons: a `custom-llm` provider whose
 * base URL refuses every connection, so nothing here depends on a provider key, on a
 * completion coming back, or on the runner being reachable from the deployment.
 */
test.describe(
  'Playground — Claude models that take no sampling params, and Top P across a reload',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'A Claude with no sampling-params row offers no sampling control, while its siblings on the same gateway keep theirs',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, providerKeys, testNamespace, page }) => {
        const providerName = `${testNamespace}-claude-gw`;

        await test.step('Seed one gateway serving all three classifications', async () => {
          await providerKeys.createUnreachableModels({
            providerName,
            modelNames: [
              CLAUDE_MODEL_WITHOUT_SAMPLING,
              CLAUDE_MODEL_WITH_SAMPLING,
              NON_CLAUDE_MODEL,
            ],
          });
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Open the Playground on the Claude that takes neither parameter', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModelFromProvider(
            0,
            providerName,
            CLAUDE_MODEL_WITHOUT_SAMPLING,
          );
        });

        await test.step(`${CLAUDE_MODEL_WITHOUT_SAMPLING} gets no sampling control at all`, async () => {
          await playground.openModelParameters(0);

          // The panel has to be open for these zero-counts to mean anything: an unopened
          // popover is unmounted, so every locator below would read 0 whatever the build does.
          await expect(
            playground.modelParametersPanel(),
            'the parameters panel is open — otherwise the absences below prove nothing',
          ).toBeVisible();

          await expect(
            playground.samplingOption('Temperature'),
            'a Claude that rejects the pair must not be offered a choice between them',
          ).toHaveCount(0);
          await expect(playground.samplingOption('Top P')).toHaveCount(0);

          // Presence, not enabled-ness, again: a slider the panel merely dimmed would still
          // be holding a value the request builder could pick up.
          await expect(
            playground.sliderInput('temperature'),
            'no temperature control — this model takes neither half of the pair',
          ).toHaveCount(0);
          await expect(playground.sliderInput('topP')).toHaveCount(0);

          await playground.closeModelParameters();
        });

        await test.step(`${CLAUDE_MODEL_WITH_SAMPLING} on the SAME gateway still gets the exclusive choice`, async () => {
          await playground.selectModelFromProvider(0, providerName, CLAUDE_MODEL_WITH_SAMPLING);
          await playground.openModelParameters(0);

          await expect(
            playground.samplingOption('Temperature'),
            'nothing but the model segment differs from the previous model — so the model is what decided it',
          ).toHaveCount(1);
          await expect(playground.samplingOption('Top P')).toHaveCount(1);
          await expect(
            playground.selectedSamplingOptions(),
            'exactly one half selected — the exclusive rule still applies to a sampling-capable Claude',
          ).toHaveCount(1);
          await expect(playground.sliderInput('temperature')).toHaveCount(1);
          await expect(playground.sliderInput('topP')).toHaveCount(0);

          await playground.closeModelParameters();
        });

        await test.step(`${NON_CLAUDE_MODEL} on the SAME gateway keeps two independent sliders`, async () => {
          await playground.selectModelFromProvider(0, providerName, NON_CLAUDE_MODEL);
          await playground.openModelParameters(0);

          await expect(
            playground.samplingOption('Temperature'),
            'the gateway is named "-claude-gw": a Sampling choice here means the provider name stood in for the model',
          ).toHaveCount(0);
          await expect(playground.samplingOption('Top P')).toHaveCount(0);

          // Both mounted at once — which is why the two zero-counts on the first model are a
          // statement about that model and not about a panel that renders no sliders here.
          await expect(playground.sliderInput('temperature')).toHaveCount(1);
          await expect(playground.sliderInput('topP')).toHaveCount(1);
        });
      },
    );

    test(
      'A Claude with no sampling-params row sends neither temperature nor top_p, while a non-Claude on the same gateway sends both',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, providerKeys, testNamespace, page }) => {
        const providerName = `${testNamespace}-claude-gw`;

        const [claudeModelId, nonClaudeModelId] = await test.step(
          'Seed one gateway serving the Claude and a non-Claude model',
          async () => {
            return providerKeys.createUnreachableModels({
              providerName,
              modelNames: [CLAUDE_MODEL_WITHOUT_SAMPLING, NON_CLAUDE_MODEL],
            });
          },
        );

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Open the Playground on the Claude and write a one-line prompt', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModelFromProvider(
            0,
            providerName,
            CLAUDE_MODEL_WITHOUT_SAMPLING,
          );
          await playground.fillFirstMessage('Reply with the single word OK.');
        });

        const claudeRequest = await test.step('Run, and capture the outbound request', async () => {
          // Armed before the click: the POST is in flight the moment Run is pressed, so
          // subscribing afterwards would race it.
          const sent = page.waitForRequest(
            (r) => r.method() === 'POST' && isChatCompletion(r.url()),
            { timeout: 60_000 },
          );
          await playground.clickRun();
          return sent;
        });

        await test.step('The request carries neither sampling parameter', async () => {
          const body = claudeRequest.postDataJSON() as Record<string, unknown>;

          expect(
            body.model,
            "the request names THIS run's gateway model, not a same-named model behind a leftover provider key",
          ).toBe(claudeModelId);
          expect(
            Object.keys(body),
            'a model that rejects sampling params must not be sent one — the panel hiding the slider is not enough if the value still ships',
          ).not.toContain('temperature');
          expect(Object.keys(body)).not.toContain('top_p');
        });

        const nonClaudeRequest = await test.step(
          'Switch to the non-Claude model on the same gateway and run again',
          async () => {
            await playground.waitForRunIdle();
            await playground.selectModelFromProvider(0, providerName, NON_CLAUDE_MODEL);

            const sent = page.waitForRequest(
              (r) => r.method() === 'POST' && isChatCompletion(r.url()),
              { timeout: 60_000 },
            );
            await playground.clickRun();
            return sent;
          },
        );

        await test.step('That request carries BOTH parameters', async () => {
          const body = nonClaudeRequest.postDataJSON() as Record<string, unknown>;

          expect(body.model).toBe(nonClaudeModelId);
          // The positive control for the assertion above: the two absences only mean
          // something if this same gateway, on this same page, does send the pair for a model
          // that takes it. `typeof`, because the default temperature is 0 and a truthiness
          // check would pass on an absent key.
          expect(
            typeof body.temperature,
            'the gateway does send temperature for a model that takes it',
          ).toBe('number');
          expect(typeof body.top_p, 'and top_p alongside it, this model having no exclusive rule').toBe(
            'number',
          );
        });

        await playground.waitForRunIdle();
      },
    );

    test(
      'A Top P-only Claude config survives a reload without a default temperature being restored',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, providerKeys, testNamespace, page }) => {
        const providerName = `${testNamespace}-claude-gw`;

        const [claudeModelId] = await test.step(
          'Seed a gateway serving a sampling-capable Claude',
          async () => {
            return providerKeys.createUnreachableModels({
              providerName,
              modelNames: [CLAUDE_MODEL_WITH_SAMPLING],
            });
          },
        );

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Open the Playground on it and choose Top P', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModelFromProvider(0, providerName, CLAUDE_MODEL_WITH_SAMPLING);

          await playground.openModelParameters(0);
          await playground.samplingOption('Top P').click();

          // Assert the choice really took BEFORE reloading. A reload over a config that never
          // changed would leave Temperature selected for an entirely uninteresting reason, and
          // the test would then be asserting the wrong thing had been preserved.
          await expect(
            playground.samplingOption('Top P'),
            'Top P is the chosen half before the reload',
          ).toHaveAttribute('data-state', 'on');
          await expect(playground.sliderInput('topP')).toHaveCount(1);
          await expect(playground.sliderInput('temperature')).toHaveCount(0);

          await playground.closeModelParameters();
        });

        await test.step('Reload the page', async () => {
          await playground.reload();
          await expect(
            playground.selectedModelLabel(0),
            'the reload restored the same model — otherwise the panel below describes a different one',
          ).toContainText(CLAUDE_MODEL_WITH_SAMPLING);
        });

        const topPDisplayed = await test.step(
          'The restored config is still Top P-only, with no temperature put back',
          async () => {
            await playground.openModelParameters(0);

            await expect(
              playground.samplingOption('Top P'),
              'hydration must not hand a deliberate Top P back as temperature-at-default',
            ).toHaveAttribute('data-state', 'on');
            await expect(
              playground.selectedSamplingOptions(),
              'still exactly one half selected after hydration',
            ).toHaveCount(1);
            await expect(playground.sliderInput('topP')).toHaveCount(1);
            await expect(
              playground.sliderInput('temperature'),
              'a restored default temperature is the regression — the pair would then be live together',
            ).toHaveCount(0);

            const displayed = await playground.sliderInput('topP').inputValue();
            expect(displayed, 'the Top P control displays a number').toMatch(/^\d+(\.\d+)?$/);
            await playground.closeModelParameters();
            return Number(displayed);
          },
        );

        await test.step('Write a one-line prompt', async () => {
          await playground.fillFirstMessage('Reply with the single word OK.');
        });

        const topPRequest = await test.step('Run, and capture the outbound request', async () => {
          const sent = page.waitForRequest(
            (r) => r.method() === 'POST' && isChatCompletion(r.url()),
            { timeout: 60_000 },
          );
          await playground.clickRun();
          return sent;
        });

        await test.step('The post-reload request still carries top_p and no temperature', async () => {
          const body = topPRequest.postDataJSON() as Record<string, unknown>;

          expect(body.model).toBe(claudeModelId);
          expect(body.top_p, 'the request follows the restored control, not a default').toBe(
            topPDisplayed,
          );
          expect(
            Object.keys(body),
            'the request is where a silently restored temperature would actually cost the user — the panel looks fine either way',
          ).not.toContain('temperature');
        });

        await playground.waitForRunIdle();
      },
    );
  },
);
