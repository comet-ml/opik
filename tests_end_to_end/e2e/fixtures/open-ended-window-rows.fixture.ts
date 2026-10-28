import { test as baseTest } from './experiment-message-panel.fixture';
import {
  isUuidWindowRejection,
  skipUnlessBackdatedIdsAccepted,
  UUID_VALIDATION_SKIP_REASON,
} from './uuid-window-guard';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, uuid7Moment, type BackendClient, type SpanBatchSeed } from '../core/backend';

/** One seeded trace, its single LLM span and the thread the two sit in. */
export interface OpenEndedWindowRow {
  /** `current`, `previous` or `far-future` — which of the three groups it seeds. */
  group: 'current' | 'previous' | 'far-future';
  traceId: string;
  spanId: string;
  threadId: string;
  /** The instant the trace's UUIDv7 id embeds — the axis every windowed read uses. */
  idMoment: Date;
  /**
   * The UTC day the id falls on, `YYYY-MM-DD`, as a DAILY bucket's `time`
   * begins. Derived from the minted id rather than from the requested age, so
   * a caller compares against the day the row really landed on.
   */
  idDay: string;
  /**
   * The UTC day the row's `start_time` falls on — the same day as `idDay` for
   * every row except the far-future one, where the two deliberately differ.
   */
  startDay: string;
  durationMs: number;
  errored: boolean;
}

/** What one KPI period must total, in the units the `kpi-cards` answer uses. */
export interface OpenEndedKpiExpectation {
  count: number;
  /** Percentage in [0, 100], as the `errors` card reports it. Absent for threads. */
  errorRate: number;
  /** Milliseconds. */
  avgDuration: number;
  /** USD. */
  totalCost: number;
}

export interface OpenEndedWindowRowsRef {
  rows: OpenEndedWindowRow[];
  /** The one row whose UUIDv7 id embeds mid-2200. */
  farFuture: OpenEndedWindowRow;
  /** The three rows inside the current window by their id. */
  current: OpenEndedWindowRow[];
  /** The two rows in the equal-length period before it. */
  previous: OpenEndedWindowRow[];
  /**
   * What the cards must read when the request carries NO `interval_end` — the
   * far-future row included in the current period.
   */
  openEnded: OpenEndedKpiExpectation;
  /**
   * What the same cards must read when `interval_end` is sent as `now`, which
   * is what the Logs page used to do. Every number differs from `openEnded`.
   */
  explicitEnd: OpenEndedKpiExpectation;
  /**
   * The prior period, which is identical under both reads: the backend sizes it
   * from its own `now` whether or not an end was requested, so a far-future row
   * can only ever inflate `current`.
   */
  previousPeriod: OpenEndedKpiExpectation;
  /**
   * The `interval_start` the Logs page sends for its `past7days` preset: UTC
   * start of day, six days back. The backend derives the prior period by
   * shifting the current one back by its own length, so a caller must send
   * exactly this value.
   */
  intervalStart: Date;
  /** The `time_range` preset key a caller must open the Logs page with. */
  timeRangePreset: string;
}

