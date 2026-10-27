import { test, expect } from '@e2e/fixtures';
import type { OuterPromptTokensSpansRef } from '@e2e/fixtures';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * The SDK's outer `prompt_tokens` decides whether the provider's reported input
 * already includes the cache (opik#8987, issue-7773).
 *
 * Clients disagree about this, and the disagreement is not cosmetic: billing
 * the same usage both ways differs by the contested tokens at the full input
 * rate. LiteLLM's `prompt_tokens` includes the cache; Anthropic's
 * `input_tokens` excludes it; and a span logged through the Python SDK carries
 * the provider's own usage under `original_usage.*` with the SDK's converted
 * total beside it. When the numbers can settle which convention applied, they
 * do — and one of the signals is that outer total: if it equals the reported
 * input PLUS the cache, the client plainly treated them as separate buckets.
 *
 * This is the whole of the `VariantInput` change, and it is the one vector in
 * the PR where IDENTICAL provider usage must price two different ways. That is
 * what makes it impossible to pass by accident: a backend that ignored the
 * outer total would price both halves the same, whichever convention it then
 * picked, and no single-span assertion could tell the two apart.
 *
 * API-only, deliberately. Both amounts fall under `formatCost`'s $0.01 floor,
 * so the panel renders "<$0.01" for each — a UI assertion would add a rendering
 * failure mode while asserting nothing about the decision.
 * `span-cross-shape-cache-keys.spec.ts` is where this PR's rendering is
 * asserted, on the one vector a browser can discriminate.
 */

/** `deepinfra/anthropic/claude-3-7-sonnet-latest`'s own published input rate. */
const INPUT_RATE = 3.3e-6;

/**
 * Every seeded span, by name, once both of them are queryable.
 *
 * The count is asserted rather than only the lookup: a read that also returned
 * spans from another trace would still find both seeded ones and pass.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: OuterPromptTokensSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () =>
        (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      { timeout: 60_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(seed.spans.length);

  const spans = await backendClient.listSpanCosts({ projectId, traceId: seed.traceId });
  expect(spans, 'the trace carries exactly the seeded spans').toHaveLength(seed.spans.length);
  return new Map(spans.map((s) => [s.name, s]));
}

/**
 * The span seeded under `key`, asserted to have landed before it is used.
 *
 * A vector the fixture failed to seed would otherwise surface as an `undefined`
 * the arithmetic below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: OuterPromptTokensSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to the vector seeded under `key`.
 *
 * Asserts a cost is present before returning it: both vectors are on a model
 * the price table resolves, so an absent cost is a failure, and a `?? 0` would
 * turn the delta below into a difference between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: OuterPromptTokensSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

test.describe(
  "Span cost — the SDK's prompt total decides whether the input contains the cache",
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'two spans with identical provider usage price differently according to the outer prompt total alone',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ outerPromptTokensSpans, project, backendClient }) => {
        const byName = await test.step('Read the seeded spans back once both of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, outerPromptTokensSpans));

        const cost = (key: string) => costOf(byName, outerPromptTokensSpans, key);

        await test.step("The two spans' provider usage really is identical, and only their outer total differs (seed shape)", async () => {
          // The premise of the entire comparison, and the one thing that could
          // make it pass for the wrong reason: if the two halves differed
          // anywhere inside `original_usage`, two different prices would be the
          // expected outcome and the spec would prove nothing about the outer
          // total. Asserted key by key rather than by comparing the two maps to
          // each other, so a write path that dropped the SAME key from both
          // fails here instead of agreeing with itself.
          for (const seed of outerPromptTokensSpans.spans) {
            const usage = spanFor(byName, outerPromptTokensSpans, seed.key).usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(
              usage!['original_usage.input_tokens'],
              `${seed.key}: the provider's reported input`,
            ).toBe(outerPromptTokensSpans.reportedInputTokens);
            expect(
              usage!['original_usage.cache_read_input_tokens'],
              `${seed.key}: the provider's reported cache read`,
            ).toBe(outerPromptTokensSpans.reportedCacheReadTokens);
            expect(
              usage!['original_usage.output_tokens'],
              `${seed.key}: the provider's reported output`,
            ).toBe(outerPromptTokensSpans.reportedOutputTokens);
            expect(
              usage!['prompt_tokens'],
              `${seed.key}: the SDK's own converted prompt total — the only thing that differs between the two spans`,
            ).toBe(seed.outerPromptTokens);
          }

          // And they really do differ in it, or the pair is two copies of the
          // same vector and the inequality below could never hold.
          const [first, second] = outerPromptTokensSpans.spans;
          expect(
            first.outerPromptTokens,
            'the two vectors must report different outer prompt totals',
          ).not.toBe(second.outerPromptTokens);
        });

        await test.step('Each vector is priced at the hand-computed table amount', async () => {
          // Absolute amounts first: they pin the arithmetic to the row's own
          // shipped rates rather than merely to itself, and they are what say
          // WHICH way each half was read rather than only that the two differ.
          for (const seed of outerPromptTokensSpans.spans) {
            expect(
              cost(seed.key),
              `${seed.key} — ${seed.means}: ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
          }
        });

        await test.step('The two differ by exactly the contested tokens at the full input rate', async () => {
          // The discriminating comparison. A backend that ignored the outer
          // total would price these two identically — a delta of zero —
          // whichever convention it then applied to both. And the delta being
          // the cache at the INPUT rate, rather than at any other rate, is what
          // says the 200 tokens moved between the input and cache buckets
          // rather than being re-rated.
          expect(
            cost('outer-includes-cache'),
            'the half whose outer total says the input EXCLUDES the cache bills 200 more tokens of input, so it must be the dearer of the two',
          ).toBeGreaterThan(cost('outer-excludes-cache'));
          expect(
            cost('outer-includes-cache') - cost('outer-excludes-cache'),
            'the gap is the 200 contested tokens at the $3.3/M input rate — the clause failing to fire in either direction shows up here',
          ).toBeCloseTo(outerPromptTokensSpans.reportedCacheReadTokens * INPUT_RATE, 9);
        });
      },
    );
  },
);
