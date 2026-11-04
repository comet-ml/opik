import { test, expect } from '@e2e/fixtures';
import type { MixedCaseModelCostSeed, MixedCaseModelCostSpansRef } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import { displayedCost } from '@e2e/core/money';
import type { BackendClient, SpanCostRef } from '@e2e/core/backend';

/**
 * A model id whose LETTER CASE differs from the price table's key still
 * resolves a price — on the API, in the trace panel, and in the Spans table
 * (opik#9309 / OPIK-9052).
 *
 * `CostService.findModelPriceUnderProvider` tries the id verbatim and then a
 * ladder of normalised variants, every rung of which lower-cases. Each rung is
 * guarded by "did normalising actually change the name?", and before this
 * release those guards compared case-insensitively — so an id differing from
 * its own variant only in case was judged unchanged, every retry was skipped,
 * and the span resolved NO price. The upstream fix is a one-word change to a
 * comparison, which is exactly the kind of thing that regresses again quietly.
 *
 * The user-visible failure is not an error. A span logged by a client that
 * upper-cases model names — Bedrock's own ids are dotted and routinely
 * upper-cased in configuration, and OTel producers pass through whatever
 * `gen_ai.request.model` carried — renders a token count and no cost, while the
 * trace above it still rolls up a perfectly ordinary-looking total that is
 * simply too small. Nobody re-derives that number by hand.
 *
 * ## Why the sixteen existing specs on this capability could not catch it
 *
 * Every model id in `model-cost-spans.fixture.ts`,
 * `openrouter-removed-row-spans.fixture.ts`, `openrouter-cost-spans.fixture.ts`
 * and their siblings is lower-case. They exercise the ladder's other rungs
 * thoroughly — provider-prefix strip, dot-normalising, compact-date strip,
 * version-pin strip — and all of them from an id that was already lower-case,
 * which is the one input that cannot reach the guard this release fixed. That
 * is a coverage gap rather than an oversight: the case path did not exist as a
 * distinct question until the bug was found.
 *
 * ## What makes each assertion discriminating
 *
 * Every vector is seeded with `usage` and no `total_cost`, so each amount is the
 * server's own lookup against the shipped file rather than something the seed
 * handed back to itself. And each mixed-case id is paired with the lower-case
 * spelling of the SAME model, seeded in the same trace in the same read:
 *
 *  - the absolute amount pins the lookup to the shipped table, so a resolver
 *    that reached some other model's row fails even if that row's rate looked
 *    plausible;
 *  - the equality with its control pins the two spellings to the SAME row, so a
 *    table whose rates moved fails on the amount while the pairing still holds,
 *    and the two failures read differently.
 *
 * Neither alone is enough: a build that priced nothing at all satisfies "both
 * spellings agree", and a build that resolved the upper-case id onto a
 * same-priced neighbour satisfies the absolute amount.
 *
 * ## Not claimed
 *
 * Which rung of the ladder resolved a given id. The ladder is internal and the
 * spellings here were chosen so that between them they can only be served by
 * three different rungs (see the fixture), but nothing in a response says which
 * one ran — so this asserts the outcome a user sees, not the path.
 */

/**
 * Every seeded span, by name, once all six are queryable.
 *
 * The whole set is read and its length asserted rather than only the vectors a
 * step needs: three vectors are asserted to be priced at the SAME amount as
 * three others, and a read that silently returned fewer rows would leave a
 * `costOf` lookup failing on an absent span rather than on a wrong amount.
 */
