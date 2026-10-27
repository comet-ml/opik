import { test as baseTest } from './experiment-type-datasets.fixture';
import { isRateLimitedError, uuid7, type BackendClient } from '../core/backend';

export interface PagedStreamPopulationRef {
  /** Every seeded trace id, minted here so the oracle is the seed itself. */
  traceIds: string[];
  /** Every seeded span id, `SPANS_PER_TRACE` per trace. */
  spanIds: string[];
  tracesSeeded: number;
  spansSeeded: number;
}

export interface PagedStreamPopulationFixtures {
  pagedStreamPopulation: PagedStreamPopulationRef;
}

/**
 * Sixty traces.
 *
 * Not arbitrary, and not "as many as we could afford": sixty is the smallest
 * population that crosses a page boundary SEVERAL times at every batch size the
 * spec drives (60/7 is nine pages, 60/13 is five) while still fitting in one page
 * at the SDK's own 2000 default — which is the single-page control the many-page
 * reads are compared against. Provoking the same arithmetic by seeding past
 * `MAX_ENDPOINT_BATCH_SIZE` would take 2001 traces for an identical assertion,
 * because `max_batch_size` is a parameter.
 */
const TRACE_COUNT = 60;
/** Two, so the span population is a different size from the trace one. */
const SPANS_PER_TRACE = 2;

/** How long a just-written batch may take to become queryable. */
const QUERYABLE_TIMEOUT_MS = 180_000;
/**
 * Two seconds, not one: both listings are rate-limited per workspace, and on a
 * shared environment a poll that hammers them for three minutes is this fixture
 * spending someone else's budget as well as its own.
 */
const QUERYABLE_POLL_MS = 2_000;
/** How long to stand off after a listing answers 429. */
const RATE_LIMIT_BACKOFF_MS = 10_000;

/**
 * Block until `count()` reports exactly `expected`.
 *
 * Both halves of "exactly" matter, and here the second one is the whole reason
 * this runs before `use()`. Ingestion is eventually consistent, so a read taken
 * straight after the write legitimately sees fewer — but the spec deliberately
 * does NOT pass `wait_for_at_least` to `search_*`, because a read that blocks
 * until the population appears would mask a SHORT READ as a slow one, and a short
 * read is the entire subject. The waiting has to happen somewhere, so it happens
 * where it cannot hide anything: against a different query, before the reads
 * under test begin.
 */
async function waitForCount(
  what: string,
  count: () => Promise<number>,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  let lastSwallowed: unknown = null;
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    try {
      const total = await count();
      seen = total;
      if (total === expected) return;
    } catch (err) {
      // The client already stands off and retries a 429; one reaching here means
      // the squeeze outlasted that backoff. A poll is exactly the caller that can
      // afford to wait it out. Only 429 — any other error is real and must
      // surface rather than being spent as poll budget.
      if (!isRateLimitedError(err)) throw err;
      seen = 'rate limited';
      lastSwallowed = err;
      await new Promise((r) => setTimeout(r, RATE_LIMIT_BACKOFF_MS));
      continue;
    }
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[pagedStreamPopulation fixture] ${what}: saw ${seen}, expected ${expected}, after ` +
      `${Date.now() - start}ms` +
      (lastSwallowed === null
        ? ''
        : `; last refusal stood off: ${
            lastSwallowed instanceof Error ? lastSwallowed.message : String(lastSwallowed)
          }`),
  );
}

/**
 * A project holding exactly sixty traces and one hundred and twenty spans, with
 * every id minted here.
 *
 * The ids ARE the oracle, which is why they are minted rather than read back: a
 * paging spec compares what the SDK's multi-page read returned against the set
 * that provably exists, and an oracle assembled by a second paged read would
 * share whatever bug it is looking for. The REST enumerations below are a check
 * on the SEED, not a substitute for the oracle — and they are a different query
 * from the cursor stream the SDK drives, so a cursor bug cannot make them pass.
 *
 * Written through the batch endpoints rather than the SDK. The subject is the
 * SDK's READ path (`read_and_parse_full_stream`), so routing 180 writes through
 * it as well would add a second suspect to every failure and a great deal of wall
 * clock. Both endpoints cap at 1000 per call, so this is two writes.
 *
 * No teardown of its own: traces and spans go with the `project` fixture.
 */
export const test = baseTest.extend<PagedStreamPopulationFixtures>({
  pagedStreamPopulation: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Minted in sequence, so the seed's own order matches the id order the
    // backend's `id < :last_retrieved_id` cursor pages in. That keeps a partial
    // read ("some 55 of the 60") a well-defined subset of a known set rather
    // than an accident of when each row happened to be written.
    const traceIds = Array.from({ length: TRACE_COUNT }, () => uuid7());
    await backendClient.createTracesBatch({
      projectName: project.name,
      traces: traceIds.map((id, i) => ({
        id,
        name: `${testNamespace}-trace-${String(i + 1).padStart(3, '0')}`,
        input: { question: `paged question ${i + 1}` },
        output: { answer: `paged answer ${i + 1}` },
      })),
    });

    const spanIds: string[] = [];
    const spans = traceIds.flatMap((traceId, traceIndex) =>
      Array.from({ length: SPANS_PER_TRACE }, (_, spanIndex) => {
        const id = uuid7();
        spanIds.push(id);
        return {
          id,
          traceId,
          name: `${testNamespace}-span-${String(traceIndex + 1).padStart(3, '0')}-${spanIndex + 1}`,
        };
      }),
    );
    await backendClient.createSpansBatch({ projectName: project.name, spans });

    // A duplicate id would silently shrink the population every assertion is
    // made against, so the seed's own uniqueness is checked rather than assumed.
    if (new Set(traceIds).size !== traceIds.length) {
      throw new Error('[pagedStreamPopulation fixture] minted a duplicate trace id');
    }
    if (new Set(spanIds).size !== spanIds.length) {
      throw new Error('[pagedStreamPopulation fixture] minted a duplicate span id');
    }

    const countTraces = async (client: BackendClient): Promise<number> =>
      (await client.listTraceIds({ projectId: project.id, size: 500 })).length;

    await waitForCount(
      `${TRACE_COUNT} traces queryable`,
      () => countTraces(backendClient),
      TRACE_COUNT,
    );
    await waitForCount(
      `${spanIds.length} spans queryable`,
      // size 1: the total is in the envelope, so there is no reason to transfer
      // rows in order to count them.
      async () =>
        (await backendClient.listSpanIdsPage({ projectId: project.id, page: 1, size: 1 })).total,
      spanIds.length,
    );

    const ref: PagedStreamPopulationRef = {
      traceIds,
      spanIds,
      tracesSeeded: TRACE_COUNT,
      spansSeeded: spanIds.length,
    };
    await testInfo.attach('opik.pagedStreamPopulation', {
      // The ids in full, all 180 of them. A failure here is "which id went
      // missing, and where in the order", and no shorter summary answers that.
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './experiment-type-datasets.fixture';
