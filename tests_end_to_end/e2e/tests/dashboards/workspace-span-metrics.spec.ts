import { test, expect } from '@e2e/fixtures';
import type { MetricInterval, MetricSeries } from '@e2e/core/backend';

/**
 * The workspace span-metrics read behind a dashboard "Span token usage" Time
 * series widget — `POST /v1/private/workspaces/metrics/spans` (OPIK-7923).
 *
 * Why this endpoint and this configuration: the widget only calls it when it is
 * scoped to *All projects in the workspace*. Pick a specific project and the
 * widget calls `/projects/{id}/metrics` instead, and never reaches the code
 * OPIK-7923 changed. So the request here deliberately omits `project_ids`.
 *
 * OPIK-7923 routed this endpoint's filters through `FiltersFactory`. The
 * failure that matters is quiet: the front end sends filter objects carrying
 * `id`, `type` and an empty `key` alongside the fields the backend needs, and a
 * validator that rejected any of those would turn a legitimate widget filter
 * into a 400 — which the widget renders as a blank chart, not as an error. So
 * the payloads below are shaped exactly as the front end emits them, extra
 * fields and all, and every assertion is on a number rather than on a 200.
 *
 * SCOPE — this spec asserts the widget's *data contract*, not its rendering.
 * It does not create a dashboard or a widget and does not open a browser. The
 * dashboards UI carries no `data-testid` anywhere, so a rendering assertion
 * would need front-end changes shipped in the same commit, which could not be
 * verified against a deployed environment before review. The numbers a chart
 * would plot are asserted here against the seed; that a chart plots them is
 * still uncovered.
 */

/**
 * A filter object exactly as `processFiltersArray` emits it — including the
 * `id` the table stamps on each row and the empty `key` that non-dictionary
 * fields carry. Those two fields are the reason this is worth asserting: they
 * are meaningless to the backend but must not be rejected by it.
 */
const uiFilter = (
  field: string,
  operator: string,
  value: string,
  type = 'string',
): Record<string, unknown> => ({
  id: `${field}-${operator}`,
  field,
  type,
  operator,
  key: '',
  value,
});

/**
 * The window/interval pairs the dashboard date-range control produces.
 * `calculateIntervalType` buckets <=3 days as HOURLY and <=30 as DAILY, so
 * these four presets straddle the boundary in both directions.
 */
const DATE_RANGES: Array<{ label: string; days: number; interval: MetricInterval }> = [
  { label: 'past30days', days: 30, interval: 'DAILY' },
  { label: 'past7days', days: 7, interval: 'DAILY' },
  { label: 'past3days', days: 3, interval: 'HOURLY' },
  { label: 'past24hours', days: 1, interval: 'HOURLY' },
];

/**
 * Sum of one named usage series across the window.
 *
 * The series must be there: a missing `total_tokens` means the aggregation
 * returned nothing, which is a failure, not a zero. Individual points may be
 * null — `WITH FILL` pads empty buckets — and those genuinely are zero.
 */
const seriesTotal = (series: MetricSeries[], name: string): number => {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();
  return found!.points.reduce((acc, p) => acc + (p.value ?? 0), 0);
};

/** Same sum, but for a query expected to match nothing, where absent is zero. */
const seriesTotalOrZero = (series: MetricSeries[], name: string): number => {
  const found = series.find((s) => s.name === name);
  if (!found) return 0;
  return found.points.reduce((acc, p) => acc + (p.value ?? 0), 0);
};

const daysAgo = (days: number): Date => new Date(Date.now() - days * 24 * 60 * 60 * 1000);

