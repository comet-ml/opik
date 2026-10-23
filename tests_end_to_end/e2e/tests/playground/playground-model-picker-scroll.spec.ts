import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { providerModelNames } from '@e2e/core/provider-keys';

/**
 * The model picker's option list now sits in an `ArrowScrollArea` with a real
 * max height, so a list longer than the box gets its own scroll arrows — a 24px
 * opaque strip pinned over the top or bottom edge of the list.
 *
 * The regression that strip invites is that it covers an option. An arrow drawn
 * over the first row at the top, or the last row at the bottom, would make that
 * model permanently unselectable: every click at its centre would hit the arrow
 * and scroll instead. The component avoids it by RETRACTING each arrow at the
 * end it would otherwise cover, which is why the arrow state and the
 * selectability of the two terminal options are the two things asserted here.
 *
 * Neither is reachable through the estate's existing model selection. Every
 * other helper — `setModelForVariant`, `selectModelFromProvider`,
 * `OptimizationStudioPage.selectModel` — types a full model name into "Search
 * model" before clicking, so the list is always one or two rows and never grows
 * an arrow. The component's own vitest specs stub `scrollHeight`,
 * `clientHeight`, `ResizeObserver` and `scrollBy`, so they cannot see real hit
 * testing either.
 *
 * Deterministic and free: one Custom provider declaring 30 models over REST
 * overflows the 248px viewport by construction (30 rows at 40px plus a 40px
 * group label = 1240px), it needs no provider key, and no model is ever
 * invoked — the provider's base URL refuses every connection.
 *
 * The two tests seed DIFFERENT model prefixes on purpose. Provider keys are
 * workspace-global and the suite runs `fullyParallel`, so a shared prefix would
 * let these two tests see each other's models in one filtered list.
 */

/** Rows the list must hold to overflow the picker's viewport. Not a round number by accident: see above. */
const MODEL_COUNT = 30;

/** Height of one option row (`h-10`), the smallest scroll that moves the list usefully. */
const MODEL_ROW_HEIGHT_PX = 40;

const modelNames = (prefix: string): string[] =>
  Array.from({ length: MODEL_COUNT }, (_, i) => {
    const ordinal = String(i + 1).padStart(2, '0');
    // The two names the selectability test clicks are spelled so a failure
    // message says which end of the list was unreachable.
    if (i === 0) return `${prefix}-${ordinal}-first`;
    if (i === MODEL_COUNT - 1) return `${prefix}-${ordinal}-last`;
    return `${prefix}-${ordinal}`;
  });

