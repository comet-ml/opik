import { expect } from '@playwright/test';
import { test as baseTest } from './thread-search-turns.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import { displayedCost } from '../core/money';

/**
 * One LLM span per model-id SPELLING, paired so that each upper-case or
 * dotted-upper-case id has a lower-case control of the same model beside it.
 *
 * The pairing is the whole design. `CostService.findModelPriceUnderProvider`
 * resolves a price by trying the id verbatim and then a ladder of normalised
 * variants, each of which is lower-cased; before opik#9309 every rung's
 * "did normalising change anything?" guard compared case-INSENSITIVELY, so an
 * id that differed from its variant only in letter case was judged unchanged,
 * the retry was skipped, and the span resolved no price at all. The failure is
 * money silently reading as nothing on a page people open to decide spend.
 *
 * An absolute amount alone cannot catch the regression's inverse — a table that
 * moved — and a same-as-its-control comparison alone cannot catch a resolver
 * that stopped pricing BOTH spellings. Each vector therefore carries both: the
 * amount the shipped price file implies, and the key of the lower-case vector it
 * must equal.
 */
export interface MixedCaseModelCostSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /**
   * The model id as a client logs it, character for character — the subject of
   * this fixture, which is why every span is a raw REST write (see the fixture
   * doc below).
   */
  model: string;
  /**
   * The `provider` field logged beside it, as `ProviderRegistry.resolve` reads
   * it.
   *
   * `bedrock` rather than the price row's own `bedrock_converse`, deliberately:
   * that is the name a Bedrock client sends, and reaching the row at all
   * depends on `priceLookupOrder` searching the sibling provider second. A
   * vector that named `bedrock_converse` would skip that hop and pass over a
   * build where it had broken.
   */
  provider: string;
  /**
   * The cost the server must resolve from its own price table at the seeded
   * token counts.
   *
   * Never sent: the span is logged with `usage` and no `total_cost`, so the
   * amount is the server's answer and this is only what it is compared with.
   */
  expectedCost: number;
  /**
   * The lower-case vector this one must be priced identically to, or `null`
   * when this vector IS that control.
   *
   * The comparison a single absolute amount cannot make: it says the two
   * spellings reach the SAME row, so a build that resolved the upper-case id
   * onto some other model's rate fails here even if that rate happened to be
   * plausible.
   */
  equalsKey: string | null;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface MixedCaseModelCostSpansRef {
  traceId: string;
  spans: MixedCaseModelCostSeed[];
  promptTokens: number;
  completionTokens: number;
  /** Sum of every vector's `expectedCost` — the trace's rolled-up total. */
  expectedTraceCost: number;
}

export interface MixedCaseModelCostSpansFixtures {
  mixedCaseModelCostSpans: MixedCaseModelCostSpansRef;
}

/**
 * A million of each, for the reason `modelCostSpans` uses the same counts:
 * `formatCost` renders anything under a cent as the bare `<$0.01` marker, and
 * at realistic token counts all six of these amounts would print that same
 * string — so the UI half could not tell a correctly-priced span from a
 * mis-priced one, nor either from its control.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/**
 * Three model ids in a spelling no existing fixture uses, each with its
 * lower-case twin.
 *
 * Rates from the shipped price table
 * (`apps/opik-backend/src/main/resources/model_prices_and_context_window.json`),
 * at 1M prompt + 1M completion tokens:
 *
 *   gpt-5-mini                    $0.25/M in + $2/M out    -> $2.25
 *   gpt-4o                        $2.5/M in + $10/M out    -> $12.50
 *   anthropic.claude-opus-4-6-v1  $5/M in + $25/M out      -> $30.00
 *
 * None of the three publishes an `above_NNNk` tier, a reasoning rate or an
 * audio rate, so a million tokens bills at the flat rates above and the
 * expected amounts stay arithmetic rather than a transcription of a tier table.
 * Each does publish a cache read rate, which routes the calculator through its
 * cache-aware branch — with no cached tokens in the usage that branch reduces
 * to input + output, so the pair still differs only in letter case.
 *
 * ## Why these three spellings
 *
 * They take three DIFFERENT rungs of the normalisation ladder, which is what
 * makes the set more than one assertion repeated:
 *
 *  - `GPT-5-MINI` reaches its row through `normalizeModelName` alone
 *    (lower-case; there are no dots to replace).
 *  - `Gpt-4o` is the mixed-case rather than all-caps shape, so a fix that had
 *    special-cased a fully upper-case id does not cover it.
 *  - `ANTHROPIC.CLAUDE-OPUS-4-6-V1` cannot use that rung at all: the price key
 *    carries a DOT and `normalizeModelName` replaces dots with hyphens, so the
 *    only variant that matches is the one `stripDateSuffix` produces — which
 *    lower-cases as a side effect while leaving the dot alone. It also crosses
 *    providers (`bedrock` -> `bedrock_converse`) on the way.
 *
 * ## Why the twins are seeded rather than assumed
 *
 * The lower-case ids are each already priced by other specs, so it would be
 * tempting to take their amounts as read. They are seeded here instead because
 * the discriminating claim is about one READ: both spellings resolving the same
 * amount in the same workspace at the same moment. A control priced in another
 * spec, in another run, against another build of the table cannot make it.
 */
