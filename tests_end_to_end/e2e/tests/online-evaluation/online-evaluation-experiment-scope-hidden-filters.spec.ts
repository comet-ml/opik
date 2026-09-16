import { test, expect } from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';

/**
 * `RuleFilteringSection` early-returns `null` for `trigger_scope=experiment` —
 * neither filters nor the sampling rate reach an experiment trace, so the
 * dialog has nothing to configure there and renders no control for either.
 *
 * That is a deliberate product decision, and it creates the exact shape a form
 * silently loses data in: two persisted fields the user cannot see while
 * editing. A serializer that rebuilds the payload from the controls it rendered
 * drops them; one that re-initialises from the form's defaults resets them to
 * 1.0 and `[]`. Both look identical on screen — the dialog closes, the row is
 * unchanged, and the rule has quietly stopped being filtered.
 *
 * The assertion is therefore on the outbound PATCH body as well as the
 * persisted read-back, and the wire half is the one that matters: "dropped,
 * then re-defaulted by the backend" and "preserved end to end" are
 * indistinguishable after the fact, and only the request tells them apart.
 *
 * Deterministic and LLM-free by construction — the rule never executes. It is
 * seeded over REST for a reason beyond isolating the edit path: with the
 * filtering section unrendered at this scope, the create dialog has no control
 * that could set a filter or a non-default rate, so there is no UI path to the
 * starting state at all.
 *
 * `sampling_rate` is asserted against a value the server would never choose:
 * 1.0 is the create-time default, so a rule that lost its rate and got the
 * default back would compare equal to one that kept it.
 */

/** Neither the form default (1.0) nor 0 — a value only this test could have set. */
const SEEDED_SAMPLING_RATE = 0.42;

/**
 * A single filter, in the shape the REST layer stores. Kept to one row because
 * what is under test is whether the list survives at all, not how many rows it
 * can hold.
 *
 * The server does not echo this object back verbatim: it strips the frontend's
 * transient `id` and adds `key: ""` where the field was absent (inert for a
 * `string` field). So the read-back is compared on the triple that identifies
 * the filter — field, operator, value — rather than by deep equality, which
 * would fail on that normalisation and say nothing about data loss.
 */
const SEEDED_FILTER: BackendFilter = {
  field: 'name',
  type: 'string',
  operator: 'contains',
  value: 'keepme',
};

/** The identifying triple of a filter, for comparison across the normalisation. */
function filterIdentity(filter: {
  field?: unknown;
  operator?: unknown;
  value?: unknown;
}): { field: unknown; operator: unknown; value: unknown } {
  return { field: filter.field, operator: filter.operator, value: filter.value };
}

