import { test as baseTest } from './cache-dialect-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

export interface NewProviderCostSpanSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /** The model id as the client logs it. */
  model: string;
  /** The `provider` field as the client logs it. */
  provider: string;
  /**
   * The cost the shipped price table says this vector costs, or 0 for the
   * control, which names a provider no price row mentions.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * price is the server's own answer and this is only what it is compared
   * against.
   */
  expectedCost: number;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface NewProviderCostSpansRef {
  traceId: string;
  spans: NewProviderCostSpanSeed[];
  promptTokens: number;
  completionTokens: number;
  /** Sum of `expectedCost` across every span — the trace's rolled-up total. */
  expectedTraceCost: number;
}

export interface NewProviderCostSpansFixtures {
  newProviderCostSpans: NewProviderCostSpansRef;
}

/**
 * 150k prompt / 50k completion.
 *
 * Two constraints pick these, and only a narrow band satisfies both. Every
 * resolved price has to clear `formatCost`'s `<$0.01` floor so the UI half can
 * read an exact dollar amount — and the cheapest row here is `voyage-3-large`
 * at $0.18/M input with its output priced at zero, which needs about 56k prompt
 * tokens to reach a cent. At the same time the prompt must stay UNDER 200k:
 * `vertex_ai/gemini-3-pro-preview` publishes `above_200k_tokens` rates at twice
 * its base ones, and a tiered vector would make this fixture about prompt tiers
 * rather than about which providers resolve a price at all.
 */
const PROMPT_TOKENS = 150_000;
const COMPLETION_TOKENS = 50_000;

/**
 * Six providers that the removed `PROVIDERS_MAPPING` whitelist used to drop,
 * plus one control.
 *
 * Before opik#8941, `CostService` would only look a price up for a provider
 * present in a hard-coded map of about a dozen names; 91 `litellm_provider`
 * values in the shipped price table were not in it, so every span logged
 * against one of them resolved no cost at all — and rendered beside identical
 * siblings that did. The existing cost specs seed only `openai`, `anthropic`,
 * `perplexity`, `mistral` and `google_ai`, every one of which was on the old
 * whitelist, so a regression that reinstated any provider filter would pass the
 * whole estate today.
 *
 * Each vector is the simplest thing that can be priced under its provider — a
 * row whose only cost fields are the input and output token rates, so the
 * expected amount is two rates times two counts with no cache, audio, tier or
 * character term in it:
 *
 *   gemini-3-pro-preview                       vertex_ai   $2/M in     + $12/M out
 *   together_ai/moonshotai/Kimi-K2-Instruct    together_ai $1/M in     + $3/M out
 *   together-ai-21.1b-41b                      together_ai $0.8/M in   + $0.8/M out
 *   databricks-claude-haiku-4-5                databricks  $1.00002/M  + $5.00003/M
 *   fal_ai/fal-ai/moondream3-preview/query     fal_ai      $0.4/M in   + $3.5/M out
 *   voyage-3-large                             voyage      $0.18/M in  + $0/M out
 *
 * Two of them are not interchangeable with a simpler name. `CostService`
 * strips everything up to the FIRST `/` from a model id before looking it up,
 * and builds its price keys the same way — so a price-file key with two
 * segments after the provider prefix (`together_ai/moonshotai/Kimi-K2-Instruct`,
 * `fal_ai/fal-ai/moondream3-preview/query`) is only reachable when the client
 * logs the model under its full table name. Those two therefore exercise the
 * price-KEY parse rather than just the provider map, and a resolver that
 * stripped one segment too many or too few would fail exactly there.
 *
 * `voyage-3-large` is an `embedding`-mode row rather than a `chat` one, and its
 * output rate is zero: it pins both that mode is not itself a filter, and that
 * 50k completion tokens on a row publishing `output_cost_per_token: 0` bill
 * nothing.
 *
 * The control is what makes the six a finding rather than a tautology. It names
 * a provider and a model that appear in no price row at all, so it must stay
 * unpriced — without it, "these providers resolve a price" could not be told
 * apart from a lookup that had started inventing rows for anything it was
 * handed.
 */
const SPAN_SEEDS: Array<Omit<NewProviderCostSpanSeed, 'name'>> = [
  {
    key: 'vertex-ai',
    model: 'gemini-3-pro-preview',
    provider: 'vertex_ai',
    expectedCost: 0.9,
    workings: '150k x $2/M in + 50k x $12/M out',
  },
  {
    key: 'together-ai-nested-slash',
    // The full price-table key. See the note on the price-key parse above.
    model: 'together_ai/moonshotai/Kimi-K2-Instruct',
    provider: 'together_ai',
    expectedCost: 0.3,
    workings: '150k x $1/M in + 50k x $3/M out',
  },
  {
    key: 'together-ai-flat',
    model: 'together-ai-21.1b-41b',
    provider: 'together_ai',
    expectedCost: 0.16,
    workings: '150k x $0.8/M in + 50k x $0.8/M out',
  },
  {
    key: 'databricks',
    model: 'databricks-claude-haiku-4-5',
    provider: 'databricks',
    expectedCost: 0.4000045,
    workings: '150k x $1.00002/M in + 50k x $5.00003/M out',
  },
  {
    key: 'fal-ai',
    // The full price-table key, for the same reason as the together_ai row.
    model: 'fal_ai/fal-ai/moondream3-preview/query',
    provider: 'fal_ai',
    expectedCost: 0.235,
    workings: '150k x $0.4/M in + 50k x $3.5/M out',
  },
  {
    key: 'voyage-embedding',
    model: 'voyage-3-large',
    provider: 'voyage',
    expectedCost: 0.027,
    workings:
      '150k x $0.18/M in, and nothing for the 50k completion tokens — this embedding row publishes output_cost_per_token: 0',
  },
  {
    key: 'unpriced-control',
    // Deliberately nonsense, and deliberately prefixed: a name a future price
    // file could plausibly acquire would turn this control into a flake.
    model: 'qa-no-such-model-in-any-price-row',
    provider: 'qa-no-such-provider',
    expectedCost: 0,
    workings:
      'neither this provider nor this model appears in any price row, so a cost here means the lookup is inventing rows for whatever it is handed',
  },
];

/**
 * One trace carrying seven LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans`. The subject is the `provider`
 * and `model` pair as a client logs it, and the bridge's own usage builders
 * would be an extra translation between the seed and the lookup under test —
 * `modelCostSpans` seeds through the bridge where that does not matter, but
 * here two of the vectors depend on the model id arriving character for
 * character, since the price-key parse is part of what they exercise.
 *
 * The trace is a REST write for the usual reason: the bridge's `/traces` route
 * emits through `@opik.track`, which adds a root span carrying no model and no
 * usage. It would price at nothing and change no cost, but it would break the
 * "the trace carries exactly the seeded spans" read the spec opens with — which
 * is the check that stops a cost from being attributed to the wrong span, and
 * the check that tells the control apart from a span that never landed.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind.
 */
export const test = baseTest.extend<NewProviderCostSpansFixtures>({
  newProviderCostSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: NewProviderCostSpanSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-new-provider-cost-trace`,
        source: 'sdk',
        input: { question: 'seeded newly-priced provider resolution' },
        output: { answer: 'seeded newly-priced provider resolution' },
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

      const ref: NewProviderCostSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.newProviderCostSpans', {
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
          console.warn(`[newProviderCostSpans fixture] delete warning for trace ${traceId}:`, err);
        }
      }
    }
  },
});

export { expect } from './cache-dialect-spans.fixture';
