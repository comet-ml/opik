import { test, expect } from '@e2e/fixtures';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import { PlaygroundPage } from '@e2e/pom/playground.page';

/**
 * Which metrics a playground dataset run will be judged by is decided in the
 * Metrics picker, and it is decided from two sources that the user can only
 * tell apart here: the rules they tick, and every enabled rule scoped to
 * experiment traces, which runs whether or not it was ticked.
 *
 * That second set is why the picker's DEFAULT is the interesting assertion. A
 * dataset run is logged as an experiment trace, so an experiment-scoped rule
 * always scores it; the picker reflects that by showing such a rule checked and
 * locked, while leaving it out of the user's own selection. Three rules
 * discriminate all three paths at once:
 *
 *   picked      production scope, ticked here      -> user's selection
 *   notpicked   production scope, left alone       -> neither selected nor forced
 *   always      experiment scope, checked + locked -> forced, never selected
 *
 * `notpicked` is what makes this a test rather than a demo. A regression that
 * pre-ticked every rule in the project would satisfy every other assertion
 * here, and would silently send a run to be scored by rules nobody chose.
 *
 * No model and no provider key: selecting a dataset paints the picker from the
 * project's rules query alone, which is the whole surface under test — the same
 * reason playground-virtualized-grid.spec.ts needs neither. The rules are
 * constant-1.0 python metrics that never execute here; they exist for their
 * scope and their name.
 *
 * SCOPE, stated because the gap is easy to miss: this pins what the picker
 * OFFERS and RECORDS, not what the backend then scores. Asserting that a run is
 * scored by exactly these rules needs a real LLM call, so it belongs in a spec
 * that can make one — see the note on this file in the PR that introduced it.
 *
 * One product behaviour shapes the page object this drives and is worth knowing
 * before reading it: on 2.2.66 the Metrics popover closes on its own about a
 * second after opening, unprompted. `PlaygroundPage.ensureMetricsPickerOpen`
 * documents the measurement. It does not affect what is asserted here — the
 * picker reopens with its state intact — but it is why every read below goes
 * through one atomic snapshot instead of a series of locator assertions.
 */

/** Two items is enough for the picker to mount against a real dataset run. */
const DATASET_ITEMS = [
  { question: 'First question', expected_output: 'OK' },
  { question: 'Second question', expected_output: 'OK' },
];

