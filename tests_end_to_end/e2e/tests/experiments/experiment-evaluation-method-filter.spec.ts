import { test, expect } from '@e2e/fixtures';
import type { BackendFilter } from '@e2e/core/backend';

/**
 * Filtering the experiments listing by `evaluation_method` (opik#9456,
 * OPIK-9127) — a new `FieldType.ENUM` entry on `ExperimentField`.
 *
 * Nothing in the product sends this filter yet: no frontend consumer shipped
 * this release, which is why release-coverage scored the PR "API yes, no UI to
 * cover" and left it off the manual list. That makes it the likeliest change
 * here to have nobody looking at it, and the two things a user can observe
 * about it are both answers rather than rows.
 *
 * What this pins, and why each half fails independently:
 *
 *  - **Which rows come back.** `= dataset` and `= evaluation_suite` must
 *    partition the dataset's experiments, and `!=` must be the exact
 *    complement of `=` in BOTH directions. A clause that matched a prefix, or
 *    one that folded an unrecognised stored value in with the default the way
 *    `Source.legacyFallbackDbValue` deliberately does for traces, returns a
 *    plausible set of experiments that is quietly wrong — and `evaluation_method`
 *    is NOT wired for that fallback (`FieldType.ENUM`, not `ENUM_LEGACY`), so
 *    a leak in either direction is a defect rather than a documented widening.
 *  - **What a malformed ask answers.** The repo's own filter checklist
 *    (`.claude/rules/apps/opik-backend/adding_filter_field_types.md`) names two
 *    failure classes for a new field type, and both are HTTP statuses: a missing
 *    `FIELD_TYPE_VALIDATION_MAP` entry throws an NPE inside
 *    `validateFieldType()` (a 500), and a missing operator entry makes
 *    `toAnalyticsDbOperator()` return null (a 400). An unrecognised enum VALUE
 *    is a third case and must be neither — the user asked a well-formed
 *    question with no answer, which is an empty 200.
 *
 * API-level with no browser, and that is not a shortcut: there is no UI to
 * drive. `@cap:experiments.list-experiments` is claimed for the listing read
 * itself; no part of this asserts anything about the Experiments table, its
 * filter bar or its columns.
 *
 * Deterministic and free — three experiment rows, no traces, no items, no
 * model. See `evaluationMethodExperiments` for why they share one dataset.
 */

/** Build the filter the way the front end would once it has a consumer. */
const methodFilter = (operator: '=' | '!=', value: string): BackendFilter[] => [
  { field: 'evaluation_method', operator, value, type: 'enum' },
];

/** Big enough to take every experiment this fixture seeds on one page. */
const PAGE_SIZE = 100;