test.describe('Dashboard span metrics — data contract', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  /**
   * Span ingestion is eventually consistent and the polls below are allowed two
   * minutes, which the default budget cannot contain. Declared on the describe
   * so it also covers fixture setup.
   */
  test.slow();

  test(
    'the widget filter payload is accepted and aggregates to the seeded token totals',
    { tag: ['@cap:dashboards.widget-filters'] },
    async ({ tokenUsageSpans, backendClient }) => {
      const { spanNamePrefix, totals, totalTokensByProvider } = tokenUsageSpans;

      const nameFilter = uiFilter('name', 'contains', spanNamePrefix);
      const query = (filters: Array<Record<string, unknown>>) =>
        backendClient.workspaceSpanMetric({
          metricType: 'SPAN_TOKEN_USAGE',
          interval: 'DAILY',
          intervalStart: daysAgo(7),
          intervalEnd: new Date(),
          filters,
        });

      await test.step('The seeded spans are queryable and sum to the seeded total', async () => {
        // Span ingestion is eventually consistent, so the first read is polled.
        await expect
          .poll(
            async () => {
              const { status, series } = await query([nameFilter]);
              return status === 200 ? seriesTotalOrZero(series, 'total_tokens') : -1;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(totals.totalTokens);
      });

      await test.step("The front end's own filter payload is accepted, extra fields and all", async () => {
        const { status, message, series } = await query([nameFilter]);
        expect(status, `filtered read rejected with: ${message}`).toBe(200);
        expect(series.length, 'the answer carries at least one usage series').toBeGreaterThan(0);
      });

      await test.step('Every usage series matches the seed', async () => {
        const { series } = await query([nameFilter]);
        expect(seriesTotal(series, 'total_tokens'), 'total_tokens').toBe(totals.totalTokens);
        expect(seriesTotal(series, 'prompt_tokens'), 'prompt_tokens').toBe(totals.promptTokens);
        expect(seriesTotal(series, 'completion_tokens'), 'completion_tokens')
          .toBe(totals.completionTokens);
      });

      await test.step('A provider filter narrows the aggregation to that provider', async () => {
        for (const [provider, expected] of Object.entries(totalTokensByProvider)) {
          const { status, series } = await query([
            nameFilter,
            uiFilter('provider', '=', provider),
          ]);
          expect(status, `provider=${provider}`).toBe(200);
          expect(seriesTotal(series, 'total_tokens'), `total_tokens for provider ${provider}`)
            .toBe(expected);
        }
        // The seed splits unevenly across the two providers, so agreeing with
        // the grand total would mean the provider filter did nothing.
        const providerSum = Object.values(totalTokensByProvider).reduce((a, b) => a + b, 0);
        expect(providerSum, 'the per-provider totals account for the whole seed')
          .toBe(totals.totalTokens);
      });

      await test.step('A model filter narrows the aggregation to that model family', async () => {
        const { status, series } = await query([
          nameFilter,
          uiFilter('model', 'starts_with', 'gpt'),
        ]);
        expect(status, 'model starts_with gpt').toBe(200);
        expect(seriesTotal(series, 'total_tokens'), 'total_tokens for the gpt models')
          .toBe(totalTokensByProvider.openai);
      });

      await test.step('A filter that matches nothing aggregates to nothing', async () => {
        // Without this, every assertion above would also hold for an endpoint
        // that ignored the filters and happened to see only these spans.
        const { status, series } = await query([
          uiFilter('name', 'contains', `${spanNamePrefix}-no-such-span`),
        ]);
        expect(status, 'a filter matching no span').toBe(200);
        expect(seriesTotalOrZero(series, 'total_tokens'), 'total_tokens for an empty match').toBe(0);
      });
    },
  );

  test(
    'every dashboard date range is served at the interval the range implies',
    { tag: ['@cap:dashboards.metric-date-range'] },
    async ({ tokenUsageSpans, backendClient }) => {
      const { spanNamePrefix, totals } = tokenUsageSpans;
      const nameFilter = uiFilter('name', 'contains', spanNamePrefix);

      await test.step('The seeded spans are queryable', async () => {
        await expect
          .poll(
            async () => {
              const { status, series } = await backendClient.workspaceSpanMetric({
                metricType: 'SPAN_TOKEN_USAGE',
                interval: 'DAILY',
                intervalStart: daysAgo(7),
                intervalEnd: new Date(),
                filters: [nameFilter],
              });
              return status === 200 ? seriesTotalOrZero(series, 'total_tokens') : -1;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(totals.totalTokens);
      });

      for (const range of DATE_RANGES) {
        await test.step(`${range.label} is served at ${range.interval} and carries the whole seed`, async () => {
          const { status, message, series } = await backendClient.workspaceSpanMetric({
            metricType: 'SPAN_TOKEN_USAGE',
            interval: range.interval,
            intervalStart: daysAgo(range.days),
            intervalEnd: new Date(),
            filters: [nameFilter],
          });
          expect(status, `${range.label} rejected with: ${message}`).toBe(200);
          // Every range ends now and the spans were seeded moments ago, so each
          // window contains all of them however it buckets them.
          expect(seriesTotal(series, 'total_tokens'), `total_tokens over ${range.label}`)
            .toBe(totals.totalTokens);
        });
      }
    },
  );

  test(
    'a filter the backend cannot serve is refused with a 400 that names it',
    { tag: ['@cap:dashboards.widget-filters'] },
    async ({ backendClient }) => {
      await test.step("An operator no LIST field supports is rejected, not run", async () => {
        const { status, message } = await backendClient.workspaceSpanMetric({
          metricType: 'SPAN_TOKEN_USAGE',
          interval: 'DAILY',
          intervalStart: daysAgo(7),
          intervalEnd: new Date(),
          filters: [uiFilter('tags', '>', 'x', 'list')],
        });
        // 400 specifically: a 500 here is the failure mode OPIK-7923 set out to
        // remove, and a 200 would mean the operator was silently dropped.
        expect(status, `unsupported operator answered: ${message}`).toBe(400);
        expect(message, 'the rejection names the field').toContain('tags');
        expect(message, 'the rejection names the operator').toContain('>');
      });
    },
  );
});
