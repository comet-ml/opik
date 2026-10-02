import { test as baseTest } from './suite-experiment-run-media.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';
import { skipUnlessBackdatedIdsAccepted } from './uuid-window-guard';

/**
 * One seeded span whose UUIDv7 id embeds a chosen instant.
 *
 * `idMoment` is the axis: `SpansReadPathWeekBound` derives the week partitions
 * to scan from the id's embedded timestamp, so which weeks a read touches — and
 * therefore whether the row is found at all — is decided here and nowhere else.
 * The span's own `start_time` is present-day throughout, which is the point:
 * these are ordinary spans on an ordinary trace, distinguished only by the
 * instant their id happens to encode.
 */
export interface IdAgedSpanRef {
  id: string;
  label: string;
  name: string;
  idMoment: Date;
  input: Record<string, string>;
  output: Record<string, string>;
  metadata: Record<string, string>;
  type: string;
}

export interface IdAgedSpansRef {
  projectId: string;
  projectName: string;
  traceId: string;
  /** The four ages, in seed order. */
  spans: IdAgedSpanRef[];
}

export interface IdAgedSpansFixtures {
  idAgedSpans: IdAgedSpansRef;
}

/**
 * 1970-01-01, whose ISO week starts 1969-12-29 — below the floor of
 * ClickHouse's 16-bit `Date`.
 */
const EPOCH_MOMENT = new Date(0);

/**
 * ~2201-08-05, the instant a 32-bit `DateTime` wraps to.
 *
 * The legacy `id_at DateTime('UTC')` column truncates anything past 2106 before
 * a week expression ever sees it, so this is the age at which a bound computed
 * from the stored column and one computed from the id itself part company.
 */
const FAR_FUTURE_MOMENT = new Date(Date.UTC(2201, 7, 5));

/**
 * 2350-01-01, past the `DateTime64` ceiling (2299-12-31).
 *
 * The age where the bound has to be DROPPED rather than computed: a week
 * derived from an instant the column cannot represent would exclude the row's
 * own partition, and the read would answer 404 for a span that is plainly
 * there.
 */
const BEYOND_CEILING_MOMENT = new Date(Date.UTC(2350, 0, 1));

/** How long a just-written span may take to become readable by id. */
const READABLE_TIMEOUT_MS = 30_000;
const READABLE_POLL_MS = 500;

const AGES: Array<{ label: string; idMoment: Date }> = [
  { label: 'epoch', idMoment: EPOCH_MOMENT },
  { label: 'present', idMoment: new Date() },
  { label: 'far-future', idMoment: FAR_FUTURE_MOMENT },
  { label: 'beyond-ceiling', idMoment: BEYOND_CEILING_MOMENT },
];

/**
 * One present-day trace carrying four spans whose ids span every age the
 * read-path week bound has to cope with (opik#8537, OPIK-8361).
 *
 * The PR bounds the span-by-id read to the weeks an id resolves to, which turns
 * a full-table scan into a partition-local one. The risk it introduces is that
 * a bound computed wrongly — or computed at all where the instant is
 * unrepresentable — silently excludes the row's own partition. That failure is
 * not an error: the read answers "not found", and an UPDATE whose row lookup
 * misses falls through to a partial insert that returns 204 and drops every
 * field the request did not carry.
 *
 * Every age is a FIXED instant, not an offset from the wall clock, so the seed
 * means the same thing on every run.
 *
 * The spans deliberately differ in name, input, output and metadata: the whole
 * point of the PATCH half is that a missed lookup leaves those fields behind,
 * and identical spans could not show it. Present-day is included as the control
 * — an age at which every bound, right or wrong, contains the row — so a failure
 * at the extremes cannot be confused with the read being broken outright.
 *
 * Teardown deletes the trace, which cascades to its spans
 * (`TraceDeletedListener` → `SpanService.deleteByTraceIds`); traces do not
 * cascade with the project, so the trace itself must go explicitly.
 */
export const test = baseTest.extend<IdAgedSpansFixtures>({
  idAgedSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Probed before anything is seeded: an env running UUID timestamp
    // validation in reject mode refuses these ids outright, and the honest
    // answer there is "this env cannot host this test", not a failure that
    // reads like a product defect.
    await skipUnlessBackdatedIdsAccepted(
      backendClient,
      project.name,
      Date.now() - EPOCH_MOMENT.getTime(),
    );

    const traceId = uuid7();
    await backendClient.createTraceWithSource({
      id: traceId,
      projectName: project.name,
      name: `${testNamespace}-aged-spans-trace`,
      source: 'sdk',
      input: { q: 'aged spans' },
      output: { a: 'aged spans' },
    });

    const spans: IdAgedSpanRef[] = AGES.map(({ label, idMoment }) => ({
      id: uuid7(idMoment),
      label,
      idMoment,
      name: `${testNamespace}-span-${label}`,
      input: { q: `input for ${label}` },
      output: { a: `output for ${label}` },
      metadata: { seededFor: label },
      type: 'general',
    }));

    try {
      for (const span of spans) {
        await backendClient.createSpan({
          id: span.id,
          traceId,
          projectName: project.name,
          name: span.name,
          source: 'sdk',
          type: 'general',
          input: span.input,
          output: span.output,
          metadata: span.metadata,
        });
      }

      // Every span readable by id, with its own fields, BEFORE any test runs.
      //
      // This is the fixture proving it can discriminate. If a seed at one of
      // the extreme ages never became readable, the spec's own by-id read would
      // fail and look exactly like the regression it is hunting — so the
      // distinction is drawn here, where the failure message can say "the seed
      // never landed" instead.
      for (const span of spans) {
        await waitForSpan(backendClient, span);
      }

      const ref: IdAgedSpansRef = {
        projectId: project.id,
        projectName: project.name,
        traceId,
        spans,
      };

      await testInfo.attach('opik.idAgedSpans', {
        body: JSON.stringify(
          {
            ...ref,
            spans: spans.map((s) => ({ ...s, idMoment: s.idMoment.toISOString() })),
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces([traceId]);
        } catch (err) {
          console.warn(`[idAgedSpans fixture] delete warning for trace ${traceId}:`, err);
        }
      }
    }
  },
});

/**
 * Block until one span reads back by id with the fields it was written with.
 *
 * Polls rather than reads once: the span write answers before the row is
 * queryable. The failure message distinguishes "never became readable" from
 * "came back wrong", because the two send a reader to different places — the
 * first to ingestion, the second to the read path this spec is about.
 */
async function waitForSpan(backendClient: BackendClient, span: IdAgedSpanRef): Promise<void> {
  const start = Date.now();
  let seen = false;
  let last: unknown = null;
  while (Date.now() - start < READABLE_TIMEOUT_MS) {
    const payload = await backendClient.getSpanPayload(span.id);
    if (payload !== null) {
      seen = true;
      last = payload;
      if (payload.name === span.name && payload.output?.a === span.output.a) return;
    }
    await new Promise((r) => setTimeout(r, READABLE_POLL_MS));
  }
  throw new Error(
    `[idAgedSpans fixture] the ${span.label} span ${span.id} (id instant ` +
      `${span.idMoment.toISOString()}) ` +
      (seen
        ? `came back without the fields it was seeded with: ${JSON.stringify(last)}`
        : 'never became readable by id') +
      ` after ${Date.now() - start}ms`,
  );
}

export { expect } from './suite-experiment-run-media.fixture';
