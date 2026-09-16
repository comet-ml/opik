import { test, expect } from '@e2e/fixtures';
import type { ModelCostSpanSeed, ModelCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * Server-side LLM cost resolution: dated model ids (OPIK-8242) and
 * reasoning-token billing (OPIK-7791).
 *
 * Every other cost assertion in the estate seeds `total_cost` from the client,
 * so the backend never has to look a price up — which means the resolution path
 * itself has never run in e2e. It is a silent-wrongness risk of the worst kind:
 * a model id the resolver fails to normalise reads as $0, and one it normalises
 * too eagerly reads as some other model's price. Both render as a perfectly
 * ordinary number on a page people read to decide what their LLM spend is.
 *
 * The fixture logs twelve LLM spans with `usage` and **no** `total_cost`:
 *
 *  - Five for id normalisation. Three carry ids that must resolve, covering
 *    four steps between them (provider-prefix strip, dot-normalising,
 *    compact-date strip, and an alias — the first id needs three of them at
 *    once); two are controls whose eight trailing digits are not dates and
 *    must not be stripped onto another model's row.
 *  - Five for reasoning tokens, all on one model, differing only in the
 *    reasoning count and the usage key it arrives under.
 *  - Two for a model priced per input character rather than per token
 *    (OPIK-7791), differing only in whether the character count is there. A
 *    price key the backend does not read is the same silent failure as a model
 *    id it cannot resolve: no cost chip on the span, and a trace total that
 *    still looks like a number.
 *
 * The expected amounts are the shipped price table's own numbers — see the
 * fixture. They are asserted at both surfaces because that is where the two can
 * disagree: the API is where the resolution happens, and the panel is where a
 * human would notice it being wrong.
 */

/** The seeded spans the price table must resolve, and the two it must not. */
const priced = (spans: readonly ModelCostSpanSeed[]): ModelCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost > 0);
const controls = (spans: readonly ModelCostSpanSeed[]): ModelCostSpanSeed[] =>
  spans.filter((s) => s.expectedCost === 0);

/**
 * `formatCost`'s rendering of a resolved price: floored to two decimals, with
 * trailing zeros dropped ($30.00 renders as "$30"). Written through `toFixed`
 * rather than `Math.floor(v * 100) / 100` because the latter turns 51.75 into
 * 51.74 — the binary representation of 51.75 * 100 is a hair under 5175.
 */
const asDisplayed = (cost: number): string => `$${Number(cost.toFixed(2))}`;

/**
 * A span's cost as the server attributed it, for the controls only.
 *
 * Absent and zero are the same answer for a model the price table must not
 * match — "nothing was billed" — and only the controls are allowed to collapse
 * them. The priced spans assert a non-null value first, so a cost that went
 * missing there fails instead of quietly reading as zero.
 */
const attributedCost = (span: SpanCostRef): number => span.totalEstimatedCost ?? 0;

