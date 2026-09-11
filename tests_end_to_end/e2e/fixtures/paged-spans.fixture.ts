import type { TestInfo } from '@playwright/test';
import { test as baseTest } from './timed-threads.fixture';
import { isUuidWindowRejection, UUID_VALIDATION_SKIP_REASON } from './id-aged-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  isRateLimitedError,
  uuid7,
  type BackendClient,
  type SpanBatchSeed,
} from '../core/backend';

/** The seeded span population a paging spec reads back. */
export interface PagedSpansRef {
  /** The trace ids the spans hang off, one per group. */
  traceIds: string[];
  /** Every seeded span id, sorted, so a collected set can be compared to it. */
  spanIds: string[];
  /** `spanIds.length`, named because every paging assertion is about it. */
  total: number;
}

/** The same project, plus one span whose id sorts ahead of every other. */
export interface FarFuturePagedSpanRef {
  id: string;
  name: string;
  /** The instant the id embeds — the axis under test, not the row's start_time. */
  idMoment: Date;
}

export interface PagedSpansFixtures {
  pagedSpans: PagedSpansRef;
  farFuturePagedSpan: FarFuturePagedSpanRef;
}

/**
 * 130 spans over five traces.
 *
 * Chosen so no page size the table offers divides the population evenly: at 25
 * the last page holds 5, at 50 it holds 30, at 100 it holds 30. A count that
 * divided evenly would let a reader that dropped or duplicated a whole page
 * still finish on a clean boundary, which is the shape of paging bug this seed
 * exists to catch.
 */
const TRACE_COUNT = 5;
const SPANS_PER_TRACE = 26;

/**
 * Above the 16-bit `Date` ceiling (2149-06-06), which is what makes this span's
 * id greater than every other in the project and therefore usable as the cursor
 * that should admit all of them. The exploration this spec came from reproduced
 * the pre-fix shape at this age.
 */
const FAR_FUTURE_MOMENT = new Date(Date.UTC(2200, 5, 15));

/** How long a just-written batch may take to become queryable. */
const QUERYABLE_TIMEOUT_MS = 120_000;
/**
 * Two seconds, not the usual one. The spans listing is rate-limited per
 * workspace (`getSpans:{workspaceId}`), and a poll is the one caller that can
 * hammer it for a full two minutes — on a shared environment that is this
 * fixture spending someone else's budget as well as its own.
 */
const QUERYABLE_POLL_MS = 2_000;
/** How long to stand off after the listing answers 429. */
const RATE_LIMIT_BACKOFF_MS = 10_000;

/** The 48-bit big-endian millisecond timestamp a UUIDv7 carries, per RFC 9562. */
const embeddedMillis = (id: string): number =>
  parseInt(id.replace(/-/g, '').slice(0, 12), 16);

/**
 * Block until the project's span listing reports exactly `expected` rows.
 *
 * Both halves of "exactly" matter. Ingestion is eventually consistent, so a
 * read taken straight after the write legitimately sees fewer; and a spec whose
 * subject is "every span comes back once" cannot open a browser against a seed
 * that only half landed — it would assert against a fixture that silently
 * failed to set up, which reads as coverage forever.
 */
async function waitForSpanCount(
  backendClient: BackendClient,
  projectId: string,
  expected: number,
): Promise<void> {
  const start = Date.now();
  let seen: number | string = 'no answer yet';
  while (Date.now() - start < QUERYABLE_TIMEOUT_MS) {
    try {
      // size 1: the total is in the envelope, so there is no reason to transfer
      // rows to count them.
      const total = (await backendClient.listSpanIdsPage({ projectId, page: 1, size: 1 })).total;
      seen = total;
      if (total === expected) return;
    } catch (err) {
      // The client already stands off and retries a 429; if one still reaches
      // here the squeeze outlasted that backoff. A poll is exactly the caller
      // that can afford to wait it out, so it does — rather than failing the
      // test with an infrastructure message. Only 429: any other error is a
      // real one and must surface.
      if (!isRateLimitedError(err)) throw err;
      seen = 'rate limited';
      await new Promise((r) => setTimeout(r, RATE_LIMIT_BACKOFF_MS));
      continue;
    }
    await new Promise((r) => setTimeout(r, QUERYABLE_POLL_MS));
  }
  throw new Error(
    `[pagedSpans fixture] project ${projectId} reported ${seen} spans, expected ${expected}, ` +
      `after ${Date.now() - start}ms`,
  );
}

async function deleteTraces(
  backendClient: BackendClient,
  traceIds: string[],
  label: string,
): Promise<void> {
  try {
    await backendClient.deleteTraces(traceIds);
  } catch (err) {
    // Never rethrow from teardown: a cleanup failure must not replace the
    // test's own error.
    console.warn(`[${label} fixture] trace delete warning:`, err);
  }
}

