import { expect } from '@playwright/test';
import { test as baseTest } from './cost-breakdown-spans.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import { displayedCost } from '../core/money';

/**
 * One OpenRouter model as 2.2.139 ships it, seeded as an LLM span.
 *
 * The four vectors were authored as a 2x2 over two facts opik#9364 and
 * opik#9365 moved separately — is the row still in the price table, and does
 * the catalog still give the id a display label.
 *
 * **On this build the label axis has collapsed.** opik#9608's provider-model
 * sync re-labelled `meta-llama/llama-3.1-70b-instruct`, so both priced rows are
 * now labelled and both de-listed rows are not; there is no longer an
 * "unlabelled but still billed" cell. The PRICING claims — two de-listed ids
 * attributed nothing, two surviving ids billed at their own distinct shipped
 * rates, and a trace roll-up that omits the de-listed pair — are unaffected and
 * are what this fixture is for. See the note beside the 2x2 self-check below.
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
   * prompt or online-evaluation rule keeps running — which is the shape of the
   * money-to-zero finding.
   *
   * It tracks `llm-models-default.yaml`, a GENERATED file, exactly as
   * `expectedCost` tracks the generated price table: opik#9608's sync re-added a
   * `label:` to the llama row this fixture had pinned as de-labelled. Read the
   * row out of that YAML before treating a changed value here as a defect.
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
 * The two ids opik#9364 dropped from the price table, and two that kept their
 * rows — one of which opik#9365 de-labelled and opik#9608 has since re-labelled.
 *
 *   openrouter/poolside/laguna-s-2.1                     no row            -> nothing
 *   openrouter/nousresearch/hermes-3-llama-3.1-405b      no row            -> nothing
 *   openrouter/meta-llama/llama-3.1-70b-instruct         $0.72/M in + out  -> $1.44
 *   openrouter/qwen/qwen3.8-27b                        $0.06/M + $1.96/M  -> $2.02
 *
 * **These amounts track a GENERATED artifact and are expected to need
 * updating.** `model_prices_openrouter.json` is regenerated from OpenRouter's
 * own model list by `scripts/update_openrouter_model_prices.py`, so a row's
 * rates can move in any release — `qwen3.8-27b` has now moved five times
 * ($0.20/M + $2.50/M for 2.2.120, $0.039/M + $1.25/M for 2.2.130, $0.15/M +
 * $1.875/M for 2.2.139, $0.06/M + $2.20/M for 2.2.146, these rates for
 * 2.2.154), and `llama-3.1-70b-instruct` moved for 2.2.146 ($0.40/M both ways
 * through 2.2.145, $0.72/M both ways since). The 2.2.146 regen — opik#9585 —
 * moved BOTH priced rows; the 2.2.154 one — opik#9697 — moved only qwen, which
 * is why each vector names the release its rates came from.
 * That is neither a defect nor flake; it is the cost of pinning
 * absolute amounts, which is also the only thing that pins the lookup to the
 * SHIPPED table rather than to itself. When this fails, read the row out of
 * `apps/opik-backend/src/main/resources/model_prices_openrouter.json` and check
 * the published rates against it BEFORE treating a changed amount as a finding.
 *
 * Every amount lives HERE and nowhere else, so a regen is one edit: the spec
 * reads each vector's `expectedCost` rather than repeating it. The 2.2.146
 * regen is what showed why that matters — the de-labelled row's amount was
 * pinned in both places and only one of them was the obvious one to update.
 *
 * ## Why these four
 *
 * `openrouter-repriced-spans.fixture.ts` already seeds a removed row —
 * `minimax-m3.1-flash:free`, dropped by opik#9180 — so "a de-listed id is
 * unpriced" is not itself new. Two things here are:
 *
 *  - **A still-priced control under a different vendor prefix.**
 *    `meta-llama/llama-3.1-70b-instruct` is served and STILL billed. A build
 *    that had lost the price file wholesale, or that read a stale copy, fails on
 *    it rather than passing because everything happened to be unpriced. It is
 *    the discriminator the `:free` control cannot be: that one differs from its
 *    priced sibling by a suffix, which tests the key parse, not the table.
 *    (Authored as the "de-labelled but still billed" cell; opik#9608 re-labelled
 *    it, so it no longer carries that half — see the self-check below.)
 *  - **Two removed rows, not one.** They are different vendors with different
 *    id shapes, so a lookup that fell through for one vendor prefix and not
 *    another cannot satisfy both.
 *
 * `qwen3.8-27b` is the second priced row. Its amount is well clear of
 * llama's, so the two render as two distinct strings and the panel half is
 * discriminating per span — the assertions at the foot of this fixture hold both
 * the raw amounts and their rendered forms apart, so a regen that collapsed the
 * two onto one figure fails here rather than quietly weakening the panel half.
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
    key: 'llama-priced-control',
    model: 'openrouter/meta-llama/llama-3.1-70b-instruct',
    catalogId: 'meta-llama/llama-3.1-70b-instruct',
    expectedCost: 1.44,
    // QA: opik#9365 DID de-label this row for 2.2.139, but the provider-model
    // sync in opik#9608 re-added it to `llm-models-default.yaml` WITH a
    // `label:`, so it is labelled again on this build. The label tracks a
    // generated file exactly as the rates do — flipped to match what ships
    // rather than asserting a de-labelling the registry has since undone.
    catalogLabelled: true,
    workings:
      '1.0M x $0.72/M in + 1.0M x $0.72/M out — a second priced row, under a different vendor ' +
      'prefix from qwen, so a lookup that resolved only one vendor cannot satisfy both and ' +
      '"the table stopped billing it" is separable from "no row prices it". ' +
      'Re-priced by opik#9585 for 2.2.146 (both rates were $0.40/M through 2.2.145); check ' +
      'model_prices_openrouter.json before calling a changed amount a defect',
  },
  {
    key: 'qwen-priced',
    model: 'openrouter/qwen/qwen3.8-27b',
    catalogId: 'qwen/qwen3.8-27b',
    expectedCost: 2.02,
    catalogLabelled: true,
    workings:
      '1.0M x $0.06/M in + 1.0M x $1.96/M out — re-priced AGAIN by opik#9834 for 2.3.7 ' +
      '($0.035/M + $1.50/M for 2.2.154 onwards, which is what this pin held before). ' +
      'Sixth move; llama above was NOT touched by this regen either, which is why only this ' +
      'one changed. Check model_prices_openrouter.json before calling a changed amount a defect',
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
    // QA: this used to assert a 2x2 — label presence varying independently of
    // price presence, with llama as the priced-but-de-labelled cell. opik#9608's
    // provider-model sync re-labelled llama, so on this build the two axes
    // coincide: both priced rows are labelled and both de-listed rows are not.
    //
    // Asserted in the shape that is actually true, rather than left claiming a
    // de-labelling the registry has undone. The PRICING claims above are
    // untouched and are what this fixture is for; what is gone is the
    // "unlabelled but still billed" cell. Restoring it needs a row that is
    // unlabelled in `llm-models-default.yaml` AND priced in
    // `model_prices_openrouter.json` — 9 such unsuffixed rows exist on this
    // build (e.g. `meta-llama/llama-guard-4-12b`, `amazon/nova-premier-v1`) —
    // but picking one is a coverage decision, not a drift fix, so it is left
    // for a human rather than swapped in here.
    expect(
      priced.filter((s) => s.catalogLabelled).map((s) => s.key),
      'both priced rows are labelled on this build — see the note above',
    ).toEqual(['llama-priced-control', 'qwen-priced']);
    expect(
      removed.filter((s) => !s.catalogLabelled).map((s) => s.key),
      'and both de-listed rows are unlabelled, which is still what separates them',
    ).toEqual(['poolside-removed', 'hermes-removed']);

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