/**
 * Every seeded span, by name, once all of them are queryable.
 *
 * The count is asserted, not just the lookup: a read that also returned spans
 * from another trace would still find every seeded one and pass.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: ModelCostSpansRef,
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
 * The cost the server attributed to the vector seeded under `key`.
 *
 * Asserts the span was read back and carries a cost before returning it, so a
 * seed that never landed fails here rather than turning into a `null` that the
 * arithmetic below would have to code around.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: ModelCostSpansRef,
  key: string,
): number {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  expect(
    span!.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is a failure, not a zero`,
  ).not.toBeNull();
  return span!.totalEstimatedCost!;
}

test.describe('Span cost — server-side price resolution', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('LLM spans logged without a cost are priced from their model id, and undated look-alikes are not', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    modelCostSpans,
    project,
    backendClient,
  }) => {
    const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
      readSeededSpans(backendClient, project.id, modelCostSpans));

    await test.step('Each span really carries the model id under test (seed shape)', async () => {
      // Without this the cost assertions below could pass over a seed that
      // silently dropped `model`, which is the one input the resolution reads.
      for (const seed of modelCostSpans.spans) {
        const span = byName.get(seed.name);
        expect(span, `span ${seed.name} was seeded`).toBeDefined();
        expect(span!.model, `model id logged for ${seed.name}`).toBe(seed.model);
        expect(span!.provider, `provider logged for ${seed.name}`).toBe(seed.provider);
      }
    });

    await test.step('Every resolvable model id is priced at the table rate', async () => {
      for (const seed of priced(modelCostSpans.spans)) {
        const span = byName.get(seed.name)!;
        expect(
          span.totalEstimatedCost,
          `${seed.model} must be priced server-side — an absent cost is the failure this spec exists for, not a zero`,
        ).not.toBeNull();
        expect(span.totalEstimatedCost!, `cost resolved for ${seed.model}`).toBeCloseTo(
          seed.expectedCost,
          6,
        );
      }
    });

    await test.step('Every control is billed nothing', async () => {
      for (const seed of controls(modelCostSpans.spans)) {
        expect(
          attributedCost(byName.get(seed.name)!),
          `${seed.key} (${seed.model}): ${seed.zeroCostReason ?? 'this vector must not be billed'}`,
        ).toBe(0);
      }
    });

    await test.step("The trace's rolled-up cost is the sum of its spans", async () => {
      const traceCost = await backendClient.getTraceCost(modelCostSpans.traceId);
      expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
      expect(traceCost!, 'trace total across every seeded span').toBeCloseTo(
        modelCostSpans.expectedTraceCost,
        6,
      );
    });
  });

  test('Reasoning tokens bill at the reasoning rate and come out of the standard-output bucket', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    modelCostSpans,
    project,
    backendClient,
  }) => {
    // No page: the subject is an arithmetic decision the backend makes while
    // pricing a span. The panel renders whatever number came out of it, so
    // reading it through a browser would add a rendering failure mode to an
    // assertion about arithmetic. The panel's rendering of these same spans is
    // covered by the UI test below, which walks every priced span.

    const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
      readSeededSpans(backendClient, project.id, modelCostSpans));

    const cost = (key: string) => costOf(byName, modelCostSpans, key);

    await test.step('Each vector is priced at the hand-computed table amount', async () => {
      // Absolute amounts first: they are what pins the arithmetic to the
      // shipped rates rather than merely to itself. Every one is 1M prompt +
      // 1M completion tokens on perplexity/sonar-deep-research
      // ($2/M in, $8/M out, $3/M reasoning) — see the fixture.
      const expected: Array<[string, number, string]> = [
        ['reasoning-absent', 10, '2.00 in + 1.0M x $8/M out'],
        ['reasoning-sdk-key', 8, '2.00 in + 600k x $8/M out + 400k x $3/M reasoning'],
        ['reasoning-otel-key', 8, 'same as the SDK key, reached through the bare OTel key'],
        ['reasoning-over-reported', 5, '2.00 in + 0 x $8/M out + 1.0M x $3/M reasoning'],
        ['reasoning-negative', 10, 'negative reasoning count floors at 0, so identical to absent'],
      ];
      for (const [key, amount, workings] of expected) {
        expect(cost(key), `${key}: ${workings}`).toBeCloseTo(amount, 6);
      }
    });

    await test.step('Reasoning tokens are billed at their own rate, not the output rate', async () => {
      // The discriminating comparison, and the reason the absent-key control is
      // seeded. Both spans report identical prompt and completion totals, so if
      // reasoning tokens were still billed at the plain output rate — the
      // behaviour before the split — these two would be equal. An absolute
      // assertion alone could not tell that apart from the price table moving.
      expect(
        cost('reasoning-sdk-key'),
        'a span reporting reasoning tokens must cost less than an identical one that reports none, because $3/M reasoning is cheaper than $8/M output',
      ).toBeLessThan(cost('reasoning-absent'));
    });

    await test.step('The bare OTel usage key is an equal-standing fallback', async () => {
      // completion_tokens_details.reasoning_tokens (OTel ingestion) must price
      // exactly as original_usage.completion_tokens_details.reasoning_tokens
      // (Python SDK 1.6.0+) does. The fixture writes this vector straight to
      // the REST API precisely so the SDK cannot normalise the two into one.
      expect(
        cost('reasoning-otel-key'),
        'the bare OTel key must resolve the same price as the original_usage. key',
      ).toBeCloseTo(cost('reasoning-sdk-key'), 6);
    });

    await test.step('Both clamps hold', async () => {
      // Over-report: reasoning tokens are a subset of completion tokens, so a
      // count above the completion total bills the whole output at the
      // reasoning rate and no more — never more reasoning than there are
      // completion tokens, and never a negative standard-output bucket, which
      // would show up as a cost below the $2.00 input floor.
      expect(
        cost('reasoning-over-reported'),
        'an over-reported reasoning count must clamp to completion_tokens, not bill beyond it',
      ).toBeLessThan(cost('reasoning-sdk-key'));
      expect(
        cost('reasoning-over-reported'),
        'the standard-output bucket must floor at zero rather than go negative',
      ).toBeGreaterThan(0);

      // Under-report: a negative count floors at zero, leaving the span priced
      // exactly as one that reported no reasoning tokens at all.
      expect(
        cost('reasoning-negative'),
        'a negative reasoning count must floor at 0, pricing identically to the absent-key control',
      ).toBeCloseTo(cost('reasoning-absent'), 6);
    });
  });

  test('A model priced per input character bills from its character count, not from its tokens', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    modelCostSpans,
    project,
    backendClient,
  }) => {
    // No page, for the same reason as the reasoning-token test above: the
    // subject is which usage key the backend multiplies a price by. The
    // panel's rendering of the priced one is covered by the UI test below.

    const byName = await test.step('Read the seeded spans back once all of them are queryable', async () =>
      readSeededSpans(backendClient, project.id, modelCostSpans));

    await test.step('The character-priced span is billed at the table rate', async () => {
      // mistral/voxtral-mini-tts-latest publishes input_cost_per_character and
      // no token rate at all: 1M characters x $1.6e-05 -> $16.00. A model whose
      // price key ModelCostData does not declare resolves to nothing instead,
      // and nothing is what this assertion exists to tell apart from $16.
      expect(
        costOf(byName, modelCostSpans, 'characters-priced'),
        'characters-priced: 1.0M input_characters x $1.6e-05/character',
      ).toBeCloseTo(16, 6);
    });

    await test.step('The same model without a character count is billed nothing', async () => {
      // The discriminating half. Both vectors carry the identical 1M prompt +
      // 1M completion tokens and differ only in `input_characters`, so a cost
      // that had come from the token counts would price them the same.
      const control = modelCostSpans.spans.find((s) => s.key === 'characters-absent');
      expect(control, "the fixture must seed a 'characters-absent' vector").toBeDefined();
      const span = byName.get(control!.name);
      expect(span, `span ${control!.name} was seeded`).toBeDefined();
      expect(
        attributedCost(span!),
        `characters-absent: ${control!.zeroCostReason}`,
      ).toBe(0);
    });
  });

  test('The trace panel renders the server-resolved cost for the trace and for each priced span', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    modelCostSpans,
    project,
    backendClient,
    page,
  }) => {
    const byName = await test.step('The server really resolved the prices the panel is about to be read for', async () => {
      // A UI assertion over a seed that never got priced is a test that cannot
      // fail: the amounts below would simply never appear, and any wait for
      // them would be indistinguishable from a rendering bug.
      await expect
        .poll(
          async () => {
            const spans = await backendClient.listSpanCosts({
              projectId: project.id,
              traceId: modelCostSpans.traceId,
            });
            return spans.filter((s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0)
              .length;
          },
          { timeout: 60_000, intervals: [500, 1_000, 2_000] },
        )
        .toBe(priced(modelCostSpans.spans).length);

      // Read back for the ids, which are what pin each panel assertion below to
      // the span it is supposed to be about.
      return readSeededSpans(backendClient, project.id, modelCostSpans);
    });

    const logs = new LogsPage(page);

    const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
      await logs.goto(project.id);
      const panel = await logs.openTraceById(modelCostSpans.traceId);
      await panel.waitForFullyLoaded();
      return panel;
    });

    await test.step('The trace header shows the rolled-up total', async () => {
      await expect(panel.estimatedCost(asDisplayed(modelCostSpans.expectedTraceCost))).toBeVisible();
    });

    await test.step('Each priced span shows its own resolved cost', async () => {
      // Deliberately not asserted here: what the panel renders for the two
      // control spans. `formatCost` maps a zero cost to a bare "-", which is
      // not a claim the UI can be held to — the controls are asserted at the
      // API, where "no cost was attributed" is unambiguous.
      for (const seed of priced(modelCostSpans.spans)) {
        await panel.selectSpan(seed.name);
        // Pin the two assertions below to the span that is actually selected.
        // `selectSpan` only waits for SOME span to be in the URL, and five of
        // these vectors share one model while two pairs share an expected
        // amount — so a click that left the viewer on the previous span would
        // still satisfy both, giving an iteration that cannot fail.
        await expect
          .poll(() => new URL(page.url()).searchParams.get('span'), {
            message: `the panel must be showing '${seed.name}' itself, not whichever span was selected before it`,
          })
          .toBe(byName.get(seed.name)!.id);
        await expect(panel.spanModelChip).toContainText(seed.model);
        await expect(panel.estimatedCost(asDisplayed(seed.expectedCost))).toBeVisible();
      }
    });
  });
});
