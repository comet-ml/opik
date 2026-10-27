import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';

/**
 * The playground's model-parameters panel must send exactly what it displays.
 *
 * The defect class here is silent by construction — neither surface can catch
 * it alone:
 *
 *   - **API-only** cannot: a request carrying `temperature` when the panel has
 *     taken the sampling pair away, or carrying no effort at all, is a
 *     perfectly well-formed request. There is nothing wrong with it except that
 *     it is not what the user asked for.
 *   - **UI-only** cannot either: the panel renders whatever its own config
 *     says, so a control showing "Low" over a config the request builder then
 *     strips looks entirely correct on screen.
 *
 * So the assertion is the panel's own displayed values compared against the
 * outbound `POST /v1/private/chat/completions` body. It costs money at the
 * wrong setting rather than erroring, which is why it is worth a permanent
 * test.
 *
 * ## What opik#8880 changed here, and why this spec changed with it
 *
 * The effort level now turns thinking ON. `claude-sonnet-4-6` thinks only when
 * asked, so its control gained an **Off** choice and opens on it, and at any
 * other level the Temperature/Top P pair is taken away — Anthropic refuses a
 * `temperature` alongside thinking, answering "temperature may only be set to 1
 * when thinking is enabled". The previous version of this spec asserted the
 * behaviour that replaced: it read the dropdown's opening label against a map
 * with no `Off` entry, and asserted the body still carried `top_p` after a
 * level was picked. Both are now wrong by design, so the spec had to be
 * rewritten rather than extended.
 *
 * The replacement keeps the same shape — panel versus outbound body — because
 * that is the pair that catches the case neither surface can see on its own: a
 * request still carrying `temperature` once Claude can think is well-formed,
 * and Anthropic answers it with a 400 only in production.
 *
 * Anthropic-specific on purpose. The sampling pair is a single exclusive choice
 * only because Anthropic rejects a request carrying both, and
 * `claudeWithSampling` is picked because it is the one model exposing BOTH
 * halves under test — an effort control WITH an Off choice, and the sampling
 * toggle. Its newer siblings set `supportsSamplingParams: false` and render no
 * toggle at all.
 *
 * The effort travels as `custom_parameters.output_config.effort`, the slot the
 * backend proxy forwards to Anthropic's `output_config.effort`. A flat
 * `thinking_effort` is what the proxy used to drop without a trace, so the body
 * must not carry one.
 *
 * Deterministic and billing-free: the completion is short-circuited at the
 * browser, every assertion is on the request the browser SENT, and the model is
 * registered unbilled by `modelRegistryProviders`.
 */

/** The model's own id, as the request must name it. */
const MODEL_ID = 'claude-sonnet-4-6';

/**
 * Every level the control offers, in order. Off first: it is Opik's own choice,
 * not one of Anthropic's, and it is never sent as an effort.
 *
 * The whole list rather than "Off is present", because a build that dropped the
 * Off choice and one that dropped the entire control read the same through a
 * single membership check.
 */
const OFFERED_EFFORTS = ['Off', 'Low', 'Medium', 'High', 'Max'] as const;

/**
 * The level this spec switches to, and the value it must travel as.
 *
 * Deliberately not the default: a request carrying the default would also pass
 * on a build that ignored the control entirely and let `resolveEffort`
 * substitute the model's own default.
 */
const CHOSEN_EFFORT = { label: 'Low', value: 'low' } as const;

/**
 * The temperature this spec sets. Not the control's default of 0 either: a body
 * carrying 0 would equally be satisfied by a build that sent the default
 * whatever the slider held, and 0 is also falsy, which makes a dropped value
 * and a sent one hard to tell apart in an assertion.
 */
const CHOSEN_TEMPERATURE = '0.4';

