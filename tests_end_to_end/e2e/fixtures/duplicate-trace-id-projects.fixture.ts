import { expect } from '@playwright/test';
import { test as baseTest, waitForSpanReadable } from './scored-trace-spans.fixture';
import { uuid7 } from '../core/backend';
import type { BackendClient, ProjectRef } from '../core/backend';

/** One seeded span of a project's half of the shared trace. */
export interface DuplicateTraceSpanSeed {
  id: string;
  name: string;
  type: 'llm' | 'tool';
  provider?: string;
  /** Client-supplied, so the expected totals are literals and not price-table arithmetic. */
  totalEstimatedCost: number;
  usage?: Record<string, number>;
}

/**
 * What one project's own copy of the shared trace holds, and so what the reads
 * scoped to it must report.
 *
 * `expected` is the whole answer rather than a field or two: the point of the
 * seed is that EVERY aggregate differs between the two projects, so a leak in
 * any one of the nine joins opik#9643 re-keyed is visible as a wrong number
 * rather than as a missing row.
 */
export interface DuplicateTraceIdProjectSeed {
  project: ProjectRef;
  /** Which side of the `ORDER BY project_id DESC LIMIT 1` tie-break this project is on. */
  role: 'lower' | 'higher';
  /** This project's own name for the shared trace id. */
  traceName: string;
  spans: DuplicateTraceSpanSeed[];
  /** The comment written against this project's copy of the trace. */
  comment: string;
  /** This project's guardrail: its `secondary_id`, type and verdict. */
  guardrail: { secondaryId: string; name: 'PII' | 'TOPIC'; result: 'failed' | 'passed' };
  /** The score name only this project uses, at the value only this project wrote. */
  uniqueScore: { name: string; value: number };
  /** This project's value for the score name BOTH projects wrote. */
  sharedScoreValue: number;
  /** This project's value for the span score name both projects wrote. */
  spanScoreValue: number;
  expected: {
    totalEstimatedCost: number;
    usage: Record<string, number>;
    spanCount: number;
    llmSpanCount: number;
    hasToolSpans: boolean;
    providers: string[];
  };
}

export interface DuplicateTraceIdProjectsRef {
  /** The one trace id both projects hold. */
  traceId: string;
  /** The score name both projects wrote, at different values. */
  sharedScoreName: string;
  /** The span score name both projects wrote, at different values. */
  spanScoreName: string;
  lower: DuplicateTraceIdProjectSeed;
  higher: DuplicateTraceIdProjectSeed;
}

export interface DuplicateTraceIdProjectsFixtures {
  duplicateTraceIdProjects: DuplicateTraceIdProjectsRef;
}

/** The shared trace score name's two values. The higher project's is the answer. */
const SHARED_SCORE_LOWER = 0.1;
const SHARED_SCORE_HIGHER = 0.9;
/** The shared SPAN score name's two values — averaged if the join leaks. */
const SPAN_SCORE_LOWER = 0.2;
const SPAN_SCORE_HIGHER = 0.8;

/**
 * True when `left` sorts strictly above `right` as ClickHouse compares the
 * `project_id` column in `ORDER BY (workspace_id, project_id, id) DESC`.
 *
 * Compared as the canonical lowercase hex string, which for the ids this suite
 * actually sees is the same order as every other plausible reading of a UUID.
 * A UUIDv7's leading 48 bits are a millisecond timestamp, so a present-day id
 * begins `01…`: the top bit of the first 64 bits is clear, which is what makes
 * the byte-wise, unsigned-integer and signed-integer orderings agree with the
 * lexicographic one. {@link assertComparableIds} asserts that premise rather
 * than assuming it, so an id generator that stopped producing v7s fails here
 * instead of silently making this helper describe a different order.
 */
function sortsHigher(left: string, right: string): boolean {
  return left.toLowerCase() > right.toLowerCase();
}

