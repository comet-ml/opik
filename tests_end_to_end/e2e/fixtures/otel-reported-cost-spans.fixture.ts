import { test as baseTest } from './comparison-stats-filters.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { otelDouble, uuid7 } from '../core/backend';
import type { OtelSpanSeed } from '../core/backend';

/**
 * One OTel span of the reported-cost matrix, and what the backend must make of
 * it.
 *
 * `reportedCost` being `null` is what makes a vector the CONTROL: that span
 * carries no cost attribute at all, so its price is the server's own lookup and
 * its `total_estimated_cost_version` is stamped. Every other vector reports an
 * amount, which must survive verbatim with no version beside it.
 */
export interface OtelReportedCostSeed {
  /** Stable, namespace-free handle a spec looks a vector up by. */
  key: string;
  name: string;
  /**
   * The attribute spelling this vector reports its cost under, or `null` for
   * the control that reports none.
   *
   * Both spellings are real and both are mapped by `GenAIMappingRules`:
   * `gen_ai.usage.cost` is the single-key form older instrumentations emit, and
   * `gen_ai.usage.cost.amount` is the structured form that carries a currency
   * and a source beside it. They are separate rules in the backend, so they can
   * regress apart.
   */
  costAttribute: 'gen_ai.usage.cost' | 'gen_ai.usage.cost.amount' | null;
  /** The amount reported, or `null` for the control. */
  reportedCost: number | null;
  /**
   * The amount the span must END UP with — the reported one where there is one,
   * and the price table's own answer for the control.
   */
  expectedCost: number;
  /**
   * Whether `total_estimated_cost_version` must be set on the stored span.
   *
   * The whole point of the pair. A reported cost bypasses `CostService`
   * entirely, so the version column stays NULL; the control is priced from the
   * table, so it is stamped. Identical amounts in the same column with
   * different origins are otherwise indistinguishable, which is how a
   * regression that silently re-priced a provider's own billed amount would go
   * unseen.
   */
  expectsCostVersion: boolean;
}

export interface OtelReportedCostSpansRef {
  /** The Opik trace the exported spans were attached to via `opik.trace_id`. */
  traceId: string;
  /**
   * The ingestion endpoint's own answer to the export.
   *
   * Returned rather than asserted inside the fixture because the ACCEPTANCE is
   * one of the three claims opik#9499 is about: before that fix the
   * string-valued `gen_ai.usage.cost.currency`/`.source` attributes fell
   * through to the `gen_ai.usage.` prefix rule, which parses its value as a
   * number and rejected the WHOLE export. A seed that threw would report that
   * as broken test plumbing instead of as the product refusing a valid export.
   */
  exportStatus: number;
  /** The endpoint's response body, so a refusal names itself in the failure. */
  exportMessage: string;
  spans: OtelReportedCostSeed[];
  inputTokens: number;
  outputTokens: number;
  /** The currency the structured vector reports, which must reach metadata. */
  currency: string;
  /** The `cost.source` the structured vector reports, likewise. */
  costSource: string;
}

export interface OtelReportedCostSpansFixtures {
  otelReportedCostSpans: OtelReportedCostSpansRef;
}

/**
 * A million of each, for the same reason as `otelProviderAliasSpans`: the
 * control's price has to clear `formatCost`'s `<$0.01` floor so the panel
 * renders an exact dollar amount an assertion can read. At a realistic token
 * count the control would render as `<$0.01` and the two reported spans as
 * their own amounts, which would make the comparison pass for the wrong reason.
 */
const INPUT_TOKENS = 1_000_000;
const OUTPUT_TOKENS = 1_000_000;

/**
 * `gpt-4o-mini` at 1M + 1M tokens, from the shipped price table:
 * $0.15/M input + $0.60/M output -> $0.75.
 *
 * Pinned rather than derived from the price file, for the reason
 * `otel-provider-alias-spans` gives: pinning is what makes a reprice a thing a
 * human looks at. It is also load-bearing TWICE over here — it is the control's
 * expected amount, and it is what makes the reported amount below
 * unmistakably not a recomputed one.
 */
const COMPUTED_COST = 0.75;

/**
 * The amount the two reporting vectors claim.
 *
 * Deliberately nothing like {@link COMPUTED_COST}, and deliberately not a whole
 * number. A reported cost that happened to equal what the table computes would
 * make the whole spec vacuous — "the reported amount survived" and "the server
 * re-priced it" would be the same observation. And the cents matter: the
 * attribute goes on the wire as a proto3 `doubleValue`, so a regression that
 * read it as an int64 would store `1`, which a `toBeCloseTo` against a whole
 * number would have accepted.
 */
const REPORTED_COST = 1.23;

/**
 * A non-USD currency, because that is the half that was REJECTED.
 *
 * `gen_ai.usage.cost.currency` is a string, and before opik#9499 ordered the
 * `gen_ai.usage.cost.*` rules ahead of the `gen_ai.usage.` prefix the string
 * reached a numeric parse and took the whole export down with it. A vector
 * reporting `USD` would exercise the same path, but `EUR` beside an amount
 * rendered as `$1.23` is also the user-visible consequence of the currency
 * being dropped — a figure with the wrong unit on it.
 */
const CURRENCY = 'EUR';

/** `gen_ai.usage.cost.source` — who says so. Mapped to metadata, like the currency. */
const COST_SOURCE = 'provider';

