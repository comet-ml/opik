import { test as baseTest } from './bystander.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** One seeded day of the window: how far back it sits and what it should total. */
export interface ProjectMetricDaySeed {
  /** Whole days before "now". Never 0 — see the note on the seed below. */
  ageDays: number;
  /** `YYYY-MM-DD` (UTC) of the DAILY bucket this day's spans fall in. */
  bucketDate: string;
  traceId: string;
  spanCount: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface ProjectMetricSpansRef {
  /** One backdated trace per seeded day, oldest last. */
  days: ProjectMetricDaySeed[];
  /** Seeded totals across every day. */
  totals: {
    spanCount: number;
    promptTokens: number;
    completionTokens: number;
    totalTokens: number;
  };
  /** Seeded `total_tokens` per provider — an uneven split of the grand total. */
  totalTokensByProvider: Record<string, number>;
  /** A window that contains every seeded day and two empty days before them. */
  windowStart: Date;
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
 * Seven LLM spans spread unevenly over four past days.
 *
 * Uneven twice over, and both matter. Across days, the per-day `total_tokens`
 * come out 70 / 35 / 94 / 75: a query that bucketed wrongly — or dropped the
 * bucket entirely and summed the window — cannot land on those four numbers by
 * accident, whereas four equal days would forgive it. Across providers, the
 * split is 174 / 100, so a breakdown that ignored its group expression would
 * report the grand total instead of either.
 *
 * `ageDays` is never 0. A span stamped "now" is a coin flip against the
 * backend's own clock, and one stamped even slightly ahead of it is silently
 * excluded from any window ending at now — the failure looks like a wrong
 * aggregate rather than a bad seed. Whole days back also keeps every seed on a
 * distinct UTC date whatever time the run starts.
 */
const DAY_SEEDS: Array<{ ageDays: number; spans: SpanSeed[] }> = [
  {
    ageDays: 1,
    spans: [
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 30, completionTokens: 20 },
      { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 12, completionTokens: 8 },
    ],
  },
  {
    ageDays: 2,
    spans: [
      { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 20, completionTokens: 15 },
    ],
  },
  {
    ageDays: 3,
    spans: [
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 18, completionTokens: 12 },
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 25, completionTokens: 15 },
      { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 14, completionTokens: 10 },
    ],
  },
  {
    ageDays: 4,
    spans: [
      { model: OPENAI_MODEL, provider: 'openai', promptTokens: 45, completionTokens: 30 },
    ],
  },
];

/** Two empty days sit between the window's start and the oldest seeded day. */
const WINDOW_DAYS = 6;
const DAY_MS = 24 * 60 * 60 * 1000;

const utcDate = (at: Date): string => at.toISOString().slice(0, 10);

/**
 * A single project carrying seven LLM spans with known usage, spread over four
 * past days.
 *
 * Everything the per-project metrics read is asserted on comes from here, so
 * the fixture owns the whole shape: which days carry spans, how many, and the
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
    const days: ProjectMetricDaySeed[] = [];
    const totalTokensByProvider: Record<string, number> = {};

    for (const day of DAY_SEEDS) {
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name: `${testNamespace}-d${day.ageDays}`,
        input: { question: `seeded metrics day -${day.ageDays}` },
        output: { answer: `seeded metrics day -${day.ageDays}` },
        age_days: day.ageDays,
        spans: day.spans.map((span, i) => ({
          name: `${testNamespace}-d${day.ageDays}-span-${i + 1}`,
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

      if (created.span_count !== day.spans.length) {
        throw new Error(
          `[projectMetricSpans fixture] day -${day.ageDays}: expected ${day.spans.length} spans, ` +
            `bridge reported ${created.span_count}`,
        );
      }

      for (const span of day.spans) {
        totalTokensByProvider[span.provider] =
          (totalTokensByProvider[span.provider] ?? 0) +
          span.promptTokens +
          span.completionTokens;
      }

      days.push({
        ageDays: day.ageDays,
        bucketDate: utcDate(new Date(now - day.ageDays * DAY_MS)),
        traceId: created.id,
        spanCount: day.spans.length,
        promptTokens: day.spans.reduce((acc, s) => acc + s.promptTokens, 0),
        completionTokens: day.spans.reduce((acc, s) => acc + s.completionTokens, 0),
        totalTokens: day.spans.reduce((acc, s) => acc + s.promptTokens + s.completionTokens, 0),
      });
    }

    // Distinct dates are what the per-bucket assertions key on. They can only
    // collide if `age_days` repeated, but the seed is data — say so here rather
    // than letting a duplicated entry silently collapse two days into one
    // expectation that then "passes".
    const dates = new Set(days.map((d) => d.bucketDate));
    if (dates.size !== days.length) {
      throw new Error(
        `[projectMetricSpans fixture] seeded days share a UTC bucket: ${days.map((d) => d.bucketDate).join(', ')}`,
      );
    }

    const ref: ProjectMetricSpansRef = {
      days,
      totals: {
        spanCount: days.reduce((acc, d) => acc + d.spanCount, 0),
        promptTokens: days.reduce((acc, d) => acc + d.promptTokens, 0),
        completionTokens: days.reduce((acc, d) => acc + d.completionTokens, 0),
        totalTokens: days.reduce((acc, d) => acc + d.totalTokens, 0),
      },
      totalTokensByProvider,
      // Start of the UTC day WINDOW_DAYS back, so the window opens on a bucket
      // boundary and the two days before the oldest seed are whole empty
      // buckets rather than a partial one.
      windowStart: new Date(`${utcDate(new Date(now - WINDOW_DAYS * DAY_MS))}T00:00:00.000Z`),
    };

    await testInfo.attach('opik.projectMetricSpans', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(days.map((d) => d.traceId));
      } catch (err) {
        console.warn('[projectMetricSpans fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './bystander.fixture';
