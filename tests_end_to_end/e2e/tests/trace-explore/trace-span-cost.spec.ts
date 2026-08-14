import * as fs from 'node:fs';
import * as path from 'node:path';
import { test, expect } from '@e2e/fixtures';
import type { SdkClient } from '@e2e/core/sdk';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * The price table the backend ships and prices against. Read from the repo
 * rather than hard-coded, so the expectation is "the backend applied the rate
 * it shipped with" — the invariant a price-table regeneration can silently
 * break — and not "the backend applied a rate someone typed into a spec once".
 */
const PRICE_TABLE_PATH = path.resolve(
  __dirname,
  '../../../../apps/opik-backend/src/main/resources/model_prices_and_context_window.json',
);

interface PriceEntry {
  input_cost_per_token?: number;
  output_cost_per_token?: number;
}

const priceTable = JSON.parse(fs.readFileSync(PRICE_TABLE_PATH, 'utf8')) as Record<
  string,
  PriceEntry
>;

function ratesFor(model: string): { input: number; output: number } {
  const entry = priceTable[model];
  if (!entry || entry.input_cost_per_token == null || entry.output_cost_per_token == null) {
    throw new Error(
      `${model} has no input/output token price in ${path.basename(PRICE_TABLE_PATH)} — ` +
        'either the model was renamed or its price entry was dropped',
    );
  }
  return { input: entry.input_cost_per_token, output: entry.output_cost_per_token };
}

/** One model/provider pair whose cost the backend must compute from usage alone. */
interface PricedModel {
  model: string;
  provider: string;
}

// Three providers, so a regression confined to one provider's rates still
// fails. Every entry must exist in the shipped price table.
const PRICED_MODELS: PricedModel[] = [
  { model: 'gpt-4o', provider: 'openai' },
  { model: 'gpt-4o-mini', provider: 'openai' },
  { model: 'claude-haiku-4-5-20251001', provider: 'anthropic' },
];

const PROMPT_TOKENS = 1_000;
const COMPLETION_TOKENS = 500;

// Big enough that the computed figure escapes the UI's "<$0.01" bucket and a
// real number has to render: 1,000,000 x $2.5/M + 500,000 x $10/M = $7.50.
const UI_MODEL: PricedModel = { model: 'gpt-4o', provider: 'openai' };
const UI_PROMPT_TOKENS = 1_000_000;
const UI_COMPLETION_TOKENS = 500_000;

const LLM_SPAN = 'priced-llm-call';

function expectedCost(model: string, promptTokens: number, completionTokens: number): number {
  const rates = ratesFor(model);
  return promptTokens * rates.input + completionTokens * rates.output;
}

/**
 * Match the rendered cost inside an element's text while refusing a longer
 * number — "$7.55" must not satisfy "$7.5", which a plain substring match would
 * allow. Deliberately not an exact-text match: the Traces table's cost cell
 * also hosts the Ollie "Explain cost" trigger, which mounts asynchronously and
 * appends its own label, and the panel renders the figure alongside its label.
 */
function renderedCostPattern(rendered: string): RegExp {
  return new RegExp(`${rendered.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}(?!\\d)`);
}

/**
 * Seed a trace holding a single LLM span that carries model, provider and token
 * usage but **no** `total_cost`, so the only cost the backend can report is one
 * it computed itself.
 */
async function seedPricedTrace(
  sdkClient: SdkClient,
  args: {
    projectName: string;
    traceName: string;
    model: string;
    provider: string;
    promptTokens: number;
    completionTokens: number;
  },
): Promise<{ id: string }> {
  return sdkClient.python.createNestedTrace({
    project_name: args.projectName,
    name: args.traceName,
    input: { prompt: 'price this' },
    output: { completion: 'priced' },
    spans: [
      {
        name: LLM_SPAN,
        type: 'llm',
        model: args.model,
        provider: args.provider,
        input: { prompt: 'price this' },
        output: { completion: 'priced' },
        usage: {
          prompt_tokens: args.promptTokens,
          completion_tokens: args.completionTokens,
          total_tokens: args.promptTokens + args.completionTokens,
        },
        // total_cost deliberately omitted — see the doc comment above.
      },
    ],
  });
}

