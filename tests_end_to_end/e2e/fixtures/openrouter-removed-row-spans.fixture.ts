import { expect } from '@playwright/test';
import { test as baseTest } from './cost-breakdown-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import { displayedCost } from '../core/money';

/**
 * One OpenRouter model as 2.2.139 ships it, seeded as an LLM span.
 *
 * The four vectors form a 2x2 over two INDEPENDENT facts — is the row still in
 * the price table, and does the catalog still give the id a display label —
 * because opik#9364 and opik#9365 moved those two separately and the release
 * report's open question was whether a user can tell. Nothing else in the
 * estate seeds the "de-labelled but still priced" cell, which is the one that
 * stops this spec passing for the wrong reason (see the fixture doc below).
 */
export interface OpenRouterRemovedRowSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /**
   * The model id as a client logs it — the three-segment spelling
   * (`openrouter/<vendor>/<model>`) that `model_prices_openrouter.json` keys,
   * and the only one `CostService.parseModelName` resolves against it.
   */
  model: string;
  /**
   * The same model as the CATALOG spells it: two-segment, no `openrouter/`
   * prefix, because the catalog nests rows under their provider
   * (`llm-models-default.yaml`). Deliberately carried as its own field rather
   * than derived by stripping a prefix: the two spellings being different is a
   * real property of this surface, and a helper that converted one into the
   * other would hide a day when they stop corresponding.
   */
  catalogId: string;
  /**
   * The amount the shipped price file's rates give at the seeded token counts,
   * or `null` for a model NO row prices any more.
   *
   * `null` rather than `0`: the two are different claims about the product.
   * "Priced at nothing" would be a row whose rates are zero — a free model —
   * and the whole finding here is that a de-listed model renders no figure at
   * all, which is not the same thing a reader sees.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * amount is the server's own lookup and this is only what it is compared to.
   */
  expectedCost: number | null;
  /**
   * Whether the curated catalog still gives this id a `label`.
   *
   * Asserted, not incidental. `label` is what the model picker lists, so a
   * de-labelled id is one a user can no longer SELECT but a saved playground
   * prompt or online-evaluation rule keeps running. Pairing it with
   * `expectedCost` is what makes the spec's point: of the three unlabelled
   * rows here, one is still billed and two are not.
   */
  catalogLabelled: boolean;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface OpenRouterRemovedRowSpansRef {
  traceId: string;
  spans: OpenRouterRemovedRowSeed[];
  promptTokens: number;
  completionTokens: number;
  /**
   * Sum of the PRICED vectors' `expectedCost` — what the trace must roll up to,
   * the two de-listed rows adding nothing.
   */
  expectedTraceCost: number;
}

export interface OpenRouterRemovedRowSpansFixtures {
  openRouterRemovedRowSpans: OpenRouterRemovedRowSpansRef;
}

/**
 * A million of each, for the reason `openRouterRepricedSpans` uses the same
 * counts: OpenRouter's rows are cheap, and at realistic token counts every one
 * of these amounts would render as `<$0.01` and the panel half could not tell
 * them apart.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/**
 * The two ids opik#9364 dropped from the price table, the id opik#9365
 * de-labelled but left priced, and a row that kept both.
 *
 *   openrouter/poolside/laguna-s-2.1                     no row            -> nothing
 *   openrouter/nousresearch/hermes-3-llama-3.1-405b      no row            -> nothing
 *   openrouter/meta-llama/llama-3.1-70b-instruct         $0.40/M in + out  -> $0.80
 *   openrouter/qwen/qwen3.8-27b                          $0.15/M + $1.875/M -> $2.025
 *
 * **These amounts track a GENERATED artifact and are expected to need
 * updating.** `model_prices_openrouter.json` is regenerated from OpenRouter's
 * own model list by `scripts/update_openrouter_model_prices.py`, so a row's
 * rates can move in any release — `qwen3.8-27b` has now moved three times
 * ($0.20/M + $2.50/M for 2.2.120, $0.039/M + $1.25/M for 2.2.130, these rates
 * for 2.2.139). That is neither a defect nor flake; it is the cost of pinning
 * absolute amounts, which is also the only thing that pins the lookup to the
 * SHIPPED table rather than to itself. When this fails, read the row out of
 * `apps/opik-backend/src/main/resources/model_prices_openrouter.json` and check
 * the published rates against it BEFORE treating a changed amount as a finding.
 *
 * ## Why these four
 *
 * `openrouter-repriced-spans.fixture.ts` already seeds a removed row —
 * `minimax-m3.1-flash:free`, dropped by opik#9180 — so "a de-listed id is
 * unpriced" is not itself new. Two things here are:
 *
 *  - **The de-labelled control.** `meta-llama/llama-3.1-70b-instruct` lost its
 *    catalog `label` in the same release that removed the two rows above it, so
 *    it is served, unselectable in the picker, and STILL billed. A build that
 *    had lost the price file wholesale, or that read a stale copy, fails on it
 *    rather than passing because everything happened to be unpriced. It is the
 *    discriminator the `:free` control cannot be: that one differs from its
 *    priced sibling by a suffix, which tests the key parse, not the table.
 *  - **Two removed rows, not one.** They are different vendors with different
 *    id shapes, so a lookup that fell through for one vendor prefix and not
 *    another cannot satisfy both.
 *
 * `qwen3.8-27b` is the fourth cell: priced AND labelled, so the 2x2 is complete
 * and `catalogLabelled` is not a constant. Its amount is also an order of
 * magnitude off llama's, so the two render as two distinct strings and the panel
 * half is discriminating per span.
 */