/** Matches the rule PATCH on both the `/opik/api` and bare `/api` mounts. */
function isRuleUpdate(url: string, ruleId: string): boolean {
  return new URL(url).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`);
}

test.describe(
  'Online Evaluation — experiment-scope rule filters',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'Saving an experiment-scoped rule unchanged preserves the filters and rate the dialog hides',
      { tag: ['@cap:online-evaluation.rule-filters'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-exprule`;

        const ruleId = await test.step('Seed an experiment-scoped rule with filters and a non-default rate', async () =>
          backendClient.createAutomationRule({
            projectId: project.id,
            name: ruleName,
            samplingRate: SEEDED_SAMPLING_RATE,
            triggerScope: 'experiment',
            filters: [SEEDED_FILTER],
            metric: buildConstantScoreMetric(ruleName),
            arguments: { output: 'output.output' },
          }));

        await test.step('The seed really persisted all three fields', async () => {
          // Asserted before the browser opens. A round-trip over a rule that
          // never held a filter, or that fell back to the default rate, would
          // pass without proving anything — and would read as coverage forever.
          const seeded = await backendClient.getAutomationRule(ruleId);
          expect(seeded.triggerScope, 'the rule targets experiment traces').toBe('experiment');
          expect(seeded.samplingRate, 'the rule holds the non-default rate').toBe(
            SEEDED_SAMPLING_RATE,
          );
          expect(seeded.filters, 'the rule came back with a filter list').not.toBeNull();
          expect(seeded.filters, 'exactly the one seeded filter').toHaveLength(1);
          expect(filterIdentity(seeded.filters![0])).toEqual(filterIdentity(SEEDED_FILTER));
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open the rule in the edit dialog', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditDialogByName(ruleName);
        });

        await test.step('The dialog renders no filtering or sampling control at this scope', async () => {
          // The whole accordion is unmounted, not merely collapsed, so the
          // trigger itself is absent — and with it the sampling-rate input,
          // which only exists inside it.
          await expect(
            onlineEval.filteringSamplingTrigger,
            'Filtering & Sampling is not rendered for an experiment-scoped rule',
          ).toHaveCount(0);
          await expect(
            onlineEval.samplingRateInput,
            'and neither is the sampling-rate control it contains',
          ).toHaveCount(0);

          // The control that IS rendered, hydrated from the persisted scope.
          // Without this the two absences above would also be satisfied by a
          // dialog that failed to render its body at all, which would make the
          // save below a save of nothing.
          await expect(
            onlineEval.triggerScopeControl,
            'the trace-scope dialog still renders its Trigger scope control',
          ).toBeVisible();
          await expect(onlineEval.triggerScopeOption('Experiment traces')).toHaveAttribute(
            'data-state',
            'on',
          );
        });

        const update = await test.step('Press "Update rule" without editing anything', async () => {
          // Armed before the click: the request is in flight the moment the
          // dialog submits, so subscribing afterwards would race it.
          const patched = page.waitForRequest(
            (request) => request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId),
          );
          // The response too, not just the dispatch. AddEditRuleDialog calls
          // setOpen(false) outside the mutation's callbacks, so the dialog hides
          // the moment the PATCH is sent — waiting for it to close is no
          // barrier, and the read-back below would otherwise be free to observe
          // the pre-save row and pass on a backend that cleared the filters.
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
          );
          await onlineEval.submitDialog();
          const request = await patched;
          const response = await answered;
          expect(
            response.ok(),
            `the unedited save was accepted (got ${response.status()})`,
          ).toBe(true);
          return request;
        });

        await test.step('The outbound PATCH still carries the rate and the filters', async () => {
          // This is where a strip happens — the backend only ever sees the
          // result. Reading the body here separates "the frontend dropped it"
          // from "the backend dropped it", which the read-back cannot.
          const body = (update.postDataJSON() ?? {}) as Record<string, unknown>;

          // Key presence first, and asserted rather than inferred: an omitted
          // `sampling_rate` and an omitted `filters` are the two failures under
          // test, and `body.filters?.length` would read both as "no filters"
          // instead of failing.
          expect(
            Object.keys(body),
            'the update payload names sampling_rate',
          ).toContain('sampling_rate');
          expect(Object.keys(body), 'the update payload names filters').toContain('filters');

          expect(body.sampling_rate, 'an unedited save sends the rate back unchanged').toBe(
            SEEDED_SAMPLING_RATE,
          );
          expect(body.trigger_scope, 'and does not change the scope').toBe('experiment');

          const sentFilters = body.filters;
          expect(Array.isArray(sentFilters), 'filters is sent as a list').toBe(true);
          expect(sentFilters as unknown[], 'the whole list goes back, not a subset').toHaveLength(
            1,
          );
          expect(
            filterIdentity((sentFilters as Array<Record<string, unknown>>)[0]),
            'the filter is sent as it was hydrated',
          ).toEqual(filterIdentity(SEEDED_FILTER));
        });

        await test.step('And the rule persists them unchanged', async () => {
          const saved = await backendClient.getAutomationRule(ruleId);
          expect(saved.samplingRate, 'the persisted rate survives the round-trip').toBe(
            SEEDED_SAMPLING_RATE,
          );
          expect(saved.triggerScope, 'the persisted scope survives the round-trip').toBe(
            'experiment',
          );
          expect(saved.enabled, 'the unedited save did not disable the rule').toBe(true);
          expect(saved.filters, 'the rule still has a filter list').not.toBeNull();
          expect(saved.filters, 'and still exactly one filter').toHaveLength(1);
          expect(
            filterIdentity(saved.filters![0]),
            'the filter survives the round-trip through the edit dialog',
          ).toEqual(filterIdentity(SEEDED_FILTER));
        });

        await test.step('The rule is still listed, with the state it started in', async () => {
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await expect(onlineEval.ruleStatusCell(ruleName, 'Enabled')).toBeVisible();
        });
      },
    );
  },
);
