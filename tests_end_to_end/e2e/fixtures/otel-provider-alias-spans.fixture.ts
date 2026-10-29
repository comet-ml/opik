import { test as baseTest } from './compare-button-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import type { OtelSpanSeed } from '../core/backend';

/**
 * One OTel span of the alias matrix, and what the backend must make of it.
 *
 * `reportedProvider` is what the instrumentation put on `gen_ai.provider.name`;
 * `storedProvider` is the canonical Opik name the span must end up carrying. The
 * two being separate fields is the whole point — a resolver that stopped
 * aliasing would store the reported value, and the span would still look
 * perfectly ordinary apart from a cost of nothing.
 */
export interface OtelAliasSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The value sent on `gen_ai.provider.name`. */
  reportedProvider: string;
  /** The provider the span must be STORED with, after `ProviderResolvers`. */
  storedProvider: string;
  model: string;
  /**
   * The cost the price table must resolve for this vector, or 0 for a provider
   * value the resolver must deliberately leave alone.
   *
   * Never sent: the seed carries token counts and no `gen_ai.usage.cost`, so the
   * price is the server's own answer and this is only what it is compared to.
   */
  expectedCost: number;
  /**
   * The vector this one is read against, for the aliased spans: an
   * otherwise-identical span that reported the canonical provider directly.
   *
   * Absent for the canonical spans themselves and for the excluded ones.
   */
  controlKey?: string;
  /** Why an excluded vector must not be priced, for the assertion message. */
  zeroCostReason?: string;
}

export interface OtelProviderAliasSpansRef {
  /** The Opik trace the exported spans were attached to via `opik.trace_id`. */
  traceId: string;
  spans: OtelAliasSpanSeed[];
  inputTokens: number;
  outputTokens: number;
}

export interface OtelProviderAliasSpansFixtures {
  otelProviderAliasSpans: OtelProviderAliasSpansRef;
}

/**
 * A million of each, for the same reason as `modelCostSpans`: every resolved
 * price has to clear `formatCost`'s `<$0.01` floor so the panel renders an exact
 * dollar amount an assertion can read. At a realistic token count all three
 * models alike would render as `<$0.01`, which no assertion could tell apart
 * from an unpriced one — and "unpriced" is precisely the failure under test.
 */
const INPUT_TOKENS = 1_000_000;
const OUTPUT_TOKENS = 1_000_000;

/**
 * The three quarkus-langchain4j provider spellings opik#8621 aliases, each
 * paired with a control that reports the canonical name directly, plus the two
 * values the PR deliberately leaves alone.
 *
 * quarkus-langchain4j writes LangChain4j's `ModelProvider` enum lowercased into
 * `gen_ai.provider.name` instead of the OTel semconv value, so `open_ai`,
 * `amazon_bedrock` and `google_ai_gemini` reach `CostService` matching no
 * pricing row at all. Each pair is identical but for the provider string, which
 * is what makes the comparison discriminating: an absolute amount alone could
 * not tell "the alias resolved" apart from "the price table moved", and an
 * equality alone could not tell it apart from "neither side is priced".
 *
 * Models are chosen to be the simplest thing that can be priced under each
 * canonical provider — a row whose ONLY cost fields are `input_cost_per_token`
 * and `output_cost_per_token`, so the expected amount is the two rates times the
 * two counts with no cache, audio, character or prompt-tier term in it:
 *
 *   gpt-3.5-turbo                  openai     $0.5/M in + $1.5/M out  -> $2.00
 *   amazon.titan-text-express-v1   bedrock    $1.3/M in + $1.7/M out  -> $3.00
 *   gemini-gemma-2-27b-it          google_ai  $0.35/M in + $1.05/M out -> $1.40
 *
 * The two excluded values are not an afterthought. `GenAiProviderAliasResolver`
 * documents why each is left out — `google_genai` names more than one Google
 * backend, and Quarkus' Azure OpenAI model reports `OTHER` rather than its own
 * enum value — so an alias map that grew too eagerly would price them, and
 * `azure_open_ai` landing on the OpenAI table is exactly the mis-attribution the
 * resolver's own comment says it is avoiding. Both are seeded on the same models
 * as a priced pair, so "not priced" cannot be explained by the model.
 */