export const test = baseTest.extend<PagedSpansFixtures>({
  /**
   * A fresh project holding 130 spans across five traces, every id minted here
   * so the spec can compare a paged read to the exact set that was written.
   *
   * Seeded through `POST /v1/private/spans/batch` rather than one span at a
   * time: 130 sequential writes is both slow and the fastest route to the
   * workspace ingestion rate limit, and a rate-limited seed lands partially —
   * indistinguishable, from inside the spec, from a paging read that lost rows.
   *
   * Teardown deletes the traces, which takes their spans with them. Neither the
   * project delete nor `global-teardown`'s run-prefix sweep would: the sweep
   * knows about projects, datasets and experiments, and deleting a project does
   * not delete its traces.
   */
  pagedSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];
    const spans: SpanBatchSeed[] = [];

    try {
      for (let t = 0; t < TRACE_COUNT; t++) {
        const traceId = uuid7();
        await backendClient.createTraceWithSource({
          id: traceId,
          projectName: project.name,
          name: `${testNamespace}-t${t}`,
          source: 'sdk',
          input: `${testNamespace} input t${t}`,
          output: `${testNamespace} output t${t}`,
          endTime: new Date(),
        });
        traceIds.push(traceId);

        for (let s = 0; s < SPANS_PER_TRACE; s++) {
          spans.push({
            id: uuid7(),
            traceId,
            name: `${testNamespace}-t${t}-s${s}`,
          });
        }
      }

      await backendClient.createSpansBatch({ projectName: project.name, spans });

      const spanIds = spans.map((s) => s.id).sort();
      // A duplicate id would silently shrink the population the spec compares
      // against, so the seed's own uniqueness is checked rather than assumed.
      if (new Set(spanIds).size !== spanIds.length) {
        throw new Error('[pagedSpans fixture] minted a duplicate span id');
      }

      await waitForSpanCount(backendClient, project.id, spanIds.length);

      const ref: PagedSpansRef = { traceIds, spanIds, total: spanIds.length };
      await testInfo.attach('opik.pagedSpans', {
        body: JSON.stringify({ ...ref, spanIds: `${spanIds.length} ids` }, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        await deleteTraces(backendClient, traceIds, 'pagedSpans');
      }
    }
  },

  /**
   * One extra span in `pagedSpans`' project whose UUIDv7 id embeds mid-2200.
   *
   * Chained onto `pagedSpans` rather than seeded alongside it so the ordinary
   * paging test can keep asserting on an exact 130 — a test that does not
   * request this fixture never gets the extra row.
   *
   * The seed proves its own premise before the spec reads anything: the id is
   * decoded back and compared to the instant it was minted for. A mis-minted id
   * would leave every assertion below exercising an ordinary span.
   */
  farFuturePagedSpan: async (
    { backendClient, pagedSpans, project, testNamespace },
    use,
    testInfo: TestInfo,
  ) => {
    const ref: FarFuturePagedSpanRef = {
      id: uuid7(FAR_FUTURE_MOMENT),
      name: `${testNamespace}-far-future`,
      idMoment: FAR_FUTURE_MOMENT,
    };

    const embedded = embeddedMillis(ref.id);
    if (embedded !== FAR_FUTURE_MOMENT.getTime()) {
      throw new Error(
        `[farFuturePagedSpan fixture] id ${ref.id} embeds ${new Date(embedded).toISOString()}, ` +
          `expected ${FAR_FUTURE_MOMENT.toISOString()}`,
      );
    }

    try {
      await backendClient.createSpansBatch({
        projectName: project.name,
        spans: [
          {
            id: ref.id,
            traceId: pagedSpans.traceIds[0],
            name: ref.name,
            // start_time stays at "now" on purpose: the id is the only axis
            // under test, and a 2200 start_time would additionally exercise the
            // write path's own range validation.
          },
        ],
      });
    } catch (err) {
      // Reject-mode UUID validation refuses exactly the id this fixture exists
      // to seed. It ships disabled and the mode is not readable from the
      // client, so it is detected from the rejection — otherwise the spec fails
      // as an opaque 400 that reads like a product bug.
      if (isUuidWindowRejection(err)) baseTest.skip(true, UUID_VALIDATION_SKIP_REASON);
      throw err;
    }

    await waitForSpanCount(backendClient, project.id, pagedSpans.total + 1);

    await testInfo.attach('opik.farFuturePagedSpan', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    // No teardown of its own: the span hangs off a trace `pagedSpans` owns, and
    // that fixture's delete takes it. Deleting it here as well would race with
    // the spec's own reads on a shared trace.
    await use(ref);
  },
});

export { expect } from './timed-threads.fixture';