/** Fail unless both ids are present-day UUIDv7s — see {@link sortsHigher}. */
function assertComparableIds(a: string, b: string): void {
  for (const id of [a, b]) {
    const hex = id.replace(/-/g, '').toLowerCase();
    expect(hex, `'${id}' is a 32-nibble UUID`).toHaveLength(32);
    expect(
      parseInt(hex[0], 16),
      `'${id}' must have the top bit of its first 64 bits clear, or the string order this ` +
        'fixture sorts by is not the order ClickHouse applies to project_id',
    ).toBeLessThan(8);
  }
}

/**
 * Write one project's copy of the shared trace id, then wait until that
 * project's own listing can see it.
 *
 * The wait is what makes the write ORDER real, and the order matters for
 * exactly one reason: `POST /v1/private/traces/{id}/comments` resolves its
 * project FROM THE ID (`SELECT_PROJECT_ID_FROM_TRACE`, which opik#9643 made
 * `ORDER BY project_id DESC LIMIT 1`), so the lower project's comment can only
 * be written while the lower project is the id's only holder. Polled through
 * the project-scoped listing, which cannot fan out, so this wait can never be
 * satisfied by the other project's copy.
 */
async function writeTraceInto(
  backendClient: BackendClient,
  seed: DuplicateTraceIdProjectSeed,
  traceId: string,
): Promise<void> {
  await backendClient.createTracesBatch({
    projectName: seed.project.name,
    traces: [
      {
        id: traceId,
        name: seed.traceName,
        input: { wrote: seed.traceName },
        output: {},
      },
    ],
  });
  await expect
    .poll(
      async () =>
        (
          await backendClient.findTraceAggregatesInProject({
            projectId: seed.project.id,
            traceId,
          })
        ).trace?.name ?? null,
      {
        timeout: 120_000,
        intervals: [500, 1_000, 2_000],
        message:
          `the ${seed.role} project's copy of ${traceId} must be readable in ` +
          `'${seed.project.name}' before anything else is written against the id`,
      },
    )
    .toBe(seed.traceName);
}

/**
 * One trace id written into TWO projects, each holding its own spans, costs,
 * usage, providers, trace scores, span scores, comment and guardrail.
 *
 * This is the state opik#9643 (issue-7473) exists for and the one the estate
 * could not reach. A trace id is minted by the client, so nothing refuses a
 * second write of it under another project name — a replayed job with
 * `OPIK_PROJECT_NAME` changed does it — and `TraceDAO.SELECT_BY_IDS` used to
 * group its spans, comments, feedback scores, span scores, guardrails and
 * annotation-queue subqueries on the trace id ALONE. So the panel every user
 * opens all day reported one project's cost and token totals summed with
 * another's, and that is invisible: a cross-project roll-up renders as a
 * perfectly ordinary number.
 *
 * ## The decoy holds the LARGER numbers
 *
 * `GET /v1/private/traces/{id}` resolves one project for an id in several — the
 * highest `project_id` — so the project this fixture labels `higher` is the one
 * the read must describe, and `lower` is the decoy. The decoy deliberately
 * carries MORE spans, a HIGHER cost, more usage, two providers the other does
 * not have and the trace's only `tool` span, so a leak shows up as an inflated
 * total, an extra span, an unexpected provider and `has_tool_spans` flipping to
 * true. The reverse arrangement would hide a leak behind the bigger number
 * being the right one.
 *
 * Both halves share ONE trace-score name and ONE span-score name at different
 * values, plus a name unique to each. The shared names are the strongest
 * discriminators available: `span_feedback_scores` is documented as an average
 * per score name over the trace's spans, so a leaked span score surfaces as the
 * MEAN of the two values rather than as a second row, which no assertion about
 * row counts would catch.
 *
 * ## Which project is which is decided by the ids, not by creation order
 *
 * Two projects created milliseconds apart can land in the same UUIDv7
 * millisecond, after which the tail is random and "the second one sorts higher"
 * is a coin toss. So the fixture reads the two ids it was given and LABELS them,
 * rather than asserting an order it arranged — the alternative is a premise
 * assertion that fails at random. The order itself is still asserted
 * (`assertComparableIds`), because the comparison only means what this fixture
 * says it means for present-day v7 ids.
 *
 * ## It proves it can discriminate before any spec reads anything
 *
 * The last step reads each project's own listing and asserts that project's
 * whole expected aggregate set. Without it, a seed whose spans silently landed
 * in one project would leave every scoping assertion trivially satisfiable, and
 * the spec would read as coverage for good.
 *
 * Teardown: both projects come from the `project` / `secondProject` fixtures,
 * which delete them; the traces are swept explicitly through
 * `registerProjectTracesCleanup` because `deleteProject` does not cascade to
 * them (see `trace.fixture.ts`). The guardrail rows cannot be deleted at all —
 * the resource exposes no delete — but they are keyed to a project and a trace
 * that both go away, so nothing can read them afterwards.
 */
