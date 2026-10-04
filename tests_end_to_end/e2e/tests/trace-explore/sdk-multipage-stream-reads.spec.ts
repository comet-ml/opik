import { test, expect } from '@e2e/fixtures';
import type { StreamReadShape } from '@e2e/core/sdk';

/**
 * The Python SDK's MULTI-PAGE read of `search_traces` and `search_spans`
 * (opik#8411, issue 8407).
 *
 * `read_and_parse_full_stream` was rewritten so the paging cursor is the last id
 * the BACKEND sent rather than the last one that parsed, and so records the
 * backend sent but that failed to decode count towards a page being full. That is
 * a rewrite of the happy path of every paged read in the SDK — and nothing in this
 * estate had ever paged it. Every existing call asks for fewer rows than one page
 * holds: `search_traces(max_results=1)`, `search_spans(max_results=len(spans))`,
 * `search_threads`. `Experiment.get_items` does not go through this function at
 * all; it has its own concurrent pager.
 *
 * The failure mode is a SHORT READ: some ids simply absent from a list that
 * otherwise looks entirely ordinary. Nothing raises, nothing warns at the call
 * site, and in the `cli/migrate/datasets` paths a hole becomes a deletion.
 *
 * Crossing page boundaries is made cheap by `max_batch_size`, which is a
 * parameter — so the same arithmetic that would need 2001 rows at the shipped
 * 2000 default is provoked with 60 traces at a batch of 7. The fixture mints
 * every id, and that set is the oracle; the reads are compared against it, never
 * against another paged read.
 *
 * Deliberately no `wait_for_at_least` on any read here. It would block until the
 * population was visible, which is precisely how a short read disguises itself as
 * a slow one — the fixture establishes visibility first, through the offset-paged
 * REST listings, which are a different query from the cursor stream under test.
 *
 * API-level throughout: the subject is a list a Python function returned. Driving
 * a page to observe it second-hand would add a rendering failure mode to an
 * assertion about arithmetic, and the Logs table is a different read entirely.
 */

/**
 * Batch sizes that make the read page, plus the shipped default as the control.
 *
 * 7 and 13 are co-prime with 60 in the way that matters: neither divides it, so
 * the last page of each is a partial one and the read has to stop on a short page
 * rather than on an exact boundary. 2000 is the SDK's own
 * `MAX_ENDPOINT_BATCH_SIZE` — one page for this population, and the arm that
 * says the many-page reads agree with a read that never paged at all.
 */
const EXHAUSTIVE_BATCH_SIZES: Array<number | undefined> = [7, 13, undefined];

const batchLabel = (size: number | undefined): string =>
  size === undefined ? 'sdk-default' : `batch-${size}`;

/**
 * `max_results` values that are NOT multiples of the batch size, so the final
 * request of each read asks for fewer rows than a full page.
 *
 * `current_batch_size = min(amount_left, batch_size)` is the line these exercise:
 * a read that asked for a full page on the last request would over-read, and one
 * that stopped at the previous boundary would come back short. 1 at a batch of 7
 * is the degenerate end of the same arithmetic — one request, one row, and the
 * loop must not go round again.
 */
const PARTIAL_SHAPES: Array<{ maxResults: number; batchSize: number }> = [
  { maxResults: 55, batchSize: 7 },
  // The same count at a different page size. Which rows a partial read returns
  // is the backend's ordering to decide, not this spec's — but it must not
  // depend on the page size the client happened to pick, and these two are what
  // make that comparison possible. See the cross-check step below.
  { maxResults: 55, batchSize: 13 },
  { maxResults: 13, batchSize: 5 },
  { maxResults: 1, batchSize: 7 },
];

/** The two shapes above that ask for the same count at different page sizes. */
const CROSS_CHECK_COUNT = 55;

/** A `max_results` well past the population: the read must stop, not loop. */
const OVER_ASK = 500;

/** Every read this spec makes, as one bridge request. */
function shapesFor(population: number): StreamReadShape[] {
  return [
    ...EXHAUSTIVE_BATCH_SIZES.map((size) => ({
      key: `all-${batchLabel(size)}`,
      max_results: population,
      ...(size === undefined ? {} : { max_batch_size: size }),
    })),
    ...PARTIAL_SHAPES.map(({ maxResults, batchSize }) => ({
      key: `partial-${maxResults}-of-${batchSize}`,
      max_results: maxResults,
      max_batch_size: batchSize,
    })),
    { key: 'over-ask', max_results: OVER_ASK, max_batch_size: 7 },
  ];
}

