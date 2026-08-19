import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { SpanRowRef } from '@e2e/core/backend';

/**
 * OTel instrumentations name a provider with the semantic-convention vocabulary
 * (`vertex_ai`, `aws.bedrock`, `x_ai`), which spells several providers
 * differently from Opik's own price-table keys. A value that reaches the cost
 * service unmapped matches no price row, so the span silently costs nothing —
 * and a raw `vertex_ai` at $0 looks like a perfectly healthy row in the product.
 *
 * The first test asserts the mapping at the API, over the whole alias table at
 * once. The second writes through the OTLP endpoint and reads back through the
 * UI, which is where a user would meet the same fact.
 */
test.describe('OTel provider aliases', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  /**
   * Narrow a span's cost, failing when it is absent. A missing cost is the
   * regression under test, so it must not be defaulted to 0 and compared away.
   */
  const costOf = (row: SpanRowRef): number => {
    expect(row.totalEstimatedCost, `span "${row.name}" was stored with no cost`).not.toBeNull();
    return Number(row.totalEstimatedCost);
  };

  test(
    'Every gen_ai.system alias resolves to its canonical Opik provider and prices',
    { tag: ['@cap:traces.span-model-cost-tokens'] },
    async ({ otelAliasSpans }) => {
      const { cases, spans, total, sameModelPair } = otelAliasSpans;

      const rowFor = (name: string): SpanRowRef => {
        const row = spans.find((s) => s.name === name);
        if (!row) throw new Error(`Seeded span "${name}" is missing from the ingested batch`);
        return row;
      };

      await test.step('The batch landed as exactly the seeded spans', async () => {
        // Asserted against the server's own total as well as the rows read back:
        // an extra row would mean the project carries spans this test did not
        // seed, and every assertion below would be about the wrong set.
        expect(total).toBe(cases.length);
        expect(spans).toHaveLength(cases.length);
      });

      await test.step('Each semconv provider value resolves to its canonical name', async () => {
        // One assertion over the whole table rather than a loop of lookups: a
        // failure then names every alias that regressed, not just the first.
        const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name);
        expect(
          spans.map((s) => ({ name: s.name, provider: s.provider })).sort(byName),
        ).toEqual(
          cases.map((c) => ({ name: c.name, provider: c.expectedProvider })).sort(byName),
        );
      });

      await test.step('Each resolved provider matched a price row', async () => {
        // Deliberately not an exact dollar amount: model_prices_and_context_window.json
        // is synced from LiteLLM on a schedule, so a hardcoded figure rots. The
        // failure this guards is a mapping miss, which stores no cost at all.
        for (const row of spans) {
          expect(costOf(row), `span "${row.name}" priced`).toBeGreaterThan(0);
        }
      });

      await test.step('The same model prices differently per resolved Google backend', async () => {
        const dearer = rowFor(sameModelPair.dearer);
        const cheaper = rowFor(sameModelPair.cheaper);

        // Both sides must really be the same model, or the inequality below
        // would just be comparing two different price rows.
        expect(dearer.model).toBe(sameModelPair.model);
        expect(cheaper.model).toBe(sameModelPair.model);

        // Vertex AI is dearer than the Gemini Developer API for this model, so a
        // strict inequality proves the resolved provider reached the price table
        // rather than only being stamped on the span.
        expect(costOf(dearer)).toBeGreaterThan(costOf(cheaper));
      });
    },
  );

  test(
    'Logs shows the resolved provider and its priced cost for OTLP-ingested spans',
    { tag: ['@cap:traces.span-model-cost-tokens', '@cap:traces.toggle-spans-view'] },
    async ({ otelAliasSpans, project, page }) => {
      const logs = new LogsPage(page);
      const { spans, sameModelPair } = otelAliasSpans;

      await test.step('Open Logs and switch to the Spans view', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await logs.switchToSpans();
        await expect(logs.spanRows).toHaveCount(spans.length);
      });

      await test.step('Every span renders the cost the API priced it at', async () => {
        for (const row of spans) {
          const rendered = await logs.readSpanCost(row.id);
          const exact = costOf(row);
          // The cell floors to two decimals, so it sits at or just below the
          // stored value — never above it, and never a different amount.
          expect(rendered, `span "${row.name}" rendered cost`).toBeLessThanOrEqual(exact);
          expect(exact - rendered, `span "${row.name}" rendered cost`).toBeLessThan(0.01);
        }
      });

      await test.step('The Total cost card rolls up the same per-span costs', async () => {
        const sum = spans.reduce((acc, row) => acc + costOf(row), 0);
        const rendered = await logs.readTotalCost();
        expect(rendered).toBeLessThanOrEqual(sum);
        expect(sum - rendered).toBeLessThan(0.01);
      });

      await test.step('The span panel header names the resolved provider, not the sent one', async () => {
        const vertex = spans.find((s) => s.name === sameModelPair.dearer);
        if (!vertex) throw new Error(`Seeded span "${sameModelPair.dearer}" is missing`);
        const expected = otelAliasSpans.cases.find((c) => c.name === sameModelPair.dearer);
        if (!expected) throw new Error(`No seeded case named "${sameModelPair.dearer}"`);

        const panel = await logs.openSpanById(vertex.id, vertex.traceId);
        await panel.waitForFullyLoaded();
        // Asserted against the canonical name the mapping owes us, and as exact
        // text: the semconv value sent for this span is `vertex_ai`, which
        // `google_vertexai` contains — so a substring match would go green on a
        // provider that was never mapped at all.
        await expect(panel.spanModelChip).toHaveText(
          `${expected.expectedProvider} ${expected.model}`,
        );
      });
    },
  );
});
