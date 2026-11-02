import { test, expect } from '@e2e/fixtures';
import type { CostBreakdownGroup, CostBreakdownSpansRef } from '@e2e/fixtures';
import type { BackendClient, MetricSeries, ProjectMetricType } from '@e2e/core/backend';
import { DashboardsPage } from '@e2e/pom/dashboards.page';

/**
 * The `Others` series of a COST breakdown — `POST /v1/private/projects/{id}/metrics`
 * with a `breakdown` on `SPAN_COST` or `COST` (opik#9001, issue-8132).
 *
 * The backend keeps the top ten breakdown groups by aggregate and folds
 * everything past that into one `__others__` series. For every metric EXCEPT
 * the two cost ones it does that by concatenating the overflow groups' raw
 * entries, which leaves one point per overflow group per bucket, all sharing a
 * timestamp. opik#9001 gave the cost family its own branch: merge the overflow
 * per bucket with `BigDecimal::add`, so `Others` is one exact decimal point per
 * bucket.
 *
 * Why this is worth a spec of its own. `project-span-metrics.spec.ts` already
 * drives twelve breakdown variants — and asserts that it drove exactly twelve —
 * but every one of them is `SPAN_COUNT`, `SPAN_TOKEN_USAGE` or `SPAN_DURATION`,
 * and none of them overflows the group limit. So both halves of this change
 * were undriven: the cost metrics' breakdown at all, and the overflow path for
 * anything. The failure it guards is silent wrongness on MONEY: a cost chart
 * reading a plausible-but-wrong total is not something a user sanity-checks.
 *
 * SCOPE — the same two layers as the neighbouring dashboards specs. The API
 * tests pin the arithmetic, which cannot be read back off a chart: a rendered
 * line at "0.6" and one at "0.6000000000000001" are the same pixels. The last
 * test drives the real widget dialog's Group by control, so the capability tag
 * names something a browser actually exercised — `configure-widget`'s own note
 * records "group by" as the uncovered part of it.
 *
 * NOT covered, and deliberately not claimed: the `Others` series for non-cost
 * metrics. On the same seed, `SPAN_COUNT` grouped by provider still answers
 * three duplicate-timestamp points per bucket instead of one summed point —
 * the `else` branch beside the one this release fixed. That is pre-existing
 * behaviour 2.2.138 did not change, and pinning it either way here would be
 * guessing at a product decision rather than testing one.
 */

/** One group's expected series: its name under the chosen dimension and its cost per bucket. */
interface ExpectedGroup {
  name: string;
  costPerBucket: number;
}

/** What one breakdown read must answer. */
interface BreakdownExpectation {
  /** The ten groups the limit keeps, each with its own per-bucket cost. */
  topGroups: ExpectedGroup[];
  /** The exact per-bucket value of the single `__others__` point. */
  othersPerBucket: number;
  /** Sum of every series in one bucket — what the un-grouped read must also answer. */
  bucketTotal: number;
}

/** The overflow series' name, as `BreakdownQueryBuilder.OTHERS_GROUP_NAME` spells it. */
const OTHERS = '__others__';

/**
 * Exact sum of values that are all whole hundredths, via integer arithmetic.
 *
 * Deliberately NOT a plain `reduce((a, b) => a + b)`. Every value here is a
 * whole cent, so scaling to integers makes the sum exact — which keeps the
 * "groups sum to the un-grouped total" assertions about GROUPING rather than
 * about which order JavaScript happened to add doubles in. The float-precision
 * question is asked separately and directly, by comparing the `__others__`
 * point to the literal `0.6`: that comparison is the one that must stay
 * sensitive to the last bit, and rounding it would be exactly how a regression
 * to double arithmetic in the backend slipped through.
 */
const sumExact = (values: number[]): number =>
  values.reduce((acc, v) => acc + Math.round(v * 100), 0) / 100;

/** The `YYYY-MM-DDTHH` (UTC) bucket a data point falls in, as the fixture records its seeds. */
const bucketHour = (point: { time: string }): string => point.time.slice(0, 13);

/**
 * Every point's value, having first asserted that none is null.
 *
 * The breakdown query carries no `WITH FILL` (`GET_SPAN_COST_WITH_BREAKDOWN`
 * has no `FROM`/`TO` clause), so a breakdown series holds a point only for a
 * bucket in which its group really had rows — there is nothing legitimately
 * null in this answer. A `?? 0` here would turn a missing aggregate into a
 * silent zero and let every sum below pass while the read returned nothing.
 */