export interface OpenEndedWindowRowsFixtures {
  openEndedWindowRows: OpenEndedWindowRowsRef;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Above the 16-bit `Date` ceiling (2149-06-06) and 174 years clear of any clock
 * this suite could run on, so nothing here turns on when it runs. The same
 * instant `far-future-error-traces.fixture.ts` uses, deliberately: a row a
 * client with a bad clock really could mint.
 */
const FAR_FUTURE_MOMENT = new Date(Date.UTC(2200, 5, 15));

/**
 * `claude-haiku-4.5-20251001` at 1M prompt + 1M completion tokens prices at
 * $1/M in + $5/M out = exactly $6.00 from the shipped price table — the same
 * vector `span-cost-resolution.spec.ts` and `span-kpi-cards.fixture.ts` pin, so
 * if the table moves they fail together and say so.
 *
 * A million of each also lifts every per-span cost clear of `formatCost`'s
 * `<$0.01` floor, so the rendered card reads an exact dollar amount.
 */
const COST_MODEL = 'claude-haiku-4.5-20251001';
const COST_PROVIDER = 'anthropic';
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;
const COST_PER_SPAN = 6;

const ERROR_INFO = {
  exceptionType: 'ValueError',
  message: 'seeded row failure',
  traceback: 'seeded row failure',
};

/**
 * The seed, one entry per row.
 *
 * `idAgeDays` places the row on the axis every windowed read uses; `ms` is the
 * duration both the trace and its span carry, so the Avg duration card reads
 * the same number whichever entity type it is asked for.
 *
 * The ages are whole days apart and none is less than a day old, so every row
 * lands on its own UTC day and NONE of them lands on today — which is what
 * makes today's chart bucket belong to the far-future row alone, at every hour
 * the suite could start at. The current window runs from UTC midnight six days
 * back, so 3.2 days is comfortably inside it and 8.5 is comfortably outside.
 *
 * The durations are chosen so the Avg duration card moves between the two
 * reads as well as the count: 450ms open-ended (four rows), 200ms with an
 * explicit end (three), 600ms in the prior period. Three distinct numbers, so
 * a card wired to the wrong period cannot land on the right one.
 */
const SEED: Array<{
  group: OpenEndedWindowRow['group'];
  label: string;
  idAgeDays?: number;
  startAgeDays: number;
  ms: number;
  errored: boolean;
}> = [
  { group: 'current', label: 'current-0', idAgeDays: 1.2, startAgeDays: 1.2, ms: 100, errored: true },
  { group: 'current', label: 'current-1', idAgeDays: 2.2, startAgeDays: 2.2, ms: 200, errored: true },
  { group: 'current', label: 'current-2', idAgeDays: 3.2, startAgeDays: 3.2, ms: 300, errored: false },
  { group: 'previous', label: 'previous-0', idAgeDays: 8.5, startAgeDays: 8.5, ms: 600, errored: false },
  { group: 'previous', label: 'previous-1', idAgeDays: 9.5, startAgeDays: 9.5, ms: 600, errored: false },
  // The only row whose id and `start_time` fall on different days, and the
  // reason that matters: its `start_time` day (4.2 back) is INSIDE the
  // requested window, so a read that ranged or bucketed on `start_time` would
  // count it under an explicit end too, and would place it on that day rather
  // than in the latest bucket. Both are what this seed tells apart.
  { group: 'far-future', label: 'far-future', startAgeDays: 4.2, ms: 1200, errored: false },
];

/** UTC start of day, `days` back — how the front end builds `past7days`. */
function utcStartOfDayAgo(days: number): Date {
  const at = new Date(Date.now() - days * DAY_MS);
  return new Date(`${at.toISOString().slice(0, 10)}T00:00:00.000Z`);
}

const utcDay = (at: Date): string => at.toISOString().slice(0, 10);

const mean = (values: number[]) => values.reduce((a, b) => a + b, 0) / values.length;

function expectationFor(rows: OpenEndedWindowRow[]): OpenEndedKpiExpectation {
  return {
    count: rows.length,
    errorRate: (rows.filter((r) => r.errored).length / rows.length) * 100,
    avgDuration: mean(rows.map((r) => r.durationMs)),
    totalCost: rows.length * COST_PER_SPAN,
  };
}

/**
 * Six traces in one fresh project, each with one priced LLM span and a thread
 * of its own: three inside the last seven days (two errored), two in the seven
 * days before that, and one whose UUIDv7 id embeds 2200-06-15.
 *
 * What the `kpi-cards` read must answer over a `past7days` window, for every
 * one of the three entity types:
 *
 *                 no interval_end   interval_end = now
 *   count              4 / prev 2         3 / prev 2
 *   error rate        50% / prev 0     66.7% / prev 0
 *   avg duration    450ms / prev 600  200ms / prev 600
 *   total cost       $24 / prev $12    $18 / prev $12
 *
 * Every current-period number differs between the two columns and every prior
 * one is identical, which is the whole shape of the behaviour: without a
 * requested end there is no upper id bound, while the prior period is still
 * sized from the server's own `now`. Note the error rate moves DOWN when the
 * far-future row is admitted — it is clean, so it pads the denominator — so a
 * card that merely got bigger cannot pass by accident.
 *
 * Deterministic despite naming a period. Every window is keyed on the instant
 * each row's UUIDv7 id embeds and this fixture mints all six, so the split
 * between the periods is placed by the seed rather than by when the suite runs.
 * See the note on SEED for the margins.
 */
export const test = baseTest.extend<OpenEndedWindowRowsFixtures>({
  openEndedWindowRows: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const rows: OpenEndedWindowRow[] = [];

    try {
      // The dated rows are backdated out to 9.5 days, so a reject-mode env
      // refuses the very first seed. Probed up front rather than caught on the
      // far-future write alone: without this the first ordinary row fails as an
      // opaque 400 that reads like a product bug.
      await skipUnlessBackdatedIdsAccepted(backendClient, project.name, 9.5 * DAY_MS);

      for (const seed of SEED) {
        const idMoment =
          seed.idAgeDays === undefined
            ? FAR_FUTURE_MOMENT
            : new Date(Date.now() - seed.idAgeDays * DAY_MS);
        const startTime = new Date(Date.now() - seed.startAgeDays * DAY_MS);
        const endTime = new Date(startTime.getTime() + seed.ms);

        const traceId = uuid7(idMoment);
        const threadId = `${testNamespace}-${seed.label}-thread`;

        try {
          await backendClient.createTraceWithSource({
            id: traceId,
            projectName: project.name,
            name: `${testNamespace}-${seed.label}`,
            // Mandatory: the Logs page filters every read on `source = sdk`, so
            // a row seeded without it is written, readable by id, and absent
            // from the table and the cards a UI assertion is about to read.
            source: 'sdk',
            threadId,
            input: `${testNamespace} input ${seed.label}`,
            output: `${testNamespace} output ${seed.label}`,
            startTime,
            endTime,
            ...(seed.errored ? { errorInfo: ERROR_INFO } : {}),
          });
        } catch (err) {
          // Reject-mode UUID validation refuses exactly the far-future id this
          // fixture exists to seed. It ships disabled and the mode is not
          // readable from the client, so it is detected from the rejection
          // rather than checked up front.
          if (isUuidWindowRejection(err)) baseTest.skip(true, UUID_VALIDATION_SKIP_REASON);
          throw err;
        }

        // One millisecond after the trace, so the span's own id sits in the same
        // window and the same bucket. Span reads range on the span id, not on
        // its trace's.
        const spanId = uuid7(new Date(idMoment.getTime() + 1));
        const span: SpanBatchSeed = {
          id: spanId,
          traceId,
          name: `${testNamespace}-${seed.label}-span`,
          type: 'llm',
          startTime,
          endTime,
          model: COST_MODEL,
          provider: COST_PROVIDER,
          // No `total_cost`: the server prices the span, so the Total cost card
          // reads the backend's own arithmetic rather than ours.
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
          },
          ...(seed.errored ? { errorInfo: ERROR_INFO } : {}),
        };
        await backendClient.createSpansBatch({ projectName: project.name, spans: [span] });

        rows.push({
          group: seed.group,
          traceId,
          spanId,
          threadId,
          // Read back out of the minted id rather than trusted from the seed:
          // this is the instant the backend itself buckets on.
          idMoment: uuid7Moment(traceId),
          idDay: utcDay(uuid7Moment(traceId)),
          startDay: utcDay(startTime),
          durationMs: seed.ms,
          errored: seed.errored,
        });
      }

      const current = rows.filter((r) => r.group === 'current');
      const previous = rows.filter((r) => r.group === 'previous');
      const farFuture = rows.find((r) => r.group === 'far-future');
      if (!farFuture || current.length !== 3 || previous.length !== 2) {
        throw new Error(
          `[openEndedWindowRows fixture] seeded ${current.length} current, ` +
            `${previous.length} previous and ${farFuture ? 1 : 0} far-future rows`,
        );
      }

      // The seed's own discriminators, asserted rather than assumed: a spec
      // whose subject is "the id decides, not start_time" and "today's bucket
      // belongs to the far-future row alone" rests on both of these, and either
      // could quietly stop holding if the ages above were edited.
      const seededDays = new Set([...current, ...previous].map((r) => r.idDay));
      if (seededDays.size !== current.length + previous.length) {
        throw new Error(
          `[openEndedWindowRows fixture] two dated rows share a UTC day: ` +
            `${[...current, ...previous].map((r) => r.idDay).join(', ')}`,
        );
      }
      const today = utcDay(new Date());
      if (seededDays.has(today)) {
        throw new Error(
          `[openEndedWindowRows fixture] a dated row landed on today (${today}), so ` +
            `today's bucket would no longer belong to the far-future row alone`,
        );
      }
      if (farFuture.startDay === farFuture.idDay || current.some((r) => r.idDay === farFuture.startDay)) {
        throw new Error(
          `[openEndedWindowRows fixture] the far-future row's start_time day ` +
            `(${farFuture.startDay}) must differ from its id day (${farFuture.idDay}) and ` +
            `from every current row's`,
        );
      }

      const ref: OpenEndedWindowRowsRef = {
        rows: [...rows],
        farFuture,
        current,
        previous,
        openEnded: expectationFor([...current, farFuture]),
        explicitEnd: expectationFor(current),
        previousPeriod: expectationFor(previous),
        intervalStart: utcStartOfDayAgo(6),
        timeRangePreset: 'past7days',
      };

      await testInfo.attach('opik.openEndedWindowRows', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && rows.length > 0) {
        await deleteSeeded(
          backendClient,
          rows.map((r) => r.traceId),
        );
      }
    }
  },
});

/**
 * Deleting the project would not take these with it, and `global-teardown`'s
 * run-prefix sweep does not know about traces at all — so they are deleted
 * explicitly, and never by throwing: a cleanup failure must not replace the
 * test's own error. The spans and the threads go with their traces.
 */
async function deleteSeeded(backendClient: BackendClient, traceIds: string[]): Promise<void> {
  try {
    await backendClient.deleteTraces(traceIds);
    return;
  } catch (err) {
    console.warn('[openEndedWindowRows fixture] batch delete failed, retrying per id:', err);
  }
  for (const id of traceIds) {
    try {
      await backendClient.deleteTraces([id]);
    } catch (err) {
      console.warn(`[openEndedWindowRows fixture] could not delete ${id}:`, err);
    }
  }
}

export { expect } from './experiment-message-panel.fixture';
