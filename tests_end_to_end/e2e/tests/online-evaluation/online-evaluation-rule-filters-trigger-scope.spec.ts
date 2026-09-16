import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/**
 * The Filtering & Sampling section of the add/edit rule dialog, against the
 * Trigger scope toggle beside it.
 *
 * Neither a filter nor the sampling rate reaches an experiment trace —
 * `OnlineScoringSampler.shouldScoreTrace` consults them only on the production
 * branch — so `RuleFilteringSection` returns null at trigger scope
 * `experiment`, and the explainer at the other scopes now says so.
 *
 * Two halves, and the second is the one that matters:
 *   - CREATE: the section is present at `Production traces` and at `Both`, and
 *     gone at `Experiment traces`.
 *   - EDIT: a rule that already carries a filter and a 0% rate keeps BOTH when
 *     it is saved at a scope that does not display them. Those values come back
 *     into force the moment someone widens the scope again, so a save that
 *     quietly dropped them would be invisible in the dialog and visible only in
 *     what the rule scores months later.
 *
 * Driven through the UI because the behaviour IS the dialog: what the API
 * stores is asserted too, but only as the thing the form did or did not send.
 */
test.describe('Online Evaluation — rule filters vs trigger scope', { tag: ['@t2-cuj', '@area:online-evaluation'] }, () => {
  test('Filters and sampling are hidden at Experiment scope, and survive a save made there', { tag: ['@cap:online-evaluation.rule-filters'] }, async ({
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // No scoring and no LLM here — this is form state — but the dialog opens
    // over a rules list that has to load, and the edit path makes two round
    // trips through it.
    test.setTimeout(180_000);

    const ruleName = `${testNamespace}-filtered`;
    /** A trace name nothing in this project is ever seeded with. */
    const filterValue = `${testNamespace}-no-trace-has-this-name`;

    const ruleId = await test.step('Seed a production-scope rule with a filter and a 0% rate', async () => {
      // Seeded over REST rather than through the dialog: driving the
      // three-control filter row is a different test, and this one needs the
      // rule to EXIST with those values so the edit dialog has something real
      // to hydrate from.
      return backendClient.createAutomationRule({
        projectId: project.id,
        name: ruleName,
        samplingRate: 0,
        triggerScope: 'production',
        filters: [{ field: 'name', operator: '=', value: filterValue }],
        metric: buildConstantScoreMetric(ruleName),
        arguments: { output: 'output.output' },
      });
    });

    const onlineEval = new OnlineEvaluationPage(page);

    await test.step('Open the project\'s Online evaluation page', async () => {
      await onlineEval.goto(project.id);
      await onlineEval.waitForReady();
      await expect(onlineEval.ruleRow(ruleName)).toBeVisible();
    });

    await test.step('In a new rule, Filtering & Sampling is shown at the default Production scope', async () => {
      await onlineEval.openCreateRuleDialog();
      await expect(
        onlineEval.triggerScopeOption('Production traces'),
        'a new rule defaults to production scope',
      ).toHaveAttribute('aria-checked', 'true');
      await expect(onlineEval.filteringSamplingTrigger).toBeVisible();

      await onlineEval.expandFilteringAndSampling();
      await expect(
        onlineEval.filteringSamplingDescription,
        'the explainer must say the two controls are production-only — that sentence is ' +
          'the only thing telling a user why the section disappears at experiment scope',
      ).toContainText(
        'Both apply to production traces only — traces from experiments, the playground ' +
          'and optimization runs ignore them.',
      );
    });

    await test.step('Switching that new rule to Experiment scope removes the section', async () => {
      await onlineEval.setTriggerScope('Experiment traces');
      await expect(
        onlineEval.filteringSamplingTrigger,
        'neither filters nor the sampling rate reach an experiment trace, so the section ' +
          'must not be offered at that scope',
      ).toHaveCount(0);
    });

    await test.step('Switching to Both brings it back', async () => {
      // The complement: `Both` still covers production traffic, so the controls
      // are live again. Without this the test would pass equally well if the
      // section had been removed outright.
      await onlineEval.setTriggerScope('Both');
      await expect(onlineEval.filteringSamplingTrigger).toBeVisible();
      await onlineEval.cancelRuleDialog();
    });

    await test.step('Save the seeded rule at Experiment scope, without touching its filter or rate', async () => {
      await onlineEval.openEditRuleDialog(ruleName);
      await expect(
        onlineEval.triggerScopeOption('Production traces'),
        'the edit dialog must hydrate the scope from the persisted value',
      ).toHaveAttribute('aria-checked', 'true');
      await expect(
        onlineEval.filteringSamplingTrigger,
        'the section is shown while the rule is still production-scoped',
      ).toBeVisible();

      await onlineEval.setTriggerScope('Experiment traces');
      await expect(onlineEval.filteringSamplingTrigger).toHaveCount(0);
      await onlineEval.submitRuleDialog();
    });

    await test.step('The save moved the scope and kept the rate and the filter', async () => {
      // The whole point of the edit half. A form that serialized only what it
      // was displaying would come back with rate 1.0 and no filters, and
      // nothing in the dialog would ever show the difference.
      const rule = await backendClient.getAutomationRule(ruleId);
      expect(rule.triggerScope, 'the scope the dialog was submitted at').toBe('experiment');
      expect(
        rule.samplingRate,
        'a rate the dialog no longer displays must not be reset to the 100% default',
      ).toBe(0);
      expect(
        rule.filters.map((f) => `${f.field}${f.operator}${f.value}`),
        'a filter the dialog no longer displays must survive the save verbatim',
      ).toEqual([`name=${filterValue}`]);
    });

    await test.step('Widening the scope back to Production shows the preserved values, live', async () => {
      // Read from the re-opened dialog rather than from the API again: the
      // values being in the database is not the same claim as the user getting
      // them back, and this is the moment they come back into force.
      await onlineEval.openEditRuleDialog(ruleName);
      await expect(
        onlineEval.triggerScopeOption('Experiment traces'),
        'the edit dialog must hydrate the scope saved above',
      ).toHaveAttribute('aria-checked', 'true');
      await expect(
        onlineEval.filteringSamplingTrigger,
        'still hidden while the rule is experiment-scoped',
      ).toHaveCount(0);

      await onlineEval.setTriggerScope('Production traces');
      await onlineEval.expandFilteringAndSampling();

      await expect(
        onlineEval.samplingRateInput,
        'the sampling rate is displayed as a percentage; 0 is the value seeded',
      ).toHaveValue('0');
      await expect(onlineEval.filterColumnCell, 'the filter field, as the dialog labels it').toHaveText(
        'Name',
      );
      await expect(onlineEval.filterOperatorCell).toHaveText('=');
      await expect(onlineEval.filterValueInput).toHaveValue(filterValue);

      await onlineEval.cancelRuleDialog();
    });
  });
});
