import { test as baseTest } from './grouped-dataset.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface TokenUsageSpanSeed {
  name: string;
  model: string;
  provider: string;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
}

export interface TokenUsageSpansRef {
  /** Every seeded span name starts with this — the workspace-wide read's only scope. */
  spanNamePrefix: string;
  traceId: string;
  spans: TokenUsageSpanSeed[];
  /** Seeded totals, summed across every span. */
  totals: { promptTokens: number; completionTokens: number; totalTokens: number };
  /** Seeded `total_tokens` per provider. */
  totalTokensByProvider: Record<string, number>;
  /** Earliest instant the seeded spans can fall on, for a metrics window. */
  windowStart: Date;
}

export interface TokenUsageSpansFixtures {
  tokenUsageSpans: TokenUsageSpansRef;
}

const OPENAI_MODEL = 'gpt-4o-mini';
const ANTHROPIC_MODEL = 'claude-3-5-haiku-20241022';

/**
 * Five LLM spans with known, uneven token counts across two providers.
 *
 * Uneven on purpose: equal counts would let a query that grouped or summed
 * wrongly still land on the right grand total. As seeded —
 *   prompt 107 + completion 63 = total 170,   openai 102 / anthropic 68
 * — the per-provider split, the per-usage-key split and the grand total are
 * three independently wrong-able numbers.
 */
const SPAN_SEEDS: Array<Omit<TokenUsageSpanSeed, 'name'>> = [
  { model: OPENAI_MODEL, provider: 'openai', promptTokens: 20, completionTokens: 10, totalTokens: 30 },
  { model: OPENAI_MODEL, provider: 'openai', promptTokens: 25, completionTokens: 15, totalTokens: 40 },
  { model: OPENAI_MODEL, provider: 'openai', promptTokens: 22, completionTokens: 10, totalTokens: 32 },
  { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 20, completionTokens: 14, totalTokens: 34 },
  { model: ANTHROPIC_MODEL, provider: 'anthropic', promptTokens: 20, completionTokens: 14, totalTokens: 34 },
];

/**
 * One trace carrying five LLM spans with known usage, named under a per-test
 * prefix.
 *
 * The prefix is what makes a workspace-wide aggregation assertable at all: the
 * endpoint under test reads every project in the workspace, which is shared and
 * concurrently written by other specs, so nothing about the *unfiltered* answer
 * is deterministic. A `name contains <prefix>` filter narrows it to exactly
 * these five spans, and that filter is itself the thing under test.
 *
 * Teardown deletes the trace (and with it its spans) here rather than in the
 * test, so an assertion failure cannot leave spans behind that would pollute a
 * later run's unfiltered numbers.
 */
export const test = baseTest.extend<TokenUsageSpansFixtures>({
  tokenUsageSpans: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const spanNamePrefix = `${testNamespace}-span`;
    const windowStart = new Date();

    const spans: TokenUsageSpanSeed[] = SPAN_SEEDS.map((seed, i) => ({
      ...seed,
      name: `${spanNamePrefix}-${i + 1}`,
    }));

    const created = await sdkClient.python.createNestedTrace({
      project_name: project.name,
      name: `${testNamespace}-usage-trace`,
      input: { question: 'seeded token usage' },
      output: { answer: 'seeded token usage' },
      spans: spans.map((span) => ({
        name: span.name,
        type: 'llm' as const,
        model: span.model,
        provider: span.provider,
        usage: {
          prompt_tokens: span.promptTokens,
          completion_tokens: span.completionTokens,
          total_tokens: span.totalTokens,
        },
      })),
    });

    if (created.span_count !== spans.length) {
      throw new Error(
        `[tokenUsageSpans fixture] expected ${spans.length} spans, bridge reported ${created.span_count}`,
      );
    }

    const sum = (pick: (s: TokenUsageSpanSeed) => number): number =>
      spans.reduce((acc, s) => acc + pick(s), 0);

    const totalTokensByProvider: Record<string, number> = {};
    for (const span of spans) {
      totalTokensByProvider[span.provider] =
        (totalTokensByProvider[span.provider] ?? 0) + span.totalTokens;
    }

    const ref: TokenUsageSpansRef = {
      spanNamePrefix,
      traceId: created.id,
      spans,
      totals: {
        promptTokens: sum((s) => s.promptTokens),
        completionTokens: sum((s) => s.completionTokens),
        totalTokens: sum((s) => s.totalTokens),
      },
      totalTokensByProvider,
      // Back off a minute: the bridge stamps the spans a moment after this
      // fixture starts, and a window that opens exactly now can race them out.
      windowStart: new Date(windowStart.getTime() - 60_000),
    };

    await testInfo.attach('opik.tokenUsageSpans', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces([created.id]);
      } catch (err) {
        console.warn(`[tokenUsageSpans fixture] delete warning for trace ${created.id}:`, err);
      }
    }
  },
});

export { expect } from './grouped-dataset.fixture';
