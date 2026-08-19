import { test, expect } from '@e2e/fixtures';
import type { OtelProviderCase } from '@e2e/fixtures';
import type { SpanRowRef } from '@e2e/core/backend';

/**
 * Two OTel attributes carry the provider: the deprecated `gen_ai.system` and its
 * replacement `gen_ai.provider.name`. An instrumentation mid-migration emits
 * both, and their vocabularies differ (`xai` vs `x_ai`), so which one wins
 * decides the provider — and therefore which price row the span is costed
 * against. Nothing about that is visible in the product: the loser's value would
 * simply be the one stored.
 *
 * API-level throughout. There is no rendering of its own to check, and driving a
 * browser to observe a stored string second-hand would only add flake.
 */
test.describe('OTel provider precedence', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  const byName = (a: { name: string }, b: { name: string }) => a.name.localeCompare(b.name);

  /**
   * Assert the resolved providers for a subset of the seeded cases, as a single
   * whole-set comparison so a failure names every case that regressed.
   *
   * The subset is checked for completeness first: a `filter` that matched
   * nothing would otherwise compare two empty lists and pass having asserted
   * nothing at all.
   */
  const expectProviders = (
    spans: SpanRowRef[],
    cases: OtelProviderCase[],
    names: string[],
  ): void => {
    expect(cases.map((c) => c.name)).toEqual(expect.arrayContaining(names));
    const selected = cases.filter((c) => names.includes(c.name));
    expect(selected).toHaveLength(names.length);

    const actual = spans
      .filter((s) => names.includes(s.name))
      .map((s) => ({ name: s.name, provider: s.provider }))
      .sort(byName);
    expect(actual).toEqual(
      selected.map((c) => ({ name: c.name, provider: c.expectedProvider })).sort(byName),
    );
  };

  test(
    'gen_ai.provider.name resolves the provider, and is alias-mapped, when gen_ai.system is absent',
    { tag: ['@cap:traces.span-model-cost-tokens'] },
    async ({ otelProviderPrecedenceSpans }) => {
      const { cases, spans, total } = otelProviderPrecedenceSpans;

      await test.step('The batch landed as exactly the seeded spans', async () => {
        expect(total).toBe(cases.length);
        expect(spans).toHaveLength(cases.length);
      });

      await test.step('The current semconv attribute alone resolves a canonical provider', async () => {
        // Not just "a provider was stored": each of these is a semconv spelling
        // that differs from Opik's, so storing the value verbatim would be the
        // failure. `google_vertexai` also proves the fallback runs *before* the
        // alias table rather than bypassing it.
        expectProviders(spans, cases, [
          'otel-name-only-x-ai',
          'otel-name-only-vertex-ai',
          'otel-name-only-gcp-gemini',
        ]);
      });
    },
  );

  test(
    'gen_ai.system stays authoritative, and a blank gen_ai.provider.name never blanks the provider',
    { tag: ['@cap:traces.span-model-cost-tokens'] },
    async ({ otelProviderPrecedenceSpans }) => {
      const { cases, spans, total } = otelProviderPrecedenceSpans;

      await test.step('The batch landed as exactly the seeded spans', async () => {
        expect(total).toBe(cases.length);
        expect(spans).toHaveLength(cases.length);
      });

      await test.step('gen_ai.system wins over a disagreeing gen_ai.provider.name', async () => {
        // Both directions, so the result cannot be an artefact of the order the
        // two attributes happen to appear in the OTLP payload.
        expectProviders(spans, cases, ['otel-system-wins-openai', 'otel-system-wins-xai']);
      });

      await test.step('An empty or non-string gen_ai.provider.name leaves the provider intact', async () => {
        expectProviders(spans, cases, [
          'otel-empty-provider-name',
          'otel-nonstring-provider-name',
        ]);
      });

      await test.step('A blank gen_ai.system falls back instead of winning', async () => {
        // The mirror image of the case above: "authoritative when present" has
        // to mean present, not merely declared.
        expectProviders(spans, cases, ['otel-empty-system', 'otel-system-only']);
      });
    },
  );
});
