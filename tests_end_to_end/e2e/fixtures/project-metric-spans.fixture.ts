import { test as baseTest } from './bystander.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7Moment } from '../core/backend';

/** One seeded hour of the window: how far back it sits and what it should total. */
export interface ProjectMetricHourSeed {
  /** Whole hours before "now". Never 0 — see the note on the seed below. */
  ageHours: number;
  /** `YYYY-MM-DDTHH` (UTC) of the HOURLY bucket this hour's spans fall in. */
  bucketHour: string;
  traceId: string;
  spanCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface ProjectMetricSpansRef {
  /** One backdated trace per seeded hour, oldest last. */
  hours: ProjectMetricHourSeed[];
  /** Seeded totals across every hour. */
  totals: {
    spanCount: number;
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
  /** Seeded `total_tokens` per provider — an uneven split of the grand total. */
  totalTokensByProvider: Record<string, number>;
  /** A window that contains every seeded hour and six empty hours before them. */
  windowStart: Date;
  /** Closes before the oldest seed, so a read over it must aggregate to nothing. */
  emptyWindowEnd: Date;
}

export interface ProjectMetricSpansFixtures {
  projectMetricSpans: ProjectMetricSpansRef;
}

const OPENAI_MODEL = 'gpt-4o-mini';
const ANTHROPIC_MODEL = 'claude-3-5-haiku-20241022';

interface SpanSeed {
  model: string;
  provider: string;
  promptTokens: number;
  completionTokens: number;
}

/**
 * Seven LLM spans spread unevenly over four past hours.
 *
 * Uneven twice over, and both matter. Across hours, the per-hour `total_tokens`
 * come out 70 / 35 / 94 / 75: a query that bucketed wrongly — or dropped the
 * bucket entirely and summed the window — cannot land on those four numbers by
 * accident, whereas four equal hours would forgive it. Across providers, the
 * split is 195 / 79, so a breakdown that ignored its group expression would
 * report the grand total instead of either.
 *
 * HOURS, not days, because ingestion validates the instant embedded in an id:
 * `UuidV7TimestampValidator` refuses anything outside `uuidValidation.window`
 * (24h on comet.com), and a backdated seed needs a backdated id — the backend
 * buckets on `UUIDv7ToDateTime(id)`, so a fresh id lands "now" whatever
 * `start_time` says. Days put every seed outside that window; the deepest here
 * is 12h, which leaves half the window as headroom. The subject of these specs
 * is that each bucket carries its own spans, and an hour proves that as well as
 * a day does.
 *
 * `ageHours` is never 0. A span stamped "now" is a coin flip against the
 * backend's own clock, and one stamped even slightly ahead of it is silently
 * excluded from any window ending at now — the failure looks like a wrong
 * aggregate rather than a bad seed. Whole hours back also keep every seed in a
 * distinct UTC hour whatever minute the run starts.
 */
const HOUR_SEEDS: Array<{ ageHours: number; spans: SpanSeed[] }> = [
  {
    ageHours: 1,
    spans: [
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 30, completionTokens: 20 },
      { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 12, completionTokens: 8 },
    ],
  },
  {
    ageHours: 3,
    spans: [
      { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 20, completionTokens: 15 },
    ],
  },
  {
    ageHours: 6,
    spans: [
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 18, completionTokens: 12 },
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 25, completionTokens: 15 },
      { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 14, completionTokens: 10 },
    ],
  },
  {
    ageHours: 12,
    spans: [
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 45, completionTokens: 30 },
    ],
  },
];

/** Six empty hours sit between the window's start and the oldest seeded hour. */
const WINDOW_HOURS = 18;
/** Between the window start and the oldest seed, so it spans only empty buckets. */
const EMPTY_WINDOW_END_HOURS = 15;
const HOUR_MS = 60 * 60 * 1000;

/** The `YYYY-MM-DDTHH` (UTC) hour an instant falls in. */
const utcHour = (at: Date): string => at.toISOString().slice(0, 13);

