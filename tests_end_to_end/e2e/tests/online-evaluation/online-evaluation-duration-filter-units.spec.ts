import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import type { BackendFilter } from '@e2e/core/backend';

/** The label the dialog gives the duration column — and the unit it promises. */
const DURATION_COLUMN = 'Duration (s)';

/**
 * A whole-second threshold and its millisecond storage, and a SUB-SECOND one.
 *
 * The sub-second case is the one that separates a correct two-way conversion
 * from a one-sided one: 5 → 5000 → 5 and 5 → 5000 → 5000 both look plausible
 * on a single save, whereas 0.2 → 200 has to divide on the way back in or the
 * field shows 200 and the next save stores 200000.
 */
const WHOLE = { seconds: '5', millis: '5000' };
const SUB_SECOND = { seconds: '0.2', millis: '200' };

/** A second filter that must come through untouched — the conversion's blast radius. */
const NAME_FILTER_VALUE = 'keepme';

/**
 * Seconds ⇄ milliseconds for an online-evaluation rule's duration filter
 * (OPIK-8000).
 *
 * The dialog labels the column "Duration (s)" while the backend stores and
 * matches on milliseconds, so every save has to multiply and every load has to
 * divide. Before the fix it did neither: "> 5" was stored as 5, i.e. 5ms, and
 * the rule matched essentially every trace.
 *
 * `online-evaluation-duration-filter-selects.spec.ts` is the companion to this
 * one and asserts the consequence — which traces a filtered rule actually
 * scores. This spec asserts the conversion itself, at the two points a unit bug
 * can hide:
 *
 *   - **The outbound body**, not only the read-back. A value normalised on
 *     hydration but not denormalised on save (or the reverse) round-trips
 *     perfectly through the API and still compounds on the next save. Only the
 *     request tells "converted correctly" apart from "converted twice" or "not
 *     at all".
 *   - **A repeated, UNEDITED save.** One cycle cannot distinguish a two-way
 *     conversion from no conversion at all, because both leave the stored value
 *     where they found it. Two cycles with no edit in between is what makes
 *     drift observable: a one-sided conversion multiplies again each time.
 *
 * The name filter alongside it is the blast-radius control. The conversion is
 * applied per filter by type, so a change that reached every row instead of the
 * duration one would corrupt unrelated filters — and nothing about the duration
 * assertions would notice.
 *
 * Deterministic and free: the rules are created but never run, so no trace is
 * scored, no metric executes and no provider key is needed.
 */