const pointValues = (series: MetricSeries, label: string): number[] => {
  for (const point of series.points) {
    expect(point.value, `${label}: the point at ${point.time} carries a value`).not.toBeNull();
  }
  return series.points.map((p) => p.value as number);
};

/** The series by that exact name, asserted present. */
const seriesNamed = (series: MetricSeries[], name: string): MetricSeries => {
  const found = series.find((s) => s.name === name);
  expect(found, `the answer carries a "${name}" series`).toBeDefined();
  return found!;
};

/**
 * The scalar-dimension expectation: ten groups kept, the last three overflowing.
 *
 * `pick` reads the dimension value off a group, so provider, model, name and
 * metadata all share one expectation builder — they are the same arithmetic
 * over the same seed, differing only in which column the backend groups on.
 */
const scalarExpectation = (
  seed: CostBreakdownSpansRef,
  pick: (group: CostBreakdownGroup) => string,
): BreakdownExpectation => {
  const kept = seed.groups.slice(0, seed.groupLimit);
  const overflow = seed.groups.slice(seed.groupLimit);
  // The seed exists to overflow the limit; if it stopped doing so, every
  // assertion below would hold with no `__others__` series at all.
  expect(overflow.length, 'the seed overflows the breakdown group limit').toBeGreaterThan(1);
  return {
    topGroups: kept.map((g) => ({ name: pick(g), costPerBucket: g.cost })),
    othersPerBucket: sumExact(overflow.map((g) => g.cost)),
    bucketTotal: seed.perBucketTotal,
  };
};

/**
 * Asserts one breakdown read in full: the whole series set, each group's own
 * points, the `Others` shape and value, and the per-bucket sum.
 *
 * The series-set assertion is first and is an equality, not a containment:
 * finding the expected groups in the answer would pass just as well for an
 * answer that ALSO carried an eleventh group the limit should have folded away
 * — which is the shape of the bug this whole spec is about.
 */
const expectBreakdown = (
  series: MetricSeries[],
  seed: CostBreakdownSpansRef,
  expected: BreakdownExpectation,
  label: string,
): void => {
  expect(
    series.map((s) => s.name).sort(),
    `${label}: the ten kept groups and Others, and nothing else`,
  ).toEqual([...expected.topGroups.map((g) => g.name), OTHERS].sort());

  for (const group of expected.topGroups) {
    const found = seriesNamed(series, group.name);
    const values = pointValues(found, `${label} / ${group.name}`);
    expect(
      found.points.map(bucketHour).sort(),
      `${label}: "${group.name}" has one point per seeded bucket`,
    ).toEqual([...seed.buckets].sort());
    for (const value of values) {
      expect(value, `${label}: "${group.name}" bills its own cost, not the group total`).toBe(
        group.costPerBucket,
      );
    }
  }

  const others = seriesNamed(series, OTHERS);
  // The heart of opik#9001. ONE point per bucket, not one per overflow group
  // per bucket — a concatenated overflow answers `overflow.length` points per
  // bucket, all stamped with the same time, and a chart reading that shows
  // roughly a single overflow group's value where the group holds all of them.
  expect(
    others.points.map(bucketHour).sort(),
    `${label}: Others carries exactly one point per bucket`,
  ).toEqual([...seed.buckets].sort());
  for (const value of pointValues(others, `${label} / Others`)) {
    // Compared to the literal, with no rounding anywhere near it: the seeded
    // overflow sums to 0.6 in exact decimal and to 0.6000000000000001 in IEEE
    // doubles, so this is the assertion that tells `BigDecimal::add` from a
    // regression to `double`.
    expect(value, `${label}: Others sums its overflow groups in exact decimal`).toBe(
      expected.othersPerBucket,
    );
  }

  for (const bucket of seed.buckets) {
    const inBucket = series.flatMap((s) =>
      s.points.filter((p) => bucketHour(p) === bucket).map((p) => p.value as number),
    );
    expect(
      sumExact(inBucket),
      `${label}: every series in bucket ${bucket} accounts for the whole cost`,
    ).toBe(expected.bucketTotal);
  }
};

