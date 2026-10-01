import { test, expect, CACHED_TOKENS_KEY } from '@e2e/fixtures';
import type { CachedTokenSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * Server-side LLM cost resolution for CACHED input tokens (OPIK-7791).
 *
 * `span-cost-resolution.spec.ts` covers the rest of the resolution path — model
 * id normalisation, reasoning tokens, a per-character price — but nothing in the
 * estate has a vector for the cache branch, and the branch is a routing decision
 * rather than a rate: `CostService.resolveCalculator` sends a span to the
 * provider's cache-aware calculator only when its price entry publishes a
 * positive cache rate. A model whose `cache_read_input_token_cost` is absent
 * falls through to plain `textGenerationCost`, which never subtracts cached
 * tokens, so every cached token is billed at the full input rate.
 *
 * Release 2.2.60 re-priced `gpt-5.5-pro` with no cache rate, which is what makes
 * this observable: a span reporting 50k of its 200k prompt tokens as cache hits
 * costs exactly what one reporting none costs. `gpt-4.1` still publishes a cache
 * rate and is seeded as the control pair, discounting by the table's $0.5/M.
 *
 * The failure mode this guards is silent by construction. Both numbers render as
 * perfectly ordinary dollar amounts on the page people read to decide what their
 * LLM spend is; only the *pair* shows anything, which is why every vector here is
 * seeded twice over and asserted against its own twin.
 *
 * WHAT THIS SPEC DOES AND DOES NOT CLAIM. It pins the shipped price table as
 * 2.2.60 ships it, and that is deliberate — the release exploration flagged the
 * missing `gpt-5.5-pro` cache rate as possibly wrong, and nobody has yet
 * answered whether OpenAI still discounts cached input on that model. If the
 * answer turns out to be yes, the price entry gains a cache rate and the
 * `no-cache-rate` pair below goes red. That is this spec working, not flaking:
 * move the vector onto whichever entry then publishes no cache rate and keep the
 * comparison, which is the durable behaviour.
 */

/**
 * `formatCost`'s rendering of a resolved price: `lodash/floor(value, 2)`, with
 * trailing zeros dropped by JS number formatting. $7.80 renders as "$7.8" and
 * $0.405 as "$0.4".
 *
 * Floored, not rounded: money.ts calls `lodash/floor`, so rounding would expect
 * "$0.41" for the control vector and fail against a correct app. And floored the
 * way lodash floors — by shifting the decimal exponent in the number's string
 * form — rather than as `Math.floor(v * 100) / 100`, which is off by a cent
 * whenever the multiplication lands a hair low in binary (`7.8 * 100` is not
 * exactly 780, and `51.75 * 100` is under 5175).
 *
 * The panel locator matches exactly, so "$0.4" cannot pass for the "$0.48" the
 * sibling vector renders.
 */
const asDisplayed = (cost: number): string => {
  const [mantissa, exponent] = `${cost}e`.split('e');
  const shifted = Math.floor(Number(`${mantissa}e${Number(exponent || '0') + 2}`));
  return `$${Number(`${shifted}e-2`)}`;
};

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted rather than only the lookup: a read that also returned
 * spans from another trace would still find every seeded one and pass.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: CachedTokenSpansRef,
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
 * A vector the fixture failed to seed would otherwise surface as an
 * `undefined` the arithmetic below had to code around, which is the shape that
 * turns a broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: CachedTokenSpansRef,
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
 * Asserts a cost is present before returning it: for a model the price table
 * does resolve, an absent cost is the regression, and a `?? 0` here would
 * report it as a comparison between two zeros.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: CachedTokenSpansRef,
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
  'Span cost — cached input tokens',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'a model publishing no cache-read rate bills cached input tokens at the full input price, while one that publishes a rate discounts them',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cachedTokenSpans, project, backendClient }) => {
        // No page. The subject is which calculator the backend routed each span
        // to while pricing it; the panel renders whatever number came out, so
        // driving a browser here would add a rendering failure mode to an
        // assertion about arithmetic. The panel's rendering of these same four
        // spans is the second test below.

        const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
          readSeededSpans(backendClient, project.id, cachedTokenSpans));

        await test.step('Each span carries the model and the cached-token count the vector is about (seed shape)', async () => {
          // The guard that stops this spec from being able to pass for the wrong
          // reason. `cached_tokens` arrives under a dotted key the Python SDK
          // rewrites, which is why the fixture writes these spans straight to
          // REST — but "the key was dropped in transit" and "cached tokens
          // bought no discount" produce the identical cost, so the key has to be
          // read back off the stored span before any cost is compared.
          for (const seed of cachedTokenSpans.spans) {
            const span = spanFor(byName, cachedTokenSpans, seed.key);
            expect(span.model, `model id logged for ${seed.key}`).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe(seed.provider);

            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              cachedTokenSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              cachedTokenSpans.completionTokens,
            );
            if (seed.cachedTokens > 0) {
              expect(
                usage![CACHED_TOKENS_KEY],
                `${seed.key} must really report cached tokens under ${CACHED_TOKENS_KEY}, or its cost is not evidence of anything`,
              ).toBe(seed.cachedTokens);
            } else {
              expect(
                usage![CACHED_TOKENS_KEY],
                `${seed.key} is the no-cache half of its pair and must report no cached tokens`,
              ).toBeUndefined();
            }
          }
        });

        await test.step('Every vector is priced at the hand-computed table amount', async () => {
          // Absolute amounts first: they are what pins the arithmetic to the
          // shipped rates rather than merely to itself. Two spans agreeing on a
          // wrong number would satisfy the comparisons below and nothing else.
          for (const seed of cachedTokenSpans.spans) {
            expect(
              costOf(byName, cachedTokenSpans, seed.key),
              `${seed.key}: ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 6);
          }
        });

        await test.step('With no cache-read rate published, reporting cached tokens changes nothing', async () => {
          // The finding, stated as a comparison. Both spans report identical
          // prompt and completion totals and differ only in the cached count, so
          // a cache discount of any size would separate them.
          expect(
            costOf(byName, cachedTokenSpans, 'no-cache-rate-cached'),
            'gpt-5.5-pro publishes no cache_read_input_token_cost, so a span reporting 50k cached prompt tokens must cost exactly what an identical span reporting none costs — every cached token billed at the full input rate',
          ).toBeCloseTo(costOf(byName, cachedTokenSpans, 'no-cache-rate-plain'), 6);
        });

        await test.step('The control model does discount its cached tokens', async () => {
          // The half that makes the equality above a statement about the price
          // table rather than about the seed. gpt-4.1 publishes $0.5/M cache
          // read against $2/M input, so its cached span must come out strictly
          // cheaper — and by exactly the rate difference on 50k tokens.
          const cached = costOf(byName, cachedTokenSpans, 'cache-rate-cached');
          const plain = costOf(byName, cachedTokenSpans, 'cache-rate-plain');
          expect(
            cached,
            'gpt-4.1 publishes a cache_read rate, so its cached span must be cheaper than its uncached twin',
          ).toBeLessThan(plain);
          expect(
            plain - cached,
            'the discount is 50k tokens at the difference between $2/M input and $0.5/M cache read',
          ).toBeCloseTo(50_000 * (2e-6 - 0.5e-6), 6);
        });

        await test.step("The trace's rolled-up cost is the sum of its spans", async () => {
          const traceCost = await backendClient.getTraceCost(cachedTokenSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(traceCost!, 'trace total across every seeded span').toBeCloseTo(
            cachedTokenSpans.expectedTraceCost,
            6,
          );
        });
      },
    );

    test(
      'the trace panel renders the same cached-token costs the API resolved',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ cachedTokenSpans, project, backendClient, page }) => {
        const byName = await test.step('The server really priced every span before the panel is read for those amounts', async () => {
          // A UI assertion over a seed that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: cachedTokenSpans.traceId,
                });
                return spans.filter((s) => (s.totalEstimatedCost ?? 0) > 0).length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(cachedTokenSpans.spans.length);

          // Read back for the ids, which are what pin each panel assertion below
          // to the span it is supposed to be about.
          return readSeededSpans(backendClient, project.id, cachedTokenSpans);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const panel = await logs.openTraceById(cachedTokenSpans.traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('The trace header shows the rolled-up total', async () => {
          await expect(
            panel.estimatedCost(asDisplayed(cachedTokenSpans.expectedTraceCost)),
          ).toBeVisible();
        });

        await test.step('Each span shows its own resolved cost against its own model', async () => {
          for (const seed of cachedTokenSpans.spans) {
            await panel.selectSpan(seed.name);
            // Pin the two assertions below to the span that is actually
            // selected. `selectSpan` only waits for SOME span to be in the URL,
            // and each pair here shares both a model id and an expected amount —
            // so a click that left the viewer on the previous span would satisfy
            // both, giving an iteration that cannot fail.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
              })
              .toBe(byName.get(seed.name)!.id);
            await expect(panel.spanModelChip).toContainText(seed.model);
            await expect(panel.estimatedCost(asDisplayed(seed.expectedCost))).toBeVisible();
          }
        });
      },
    );
  },
);
