import { test, expect } from '@e2e/fixtures';
import type { Page } from '@playwright/test';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ConfigurationPage } from '@e2e/pom/configuration.page';
import { assertAllowedModelDisplayName } from '@e2e/core/llm-model-policy';

// Non-Claude on purpose: a Claude model, even on OpenRouter, gets a one-of
// Temperature / Top P choice instead of its own Temperature slider. On OpenRouter
// the display name is the model id.
const MODEL = 'openai/gpt-4o-mini';
const OPENROUTER_GROUP = 'OpenRouter';

function isChatCompletion(url: string): boolean {
  return new URL(url).pathname.endsWith('/v1/private/chat/completions');
}

async function selectFromOpenRouterGroup(page: Page, model: string): Promise<boolean> {
  return test.step(`select "${model}" from the ${OPENROUTER_GROUP} group`, async () => {
    assertAllowedModelDisplayName(model);
    const listbox = page.getByRole('listbox');
    const search = listbox.getByPlaceholder('Search model');
    // Scoped to the built-in group: the Custom "openrouter" provider that
    // ensureModelAvailable and the provider-sanity matrix create offers the same
    // name, and PlaygroundPage.selectModel takes whichever option comes first.
    const option = listbox
      .getByRole('group', { name: OPENROUTER_GROUP, exact: true })
      .getByRole('option', { name: model, exact: true });

    await expect(async () => {
      await page.getByTestId('select-a-llm-model').first().click();
      await expect(listbox).toBeVisible({ timeout: 2_000 });
    }).toPass({ timeout: 15_000 });

    await search.fill(model);
    const offered = await option
      .waitFor({ state: 'visible', timeout: 5_000 })
      .then(() => true)
      .catch(() => false);
    if (!offered) {
      await page.keyboard.press('Escape');
      await expect(listbox).toBeHidden();
      return false;
    }

    await expect(async () => {
      await search.fill(model);
      await option.click({ timeout: 2_000 });
      await expect(listbox).toBeHidden({ timeout: 2_000 });
    }).toPass({ timeout: 30_000 });
    return true;
  });
}

async function commitSliderInput(
  playground: PlaygroundPage,
  controlId: string,
  value: string,
): Promise<void> {
  return test.step(`set ${controlId} to ${value}`, async () => {
    const input = playground.sliderInput(controlId);
    // A real click, not just fill(): it moves the pointer off the gear, whose
    // hover tooltip would otherwise sit above the menu and take the Escape that
    // closeModelParameters sends.
    await input.click();
    await input.fill(value);
    // SliderInputControl writes the config on blur only.
    await input.blur();
  });
}

async function nextCompletionBody(
  page: Page,
  act: () => Promise<void>,
): Promise<Record<string, unknown>> {
  const sent = page.waitForRequest(
    (r) => r.method() === 'POST' && isChatCompletion(r.url()),
    { timeout: 60_000 },
  );
  await act();
  const body = (await sent).postDataJSON() as Record<string, unknown> | null;
  expect(body, 'the Run posted a JSON completion body').not.toBeNull();
  // The POST goes out as the run starts, so wait for the run to settle before the next panel edit.
  await expect(
    page
      .getByTestId('playground-run-button')
      .and(page.locator('[data-mode="run"], [data-mode="re-run"]')),
    'the run finished and the playground is idle again',
  ).toBeVisible();
  return body!;
}

