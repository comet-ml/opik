import { test, expect, REGISTRY_MODEL } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { captureCompletionBody } from '@e2e/core/playground-completions';

/**
 * What happens to a Claude thinking effort when the model changes
 * (OPIK-8636, opik#8841).
 *
 * `updateProviderConfig` is a single reconciler with two rules, and which one
 * applies turns on whether the user ever touched the control:
 *
 *   - an effort still sitting on the PREVIOUS model's default was never a
 *     choice, so the NEW model's own default replaces it;
 *   - an effort the user PICKED is a choice, so it moves to the nearest level
 *     the new model offers — a tie going to the lower, cheaper one — rather
 *     than being thrown away for the default.
 *
 * Both failure directions are silent and expensive. An effort that quietly
 * carries over bills for thinking nobody asked for; one that quietly resets
 * drops thinking the user is paying for. Neither puts anything on screen to
 * say so, and on a model with no Off a carried-over `off` is a level Anthropic
 * answers with a 400 — which the user only ever sees as a run that failed.
 *
 * ## Why this model pair
 *
 * "The new model's default" and "the nearest level the new model offers" give
 * the SAME answer for most pairs, which is why the obvious ones cannot test
 * this: Claude Opus 4.5's default High is also the nearest to Max among its
 * low/medium/high. {@link REGISTRY_MODEL.claudeEffortDefaultMedium} against
 * {@link REGISTRY_MODEL.claudeWithSampling} is a pair where they differ —
 * Medium/Off as defaults, and xHigh offered by one and not the other — so each
 * case below has a different expected answer under each rule.
 *
 * ## Why both surfaces
 *
 * The panel's displayed level and the outbound body can disagree, and only
 * comparing them catches it: the reconciler writes the settled level back into
 * the config, while `resolveEffort` independently masks a stored level the
 * model does not offer for DISPLAY. A build where the write-back failed shows
 * the right level over a config that still holds the old one, and sends the
 * old one.
 *
 * ## Why this is not already covered
 *
 * `playground-model-parameters.spec.ts` covers this capability on one model and
 * never changes it, so the reconciler is untouched by it.
 * `playground-openai-effort-pipeline-mode.spec.ts` is the OpenAI half of the
 * same function, reached through a key's pipeline mode rather than a model
 * change. `getNearestOfferedLevel` had no e2e cover at all.
 *
 * Deterministic and billing-free: no seeding beyond the fixture project, every
 * completion is short-circuited at the browser, and the models are registered
 * unbilled by `modelRegistryProviders`.
 */

/**
 * The id the request must name after every switch below ends on
 * {@link REGISTRY_MODEL.claudeWithSampling}. Asserted each time, so a switch
 * that silently failed to take fails here rather than having the effort
 * assertion read the previous model's panel.
 */
const SONNET_ID = 'claude-sonnet-4-6';

