import { expect } from '@playwright/test';
import { uuid7, type BackendClient, type MetricSeries, type SpanBatchSeed } from '../core/backend';

/**
 * The vocabulary the two cost-bucketing seeds share (OPIK-8335): one priced LLM
 * span, the three days they place spend on, and how a bucketed cost series is
 * read back.
 *
 * `span-cost-buckets` and `thread-cost-buckets` seed different entities — plain
 * traces versus threads — but both exist to prove the same thing: that a cost
 * series is bucketed on the entity it names rather than on some neighbouring
 * timestamp. That only works if the days and the price are the same fact in both,
 * so they live here rather than being restated twice with a chance to drift.
 */

/**
 * `claude-haiku-4.5-20251001` at 1M prompt + 1M completion tokens prices at
 * $1/M in + $5/M out = exactly $6.00 from the shipped price table — the same
 * vector `span-cost-resolution.spec.ts` and `span-kpi-cards.fixture.ts` pin, so
 * if the table moves they all fail together and say so.
 *
 * A million of each also lifts every per-span cost clear of `formatCost`'s
 * `<$0.01` floor, so a rendered card reads an exact dollar amount rather than a
 * placeholder.
 */
export const COST_MODEL = 'claude-haiku-4.5-20251001';
export const COST_PROVIDER = 'anthropic';
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/** USD per span seeded through `costedSpan`. */
export const COST_PER_SPAN_USD = 6;

/**
 * The three days the seeds place spend on, as whole days back from **noon UTC**.
 *
 * Noon, and whole days, for two independent reasons:
 *
 *   * the bucket is `toStartOfInterval(_, INTERVAL 1 DAY)` in UTC, so an instant
 *     placed near midnight could land either side of a boundary — noon is twelve
 *     hours clear of both;
 *   * whole-day offsets from one anchor guarantee the three fall on three
 *     *different* UTC dates whatever hour the suite runs at, which is the whole
 *     point: a bucketing assertion whose buckets can coincide asserts nothing.
 *
 * The two current-period days sit inside the Logs page's `past7days` window,
 * which opens at UTC midnight six days back — so `EARLY` is at least 1.5 days
 * inside it at every hour of the day. `PREVIOUS` sits in the period before it:
 * the KPI cards derive that period by shifting the current one back by its own
 * length, putting it 12–14 days back, and 10.5 days is comfortably inside.
 */
export const DAYS_BACK_EARLY = 4;
export const DAYS_BACK_LATE = 1;
export const DAYS_BACK_PREVIOUS = 10;

/** Noon UTC, `days` whole days back from today's UTC date. */
export function utcNoonDaysBack(days: number): Date {
  const now = new Date();
  return new Date(
    Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() - days, 12, 0, 0, 0),
  );
}

/**
 * The Logs page's `past7days` preset, as the two values a seed has to agree with
 * it on: the `time_range` key to open the page with, and the `interval_start`
 * the page derives from it — UTC start of day, six days back.
 *
 * One fact in one place, because the two are only correct together. A seed that
 * opened the page on `past7days` while sending the API a window of a different
 * length would compare the chart to a series nobody drew, and the KPI cards
 * derive their previous period from this length too — so a drift here moves the
 * previous period as well, silently.
 */
export const PAST_7_DAYS_PRESET = 'past7days';
const PAST_7_DAYS_BACK = 6;
const DAY_MS = 24 * 60 * 60 * 1000;

/** The `interval_start` `past7days` implies: UTC start of day, six days back. */
export function past7DaysIntervalStart(): Date {
  const at = new Date(Date.now() - PAST_7_DAYS_BACK * DAY_MS);
  return new Date(`${at.toISOString().slice(0, 10)}T00:00:00.000Z`);
}

/** The UTC date an instant falls on, as `YYYY-MM-DD` — a daily bucket's key. */
export function utcDayOf(moment: Date | string): string {
  const at = typeof moment === 'string' ? new Date(moment) : moment;
  if (Number.isNaN(at.getTime())) {
    throw new Error(`[cost-buckets] '${String(moment)}' is not a readable instant`);
  }
  return at.toISOString().slice(0, 10);
}