test.describe('Trace spans — backend-computed cost', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('An LLM span logged with usage and no cost is priced from the shipped price table, and rolls up to its trace', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
  }) => {
    test.setTimeout(180_000);

    const seeded = await test.step(
      `Seed one trace per priced model (${PROMPT_TOKENS} prompt / ${COMPLETION_TOKENS} completion tokens, no cost)`,
      async () => {
        const traces = await Promise.all(
          PRICED_MODELS.map((m) =>
            seedPricedTrace(sdkClient, {
              projectName: project.name,
              traceName: `${testNamespace}-${m.model}`,
              model: m.model,
              provider: m.provider,
              promptTokens: PROMPT_TOKENS,
              completionTokens: COMPLETION_TOKENS,
            }),
          ),
        );
        return PRICED_MODELS.map((m, i) => ({ ...m, traceId: traces[i].id }));
      },
    );

    for (const seed of seeded) {
      const expected = expectedCost(seed.model, PROMPT_TOKENS, COMPLETION_TOKENS);

      await test.step(`${seed.provider}/${seed.model}: span cost == tokens x shipped rate ($${expected})`, async () => {
        const spans = await backendClient.listSpansForTrace({
          traceId: seed.traceId,
          projectId: project.id,
        });
        expect(spans.map((s) => s.name)).toEqual([LLM_SPAN]);
        const span = spans[0];

        // The inputs the price lookup keys on must survive the round-trip;
        // otherwise a cost match below could be a coincidence of the backend
        // pricing a different model than the one we logged.
        expect(span.model, 'span model round-trips').toBe(seed.model);
        expect(span.provider, 'span provider round-trips').toBe(seed.provider);
        expect(span.usage.prompt_tokens, 'prompt tokens round-trip').toBe(PROMPT_TOKENS);
        expect(span.usage.completion_tokens, 'completion tokens round-trip').toBe(
          COMPLETION_TOKENS,
        );

        // The assertion this spec exists for: the backend priced the span
        // itself, and it used the rate it shipped with. Tolerance is ~1e-8 —
        // tighter than any price change these models could plausibly receive,
        // loose enough to absorb decimal/float representation.
        expect(
          span.totalEstimatedCost,
          `${seed.model} must be priced by the backend, not left unpriced`,
        ).not.toBeNull();
        expect(
          span.totalEstimatedCost!,
          `${seed.model}: ${PROMPT_TOKENS} x ${ratesFor(seed.model).input} + ${COMPLETION_TOKENS} x ${ratesFor(seed.model).output}`,
        ).toBeCloseTo(expected, 8);
      });

      await test.step(`${seed.provider}/${seed.model}: the parent trace rolls up the same figure`, async () => {
        // The trace-level roll-up is aggregated on a separate path from the
        // span write, so poll rather than read once.
        await expect
          .poll(
            async () => (await backendClient.getTrace(seed.traceId))?.totalEstimatedCost ?? null,
            {
              message: `trace ${seed.traceId} should roll up its single span's cost`,
              timeout: 30_000,
              intervals: [500, 1000, 2000],
            },
          )
          .not.toBeNull();

        const trace = await backendClient.getTrace(seed.traceId);
        expect(trace!.totalEstimatedCost!, 'trace roll-up equals its only span').toBeCloseTo(
          expected,
          8,
        );
      });
    }
  });

  test('A computed cost above the rounding floor renders in the traces table and the span detail panel', { tag: ['@cap:traces.span-model-cost-tokens'] }, async ({
    project,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    test.setTimeout(180_000);

    const expected = expectedCost(UI_MODEL.model, UI_PROMPT_TOKENS, UI_COMPLETION_TOKENS);
    // The UI trims trailing zeros, so $7.50 renders as "$7.5".
    const rendered = `$${String(Number(expected.toFixed(2)))}`;
    const renderedPattern = renderedCostPattern(rendered);

    const traceId = await test.step(
      `Seed a ${UI_MODEL.model} span at ${UI_PROMPT_TOKENS}/${UI_COMPLETION_TOKENS} tokens (expect ${rendered})`,
      async () => {
        const trace = await seedPricedTrace(sdkClient, {
          projectName: project.name,
          traceName: `${testNamespace}-ui-cost`,
          model: UI_MODEL.model,
          provider: UI_MODEL.provider,
          promptTokens: UI_PROMPT_TOKENS,
          completionTokens: UI_COMPLETION_TOKENS,
        });
        return trace.id;
      },
    );

    await test.step('The backend agrees on the figure before we look at the page', async () => {
      const spans = await backendClient.listSpansForTrace({ traceId, projectId: project.id });
      expect(spans[0].totalEstimatedCost!).toBeCloseTo(expected, 8);
    });

    const logs = new LogsPage(page);

    await test.step(`The traces table's Estimated cost cell renders ${rendered}`, async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      // The row's cost cell is fed by the trace roll-up, which can land a beat
      // after the row itself — assert with the locator's own retry.
      await expect(logs.explainCell(traceId, 'cost')).toHaveText(renderedPattern, {
        timeout: 30_000,
      });
    });

    await test.step(`The LLM span's detail panel renders ${rendered}`, async () => {
      const panel = await logs.openTraceById(traceId);
      await panel.waitForFullyLoaded();
      await panel.selectSpan(LLM_SPAN);
      await expect(panel.spanModelChip).toContainText(UI_MODEL.model);
      await expect(panel.panelText(renderedPattern).first()).toBeVisible();
    });
  });
});
