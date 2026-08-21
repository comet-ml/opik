import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * OTel-ingested spans report their provider with the semantic-convention
 * vocabulary (`vertex_ai`, `gcp.vertex_ai`, ...), which spells several
 * providers differently from Opik's price-table keys. A value that reaches
 * `CostService` unmapped matches no pricing row and the span silently costs
 * nothing — OPIK-7717.
 *
 * Nothing else in this suite ingests OTLP at all: the fixtures seed through the
 * SDK, which writes the provider verbatim and never reaches
 * `OpenTelemetryMapper`. These specs drive the collector endpoint directly and
 * read the result back both through the spans API and on Logs > Spans.
 *
 * The unmapped `azure-control` case is what makes them able to fail. It is a
 * provider value the mapping deliberately does not alias, so it reproduces the
 * pre-fix symptom — no cost — alongside the fixed rows in the same build. Every
 * case is ingested with identical token counts, so a cost difference between
 * two rows can only come from the provider that was resolved.
 *
 * Costs are compared against each other, never against a literal: the price
 * table is data the product updates, and pinning `0.0035` here would turn a
 * routine price change into a test failure. What must hold is that Claude on
 * Vertex is priced from the Claude rows, which the direct-`anthropic` control
 * states exactly.
 */
test.describe(
  'OTel provider aliases — canonical provider and cost',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'OTLP semconv provider values resolve to canonical Opik providers and price the span',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelProviderSpans, project, backendClient }) => {
        const { all, byKey, inputTokens, outputTokens } = otelProviderSpans;

        const spans = await test.step('Read the ingested spans back', async () => {
          const page = await backendClient.listSpans({ projectId: project.id });
          // The seeded batch is the project's entire span population, so the
          // totals are asserted too: a mapping that duplicated or dropped a
          // span would still let a per-case lookup succeed.
          expect(page.total).toBe(all.length);
          expect(page.spans).toHaveLength(all.length);
          return new Map(page.spans.map((span) => [span.name, span]));
        });

        const spanFor = (key: string) => {
          const seeded = byKey(key);
          const span = spans.get(seeded.name);
          expect(span, `span "${seeded.name}" is missing from the spans response`).toBeDefined();
          return span!;
        };

        await test.step('Each case resolves to its canonical provider, model unchanged', async () => {
          for (const seeded of all) {
            const span = spanFor(seeded.key);
            expect(span.provider, `provider for ${seeded.key}`).toBe(seeded.expectedProvider);
            expect(span.model, `model for ${seeded.key}`).toBe(seeded.model);
            expect(span.type, `type for ${seeded.key}`).toBe(seeded.expectedType);
          }
        });

        await test.step('Mapped providers are priced; the unaliased control is not', async () => {
          for (const seeded of all.filter((s) => s.expectPriced)) {
            const cost = spanFor(seeded.key).totalEstimatedCost;
            expect(cost, `cost for ${seeded.key}`).not.toBeNull();
            expect(cost!, `cost for ${seeded.key}`).toBeGreaterThan(0);
          }
          // `azure.ai.inference` is excluded from the alias map on purpose (it
          // fronts more than one Opik provider), so it must still cost nothing.
          expect(spanFor('azure-control').totalEstimatedCost).toBeNull();
        });

        await test.step('Claude on Vertex is priced from the Claude rows, not the Gemini ones', async () => {
          const vertexClaude = spanFor('vertex-claude').totalEstimatedCost;
          const anthropicDirect = spanFor('anthropic-direct').totalEstimatedCost;
          const vertexGemini = spanFor('vertex-gemini').totalEstimatedCost;
          expect(vertexClaude).not.toBeNull();
          expect(anthropicDirect).not.toBeNull();
          expect(vertexGemini).not.toBeNull();

          // Same model, same token counts, two providers that both hold Claude
          // rows: the figures must agree. Left under `google_vertexai` the
          // Vertex row would match no row at all and cost nothing.
          expect(vertexClaude).toBe(anthropicDirect);
          // ...and must not be the Gemini figure, which is what a resolver that
          // stopped at `google_vertexai` would have produced had Gemini and
          // Claude shared a price.
          expect(vertexClaude).not.toBe(vertexGemini);
        });

        await test.step('Token usage survives the OTel attribute mapping', async () => {
          const span = spanFor('vertex-claude');
          expect(span.usage).toEqual(
            expect.objectContaining({
              prompt_tokens: inputTokens,
              completion_tokens: outputTokens,
              total_tokens: inputTokens + outputTokens,
            }),
          );
        });
      },
    );

    test(
      'Logs > Spans prices the aliased rows and leaves the unmapped control blank',
      { tag: ['@cap:traces.span-model-cost-tokens', '@cap:traces.toggle-spans-view'] },
      async ({ otelProviderSpans, project, page }) => {
        const logs = new LogsPage(page);
        const { all } = otelProviderSpans;

        await test.step('Open Logs and switch to the Spans view', async () => {
          await logs.goto(project.id);
          await logs.waitForReady();
          await logs.switchToSpans();
          await expect(logs.spansTab).toHaveAttribute('aria-checked', 'true');
        });

        await test.step('Every seeded span is listed', async () => {
          for (const seeded of all) {
            await logs.waitForSpanRow(seeded.id);
          }
          await expect(logs.spanRows).toHaveCount(all.length);
        });

        await test.step('Estimated cost renders for priced rows and "-" for the rest', async () => {
          for (const seeded of all) {
            // Anything under a cent renders as "<$0.01", so the table cannot
            // separate the Gemini figure from the Claude one — the exact
            // amounts are the API test's job. What it *can* separate, and what
            // OPIK-7717 was reported as, is a cost from no cost at all.
            const expected = seeded.expectPriced ? '<$0.01' : '-';
            await expect(
              logs.spanCell(seeded.id, 'total_estimated_cost'),
              `cost cell for ${seeded.key}`,
            ).toHaveText(expected);
          }
        });
      },
    );

    test(
      'The span detail panel names the canonical provider for a Claude-on-Vertex span',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelProviderSpans, project, page }) => {
        const logs = new LogsPage(page);
        const vertexClaude = otelProviderSpans.byKey('vertex-claude');

        const panel = await test.step('Open the Claude-on-Vertex span', async () => {
          await logs.gotoSpans(project.id);
          const panel = await logs.openSpanById(vertexClaude.traceId, vertexClaude.id);
          await panel.waitForFullyLoaded();
          return panel;
        });

        await test.step('The header carries the resolved provider, not the wire value', async () => {
          // The Spans table has no Provider column, so the detail header is
          // where a user actually reads which provider a span was grouped
          // under. `vertex_ai` — the value that went over the wire — must not
          // be what is shown.
          await expect(panel.spanModelChip).toHaveText(
            `${vertexClaude.expectedProvider} ${vertexClaude.model}`,
          );
        });
      },
    );
  },
);

