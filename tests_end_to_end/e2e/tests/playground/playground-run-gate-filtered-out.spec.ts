import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * A dataset-grid filter that excludes every row closes the Playground's run
 * gate, on every surface that can start a run, with the reason naming the
 * filter (opik#8750).
 *
 * Zero matching rows is a real state rather than a loading one, and it is the
 * one the grid filter made reachable: before it, a loaded dataset either had
 * items or did not. Running against an empty item list builds no
 * prompt/item combinations at all, so a gate that let it through would post an
 * experiment that could never produce a row — and `PlaygroundHeader`'s own
 * comment says as much ("Zero rows is a real state, not a loading one: a filter
 * can exclude everything").
 *
 * ## Why both Run surfaces, and why that is not one assertion twice
 *
 * The header's Run and each variant card's Run compute their refusal
 * SEPARATELY. `PlaygroundHeader` reads `useDatasetItemsTotal()` into
 * `isExperimentMode && total === 0`; `PlaygroundRunButton` reads the same store
 * value into `!!datasetId && total === 0` off its own `usePlaygroundDataset()`
 * call, and renders its own tooltip. Neither is evidence about the other: a
 * build that fixed the condition in one place leaves the other starting runs
 * that cannot do anything, and the variant cards are the surface a user reaches
 * for when they want to run one column.
 *
 * ## Why this is not covered by the empty-version guard
 *
 * `playground.empty-source-run-guard` is about a dataset VERSION with no items
 * — a different predicate (`isDatasetEmpty`, from `versionItemsTotal`) with a
 * different reason, checked earlier in the same chain. On a version that holds
 * items, that branch never fires, so nothing in the estate reaches this one.
 *
 * ## It proves the gate can open again
 *
 * The last step narrows the filter to one that matches a single row and asserts
 * every surface comes back. Without it, "Run is disabled" is equally satisfied
 * by a filter interaction that broke the page, or by a gate that refuses any
 * filtered grid at all. Driven by retyping the value rather than by clearing
 * the filter, so the second read is a fresh query the grid really has to answer
 * — see `setDatasetGridFilter`.
 *
 * ## Deterministic and provider-free
 *
 * Nothing is ever run. The provider exists only so a model is selectable (the
 * gate is unreachable without one) and its base URL refuses every connection,
 * so a spec that accidentally started a run would fail in milliseconds rather
 * than bill. The matching and non-matching values are both fixed strings over
 * the `dataset` fixture's own three items.
 */
test.describe(
  'Playground — the run gate when a filter excludes every dataset row',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test.use({ viewport: { width: 1600, height: 900 } });

    /** `runDisabledReason`'s / `promptRunDisabledReason`'s filter branch, verbatim. */
    const NO_MATCHES_REASON = 'No dataset items match the current filters';
    /** The header's tooltip when a dataset run is allowed. */
    const RUNNABLE_HEADER_REASON = 'Run experiment on dataset';

    /** The dataset column every item carries, and the one filtered on. */
    const FILTER_COLUMN = 'input';

    test(
      'every Run surface refuses it with the filter reason, and lets go when one row matches again',
      { tag: ['@cap:playground.run-against-dataset'] },
      async ({ project, dataset, providerKeys, testNamespace, page }) => {
        test.setTimeout(240_000);

        const rowCount = dataset.items.length;
        expect(
          rowCount,
          'more than one row, so narrowing to one is a real narrowing',
        ).toBeGreaterThan(1);

        /**
         * A value no seeded item's `input` contains, and one exactly one item's
         * does.
         *
         * The matching value is read off the fixture's own items rather than
         * written out, so a reseeded fixture cannot leave this asserting
         * against a row that no longer exists. The non-matching one is
         * namespaced by the run, so it cannot collide with anything.
         */
        const noMatchValue = `${testNamespace}-matches-nothing`;
        const oneMatchValue = dataset.items[1].input;

        await test.step('The two filter values really discriminate over the seeded items', async () => {
          // Load-bearing, and asserted before a browser is opened. Every
          // assertion below is about how many rows a filter leaves, and a value
          // that happened to match nothing — or everything — would satisfy one
          // half of this spec while proving nothing.
          const matching = dataset.items.filter((item) => item.input.includes(oneMatchValue));
          expect(
            matching,
            `exactly one seeded item's input contains "${oneMatchValue}"`,
          ).toHaveLength(1);
          expect(
            dataset.items.filter((item) => item.input.includes(noMatchValue)),
            `no seeded item's input contains "${noMatchValue}"`,
          ).toHaveLength(0);
        });

        const modelId = await test.step('Seed a selectable provider that refuses every connection', async () =>
          providerKeys.createUnreachable({
            providerName: `${testNamespace}-unreachable`,
            modelName: `${testNamespace}-dead-model`,
          }));

        const playground = new PlaygroundPage(page, project.id);

        await test.step(`Load the ${rowCount}-item dataset with TWO variants`, async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.configureVariant(0, {
            userPrompt: '{{input}}',
            modelDisplayName: modelId.split('/').pop(),
          });
          // The per-variant Run control only renders once there is more than
          // one variant (`showPerPromptRunControls`), so a single-variant page
          // cannot exercise that half of the gate at all.
          await playground.duplicateLastVariant();
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({
            mode: 'dataset',
            entityName: dataset.name,
          });
          await expect(playground.loadedSourcePill()).toBeVisible();
          await playground.waitForRunReady({ expectedRows: rowCount });
        });

        await test.step('With no filter, every Run surface is open', async () => {
          // The baseline. Every assertion after the filter is about a change,
          // and a gate that was already closed would satisfy all of them having
          // proved nothing.
          expect(
            await playground.mountedRowIds(),
            'the grid holds every seeded row',
          ).toHaveLength(rowCount);
          await expect(playground.headerRunButton()).toBeEnabled();
          expect(await playground.headerRunTooltipText()).toBe(RUNNABLE_HEADER_REASON);
          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeEnabled();
          }
          // Deliberately not reading each variant's tooltip here. A tooltip on
          // an ENABLED control closes on pointer-leave, so three reads in a row
          // leave Radix's positioning wrapper moving over the next trigger —
          // measured on 2.2.119, where it swallowed the hover and the read
          // waited on a tooltip that could never open. The variants' own reason
          // is read where it is the claim: in the refusal step below, where
          // both controls are disabled and `hoverAndReadTooltip`'s Escape is
          // the dismissal that works.
          await expect(
            playground.datasetGridFiltersTrigger(),
            'the grid Filters control is mounted in dataset mode',
          ).toHaveCount(1);
        });

        await test.step(`Filter ${FILTER_COLUMN} to a value no row carries`, async () => {
          // The total comes from the grid's own response rather than from the
          // DOM, so this cannot pass against a filter the grid never received —
          // which is the trap this interaction is known for: the popover
          // applies on close, and an incomplete row is dropped in silence.
          const total = await playground.setDatasetGridFilter({
            datasetId: dataset.id,
            column: FILTER_COLUMN,
            value: noMatchValue,
          });
          expect(total, 'the grid received the filter and it matched nothing').toBe(0);
        });

        await test.step('The grid is empty', async () => {
          await expect
            .poll(async () => (await playground.mountedRowIds()).length, {
              message: 'the filtered grid must render no rows before the gate is read',
              timeout: 15_000,
            })
            .toBe(0);
        });

        await test.step('The header refuses the run, naming the filter', async () => {
          await expect(playground.headerRunButton()).toBeDisabled();
          // The reason itself, not merely that the button is off: the header
          // checks four other conditions before this one, and a build that
          // closed the gate for the wrong reason would tell the user to fix
          // something that is not wrong.
          expect(await playground.headerRunTooltipText()).toBe(NO_MATCHES_REASON);
        });

        await test.step("…and so does every variant card's own Run", async () => {
          // The second surface, which computes the same refusal from its own
          // call and renders its own tooltip. Both variants, so a gate wired to
          // the first card only fails here.
          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeDisabled();
            expect(await playground.variantRunTooltipText(index)).toBe(NO_MATCHES_REASON);
          }
        });

        await test.step(`Narrow the same filter to a value exactly one row carries`, async () => {
          const total = await playground.setDatasetGridFilter({
            datasetId: dataset.id,
            column: FILTER_COLUMN,
            value: oneMatchValue,
          });
          // One, not "more than zero": a filter the grid dropped on the way
          // through would answer with all three and open the gate below for a
          // reason that has nothing to do with the match.
          expect(total, 'the grid received the narrowed filter and it matched one row').toBe(1);
        });

        await test.step('Every Run surface opens again', async () => {
          // The control that makes the refusals above mean something. Asserted
          // with the generic reasons rather than as a bare "not disabled": a
          // build that cleared the reason without enabling the control, or
          // enabled it while still claiming nothing matched, would pass that.
          await expect
            .poll(async () => (await playground.mountedRowIds()).length, {
              message: 'the narrowed grid must render its one row before the gate is read',
              timeout: 15_000,
            })
            .toBe(1);
          await expect(playground.headerRunButton()).toBeEnabled();
          expect(await playground.headerRunTooltipText()).toBe(RUNNABLE_HEADER_REASON);
          for (const index of [0, 1]) {
            await expect(playground.variantRunButton(index)).toBeEnabled();
          }
        });
      },
    );
  },
);