const SPAN_SEEDS: Array<Omit<MixedCaseModelCostSeed, 'name'>> = [
  {
    key: 'gpt5mini-upper',
    model: 'GPT-5-MINI',
    provider: 'openai',
    expectedCost: 2.25,
    equalsKey: 'gpt5mini-lower',
    workings:
      '1.0M x $0.25/M in + 1.0M x $2/M out; reached by lower-casing alone, which is the rung ' +
      "9309's case-sensitive guard restored",
  },
  {
    key: 'gpt5mini-lower',
    model: 'gpt-5-mini',
    provider: 'openai',
    expectedCost: 2.25,
    equalsKey: null,
    workings: 'the same row reached verbatim — the control the upper-case spelling must match',
  },
  {
    key: 'gpt4o-mixed',
    model: 'Gpt-4o',
    provider: 'openai',
    expectedCost: 12.5,
    equalsKey: 'gpt4o-lower',
    workings:
      '1.0M x $2.5/M in + 1.0M x $10/M out; mixed case rather than all caps, so a fix narrowed ' +
      'to fully upper-case ids does not cover it',
  },
  {
    key: 'gpt4o-lower',
    model: 'gpt-4o',
    provider: 'openai',
    expectedCost: 12.5,
    equalsKey: null,
    workings: 'the same row reached verbatim — the control for the mixed-case spelling',
  },
  {
    key: 'bedrock-opus-upper',
    model: 'ANTHROPIC.CLAUDE-OPUS-4-6-V1',
    provider: 'bedrock',
    expectedCost: 30,
    equalsKey: 'bedrock-opus-lower',
    workings:
      '1.0M x $5/M in + 1.0M x $25/M out; the dotted key rules out normalizeModelName (it turns ' +
      "the dot into a hyphen), so this one can only resolve through stripDateSuffix's " +
      'lower-casing, and it crosses bedrock -> bedrock_converse to do it',
  },
  {
    key: 'bedrock-opus-lower',
    model: 'anthropic.claude-opus-4-6-v1',
    provider: 'bedrock',
    expectedCost: 30,
    equalsKey: null,
    workings: 'the same row reached verbatim — the control for the dotted upper-case spelling',
  },
];

/**
 * One trace carrying six LLM spans that report `usage` and **no** `total_cost`,
 * so the backend has to price each from the id it was handed.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * and here that is not a preference but the only faithful option: the subject
 * is the model id's LETTER CASE, and the Python SDK is free to normalise what it
 * is handed. A seed that arrived lower-cased would be a duplicate of its own
 * control reading as coverage of the upper-case path. The trace is a REST write
 * for the reason `openRouterRemovedRowSpans` gives — the bridge's `/traces`
 * route emits through `@opik.track`, which would add a root span carrying no
 * model and no usage and break the "the trace carries exactly the seeded spans"
 * read this fixture's spec depends on.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that opens
 * the moment the id exists: every write runs BEFORE `use()`, and the trace's own
 * rolled-up cost is one of the things asserted, so a failure must not leave
 * priced spans behind.
 */
export const test = baseTest.extend<MixedCaseModelCostSpansFixtures>({
  mixedCaseModelCostSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: MixedCaseModelCostSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // The premises every assertion downstream rests on, checked before anything
    // is written. A seed that drifted — a pair whose two members no longer
    // differ only in case, a control that stopped being lower-case — would
    // leave the spec's comparisons trivially true rather than failing.
    const byKey = new Map(spans.map((s) => [s.key, s]));
    const variants = spans.filter((s) => s.equalsKey !== null);
    const controls = spans.filter((s) => s.equalsKey === null);
    expect(variants, 'three mixed-case spellings under test').toHaveLength(3);
    expect(controls, 'and a lower-case control for each').toHaveLength(3);
    for (const variant of variants) {
      const control = byKey.get(variant.equalsKey!);
      expect(control, `'${variant.key}' names an existing control vector`).toBeDefined();
      expect(
        variant.model,
        `'${variant.key}' must not already be lower-case, or it is not testing the case path`,
      ).not.toBe(variant.model.toLowerCase());
      expect(
        control!.model,
        `'${control!.key}' is the lower-case spelling of '${variant.key}'`,
      ).toBe(variant.model.toLowerCase());
      expect(
        control!.provider,
        'a pair must differ in CASE ONLY, so the provider has to match too — otherwise a ' +
          'difference in amount could come from the provider lookup rather than the spelling',
      ).toBe(variant.provider);
      expect(
        control!.expectedCost,
        `'${variant.key}' and its control must expect the same amount`,
      ).toBe(variant.expectedCost);
    }
    expect(
      new Set(controls.map((s) => displayedCost(s.expectedCost))).size,
      'the three pairs must RENDER as three distinct strings (displayedCost floors to cents, so ' +
        'two different numbers can print the same), or the panel half cannot tell one span from ' +
        'another',
    ).toBe(controls.length);

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-mixed-case-model-cost-trace`,
        source: 'sdk',
        input: { question: 'seeded mixed-case model ids' },
        output: { answer: 'seeded mixed-case model ids' },
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

      const ref: MixedCaseModelCostSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: spans.reduce((acc, s) => acc + s.expectedCost, 0),
      };

      await testInfo.attach('opik.mixedCaseModelCostSpans', {
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
            `[mixedCaseModelCostSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './thread-search-turns.fixture';
