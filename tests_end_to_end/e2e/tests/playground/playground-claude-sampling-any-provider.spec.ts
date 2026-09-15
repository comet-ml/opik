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
      'Choosing Top P for a Claude model on a custom gateway sends top_p and no temperature',
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
      },
    );
  },
);