/**
 * The current semconv defines `gen_ai.provider.name` on `execute_tool` and
 * `invoke_agent` spans as well as inference spans, and
 * `enrichSpanWithAttributes` applies a rule's span type unconditionally as it
 * walks the attributes. So the attribute must be read for its provider without
 * carrying an `llm` type along with it — otherwise every tool span in a
 * fully-migrated workspace is silently reclassified as an LLM call, inflating
 * LLM-call counts and cost attribution on Logs and the dashboards.
 */
test.describe(
  'OTel provider aliases — span typing',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'An execute_tool span carrying gen_ai.provider.name is not retyped as an LLM call',
      { tag: ['@cap:traces.span-model-cost-tokens'] },
      async ({ otelProviderSpans, project, backendClient, page }) => {
        const logs = new LogsPage(page);
        const executeTool = otelProviderSpans.byKey('execute-tool');

        await test.step('The API reports a general span with no model, usage or cost', async () => {
          const { spans } = await backendClient.listSpans({ projectId: project.id });
          const span = spans.find((s) => s.name === executeTool.name);
          expect(span, `span "${executeTool.name}" is missing from the spans response`).toBeDefined();

          expect(span!.type).toBe('general');
          expect(span!.model).toBeNull();
          expect(span!.usage).toBeNull();
          expect(span!.totalEstimatedCost).toBeNull();
          // The new attribute was still read — this is what separates "the rule
          // carries no span type" from "the rule was never applied".
          expect(span!.provider).toBe('openai');
        });

        await test.step('Logs > Spans renders it as General with no cost', async () => {
          await logs.gotoSpans(project.id);
          await logs.waitForSpanRow(executeTool.id);

          await expect(logs.spanCell(executeTool.id, 'type')).toHaveText('General');
          await expect(logs.spanCell(executeTool.id, 'total_estimated_cost')).toHaveText('-');
        });
      },
    );
  },
);
