import { test as baseTest } from './aged-experiment.fixture';
import type { OtelSpanSeed, SpanRowRef } from '../core/backend';

/** One OTLP span the mapping has to classify, plus the answer it must give. */
export interface OtelProviderCase {
  /** Span name — unique inside the seeded project, and how tests find the row. */
  name: string;
  /** Attributes sent alongside the model and token usage every case carries. */
  attributes: Record<string, string | number>;
  /** The canonical Opik provider the span must be stored with. */
  expectedProvider: string;
  /** `gen_ai.request.model` sent for this case. */
  model: string;
}

export interface OtelSpansRef {
  cases: OtelProviderCase[];
  /** The ingested spans as `GET /v1/private/spans` reports them. */
  spans: SpanRowRef[];
  /** The server's own total for the project, so a leaked extra row fails. */
  total: number | null;
}

export interface OtelAliasSpansRef extends OtelSpansRef {
  /**
   * The two cases that send the *same* model under a different `gen_ai.system`.
   * Vertex AI and the Gemini Developer API price `gemini-2.0-flash-001`
   * differently, so a strict inequality between these two is what separates
   * "the provider string was rewritten" from "the rewritten provider reached
   * the price table" — a mapping that stamped the right name but missed pricing
   * would leave both at the same number.
   */
  sameModelPair: { model: string; dearer: string; cheaper: string };
}

export interface OtelSpansFixtures {
  otelAliasSpans: OtelAliasSpansRef;
  otelProviderPrecedenceSpans: OtelSpansRef;
}

/**
 * Ten million input tokens, no output tokens.
 *
 * Deliberately large: at realistic token counts every one of these spans prices
 * under a cent, and the UI renders that as "<$0.01" for all of them alike — so a
 * screen assertion would pass with the entire price table wrong. At this volume
 * each provider's cost renders as a distinct dollar amount.
 */
const ALIAS_USAGE: Record<string, number> = {
  'gen_ai.usage.input_tokens': 10_000_000,
  'gen_ai.usage.output_tokens': 0,
};

/** Modest usage — the precedence cases assert on the provider, never on cost. */
const PRECEDENCE_USAGE: Record<string, number> = {
  'gen_ai.usage.input_tokens': 1_000,
  'gen_ai.usage.output_tokens': 500,
};

const GEMINI_MODEL = 'gemini-2.0-flash-001';
/** Priced under both `openai` and `azure`, so it works as the shared control. */
const OPENAI_MODEL = 'gpt-4o-mini';

/**
 * Every 1:1 rename in the OTel GenAI provider vocabulary, plus two providers
 * whose semconv spelling already matches Opik's as controls. A model is picked
 * per provider that has a real price row, so "resolved" and "priced" are both
 * observable — an alias that resolved but matched no pricing would still be a
 * silent $0 in the product.
 */
const ALIAS_CASES: Array<{ system: string; expectedProvider: string; model: string }> = [
  { system: 'vertex_ai', expectedProvider: 'google_vertexai', model: GEMINI_MODEL },
  { system: 'gcp.vertex_ai', expectedProvider: 'google_vertexai', model: GEMINI_MODEL },
  { system: 'gcp.gemini', expectedProvider: 'google_ai', model: GEMINI_MODEL },
  { system: 'x_ai', expectedProvider: 'xai', model: 'grok-2' },
  {
    system: 'aws.bedrock',
    expectedProvider: 'bedrock',
    model: 'anthropic.claude-3-haiku-20240307-v1:0',
  },
  { system: 'mistral_ai', expectedProvider: 'mistral', model: 'mistral-large-latest' },
  { system: 'az.ai.openai', expectedProvider: 'azure', model: OPENAI_MODEL },
  { system: 'azure.ai.openai', expectedProvider: 'azure', model: OPENAI_MODEL },
  // Controls: already canonical, and must pass through untouched.
  { system: 'openai', expectedProvider: 'openai', model: OPENAI_MODEL },
  { system: 'anthropic', expectedProvider: 'anthropic', model: 'claude-3-haiku-20240307' },
];

/** Span name for an alias case — the semconv value, made safe for a name. */
const aliasCaseName = (system: string): string => `otel-${system.replace(/[._]/g, '-')}`;

/**
 * Which of `gen_ai.system` and `gen_ai.provider.name` decides the provider.
 *
 * An instrumentation mid-migration emits both, and their vocabularies differ
 * (`xai` vs `x_ai`), so without a pinned precedence the OTLP attribute order
 * would decide the provider — and therefore the price. The blank and non-string
 * cases are the other half: a `gen_ai.provider.name` that carries nothing must
 * not blank out a provider `gen_ai.system` already resolved.
 */