test.describe(
  'Playground — the model picker\'s scrolling option list',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'The scroll arrows show only in the direction the list can still scroll',
      { tag: ['@cap:playground.select-model-provider'] },
      async ({ page, project, testNamespace, providerKeys }) => {
        const providerName = `${testNamespace}-arrows`;
        const prefix = 'arrowstate';
        const models = modelNames(prefix);

        await test.step(`Seed one provider offering ${MODEL_COUNT} models`, async () => {
          await providerKeys.createWithModels({ providerName, modelNames: models });
        });

        await test.step('The provider really stored every model', async () => {
          // Before the browser opens. A provider that kept fewer models would
          // not overflow the viewport, no arrow would ever render, and every
          // absence assertion below would pass having proven nothing.
          expect(
            await providerModelNames(providerName),
            `the provider must offer all ${MODEL_COUNT} models, in order`,
          ).toEqual(models);
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Open the picker and filter to the seeded list', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.openModelPicker(0);
          await playground.filterModelOptions(prefix, MODEL_COUNT);
          expect(
            await playground.modelListOverflows(),
            'the seeded list must be longer than the picker viewport, or there is ' +
              'nothing for an arrow to do',
          ).toBe(true);
        });

        await test.step('At the top only the down arrow is mounted', async () => {
          await playground.scrollModelListTo('top');
          await expect(
            playground.modelListScrollArrow('up'),
            'nothing above the first row, so no up arrow may cover it',
          ).toHaveCount(0);
          await expect(playground.modelListScrollArrow('down')).toBeVisible();
        });

        await test.step('One press of the down arrow scrolls the list', async () => {
          // The arrow existing is not the claim — it has to MOVE the list. This
          // is the regression that prompted the component: the Radix arrow it
          // replaced scrolled only on hover, by the height of the selected
          // item, and no item is mounted in this list, so pressing it did
          // nothing at all.
          //
          // A band rather than the 120px the component scrolls today, for the
          // same reason the sampling-rate spec bands its own count: the
          // failures worth catching are structural — a press that scrolls
          // NOTHING (the bug above), or one that jumps a whole screen and skips
          // rows the user was reading. Retuning the nudge between those bounds
          // is not a regression, and pinning the constant here would fail the
          // build for a change no user could notice.
          const viewportHeight = await playground.modelListViewportHeight();
          const settled = await playground.clickModelListScrollArrow('down');
          expect(
            settled,
            'one press must scroll at least a full row — the arrow it replaced ' +
              'scrolled by nothing',
          ).toBeGreaterThanOrEqual(MODEL_ROW_HEIGHT_PX);
          expect(
            settled,
            'and at most one screenful, so a press nudges the list rather than ' +
              'skipping past rows unseen',
          ).toBeLessThanOrEqual(viewportHeight);

          await expect(
            playground.modelListScrollArrow('up'),
            'mid-list, both directions are available',
          ).toBeVisible();
          await expect(playground.modelListScrollArrow('down')).toBeVisible();
        });

        await test.step('At the bottom only the up arrow is mounted', async () => {
          await playground.scrollModelListTo('bottom');
          await expect(
            playground.modelListScrollArrow('down'),
            'nothing below the last row, so no down arrow may cover it',
          ).toHaveCount(0);
          await expect(playground.modelListScrollArrow('up')).toBeVisible();
        });

        await test.step('A list that fits shows no arrows at all', async () => {
          // One model matches this term, so the list stops overflowing and both
          // arrows must go — a strip left over a list with nothing to scroll is
          // pure obstruction.
          await playground.filterModelOptions(`${prefix}-07`, 1);
          expect(
            await playground.modelListOverflows(),
            'a one-row list must fit the viewport',
          ).toBe(false);
          await expect(playground.modelListScrollArrow('up')).toHaveCount(0);
          await expect(playground.modelListScrollArrow('down')).toHaveCount(0);
        });
      },
    );

    test(
      'The first and last models of an overflowing list are both selectable',
      { tag: ['@cap:playground.select-model-provider'] },
      async ({ page, project, testNamespace, providerKeys }) => {
        const providerName = `${testNamespace}-ends`;
        const prefix = 'endpick';
        const models = modelNames(prefix);
        const firstModel = models[0];
        const lastModel = models[MODEL_COUNT - 1];

        await test.step(`Seed one provider offering ${MODEL_COUNT} models`, async () => {
          await providerKeys.createWithModels({ providerName, modelNames: models });
        });

        await test.step('The provider really stored every model', async () => {
          expect(
            await providerModelNames(providerName),
            `the provider must offer all ${MODEL_COUNT} models, in order`,
          ).toEqual(models);
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Open the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
        });

        // The LAST model is picked FIRST, and the first model after it. Order
        // matters: the Playground defaults a fresh prompt card to the first
        // model it can offer, so selecting the first one from a clean page
        // would assert a label that was already on screen — true whether or not
        // the click ever landed. Taken in this order, each selection has to
        // CHANGE the trigger, and the one that would be masked is the one made
        // against a trigger already showing the other end of the list.
        await test.step(`Select "${lastModel}" from the bottom of the list`, async () => {
          await playground.openModelPicker(0);
          await playground.filterModelOptions(prefix, MODEL_COUNT);
          await playground.scrollModelListTo('bottom');
          expect(
            await playground.selectedModelLabel(0),
            'the picker must not already be showing the model about to be clicked',
          ).not.toBe(lastModel);

          await playground.chooseModelOption(providerName, lastModel);
          expect(
            await playground.selectedModelLabel(0),
            'the last option, sitting where the up arrow is drawn, selected on a click',
          ).toBe(lastModel);
        });

        await test.step(`Select "${firstModel}" from the top of the list`, async () => {
          await playground.openModelPicker(0);
          await playground.filterModelOptions(prefix, MODEL_COUNT);
          // Reopening lands the list on the selected model, which is now at the
          // bottom, so this scroll is a real gesture and not a no-op.
          await playground.scrollModelListTo('top');
          expect(
            await playground.selectedModelLabel(0),
            'still showing the previous pick, so the next assertion is a change',
          ).toBe(lastModel);

          await playground.chooseModelOption(providerName, firstModel);
          expect(
            await playground.selectedModelLabel(0),
            'the first option, sitting where the down arrow is drawn, selected on a click',
          ).toBe(firstModel);
        });
      },
    );
  },
);
