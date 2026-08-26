import { test, expect } from '@e2e/fixtures';
import { uuid7, type BackendClient } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * Trace deletion across the eras a trace id's embedded timestamp can land in.
 *
 * A trace `DELETE` bounds its mutation to the weekly partitions the batch's ids
 * resolve to (`WeeklyPartitions.of`, `TraceDAO`). The two `id_at` columns the
 * statement may meet declare different widths — legacy `traces` is a 32-bit
 * `DateTime` and stores a timestamp past `2106-02-07 06:28:15` **wrapped**
 * modulo 2^32 seconds, `traces_local_v2` is a `DateTime64` and stores the honest
 * value — so a far-future id (litellm mints ~2201, see BerriAI/litellm#31294,
 * and production already holds such rows) belongs to a different week on each.
 * Naming only one of the two is a predicate that matches nothing.
 *
 * That failure is silent from the outside: the endpoint answers 204 and the row
 * is still there. So every assertion here is "the row really went away" — never
 * "the request succeeded".
 *
 * `trace-delete.spec.ts` cannot catch it: it seeds through
 * `sdkClient.python.createTrace`, so every id it deletes is minted at wall-clock
 * time, where the honest and wrapped weeks coincide and the second value is
 * never derived. The ids here are minted with `uuid7()` at fixed literal
 * instants — no wall clock, no LLM, no dependence on data already in the
 * workspace.
 *
 * Why the era matrix is asserted through the API and not the Logs table: the
 * Logs page always applies a trailing date window (`TracesSpansTab` defaults to
 * "Past 30 days" and passes `excludePresets: [alltime]`, and a custom range is
 * clamped to the last year), and that window is applied to `id_at`. On the
 * legacy column an id minted at `2106-02-07T06:28:16Z` stores as `1970-01-01`,
 * so its row is outside every range the control can express and is never listed
 * — with or without this change. Driving that era through the UI would assert
 * the absence of a row that was never present, which is a test that cannot
 * fail. The UI half of the story is the mixed-era bulk delete below, whose ids
 * both list normally.
 */

interface SeededTrace {
  id: string;
  name: string;
  /** Human-readable era label, used in step titles and assertion messages. */
  label: string;
}

/**
 * One id per class the partition derivation branches on:
 *   - the last second at which the legacy modulo is still the identity,
 *   - the first second past it, where the id starts contributing a second week,
 *   - a litellm-style ~2201 id, the shape production actually holds,
 *   - the last week below `ID_AT_CEILING`, still derivable,
 *   - one past the ceiling, where the derivation gives up and the statement goes
 *     out unbounded.
 */
const FAR_FUTURE_ERAS: ReadonlyArray<{ label: string; instant: string }> = [
  { label: 'era-2106-last-identity-second', instant: '2106-02-07T06:28:15Z' },
  { label: 'era-2106-first-wrapping-second', instant: '2106-02-07T06:28:16Z' },
  { label: 'era-2201-litellm', instant: '2201-08-05T00:00:00Z' },
  { label: 'era-2299-below-ceiling', instant: '2299-12-31T00:00:00Z' },
  { label: 'era-2350-past-ceiling', instant: '2350-06-01T00:00:00Z' },
];

const LITELLM_ERA = '2201-08-05T00:00:00Z';

/**
 * Seed one trace whose id is minted at `instant` (default: now), through the
 * REST write.
 *
 * The bridge mints its own ids, and these tests need the id to carry a chosen
 * timestamp — that is the whole variable under test — so the write goes through
 * the backend client, the same route `optimization-run.fixture.ts` takes when it
 * has to name the traces it seeds.
 */
async function seedTraceAt(
  backendClient: BackendClient,
  projectName: string,
  testNamespace: string,
  label: string,
  instant?: string,
): Promise<SeededTrace> {
  const name = `${testNamespace}-${label}`;
  const id = await backendClient.createTraceWithSource({
    id: uuid7(instant === undefined ? new Date() : new Date(instant)),
    projectName,
    name,
    source: 'sdk',
    input: { question: `question from ${label}` },
    output: { answer: `answer from ${label}` },
  });
  return { id, name, label };
}

/** Ids the project lists right now, unwindowed, sorted so sets compare cleanly. */
async function listedIds(backendClient: BackendClient, projectId: string): Promise<string[]> {
  return (await backendClient.listTraceIds({ projectId })).sort();
}

const sortedIds = (traces: readonly SeededTrace[]): string[] => traces.map((t) => t.id).sort();