test.describe(
  'Experiments — evaluation_method filter (opik#9456)',
  { tag: ['@t2-cuj', '@area:experiments'] },
  () => {
    test(
      'the filter partitions the dataset exactly, in both directions',
      { tag: ['@cap:experiments.list-experiments'] },
      async ({ evaluationMethodExperiments, backendClient }) => {
        const seed = evaluationMethodExperiments;
        const defaultIds = seed.defaultMethod.map((e) => e.id).sort();
        const suiteIds = seed.suiteMethod.map((e) => e.id).sort();
        const allIds = seed.all.map((e) => e.id).sort();

        /** One filtered read, with the status asserted before the rows. */
        const read = async (
          what: string,
          filters: BackendFilter[] | undefined,
        ): Promise<{ total: number; ids: string[] }> => {
          const answer = await backendClient.listExperimentsFiltered({
            datasetId: seed.datasetId,
            filters,
            size: PAGE_SIZE,
          });
          expect(answer.status, `${what} answered ${answer.status}: ${answer.message}`).toBe(200);
          return { total: answer.total as number, ids: [...answer.ids].sort() };
        };

        await test.step('Unfiltered, the dataset holds exactly the three seeded experiments', async () => {
          // The baseline every comparison below is read against, and a real
          // assertion rather than a warm-up: a fourth experiment in this
          // dataset (a leftover, or a parallel worker) would make each filtered
          // answer below right about a population this test does not know.
          const answer = await read('the unfiltered listing', undefined);
          expect(answer.total, 'the envelope counts three experiments').toBe(allIds.length);
          expect(answer.ids, 'and returns exactly the seeded three').toEqual(allIds);
        });

        await test.step(`= ${seed.defaultMethodValue} returns exactly the two default experiments`, async () => {
          const answer = await read(`the = ${seed.defaultMethodValue} read`, methodFilter('=', seed.defaultMethodValue));
          // Both the population and the rows: `total` alone passes over a read
          // that returned one of the two, and the id set alone passes over an
          // envelope that claimed more than it returned.
          expect(answer.total, 'the envelope counts two').toBe(defaultIds.length);
          expect(answer.ids, 'and they are the two default-method experiments').toEqual(defaultIds);
        });

        await test.step(`= ${seed.suiteMethodValue} returns exactly the one suite experiment`, async () => {
          const answer = await read(`the = ${seed.suiteMethodValue} read`, methodFilter('=', seed.suiteMethodValue));
          expect(answer.total, 'the envelope counts one').toBe(suiteIds.length);
          expect(answer.ids, 'and it is the suite experiment').toEqual(suiteIds);
        });

        await test.step('!= is the exact complement of = in both directions', async () => {
          // Stated from both sides, because they are not the same query and can
          // fail apart: NOT_EQUAL has its own operator-map entry, and a clause
          // that dropped rows whose stored method it could not recognise would
          // satisfy one direction and leak in the other. The pair also catches
          // an operator that was silently ignored — a `!=` served as `=` agrees
          // with the step above and contradicts this one.
          const notDefault = await read(
            `the != ${seed.defaultMethodValue} read`,
            methodFilter('!=', seed.defaultMethodValue),
          );
          expect(notDefault.total, 'everything that is not the default method').toBe(suiteIds.length);
          expect(notDefault.ids, 'is exactly the suite experiment').toEqual(suiteIds);

          const notSuite = await read(
            `the != ${seed.suiteMethodValue} read`,
            methodFilter('!=', seed.suiteMethodValue),
          );
          expect(notSuite.total, 'everything that is not the suite method').toBe(defaultIds.length);
          expect(notSuite.ids, 'is exactly the two default-method experiments').toEqual(defaultIds);
        });
      },
    );

    test(
      'a value with no match answers an empty 200, and an unsupported operator a 400 naming the field',
      { tag: ['@cap:experiments.list-experiments'] },
      async ({ evaluationMethodExperiments, backendClient }) => {
        const seed = evaluationMethodExperiments;

        await test.step('An unrecognised enum value is an empty answer, not a 500', async () => {
          const answer = await backendClient.listExperimentsFiltered({
            datasetId: seed.datasetId,
            filters: methodFilter('=', 'no_such_evaluation_method'),
            size: PAGE_SIZE,
          });
          // The status FIRST and on its own line: a missing
          // `FIELD_TYPE_VALIDATION_MAP` entry for the new type throws an NPE
          // inside `validateFieldType()`, which arrives as a 500 — and a 500
          // and an empty result are the same thing to any assertion that only
          // looked at the rows.
          expect(
            answer.status,
            `an unknown enum value answered ${answer.status}: ${answer.message}`,
          ).toBe(200);
          expect(answer.total, 'and it matches nothing').toBe(0);
          expect(answer.ids, 'returning no rows at all').toEqual([]);
        });

        await test.step('An operator the type does not support is a 400 naming field and type', async () => {
          // `ANALYTICS_DB_OPERATOR_MAP` gives ENUM entries for =, !=, IN,
          // NOT_IN, IS_EMPTY and IS_NOT_EMPTY only. `contains` is the operator
          // the UI offers for every STRING field, so it is the one a consumer
          // would reach for by mistake — and a 400 is the right answer, where a
          // 500 or a result set built on a silently-dropped clause is not.
          const answer = await backendClient.listExperimentsFiltered({
            datasetId: seed.datasetId,
            filters: [
              {
                field: 'evaluation_method',
                operator: 'contains',
                value: seed.defaultMethodValue,
                type: 'enum',
              },
            ],
            size: PAGE_SIZE,
          });
          expect(
            answer.status,
            `a contains filter answered ${answer.status}: ${answer.message}`,
          ).toBe(400);
          // The message in full. "Rejected" is not the contract — rejected
          // naming the field and its type is, because that is what tells a
          // caller the operator is wrong rather than the value.
          expect(answer.message).toBe(
            "Invalid operator 'contains' for field 'evaluation_method' of type 'ENUM'",
          );
        });

        await test.step('The refusals changed nothing the next read can see', async () => {
          // The dataset still holds its three experiments. A validation path
          // that answered 400 after mutating state, or a 500 that left the
          // listing unusable, is invisible to the two steps above.
          const after = await backendClient.listExperimentsFiltered({
            datasetId: seed.datasetId,
            size: PAGE_SIZE,
          });
          expect(after.status, 'the unfiltered listing still answers 200').toBe(200);
          expect(after.total, 'and still counts the three seeded experiments').toBe(seed.all.length);
        });
      },
    );
  },
);