/**
 * A single project carrying seven LLM spans with known usage, spread over four
 * past hours.
 *
 * Everything the per-project metrics read is asserted on comes from here, so
 * the fixture owns the whole shape: which hours carry spans, how many, and the
 * usage on each. The project is fresh per test, which is what makes an
 * *unfiltered* per-project aggregation deterministic in a workspace holding
 * thousands of other projects — and therefore what makes `SPAN_COUNT == 7`
 * evidence that the project predicate rendered at all.
 *
 * Teardown deletes the traces rather than relying on the project delete: a
 * project delete does not take its traces with it, and spans left behind would
 * be counted by a later run against the same window.
 */
export const test = baseTest.extend<ProjectMetricSpansFixtures>({
  projectMetricSpans: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const now = Date.now();
    const hours: ProjectMetricHourSeed[] = [];
    const totalTokensByProvider: Record<string, number> = {};

    for (const hour of HOUR_SEEDS) {
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-h${hour.ageHours}`,
        input: { question: `seeded metrics hour -${hour.ageHours}` },
        output: { answer: `seeded metrics hour -${hour.ageHours}` },
        age_days: hour.ageHours / 24,
        spans: hour.spans.map((span, i) => ({
          name: `${testNamespace}-h${hour.ageHours}-span-${i + 1}`,
          type: 'llm' as const,
          model: span.model,
          provider: span.provider,
          usage: {
            prompt_tokens: span.promptTokens,
            completion_tokens: span.completionTokens,
            total_tokens: span.promptTokens + span.completionTokens,
          },
        })),
      });

      if (created.span_count !== hour.spans.length) {
        throw new Error(
          `[projectMetricSpans fixture] hour -${hour.ageHours}: expected ${hour.spans.length} spans, ` +
            `bridge reported ${created.span_count}`,
        );
      }

      for (const span of hour.spans) {
        totalTokensByProvider[span.provider] =
          (totalTokensByProvider[span.provider] ?? 0) +
          span.promptTokens +
          span.completionTokens;
      }

      hours.push({
        ageHours: hour.ageHours,
        // Read back off the id the bridge minted, not computed from `now`: the
        // bridge anchors on its own clock at request time, and at hour
        // granularity the gap between the two straddles a bucket boundary often
        // enough to matter. This is the instant the backend itself buckets on.
        bucketHour: utcHour(uuid7Moment(created.id)),
        traceId: created.id,
        spanCount: hour.spans.length,
        promptTokens: hour.spans.reduce((acc, s) => acc + s.promptTokens, 0),
        completionTokens: hour.spans.reduce((acc, s) => acc + s.completionTokens, 0),
        totalTokens: hour.spans.reduce((acc, s) => acc + s.promptTokens + s.completionTokens, 0),
      });
    }

    // Distinct hours are what the per-bucket assertions key on. Unlike the
    // day-aged seed this replaced, these are read back off the minted ids, so a
    // collision no longer needs a duplicated entry in the table — a stalled run
    // between two seeds would do it. Checking it here keeps that from silently
    // collapsing two hours into one expectation that then "passes".
    const bucketHours = new Set(hours.map((h) => h.bucketHour));
    if (bucketHours.size !== hours.length) {
      throw new Error(
        `[projectMetricSpans fixture] seeded hours share a UTC bucket: ${hours.map((h) => h.bucketHour).join(', ')}`,
      );
    }

    const ref: ProjectMetricSpansRef = {
      hours,
      totals: {
        spanCount: hours.reduce((acc, h) => acc + h.spanCount, 0),
        promptTokens: hours.reduce((acc, h) => acc + h.promptTokens, 0),
        completionTokens: hours.reduce((acc, h) => acc + h.completionTokens, 0),
        totalTokens: hours.reduce((acc, h) => acc + h.totalTokens, 0),
      },
      totalTokensByProvider,
      // Start of the UTC hour WINDOW_HOURS back, so the window opens on a bucket
      // boundary and the six hours before the oldest seed are whole empty
      // buckets rather than a partial one.
      windowStart: new Date(`${utcHour(new Date(now - WINDOW_HOURS * HOUR_MS))}:00:00.000Z`),
      emptyWindowEnd: new Date(`${utcHour(new Date(now - EMPTY_WINDOW_END_HOURS * HOUR_MS))}:00:00.000Z`),
    };

    await testInfo.attach('opik.projectMetricSpans', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(hours.map((h) => h.traceId));
      } catch (err) {
        console.warn('[projectMetricSpans fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './bystander.fixture';