/** The ids one shape returned, asserted to have been answered at all. */
function idsFor(
  results: Array<{ key: string; ids: string[] }>,
  key: string,
): string[] {
  const found = results.find((r) => r.key === key);
  expect(found, `the bridge answered the '${key}' shape`).toBeDefined();
  return found!.ids;
}

/**
 * Assert a read returned exactly the seeded set, once, in a single pass.
 *
 * Three separate claims, because they fail differently and a reader needs to know
 * which one broke: the LENGTH (a short or long read), the absence of DUPLICATES
 * (a cursor that re-served a page), and the SET (an id that never came back, or
 * one from somewhere else). A set comparison alone would hide a duplicate, and a
 * count alone would hide both a duplicate and a substitution.
 */
function assertReadIsExactly(
  label: string,
  returned: string[],
  seeded: string[],
): void {
  expect(returned, `${label}: the read returned as many ids as were seeded`).toHaveLength(
    seeded.length,
  );
  expect(
    new Set(returned).size,
    `${label}: no id came back twice — a duplicate is a page the cursor re-served`,
  ).toBe(returned.length);
  expect(
    new Set(returned),
    `${label}: the read returned exactly the seeded ids, no more and no fewer`,
  ).toEqual(new Set(seeded));
}

test.describe(
  'Python SDK — multi-page search reads',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'search_traces returns every seeded id exactly once at every page size, and the same set each time',
      { tag: ['@cap:traces.sdk-multipage-stream-reads'] },
      async ({ pagedStreamPopulation, sdkClient, project }) => {
        // Well past the 90s default, and spent on two things the assertions
        // cannot avoid: the fixture writes 180 rows and then waits for all of
        // them to be queryable before any read runs, and each shape below is a
        // whole multi-page walk — eight to eighteen backend requests each,
        // against a per-workspace rate limit the SDK stands off for. Crossing a
        // page boundary is the subject, so neither the population nor the number
        // of walks is padding.
        test.setTimeout(300_000);

        const reads = await test.step('Read the population back at three page sizes and four partial shapes', async () =>
          sdkClient.python.streamReads({
            project_name: project.name,
            trace_shapes: shapesFor(pagedStreamPopulation.tracesSeeded),
          }));

        await test.step('Every exhaustive read is exactly the seeded set', async () => {
          for (const size of EXHAUSTIVE_BATCH_SIZES) {
            const key = `all-${batchLabel(size)}`;
            assertReadIsExactly(
              `search_traces(max_results=${pagedStreamPopulation.tracesSeeded}, max_batch_size=${
                size ?? 'default'
              })`,
              idsFor(reads.traces, key),
              pagedStreamPopulation.traceIds,
            );
          }
        });

        await test.step('A partial read returns exactly as many as it asked for, all of them real', async () => {
          const seeded = new Set(pagedStreamPopulation.traceIds);
          for (const { maxResults, batchSize } of PARTIAL_SHAPES) {
            const label = `search_traces(max_results=${maxResults}, max_batch_size=${batchSize})`;
            const ids = idsFor(reads.traces, `partial-${maxResults}-of-${batchSize}`);
            expect(
              ids,
              `${label}: a max_results that is not a multiple of the page size must return exactly that many`,
            ).toHaveLength(maxResults);
            expect(new Set(ids).size, `${label}: no id came back twice`).toBe(ids.length);
            for (const id of ids) {
              expect(
                seeded.has(id),
                `${label}: returned an id this project was never seeded with (${id})`,
              ).toBe(true);
            }
          }
        });

        await test.step('Two page sizes reading the same count return the SAME ids', async () => {
          // The discriminating comparison for a partial read. Asserting "55 real
          // ids, none twice" accepts a cursor that dropped one row and picked up
          // a different one instead; the same 55 having to come back whatever the
          // page size does not. 55 at a batch of 7 ends its last page mid-page
          // after seven full ones, and at 13 after four — two quite different
          // walks over one ordering.
          const atSeven = idsFor(reads.traces, `partial-${CROSS_CHECK_COUNT}-of-7`);
          const atThirteen = idsFor(reads.traces, `partial-${CROSS_CHECK_COUNT}-of-13`);
          expect(
            atSeven.length,
            'both arms of the cross-check read the same count, or they are not comparable',
          ).toBe(atThirteen.length);
          expect(
            new Set(atSeven),
            `the same ${CROSS_CHECK_COUNT} ids must come back whatever page size the client chose`,
          ).toEqual(new Set(atThirteen));
          expect(
            atSeven.length,
            'and it is a partial read, not the whole population under another name',
          ).toBeLessThan(pagedStreamPopulation.tracesSeeded);
        });

        await test.step('Asking for far more than exists returns what exists, and stops', async () => {
          // The loop's termination condition (`current_batch_size >
          // records_received`). A read that kept going would either hang or
          // start replaying pages, and the duplicate check inside
          // `assertReadIsExactly` is what would catch the replay.
          assertReadIsExactly(
            `search_traces(max_results=${OVER_ASK}) over ${pagedStreamPopulation.tracesSeeded} traces`,
            idsFor(reads.traces, 'over-ask'),
            pagedStreamPopulation.traceIds,
          );
        });
      },
    );

    test(
      'search_spans pages the same way over a differently-sized population',
      { tag: ['@cap:traces.sdk-multipage-stream-reads'] },
      async ({ pagedStreamPopulation, sdkClient, project }) => {
        // Well past the 90s default, and spent on two things the assertions
        // cannot avoid: the fixture writes 180 rows and then waits for all of
        // them to be queryable before any read runs, and each shape below is a
        // whole multi-page walk — eight to eighteen backend requests each,
        // against a per-workspace rate limit the SDK stands off for. Crossing a
        // page boundary is the subject, so neither the population nor the number
        // of walks is padding.
        test.setTimeout(300_000);

        // A separate test, and a separate population size, because the spans
        // stream is a different endpoint behind the same function: 120 spans at a
        // batch of 7 is seventeen full pages and a partial one, where 60 traces is
        // eight and a partial. An off-by-one that happened to be invisible at one
        // of those counts is not at the other.
        const reads = await test.step('Read every seeded span back at three page sizes', async () =>
          sdkClient.python.streamReads({
            project_name: project.name,
            span_shapes: shapesFor(pagedStreamPopulation.spansSeeded),
          }));

        await test.step('The span population really is a different size from the trace one', async () => {
          // Otherwise this test is the previous one again under another name.
          expect(
            pagedStreamPopulation.spansSeeded,
            'the spans arm must not read the same count as the traces arm',
          ).not.toBe(pagedStreamPopulation.tracesSeeded);
        });

        await test.step('Every exhaustive read is exactly the seeded set', async () => {
          for (const size of EXHAUSTIVE_BATCH_SIZES) {
            assertReadIsExactly(
              `search_spans(max_results=${pagedStreamPopulation.spansSeeded}, max_batch_size=${
                size ?? 'default'
              })`,
              idsFor(reads.spans, `all-${batchLabel(size)}`),
              pagedStreamPopulation.spanIds,
            );
          }
        });

        await test.step('A partial read returns exactly as many as it asked for, all of them real', async () => {
          const seeded = new Set(pagedStreamPopulation.spanIds);
          for (const { maxResults, batchSize } of PARTIAL_SHAPES) {
            const label = `search_spans(max_results=${maxResults}, max_batch_size=${batchSize})`;
            const ids = idsFor(reads.spans, `partial-${maxResults}-of-${batchSize}`);
            expect(ids, `${label}: exactly the requested count`).toHaveLength(maxResults);
            expect(new Set(ids).size, `${label}: no id came back twice`).toBe(ids.length);
            for (const id of ids) {
              expect(
                seeded.has(id),
                `${label}: returned an id this project was never seeded with (${id})`,
              ).toBe(true);
            }
          }
        });

        await test.step('Asking for far more than exists returns what exists, and stops', async () => {
          assertReadIsExactly(
            `search_spans(max_results=${OVER_ASK}) over ${pagedStreamPopulation.spansSeeded} spans`,
            idsFor(reads.spans, 'over-ask'),
            pagedStreamPopulation.spanIds,
          );
        });
      },
    );
  },
);
