import { test, expect } from '@e2e/fixtures';
import type { Locator } from '@playwright/test';
import type { OpenEndedKpiExpectation } from '@e2e/fixtures';
import type { KpiCardStat } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The Logs page over an OPEN-ENDED window — the one every date-range preset
 * that ends today produces, which is every preset the control offers and the
 * default view (OPIK-8206).
 *
 * `calculateIntervalStartAndEnd` returns `intervalEnd: undefined` for such a
 * preset, so the page sends `interval_start` and no `interval_end` at all. That
 * is not the same request with a default filled in: on that branch the read has
 * no upper id bound, and a row whose UUIDv7 id instant lies in the future — a
 * client with a bad clock is all it takes — counts in the current period. With
 * an explicit end it does not. Before the release the cards and the chart
 * capped the window at `now` while the table below them did not, so the same
 * project showed rows in its table that its own count card denied.
 *
 * Why it is worth a spec of its own. The failure is silent: a count card that
 * disagrees with the footer beneath it renders exactly as convincingly as one
 * that agrees, and the prior-period column is not on screen at all — only the
 * delta computed from it, which is the number a user reacts to. And nothing in
 * the estate crosses this axis. `span-kpi-cards.spec.ts` sends no
 * `interval_end` but seeds only past rows, so the branch is never observable;
 * `spans-view-pagination.spec.ts` seeds a mid-2200 span and asserts the list
 * only, per its own comment.
 *
 * Both surfaces, because they can disagree, and that disagreement IS the bug
 * this covers. The API half pins both periods to exact numbers for all three
 * entity types, including the prior period, which cannot be read off the page.
 * The UI half then proves the page really takes this branch — the outgoing
 * requests carry no `interval_end` — and that the cards, their deltas and the
 * table footer report one population with the far-future row in it.
 *
 * Deterministic despite naming a period. Every window is keyed on the instant
 * each row's UUIDv7 id embeds and the fixture mints all six; the far-future id
 * is mid-2200, 174 years clear of any clock this could run on. See the fixture
 * for the margins.
 */

/** The entity types the Logs page's three tabs ask the KPI endpoint for. */
const ENTITY_TYPES = ['traces', 'spans', 'threads'] as const;
type EntityType = (typeof ENTITY_TYPES)[number];

/**
 * The cards the endpoint serves per entity type.
 *
 * Threads carry no error rate — a thread is not a thing that failed — and the
 * answer omits the card rather than zeroing it, which `MetricsSummary` mirrors
 * by filtering the card out of the row. Asserted as the WHOLE card set so an
 * extra or missing card is a failure rather than something a per-card lookup
 * would walk past.
 */
const CARDS_BY_ENTITY: Record<EntityType, string[]> = {
  traces: ['avg_duration', 'count', 'errors', 'total_cost'],
  spans: ['avg_duration', 'count', 'errors', 'total_cost'],
  threads: ['avg_duration', 'count', 'total_cost'],
};

/**
 * The rendered forms of the open-ended current period, which is what a preset
 * ending today puts on screen.
 *
 * Spelled out rather than recomputed: re-implementing `formatDuration` and
 * `formatCost` in the spec would make it agree with itself whatever those
 * functions did. Each literal follows from the seed and the front end's
 * documented formatting:
 *
 *   count         4 rows                            -> "4"
 *   error rate    2 of 4 errored                    -> "50%"
 *   avg duration  mean(100, 200, 300, 1200) = 450ms -> "0.5s"  (1dp)
 *   total cost    4 spans x $6.00                   -> "$24"
 */
const EXPECTED_CARD_VALUES: Record<string, string> = {
  count: '4',
  errors: '50%',
  avg_duration: '0.5s',
  total_cost: '$24',
};

/**
 * The rendered deltas against the prior period (2 clean 600ms rows, $12).
 *
 * Distinct on purpose — a card wired to the wrong metric could not land on the
 * right delta by coincidence:
 *
 *   count          2 -> 4         +100%
 *   error rate     0% -> 50%      +50pp   percentage points, not percent
 *   avg duration   600 -> 450ms    -25%   a DECREASE; the arrow is an icon,
 *                                         so only the magnitude is text
 *   total cost     $12 -> $24     +100%
 *
 * These are the numbers an open-ended read produces. With `interval_end = now`
 * the same seed renders +50%, +66.7pp, -66.7% and +50% — every one of them
 * different — so the deltas discriminate the two branches as sharply as the
 * values do.
 */
