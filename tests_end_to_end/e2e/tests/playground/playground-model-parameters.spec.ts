import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import { anthropicKeyUsable } from '@e2e/core/llm-key-preflight';

/**
 * The playground's model-parameters panel must send exactly what it displays.
 *
 * `playground.configure-model-settings` is uncovered, and the defect class here
 * is silent by construction — neither surface can catch it alone:
 *
 *   - **API-only** cannot: a request carrying `temperature` when the panel is
 *     set to Top P, or carrying no `thinking_effort` at all, is a perfectly
 *     well-formed request. There is nothing wrong with it except that it is not
 *     what the user asked for.
 *   - **UI-only** cannot either: the panel renders whatever its own config says,
 *     so a control showing "High (Default)" over a config the request builder
 *     then strips looks entirely correct on screen.
 *
 * So the assertion is the panel's own displayed values compared against the
 * outbound `POST /v1/private/chat/completions` body. It costs money at the wrong
 * setting rather than erroring, which is why it is worth a permanent test.
 *
 * Deterministic despite being a live LLM call: every assertion is on the request
 * the browser sent, never on what came back. The test does not wait for a
 * completion.
 *
 * Anthropic-specific on purpose. The sampling pair is a single choice only
 * because Anthropic rejects a request carrying both, and `claude-sonnet-4-6` is
 * picked because it is the model that offers BOTH halves under test — a thinking
 * effort AND the sampling toggle. Its newer siblings (Sonnet 5, the Opus 4.7+
 * line) set `supportsSamplingParams: false` and render no toggle at all.
 */

const MODEL_DISPLAY_NAME = 'Claude Sonnet 4.6';

/**
 * The effort this test switches the dropdown to. Deliberately NOT the default:
 * a request that carried the default would also pass on a build that ignored
 * the control entirely and let `resolveEffort` substitute "high".
 */
const CHOSEN_EFFORT_LABEL = 'Low';

/**
 * How the dropdown's labels map onto the values the request carries. Written out
 * rather than lower-cased from the label because the mapping is not mechanical —
 * "High (Default)" is `high` and "xHigh" is `xhigh` — and because an unknown
 * label must fail this test rather than quietly resolve to something.
 */
const THINKING_EFFORT_VALUE_BY_LABEL: Record<string, string> = {
  Adaptive: 'adaptive',
  Low: 'low',
  Medium: 'medium',
  'High (Default)': 'high',
  xHigh: 'xhigh',
  Max: 'max',
};

/** The completion proxy, on both the `/opik/api` and bare `/api` mounts. */
function isChatCompletion(url: string): boolean {
  return new URL(url).pathname.endsWith('/v1/private/chat/completions');
}

test.describe(
  'Playground — model parameters',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'The Anthropic model-parameters panel sends exactly the sampling parameter and thinking effort it displays',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, page }) => {
        test.setTimeout(180_000);

        await test.step('This workspace can offer an Anthropic model at all', async () => {
          // The panel under test renders off the model identifier, not off a
          // working key — but the model has to be selectable, which needs the
          // provider configured on the workspace. Provision it when the runner
          // holds a key; otherwise take the workspace as it is.
          const cfg = new ConfigurationPage(page);
          await cfg.gotoAiProviders();
          // anthropicKeyUsable(), not the raw env var: after the preflight blanks
          // a rejected key this would otherwise fall to the hasProvider() branch
          // and stop provisioning, leaving the spec dependent on an earlier run
          // having configured the workspace.
          const anthropicKey = anthropicKeyUsable() ? process.env.ANTHROPIC_API_KEY : undefined;
          const configured = anthropicKey
            ? await cfg.ensureProviderConfigured('Anthropic', anthropicKey)
            : await cfg.hasProvider('Anthropic');
          test.skip(
            !configured,
            'no Anthropic provider on this workspace and no ANTHROPIC_API_KEY to add one',
          );
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${MODEL_DISPLAY_NAME}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          test.skip(
            !(await playground.isModelOffered(0, MODEL_DISPLAY_NAME)),
            `${MODEL_DISPLAY_NAME} is not in this deployment's model registry`,
          );
          await playground.selectModel(0, MODEL_DISPLAY_NAME);
        });

        await test.step('The sampling pair is one choice, with one live control', async () => {
          await playground.openModelParameters(0);

          await expect(
            playground.samplingOption('Temperature'),
            'Anthropic renders a Temperature / Top P choice',
          ).toHaveCount(1);
          await expect(playground.samplingOption('Top P')).toHaveCount(1);
          await expect(
            playground.selectedSamplingOptions(),
            'exactly one half of the pair may be selected — two selected is the request Anthropic rejects',
          ).toHaveCount(1);

          // Presence, not enabled-ness: the outgoing half is unmounted, so a
          // build that merely dimmed it would still be holding a value the
          // request builder could pick up.
          await expect(
            playground.sliderInput('temperature'),
            'Temperature is the default live half',
          ).toHaveCount(1);
          await expect(
            playground.sliderInput('topP'),
            'and Top P has no control at all while Temperature is live',
          ).toHaveCount(0);
        });

        const topPDisplayed = await test.step(
          'Choosing Top P swaps the live control rather than adding one',
          async () => {
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

        const expectedEffort = await test.step(
          `Set Thinking effort to "${CHOSEN_EFFORT_LABEL}"`,
          async () => {
            const initial = (await playground.thinkingEffortSelect().textContent())?.trim() ?? '';
            expect(
              THINKING_EFFORT_VALUE_BY_LABEL,
              `the dropdown opened on "${initial}", which this test has no request value for — ` +
                'the label set changed and the mapping below is stale',
            ).toHaveProperty(initial);

            await playground.selectThinkingEffort(CHOSEN_EFFORT_LABEL);
            return THINKING_EFFORT_VALUE_BY_LABEL[CHOSEN_EFFORT_LABEL];
          },
        );

        await test.step('Close the panel and run a one-line prompt', async () => {
          await playground.closeModelParameters();
          await playground.fillFirstMessage('Reply with the single word OK.');
        });

        const request = await test.step('Capture the outbound completion request', async () => {
          // Armed before the click: the POST is in flight the moment Run is
          // pressed, so subscribing afterwards would race it.
          const sent = page.waitForRequest(
            (r) => r.method() === 'POST' && isChatCompletion(r.url()),
            { timeout: 60_000 },
          );
          await playground.clickRun();
          return sent;
        });

        await test.step('The request carries exactly what the panel displayed', async () => {
          const body = request.postDataJSON() as Record<string, unknown>;

          expect(body.model, 'the request names the selected model').toBe('claude-sonnet-4-6');
          expect(
            body.top_p,
            'the live Top P control is what the request carries, at the displayed value',
          ).toBe(topPDisplayed);
          // The pair Anthropic rejects together must never both be sent, and the
          // unmounted control must not leave a value behind.
          expect(
            Object.keys(body),
            'no temperature alongside top_p — Anthropic refuses a request holding both',
          ).not.toContain('temperature');
          expect(
            body.thinking_effort,
            'the effort the dropdown displays is the effort the provider is asked for',
          ).toBe(expectedEffort);
        });
      },
    );
  },
);