const ALIAS_SEEDS: Array<Omit<OtelAliasSpanSeed, 'name'>> = [
  {
    key: 'open-ai-aliased',
    reportedProvider: 'open_ai',
    storedProvider: 'openai',
    model: 'gpt-3.5-turbo',
    expectedCost: 2,
    controlKey: 'openai-canonical',
  },
  {
    key: 'openai-canonical',
    reportedProvider: 'openai',
    storedProvider: 'openai',
    model: 'gpt-3.5-turbo',
    expectedCost: 2,
  },
  {
    key: 'amazon-bedrock-aliased',
    reportedProvider: 'amazon_bedrock',
    storedProvider: 'bedrock',
    model: 'amazon.titan-text-express-v1',
    expectedCost: 3,
    controlKey: 'bedrock-canonical',
  },
  {
    key: 'bedrock-canonical',
    reportedProvider: 'bedrock',
    storedProvider: 'bedrock',
    model: 'amazon.titan-text-express-v1',
    expectedCost: 3,
  },
  {
    key: 'google-ai-gemini-aliased',
    reportedProvider: 'google_ai_gemini',
    storedProvider: 'google_ai',
    model: 'gemini-gemma-2-27b-it',
    expectedCost: 1.4,
    controlKey: 'google-ai-canonical',
  },
  {
    key: 'google-ai-canonical',
    reportedProvider: 'google_ai',
    storedProvider: 'google_ai',
    model: 'gemini-gemma-2-27b-it',
    expectedCost: 1.4,
  },
  {
    key: 'google-genai-excluded',
    reportedProvider: 'google_genai',
    // Stored verbatim: not in the alias map, and not one of the two ambiguous
    // values `GoogleProviderResolver` claims either.
    storedProvider: 'google_genai',
    model: 'gemini-gemma-2-27b-it',
    expectedCost: 0,
    zeroCostReason:
      'google_genai names more than one Google backend, so the resolver leaves it alone; a cost ' +
      'here means it was aliased onto one of them and the span is attributed to a backend nobody ' +
      'established it ran on',
  },
  {
    key: 'azure-open-ai-excluded',
    reportedProvider: 'azure_open_ai',
    storedProvider: 'azure_open_ai',
    model: 'gpt-3.5-turbo',
    expectedCost: 0,
    zeroCostReason:
      "Quarkus' Azure OpenAI model reports OTHER rather than this value, so it is deliberately " +
      'unaliased; a cost here means an Azure-fronted call was billed against the OpenAI table',
  },
];

/**
 * One Opik trace carrying eight spans written through the OTLP ingestion
 * endpoint — the only fixture in the estate that reaches it.
 *
 * Every other span seed sets `provider` as a literal field on
 * `POST /v1/private/spans`, which skips `ProviderResolvers` entirely. So the
 * aliasing this seeds is not merely uncovered, it is unreachable by any existing
 * fixture: the same eight spans written the usual way would store exactly what
 * they were given and prove nothing.
 *
 * The trace is created first and handed to the export as `opik.trace_id`, rather
 * than letting the service mint one from the OTLP trace id. Two reasons, both
 * about the assertions rather than the seed: a caller can then read the spans
 * back with `listSpanCosts({ traceId })` and open them in the panel by id — the
 * same reads `span-cost-resolution.spec.ts` makes — and a seed that failed to
 * land shows up as an empty read against a trace that provably exists, instead
 * of as a lookup for a UUID the test would have had to re-derive.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that opens
 * the moment the trace exists: the export and the visibility poll both run
 * BEFORE `use()`, so a failure in either would otherwise skip the only cleanup
 * and leave priced spans behind.
 */
export const test = baseTest.extend<OtelProviderAliasSpansFixtures>({
  otelProviderAliasSpans: async (
    { backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const spans: OtelAliasSpanSeed[] = ALIAS_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    await backendClient.createTraceWithSource({
      id: traceId,
      projectName: project.name,
      name: `${testNamespace}-otel-alias-trace`,
      source: 'sdk',
      input: { question: 'seeded otel provider alias resolution' },
      output: { answer: 'seeded otel provider alias resolution' },
    });

    try {
      const otelSpans: OtelSpanSeed[] = spans.map((span) => ({
        name: span.name,
        attributes: {
          // `gen_ai.provider.name` and not `gen_ai.system`: the deprecated
          // attribute stays authoritative, so a seed carrying both would resolve
          // through the OTHER branch and never reach the alias map at all.
          'gen_ai.provider.name': span.reportedProvider,
          'gen_ai.request.model': span.model,
          // Numbers, so they arrive as OTLP `intValue` —
          // `extractUsageField` reads `hasIntValue()` and skips a count that
          // arrived as a string, which would leave every span unpriced and the
          // aliased-vs-canonical comparison trivially satisfied.
          'gen_ai.usage.input_tokens': INPUT_TOKENS,
          'gen_ai.usage.output_tokens': OUTPUT_TOKENS,
        },
      }));

      await backendClient.postOtelSpans({
        projectName: project.name,
        opikTraceId: traceId,
        spans: otelSpans,
      });

      const ref: OtelProviderAliasSpansRef = {
        traceId,
        spans,
        inputTokens: INPUT_TOKENS,
        outputTokens: OUTPUT_TOKENS,
      };

      await testInfo.attach('opik.otelProviderAliasSpans', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      // Swallow-and-warn, never rethrow: a delete that fails here would replace
      // whichever seeding or assertion error actually broke the test.
      if (!shouldLeaveArtifacts(testInfo)) {
        try {
          await backendClient.deleteTraces([traceId]);
        } catch (err) {
          console.warn(
            `[otelProviderAliasSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './compare-button-experiments.fixture';