const EXPECTED_CARD_DELTAS: Record<string, string> = {
  count: '100%',
  errors: '50pp',
  avg_duration: '25%',
  total_cost: '100%',
};

/**
 * One card's two values, asserted present.
 *
 * Both are nullable in the response, and a caller that defaulted a missing one
 * to 0 would compare two absences and call it agreement — so an absence fails
 * here rather than passing silently.
 */
function kpi(stats: KpiCardStat[], type: string): { current: number; previous: number } {
  const stat = stats.find((s) => s.type === type);
  expect(stat, `the answer carries a "${type}" card`).toBeDefined();
  expect(stat!.currentValue, `"${type}" current_value is present`).not.toBeNull();
  expect(stat!.previousValue, `"${type}" previous_value is present`).not.toBeNull();
  return { current: stat!.currentValue!, previous: stat!.previousValue! };
}

/** Every card in one answer, compared against one period's expectation. */
function expectPeriod(
  stats: KpiCardStat[],
  entityType: EntityType,
  label: string,
  current: OpenEndedKpiExpectation,
  previous: OpenEndedKpiExpectation,
): void {
  const count = kpi(stats, 'count');
  expect(count.current, `${entityType} ${label}: count this period`).toBe(current.count);
  expect(count.previous, `${entityType} ${label}: count last period`).toBe(previous.count);

  if (CARDS_BY_ENTITY[entityType].includes('errors')) {
    const errors = kpi(stats, 'errors');
    // A percentage in [0, 100], not a count — the card is "Error rate". 66.6…
    // recurring under an explicit end, hence the tolerance rather than toBe.
    expect(errors.current, `${entityType} ${label}: error rate this period`).toBeCloseTo(
      current.errorRate,
      4,
    );
    expect(errors.previous, `${entityType} ${label}: error rate last period`).toBeCloseTo(
      previous.errorRate,
      4,
    );
  }

  const duration = kpi(stats, 'avg_duration');
  expect(duration.current, `${entityType} ${label}: avg duration this period (ms)`).toBeCloseTo(
    current.avgDuration,
    0,
  );
  expect(duration.previous, `${entityType} ${label}: avg duration last period (ms)`).toBeCloseTo(
    previous.avgDuration,
    0,
  );

  const cost = kpi(stats, 'total_cost');
  // Priced server-side from the span's usage — the seed sends no total_cost —
  // so this is the backend's own arithmetic rather than ours.
  expect(cost.current, `${entityType} ${label}: total cost this period`).toBeCloseTo(
    current.totalCost,
    2,
  );
  expect(cost.previous, `${entityType} ${label}: total cost last period`).toBeCloseTo(
    previous.totalCost,
    2,
  );
}