/**
 * One LLM span the backend prices at exactly `COST_PER_SPAN_USD`.
 *
 * `moment` fixes both the id and `start_time`. The id is the load-bearing one —
 * `span_time` is `UUIDv7ToDateTime(id)`, so that is what a SPAN_COST bucket is
 * keyed on — but they are minted together so the row a human opens in the table
 * agrees with the bar the chart drew for it.
 *
 * Deliberately no `total_cost`: the backend prices the span from `usage` and the
 * model, so the number every assertion below compares against is the product's
 * own arithmetic rather than a figure the seed asserted into existence.
 */
export function costedSpan(args: {
  id?: string;
  traceId: string;
  name: string;
  moment: Date;
  durationMs?: number;
}): SpanBatchSeed {
  return {
    id: args.id ?? uuid7(args.moment),
    traceId: args.traceId,
    name: args.name,
    type: 'llm',
    startTime: args.moment,
    endTime: new Date(args.moment.getTime() + (args.durationMs ?? 1_000)),
    model: COST_MODEL,
    provider: COST_PROVIDER,
    usage: {
      prompt_tokens: PROMPT_TOKENS,
      completion_tokens: COMPLETION_TOKENS,
      total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
    },
  };
}

/**
 * Delete the traces a seed wrote — the whole batch first, then one id at a time.
 *
 * Explicit, because nothing else sweeps these: deleting the project does not
 * take its traces with it, and `global-teardown`'s run-prefix sweep does not
 * know about traces at all. The batch is a single request, so one bad id loses
 * every other trace with it — hence the per-id retry, which is the same shape
 * `id-aged-traces` and `far-future-error-traces`, the estate's other
 * backdated-trace seeds, already use. Never throws: a cleanup failure must not
 * replace the test's own error.
 */
export async function deleteSeededTraces(
  backendClient: BackendClient,
  ids: string[],
  fixtureLabel: string,
): Promise<void> {
  try {
    await backendClient.deleteTraces(ids);
    return;
  } catch (err) {
    console.warn(`[${fixtureLabel} fixture] batch trace delete failed, retrying per id:`, err);
  }

  for (const id of ids) {
    try {
      await backendClient.deleteTraces([id]);
    } catch (err) {
      console.warn(`[${fixtureLabel} fixture] could not delete ${id}:`, err);
    }
  }
}

/**
 * One named series of a metrics answer, as `{ 'YYYY-MM-DD': value }` over the
 * buckets that actually carry spend.
 *
 * The empty buckets are dropped rather than compared: `WITH FILL` pads the whole
 * window, so their number depends on the hour the suite runs at. What does not
 * depend on it is *which* days carry a value and how much — so the caller gets a
 * map it can compare whole, and a day that should be empty fails by being
 * present rather than by being looked up and found missing.
 *
 * The series must be there. An absent one means the aggregation returned
 * nothing at all, which is a failure rather than an empty answer — and a caller
 * that defaulted it to `{}` would then agree with an expectation of "no spend
 * anywhere" for the wrong reason.
 */
export function bucketsByDay(series: MetricSeries[], name: string): Record<string, number> {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();

  const byDay: Record<string, number> = {};
  for (const point of found!.points) {
    if (point.value === null || point.value === 0) continue;
    const day = utcDayOf(point.time);
    byDay[day] = (byDay[day] ?? 0) + point.value;
  }
  return byDay;
}

/** Everything a `bucketsByDay` map adds up to. */
export function bucketTotal(byDay: Record<string, number>): number {
  return Object.values(byDay).reduce((acc, value) => acc + value, 0);
}

/**
 * Assert a bucketed series is exactly the expected one — the same days, each
 * carrying the same value.
 *
 * The day set is compared first and as a whole, because the leak is the
 * interesting failure: a series that carries the right value on the right day
 * AND a stray value on a day nothing happened has still been bucketed wrongly,
 * and looking each expected day up one at a time would never notice. Values are
 * compared to the cent; ClickHouse answers costs as decimals and the exact
 * float that reaches JSON is not part of the contract.
 */
export function expectBucketsByDay(
  actual: Record<string, number>,
  expected: Record<string, number>,
  what: string,
): void {
  expect(Object.keys(actual).sort(), `${what}: the days carrying a value`).toEqual(
    Object.keys(expected).sort(),
  );
  for (const [day, value] of Object.entries(expected)) {
    expect(actual[day], `${what}: the value bucketed at ${day}`).toBeCloseTo(value, 2);
  }
}