const SPAN_SEEDS: Array<Omit<OpenRouterRemovedRowSeed, 'name'>> = [
  {
    key: 'poolside-removed',
    model: 'openrouter/poolside/laguna-s-2.1',
    catalogId: 'poolside/laguna-s-2.1',
    expectedCost: null,
    catalogLabelled: false,
    workings:
      '9364 removed this row, so nothing prices it — a cost here means a stale price file ' +
      'or a lookup that fell through to another poolside row',
  },
  {
    key: 'hermes-removed',
    model: 'openrouter/nousresearch/hermes-3-llama-3.1-405b',
    catalogId: 'nousresearch/hermes-3-llama-3.1-405b',
    expectedCost: null,
    catalogLabelled: false,
    workings:
      '9364 removed this row too, under a different vendor prefix — so a lookup that stopped ' +
      'resolving one vendor cannot satisfy both removals',
  },
  {
    key: 'llama-delabelled',
    model: 'openrouter/meta-llama/llama-3.1-70b-instruct',
    catalogId: 'meta-llama/llama-3.1-70b-instruct',
    expectedCost: 0.8,
    catalogLabelled: false,
    workings:
      '1.0M x $0.40/M in + 1.0M x $0.40/M out — de-labelled by 9365 and still priced, which is ' +
      'what separates "the picker stopped offering it" from "the table stopped billing it"',
  },
  {
    key: 'qwen-priced',
    model: 'openrouter/qwen/qwen3.8-27b',
    catalogId: 'qwen/qwen3.8-27b',
    expectedCost: 2.025,
    catalogLabelled: true,
    workings:
      '1.0M x $0.15/M in + 1.0M x $1.875/M out (re-priced again for 2.2.139; check ' +
      'model_prices_openrouter.json before calling a changed amount a defect)',
  },
];

/** Every seeded model id carries the `provider` a real OpenRouter call would. */
const PROVIDER = 'openrouter';

/** The catalog provider the four ids are nested under. */
export const OPENROUTER_CATALOG_PROVIDER = 'openrouter';

/**
 * One trace carrying four LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `openRouterRepricedSpans` does it: the subject is the model id
 * as a client spells it and the price-key parse splits on its first slash, so
 * every character has to arrive exactly as written with no SDK normalisation in
 * between. The trace is a REST write for the same reason — the bridge's
 * `/traces` route emits through `@opik.track`, which would add a root span
 * carrying no model and no usage and break the "the trace carries exactly the
 * seeded spans" read.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind.
 */
export const test = baseTest.extend<OpenRouterRemovedRowSpansFixtures>({
  openRouterRemovedRowSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: OpenRouterRemovedRowSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // The premises every assertion downstream rests on, asserted before
    // anything is written. A seed that drifted into (say) three unpriced rows
    // and one priced one would still let a panel assertion pass while no longer
    // posing the question this fixture exists for.
    const priced = spans.filter((s) => s.expectedCost !== null);
    const removed = spans.filter((s) => s.expectedCost === null);
    expect(removed.map((s) => s.key), 'two de-listed rows, from two vendors').toEqual([
      'poolside-removed',
      'hermes-removed',
    ]);
    expect(priced, 'two priced rows').toHaveLength(2);
    expect(
      new Set(priced.map((s) => s.expectedCost)).size,
      'the two priced rows must expect two different amounts, or the panel half cannot tell ' +
        "one span's cost from another's",
    ).toBe(priced.length);
    expect(
      new Set(priced.map((s) => displayedCost(s.expectedCost!))).size,
      'the two priced amounts must also RENDER as two distinct strings (displayedCost floors ' +
        'to cents, so two different numbers can print the same)',
    ).toBe(priced.length);
    // The 2x2 itself: label presence and price presence have to vary
    // independently, or the spec is asserting one fact twice.
    expect(
      priced.filter((s) => !s.catalogLabelled).map((s) => s.key),
      'exactly one priced-but-de-labelled vector — the control for 9365 against 9364',
    ).toEqual(['llama-delabelled']);
    expect(
      priced.filter((s) => s.catalogLabelled).map((s) => s.key),
      'exactly one vector that kept both its row and its label',
    ).toEqual(['qwen-priced']);

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-openrouter-removed-rows-trace`,
        source: 'sdk',
        input: { question: 'seeded OpenRouter rows removed from the price table' },
        output: { answer: 'seeded OpenRouter rows removed from the price table' },
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
          provider: PROVIDER,
          usage: {
            prompt_tokens: PROMPT_TOKENS,
            completion_tokens: COMPLETION_TOKENS,
            total_tokens: PROMPT_TOKENS + COMPLETION_TOKENS,
            // No `total_cost` — deliberately. See the doc comment above.
          },
        });
      }

      const ref: OpenRouterRemovedRowSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: priced.reduce((acc, s) => acc + s.expectedCost!, 0),
      };

      await testInfo.attach('opik.openRouterRemovedRowSpans', {
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
            `[openRouterRemovedRowSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './cost-breakdown-spans.fixture';
