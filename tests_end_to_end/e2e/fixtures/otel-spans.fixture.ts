import { randomBytes } from 'node:crypto';
import { test as baseTest } from './evaluated-thread.fixture';
import type { OtelSpanSeed } from '../core/backend';

export interface OtelSpanRef {
  /** The Opik span id the backend minted — also the Spans table's `data-row-id`. */
  id: string;
  /** The Opik trace id the span was linked to, needed to open its detail panel. */
  traceId: string;
  name: string;
  /** Model as the instrumentation reported it, and as span detail should render it. */
  model: string;
  /** Canonical Opik provider the alias chain is expected to resolve to. */
  expectedProvider: string;
  /** Cost the static price table gives for `TOKENS` on this provider/model pair. */
  expectedCost: number;
}

export interface OtelSpansFixtures {
  otelSpans: {
    /** Every seeded span, in seed order. */
    all: OtelSpanRef[];
    /** `gen_ai.system=vertex_ai` + a Claude model — expected `anthropic_vertexai`. */
    vertexClaude: OtelSpanRef;
    /** `gen_ai.system=vertex_ai` + a Gemini model — expected `google_vertexai`. */
    vertexGemini: OtelSpanRef;
    /** Semconv-current `gen_ai.provider.name` + a Gemini model — also `google_vertexai`. */
    gcpVertexGemini: OtelSpanRef;
  };
}

/**
 * Identical token counts on every span, so the only thing that can move the
 * cost is which price rows the resolved provider selected. Chosen round so the
 * expected costs below are exact rather than floating-point approximations.
 */
const TOKENS = { input: 1000, output: 500 };

const ANTHROPIC_VERTEX_AI = 'anthropic_vertexai';
const GOOGLE_VERTEX_AI = 'google_vertexai';

const CLAUDE_MODEL = 'claude-haiku-4-5';
const GEMINI_MODEL = 'gemini-2.5-flash-lite';

/**
 * Claude on Vertex is priced from LiteLLM's `vertex_ai-anthropic_models` rows
 * ($1e-6 in / $5e-6 out); Gemini on Vertex from `vertex_ai-language-models`
 * ($1e-7 in / $4e-7 out). The two differ by an order of magnitude on the same
 * token counts, which is what makes the Claude figure discriminating: a Claude
 * span left under `google_vertexai` prices at neither value — the model matches
 * no Gemini row, so it costs nothing at all.
 */
const CLAUDE_COST = 1000 * 1e-6 + 500 * 5e-6; // 0.0035
const GEMINI_COST = 1000 * 1e-7 + 500 * 4e-7; // 0.0003

type SeedSpec = {
  suffix: string;
  /** Attribute carrying the provider — the deprecated one or its replacement. */
  providerAttribute: 'gen_ai.system' | 'gen_ai.provider.name';
  providerValue: string;
  model: string;
  expectedProvider: string;
  expectedCost: number;
};

/**
 * Three LLM spans covering both provider attributes and both Vertex model
 * families, so each assertion in the specs narrows to a different subset:
 *
 *   provider `anthropic_vertexai` -> vertex-claude              (1 of 3)
 *   provider `google_vertexai`    -> vertex-gemini + gcp-gemini (2 of 3)
 *
 * `gcp-gemini` is the discriminator for the attribute half of this: it reports
 * the provider only through the semconv-current `gen_ai.provider.name`, so a
 * resolver chain that still read `gen_ai.system` alone would leave it with no
 * provider and drop it out of the `google_vertexai` set entirely.
 */
const SEEDS: SeedSpec[] = [
  {
    suffix: 'vertex-claude',
    providerAttribute: 'gen_ai.system',
    providerValue: 'vertex_ai',
    model: CLAUDE_MODEL,
    expectedProvider: ANTHROPIC_VERTEX_AI,
    expectedCost: CLAUDE_COST,
  },
  {
    suffix: 'vertex-gemini',
    providerAttribute: 'gen_ai.system',
    providerValue: 'vertex_ai',
    model: GEMINI_MODEL,
    expectedProvider: GOOGLE_VERTEX_AI,
    expectedCost: GEMINI_COST,
  },
  {
    suffix: 'gcp-vertex-gemini',
    providerAttribute: 'gen_ai.provider.name',
    providerValue: 'gcp.vertex_ai',
    model: GEMINI_MODEL,
    expectedProvider: GOOGLE_VERTEX_AI,
    expectedCost: GEMINI_COST,
  },
];

/** OTel wire ids: 16 bytes of hex for a trace, 8 for a span. */
const otelTraceId = () => randomBytes(16).toString('hex');
const otelSpanId = () => randomBytes(8).toString('hex');

/**
 * Three spans pushed through the OTLP/JSON ingestion endpoint rather than the
 * SDK, because the provider alias chain and the price lookup it feeds run only
 * on that path — an SDK-created span carries a provider the caller already
 * chose, so it can never fail the mapping this seeds for.
 *
 * Each span gets its own OTel trace id, so the backend mints one Opik trace per
 * span and the Spans tab holds exactly three rows for the project.
 */
export const test = baseTest.extend<OtelSpansFixtures>({
  otelSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const names = SEEDS.map((seed) => `${testNamespace}-otel-${seed.suffix}`);

    const payload: OtelSpanSeed[] = SEEDS.map((seed, i) => ({
      traceId: otelTraceId(),
      spanId: otelSpanId(),
      name: names[i],
      attributes: {
        [seed.providerAttribute]: seed.providerValue,
        'gen_ai.request.model': seed.model,
        'gen_ai.usage.input_tokens': TOKENS.input,
        'gen_ai.usage.output_tokens': TOKENS.output,
      },
    }));

    await backendClient.ingestOtelSpans({ projectName: project.name, spans: payload });

    // Ingestion is asynchronous — the POST returns before the spans are
    // queryable — so resolve the backend-minted ids by polling for the seeded
    // names. This also fails the fixture loudly if a span never lands, instead
    // of handing a spec a set it would silently assert over.
    const stored = await backendClient.waitForSpansByName(project.id, names);

    const all: OtelSpanRef[] = SEEDS.map((seed, i) => ({
      id: stored[i].id,
      traceId: stored[i].traceId,
      name: names[i],
      model: seed.model,
      expectedProvider: seed.expectedProvider,
      expectedCost: seed.expectedCost,
    }));

    await testInfo.attach('opik.otelSpans', {
      body: JSON.stringify(all, null, 2),
      contentType: 'application/json',
    });

    await use({
      all,
      vertexClaude: all[0],
      vertexGemini: all[1],
      gcpVertexGemini: all[2],
    });
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './evaluated-thread.fixture';
