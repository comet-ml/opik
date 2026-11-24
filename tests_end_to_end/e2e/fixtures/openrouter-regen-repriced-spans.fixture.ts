import { expect } from '@playwright/test';
import { test as baseTest } from './version-scoped-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import { displayedCost } from '../core/money';

/**
 * One OpenRouter model the 2.3.1 price regen (opik#9733) moved, seeded as an LLM
 * span that reports `usage` and no `total_cost`.
 *
 * The three vectors are the three things one regen can do to a row: re-price it
 * in two directions at once, re-price it upward, and remove it entirely.
 */
export interface OpenRouterRegenRepricedSeed {
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
   * prefix, because `llm-models-default.yaml` nests rows under their provider.
   * Carried rather than derived by stripping the prefix, for the reason
   * `openRouterRemovedRowSpans` carries both: the two spellings being different
   * is a real property of this surface.
   */
  catalogId: string;
  /**
   * The amount the SHIPPED price file's rates give at the seeded token counts,
   * or `null` for a model no row prices any more.
   *
   * `null` rather than `0`: the two are different claims about the product. A
   * row whose rates are zero is a FREE model and resolves 0; a row that does
   * not exist resolves no figure at all, and a reader cannot tell the two apart
   * on screen — which is what makes a silent de-listing a finding.
   *
   * Never sent to the API. Every span is logged with `usage` and no
   * `total_cost`, so this is only what the server's own lookup is compared to.
   */
  expectedCost: number | null;
  /**
   * Whether the curated catalog still gives this id a `label`.
   *
   * `label` is what the model pickers list, so an unlabelled id is one a user
   * can no longer SELECT while a saved playground prompt or online-evaluation
   * rule pinned to it keeps running. That combination — still runnable, no
   * longer priced — is the shape of the money-to-zero finding, and this release
   * produces it on one id.
   */
  catalogLabelled: boolean;
  /** The arithmetic behind `expectedCost`, for the assertion message. */
  workings: string;
}

export interface OpenRouterRegenRepricedSpansRef {
  traceId: string;
  spans: OpenRouterRegenRepricedSeed[];
  promptTokens: number;
  completionTokens: number;
  /**
   * Sum of the PRICED vectors' `expectedCost` — what the trace must roll up to,
   * the de-listed row adding nothing.
   */
  expectedTraceCost: number;
}

export interface OpenRouterRegenRepricedSpansFixtures {
  openRouterRegenRepricedSpans: OpenRouterRegenRepricedSpansRef;
}

/**
 * A million of each, for the reason every OpenRouter cost fixture here uses the
 * same counts: these rows are priced per million tokens at fractions of a cent,
 * so at realistic token counts every amount would render as `<$0.01` and the
 * panel half could not tell one span's cost from another's.
 */
const PROMPT_TOKENS = 1_000_000;
const COMPLETION_TOKENS = 1_000_000;

/**
 * The three rows opik#9733's regen moved for 2.3.1.
 *
 *   openrouter/deepseek/deepseek-v4-pro-0813   $1.32/M -> $0.19/M in,
 *                                              $3.96/M -> $8.00/M out  -> $8.19
 *   openrouter/z-ai/glm-5.1                    $1.19/M -> $1.40/M in,
 *                                              $3.74/M -> $4.40/M out  -> $5.80
 *   openrouter/xiaomi/mimo-v2.5-pro            row REMOVED              -> nothing
 *
 * ## Why these three, and why they are not vectors on an existing fixture
 *
 * `openrouter-repriced-spans.fixture.ts` (opik#9180) and
 * `openrouter-removed-row-spans.fixture.ts` (opik#9364/9365) both pin rows this
 * regen did NOT touch, and both assert their trace carries exactly their own
 * spans and that their priced rows resolve distinct amounts — so adding vectors
 * to either trace would make its own assertions wrong. Neither names any id
 * here, so neither can fail when one of these rows moves.
 *
 * The deepseek row is the discriminating one, and the reason this is a finding
 * rather than a re-statement: its input rate fell about 7x while its output
 * rate DOUBLED. No single multiplier, no stale copy of the previous file and no
 * fallback to a neighbouring `deepseek/deepseek-v4-pro` row satisfies the pair
 * — a wrong lookup lands on a different number in one direction or the other.
 * A row re-priced in one direction (glm-5.1, both rates up) cannot say that on
 * its own.
 *
 * The mimo row is the money-to-zero case, and it is the stronger version of it:
 * the same regen that dropped its price row also dropped its catalog `label`
 * (opik#9734), while leaving the id SERVED. So a user who saved a config on it
 * can still run it, can no longer pick it, and from this release pays an
 * invisible nothing for it. Its own `mimo-v2.5` and `mimo-v2.6-*` siblings keep
 * their rows, which is what rules out "this vendor stopped resolving".
 *
 * ## These amounts track a GENERATED artifact and will need updating
 *
 * `model_prices_openrouter.json` is regenerated from OpenRouter's own model list
 * by `scripts/update_openrouter_model_prices.py`, so any of these rates can move
 * in any release — that is the cost of pinning absolute amounts, and it is also
 * the only thing that pins the lookup to the SHIPPED table rather than to
 * itself. A read of the file at run time would make the spec agree with
 * whatever the file said, including a file that shipped empty.
 *
 * When this fails, read the rows out of
 * `apps/opik-backend/src/main/resources/model_prices_openrouter.json` and check
 * the published rates against them BEFORE treating a changed amount as a defect.
 * Every amount lives HERE and nowhere else, so a regen is one edit: the spec
 * reads each vector's `expectedCost` rather than repeating it.
 */