test.describe(
  'Playground — OpenRouter model parameters',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'The OpenRouter panel offers Temperature up to 2 and sends max_tokens only when it is set',
      { tag: ['@cap:playground.configure-model-settings'] },
      async ({ project, page }) => {
        test.setTimeout(180_000);

        await test.step('This workspace can offer an OpenRouter model at all', async () => {
          const cfg = new ConfigurationPage(page);
          await cfg.gotoAiProviders();
          const openRouterKey = process.env.OPENROUTER_API_KEY;
          const configured = openRouterKey
            ? await cfg.ensureProviderConfigured('OpenRouter', openRouterKey)
            : await cfg.hasProvider('OpenRouter');
          test.skip(
            !configured,
            'no OpenRouter provider on this workspace and no OPENROUTER_API_KEY to add one',
          );
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Open the playground on ${MODEL}`, async () => {
          await playground.goto();
          await playground.waitForReady();
          test.skip(
            !(await selectFromOpenRouterGroup(page, MODEL)),
            `${MODEL} is not offered under ${OPENROUTER_GROUP} by this deployment's model registry`,
          );
        });

        await test.step('Temperature is its own slider, spanning 0 to 2', async () => {
          await playground.openModelParameters(0);

          await expect(
            playground.samplingOption('Temperature'),
            'a non-Claude model gets no one-of Temperature / Top P choice',
          ).toHaveCount(0);
          await expect(playground.sliderInput('temperature')).toHaveCount(1);
          await expect(
            playground.sliderInput('topP'),
            'Top P is live alongside Temperature, not instead of it',
          ).toHaveCount(1);

          // The slider root carries the control id (its label points at it) but no
          // testid; one added now would be missing from the deployed versions this
          // suite also runs against.
          const temperatureThumb = playground
            .modelParametersPanel()
            .locator('#temperature-slider')
            .getByRole('slider');
          await expect(temperatureThumb).toHaveAttribute('aria-valuemin', '0');
          await expect(temperatureThumb).toHaveAttribute('aria-valuemax', '2');

          await expect(
            playground.sliderInput('maxTokens'),
            'Max tokens opens at 0, which means "use the provider default"',
          ).toHaveValue('0');
        });

        const firstPanel = await test.step('Type a Temperature above 1', async () => {
          await commitSliderInput(playground, 'temperature', '1.5');
          await expect(
            playground.sliderInput('temperature'),
            'a 0..1 control would clamp 1.5 down to 1 on blur',
          ).toHaveValue('1.5');
          const shown = {
            temperature: Number(await playground.sliderInput('temperature').inputValue()),
            topP: Number(await playground.sliderInput('topP').inputValue()),
          };
          await playground.closeModelParameters();
          return shown;
        });

        await test.step('Fulfil completions in the browser and write a prompt', async () => {
          // Every assertion is on the request the browser sent. Letting it through
          // would have OpenRouter generate (and bill) a completion nothing reads.
          await page.route(
            (url) => isChatCompletion(url.toString()),
            (route) =>
              route.fulfill({
                status: 200,
                contentType: 'text/event-stream',
                body: 'data: [DONE]\n\n',
              }),
          );
          await playground.fillFirstMessage('Reply with the single word OK.');
        });

        await test.step('With Max tokens at 0, the body has no max_tokens', async () => {
          const body = await nextCompletionBody(page, () => playground.clickRun());

          expect(body.model, 'the request names the OpenRouter model').toBe(MODEL);
          expect(body.temperature, 'the displayed Temperature, not clamped to 1').toBe(
            firstPanel.temperature,
          );
          expect(body.top_p, 'Top P is sent next to Temperature at its displayed value').toBe(
            firstPanel.topP,
          );
          expect(
            Object.keys(body),
            'a 0 cap must be dropped, not sent as max_tokens: 0',
          ).not.toContain('max_tokens');
        });

        const secondPanel = await test.step(
          'Set Max tokens to 64 and type a fractional Top K',
          async () => {
            await playground.openModelParameters(0);
            await commitSliderInput(playground, 'maxTokens', '64');
            await expect(playground.sliderInput('maxTokens')).toHaveValue('64');
            await commitSliderInput(playground, 'topK', '7.5');
            const shown = {
              maxTokens: Number(await playground.sliderInput('maxTokens').inputValue()),
              topK: Number(await playground.sliderInput('topK').inputValue()),
            };
            await playground.closeModelParameters();
            return shown;
          },
        );

        await test.step('The body carries the set max_tokens and an integer top_k', async () => {
          const body = await nextCompletionBody(page, () => playground.clickRun());
          const customParameters = (body.custom_parameters ?? {}) as Record<string, unknown>;

          expect(body.max_tokens, 'exactly the Max tokens the panel shows').toBe(
            secondPanel.maxTokens,
          );
          expect(body.temperature, 'Temperature is unchanged by the second edit').toBe(
            firstPanel.temperature,
          );
          // The Top K input keeps a typed fraction on screen (QA radar on #8603), so
          // the integer guarantee lives in the request builder. Comparing against the
          // rounded display stays true if the input later snaps to whole numbers.
          expect(
            Number.isInteger(customParameters.top_k),
            `top_k is an integer, got ${customParameters.top_k}`,
          ).toBe(true);
          expect(
            customParameters.top_k,
            'top_k travels in custom_parameters, which the backend forwards to OpenRouter',
          ).toBe(Math.round(secondPanel.topK));
          expect(
            Object.keys(body),
            'no flat top_k — the backend proxy drops unknown top-level fields',
          ).not.toContain('top_k');
        });
      },
    );
  },
);