const PRECEDENCE_CASES: Array<{
  name: string;
  attributes: Record<string, string | number>;
  expectedProvider: string;
}> = [
  // `gen_ai.provider.name` alone resolves — and is alias-mapped, not stored raw.
  {
    name: 'otel-name-only-x-ai',
    attributes: { 'gen_ai.provider.name': 'x_ai' },
    expectedProvider: 'xai',
  },
  {
    name: 'otel-name-only-vertex-ai',
    attributes: { 'gen_ai.provider.name': 'vertex_ai' },
    expectedProvider: 'google_vertexai',
  },
  {
    name: 'otel-name-only-gcp-gemini',
    attributes: { 'gen_ai.provider.name': 'gcp.gemini' },
    expectedProvider: 'google_ai',
  },
  // Both present and disagreeing, tried both ways round so the answer cannot be
  // an artefact of which attribute the payload happens to list first.
  {
    name: 'otel-system-wins-openai',
    attributes: { 'gen_ai.system': 'openai', 'gen_ai.provider.name': 'x_ai' },
    expectedProvider: 'openai',
  },
  {
    name: 'otel-system-wins-xai',
    attributes: { 'gen_ai.system': 'x_ai', 'gen_ai.provider.name': 'openai' },
    expectedProvider: 'xai',
  },
  // A blank or non-string `gen_ai.provider.name` must not blank the provider.
  // The number here is sent as an OTLP intValue, not a string.
  {
    name: 'otel-empty-provider-name',
    attributes: { 'gen_ai.system': 'openai', 'gen_ai.provider.name': '' },
    expectedProvider: 'openai',
  },
  {
    name: 'otel-nonstring-provider-name',
    attributes: { 'gen_ai.system': 'openai', 'gen_ai.provider.name': 123 },
    expectedProvider: 'openai',
  },
  // The mirror image: a blank `gen_ai.system` falls back rather than winning.
  {
    name: 'otel-empty-system',
    attributes: { 'gen_ai.system': '', 'gen_ai.provider.name': 'x_ai' },
    expectedProvider: 'xai',
  },
  // Control: `gen_ai.system` alone, the shape every existing instrumentation sends.
  {
    name: 'otel-system-only',
    attributes: { 'gen_ai.system': 'anthropic' },
    expectedProvider: 'anthropic',
  },
];

function toSeeds(cases: OtelProviderCase[], usage: Record<string, number>): OtelSpanSeed[] {
  return cases.map((c) => ({
    name: c.name,
    attributes: { ...c.attributes, 'gen_ai.request.model': c.model, ...usage },
  }));
}

export const test = baseTest.extend<OtelSpansFixtures>({
  otelAliasSpans: async ({ backendClient, project }, use, testInfo) => {
    const cases: OtelProviderCase[] = ALIAS_CASES.map((c) => ({
      name: aliasCaseName(c.system),
      attributes: { 'gen_ai.system': c.system },
      expectedProvider: c.expectedProvider,
      model: c.model,
    }));

    await backendClient.ingestOtelSpans(project.name, toSeeds(cases, ALIAS_USAGE));
    // Ingest is asynchronous and answers 200 before the spans are queryable, so
    // gate on the whole batch being present: a test that opened the browser on a
    // half-ingested project would assert over whichever rows happened to land.
    const page = await backendClient.waitForOtelSpans(project.id, cases.length);

    await testInfo.attach('opik.otelAliasSpans', {
      body: JSON.stringify({ cases, spans: page.content, total: page.total }, null, 2),
      contentType: 'application/json',
    });

    await use({
      cases,
      spans: page.content,
      total: page.total,
      sameModelPair: {
        model: GEMINI_MODEL,
        dearer: aliasCaseName('vertex_ai'),
        cheaper: aliasCaseName('gcp.gemini'),
      },
    });
    // No explicit teardown — the project fixture's deleteProject cascades.
  },

  otelProviderPrecedenceSpans: async ({ backendClient, project }, use, testInfo) => {
    const cases: OtelProviderCase[] = PRECEDENCE_CASES.map((c) => ({
      ...c,
      model: OPENAI_MODEL,
    }));

    await backendClient.ingestOtelSpans(project.name, toSeeds(cases, PRECEDENCE_USAGE));
    const page = await backendClient.waitForOtelSpans(project.id, cases.length);

    await testInfo.attach('opik.otelProviderPrecedenceSpans', {
      body: JSON.stringify({ cases, spans: page.content, total: page.total }, null, 2),
      contentType: 'application/json',
    });

    await use({ cases, spans: page.content, total: page.total });
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './aged-experiment.fixture';
