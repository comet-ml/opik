import { test as baseTest, expect } from './evaluated-thread.fixture';

export interface ScoredTraceScoreSeed {
  name: string;
  value: number;
  /** null when the score was seeded without a reason. */
  reason: string | null;
}

export interface ScoredTraceRef {
  id: string;
  name: string;
  /** Trace-level scores, exactly as seeded — empty for the unscored trace. */
  scores: ScoredTraceScoreSeed[];
}

export interface ScoredSpanRef {
  id: string;
  name: string;
  score: ScoredTraceScoreSeed;
}

export interface ScoredTracesFixtures {
  scoredTraces: {
    /** Every seeded trace, in seed order: alpha (2 scores), beta (1), gamma (0). */
    all: ScoredTraceRef[];
    /** Carried by alpha (with a reason) and beta (without), at different values. */
    sharedScoreName: string;
    /** Carried by alpha only, seeded with no reason. */
    reasonlessScoreName: string;
    /** The trace with no scores at all — its score cells must render the empty state. */
    unscored: ScoredTraceRef;
    /** Alpha's one span, carrying a SPAN-level score and no trace-level one. */
    scoredSpan: ScoredSpanRef;
  };
}

const SHARED_SCORE = 'accuracy';
const REASONLESS_SCORE = 'hallucination';
const SPAN_SCORE = { name: 'span-quality', value: 0.42 };
const SPAN_NAME = 'answer';

/**
 * Three traces whose feedback scores are deliberately staggered so that every
 * score cell on the Traces table has a *different* right answer, plus one
 * span-level score on the first trace's span.
 *
 *   accuracy       -> alpha 0.9 (with a reason), beta 0.4 (without), gamma none
 *   hallucination  -> alpha 0.2 (no reason),     beta none,          gamma none
 *   span-quality   -> alpha's span 0.42          (a "<name> (span)" column)
 *
 * Every column is therefore discriminating. Beta is what separates "the cell
 * renders its own row's score" from "the cell renders the column's first
 * value"; gamma is what separates a real empty state from a cell that quietly
 * inherits a neighbour's number; and the span score is what separates
 * `span_feedback_scores` from `feedback_scores`, which the Traces table reads
 * into two different columns from two different accessors.
 *
 * The span score has no bridge route (the SDK bridge seeds trace-level scores
 * only), so it is written through the backend client and then read back before
 * `use()` — a UI assertion over a fixture that silently failed to attach it
 * would be a cell comparing "-" to "-" forever.
 */
const SEEDS: Array<{
  suffix: string;
  scores: ScoredTraceScoreSeed[];
  spanNames: string[];
}> = [
  {
    suffix: 'alpha',
    scores: [
      { name: SHARED_SCORE, value: 0.9, reason: 'alpha answered the question that was asked' },
      { name: REASONLESS_SCORE, value: 0.2, reason: null },
    ],
    spanNames: [SPAN_NAME],
  },
  {
    suffix: 'beta',
    scores: [{ name: SHARED_SCORE, value: 0.4, reason: null }],
    spanNames: [],
  },
  {
    suffix: 'gamma',
    scores: [],
    spanNames: [],
  },
];

export const test = baseTest.extend<ScoredTracesFixtures>({
  scoredTraces: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const all: ScoredTraceRef[] = [];
    for (const seed of SEEDS) {
      const name = `${testNamespace}-scored-${seed.suffix}`;
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name,
        input: { query: `question from ${seed.suffix}` },
        output: { answer: `answer from ${seed.suffix}` },
        feedback_scores: seed.scores.length
          ? seed.scores.map((s) => ({
              name: s.name,
              value: s.value,
              ...(s.reason ? { reason: s.reason } : {}),
            }))
          : undefined,
        spans: seed.spanNames.map((spanName) => ({
          name: spanName,
          type: 'general' as const,
          input: { step: spanName },
          output: { done: true },
        })),
      });
      all.push({ id: created.id, name: created.name, scores: seed.scores });
    }

    const alpha = all[0];
    const spans = await backendClient.listTraceSpans({
      projectId: project.id,
      traceId: alpha.id,
    });
    // One span, addressed by identity rather than by taking [0] of an
    // unverified list: the span score below is written by id, and writing it
    // to the wrong span would leave the "(span)" column empty for reasons no
    // assertion could explain.
    expect(
      spans.map((s) => s.name),
      'the alpha trace carries exactly its one seeded span',
    ).toEqual([SPAN_NAME]);
    const span = spans[0];

    await backendClient.addSpanFeedbackScore({
      spanId: span.id,
      name: SPAN_SCORE.name,
      value: SPAN_SCORE.value,
    });

    // Feedback-score writes are eventually consistent. Prove the span really
    // holds the score before the browser opens, so a UI failure means the UI.
    await expect
      .poll(
        async () => {
          const current = await backendClient.listTraceSpans({
            projectId: project.id,
            traceId: alpha.id,
          });
          return current[0]?.feedbackScores.map((s) => ({ name: s.name, value: s.value })) ?? [];
        },
        { timeout: 60_000, intervals: [500, 1_000, 2_000] },
      )
      .toEqual([{ name: SPAN_SCORE.name, value: SPAN_SCORE.value }]);

    const scoredSpan: ScoredSpanRef = {
      id: span.id,
      name: span.name,
      score: { name: SPAN_SCORE.name, value: SPAN_SCORE.value, reason: null },
    };

    await testInfo.attach('opik.scoredTraces', {
      body: JSON.stringify({ all, scoredSpan }, null, 2),
      contentType: 'application/json',
    });

    await use({
      all,
      sharedScoreName: SHARED_SCORE,
      reasonlessScoreName: REASONLESS_SCORE,
      unscored: all[2],
      scoredSpan,
    });
    // No explicit teardown — the project fixture's deleteProject cascades, the
    // same lifetime the sibling trace fixtures rely on. The span score lives on
    // the span, so it goes with it.
  },
});

export { expect } from './evaluated-thread.fixture';