test.describe(
  'Online Evaluation — duration filter units',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'a threshold typed in seconds is sent in milliseconds and hydrates back as seconds',
      { tag: ['@cap:online-evaluation.edit-rule', '@cap:online-evaluation.rule-filters'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-dur-units`;
        const rules = new OnlineEvaluationPage(page);

        await test.step('Open the create-rule dialog on a fresh project', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRuleDialog();
          await rules.dialog.getByRole('textbox', { name: 'Rule name' }).fill(ruleName);
          await rules.dialog.getByRole('radio', { name: 'Code metric' }).click();
        });

        await test.step(`Add a "${DURATION_COLUMN} > ${WHOLE.seconds}" filter and a name filter`, async () => {
          await rules.addFilterRow();
          await rules.setFilterColumn(0, DURATION_COLUMN);
          await rules.setFilterOperator(0, '>');
          await rules.setFilterValue(0, WHOLE.seconds);

          await rules.addFilterRow();
          await rules.setFilterColumn(1, 'Name');
          await rules.setFilterOperator(1, 'contains');
          await rules.setFilterValue(1, NAME_FILTER_VALUE);
        });

        const created = await test.step('Submit, and capture what the dialog actually sent', async () => {
          const posted = page.waitForRequest(
            (request) => request.method() === 'POST' && isRuleCreate(request.url()),
          );
          await rules.submitRuleDialog();
          return filtersFromRequestBody((await posted).postData());
        });

        await test.step('The duration went out in milliseconds, and the name filter was untouched', () => {
          // The whole filter list, not just the duration row: a conversion
          // applied to every filter instead of the duration one would leave
          // these assertions passing while quietly mangling the name.
          expect(created, 'the filters the create request carried').toEqual([
            { field: 'duration', operator: '>', value: WHOLE.millis },
            { field: 'name', operator: 'contains', value: NAME_FILTER_VALUE },
          ]);
        });

        await test.step('And that is what was stored', async () => {
          expect(
            await storedFilters(backendClient, project.id, ruleName),
            'the persisted filters',
          ).toEqual([
            { field: 'duration', operator: '>', value: WHOLE.millis },
            { field: 'name', operator: 'contains', value: NAME_FILTER_VALUE },
          ]);
        });

        await test.step('Reopening the rule shows the threshold in seconds again', async () => {
          await rules.openEditRuleDialogByName(ruleName);
          await rules.expandFilteringAndSampling();
          expect(
            await rules.readFilterValue(0),
            `a rule stored at ${WHOLE.millis}ms must render as ${WHOLE.seconds} under a ` +
              'column labelled "(s)" — showing the raw milliseconds is the bug this fixed',
          ).toBe(WHOLE.seconds);
          expect(
            await rules.readFilterValue(1),
            'the name filter hydrates unchanged',
          ).toBe(NAME_FILTER_VALUE);
          await rules.cancelDialog();
        });
      },
    );

    test(
      'a sub-second threshold survives repeated unedited saves without drifting',
      { tag: ['@cap:online-evaluation.edit-rule', '@cap:online-evaluation.rule-filters'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-dur-subsec`;
        const rules = new OnlineEvaluationPage(page);

        await test.step(`Create a rule filtered at ${SUB_SECOND.seconds}s`, async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openCreateRuleDialog();
          await rules.dialog.getByRole('textbox', { name: 'Rule name' }).fill(ruleName);
          await rules.dialog.getByRole('radio', { name: 'Code metric' }).click();
          await rules.addFilterRow();
          await rules.setFilterColumn(0, DURATION_COLUMN);
          await rules.setFilterOperator(0, '>');
          await rules.setFilterValue(0, SUB_SECOND.seconds);

          const posted = page.waitForRequest(
            (request) => request.method() === 'POST' && isRuleCreate(request.url()),
          );
          await rules.submitRuleDialog();
          expect(
            filtersFromRequestBody((await posted).postData()),
            `${SUB_SECOND.seconds}s must be sent as ${SUB_SECOND.millis}ms`,
          ).toEqual([{ field: 'duration', operator: '>', value: SUB_SECOND.millis }]);
        });

        // Twice, with no edit in between. One cycle proves nothing: a dialog
        // that converts in neither direction also re-sends exactly what it
        // loaded. Two cycles is the smallest number that exposes a one-sided
        // conversion, because the second save compounds the first.
        for (const cycle of [1, 2]) {
          await test.step(`Open-and-save cycle ${cycle} changes nothing`, async () => {
            await rules.openEditRuleDialogByName(ruleName);
            await rules.expandFilteringAndSampling();
            expect(
              await rules.readFilterValue(0),
              `cycle ${cycle}: the field must hydrate as ${SUB_SECOND.seconds}s`,
            ).toBe(SUB_SECOND.seconds);

            const patched = page.waitForRequest(
              (request) => request.method() === 'PATCH' && isRuleUpdate(request.url()),
            );
            await rules.submitRuleDialog();
            expect(
              filtersFromRequestBody((await patched).postData()),
              `cycle ${cycle}: an unedited save must re-send ${SUB_SECOND.millis}ms — a value ` +
                'converted on load but not on save comes back multiplied by 1000 each time',
            ).toEqual([{ field: 'duration', operator: '>', value: SUB_SECOND.millis }]);

            expect(
              await storedFilters(backendClient, project.id, ruleName),
              `cycle ${cycle}: the stored threshold is unchanged`,
            ).toEqual([{ field: 'duration', operator: '>', value: SUB_SECOND.millis }]);
          });
        }
      },
    );

    test(
      'a thread-scope rule re-sends its time and duration filters unchanged',
      { tag: ['@cap:online-evaluation.edit-rule', '@cap:online-evaluation.rule-filters'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-dur-thread`;
        const rules = new OnlineEvaluationPage(page);

        /**
         * A timestamp with non-zero milliseconds, deliberately.
         *
         * The duration conversion shares its helper with the time one
         * (`processFiltersArray` dispatches both by column type), so a change
         * to either is a chance to round or split the other. A whole-second
         * instant would survive a truncating round trip and prove nothing;
         * `.456` does not.
         */
        const CREATED_AT = '2026-09-01T13:47:23.456Z';
        const THREAD_DURATION_MS = '2500';

        const seeded: BackendFilter[] = [
          { field: 'created_at', type: 'time', operator: '>=', value: CREATED_AT },
          { field: 'duration', type: 'duration', operator: '<=', value: THREAD_DURATION_MS },
        ];

        await test.step('Seed a thread-scope rule carrying both filters', async () => {
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: ruleName,
            type: 'trace_thread_user_defined_metric_python',
            samplingRate: 1,
            filters: seeded,
            metric: buildConstantScoreMetric(ruleName),
          });
          // Asserted before the dialog is opened: a round trip over a rule that
          // never held these filters would pass while proving nothing.
          expect(
            await storedFilters(backendClient, project.id, ruleName),
            'the seeded thread-scope filters',
          ).toEqual([
            { field: 'created_at', operator: '>=', value: CREATED_AT },
            { field: 'duration', operator: '<=', value: THREAD_DURATION_MS },
          ]);
        });

        await test.step('The dialog shows the duration in seconds', async () => {
          await rules.goto(project.id);
          await rules.waitForReady();
          await rules.openEditRuleDialogByName(ruleName);
          await rules.expandFilteringAndSampling();
          expect(
            await rules.readFilterValue(1),
            `${THREAD_DURATION_MS}ms must render as 2.5 under a column labelled "(s)"`,
          ).toBe('2.5');
        });

        await test.step('An unedited save re-sends both filters exactly as stored', async () => {
          const patched = page.waitForRequest(
            (request) => request.method() === 'PATCH' && isRuleUpdate(request.url()),
          );
          await rules.submitRuleDialog();
          expect(
            filtersFromRequestBody((await patched).postData()),
            'an unedited save must re-send the timestamp to the millisecond, unsplit, and the ' +
              `duration as ${THREAD_DURATION_MS}ms`,
          ).toEqual([
            { field: 'created_at', operator: '>=', value: CREATED_AT },
            { field: 'duration', operator: '<=', value: THREAD_DURATION_MS },
          ]);
        });

        await test.step('And the stored rule is unchanged', async () => {
          expect(
            await storedFilters(backendClient, project.id, ruleName),
            'the persisted thread-scope filters after an unedited save',
          ).toEqual([
            { field: 'created_at', operator: '>=', value: CREATED_AT },
            { field: 'duration', operator: '<=', value: THREAD_DURATION_MS },
          ]);
        });
      },
    );
  },
);

/** Matches the rule CREATE on both the `/opik/api` and bare `/api` mounts. */
function isRuleCreate(url: string): boolean {
  return /\/v1\/private\/automations\/evaluators\/?$/.test(new URL(url).pathname);
}

/** Matches a rule UPDATE — the same collection path plus an id segment. */
function isRuleUpdate(url: string): boolean {
  return /\/v1\/private\/automations\/evaluators\/[0-9a-f-]+$/i.test(new URL(url).pathname);
}

/**
 * The identifying triple of each filter in a request body, in order.
 *
 * Field/operator/value rather than the whole object, for the reason
 * `online-evaluation-experiment-scope-hidden-filters.spec.ts` sets out at
 * length: the dialog attaches a transient `id` and re-derives `type` from the
 * column config, neither of which is persisted, so a deep comparison would fail
 * against a perfectly healthy server. The triple is what both ends agree on,
 * and the value — which is the whole subject here — is inside it.
 *
 * Throws rather than returning `[]` on a body it cannot read: an unparseable
 * request is a broken assumption, and an empty list would read as "the dialog
 * sent no filters", which is a real and different failure.
 */
function filtersFromRequestBody(
  postData: string | null,
): Array<{ field: unknown; operator: unknown; value: unknown }> {
  if (postData === null) {
    throw new Error('the captured rule request carried no body');
  }
  const body: unknown = JSON.parse(postData);
  const filters = (body as { filters?: unknown }).filters;
  if (!Array.isArray(filters)) {
    throw new Error(
      `the captured rule request carried no filters array: ${JSON.stringify(body).slice(0, 300)}`,
    );
  }
  return filters.map((f) => ({
    field: (f as Record<string, unknown>).field,
    operator: (f as Record<string, unknown>).operator,
    value: (f as Record<string, unknown>).value,
  }));
}

/** The same triple, read back off the persisted rule. */
async function storedFilters(
  backendClient: {
    listAutomationRulesForProject: (projectId: string) => Promise<Array<{ id: string; name: string }>>;
    getAutomationRule: (ruleId: string) => Promise<{ filters: Array<Record<string, unknown>> | null }>;
  },
  projectId: string,
  ruleName: string,
): Promise<Array<{ field: unknown; operator: unknown; value: unknown }>> {
  // Polled, not read once. The caller submits the dialog and waits for it to
  // close, but a closed dialog is a CLIENT event — the form dismisses on the
  // mutation resolving and the list is refetched behind it, so a read taken
  // straight after can legitimately land before the rule is listable and see
  // zero. That is a race in the reading, not a fact about the rule, and it
  // failed exactly that way once in three local runs.
  //
  // The poll settles on exactly one, so it is not weaker than the read it
  // replaces: a repeated submit that created a SECOND rule of the same name
  // still fails here rather than silently having its filters read off
  // whichever came first — the count has to be 1, and 2 never becomes 1.
  let match: Array<{ id: string; name: string }> = [];
  await expect
    .poll(
      async () => {
        const rules = await backendClient.listAutomationRulesForProject(projectId);
        match = rules.filter((r) => r.name === ruleName);
        return match.length;
      },
      {
        message: `rules named "${ruleName}" under the project`,
        timeout: 30_000,
      },
    )
    .toBe(1);
  const rule = await backendClient.getAutomationRule(match[0].id);
  expect(rule.filters, `rule "${ruleName}" came back with a filter list`).not.toBeNull();
  return (rule.filters ?? []).map((f) => ({
    field: f.field,
    operator: f.operator,
    value: f.value,
  }));
}
