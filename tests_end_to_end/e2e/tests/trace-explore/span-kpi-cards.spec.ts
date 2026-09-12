import { test, expect } from '@e2e/fixtures';
import type { KpiCardStat } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The four KPI cards above the Logs table in its Spans view, and the
 * period-over-period deltas beside them (OPIK-7791).
 *
 * Only the count card is read by anything in the estate today, and
 * `logs.page.ts` parses deliberately around the delta so it never picks one up.
 * Error rate, Avg duration and Total cost — and all four deltas — are asserted
 * by nothing. Every one of them fails silently: a card showing the wrong cost
 * or the wrong average renders exactly as convincingly as a right one, and the
 * delta is the number a user actually reacts to.
 *
 * Both surfaces, because they can disagree. The API half pins the two periods
 * to exact numbers — the previous period is not readable off the page at all,
 * only the delta computed from it. The UI half then proves the page renders
 * those numbers and derives the deltas from them, which is the part a payload
 * assertion cannot reach.
 *
 * Deterministic despite naming a period. Both windows are keyed on the instant
 * each span's UUIDv7 id embeds, and the fixture mints every id — so the split
 * between "this week" and "last week" is placed by the seed, not by when the
 * suite happens to run. See the fixture for the margins.
 */

/**
 * The rendered forms of the seeded current-period numbers.
 *
 * Spelled out rather than recomputed here: re-implementing `formatDuration` and
 * `formatCost` in the spec would make it agree with itself whatever those
 * functions did. Each literal follows from the seed and the front end's
 * documented formatting:
 *
 *   count         8                               -> "8"
 *   error rate    2 of 8 spans errored            -> "25%"
 *   avg duration  mean(100…800ms) = 450ms         -> "0.5s"   (rounded to 1dp)
 *   total cost    8 spans x $6.00                 -> "$48"
 */
const EXPECTED_CARD_VALUES: Record<string, string> = {
  count: '8',
  errors: '25%',
  avg_duration: '0.5s',
  total_cost: '$48',
};

/**
 * The rendered deltas against the prior period (4 clean 200ms spans, $24).
 *
 * Distinct on purpose — a card wired to the wrong metric could not land on the
 * right delta by coincidence:
 *
 *   count         4 -> 8        +100%
 *   error rate    0% -> 25%     +25pp   percentage points, not percent
 *   avg duration  200 -> 450ms  +125%
 *   total cost    $24 -> $48    +100%
 *
 * The unit on the error-rate card is the point of that row: a delta between two
 * percentages is a difference in percentage points, and rendering it as "%"
 * would claim the error rate rose by a quarter rather than by 25 points.
 */
const EXPECTED_CARD_DELTAS: Record<string, string> = {
  count: '100%',
  errors: '25pp',
  avg_duration: '125%',
  total_cost: '100%',
};

/**
 * One card's two values, asserted present.
 *
 * Both are nullable in the response, and a caller that defaulted a missing one
 * to 0 would compare two absences and call it agreement — so the absence is a
 * failure here rather than a silent pass.
 */
function kpi(stats: KpiCardStat[], type: KpiCardStat['type']): { current: number; previous: number } {
  const stat = stats.find((s) => s.type === type);
  expect(stat, `the answer carries a "${type}" card`).toBeDefined();
  expect(stat!.currentValue, `"${type}" current_value is present`).not.toBeNull();
  expect(stat!.previousValue, `"${type}" previous_value is present`).not.toBeNull();
  return { current: stat!.currentValue!, previous: stat!.previousValue! };
}

test.describe('Span KPI cards — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /**
   * The deltas are hidden below 240px per card (`getCardMode`), and there are
   * four cards plus the assistant sidebar. At the suite's default width they do
   * not render at all — which is by design, not a missing delta.
   */
  test.use({ viewport: { width: 2200, height: 1000 } });

  /** The fixture writes two periods of spans and the read polls for ingestion. */
  test.slow();

  test(
    'the Spans KPI cards report both periods exactly, and the page renders them with their deltas',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ spanKpiSpans, project, backendClient, page }) => {
      const read = () =>
        backendClient.projectKpiCards({
          projectId: project.id,
          entityType: 'spans',
          // The same window the Logs page derives from its `past7days` preset.
          // The page sends no interval_end for a preset ending today, so this
          // does not either — the backend uses its own `now` for both.
          intervalStart: spanKpiSpans.intervalStart,
        });

      await test.step('The seeded spans are queryable', async () => {
        // Ingestion is eventually consistent; poll the count rather than sleep,
        // so the spec neither flakes nor waits longer than it must.
        await expect
          .poll(
            async () => {
              const { status, stats } = await read();
              if (status !== 200) return -1;
              return stats.find((s) => s.type === 'count')?.currentValue ?? -1;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(spanKpiSpans.current.count);
      });

      await test.step('Every card carries the seeded number for both periods', async () => {
        const { status, message, stats } = await read();
        expect(status, `kpi-cards rejected with: ${message}`).toBe(200);
        // The whole answer, not only the cards being compared: an extra card
        // would mean the endpoint served a shape the page does not expect.
        expect(
          stats.map((s) => s.type).sort(),
          'one card per metric, and nothing else',
        ).toEqual(['avg_duration', 'count', 'errors', 'total_cost']);

        const count = kpi(stats, 'count');
        expect(count.current, 'spans this period').toBe(spanKpiSpans.current.count);
        expect(count.previous, 'spans last period').toBe(spanKpiSpans.previous.count);

        const errors = kpi(stats, 'errors');
        // A percentage in [0, 100], not a count — the card is "Error rate".
        expect(errors.current, 'error rate this period').toBe(spanKpiSpans.current.errorRate);
        expect(errors.previous, 'error rate last period').toBe(spanKpiSpans.previous.errorRate);

        const duration = kpi(stats, 'avg_duration');
        expect(duration.current, 'average duration this period (ms)').toBeCloseTo(
          spanKpiSpans.current.avgDuration,
          0,
        );
        expect(duration.previous, 'average duration last period (ms)').toBeCloseTo(
          spanKpiSpans.previous.avgDuration,
          0,
        );

        const cost = kpi(stats, 'total_cost');
        // Priced server-side from the span's usage — the seed sends no
        // total_cost — so this is the backend's own arithmetic, not ours.
        expect(cost.current, 'total cost this period').toBeCloseTo(
          spanKpiSpans.current.totalCost,
          2,
        );
        expect(cost.previous, 'total cost last period').toBeCloseTo(
          spanKpiSpans.previous.totalCost,
          2,
        );
      });

      const logs = new LogsPage(page);

      await test.step('Open the Logs page in its Spans view over the same window', async () => {
        await logs.gotoSpans(project.id, { timeRange: spanKpiSpans.timeRangePreset });
        // Gates on the count card rather than on the table: the cards are a
        // separate query, and reading the other three before this one resolves
        // would compare against the "N/A" placeholder.
        await expect(
          logs.metricsCardValue('count'),
          'the Spans count card resolves to the seeded count',
        ).toHaveText(EXPECTED_CARD_VALUES.count, { timeout: 60_000 });
      });

      await test.step('Each card renders the value the API reported', async () => {
        for (const [type, expected] of Object.entries(EXPECTED_CARD_VALUES)) {
          await expect(logs.metricsCardValue(type), `the "${type}" card`).toHaveText(expected);
        }
      });

      await test.step('Each card renders its period-over-period delta', async () => {
        for (const [type, expected] of Object.entries(EXPECTED_CARD_DELTAS)) {
          expect(await logs.readMetricsCardDelta(type), `the "${type}" card delta`).toBe(expected);
        }
      });
    },
  );
});