const SPAN_SEEDS: Array<Omit<OpenRouterRegenRepricedSeed, 'name'>> = [
  {
    key: 'deepseek-repriced-both-ways',
    model: 'openrouter/deepseek/deepseek-v4-pro-0813',
    catalogId: 'deepseek/deepseek-v4-pro-0813',
    expectedCost: 8.19,
    catalogLabelled: true,
    workings:
      '1.0M x $0.19/M in + 1.0M x $8.00/M out. 9733 moved the two rates in OPPOSITE directions ' +
      '(input $1.32/M -> $0.19/M, output $3.96/M -> $8.00/M), so no single multiplier and no ' +
      'stale copy of the previous file reproduces this amount, and a fall-through to the ' +
      'undated `deepseek/deepseek-v4-pro` row does not either',
  },
  {
    key: 'glm-repriced-upward',
    model: 'openrouter/z-ai/glm-5.1',
    catalogId: 'z-ai/glm-5.1',
    expectedCost: 5.8,
    catalogLabelled: true,
    workings:
      '1.0M x $1.40/M in + 1.0M x $4.40/M out (was $1.19/M + $3.74/M). A second priced row under ' +
      'a different vendor prefix, so a lookup that resolved only one vendor cannot satisfy both — ' +
      'and its amount is well clear of deepseek\'s, so the two render as two distinct strings',
  },
  {
    key: 'mimo-pro-delisted',
    model: 'openrouter/xiaomi/mimo-v2.5-pro',
    catalogId: 'xiaomi/mimo-v2.5-pro',
    expectedCost: null,
    catalogLabelled: false,
    workings:
      '9733 removed this row entirely (it was $0.48/M in + $1.80/M out), so nothing prices it. ' +
      'Its `mimo-v2.5` and `mimo-v2.6-*` siblings KEPT their rows, which is what rules out ' +
      '"this vendor prefix stopped resolving" as an explanation',
  },
];

/** Every seeded model id carries the `provider` a real OpenRouter call would. */
const PROVIDER = 'openrouter';

/** The catalog provider the three ids are nested under. */
export const OPENROUTER_REGEN_CATALOG_PROVIDER = 'openrouter';

/**
 * One trace carrying three LLM spans that report `usage` and **no**
 * `total_cost`, so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason the sibling OpenRouter fixtures do it: the subject is the model
 * id as a client spells it and the price-key parse splits on its first slash, so
 * every character has to arrive exactly as written with no SDK normalisation in
 * between. The trace is a REST write for the same reason — the bridge's
 * `/traces` route emits through `@opik.track`, which would add a root span
 * carrying no model and no usage and break the "the trace carries exactly the
 * seeded spans" read.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that opens
 * the moment the id exists: every write runs BEFORE `use()`, and the trace's own
 * rolled-up cost is one of the things asserted, so a failure must not leave
 * priced spans behind.
 */
export const test = baseTest.extend<OpenRouterRegenRepricedSpansFixtures>({
  openRouterRegenRepricedSpans: async (
    { backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const spans: OpenRouterRegenRepricedSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // The premises every assertion downstream rests on, asserted before
    // anything is written. A seed that drifted into (say) three priced rows
    // would still let the panel assertions pass while no longer posing the
    // question this fixture exists for.
    const priced = spans.filter((s) => s.expectedCost !== null);
    const delisted = spans.filter((s) => s.expectedCost === null);
    expect(priced.map((s) => s.key), 'two re-priced rows, from two vendors').toEqual([
      'deepseek-repriced-both-ways',
      'glm-repriced-upward',
    ]);
    expect(delisted.map((s) => s.key), 'one de-listed row').toEqual(['mimo-pro-delisted']);
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
    // The axis that makes the de-listed row a user-facing finding rather than a
    // dead id: the price row and the catalog LABEL went together for mimo, while
    // both priced rows kept their labels. A build on which the de-listed id was
    // still labelled, or the priced ones were not, is a different scenario from
    // the one the assertions describe.
    expect(
      priced.every((s) => s.catalogLabelled),
      'both re-priced rows are still labelled, so they stay selectable',
    ).toBe(true);
    expect(
      delisted.every((s) => !s.catalogLabelled),
      'and the de-listed row has lost its label, which is what makes it unselectable but ' +
        'still runnable from a saved config',
    ).toBe(true);

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-openrouter-regen-repriced-trace`,
        source: 'sdk',
        input: { question: 'seeded OpenRouter rows the 2.3.1 price regen moved' },
        output: { answer: 'seeded OpenRouter rows the 2.3.1 price regen moved' },
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

      const ref: OpenRouterRegenRepricedSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: priced.reduce((acc, s) => acc + s.expectedCost!, 0),
      };

      await testInfo.attach('opik.openRouterRegenRepricedSpans', {
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
            `[openRouterRegenRepricedSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './version-scoped-experiments.fixture';