test.describe('Logs over an open-ended window — CUJ', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /**
   * The deltas are hidden below 240px per card (`getCardMode`), and there are
   * four cards plus the assistant sidebar. At the suite's default width they do
   * not render at all — which is by design, not a missing delta.
   */
  test.use({ viewport: { width: 2200, height: 1000 } });

  /** The fixture writes six traces and six spans, and the read polls for ingestion. */
  test.slow();

  test(
    'the KPI cards count a far-future id in the current period only when no interval_end is sent',
    { tag: ['@cap:traces.logs-open-ended-window'] },
    async ({ openEndedWindowRows, project, backendClient }) => {
      const read = (entityType: EntityType, withEnd: boolean) =>
        backendClient.projectKpiCards({
          projectId: project.id,
          entityType,
          intervalStart: openEndedWindowRows.intervalStart,
          // The branch under test. The page sends nothing here for a preset
          // ending today; `new Date()` is what it used to send.
          ...(withEnd ? { intervalEnd: new Date() } : {}),
        });

      await test.step('The seeded rows are queryable', async () => {
        // Ingestion is eventually consistent; poll the count rather than sleep,
        // so the spec neither flakes nor waits longer than it must.
        await expect
          .poll(
            async () => {
              const { status, stats } = await read('traces', false);
              if (status !== 200) return -1;
              return stats.find((s) => s.type === 'count')?.currentValue ?? -1;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(openEndedWindowRows.openEnded.count);
      });

      for (const entityType of ENTITY_TYPES) {
        await test.step(`${entityType}: an open-ended read counts the far-future row`, async () => {
          const { status, message, stats } = await read(entityType, false);
          expect(status, `kpi-cards (${entityType}) rejected with: ${message}`).toBe(200);
          // The whole answer, not only the cards being compared: an extra card
          // would mean the endpoint served a shape the page does not expect.
          expect(
            stats.map((s) => s.type).sort(),
            `one card per metric this entity type has, and nothing else`,
          ).toEqual(CARDS_BY_ENTITY[entityType]);

          expectPeriod(
            stats,
            entityType,
            'open-ended',
            openEndedWindowRows.openEnded,
            openEndedWindowRows.previousPeriod,
          );
        });

        await test.step(`${entityType}: the same read with interval_end = now does not`, async () => {
          const { status, message, stats } = await read(entityType, true);
          expect(status, `kpi-cards (${entityType}) rejected with: ${message}`).toBe(200);
          expect(stats.map((s) => s.type).sort(), 'the same card set either way').toEqual(
            CARDS_BY_ENTITY[entityType],
          );

          expectPeriod(
            stats,
            entityType,
            'interval_end = now',
            openEndedWindowRows.explicitEnd,
            // Identical to the open-ended read's, and that asymmetry is the
            // point: `now` still sizes the prior period while the current one
            // is open, so a far-future row can only ever inflate `current`.
            openEndedWindowRows.previousPeriod,
          );
        });
      }

      await test.step('Every current-period number really does differ between the two branches', async () => {
        // Without this the assertions above would also hold for a seed where
        // the two expectations happened to coincide — which would make the
        // whole contrast vacuous while every step stayed green.
        const { openEnded, explicitEnd, previousPeriod } = openEndedWindowRows;
        expect(openEnded.count, 'count').not.toBe(explicitEnd.count);
        expect(openEnded.errorRate, 'error rate').not.toBe(explicitEnd.errorRate);
        expect(openEnded.avgDuration, 'avg duration').not.toBe(explicitEnd.avgDuration);
        expect(openEnded.totalCost, 'total cost').not.toBe(explicitEnd.totalCost);
        // And the prior period differs from both, so a card reading the wrong
        // period cannot satisfy either column.
        expect(previousPeriod.count, 'prior count').not.toBe(openEnded.count);
        expect(previousPeriod.count, 'prior count').not.toBe(explicitEnd.count);
      });
    },
  );

  test(
    'the Logs page sends no interval_end on a preset ending today, and its cards agree with its table',
    { tag: ['@cap:traces.logs-open-ended-window'] },
    async ({ openEndedWindowRows, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { farFuture, timeRangePreset, openEnded } = openEndedWindowRows;

      await test.step('The seeded rows are queryable before the page is opened', async () => {
        // Otherwise a slow ingest renders "N/A" cards and this fails as a UI
        // defect, which it would not be.
        await expect
          .poll(
            async () => {
              const { status, stats } = await backendClient.projectKpiCards({
                projectId: project.id,
                entityType: 'traces',
                intervalStart: openEndedWindowRows.intervalStart,
              });
              if (status !== 200) return -1;
              return stats.find((s) => s.type === 'count')?.currentValue ?? -1;
            },
            { timeout: 120_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toBe(openEnded.count);
      });

      /** The two project-scoped reads the Logs page makes for one tab. */
      const isPath = (url: string, suffix: string) => {
        const { pathname } = new URL(url);
        return (
          pathname === `/opik/api/v1/private/projects/${project.id}/${suffix}` ||
          pathname === `/api/v1/private/projects/${project.id}/${suffix}`
        );
      };

      const tabs: Array<{
        entityType: EntityType;
        open: () => Promise<void>;
        row: () => Locator;
      }> = [
        {
          entityType: 'traces',
          open: () => logs.gotoTraces(project.id, { timeRange: timeRangePreset }),
          row: () => logs.traceRow(farFuture.traceId),
        },
        {
          entityType: 'spans',
          open: () => logs.gotoSpans(project.id, { timeRange: timeRangePreset }),
          row: () => logs.spanRow(farFuture.spanId),
        },
        {
          entityType: 'threads',
          open: () => logs.gotoThreads(project.id, { timeRange: timeRangePreset }),
          row: () => logs.threadRow(farFuture.threadId),
        },
      ];

      for (const tab of tabs) {
        // Armed before the navigation: both requests go out as the tab mounts.
        const kpiRequest = page.waitForRequest((r) => isPath(r.url(), 'kpi-cards'), {
          timeout: 60_000,
        });
        const chartRequest = page.waitForRequest((r) => isPath(r.url(), 'metrics'), {
          timeout: 60_000,
        });

        await test.step(`Open Logs on the ${tab.entityType} tab over ${timeRangePreset}`, async () => {
          await tab.open();
          // Read from the toggle rather than assumed: `logsType` is persisted
          // per project in localStorage, and a bare /logs resolves to Threads —
          // so a spec that inherited it could silently be driving another table.
          expect(await logs.activeLogsTab(), 'the active entity tab').toBe(tab.entityType);
          // Gates on the count card rather than on the table: the cards are a
          // separate query, and reading the other three before this one resolves
          // would compare against the "N/A" placeholder.
          await expect(
            logs.metricsCardValue('count'),
            `the ${tab.entityType} count card resolves to the open-ended population`,
          ).toHaveText(EXPECTED_CARD_VALUES.count, { timeout: 60_000 });
        });

        await test.step(`${tab.entityType}: both reads go out with no interval_end`, async () => {
          const kpiBody = JSON.parse((await kpiRequest).postData() ?? '{}') as Record<
            string,
            unknown
          >;
          expect(kpiBody.entity_type, 'the KPI read names this tab').toBe(tab.entityType);
          expect(kpiBody.interval_start, 'the KPI read is windowed').toEqual(expect.any(String));
          // The whole point of the branch. `toBeUndefined` rather than a
          // falsy check: an `interval_end: null` would be a different request.
          expect(kpiBody, 'the KPI read sent no interval_end').not.toHaveProperty('interval_end');

          // The chart's own read, and the one front-end line the release
          // changed: `MetricsSummary` passed `chartEnd ?? dayjs()` before it,
          // so the chart capped at now even when the cards did not.
          const chartBody = JSON.parse((await chartRequest).postData() ?? '{}') as Record<
            string,
            unknown
          >;
          expect(chartBody.interval_start, 'the chart read is windowed').toEqual(
            expect.any(String),
          );
          expect(chartBody, 'the chart read sent no interval_end').not.toHaveProperty(
            'interval_end',
          );
        });

        await test.step(`${tab.entityType}: every card renders the open-ended value`, async () => {
          for (const type of CARDS_BY_ENTITY[tab.entityType]) {
            await expect(logs.metricsCardValue(type), `the "${type}" card`).toHaveText(
              EXPECTED_CARD_VALUES[type],
            );
          }
          if (!CARDS_BY_ENTITY[tab.entityType].includes('errors')) {
            // Stated rather than left out: the threads row is three cards wide
            // because a thread has no error rate, and a fourth appearing there
            // would mean the page started rendering one.
            await expect(
              logs.metricsCardValue('errors'),
              'the threads tab renders no Error rate card',
            ).toHaveCount(0);
          }
        });

        await test.step(`${tab.entityType}: every card renders its period-over-period delta`, async () => {
          for (const type of CARDS_BY_ENTITY[tab.entityType]) {
            expect(await logs.readMetricsCardDelta(type), `the "${type}" card delta`).toBe(
              EXPECTED_CARD_DELTAS[type],
            );
          }
        });

        await test.step(`${tab.entityType}: the table reports the same population, far-future row included`, async () => {
          // The agreement is the regression. A count card that disagrees with
          // the footer beneath it reads as healthy, so asserting the card alone
          // would have passed before the fix as convincingly as after it.
          expect(
            (await logs.readPaginationSummary()).total,
            'the footer reports the population the card does',
          ).toBe(openEnded.count);
          // And the row is really on screen: without this, "counted" and
          // "listed" would both rest on the same number rather than on the row.
          await expect(
            tab.row(),
            'the far-future row is listed exactly once',
          ).toHaveCount(1);
        });
      }
    },
  );
});
