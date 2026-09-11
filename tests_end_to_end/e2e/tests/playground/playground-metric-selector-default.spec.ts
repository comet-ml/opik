import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/** Rules seeded into the project, so the selector has a known, closed list. */
const RULE_COUNT = 3;

/**
 * What the Playground's metric selector starts at when a dataset is loaded.
 *
 * It used to open with everything selected: `selectedRuleIds === null` was read
 * as "all", and an effect in `RunExperimentControl` resolved that null to every
 * rule id before the run. It now opens with NOTHING selected — null and [] both
 * mean "none", and the run is scored only by the rules that target experiments
 * plus whatever the user picks here.
 *
 * That is a change in what an unchanged user workflow produces, so it is worth a
 * permanent test. It is also pure render state: no model, no provider key, no
 * run. The assertions are the two things a user reads — the count in the summary
 * row and the label on the trigger — plus the checkbox state behind them, so a
 * regression in either the tri-state collapse in `metricSelection.ts` or the
 * `isSelected` / `isAllSelected` derivation in `MetricSelector.tsx` fails here
 * rather than silently scoring a dataset run with rules nobody chose.
 */
test.describe('Playground — metric selector default', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test('Loading a dataset opens the metric selector with nothing selected', { tag: ['@cap:playground.run-against-dataset'] }, async ({
    project,
    dataset,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // No LLM call anywhere in this test; the budget covers the Playground's
    // cold load and the dataset/rules queries behind the picker.
    test.setTimeout(180_000);

    const ruleNames = Array.from(
      { length: RULE_COUNT },
      (_, i) => `${testNamespace}-metric-${i + 1}`,
    );

    await test.step(`Seed ${RULE_COUNT} online-evaluation rules into the project`, async () => {
      // The selector lists every rule in the project (`useRulesList` filtered by
      // projectId), so seeding into the fixture project is what makes the
      // "0 of N" denominator exact rather than whatever the workspace happens to
      // hold. Rules are created over REST — creating them through the dialog is
      // a different test, and this one only needs them to exist.
      //
      // Serial, not Promise.all: the names are asserted verbatim below and the
      // list is rendered newest-first, so seeding them in a defined order keeps
      // a failure message legible.
      for (const name of ruleNames) {
        await backendClient.createAutomationRule({
          projectId: project.id,
          name,
          samplingRate: 1,
          metric: buildConstantScoreMetric(name),
          arguments: { output: 'output.output' },
        });
      }

      const seeded = await backendClient.listAutomationRulesForProject(project.id);
      expect(
        seeded.map((r) => r.name).sort(),
        'the selector reads this list, so the test is only meaningful if it is exactly ' +
          'the rules seeded here',
      ).toEqual([...ruleNames].sort());
    });

    const playground = new PlaygroundPage(page, project.id);

    await test.step('Open the Playground', async () => {
      await playground.goto();
      // A keyless install opens the provider-setup dialog over the page; it has
      // nothing to do with this flow and `waitForReady` cannot pass while it
      // holds the aria tree.
      await playground.dismissProviderSetupDialog();
      await playground.waitForReady();
    });

    await test.step('Load the dataset as the run-experiment source', async () => {
      await playground.clickRunExperiment();
      await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
      await expect(playground.loadedSourcePill()).toContainText(dataset.name);
    });

    await test.step('The selector auto-opened with nothing selected', async () => {
      // Do NOT click the trigger to open it: `handleDatasetChange` already calls
      // setMetricsOpen(true), so a click would close the popover and the test
      // would then assert against a control that is not on screen.
      const popover = playground.metricSelectorPopover();
      await expect(popover, 'loading a dataset opens the metric selector').toBeVisible();

      await expect(
        playground.metricSelectionSummary(),
        'the summary row is what a user reads as "how many am I about to score with"',
      ).toHaveText(`0 of ${RULE_COUNT} selected`);

      await expect(
        playground.metricSelectorTrigger(),
        'the collapsed trigger must agree with the summary',
      ).toHaveText('Select metrics');

      // Every row unchecked, addressed by name. The summary alone would still
      // read "0 of N" if the count had decoupled from the checkbox state, which
      // is exactly the derivation that changed.
      for (const name of ruleNames) {
        await expect(
          playground.metricOption(name),
          `exactly one metric row named "${name}"`,
        ).toHaveCount(1);
        await expect(
          playground.metricOptionCheckbox(name),
          `"${name}" must start unselected — a dataset run no longer defaults to every rule`,
        ).not.toBeChecked();
      }

      // The select-all row carries a checkbox too, so the popover holds one per
      // rule plus that one. Pinning the total is what makes the loop above an
      // exhaustive statement about the list rather than a spot check.
      await expect(
        popover.getByRole('checkbox'),
        'one checkbox per rule, plus the select-all row',
      ).toHaveCount(RULE_COUNT + 1);
    });

    await test.step('Picking one metric selects that one and only that one', async () => {
      const picked = ruleNames[0];
      await playground.toggleMetric(picked);

      await expect(playground.metricSelectionSummary()).toHaveText(`1 of ${RULE_COUNT} selected`);
      // The trigger swaps "Select metrics" for "Metrics" plus a count badge; the
      // badge renders adjacent to the label with no separator, hence the run-on.
      await expect(playground.metricSelectorTrigger()).toHaveText('Metrics1');

      await expect(playground.metricOptionCheckbox(picked)).toBeChecked();
      for (const name of ruleNames.slice(1)) {
        await expect(
          playground.metricOptionCheckbox(name),
          `picking "${picked}" must not select "${name}" as well`,
        ).not.toBeChecked();
      }
    });
  });
});