test.describe(
  'Playground — metric selection',
  { tag: ['@t2-cuj', '@area:playground'] },
  () => {
    test(
      'The Metrics picker pre-selects only experiment-scoped rules, and locks them',
      { tag: ['@cap:playground.run-against-dataset'] },
      async ({
        project,
        sdkClient,
        backendClient,
        testNamespace,
        registerDatasetCleanup,
        automationRulesCleanup,
        page,
      }) => {
        test.setTimeout(180_000);

        const pickedRule = `${testNamespace}-pg-picked`;
        const notPickedRule = `${testNamespace}-pg-notpicked`;
        const alwaysRule = `${testNamespace}-pg-always`;

        await test.step('Seed three rules that discriminate the three paths', async () => {
          const seed = (name: string, triggerScope: 'production' | 'experiment') =>
            backendClient.createAutomationRule({
              projectId: project.id,
              name,
              samplingRate: 1,
              triggerScope,
              metric: buildConstantScoreMetric(name),
              arguments: { output: 'output.output' },
            });
          // Sequential rather than concurrent so a failure names the rule that
          // could not be created.
          await seed(pickedRule, 'production');
          await seed(notPickedRule, 'production');
          await seed(alwaysRule, 'experiment');
        });

        await test.step('The rules really hold the scopes this test depends on', async () => {
          // Asserted over the API before the browser opens. Everything below
          // reads as coverage only if these scopes are what the backend stored:
          // a rule that silently fell back to the server default (`production`)
          // would turn the always-run arm into a second unpicked rule, and the
          // picker would open at "0 of 3 selected" for a reason that has nothing
          // to do with the behaviour under test.
          const rules = await backendClient.listAutomationRulesForProject(project.id);
          expect(rules, 'exactly the three seeded rules, and nothing else').toHaveLength(3);

          const byName = new Map(rules.map((rule) => [rule.name, rule]));
          const scopes = await Promise.all(
            [pickedRule, notPickedRule, alwaysRule].map(async (name) => {
              const rule = byName.get(name);
              expect(rule, `the project lists a rule named ${name}`).toBeDefined();
              return backendClient.getAutomationRule(rule!.id);
            }),
          );
          const [picked, notPicked, always] = scopes;

          expect(picked.triggerScope, 'the picked rule targets production traces').toBe(
            'production',
          );
          expect(notPicked.triggerScope, 'the unpicked rule targets production traces').toBe(
            'production',
          );
          expect(always.triggerScope, 'the always-run rule targets experiment traces').toBe(
            'experiment',
          );
          for (const rule of scopes) {
            expect(rule.enabled, `${rule.name} is enabled`).toBe(true);
          }
        });

        const dataset = await test.step('Seed a dataset scoped to the project', async () => {
          // `project_name` matters: the playground's source picker only lists
          // datasets scoped to the project it is open on, so an unscoped dataset
          // could never be selected here.
          const datasetName = `${testNamespace}-pgds`;
          const created = await sdkClient.python.createDataset({
            project_name: project.name,
            name: datasetName,
            description: 'dataset whose run the metric picker configures',
            items: DATASET_ITEMS as unknown as Array<Record<string, unknown>>,
          });
          registerDatasetCleanup(created.id, datasetName);
          return created;
        });

        const playground = new PlaygroundPage(page, project.id);

        await test.step('Load the dataset into the Playground', async () => {
          await playground.goto();
          await playground.waitForReady();
          await playground.clickRunExperiment();
          await playground.selectRunExperimentSource({
            mode: 'dataset',
            entityName: dataset.name,
          });
          await expect(playground.loadedSourcePill()).toBeVisible();
          // Idle output rows paint from the items query — no run, no LLM call.
          await playground.waitForRunReady({ expectedRows: DATASET_ITEMS.length });
        });

        await test.step('The picker opens pre-selecting only the experiment-scoped rule, and locks it', async () => {
          const picker = await playground.readMetricsPicker();

          // The whole default, as one number. "1 of 3" is the claim: the picker
          // neither starts empty (the always-run rule was not recognised) nor
          // pre-ticks everything (the user's choice would be cosmetic).
          expect(
            picker.summary,
            'only the experiment-scoped rule is selected by default',
          ).toBe('1 of 3 selected');

          // …and WHICH one it is, asserted over the whole list rather than by
          // looking up the rules this test expects. A picker that also offered a
          // fourth rule, or that pre-ticked the wrong one, fails here; finding
          // each rule by name in turn would not notice either.
          expect(
            [...picker.rules].sort((a, b) => a.name.localeCompare(b.name)),
            'three rules, with only the experiment-scoped one checked — and only it locked',
          ).toEqual(
            [
              { name: alwaysRule, checked: true, locked: true },
              { name: notPickedRule, checked: false, locked: false },
              { name: pickedRule, checked: false, locked: false },
            ].sort((a, b) => a.name.localeCompare(b.name)),
          );
        });

        await test.step('Ticking one production-scope rule adds to the forced one', async () => {
          await playground.setMetricPicked(pickedRule, true);

          const picker = await playground.readMetricsPicker();
          expect(
            picker.summary,
            'the pick lands on top of the always-run rule, it does not replace it',
          ).toBe('2 of 3 selected');
          expect(
            picker.rules.find((rule) => rule.name === notPickedRule)?.checked,
            'the rule left alone stays unticked',
          ).toBe(false);
          expect(
            picker.rules.find((rule) => rule.name === alwaysRule)?.checked,
            'and the forced rule is still checked',
          ).toBe(true);
        });

        await test.step('Unticking it returns to the forced rule alone', async () => {
          await playground.setMetricPicked(pickedRule, false);

          // The floor is the assertion: the count comes back to 1, not 0. That
          // is what says the always-run rule was never part of the user's own
          // selection — only displayed alongside it — which is the difference
          // between a rule the user chose and one they cannot decline.
          const picker = await playground.readMetricsPicker();
          expect(
            picker.summary,
            'the user can give back their own pick, but not the forced one',
          ).toBe('1 of 3 selected');
          expect(picker.rules.find((rule) => rule.name === pickedRule)?.checked).toBe(false);
          expect(picker.rules.find((rule) => rule.name === alwaysRule)?.checked).toBe(true);
        });
      },
    );
  },
);
