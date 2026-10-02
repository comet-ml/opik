import { test, expect } from '@e2e/fixtures';
import type { OtelAliasSpanSeed, OtelProviderAliasSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * OTel `gen_ai.provider.name` aliasing on the ingestion endpoint (OPIK-8616 /
 * opik#8621), and the cost that depends on it.
 *
 * quarkus-langchain4j writes LangChain4j's own `ModelProvider` enum lowercased
 * into `gen_ai.provider.name` — `open_ai`, `amazon_bedrock`, `google_ai_gemini`
 * — rather than the OTel semconv value. A provider string that reaches
 * `CostService` unmapped matches no pricing row, so the span is stored with a
 * provider Opik does not recognise and billed nothing.
 *
 * That is silent wrongness of the worst shape. There is no error, no empty
 * state, and no missing row: the span is there, the model and the token counts
 * are right, and the cost reads $0.00 on the page people open to decide what
 * their LLM spend is. Nothing in this estate could see it before — the OTLP
 * endpoint had no caller at all, and the one `otel-rest` writer we do have sets
 * `provider` as a literal field on `POST /v1/private/spans`, which bypasses
 * `ProviderResolvers` entirely.
 *
 * The fixture exports eight spans through `POST /v1/private/otel/v1/traces`,
 * carrying token counts and NO `gen_ai.usage.cost`, so the price is the server's
 * own answer:
 *
 *  - Three aliased/canonical PAIRS, one per alias. Each pair is identical but
 *    for the provider string, which is what makes the comparison discriminating
 *    in both directions: an absolute amount alone could not tell "the alias
 *    resolved" apart from "the price table moved", and an equality alone could
 *    not tell it apart from "neither side is priced". Both are asserted.
 *  - Two providers opik#8621 deliberately does NOT alias, on the same models as
 *    a priced pair. `google_genai` names more than one Google backend and
 *    Quarkus' Azure OpenAI model reports `OTHER`, so an alias map that grew too
 *    eagerly would attribute a span to a backend nobody established it ran on —
 *    and `azure_open_ai` priced against the OpenAI table is the specific
 *    mis-attribution `GenAiProviderAliasResolver`'s own comment cites.
 *
 * Asserted at the API, where the resolution happens, and in the trace panel,
 * where a human would notice it being wrong.
 */

/** The seeded spans the resolver must alias and price, and the two it must not. */
const priced = (spans: readonly OtelAliasSpanSeed[]): OtelAliasSpanSeed[] =>
  spans.filter((s) => s.expectedCost > 0);
const excluded = (spans: readonly OtelAliasSpanSeed[]): OtelAliasSpanSeed[] =>
  spans.filter((s) => s.expectedCost === 0);
/** The three vectors that reported an alias, each of which names its control. */
const aliased = (spans: readonly OtelAliasSpanSeed[]): OtelAliasSpanSeed[] =>
  spans.filter((s) => s.controlKey !== undefined);

/**
 * `formatCost`'s rendering of a resolved price: floored to two decimals with
 * trailing zeros dropped, so $2.00 renders as "$2". Written through `toFixed`
 * for the reason `span-cost-resolution.spec.ts` gives — `Math.floor(v * 100)
 * / 100` turns 51.75 into 51.74.
 */
const asDisplayed = (cost: number): string => `$${Number(cost.toFixed(2))}`;

/**
 * Every exported span, by name, once all of them are queryable.
 *
 * The count is asserted, not just the lookup: a read that also returned spans
 * from another trace would still find every seeded one and pass.
 */
async function readExportedSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: OtelProviderAliasSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () =>
        (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      { timeout: 60_000, intervals: [500, 1_000, 2_000] },
    )
    .toBe(seed.spans.length);

  const spans = await backendClient.listSpanCosts({ projectId, traceId: seed.traceId });
  expect(spans, 'the trace carries exactly the exported spans').toHaveLength(
    seed.spans.length,
  );
  return new Map(spans.map((s) => [s.name, s]));
}

/**
 * The span the fixture exported under `key`, read back.
 *
 * Asserts the vector exists in the seed and the span came back before returning
 * it, so a seed that never landed fails here rather than turning into an
 * `undefined` the assertions below would have to code around.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: OtelProviderAliasSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} reached the backend`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed, asserted present first.
 *
 * An absent cost and a zero cost are the same answer — "nothing was billed" —
 * and only the excluded vectors are allowed to collapse them. For a priced
 * vector, a missing value IS the failure this spec exists for, so it must not
 * read as a zero that some later comparison happens to accept.
 */
function costOf(span: SpanCostRef, key: string): number {
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is the failure this spec exists for, not a zero`,
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

test.describe(
  'OTel ingestion — provider aliasing and the cost that depends on it',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'A quarkus-langchain4j provider alias is stored canonically and priced like the canonical span beside it',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelProviderAliasSpans, project, backendClient }) => {
        // No page: the subject is what the ingestion endpoint made of an
        // attribute. The panel renders whatever provider and cost came out of
        // it, so reading this through a browser would add a rendering failure
        // mode to an assertion about a mapping. The panel is asserted in the UI
        // test below, over these same spans.
        const byName = await test.step('Read the exported spans back once all of them are queryable', async () =>
          readExportedSpans(backendClient, project.id, otelProviderAliasSpans));

        await test.step('Each span really carries the model it was exported with (seed shape)', async () => {
          // Without this the cost assertions below could pass over an export
          // whose `gen_ai.request.model` never mapped, which is the other input
          // the price lookup reads — and a pair that agreed at $0 would satisfy
          // the equality below for entirely the wrong reason.
          for (const seed of otelProviderAliasSpans.spans) {
            const span = spanFor(byName, otelProviderAliasSpans, seed.key);
            expect(span.model, `model mapped for ${seed.key}`).toBe(seed.model);
          }
        });

        await test.step('Every aliased provider is STORED as its canonical Opik name', async () => {
          // The stored value, not just the cost. A resolver that priced
          // correctly while persisting the reported spelling would still break
          // provider filtering and grouping, and no cost assertion would see it.
          for (const seed of aliased(otelProviderAliasSpans.spans)) {
            const span = spanFor(byName, otelProviderAliasSpans, seed.key);
            expect(
              span.provider,
              `'${seed.reportedProvider}' must be stored as '${seed.storedProvider}'`,
            ).toBe(seed.storedProvider);
          }
        });

        await test.step('Each aliased span is priced EXACTLY as its canonical control', async () => {
          // The discriminating comparison, and the reason each pair is seeded.
          // Both spans of a pair report the same model and the same token
          // counts and differ only in the provider string, so an alias that
          // stopped resolving prices one of them at nothing while the other is
          // unaffected.
          for (const seed of aliased(otelProviderAliasSpans.spans)) {
            const alias = spanFor(byName, otelProviderAliasSpans, seed.key);
            const control = spanFor(byName, otelProviderAliasSpans, seed.controlKey!);
            expect(
              costOf(alias, seed.key),
              `'${seed.reportedProvider}' must price exactly as '${control.provider}' does on the same model and counts`,
            ).toBeCloseTo(costOf(control, seed.controlKey!), 6);
          }
        });

        await test.step('And at the shipped price table amount, not merely at each other', async () => {
          // Absolute amounts pin the arithmetic to the table rather than to
          // itself: a pair that agreed at some other number would satisfy the
          // equality above. Each is 1M input + 1M output tokens at the row's own
          // two rates — see the fixture for the workings.
          for (const seed of priced(otelProviderAliasSpans.spans)) {
            const span = spanFor(byName, otelProviderAliasSpans, seed.key);
            expect(
              costOf(span, seed.key),
              `${seed.key} (${seed.model} under ${seed.storedProvider}): 1M in + 1M out at the table rates`,
            ).toBeCloseTo(seed.expectedCost, 6);
          }
        });
      },
    );

    test(
      'A provider value opik#8621 excludes is stored verbatim and billed nothing',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelProviderAliasSpans, project, backendClient }) => {
        const byName = await test.step('Read the exported spans back once all of them are queryable', async () =>
          readExportedSpans(backendClient, project.id, otelProviderAliasSpans));

        await test.step('Both excluded values are persisted exactly as reported', async () => {
          for (const seed of excluded(otelProviderAliasSpans.spans)) {
            const span = spanFor(byName, otelProviderAliasSpans, seed.key);
            expect(
              span.provider,
              `'${seed.reportedProvider}' must reach storage unaliased`,
            ).toBe(seed.reportedProvider);
          }
        });

        await test.step('And neither is billed', async () => {
          // Absent and zero are the same answer here — "nothing was billed" —
          // and these are the only two vectors allowed to collapse them.
          for (const seed of excluded(otelProviderAliasSpans.spans)) {
            const span = spanFor(byName, otelProviderAliasSpans, seed.key);
            expect(
              span.totalEstimatedCost ?? 0,
              `${seed.key} (${seed.reportedProvider}): ${seed.zeroCostReason}`,
            ).toBe(0);
          }
        });

        await test.step('The same model IS priced when it reports a provider the resolver handles', async () => {
          // The half that stops this test from passing on a backend that prices
          // nothing at all. `google-genai-excluded` shares its model with
          // `google-ai-canonical` and `azure-open-ai-excluded` with
          // `openai-canonical`, so "not priced" cannot be explained by the model
          // having no row.
          for (const seed of excluded(otelProviderAliasSpans.spans)) {
            const sameModelPriced = priced(otelProviderAliasSpans.spans).filter(
              (s) => s.model === seed.model,
            );
            expect(
              sameModelPriced.length,
              `the fixture must seed a priced vector on '${seed.model}' to read ${seed.key} against`,
            ).toBeGreaterThan(0);
            for (const control of sameModelPriced) {
              expect(
                costOf(spanFor(byName, otelProviderAliasSpans, control.key), control.key),
                `${control.key} shares '${seed.model}' with ${seed.key} and must be priced, or the zero above says nothing about the provider`,
              ).toBeGreaterThan(0);
            }
          }
        });
      },
    );

    test(
      'The trace panel renders a resolved cost for every aliased span, not an unpriced dash',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelProviderAliasSpans, project, backendClient, page }) => {
        const byName = await test.step('The server really priced the exported spans the panel is about to be read for', async () => {
          // A UI assertion over an export that never got priced is a test that
          // cannot fail: the amounts below would simply never appear, and any
          // wait for them would be indistinguishable from a rendering bug.
          await expect
            .poll(
              async () => {
                const spans = await backendClient.listSpanCosts({
                  projectId: project.id,
                  traceId: otelProviderAliasSpans.traceId,
                });
                return spans.filter(
                  (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                ).length;
              },
              { timeout: 60_000, intervals: [500, 1_000, 2_000] },
            )
            .toBe(priced(otelProviderAliasSpans.spans).length);

          return readExportedSpans(backendClient, project.id, otelProviderAliasSpans);
        });

        const logs = new LogsPage(page);

        const panel = await test.step('Open the exported trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const panel = await logs.openTraceById(otelProviderAliasSpans.traceId);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('Each priced span shows its model and its own resolved cost', async () => {
          // Deliberately not asserted here: what the panel renders for the two
          // excluded spans. `formatCost` maps a zero cost to a bare "-", which
          // is not a claim the UI can be held to — they are asserted at the API,
          // where "no cost was attributed" is unambiguous.
          for (const seed of priced(otelProviderAliasSpans.spans)) {
            await panel.selectSpan(seed.name);
            // Pin the assertions to the span actually selected. `selectSpan`
            // only waits for SOME span to be in the URL, and these vectors come
            // in pairs sharing a model AND an expected amount — so a click that
            // left the viewer on the previous span would satisfy both and give
            // an iteration that cannot fail.
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
