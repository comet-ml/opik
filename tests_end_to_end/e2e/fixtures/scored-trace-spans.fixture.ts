import { expect } from '@playwright/test';
import { test as baseTest } from './openrouter-regen-repriced-spans.fixture';
import { uuid7 } from '../core/backend';
import type { BackendClient } from '../core/backend';

/** One seeded feedback score, as written and as it must still read back. */
export interface SeededScore {
  name: string;
  value: number;
}

export interface ScoredTraceSpanRef {
  id: string;
  name: string;
}

export interface ScoredTraceSpansRef {
  traceId: string;
  traceName: string;
  projectId: string;
  projectName: string;
  /**
   * The trace's two LLM spans. `[0]` carries {@link spanScore}; `[1]` carries
   * nothing and is the bystander a span-level delete must not reach.
   */
  spans: [ScoredTraceSpanRef, ScoredTraceSpanRef];
  /** Two trace-level scores, neither of which any test here deletes. */
  traceScores: [SeededScore, SeededScore];
  /** One score on `spans[0]`, which no test here deletes either. */
  spanScore: SeededScore;
}

export interface ScoredTraceSpansFixtures {
  scoredTraceSpans: ScoredTraceSpansRef;
}

/**
 * Wait until a span written through `/v1/private/spans/batch` is readable by id.
 *
 * `POST /v1/private/spans/{id}/feedback-scores` resolves its project from the
 * span, so it answers a hard 404 — "Span id: … not found" — for a span whose
 * batch write has landed but is not yet queryable. That failure surfaces inside
 * the fixture, where it reads as the suite being broken rather than as a race,
 * and it is not hypothetical: it took out this fixture's first run on staging.
 * The batch write's own 204 is not the signal, which is why `setTraceFeedbackScores`
 * exists for the trace side of the same problem.
 */
export async function waitForSpanReadable(
  backendClient: BackendClient,
  spanId: string,
): Promise<void> {
  await expect
    .poll(async () => (await backendClient.getSpan(spanId)) !== null, {
      timeout: 120_000,
      intervals: [500, 1_000, 2_000],
      message: `span ${spanId} must be readable by id before it can be scored`,
    })
    .toBe(true);
}

/**
 * One trace with two LLM spans, two trace-level feedback scores and one
 * span-level one — all of them seeded `source: 'sdk'`, none of them the score a
 * test is about.
 *
 * This is a seed of BYSTANDERS. The behaviour it exists for is the delete of a
 * score a user added by hand (opik#9643 gave `FeedbackScoreDAO.deleteScoreFrom`
 * a `projectId` and both of its deletes an `AND project_id = :project_id`), and
 * "the score disappeared" is satisfied just as well by a delete that took every
 * score on the trace, or every score in the project. So the scores here are the
 * ones that must SURVIVE: two on the trace, one on a sibling span, with a second
 * span deliberately left unscored so an over-reaching write has somewhere
 * visible to land.
 *
 * Seeded through REST rather than the SDK bridge because the spans need
 * caller-minted ids: a span-level assertion has to name the span it is about
 * both to the API and to the panel's tree, and `createNestedTrace` mints its
 * ids inside the SDK process.
 *
 * `source: 'sdk'` on every seeded score is load-bearing, not incidental. The
 * Annotate panel's editor rows come from the workspace's feedback DEFINITIONS
 * and take their value only from scores whose source is `ui`
 * (`FeedbackScoresEditor`), so an sdk-sourced score renders as a read-only tag
 * and never as a row with a "Clear score" button. That is what keeps the panel's
 * single clear control unambiguous while three other scores are on screen.
 *
 * The fixture proves its own seed before handing it over: every score is read
 * back by id at the value it was written at, and the unscored span is asserted
 * to carry none. A spec whose bystanders were never really there could not fail,
 * and would read as coverage of a destructive action's blast radius forever.
 *
 * Teardown sweeps the project's traces explicitly through
 * `registerProjectTracesCleanup` — `deleteProject` is documented in
 * `trace.fixture.ts` as not cascading to them — and the scores are rows on the
 * trace, so they go with it.
 */
export const test = baseTest.extend<ScoredTraceSpansFixtures>({
  scoredTraceSpans: async (
    { backendClient, project, testNamespace, registerProjectTracesCleanup },
    use,
    testInfo,
  ) => {
    registerProjectTracesCleanup(project.id);

    const traceId = uuid7();
    const traceName = `${testNamespace}-scored-trace`;
    // Names chosen so no one is a substring of another: the panel's score-tag
    // locator filters on `hasText`, which matches substrings, so a `-a`/`-ab`
    // pair would have one tag addressing two elements.
    const spans: [ScoredTraceSpanRef, ScoredTraceSpanRef] = [
      { id: uuid7(), name: `${testNamespace}-span-one` },
      { id: uuid7(), name: `${testNamespace}-span-two` },
    ];
    const traceScores: [SeededScore, SeededScore] = [
      { name: `${testNamespace}-alpha`, value: 0.4 },
      { name: `${testNamespace}-beta`, value: 0.6 },
    ];
    const spanScore: SeededScore = { name: `${testNamespace}-gamma`, value: 0.3 };

    await backendClient.createTracesBatch({
      projectName: project.name,
      traces: [{ id: traceId, name: traceName, input: { seed: traceName }, output: {} }],
    });
    await backendClient.createSpansBatch({
      projectName: project.name,
      spans: spans.map((span) => ({
        id: span.id,
        traceId,
        name: span.name,
        source: 'sdk' as const,
        type: 'llm' as const,
        model: 'gpt-4o',
        provider: 'openai',
        input: { seed: span.name },
      })),
    });

    // Scored through the batch endpoint, which scopes by `project_name` and
    // merges, so it is stable the moment the trace write returns — see
    // `setTraceFeedbackScores`. The per-id endpoint would race the trace.
    await backendClient.setTraceFeedbackScores({
      projectName: project.name,
      scores: traceScores.map((score) => ({ traceId, name: score.name, value: score.value })),
    });
    await waitForSpanReadable(backendClient, spans[0].id);
    await backendClient.addSpanFeedbackScore({
      spanId: spans[0].id,
      name: spanScore.name,
      value: spanScore.value,
    });

    // The discriminating step. Polled per score rather than read once: the
    // writes are eventually consistent, and a bystander that was not yet
    // readable when the browser opened would make every "it survived"
    // assertion below a statement about a score that was never there.
    for (const score of traceScores) {
      await backendClient.pollTraceForFeedbackScore(traceId, score.name, {
        until: (stored) => stored.value === score.value,
      });
    }
    await backendClient.pollSpanForFeedbackScore(spans[0].id, spanScore.name, {
      until: (stored) => stored.value === spanScore.value,
    });

    const sibling = await backendClient.getSpan(spans[1].id);
    if (sibling === null) {
      throw new Error(
        `[scoredTraceSpans fixture] the unscored span ${spans[1].id} is not readable — ` +
          'it is the bystander of the span-level delete and must exist before the test runs',
      );
    }
    if ((sibling.feedbackScores ?? []).length !== 0) {
      throw new Error(
        `[scoredTraceSpans fixture] the unscored span ${spans[1].id} already carries ` +
          `${JSON.stringify(sibling.feedbackScores)} — it must start with none, or a delete ` +
          'that reached it would be invisible',
      );
    }

    const ref: ScoredTraceSpansRef = {
      traceId,
      traceName,
      projectId: project.id,
      projectName: project.name,
      spans,
      traceScores,
      spanScore,
    };
    await testInfo.attach('opik.scoredTraceSpans', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './openrouter-regen-repriced-spans.fixture';
