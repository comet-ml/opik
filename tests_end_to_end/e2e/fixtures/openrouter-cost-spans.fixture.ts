import { test as baseTest } from './project-overview-copy.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * Which spelling of an OpenRouter model id a seed carries.
 *
 * `prefixed` is the three-segment spelling the OpenRouter price file keys its
 * rows under (`openrouter/amazon/nova-lite-v1`). `bare` is the two-segment
 * spelling Opik ITSELF writes: the model picker stores the registry id, and
 * `ExperimentTracePersistence` writes `ctx.prompt().model()` verbatim. `none`
 * is the unpriced control, which is neither.
 */
export type OpenRouterSpelling = 'prefixed' | 'bare' | 'none';

export interface OpenRouterCostSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The model id as a client logs it. */
  model: string;
  /** The `provider` field as a client logs it. */
  provider: string;
  spelling: OpenRouterSpelling;
  /**
   * The pair this vector belongs to — the two spellings of ONE OpenRouter
   * model. `null` for the control, which has no twin.
   *
   * The pairing is the point: both members report the identical token counts
   * and name the identical model, differing only in how the id is spelled, so
   * "they cost the same" is a claim about the spelling and about nothing else.
   */
  pair: string | null;
  /**
   * The amount the OpenRouter price file's own published rates give for this
   * model at the seeded token counts — the CORRECT cost for both spellings, not
   * the cost each resolves today. 0 for the control, which no row prices.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * price is the server's own answer and this is only what it is compared
   * against.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface OpenRouterCostSpansRef {
  traceId: string;
  spans: OpenRouterCostSpanSeed[];
  promptTokens: number;
  completionTokens: number;
  /**
   * Sum of `expectedCost` across every span — what the trace must roll up to
   * once both spellings of each model price. Not what it rolls up to today.
   */
  expectedTraceCost: number;
}

export interface OpenRouterCostSpansFixtures {
  openRouterCostSpans: OpenRouterCostSpansRef;
}

/**
 * A million of each, so every resolved price clears `formatCost`'s `<$0.01`
 * floor and renders as an exact dollar amount the UI half of the spec can read.
 *
 * OpenRouter's rows are cheap — `amazon/nova-lite-v1` is $0.06/M input — so a
 * realistic token count would render as `<$0.01` for both members of a pair
 * alike, and the pair would then be indistinguishable in the panel whichever
 * way the lookup went.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/**
 * Two OpenRouter models, each seeded under BOTH spellings of its id, plus one
 * control.
 *
 * `model_prices_openrouter.json` keys its 468 rows three-segment
 * (`openrouter/<vendor>/<model>`), and `CostService.parseModelName` strips
 * everything up to the FIRST slash from the key as well as from the span's
 * model — so a row lands in the price map two-segment (`amazon/nova-lite-v1`)
 * and only a THREE-segment span model can reach it. The two-segment spelling,
 * which is the one Opik writes for its own runs, strips to one segment
 * (`nova-lite-v1`) and matches nothing.
 *
 * Both models are picked for what they pin beyond the spelling:
 *
 *   openrouter/amazon/nova-lite-v1   $0.06/M in + $0.24/M out  -> $0.30
 *   openrouter/aion-labs/aion-2.0    $0.80/M in + $1.60/M out  -> $2.40
 *
 * `aion-2.0` publishes a `cache_read_input_token_cost` and `nova-lite-v1` does
 * not, so the pair also covers both branches `resolveCalculator` can send an
 * OpenRouter row down — a cache-priced row whose span reports no cached tokens
 * must still bill its plain input rate.
 *
 * Two pairs rather than one because a single pair cannot tell a spelling rule
 * from a one-off: a lookup that happened to miss one vendor prefix would
 * satisfy half of this, and `amazon/` and `aion-labs/` are both vendor prefixes
 * that are NOT themselves LiteLLM providers, so neither can resolve through
 * `findModelPrice`'s provider-prefix fallback either.
 *
 * The control is what makes the amounts a finding rather than a tautology: it
 * names a provider and a model that appear in no price row at all, so it must
 * stay unpriced. Without it, "these resolve a price" could not be told apart
 * from a lookup that had started inventing rows for whatever it was handed.
 */
const SPAN_SEEDS: Array<Omit<OpenRouterCostSpanSeed, 'name'>> = [
  {
    key: 'nova-prefixed',
    pair: 'nova',
    spelling: 'prefixed',
    model: 'openrouter/amazon/nova-lite-v1',
    provider: 'openrouter',
    expectedCost: 0.3,
    workings: '1.0M x $0.06/M in + 1.0M x $0.24/M out',
  },
  {
    key: 'nova-bare',
    pair: 'nova',
    spelling: 'bare',
    // The spelling Opik's own picker stores and its persistence writes.
    model: 'amazon/nova-lite-v1',
    provider: 'openrouter',
    expectedCost: 0.3,
    workings: 'the same row as nova-prefixed, reached through the id Opik writes',
  },
  {
    key: 'aion-prefixed',
    pair: 'aion',
    spelling: 'prefixed',
    model: 'openrouter/aion-labs/aion-2.0',
    provider: 'openrouter',
    expectedCost: 2.4,
    workings: '1.0M x $0.80/M in + 1.0M x $1.60/M out, with no cached tokens reported',
  },
  {
    key: 'aion-bare',
    pair: 'aion',
    spelling: 'bare',
    model: 'aion-labs/aion-2.0',
    provider: 'openrouter',
    expectedCost: 2.4,
    workings: 'the same row as aion-prefixed, reached through the id Opik writes',
  },
  {
    key: 'unpriced-control',
    pair: null,
    spelling: 'none',
    // Deliberately nonsense, and deliberately prefixed: a name a future price
    // file could plausibly acquire would turn this control into a flake.
    model: 'openrouter/qa-no-such-vendor/qa-no-such-model',
    provider: 'openrouter',
    expectedCost: 0,
    workings:
      'no price row names this vendor or model, so a cost here means the lookup is inventing ' +
      'rows for whatever it is handed',
  },
];

/**
 * One trace carrying five LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `newProviderCostSpans` does it: the subject is the model id as
 * a client spells it, and the price-key parse splits on its first slash — so
 * every character of that id has to arrive exactly as written, with no SDK
 * normalisation in between. The trace is a REST write for the same reason: the
 * bridge's `/traces` route emits through `@opik.track`, which would add a root
 * span carrying no model and no usage and break the "the trace carries exactly
 * the seeded spans" read the spec opens with.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind.
 */
export const test = baseTest.extend<OpenRouterCostSpansFixtures>({
  openRouterCostSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: OpenRouterCostSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-openrouter-cost-trace`,
        source: 'sdk',
        input: { question: 'seeded OpenRouter price resolution' },
        output: { answer: 'seeded OpenRouter price resolution' },
        startTime: now,
        endTime: now,
      });

      for (const span of spans) {
        await backendClient.createSpan({
          id: uuid7(),
          traceId,
          projectName: project.name,
          name: span.name,
          source: 'sdk',
          type: 'llm',
          model: span.model,
          provider: span.provider,
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: OpenRouterCostSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.openRouterCostSpans', {
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
            `[openRouterCostSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './project-overview-copy.fixture';