async function readSeededSpans(
  backendClient: BackendClient,
  projectId: string,
  seed: MixedCaseModelCostSpansRef,
): Promise<Map<string, SpanCostRef>> {
  await expect
    .poll(
      async () => (await backendClient.listSpanCosts({ projectId, traceId: seed.traceId })).length,
      {
        timeout: 60_000,
        intervals: [500, 1_000, 2_000],
        message:
          `all ${seed.spans.length} seeded spans of trace ${seed.traceId} to become queryable — ` +
          'a count stuck below that is span ingestion still catching up, not a pricing answer',
      },
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
 * the comparisons below had to code around, which is the shape that turns a
 * broken seed into a green run.
 */
function spanFor(
  byName: Map<string, SpanCostRef>,
  seed: MixedCaseModelCostSpansRef,
  key: string,
): SpanCostRef {
  const vector = seed.spans.find((s) => s.key === key);
  expect(vector, `the fixture must seed a '${key}' vector`).toBeDefined();
  const span = byName.get(vector!.name);
  expect(span, `span ${vector!.name} was seeded`).toBeDefined();
  return span!;
}

/**
 * The cost the server attributed to `key`.
 *
 * Asserts a cost is present before returning it. Every vector here must be
 * priced — an absent cost IS the regression — so a `?? 0` would report the bug
 * as a comparison between two zeros, which is the one shape that would make
 * this whole spec pass against the build it exists to fail on.
 */
function costOf(
  byName: Map<string, SpanCostRef>,
  seed: MixedCaseModelCostSpansRef,
  key: string,
): number {
  const span = spanFor(byName, seed, key);
  expect(
    span.totalEstimatedCost,
    `${key} must be priced server-side — an absent cost is the failure this spec exists for, ` +
      'not a zero',
  ).not.toBeNull();
  return span.totalEstimatedCost!;
}

test.describe(
  'Span cost — a model id whose letter case differs from the price table key',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'an upper-case or dotted upper-case model id is priced at its lower-case rate, and the trace rolls up every span',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ mixedCaseModelCostSpans, project, backendClient }) => {
        // No page. The subject is the amount the price lookup resolves; the
        // panel renders whatever came out of it, so driving a browser here
        // would add a rendering failure mode to an assertion about arithmetic.
        // The two surfaces a user reads it on are the second test.

        const byName = await test.step(
          'Read the seeded spans back once all of them are queryable',
          async () => readSeededSpans(backendClient, project.id, mixedCaseModelCostSpans),
        );

        await test.step('Each span really carries the model id spelling under test (seed shape)', async () => {
          // The premise, and here it is unusually load-bearing: the one input
          // this spec varies is letter case, so a write path that lower-cased
          // `model` on the way in would leave every vector a duplicate of its
          // own control and every comparison below trivially true.
          for (const seed of mixedCaseModelCostSpans.spans) {
            const span = spanFor(byName, mixedCaseModelCostSpans, seed.key);
            expect(
              span.model,
              `model id logged for ${seed.key} must arrive character for character, case included`,
            ).toBe(seed.model);
            expect(span.provider, `provider logged for ${seed.key}`).toBe(seed.provider);
            const usage = span.usage;
            expect(usage, `usage logged for ${seed.key}`).not.toBeNull();
            expect(usage!['prompt_tokens'], `prompt_tokens for ${seed.key}`).toBe(
              mixedCaseModelCostSpans.promptTokens,
            );
            expect(usage!['completion_tokens'], `completion_tokens for ${seed.key}`).toBe(
              mixedCaseModelCostSpans.completionTokens,
            );
          }
        });

        await test.step('Every spelling resolves the amount the shipped price table implies', async () => {
          // Absolute amounts first: they are what pins the lookup to the
          // shipped file rather than merely to the control beside it. A
          // resolver that reached a DIFFERENT row fails here even where the
          // equality below would still hold.
          for (const seed of mixedCaseModelCostSpans.spans) {
            expect(
              costOf(byName, mixedCaseModelCostSpans, seed.key),
              `${seed.key} (${seed.model} / ${seed.provider}): ${seed.workings}`,
            ).toBeCloseTo(seed.expectedCost, 9);
          }
        });

        await test.step('…and each mixed-case spelling is priced exactly as its lower-case twin', async () => {
          // The discriminating comparison, and the reason the three controls
          // are seeded at all. Without it, "the upper-case id costs $2.25" and
          // "the price table says $2.25" are the same observation, and neither
          // says the two spellings reached one row. Asserted over the pairs the
          // fixture declares rather than over hardcoded keys, so a pair added
          // there is checked here for free.
          const pairs = mixedCaseModelCostSpans.spans.filter((s) => s.equalsKey !== null);
          expect(pairs, 'three mixed-case spellings, each with a control').toHaveLength(3);
          for (const seed of pairs) {
            expect(
              costOf(byName, mixedCaseModelCostSpans, seed.key),
              `'${seed.model}' must reach the same price row as '${seed.equalsKey}' — a ` +
                'difference here means the two spellings resolved to different models',
            ).toBeCloseTo(costOf(byName, mixedCaseModelCostSpans, seed.equalsKey!), 9);
          }
        });

        await test.step("The trace's rolled-up cost is the sum of all six spans", async () => {
          // The number a user actually reads above the span list, and the one
          // the regression made plausible rather than obviously absent: with
          // the three mixed-case spellings unpriced this total would be exactly
          // half, which is a perfectly ordinary figure.
          const traceCost = await backendClient.getTraceCost(mixedCaseModelCostSpans.traceId);
          expect(traceCost, 'the trace must report a rolled-up cost').not.toBeNull();
          expect(
            traceCost!,
            'trace total across every seeded span — three spellings and their three controls',
          ).toBeCloseTo(mixedCaseModelCostSpans.expectedTraceCost, 9);
        });
      },
    );

    test(
      'the trace panel and the Spans table both show the mixed-case spans their own resolved amounts',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ mixedCaseModelCostSpans, project, backendClient, page }) => {
        // A panel walk over six spans plus a second navigation to the Spans
        // table and a per-row read there does not fit the 90s default, and on
        // staging the listing reads are rate-limited per workspace so each one
        // may have to wait out a retry.
        test.setTimeout(300_000);

        const byName = await test.step(
          'The server really priced all six spans before the page is read for those amounts',
          async () => {
            // A UI assertion over a seed that never got priced is a test that
            // cannot fail: the amounts below would simply never appear, and a
            // wait for them would be indistinguishable from a rendering bug.
            // Polled to exactly six, which is the first test's claim and this
            // one's precondition.
            await expect
              .poll(
                async () => {
                  const spans = await backendClient.listSpanCosts({
                    projectId: project.id,
                    traceId: mixedCaseModelCostSpans.traceId,
                  });
                  return spans.filter(
                    (s) => s.totalEstimatedCost !== null && s.totalEstimatedCost > 0,
                  ).length;
                },
                {
                  message:
                    'the server to price every seeded span — a timeout here means the lookup ' +
                    'resolved nothing for a spelling, not that a surface rendered it wrongly',
                  timeout: 60_000,
                  intervals: [500, 1_000, 2_000],
                },
              )
              .toBe(mixedCaseModelCostSpans.spans.length);

            return readSeededSpans(backendClient, project.id, mixedCaseModelCostSpans);
          },
        );

        const logs = new LogsPage(page);

        const panel = await test.step('Open the seeded trace in the Logs panel', async () => {
          await logs.goto(project.id);
          const opened = await logs.openTraceById(mixedCaseModelCostSpans.traceId);
          await opened.waitForFullyLoaded();
          // Wait for the TREE, not just the panel, before anything clicks a
          // node. The tree is filled by `GET /v1/private/spans?trace_id=…`,
          // which is rate limited per workspace; when parallel specs have spent
          // the budget the panel's call is shed with a 429, the empty answer is
          // cached, and nothing refetches — so the tree renders `Spans (0)` and
          // every `selectSpan` below times out on a node that can never appear.
          // `waitForSpanTreeNodes` reloads to re-issue the query, which is the
          // only thing that recovers it; see the POM method. Measured: without
          // this the walk failed on the first span at two workers while the API
          // had priced all six perfectly.
          await opened.waitForSpanTreeNodes(mixedCaseModelCostSpans.spans.map((s) => s.name));
          return opened;
        });

        await test.step('The trace header shows the rolled-up total', async () => {
          await expect(
            panel.estimatedCost(displayedCost(mixedCaseModelCostSpans.expectedTraceCost)),
            'the header names the sum of all six spans',
          ).toBeVisible();
        });

        await test.step('Each span shows its own resolved cost against its own model id', async () => {
          for (const seed of mixedCaseModelCostSpans.spans) {
            await panel.selectSpan(seed.name);
            // Pin both reads below to the span that is actually selected.
            // `selectSpan` only waits for SOME span to be in the URL, and these
            // vectors come in same-priced pairs — so a click that left the
            // viewer on the previous span would satisfy the cost assertion and
            // give an iteration that cannot fail.
            await expect
              .poll(() => new URL(page.url()).searchParams.get('span'), {
                message:
                  `the panel must be showing '${seed.name}' itself, not whichever span was ` +
                  'selected before it',
              })
              .toBe(byName.get(seed.name)!.id);
            // The model chip is read as well as the cost, and exactly: it is
            // what tells a reader WHICH spelling the amount belongs to, and a
            // panel that displayed the normalised lower-case id beside a
            // correctly-resolved cost would be a different (cosmetic) defect
            // this would surface rather than hide.
            await expect(
              panel.spanModelChip,
              `the chip names the id as logged for ${seed.key}`,
            ).toContainText(seed.model);
            await expect(
              panel.estimatedCost(displayedCost(seed.expectedCost)),
              `${seed.key} renders its own amount, not a dash`,
            ).toBeVisible();
          }
        });

        await test.step("The Spans table's Estimated cost column agrees, span by span", async () => {
          // A separate render path from the panel's header — the shared
          // DataTable's `CostCell` over the spans listing — and the column a
          // user scans before opening anything. It is also where the
          // regression was least visible: a dash in one row among five amounts
          // reads as a span that happened not to be an LLM call.
          await logs.gotoSpans(project.id, { size: 100 });
          // Keyed on the seeded span ids rather than on a population count:
          // this project's Spans view also holds whatever the fixture chain
          // above seeded, so "the table has six rows" is not this spec's claim.
          for (const seed of mixedCaseModelCostSpans.spans) {
            const spanId = byName.get(seed.name)!.id;
            const row = logs.spanRow(spanId);
            await expect(
              row,
              `exactly one Spans-view row for ${seed.key} (span ${spanId})`,
            ).toHaveCount(1, { timeout: 90_000 });
            const cell = logs.spanEstimatedCostCell(spanId);
            await expect(cell, `one Estimated cost cell for ${seed.key}`).toHaveCount(1);
            // Matched as the node whose OWN text is the amount rather than as
            // the cell's full text content: where the Ollie assistant bridge is
            // up — staging is — this cell also holds the hover "Explain"
            // trigger, so a whole-cell match reads "$2.25Explain" and fails for
            // a reason that has nothing to do with the cost.
            await expect(
              cell.getByText(displayedCost(seed.expectedCost), { exact: true }),
              `the Spans table names ${seed.key}'s own amount exactly once`,
            ).toHaveCount(1);
          }
        });
      },
    );
  },
);