/**
 * Three spans on ONE model with identical token counts, differing only in how
 * they report a cost.
 *
 * Identical but for the cost attribute is what makes the comparison
 * discriminating in both directions. An absolute amount alone could not tell
 * "the reported cost survived" apart from "the price table moved onto 1.23",
 * and the control alone could not tell "reported costs are kept" apart from
 * "nothing is priced at all". Together: two spans must read 1.23 with no
 * version, one must read 0.75 with a version, and all three carry the same
 * model and the same 1M + 1M counts — so neither half can pass for the other.
 */
const REPORTED_COST_SEEDS: Array<Omit<OtelReportedCostSeed, 'name'>> = [
  {
    key: 'reported-cost',
    costAttribute: 'gen_ai.usage.cost.amount',
    reportedCost: REPORTED_COST,
    expectedCost: REPORTED_COST,
    expectsCostVersion: false,
  },
  {
    key: 'legacy-cost',
    costAttribute: 'gen_ai.usage.cost',
    reportedCost: REPORTED_COST,
    expectedCost: REPORTED_COST,
    expectsCostVersion: false,
  },
  {
    key: 'no-reported',
    costAttribute: null,
    reportedCost: null,
    expectedCost: COMPUTED_COST,
    expectsCostVersion: true,
  },
];

/** The model every vector reports, so the control's price is the one COMPUTED_COST pins. */
const MODEL = 'gpt-4o-mini';

/** The provider every vector reports, so the control resolves a price at all. */
const PROVIDER = 'openai';

/**
 * One Opik trace carrying three spans written through the OTLP ingestion
 * endpoint, two of them reporting their own cost.
 *
 * The gap this closes is named in the release report for opik#9499: three e2e
 * specs drive `POST /v1/private/otel/v1/traces` and every one of them
 * deliberately sends NO `gen_ai.usage.cost`, so the reported-cost mapping had
 * no e2e caller at all. It cannot be reached from any other seed either —
 * `POST /v1/private/spans` takes `total_estimated_cost` as a literal field and
 * never runs the attribute rules whose ORDER is the thing opik#9499 fixed.
 *
 * The trace is created first and handed to the export as `opik.trace_id`,
 * rather than letting the service mint one, for the reasons
 * `otelProviderAliasSpans` gives: the spans are then readable with
 * `listSpanCosts({ traceId })` and openable in the panel by id, and a seed that
 * failed to land shows up as an empty read against a trace that provably
 * exists.
 *
 * The export's status is carried out on the ref instead of being thrown on. See
 * `exportStatus`.
 *
 * Teardown deletes the trace, and with it its spans, from a `finally` that
 * opens the moment the trace exists: the export runs BEFORE `use()`, so a
 * failure in it would otherwise skip the only cleanup.
 */
export const test = baseTest.extend<OtelReportedCostSpansFixtures>({
  otelReportedCostSpans: async (
    { backendClient, project, testNamespace },
    use,
    testInfo,
  ) => {
    const spans: OtelReportedCostSeed[] = REPORTED_COST_SEEDS.map((seed) => ({
      ...seed,
      name: `${testNamespace}-${seed.key}`,
    }));

    const traceId = uuid7();
    await backendClient.createTraceWithSource({
      id: traceId,
      projectName: project.name,
      name: `${testNamespace}-otel-reported-cost-trace`,
      source: 'sdk',
      input: { question: 'seeded otel reported cost' },
      output: { answer: 'seeded otel reported cost' },
    });

    try {
      const otelSpans: OtelSpanSeed[] = spans.map((span) => ({
        name: span.name,
        attributes: {
          // `gen_ai.system` rather than `gen_ai.provider.name`: the deprecated
          // attribute is the authoritative one, and the control's price depends
          // on the provider resolving — which is not what this spec is about,
          // so it takes the path with no alias map in it.
          'gen_ai.system': PROVIDER,
          'gen_ai.request.model': MODEL,
          'gen_ai.response.model': MODEL,
          // Numbers, so they arrive as `intValue` — `extractUsageField` reads
          // `hasIntValue()` and skips a count that arrived as anything else,
          // which would leave the CONTROL unpriced and its missing version
          // indistinguishable from a reported cost's absent one.
          'gen_ai.usage.input_tokens': INPUT_TOKENS,
          'gen_ai.usage.output_tokens': OUTPUT_TOKENS,
          ...(span.costAttribute === null
            ? {}
            : {
                // `otelDouble`, not a bare number: a bare one is encoded as
                // `intValue` and `extractCost` would store a truncated $1.
                [span.costAttribute]: otelDouble(span.reportedCost!),
              }),
          // The currency and source ride only on the STRUCTURED vector, which
          // is the spelling that carries them. Putting them on the legacy
          // vector too would stop the two spellings being told apart, and would
          // hide which rule's ordering the export's acceptance depends on.
          ...(span.costAttribute === 'gen_ai.usage.cost.amount'
            ? {
                'gen_ai.usage.cost.currency': CURRENCY,
                'gen_ai.usage.cost.source': COST_SOURCE,
              }
            : {}),
        },
      }));

      const { status, message } = await backendClient.postOtelSpans({
        projectName: project.name,
        opikTraceId: traceId,
        spans: otelSpans,
        tolerateRefusal: true,
      });

      const ref: OtelReportedCostSpansRef = {
        traceId,
        exportStatus: status,
        exportMessage: message,
        spans,
        inputTokens: INPUT_TOKENS,
        outputTokens: OUTPUT_TOKENS,
        currency: CURRENCY,
        costSource: COST_SOURCE,
      };

      await testInfo.attach('opik.otelReportedCostSpans', {
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
            `[otelReportedCostSpans fixture] delete warning for trace ${traceId}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './comparison-stats-filters.fixture';