test.describe(
  'Playground — Anthropic thinking effort across a model change',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      "an untouched effort adopts the new model's default rather than carrying over",
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(180_000);
        expect(
          modelRegistryProviders.providers,
          'the Anthropic registry is selectable',
        ).toContain('anthropic');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${REGISTRY_MODEL.claudeEffortDefaultMedium}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.claudeEffortDefaultMedium);
          await playground.openModelParameters(0);
        });

        await test.step('It opens on its own default, which is not the other model’s', async () => {
          // The premise of the whole test: "untouched" only means anything if
          // the starting level really is this model's default and really
          // differs from the next model's. Without this the case below passes
          // on a build where nothing was ever set.
          expect(
            await playground.readThinkingEffort(),
            `${REGISTRY_MODEL.claudeEffortDefaultMedium} declares defaultThinkingEffort: medium`,
          ).toBe('Medium');
          expect(
            await playground.thinkingEffortOptions(),
            'and it always thinks, so it offers no Off',
          ).toEqual(['Low', 'Medium', 'High', 'xHigh', 'Max']);
          await playground.closeModelParameters();
        });

        await test.step(`Switch to ${REGISTRY_MODEL.claudeWithSampling} without touching the control`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.claudeWithSampling);
          await playground.openModelParameters(0);
        });

        await test.step('The control reads Off — the new model’s default, not the nearest level', async () => {
          expect(
            await playground.readThinkingEffort(),
            'Medium is offered by this model too, so "nearest offered" would have kept it; an ' +
              'untouched level must instead follow the new model’s own default, which is Off',
          ).toBe('Off');
          await expect(
            playground.selectedSamplingOptions(),
            'and with thinking off the sampling pair comes back',
          ).toHaveCount(1);
          await playground.closeModelParameters();
        });

        await test.step('…and the request carries a sampling parameter and no effort', async () => {
          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model, 'the request names the newly selected model').toBe(SONNET_ID);
          expect(
            Object.keys(body),
            'Off sends no effort, so the previous model’s medium must not appear anywhere',
          ).not.toContain('custom_parameters');
          expect(
            Object.keys(body),
            'and the temperature the panel put back is really sent',
          ).toContain('temperature');
        });
      },
    );

    test(
      'a picked effort the new model does not offer moves to the nearest one it does',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        test.setTimeout(180_000);
        expect(modelRegistryProviders.providers).toContain('anthropic');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Pick xHigh on ${REGISTRY_MODEL.claudeEffortDefaultMedium}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.claudeEffortDefaultMedium);
          await playground.openModelParameters(0);
          // xHigh on purpose: it is the one level this model offers that the
          // next one does not, so it is the only choice that forces the
          // nearest-offered branch rather than being kept as-is.
          await playground.selectThinkingEffort('xHigh');
          await playground.closeModelParameters();
        });

        await test.step(`Switch to ${REGISTRY_MODEL.claudeWithSampling}`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.claudeWithSampling);
          await playground.openModelParameters(0);
        });

        await test.step('The control reads High — nearest offered, tie to the lower — and not Off', async () => {
          expect(
            await playground.readThinkingEffort(),
            'High and Max are equidistant from xHigh and the tie goes to the cheaper one; Off is ' +
              'this model’s default and would be the answer only if a PICKED level were discarded',
          ).toBe('High');
          await expect(
            playground.selectedSamplingOptions(),
            'a level other than Off still takes the sampling pair away on the new model',
          ).toHaveCount(0);
          await playground.closeModelParameters();
        });

        await test.step('…and the request carries that level, not the one that was picked', async () => {
          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model).toBe(SONNET_ID);
          expect(
            body.custom_parameters,
            'the settled level is written back to the config, so it is what the provider is asked ' +
              'for — an xhigh here is a level this model’s backend answers with a 400',
          ).toEqual({ output_config: { effort: 'high' } });
          expect(
            Object.keys(body),
            'and thinking is on, so neither sampling parameter goes',
          ).not.toContain('temperature');
          expect(Object.keys(body)).not.toContain('top_p');
        });
      },
    );

    test(
      'a picked effort both models offer survives the switch untouched, both ways',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ modelRegistryProviders, project, page }) => {
        // The control that makes the two cases above mean something: without
        // it, "the level changed to X" is equally satisfied by a build that
        // recomputes the level on every model change and never keeps one — the
        // silent reset this reconciler exists to avoid.
        test.setTimeout(180_000);
        expect(modelRegistryProviders.providers).toContain('anthropic');

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Pick Max on ${REGISTRY_MODEL.claudeWithSampling}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.selectModel(0, REGISTRY_MODEL.claudeWithSampling);
          await playground.openModelParameters(0);
          // Max, because both models offer it and neither one defaults to it:
          // a level equal to either default would be indistinguishable from
          // having been reset to it.
          await playground.selectThinkingEffort('Max');
          await playground.closeModelParameters();
        });

        await test.step(`It is still Max on ${REGISTRY_MODEL.claudeEffortDefaultMedium}`, async () => {
          await playground.selectModel(0, REGISTRY_MODEL.claudeEffortDefaultMedium);
          await playground.openModelParameters(0);
          expect(
            await playground.readThinkingEffort(),
            'this model offers Max, so there is nothing to reconcile — a Medium here would be the ' +
              'default silently overwriting a real choice',
          ).toBe('Max');
          await playground.closeModelParameters();
        });

        await test.step('…and still Max on the way back', async () => {
          await playground.selectModel(0, REGISTRY_MODEL.claudeWithSampling);
          await playground.openModelParameters(0);
          expect(
            await playground.readThinkingEffort(),
            'and an Off here would be the same overwrite in the other direction',
          ).toBe('Max');
          await playground.closeModelParameters();
        });

        await test.step('The request carries max, so the level really survived in the config', async () => {
          await playground.fillUserMessage('Reply with the single word OK.');
          const body = await captureCompletionBody(page, () => playground.clickRun());

          expect(body.model).toBe(SONNET_ID);
          expect(
            body.custom_parameters,
            'the panel reading Max over a config holding something else is exactly the ' +
              'disagreement a UI-only assertion cannot see',
          ).toEqual({ output_config: { effort: 'max' } });
        });
      },
    );

  },
);
