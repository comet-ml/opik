import { test, expect } from '@e2e/fixtures';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';

/**
 * The group pass rate on the prompt detail page's Experiments tab
 * (opik#9646 / OPIK-9234).
 *
 * The second call site. `ExperimentsTab` carries its OWN `aggregationKey` on its
 * own column definition, independent of `GeneralDatasetsTab`'s — the PR had to
 * change both — so the two can disagree, and a regression that lands on one
 * leaves the other looking perfectly healthy. Nothing in the estate opens this
 * tab grouped: `PromptDetailPage.gotoExperimentsTab` pins `groups=[]`.
 *
 * It is also reached differently: this tab queries experiments BY PROMPT (a
 * prompt-id filter on the same groups endpoint) rather than by project, so it is
 * not merely the same request rendered twice.
 *
 * Deliberately not a copy of every case `test-suite-group-pass-rate.spec.ts`
 * makes on the Experiments page. What is worth asserting here is that this call
 * site agrees with that one — the mean rather than the pooled ratio, the cap on
 * the group row and on the experiment row inside it, the tooltip's wording, and
 * an empty cell where there is no suite run — on a seed built once and
 * navigated to twice. The dot-colour branches are pinned on the other surface;
 * they come from the same component and nothing on this tab selects them.
 */
test.describe(
  'Prompt — Experiments tab group pass rate',
  { tag: ['@t2-cuj', '@area:prompts'] },
  () => {
    // The Pass rate column must be inside the rendered column window; the shared
    // DataTable slices cells to the visible span.
    test.use({ viewport: { width: 1600, height: 900 } });

    test(
      'group row shows the mean pass rate, with the same tooltip, and nothing for a dataset-method group',
      { tag: ['@cap:prompts.experiments-tab-group-pass-rate'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;

        const prompt = new PromptDetailPage(page);
        await test.step("Open the prompt's Experiments tab on its default grouped view", async () => {
          await prompt.gotoExperimentsTabGrouped(seed.projectId, seed.promptId);
          // Every seeded experiment is linked to this prompt's one version, so
          // the tab must show the same four groups the project page does. A
          // smaller number would mean the prompt link did not land and the
          // assertions below would be about a different population.
          await prompt.experimentGroups.waitForGroupRows(seed.groups.length);
        });

        await test.step('The mixed group reads 67% — the mean, not the pooled 50%', async () => {
          expect(seed.mixed.expectedGroupLabel, 'the mean the seed expects').toBe('67%');
          await prompt.experimentGroups.expectGroupPassRate(seed.mixed.datasetName, {
            label: seed.mixed.expectedGroupLabel,
            dot: 'failed',
          });
        });

        await test.step('With the same tooltip wording and the same pooled counts', async () => {
          await prompt.experimentGroups.expectGroupPassRateTooltip(seed.mixed.datasetName, {
            passed: requireCount(seed.mixed.expectedPassedSum, seed.mixed.datasetName),
            total: requireCount(seed.mixed.expectedTotalSum, seed.mixed.datasetName),
          });
        });

        await test.step('And the dataset-method-only group leaves its cell empty', async () => {
          await prompt.experimentGroups.expectGroupPassRateEmpty(seed.datasetOnly.datasetName);
          // Beside a group that does render one, on the same view: an empty
          // column everywhere is the pre-9646 defect and would satisfy the
          // assertion above by itself.
          await prompt.experimentGroups.expectGroupPassRate(seed.green.datasetName, {
            label: '100%',
            dot: 'passed',
          });
        });
      },
    );

    test(
      'a group one item short of perfect reads 99% on the group row and the experiment row',
      { tag: ['@cap:prompts.experiments-tab-group-pass-rate'] },
      async ({ page, groupedPassRateExperiments }) => {
        test.setTimeout(180_000);
        const seed = groupedPassRateExperiments;
        const capped = seed.cap.experiments[0];

        const prompt = new PromptDetailPage(page);
        await prompt.gotoExperimentsTabGrouped(seed.projectId, seed.promptId);
        await prompt.experimentGroups.waitForGroupRows(seed.groups.length);

        await test.step('The group row reads 99%, which plain rounding would have read as 100%', async () => {
          expect(
            Math.round(requireRate(seed.cap.expectedPassRateAvg) * 100),
            'plain rounding would read this group as 100%',
          ).toBe(100);
          await prompt.experimentGroups.expectGroupPassRate(seed.cap.datasetName, {
            label: '99%',
            dot: 'failed',
          });
        });

        await test.step("And so does the experiment's own row inside the group", async () => {
          await prompt.experimentGroups.expandGroup(seed.cap.datasetName, capped.id);
          await prompt.experimentGroups.expectExperimentPassRate(capped.id, '99%');
        });
      },
    );
  },
);

/**
 * A pooled count the seed must have — asserted rather than defaulted, so a
 * fixture that never computed its counts fails here instead of producing a
 * tooltip assertion about "0 / 0".
 */
function requireCount(value: number | null, group: string): number {
  expect(value, `group '${group}' has a pooled count to assert`).not.toBeNull();
  return value as number;
}

/** The same, for a group's mean pass rate. */
function requireRate(value: number | null): number {
  expect(value, 'the group has a mean pass rate to assert').not.toBeNull();
  return value as number;
}
