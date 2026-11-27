import { expect } from '@playwright/test';
import { test as baseTest } from './scored-group-experiments.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';
import { displayedCost } from '../core/money';

/**
 * One spelling of AWS Bedrock's Claude Sonnet 4.5, seeded as an LLM span.
 *
 * The vectors answer this release's highest-ranked manual item — opik#9607
 * deleted two `bedrock/us-gov-{east,west}-1/claude-sonnet-4-5-…` rows from
 * `model_prices_and_context_window.json` (the only 2 removals of 6036), and the
 * report asked whether GovCloud spend has therefore gone silently to $0.
 *
 * **It has not, and the deletion is inert.** `ModelPriceTable.buildRuntimeKey`
 * drops any row whose `litellm_provider` is `bedrock` and whose model name
 * still contains a `/`, at load time — so every `bedrock/<region>/…` key is
 * unpriceable whether it is in the file or not. GovCloud Sonnet 4.5 is reachable
 * and correctly priced under the spelling LiteLLM actually emits,
 * `us-gov.anthropic.…` (`litellm_provider: bedrock_converse`, no slash), at
 * exactly the GovCloud premium.
 *
 * That is a conclusion, not an observation, so the seed is built to make it
 * observable: a KEPT region-path row beside a DELETED one, both unpriced, with
 * the two priceable spellings beside them at two different amounts.
 */
export interface GovCloudBedrockCostSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /**
   * The model id as a client logs it — exactly as written, because the price-key
   * parse splits on the first slash and the whole question here is which
   * spellings survive it.
   */
  model: string;
  /**
   * The amount the shipped price file's rates give at the seeded token counts,
   * or `null` for a spelling NO row prices.
   *
   * `null` rather than `0`: the two are different claims about the product.
   * Zero is what a genuinely free model resolves to; what a reader of an
   * unpriceable span actually sees is no figure at all.
   *
   * Never sent: every span is logged with `usage` and no `total_cost`, so the
   * amount is the server's own lookup and this is only what it is compared to.
   */
  expectedCost: number | null;
  /**
   * Whether `model_prices_and_context_window.json` still carries a row under
   * this exact key on the build under test.
   *
   * This is the axis that answers the release item, and it is deliberately
   * independent of `expectedCost`: two vectors have a row and are still
   * unpriced, which is the fact that makes the deletion inert. A spec that only
   * knew "unpriced" could not tell a deleted row from an inert one.
   */
  inPriceTable: boolean;
  /** The arithmetic, or the structural reason there is none, for the assertion message. */
  workings: string;
}

export interface GovCloudBedrockCostSpansRef {
  traceId: string;
  spans: GovCloudBedrockCostSeed[];
  promptTokens: number;
  completionTokens: number;
  /** Sum of the PRICED vectors' `expectedCost` — what the trace must roll up to. */
  expectedTraceCost: number;
}

export interface GovCloudBedrockCostSpansFixtures {
  govCloudBedrockCostSpans: GovCloudBedrockCostSpansRef;
}

/**
 * 150k prompt / 50k completion, the counts the release's exploration used.
 *
 * Large enough that both priced vectors clear `formatCost`'s $0.01 floor by a
 * wide margin AND render as two visibly different strings ($1.44 against $1.2),
 * which is what makes the panel half discriminating per span. Asymmetric
 * deliberately: with equal counts the GovCloud premium on input and the one on
 * output would be indistinguishable from a single blended rate.
 */
const PROMPT_TOKENS = 150_000;
const COMPLETION_TOKENS = 50_000;

/**
 * Every seeded span carries the `provider` a real Bedrock call would.
 *
 * `bedrock` for all six, including the two spellings whose own price rows are
 * `litellm_provider: bedrock_converse`. That is not a mismatch to tidy up: the
 * provider a client logs and the `litellm_provider` the price table keys a row
 * under are different fields, and `CostService` resolving across them is part
 * of what the priced vectors below assert. Changing this to `bedrock_converse`
 * would test a spelling no Bedrock client emits.
 */
const PROVIDER = 'bedrock';