test.describe('Trace deletion — id timestamp eras', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'Deleting a trace through the API removes it at every id timestamp era, including past the 2106 wrap',
    { tag: ['@cap:traces.delete-traces-api'] },
    async ({ project, backendClient, testNamespace }) => {
      const { farFuture, survivor } = await test.step(
        'Seed one trace per far-future id era, plus an ordinary survivor',
        async () => {
          const seeded: SeededTrace[] = [];
          for (const era of FAR_FUTURE_ERAS) {
            seeded.push(
              await seedTraceAt(backendClient, project.name, testNamespace, era.label, era.instant),
            );
          }
          return {
            farFuture: seeded,
            survivor: await seedTraceAt(
              backendClient,
              project.name,
              testNamespace,
              'ordinary-survivor',
            ),
          };
        },
      );

      await test.step('Every era really was accepted by the write (seed sanity)', async () => {
        // A delete assertion over a seed that silently failed is a test that
        // cannot fail. `UUID_VALIDATION_ENABLED` governs how far ahead of now an
        // id may sit, so "the far-future write was rejected" is a real way for
        // this setup to collapse, and it has to fail here rather than later as a
        // row that was never there. Asserted as the whole listing, not id by id,
        // so a stray extra trace also fails.
        expect(await listedIds(backendClient, project.id)).toEqual(
          sortedIds([...farFuture, survivor]),
        );
      });

      await test.step('Delete each far-future trace on its own through the REST API', async () => {
        for (const trace of farFuture) {
          await backendClient.deleteTraces([trace.id]);
        }
        // The endpoint answers before the mutation has necessarily materialised,
        // so poll the read rather than asserting once. This is the assertion that
        // separates "the predicate matched the row" from a 204 over nothing.
        for (const trace of farFuture) {
          await expect
            .poll(() => backendClient.getTrace(trace.id), {
              message: `trace deleted at ${trace.label}`,
              timeout: 30_000,
            })
            .toBeNull();
        }
      });

      await test.step('The survivor is untouched and is all that is left', async () => {
        expect(await backendClient.getTrace(survivor.id)).not.toBeNull();
        expect(await listedIds(backendClient, project.id)).toEqual([survivor.id]);
      });
    },
  );
});

test.describe(
  'Trace deletion — mixed-era bulk delete',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'Bulk-deleting a mixed-era batch from the Logs table removes every selected row',
      { tag: ['@cap:traces.delete-traces'] },
      async ({ project, backendClient, testNamespace, page }) => {
        const logs = new LogsPage(page);

        // One delete over both eras binds the union of their partitions, so an
        // ordinary id has to stay matched by that wider set — the direction the
        // derivation's guard exists to keep safe, and the one no existing
        // bulk-delete test drives, because none of them mixes eras.
        const { ordinary, farFuture, survivor } = await test.step(
          'Seed an ordinary trace, a ~2201 trace and an ordinary survivor',
          async () => ({
            ordinary: await seedTraceAt(backendClient, project.name, testNamespace, 'ordinary'),
            farFuture: await seedTraceAt(
              backendClient,
              project.name,
              testNamespace,
              'era-2201-litellm',
              LITELLM_ERA,
            ),
            survivor: await seedTraceAt(
              backendClient,
              project.name,
              testNamespace,
              'ordinary-survivor',
            ),
          }),
        );

        await test.step('Both eras really were accepted by the write (seed sanity)', async () => {
          expect(await listedIds(backendClient, project.id)).toEqual(
            sortedIds([ordinary, farFuture, survivor]),
          );
        });

        await test.step('Open Logs and verify all three traces are listed', async () => {
          await logs.goto(project.id);
          await logs.waitForReady();
          for (const trace of [ordinary, farFuture, survivor]) {
            await expect(logs.traceRow(trace.id), `row for ${trace.label}`).toHaveCount(1);
          }
          // The whole answer, not just "mine are in it" — nothing else may be
          // here. `logs.traceRows`, never `logs.countTraces()`: the metrics card
          // windows on the id's embedded timestamp while the table does not, so
          // it reads 2 for this three-trace project.
          await expect(logs.traceRows).toHaveCount(3);
        });

        await test.step('Select the ordinary and the far-future row and bulk-delete them', async () => {
          await logs.selectTrace(ordinary.id);
          await logs.selectTrace(farFuture.id);
          await expect(logs.bulkDeleteButton).toBeEnabled();
          await logs.bulkDeleteSelected();
        });

        await test.step('Verify both deleted rows are gone and the survivor remains', async () => {
          await expect(logs.traceRow(ordinary.id)).toHaveCount(0);
          await expect(logs.traceRow(farFuture.id)).toHaveCount(0);
          await expect(logs.traceRow(survivor.id)).toBeVisible();
          await expect(logs.traceRows).toHaveCount(1);
        });

        await test.step('Verify both are gone from the API too', async () => {
          for (const trace of [ordinary, farFuture]) {
            await expect
              .poll(() => backendClient.getTrace(trace.id), {
                message: `trace deleted at ${trace.label}`,
                timeout: 30_000,
              })
              .toBeNull();
          }
          expect(await backendClient.getTrace(survivor.id)).not.toBeNull();
          expect(await listedIds(backendClient, project.id)).toEqual([survivor.id]);
        });
      },
    );
  },
);
