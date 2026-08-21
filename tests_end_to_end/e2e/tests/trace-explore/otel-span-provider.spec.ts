import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * OTLP-ingested spans, the one path where Opik *derives* the provider instead
 * of being told it. Third-party instrumentation reports the provider in the
 * OTel semantic-convention vocabulary (`vertex_ai`, `gcp.vertex_ai`), which
 * spells several providers differently from Opik's own price-table keys; a
 * value that reaches the cost lookup unmapped matches no row and the span
 * silently costs nothing (OPIK-7717).
 *
 * The estate's other span coverage seeds through the SDK with a provider the
 * test itself chose, so none of it can fail this mapping. Both tests here write
 * through OTLP and read back through the API and the page, because the resolved
 * provider is persisted once and then read by the cost cell, the span-detail
 * chip and the Spans-tab filter alike.
 */
test.describe('OTel span provider resolution', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test(
    'An OTLP-ingested Claude-on-Vertex span resolves to anthropic_vertexai and is priced from the Anthropic rows',
    { tag: ['@cap:traces.span-model-cost-tokens'] },
    async ({ otelSpans, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { vertexClaude, vertexGemini, all } = otelSpans;

      await test.step('Verify the stored provider and cost of each Vertex span', async () => {
        const { total, spans } = await backendClient.listSpans({ projectId: project.id });

        // Assert over the whole project, not just the rows looked up below: a
        // seed that landed twice, or a span the mapper split in two, is exactly
        // the kind of wrongness a find()-and-compare would step over.
        expect(total).toBe(all.length);
        expect(spans).toHaveLength(all.length);

        for (const seeded of all) {
          const stored = spans.find((s) => s.id === seeded.id);
          expect(stored, `span ${seeded.name} is missing from the project`).toBeDefined();
          expect(stored!.provider).toBe(seeded.expectedProvider);
          expect(stored!.model).toBe(seeded.model);
          expect(stored!.type).toBe('llm');
          // Exact, not "greater than zero": the Claude figure is only
          // meaningful because it is the Anthropic-row price and not the Gemini
          // one, and both are non-zero.
          expect(stored!.totalEstimatedCost).toBe(seeded.expectedCost);
        }

        // Same token counts on both spans, so equal costs would mean the Claude
        // span was priced off the Gemini rows — the pre-fix grouping.
        expect(vertexClaude.expectedCost).not.toBe(vertexGemini.expectedCost);
      });

      await test.step('Open the Claude span from Logs > Spans and verify its detail', async () => {
        await logs.goto(project.id);
        const panel = await logs.openSpanById(vertexClaude.traceId, vertexClaude.id);
        await panel.waitForSpanSelected(vertexClaude.id);

        // The chip renders "<provider> <model>". Exact text, so a provider left
        // as the wire value `vertex_ai` fails here rather than passing on a
        // substring of the model.
        await expect(panel.spanModelChip).toHaveText(
          `${vertexClaude.expectedProvider} ${vertexClaude.model}`,
        );
        // $0.0035 rounds to "<$0.01" in the UI. An unpriced span renders "$0",
        // which this text will not match.
        await expect(panel.panelText('<$0.01').first()).toBeVisible();
      });
    },
  );

  test(
    'The Spans tab provider filter matches OTLP spans under their canonical provider name',
    { tag: ['@cap:traces.toggle-spans-view'] },
    async ({ otelSpans, project, backendClient, page }) => {
      const logs = new LogsPage(page);
      const { all, vertexClaude, vertexGemini, gcpVertexGemini } = otelSpans;
      const googleSpans = [vertexGemini, gcpVertexGemini];

      await test.step('Confirm via the API that the seeded spans really split by provider', async () => {
        // A UI filter asserted over a fixture that failed to produce two
        // distinct providers is a test that cannot fail, so prove the split
        // holds before opening the browser.
        const { spans } = await backendClient.listSpans({ projectId: project.id });
        const providers = new Map(spans.map((s) => [s.id, s.provider]));

        expect(providers.get(vertexClaude.id)).toBe('anthropic_vertexai');
        for (const span of googleSpans) {
          expect(providers.get(span.id)).toBe('google_vertexai');
        }
      });

      await test.step('Toggle Logs to the Spans view and confirm it lists the spans', async () => {
        await logs.goto(project.id);
        await logs.waitForReady();
        await logs.switchToSpans();
        await logs.waitForSpansReady(vertexClaude.id);

        await expect(logs.spanRows).toHaveCount(all.length);
        // Rows are keyed by span id here, not trace id — that is what separates
        // the Spans view from the Traces view it was toggled away from, which
        // holds the same number of rows.
        for (const span of all) {
          await expect(logs.spanRow(span.id)).toBeVisible();
        }
      });

      await test.step('Filter by provider "anthropic_vertexai"', async () => {
        // The provider chip isn't pinned to the bar by default, so pin it first.
        await logs.pinFilterChip('Provider');
        await logs.applyFilter('provider', 'anthropic_vertexai');

        await expect(logs.spanRows).toHaveCount(1);
        await expect(logs.spanRow(vertexClaude.id)).toBeVisible();
        // The Gemini spans are the discriminator: a filter that matched on
        // "any Vertex span" rather than the resolved provider would keep them.
        for (const span of googleSpans) {
          await expect(logs.spanRow(span.id)).toBeHidden();
        }
      });

      await test.step('Re-filter by provider "google_vertexai"', async () => {
        await logs.clearAllFilters();
        await logs.applyFilter('provider', 'google_vertexai');

        await expect(logs.spanRows).toHaveCount(googleSpans.length);
        for (const span of googleSpans) {
          await expect(logs.spanRow(span.id)).toBeVisible();
        }
        await expect(logs.spanRow(vertexClaude.id)).toBeHidden();
      });
    },
  );
});