/**
 * The six vectors, and what each one is for.
 *
 *   model as logged                                                row   priced
 *   bedrock/us-gov-east-1/claude-sonnet-4-5-…        DELETED by 9607  no    no
 *   bedrock/us-gov-east-1/anthropic.claude-sonnet-4-5-…      kept     YES   no
 *   bedrock/us-gov-west-1/anthropic.claude-sonnet-4-5-…      kept     YES   no
 *   us-gov.anthropic.claude-sonnet-4-5-…                     kept     YES   $1.44
 *   anthropic.claude-sonnet-4-5-…                            kept     YES   $1.20
 *   <no row anywhere>                                                 no    no
 *
 * ## Why this shape and not fewer vectors
 *
 *  - **A deleted region-path row and a KEPT one, side by side.** This is the
 *    whole answer to the release item: both resolve nothing, so the two
 *    removals changed no behaviour. Either vector alone is uninformative —
 *    without the kept sibling, an unpriced deleted row looks like the deletion's
 *    consequence; without the deleted one, there is nothing the report asked
 *    about.
 *  - **Two regions on the kept side.** `us-gov-east-1` and `us-gov-west-1` are
 *    separate rows, so a lookup that fell through for one region prefix and not
 *    the other cannot satisfy both.
 *  - **Both priceable spellings, at DIFFERENT amounts.** $1.44 against $1.20 is
 *    the GovCloud premium itself ($3.6/$18 per M against $3/$15), so the pair
 *    asserts that the two rows are told apart rather than that some bedrock row
 *    was found. A resolver returning one fixed amount for every bedrock span
 *    satisfies at most one of them.
 *  - **An unpriced control in no row at all.** It separates "this spelling is
 *    dropped at load" from "nothing about bedrock is priced on this build": the
 *    control must be unpriced for a reason nobody disputes, so a build where
 *    everything came back unpriced still fails on the two priced vectors.
 *
 * ## These amounts track a GENERATED artifact
 *
 * `model_prices_and_context_window.json` is regenerated by the repository's own
 * automation, so a row's rates can move in any release — which is also the only
 * thing that pins the lookup to the SHIPPED table rather than to itself. When an
 * amount here fails, read the row out of
 * `apps/opik-backend/src/main/resources/model_prices_and_context_window.json`
 * and check the published rates against it BEFORE treating it as a defect.
 * Every amount lives here and nowhere else, so a regen is one edit.
 *
 * `inPriceTable` tracks that same file and is asserted for the same reason: if
 * a later release re-adds the deleted key, or drops one of the kept ones, this
 * fixture's premise has changed and a human should re-read it rather than have
 * the spec quietly assert something else.
 */
const SPAN_SEEDS: Array<Omit<GovCloudBedrockCostSeed, 'name'>> = [
  {
    key: 'region-path-deleted',
    model: 'bedrock/us-gov-east-1/claude-sonnet-4-5-20250929-v1:0',
    expectedCost: null,
    inPriceTable: false,
    workings:
      'opik#9607 deleted this key for 2.2.148 — but it was unpriceable before the deletion too, ' +
      'because isValidModelProvider drops a bedrock row whose name still holds a slash. ' +
      'A cost here would mean that rule has changed',
  },
  {
    key: 'region-path-kept-east',
    model: 'bedrock/us-gov-east-1/anthropic.claude-sonnet-4-5-20250929-v1:0',
    expectedCost: null,
    inPriceTable: true,
    workings:
      'this key IS in the shipped table, at the GovCloud rate, and still resolves nothing — ' +
      'isValidModelProvider drops it at load time. This is the vector that makes the deletion ' +
      'above inert rather than the cause of a missing amount',
  },
  {
    key: 'region-path-kept-west',
    model: 'bedrock/us-gov-west-1/anthropic.claude-sonnet-4-5-20250929-v1:0',
    expectedCost: null,
    inPriceTable: true,
    workings:
      'the same for the other GovCloud region, so a lookup that fell through for one region ' +
      'prefix and not the other cannot satisfy both',
  },
  {
    key: 'govcloud-alias-priced',
    model: 'us-gov.anthropic.claude-sonnet-4-5-20250929-v1:0',
    expectedCost: 1.44,
    inPriceTable: true,
    workings:
      '150k x $3.6/M in + 50k x $18/M out = $0.54 + $0.90. The spelling LiteLLM actually emits ' +
      'for GovCloud Bedrock (litellm_provider bedrock_converse, no slash), so this is where ' +
      'GovCloud spend is really billed — at the GovCloud premium',
  },
  {
    key: 'standard-alias-priced',
    model: 'anthropic.claude-sonnet-4-5-20250929-v1:0',
    expectedCost: 1.2,
    inPriceTable: true,
    workings:
      '150k x $3.0/M in + 50k x $15/M out = $0.45 + $0.75. The non-GovCloud baseline beside it: ' +
      'the $0.24 difference IS the premium, and asserting both is what proves the two rows are ' +
      'told apart rather than one bedrock row being found',
  },
  {
    key: 'unpriced-control',
    // Namespaced so it cannot collide with a real vendor id a later regen adds.
    model: 'anthropic.claude-qa-not-a-priced-model-v9:0',
    expectedCost: null,
    inPriceTable: false,
    workings:
      'no row under any provider prices this id, so it must be unpriced for a reason nobody ' +
      'disputes — which is what stops "the region-path spans are unpriced" being satisfied by ' +
      'a build that priced nothing at all',
  },
];