test.describe(
  'Playground — model parameters',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'The Anthropic panel sends the sampling parameter it displays, and swaps it for the thinking effort at any level',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(180_000);

        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.claudeWithSampling}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.claudeWithSampling);
          await playground.openModelParameters(0);
        });

        await test.step('The effort control opens on Off and offers Off plus the four levels', async () => {
          await expect(
            playground.modelParameterLabel('Thinking effort'),
            'the control is labelled for a model whose effort turns thinking on',
          ).toHaveCount(1);
          expect(
            await playground.readThinkingEffort(),
            'Off is the default: the model answers without thinking unless the request asks, so ' +
              'opening a new prompt must not start it thinking',
          ).toBe('Off');
          expect(
            await playground.thinkingEffortOptions(),
            'exactly the levels the backend accepts for this model, with Opik’s own Off in front',
          ).toEqual([...OFFERED_EFFORTS]);
        });

        await test.step('At Off the sampling pair is there, as one choice with one live slider', async () => {
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
          await expect(
            playground.samplingOffNote(),
            'nothing is off while the effort is Off, so there is no note to show',
          ).toHaveCount(0);
        });

        await test.step(`Set Temperature to ${CHOSEN_TEMPERATURE} and run`, async () => {
          await playground.setModelParameterSlider('temperature', CHOSEN_TEMPERATURE);
          await playground.closeModelParameters();
          await playground.fillUserMessage('Reply with the single word OK.');
        });

        await test.step('At Off the body carries that temperature and no effort at all', async () => {
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model, 'the request names the selected model').toBe(MODEL_ID);
          expect(
            body.temperature,
            'the live half of the pair is what the request carries, at the displayed value',
          ).toBe(Number(CHOSEN_TEMPERATURE));
          expect(
            Object.keys(body),
            'no top_p alongside temperature — Anthropic refuses a request holding both',
          ).not.toContain('top_p');
          // The whole key is absent, not an empty object: an `output_config`
          // left behind with no effort in it is the shape `toMatchObject`
          // would pass over, and it is what a half-done strip looks like.
          expect(
            Object.keys(body),
            'Off sends no effort — it is Opik’s own choice and never reaches Anthropic',
          ).not.toContain('custom_parameters');
        });

        await test.step(`Setting the effort to ${CHOSEN_EFFORT.label} takes the sampling pair away`, async () => {
          await playground.openModelParameters(0);
          await playground.selectThinkingEffort(CHOSEN_EFFORT.label);

          // Unmounted, not dimmed, and the CHOICE goes too, not just the live
          // slider: a Temperature the panel still offered to set would be a
          // control over a parameter the request no longer carries.
          await expect(
            playground.samplingOption('Temperature'),
            'the Temperature / Top P choice leaves the DOM once Claude can think',
          ).toHaveCount(0);
          await expect(playground.samplingOption('Top P')).toHaveCount(0);
          await expect(
            playground.sliderInput('temperature'),
            'and so does the live slider, rather than lingering with a stale value',
          ).toHaveCount(0);
          await expect(playground.sliderInput('topP')).toHaveCount(0);

          // The exact sentence, not merely "a note appeared": this surface can
          // store a Top P, so it must name both parameters. The judge-rule
          // panel, which cannot, renders the temperature-only wording, and a
          // panel showing the wrong one tells the user to go and set a control
          // that is not there.
          await expect(
            playground.samplingOffNote(),
            'the note names both parameters the playground took away',
          ).toHaveText(
            'Temperature and Top P are off while Claude can think. Set Thinking effort to Off to use them.',
          );

          await playground.closeModelParameters();
        });

        await test.step('…and the body carries the effort instead of either sampling parameter', async () => {
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model).toBe(MODEL_ID);
          expect(
            body.custom_parameters,
            'the effort the dropdown displays is the effort the provider is asked for, in the ' +
              'slot the backend forwards as output_config.effort',
          ).toEqual({ output_config: { effort: CHOSEN_EFFORT.value } });
          expect(
            Object.keys(body),
            'no temperature once Claude can think — Anthropic answers that request with a 400',
          ).not.toContain('temperature');
          expect(Object.keys(body), 'and no top_p either').not.toContain('top_p');
          expect(
            Object.keys(body),
            'no flat thinking_effort — the backend proxy drops unknown top-level fields',
          ).not.toContain('thinking_effort');
        });

        await test.step('Back at Off the sampling control returns holding the value it had', async () => {
          await playground.openModelParameters(0);
          await playground.selectThinkingEffort('Off');

          await expect(
            playground.selectedSamplingOptions(),
            'the choice is back, with one half live',
          ).toHaveCount(1);
          // The VALUE, not just the control: the config keeps the user's
          // temperature while the effort hides it, so a build that re-mounted
          // the slider at the model default would have silently discarded it.
          await expect(
            playground.sliderInput('temperature'),
            'the temperature set before the effort hid it, not the model default',
          ).toHaveValue(CHOSEN_TEMPERATURE);
          await expect(
            playground.samplingOffNote(),
            'and the note goes with it',
          ).toHaveCount(0);

          await playground.closeModelParameters();
        });

        await test.step('…and the next request carries it again, with no stale effort', async () => {
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model).toBe(MODEL_ID);
          expect(
            body.temperature,
            'the restored value is really sent, so the round trip cost the user nothing',
          ).toBe(Number(CHOSEN_TEMPERATURE));
          expect(
            Object.keys(body),
            'and the effort picked in between does not survive the return to Off',
          ).not.toContain('custom_parameters');
        });
      },
    );

    test(
      'a Claude model whose effort does not turn thinking on keeps its sampling pair at every level',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        // The control that makes the test above mean something. Without it, "a
        // level takes the sampling pair away" is equally satisfied by a build
        // that takes it away from every Claude model with an effort control —
        // which would leave this one, whose effort only changes answer length
        // and tool use, with nothing to tune and no reason given.
        test.setTimeout(180_000);
        expect(modelRegistryProviders.providers).toContain('anthropic');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.claudeEffortWithoutThinking}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.claudeEffortWithoutThinking);
          await playground.openModelParameters(0);
        });

        await test.step('Its control is labelled Effort and offers no Off', async () => {
          await expect(
            playground.modelParameterLabel('Effort'),
            'the label drops "Thinking", because this effort does not turn thinking on',
          ).toHaveCount(1);
          await expect(
            playground.modelParameterLabel('Thinking effort'),
            'and the panel is not also rendering the other label',
          ).toHaveCount(0);
          expect(
            await playground.thinkingEffortOptions(),
            'the Off choice exists only for a model that thinks solely when asked, so this one ' +
              'offers just the levels Anthropic takes',
          ).toEqual(['Low', 'Medium', 'High']);
        });

        await test.step('The sampling pair stays, at the default level and at a picked one', async () => {
          await expect(
            playground.selectedSamplingOptions(),
            'one live half at the default level',
          ).toHaveCount(1);

          await playground.selectThinkingEffort('Low');
          await expect(
            playground.selectedSamplingOptions(),
            'and still one after picking a level — thinking is not on, so Anthropic takes a ' +
              'temperature beside it',
          ).toHaveCount(1);
          await expect(
            playground.sliderInput('temperature'),
            'with its slider live rather than unmounted',
          ).toHaveCount(1);
          await expect(
            playground.samplingOffNote(),
            'and nothing was taken away, so there is no note explaining a removal',
          ).toHaveCount(0);

          await playground.closeModelParameters();
        });

        await test.step('…and the body carries the effort AND the temperature together', async () => {
          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(
            body.model,
            'this is a different model from the one above, not another spelling of it',
          ).toBe('claude-opus-4-5-20251101');
          expect(body.custom_parameters).toEqual({ output_config: { effort: 'low' } });
          expect(
            Object.keys(body),
            'the pair a thinking model cannot have is exactly what this one keeps',
          ).toContain('temperature');
        });
      },
    );
  },
);