/**
 * The un-grouped total each breakdown is measured against, asserted on its own.
 *
 * Shared by the tests below as a precondition rather than taken on trust:
 * "the groups sum to the un-grouped total" is only evidence about GROUPING if
 * the un-grouped answer is itself right. If the backend returned the wrong
 * total, a breakdown that agreed with it would read as a pass.
 */
const expectUngroupedTotal = async (
  backendClient: BackendClient,
  projectId: string,
  seed: CostBreakdownSpansRef,
  metricType: ProjectMetricType,
  seriesName: string,
): Promise<void> => {
  await test.step(`${metricType} with no breakdown totals the seed in each bucket`, async () => {
    const { status, message, series } = await backendClient.projectMetric({
      projectId,
      metricType,
      interval: 'HOURLY',
      intervalStart: seed.windowStart,
      intervalEnd: new Date(),
    });
    expect(status, `${metricType} rejected with: ${message}`).toBe(200);
    const found = seriesNamed(series, seriesName);
    const byBucket = new Map<string, number>();
    for (const point of found.points) {
      // `?? 0` on the point value is right HERE and nowhere else in this file:
      // the un-grouped query does carry `WITH FILL`, so a bucket the seed never
      // touched is padded with a null that genuinely means zero.
      byBucket.set(
        bucketHour(point),
        sumExact([byBucket.get(bucketHour(point)) ?? 0, point.value ?? 0]),
      );
    }
    for (const bucket of seed.buckets) {
      expect(byBucket.get(bucket), `${metricType} in bucket ${bucket}`).toBe(seed.perBucketTotal);
    }
    // The other half, and the half the per-bucket loop alone would miss: a
    // query that ignored its bucket expression would put everything in one
    // bucket and still satisfy that loop for that one bucket.
    const strays = [...byBucket.entries()].filter(
      ([bucket, value]) => !seed.buckets.includes(bucket) && value !== 0,
    );
    expect(strays, 'buckets outside the seeded hours are empty').toEqual([]);
  });
};

test.describe('Dashboards — cost breakdown Others', { tag: ['@t2-cuj', '@area:dashboards'] }, () => {
  /**
   * The fixture writes 26 traces and 26 priced spans and then blocks until the
   * un-grouped read answers the seeded total, which the default budget cannot
   * contain. Declared on the describe so it covers fixture setup too.
   */
  test.slow();

  test(
    'SPAN_COST grouped by provider, model, name or metadata keeps ten groups and sums the rest into one exact Others point per bucket',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ costBreakdownSpans, project, backendClient }) => {
      await expectUngroupedTotal(
        backendClient,
        project.id,
        costBreakdownSpans,
        'SPAN_COST',
        'span_cost',
      );

      const dimensions: Array<{
        field: string;
        metadataKey?: string;
        pick: (g: CostBreakdownGroup) => string;
      }> = [
        { field: 'provider', pick: (g) => g.provider },
        { field: 'model', pick: (g) => g.model },
        { field: 'name', pick: (g) => g.name },
        {
          field: 'metadata',
          metadataKey: costBreakdownSpans.metadataKey,
          pick: (g) => g.metadataValue,
        },
      ];

      for (const dimension of dimensions) {
        await test.step(`Grouped by ${dimension.field}`, async () => {
          const { status, message, series } = await backendClient.projectMetric({
            projectId: project.id,
            metricType: 'SPAN_COST',
            interval: 'HOURLY',
            intervalStart: costBreakdownSpans.windowStart,
            intervalEnd: new Date(),
            breakdown: { field: dimension.field, metadataKey: dimension.metadataKey },
          });
          expect(status, `SPAN_COST by ${dimension.field} rejected with: ${message}`).toBe(200);
          expectBreakdown(
            series,
            costBreakdownSpans,
            scalarExpectation(costBreakdownSpans, dimension.pick),
            `SPAN_COST by ${dimension.field}`,
          );
        });
      }
    },
  );

  test(
    'COST — the trace-level cost metric opik#9001 also changed — folds its overflow the same way',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ costBreakdownSpans, project, backendClient }) => {
      // Not a near-duplicate of the test above. `COST` is a different query
      // against a different table — `GET_COST_WITH_BREAKDOWN` groups on the
      // TRACE's own columns and sums the costs of the spans joined to it — and
      // it predates this release by a long way. opik#9001 routed it through the
      // new exact-sum branch alongside `SPAN_COST`, so a fix that reached only
      // the new metric would pass the test above and fail here.
      await expectUngroupedTotal(backendClient, project.id, costBreakdownSpans, 'COST', 'cost');

      const dimensions: Array<{
        field: string;
        metadataKey?: string;
        pick: (g: CostBreakdownGroup) => string;
      }> = [
        { field: 'name', pick: (g) => g.name },
        {
          field: 'metadata',
          metadataKey: costBreakdownSpans.metadataKey,
          pick: (g) => g.metadataValue,
        },
      ];

      for (const dimension of dimensions) {
        await test.step(`Grouped by ${dimension.field}`, async () => {
          const { status, message, series } = await backendClient.projectMetric({
            projectId: project.id,
            metricType: 'COST',
            interval: 'HOURLY',
            intervalStart: costBreakdownSpans.windowStart,
            intervalEnd: new Date(),
            breakdown: { field: dimension.field, metadataKey: dimension.metadataKey },
          });
          expect(status, `COST by ${dimension.field} rejected with: ${message}`).toBe(200);
          expectBreakdown(
            series,
            costBreakdownSpans,
            scalarExpectation(costBreakdownSpans, dimension.pick),
            `COST by ${dimension.field}`,
          );
        });
      }
    },
  );

  test(
    'a tags breakdown counts an overlapping tag in full under each, summing to exactly twice the un-grouped cost',
    { tag: ['@cap:dashboards.configure-widget'] },
    async ({ costBreakdownSpans, project, backendClient }) => {
      // The one group expression that is not a scalar column:
      // `arrayJoin(if(empty(s.tags), ['Unknown'], s.tags))` fans a span out once
      // per tag. Every seeded span carries its own tag AND one shared tag, so
      // the breakdown must count each span's cost twice — which is the
      // behaviour the widget's tag-cost warning exists to explain, and the
      // reason "the groups sum to the total" is the WRONG expectation here.
      // Asserting the doubling explicitly is what keeps this from reading as a
      // bug the next person "fixes".
      const seed = costBreakdownSpans;

      // Ranked by aggregate: the shared tag carries the whole bucket total, so
      // it outranks every own-tag group and takes the first of the ten slots —
      // leaving one fewer slot for the own tags and pushing a fourth group into
      // the overflow.
      const ownTagsKept = seed.groups.slice(0, seed.groupLimit - 1);
      const ownTagsOverflowed = seed.groups.slice(seed.groupLimit - 1);
      const expected: BreakdownExpectation = {
        topGroups: [
          { name: seed.sharedTag, costPerBucket: seed.perBucketTotal },
          ...ownTagsKept.map((g) => ({ name: g.tag, costPerBucket: g.cost })),
        ],
        othersPerBucket: sumExact(ownTagsOverflowed.map((g) => g.cost)),
        bucketTotal: sumExact([seed.perBucketTotal, seed.perBucketTotal]),
      };

      const { status, message, series } = await backendClient.projectMetric({
        projectId: project.id,
        metricType: 'SPAN_COST',
        interval: 'HOURLY',
        intervalStart: seed.windowStart,
        intervalEnd: new Date(),
        breakdown: { field: 'tags' },
      });
      expect(status, `SPAN_COST by tags rejected with: ${message}`).toBe(200);
      expectBreakdown(series, seed, expected, 'SPAN_COST by tags');

      // Stated as its own assertion rather than left implicit in
      // `bucketTotal`: the documented overlap is a FACT about this read, and a
      // reader of a failure here should not have to derive it from the seed.
      expect(
        expected.bucketTotal,
        'an overlapping tag breakdown exceeds real spend, by exactly the overlap',
      ).toBe(sumExact([seed.perBucketTotal, seed.perBucketTotal]));
    },
  );

  test(
    'a Time series widget grouped by Provider reads the per-project endpoint and plots the ten groups plus Others',
    {
      tag: [
        '@cap:dashboards.create-dashboard',
        '@cap:dashboards.add-widget',
        '@cap:dashboards.configure-widget',
        '@cap:dashboards.metric-date-range',
      ],
    },
    async ({ costBreakdownSpans, project, registerDashboardCleanup, page }) => {
      const dashboards = new DashboardsPage(page);
      // `METRIC_LABELS[SPAN_COST]`, which is also the generated widget title: a
      // cost metric takes no sub-metric, so nothing is appended to it.
      const metricLabel = 'Span estimated cost';
      const expected = scalarExpectation(costBreakdownSpans, (g) => g.provider);

      await test.step('Open Dashboards and create one', async () => {
        await dashboards.goto();
        await dashboards.waitForReady();
        // Registered the moment the id exists: a dashboard belongs to the
        // workspace, so neither the project fixture nor the run-prefix sweep
        // would ever remove it.
        registerDashboardCleanup(await dashboards.createDashboard(`${project.name}-dash`));
      });

      await test.step('Narrow the dashboard range to Past 24 hours', async () => {
        // Before the widget is built, so its very first read is the one under
        // test. The default preset is Past 30 days, which `calculateIntervalType`
        // serves DAILY — both seeded hours would collapse into a single bucket
        // and "one Others point per bucket" would be unfalsifiable.
        await dashboards.selectDateRange('Past 24 hours');
        expect(await dashboards.selectedDateRange(), 'the selected preset').toBe('Past 24 hours');
      });

      const isProjectMetrics = (url: string) =>
        new URL(url).pathname === `/opik/api/v1/private/projects/${project.id}/metrics` ||
        new URL(url).pathname === `/api/v1/private/projects/${project.id}/metrics`;

      /**
       * The widget's own grouped read.
       *
       * Matched on the payload rather than on "the next request to this path":
       * the dialog's live preview re-reads on every config change, so the path
       * alone matches the ungrouped SPAN_COST read taken the moment the metric
       * is picked, before Group by has been touched. Registered before the
       * group-by change so the preview's first grouped read is caught — the
       * saved widget may well answer from the react-query cache without
       * issuing a request at all.
       */
      const groupedRead = page.waitForResponse((r) => {
        if (!isProjectMetrics(r.url())) return false;
        const payload = JSON.parse(r.request().postData() ?? '{}') as {
          metric_type?: string;
          interval?: string;
          breakdown?: { field?: string };
        };
        return (
          payload.metric_type === 'SPAN_COST' &&
          payload.interval === 'HOURLY' &&
          payload.breakdown?.field === 'provider'
        );
      }, { timeout: 60_000 });

      await test.step('Configure the widget: this project, Span estimated cost, Group by Provider', async () => {
        const dialog = await dashboards.openWidgetDialog();
        await dashboards.scopeWidgetToProject(dialog, project.name);
        await dashboards.selectWidgetMetric(metricLabel);
        await dashboards.selectWidgetGroupBy('Provider');
        await dashboards.selectWidgetAggregationMode('Time-based');
        await dialog.getByRole('button', { name: 'Add widget', exact: true }).click();
        await dialog.waitFor({ state: 'hidden' });
      });

      await test.step('The widget asks the per-project endpoint for a provider breakdown, and is served the same arithmetic', async () => {
        const response = await groupedRead;
        // A mis-gated project bind surfaces here as a 500 while the chart
        // simply stays blank.
        expect(response.status(), 'the grouped per-project metrics read').toBe(200);
        const body = (await response.json()) as {
          results?: Array<{ name?: string; data?: Array<{ time?: string; value?: number | null }> }>;
        };
        // Mapped into the same shape the API tests assert on and run through
        // the same expectation: the point of driving the browser is that the
        // configuration the UI builds produces the answer the contract tests
        // pinned, and re-deriving a weaker expectation here would not say that.
        const series: MetricSeries[] = (body.results ?? []).map((r) => ({
          name: String(r.name ?? ''),
          points: (r.data ?? []).map((p) => ({ time: String(p.time ?? ''), value: p.value ?? null })),
        }));
        expectBreakdown(series, costBreakdownSpans, expected, 'the widget’s provider breakdown');
      });

      await test.step('The chart draws the ten provider groups and Others', async () => {
        // The chart plots values as SVG geometry, so the rendered pixels cannot
        // be compared to a cost — that is what the assertions above are for.
        // What the UI must prove is that it resolved the grouped query and drew
        // every series it was given, including the overflow one under the
        // display label the front end substitutes for `__others__`.
        await dashboards.waitForWidgetChart(metricLabel);
        for (const group of expected.topGroups) {
          await expect(
            dashboards.widgetLegendLabel(metricLabel, group.name),
            `the legend names the "${group.name}" group`,
          ).toHaveCount(1);
        }
        await expect(
          dashboards.widgetLegendLabel(metricLabel, 'Others'),
          'the legend names the overflow group as "Others"',
        ).toHaveCount(1);
      });
    },
  );
});