export const test = baseTest.extend<DuplicateTraceIdProjectsFixtures>({
  duplicateTraceIdProjects: async (
    { backendClient, project, secondProject, testNamespace, registerProjectTracesCleanup },
    use,
    testInfo,
  ) => {
    assertComparableIds(project.id, secondProject.id);
    const [higherProject, lowerProject] = sortsHigher(project.id, secondProject.id)
      ? [project, secondProject]
      : [secondProject, project];

    registerProjectTracesCleanup(lowerProject.id);
    registerProjectTracesCleanup(higherProject.id);

    const traceId = uuid7();
    const sharedScoreName = `${testNamespace}-shared-score`;
    const spanScoreName = `${testNamespace}-span-score`;

    const lower: DuplicateTraceIdProjectSeed = {
      project: lowerProject,
      role: 'lower',
      traceName: `${testNamespace}-decoy-trace`,
      spans: [
        {
          id: uuid7(),
          name: `${testNamespace}-decoy-llm-one`,
          type: 'llm',
          provider: 'openai',
          totalEstimatedCost: 15.75,
          usage: { prompt_tokens: 1_000, completion_tokens: 1_000, total_tokens: 2_000 },
        },
        {
          id: uuid7(),
          name: `${testNamespace}-decoy-llm-two`,
          type: 'llm',
          provider: 'google_ai',
          totalEstimatedCost: 15.75,
          usage: { prompt_tokens: 1_000, completion_tokens: 1_000, total_tokens: 2_000 },
        },
        {
          id: uuid7(),
          name: `${testNamespace}-decoy-tool`,
          type: 'tool',
          totalEstimatedCost: 0,
        },
      ],
      comment: `${testNamespace} decoy project comment`,
      guardrail: { secondaryId: uuid7(), name: 'PII', result: 'failed' },
      uniqueScore: { name: `${testNamespace}-decoy-only`, value: 0.2 },
      sharedScoreValue: SHARED_SCORE_LOWER,
      spanScoreValue: SPAN_SCORE_LOWER,
      expected: {
        totalEstimatedCost: 31.5,
        usage: { prompt_tokens: 2_000, completion_tokens: 2_000, total_tokens: 4_000 },
        spanCount: 3,
        llmSpanCount: 2,
        hasToolSpans: true,
        providers: ['google_ai', 'openai'],
      },
    };

    const higher: DuplicateTraceIdProjectSeed = {
      project: higherProject,
      role: 'higher',
      traceName: `${testNamespace}-returned-trace`,
      spans: [
        {
          id: uuid7(),
          name: `${testNamespace}-returned-llm`,
          type: 'llm',
          provider: 'anthropic',
          totalEstimatedCost: 6,
          usage: { prompt_tokens: 100, completion_tokens: 100, total_tokens: 200 },
        },
      ],
      comment: `${testNamespace} returned project comment`,
      guardrail: { secondaryId: uuid7(), name: 'TOPIC', result: 'passed' },
      uniqueScore: { name: `${testNamespace}-returned-only`, value: 0.8 },
      sharedScoreValue: SHARED_SCORE_HIGHER,
      spanScoreValue: SPAN_SCORE_HIGHER,
      expected: {
        totalEstimatedCost: 6,
        usage: { prompt_tokens: 100, completion_tokens: 100, total_tokens: 200 },
        spanCount: 1,
        llmSpanCount: 1,
        hasToolSpans: false,
        providers: ['anthropic'],
      },
    };

    // The lower project first, and its comment while it alone holds the id —
    // see `writeTraceInto`.
    await writeTraceInto(backendClient, lower, traceId);
    await backendClient.addComment({ entity: 'traces', entityId: traceId, text: lower.comment });
    await writeTraceInto(backendClient, higher, traceId);
    await backendClient.addComment({ entity: 'traces', entityId: traceId, text: higher.comment });

    for (const seed of [lower, higher]) {
      await backendClient.createSpansBatch({
        projectName: seed.project.name,
        spans: seed.spans.map((span) => ({
          id: span.id,
          traceId,
          name: span.name,
          source: 'sdk' as const,
          type: span.type,
          totalEstimatedCost: span.totalEstimatedCost,
          ...(span.provider === undefined ? {} : { provider: span.provider, model: span.provider }),
          ...(span.usage === undefined ? {} : { usage: span.usage }),
        })),
      });

      // Project-scoped by name, so each project's copy of the id gets its own
      // scores — the per-id endpoint would resolve one project for both.
      await backendClient.setTraceFeedbackScores({
        projectName: seed.project.name,
        scores: [
          { traceId, name: sharedScoreName, value: seed.sharedScoreValue },
          { traceId, name: seed.uniqueScore.name, value: seed.uniqueScore.value },
        ],
      });
      // The batch write's 204 does not make the span queryable, and the
      // per-span score endpoint resolves its project FROM the span — so it
      // answers a hard 404 until this settles. See `waitForSpanReadable`.
      await waitForSpanReadable(backendClient, seed.spans[0].id);
      await backendClient.addSpanFeedbackScore({
        spanId: seed.spans[0].id,
        name: spanScoreName,
        value: seed.spanScoreValue,
      });
      await backendClient.createGuardrailsBatch({
        projectName: seed.project.name,
        guardrails: [
          {
            entityId: traceId,
            secondaryId: seed.guardrail.secondaryId,
            name: seed.guardrail.name,
            result: seed.guardrail.result,
            config: { seeded_by: seed.traceName },
            details: { role: seed.role },
          },
        ],
      });
    }

    // Each project's own listing really reports its own aggregates — the
    // premise every assertion downstream rests on. Polled as a whole tuple
    // because the spans, scores and comment land as separate ingests and a
    // partial read is indistinguishable from a leak.
    for (const seed of [lower, higher]) {
      await expect
        .poll(
          async () => {
            const { trace } = await backendClient.findTraceAggregatesInProject({
              projectId: seed.project.id,
              traceId,
            });
            if (trace === null) return null;
            return {
              spanCount: trace.spanCount,
              llmSpanCount: trace.llmSpanCount,
              hasToolSpans: trace.hasToolSpans,
              totalEstimatedCost: trace.totalEstimatedCost,
              providers: trace.providers,
            };
          },
          {
            timeout: 180_000,
            intervals: [1_000, 2_000, 5_000],
            message:
              `the ${seed.role} project's own listing must report its own aggregates before ` +
              'the by-id read is asked anything — a seed that half landed would make every ' +
              'scoping assertion trivially true',
          },
        )
        .toEqual({
          spanCount: seed.expected.spanCount,
          llmSpanCount: seed.expected.llmSpanCount,
          hasToolSpans: seed.expected.hasToolSpans,
          totalEstimatedCost: seed.expected.totalEstimatedCost,
          providers: seed.expected.providers,
        });
    }

    const ref: DuplicateTraceIdProjectsRef = {
      traceId,
      sharedScoreName,
      spanScoreName,
      lower,
      higher,
    };
    await testInfo.attach('opik.duplicateTraceIdProjects', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './scored-trace-spans.fixture';