/**
 * One trace carrying six LLM spans that report `usage` and **no** `total_cost`,
 * so the backend has to price them itself.
 *
 * Written straight to `POST /v1/private/spans` rather than through the bridge,
 * for the reason `openRouterRemovedRowSpans` does it: the subject is the model
 * id as a client spells it and the price-key parse splits on its first slash,
 * so every character has to arrive exactly as written with no SDK
 * normalisation in between. The trace is a REST write for the same reason — the
 * bridge's `/traces` route emits through `@opik.track`, which would add a root
 * span carrying no model and no usage and break the "the trace carries exactly
 * the seeded spans" read.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the id exists: every write runs BEFORE `use()`, and the
 * trace's own rolled-up cost is one of the things asserted, so a failure must
 * not leave priced spans behind.
 */
export const test = baseTest.extend<GovCloudBedrockCostSpansFixtures>({
  govCloudBedrockCostSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const spans: GovCloudBedrockCostSeed[] = SPAN_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    // The premises every assertion downstream rests on, asserted before
    // anything is written. A seed that drifted — into one region-path vector,
    // say, or two priced rows that expect the same amount — would still let the
    // panel assertions pass while no longer posing the question this fixture
    // exists for.
    const priced = spans.filter((s) => s.expectedCost !== null);
    const unpriced = spans.filter((s) => s.expectedCost === null);
    expect(priced.map((s) => s.key), 'the two priceable spellings').toEqual([
      'govcloud-alias-priced',
      'standard-alias-priced',
    ]);
    expect(
      new Set(priced.map((s) => s.expectedCost)).size,
      'the two priced rows must expect two different amounts, or the premium is not asserted ' +
        "and the panel half cannot tell one span's cost from another's",
    ).toBe(priced.length);
    expect(
      new Set(priced.map((s) => displayedCost(s.expectedCost!))).size,
      'the two priced amounts must also RENDER as two distinct strings (displayedCost floors ' +
        'to cents, so two different numbers can print the same)',
    ).toBe(priced.length);
    expect(
      unpriced.map((s) => s.key),
      'three region-path spellings and the no-row control',
    ).toEqual([
      'region-path-deleted',
      'region-path-kept-east',
      'region-path-kept-west',
      'unpriced-control',
    ]);
    // The axis that answers the release item, asserted as a shape rather than
    // left implicit: among the unpriced vectors there must be at least one the
    // table still carries AND one it does not, or "the deletion is inert"
    // cannot be read off this seed at all.
    expect(
      unpriced.filter((s) => s.inPriceTable).map((s) => s.key),
      'kept region-path rows that are nonetheless unpriced — the inert half',
    ).toEqual(['region-path-kept-east', 'region-path-kept-west']);
    expect(
      unpriced.filter((s) => !s.inPriceTable).map((s) => s.key),
      'and the row 9607 deleted, beside a control that was never in the table',
    ).toEqual(['region-path-deleted', 'unpriced-control']);

    const traceId = uuid7();
    const now = new Date();

    try {
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: `${testNamespace}-govcloud-bedrock-cost-trace`,
        source: 'sdk',
        input: { question: 'seeded GovCloud Bedrock model id spellings' },
        output: { answer: 'seeded GovCloud Bedrock model id spellings' },
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

      const ref: GovCloudBedrockCostSpansRef = {
        traceId,
        spans,
        promptTokens: PROMPT_TOKENS,
        completionTokens: COMPLETION_TOKENS,
        expectedTraceCost: priced.reduce((acc, s) => acc + s.expectedCost!, 0),
      };

      await testInfo.attach('opik.govCloudBedrockCostSpans', {
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
            `[govCloudBedrockCostSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect };
