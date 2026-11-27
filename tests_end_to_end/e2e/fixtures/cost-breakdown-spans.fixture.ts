import { test as baseTest } from './environment-split-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/** One breakdown group: the dimension values that identify it and the cost it carries per bucket. */
export interface CostBreakdownGroup {
  /** `grp-01` … `grp-13`, in descending cost order. */
  key: string;
  /** Value of `s.provider` — the series name a `provider` breakdown returns. */
  provider: string;
  /** Value of `s.model` — the series name a `model` breakdown returns. */
  model: string;
  /** Span AND trace name, so `name` groups identically for SPAN_COST and COST. */
  name: string;
  /** Value stored under `METADATA_KEY` on the span and the trace. */
  metadataValue: string;
  /** This group's OWN tag. Every seed also carries `sharedTag`. */
  tag: string;
  /** `total_estimated_cost` of this group's one span in EACH bucket. */
  cost: number;
}

export interface CostBreakdownSpansRef {
  /** 13 groups: ten above the backend's group limit of 10, three that must overflow into `__others__`. */
  groups: CostBreakdownGroup[];
  /** Carried by every seed on top of its own tag, so a `tags` breakdown double-counts by construction. */
  sharedTag: string;
  /** The `metadata_key` a METADATA breakdown has to be asked for. */
  metadataKey: string;
  /** `YYYY-MM-DDTHH` (UTC) of each seeded HOURLY bucket, oldest first. */
  buckets: string[];
  /** Sum of every group's cost — the un-grouped total in EACH bucket. */
  perBucketTotal: number;
  /** The backend's fixed breakdown group limit. Ten groups are kept; the rest become `__others__`. */
  groupLimit: number;
  /** Opens on an hour boundary well before the oldest bucket. */
  windowStart: Date;
}

export interface CostBreakdownSpansFixtures {
  costBreakdownSpans: CostBreakdownSpansRef;
}

/**
 * Per-bucket cost of each of the thirteen groups, descending.
 *
 * The split is not decoration — it is what makes the whole spec deterministic
 * and discriminating at once:
 *
 *  - The first TEN are whole dollars, strictly descending and all strictly
 *    greater than any overflow value. `ProjectMetricsService.processBreakdownEntries`
 *    ranks groups by their aggregate over the window and keeps the top ten, and
 *    that sort is not stable for equal aggregates — equal costs would make
 *    *which* groups land in `__others__` a coin toss. Distinct, ordered costs
 *    pin the membership of both sides exactly.
 *  - The last THREE sum to 0.6 in exact decimal but to 0.6000000000000001 in
 *    IEEE doubles. That is the point: opik#9001 made the cost branch merge the
 *    overflow with `BigDecimal::add`, so a regression to double arithmetic
 *    answers a number a reader would never question, and only an overflow set
 *    whose float sum is inexact can tell the two apart.
 */
const GROUP_COSTS = [10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0.3, 0.2, 0.1] as const;

/**
 * The backend's own fixed limit (`BreakdownQueryBuilder.LIMIT`), mirrored here
 * so the fixture can state how many of its groups must overflow. Not
 * configurable by the request, so there is nothing to keep in sync beyond a
 * backend constant changing — which this spec should fail on.
 */
const GROUP_LIMIT = 10;

/**
 * Whole hours back, one bucket each.
 *
 * HOURS rather than days, for the reason `project-metric-spans.fixture.ts`
 * records at length: the backend buckets on `UUIDv7ToDateTime(id)` and
 * `UuidV7TimestampValidator` refuses an id outside a 24h window, so a backdated
 * seed needs a backdated id and cannot reach back further than that window. The
 * subject here is that each bucket carries its own exact sum, and two hours
 * prove that as well as two days would.
 *
 * Never 0, and the two are two hours apart: a span stamped "now" races the
 * backend's own clock, and adjacent hours would collapse into one bucket
 * whenever a run starts near the top of an hour.
 */
const BUCKET_AGE_HOURS = [4, 2] as const;

/** Six empty hours sit between the window's start and the oldest seeded bucket. */
const WINDOW_HOURS = 10;
const HOUR_MS = 60 * 60 * 1000;

/** The `YYYY-MM-DDTHH` (UTC) hour an instant falls in — the HOURLY bucket key. */
const utcHour = (at: Date): string => at.toISOString().slice(0, 13);

/** Exact sum of values that are all whole hundredths, via integer arithmetic. */
const sumExact = (values: readonly number[]): number =>
  values.reduce((acc, v) => acc + Math.round(v * 100), 0) / 100;

/**
 * One fresh project holding thirteen priced LLM spans in each of two HOURLY
 * buckets — one span per breakdown group per bucket.
 *
 * Thirteen is the whole design: the backend keeps ten breakdown groups and
 * folds the rest into `__others__`, so twelve groups could not distinguish "the
 * overflow was summed" from "the overflow had one member". Three overflow
 * groups make a wrong `__others__` visibly wrong — as one point per overflow
 * group per bucket instead of one summed point per bucket.
 *
 * Every group is identified on FIVE dimensions at once (provider, model, name,
 * a metadata value and its own tag), all carrying the same cost, so one seed
 * serves every breakdown field a cost metric accepts without reseeding. The
 * trace carries the same name, metadata and tags as its span, which is what
 * lets the trace-level COST metric be asserted against the same expectations as
 * the span-level SPAN_COST (`GET_COST_WITH_BREAKDOWN` groups on `t.*` and sums
 * the joined spans' costs, so one span per trace makes the two agree).
 *
 * Every seed additionally carries `sharedTag`, which is NOT padding: a `tags`
 * breakdown is the one group expression that fans a row out
 * (`arrayJoin(if(empty(s.tags), ['Unknown'], s.tags))`), so a span with two tags
 * is counted in full under both. That overlap is the behaviour the widget's
 * tag-cost warning exists to explain, and it is only assertable from a seed
 * where it provably happens.
 *
 * Costs are supplied by the CLIENT rather than priced from `usage`. The subject
 * is the arithmetic a breakdown does over costs that exist, so the expected
 * sums have to be literals in the spec rather than whatever the shipped price
 * table says this week — `modelCostSpans` is the fixture for the other
 * question.
 *
 * The fixture blocks until the un-grouped per-project read answers exactly the
 * seeded total, and throws if it never does. A breakdown assertion over a seed
 * that half landed is a test that cannot fail, and the failure it would report
 * ("the groups do not sum to the total") names the product rather than the
 * ingest lag that actually caused it.
 */
export const test = baseTest.extend<CostBreakdownSpansFixtures>({
  costBreakdownSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const sharedTag = `${testNamespace}-tag-shared`;
    const metadataKey = 'grp';

    const groups: CostBreakdownGroup[] = GROUP_COSTS.map((cost, i) => {
      const key = `grp-${String(i + 1).padStart(2, '0')}`;
      return {
        key,
        provider: `${testNamespace}-prov-${key}`,
        model: `${testNamespace}-model-${key}`,
        name: `${testNamespace}-${key}`,
        metadataValue: `${testNamespace}-meta-${key}`,
        tag: `${testNamespace}-tag-${key}`,
        cost,
      };
    });

    const now = Date.now();
    const buckets: string[] = [];
    const traces: TraceBatchSeed[] = [];
    const spans: SpanBatchSeed[] = [];

    for (const ageHours of BUCKET_AGE_HOURS) {
      const at = new Date(now - ageHours * HOUR_MS);
      buckets.push(utcHour(at));

      for (const group of groups) {
        // The id, not `start_time`, is what the read windows and buckets on, so
        // both ids are minted AT the backdated instant. The trace's id gates
        // `traces_filtered` for the COST metric and the span's gates
        // `spans_filtered` for SPAN_COST; they are separate predicates, so
        // backdating only one would silently drop half the assertions.
        const traceId = uuid7(at);
        traces.push({
          id: traceId,
          name: group.name,
          startTime: at,
          endTime: at,
          metadata: { [metadataKey]: group.metadataValue },
          tags: [group.tag, sharedTag],
          input: { group: group.key },
          output: { group: group.key },
        });
        spans.push({
          id: uuid7(at),
          traceId,
          name: group.name,
          type: 'llm',
          source: 'sdk',
          startTime: at,
          endTime: at,
          model: group.model,
          provider: group.provider,
          metadata: { [metadataKey]: group.metadataValue },
          tags: [group.tag, sharedTag],
          totalEstimatedCost: group.cost,
        });
      }
    }

    const perBucketTotal = sumExact(GROUP_COSTS);

    const ref: CostBreakdownSpansRef = {
      groups,
      sharedTag,
      metadataKey,
      buckets,
      perBucketTotal,
      groupLimit: GROUP_LIMIT,
      // Start of the UTC hour WINDOW_HOURS back, so the window opens on a
      // bucket boundary rather than mid-bucket.
      windowStart: new Date(`${utcHour(new Date(now - WINDOW_HOURS * HOUR_MS))}:00:00.000Z`),
    };

    if (new Set(buckets).size !== buckets.length) {
      throw new Error(
        `[costBreakdownSpans fixture] seeded buckets share a UTC hour: ${buckets.join(', ')}`,
      );
    }

    await backendClient.createTracesBatch({ projectName: project.name, traces });

    try {
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Not a courtesy wait — the discrimination check. Asserted on the
      // UN-grouped read, which is the same number every breakdown assertion is
      // compared against, so a seed that landed short fails here naming the
      // fixture instead of surfacing later as a breakdown that "does not sum".
      const expected = sumExact(buckets.map(() => perBucketTotal));
      const deadline = Date.now() + 120_000;
      let seen: number | string = 'no answer';
      for (;;) {
        const { status, message, series } = await backendClient.projectMetric({
          projectId: project.id,
          metricType: 'SPAN_COST',
          interval: 'HOURLY',
          intervalStart: ref.windowStart,
          intervalEnd: new Date(),
        });
        if (status !== 200) {
          seen = `${status}: ${message}`;
        } else {
          const found = series.find((s) => s.name === 'span_cost');
          seen = found
            ? sumExact(found.points.map((p) => p.value ?? 0))
            : 'no span_cost series';
          if (seen === expected) break;
        }
        if (Date.now() >= deadline) {
          throw new Error(
            `[costBreakdownSpans fixture] SPAN_COST over the window never reached ${expected}; ` +
              `last answer: ${seen}`,
          );
        }
        await new Promise((r) => setTimeout(r, 2_000));
      }

      await testInfo.attach('opik.costBreakdownSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // From a `finally` that opens the moment the traces exist: the span write
      // and the settle loop both run BEFORE `use()`, and a failure in either
      // would otherwise leave 26 priced traces behind in a project whose own
      // totals a later run reads.
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces(traces.map((t) => t.id));
        } catch (err) {
          console.warn('[costBreakdownSpans fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './environment-split-thread.fixture';
