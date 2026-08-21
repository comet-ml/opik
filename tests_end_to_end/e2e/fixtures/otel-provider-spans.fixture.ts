import { test as baseTest } from './evaluated-thread.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { OtelSpanSeed } from '../core/backend';

export interface OtelProviderSpanRef {
  /** Stable handle the spec addresses this case by. */
  key: string;
  /** OTel span name; also the name the Logs > Spans table renders. */
  name: string;
  /** Opik span id, read back after ingestion (OTLP writes return no body). */
  id: string;
  /** Opik trace id the span landed under, read back after ingestion. */
  traceId: string;
  /** Canonical Opik provider the mapper is expected to resolve for this case. */
  expectedProvider: string;
  /** Model as sent; Opik is expected to store it unchanged, or null when none was sent. */
  model: string | null;
  /** Opik span type the mapper is expected to assign. */
  expectedType: 'llm' | 'general';
  /** Whether Opik holds a price row for this (provider, model) pair. */
  expectPriced: boolean;
}

export interface OtelProviderSpansFixtures {
  otelProviderSpans: {
    all: OtelProviderSpanRef[];
    /** Look up a seeded case, throwing rather than returning undefined. */
    byKey(key: string): OtelProviderSpanRef;
    inputTokens: number;
    outputTokens: number;
  };
}

const INPUT_TOKENS = 1000;
const OUTPUT_TOKENS = 500;

const GEMINI_MODEL = 'gemini-2.5-flash';
const CLAUDE_MODEL = 'claude-haiku-4-5';

/**
 * Every case carries the same token counts, so any cost difference between two
 * rows is attributable to the provider the mapper resolved and nothing else.
 */
const USAGE: Record<string, number> = {
  'gen_ai.usage.input_tokens': INPUT_TOKENS,
  'gen_ai.usage.output_tokens': OUTPUT_TOKENS,
};

interface Seed {
  key: string;
  suffix: string;
  attributes: Record<string, string | number>;
  expectedProvider: string;
  model: string | null;
  expectedType: 'llm' | 'general';
  expectPriced: boolean;
}

/**
 * Six OTLP spans covering the OTel-provider mapping OPIK-7717 added, plus the
 * two controls that make the assertions able to fail.
 *
 * The mapped cases (`vertex-gemini`, `vertex-claude`, `provider-name-vertex`)
 * are the fix. `azure-control` is a provider value the change deliberately
 * leaves unaliased, so it still reaches the price table unmatched and costs
 * nothing — the pre-fix symptom, reproduced in the same build, which is what
 * pins the other rows to "a cost appeared" rather than "a cost exists".
 * `anthropic-direct` prices the same model under the plain `anthropic`
 * provider, so the Claude-on-Vertex row can be shown to be priced from the
 * Claude rows and not merely to be non-zero.
 *
 * `execute-tool` carries the new `gen_ai.provider.name` attribute on a
 * non-inference span and deliberately sends no model and no usage: it must be
 * read for its provider without being retyped as an LLM call.
 */
const SEEDS: Seed[] = [
  {
    key: 'vertex-gemini',
    suffix: 'vertex-gemini',
    attributes: {
      'gen_ai.system': 'vertex_ai',
      'gen_ai.request.model': GEMINI_MODEL,
      ...USAGE,
    },
    expectedProvider: 'google_vertexai',
    model: GEMINI_MODEL,
    expectedType: 'llm',
    expectPriced: true,
  },
  {
    key: 'vertex-claude',
    suffix: 'vertex-claude',
    attributes: {
      'gen_ai.system': 'vertex_ai',
      'gen_ai.request.model': CLAUDE_MODEL,
      ...USAGE,
    },
    expectedProvider: 'anthropic_vertexai',
    model: CLAUDE_MODEL,
    expectedType: 'llm',
    expectPriced: true,
  },
  {
    key: 'provider-name-vertex',
    suffix: 'provider-name-vertex',
    attributes: {
      'gen_ai.provider.name': 'gcp.vertex_ai',
      'gen_ai.request.model': GEMINI_MODEL,
      ...USAGE,
    },
    expectedProvider: 'google_vertexai',
    model: GEMINI_MODEL,
    expectedType: 'llm',
    expectPriced: true,
  },
  {
    key: 'azure-control',
    suffix: 'azure-control',
    attributes: {
      'gen_ai.system': 'azure.ai.inference',
      'gen_ai.request.model': CLAUDE_MODEL,
      ...USAGE,
    },
    expectedProvider: 'azure.ai.inference',
    model: CLAUDE_MODEL,
    expectedType: 'llm',
    expectPriced: false,
  },
  {
    key: 'anthropic-direct',
    suffix: 'anthropic-direct',
    attributes: {
      'gen_ai.system': 'anthropic',
      'gen_ai.request.model': CLAUDE_MODEL,
      ...USAGE,
    },
    expectedProvider: 'anthropic',
    model: CLAUDE_MODEL,
    expectedType: 'llm',
    expectPriced: true,
  },
  {
    key: 'execute-tool',
    suffix: 'execute-tool',
    attributes: {
      'gen_ai.provider.name': 'openai',
      'gen_ai.operation.name': 'execute_tool',
      'gen_ai.tool.name': 'lookup_order',
    },
    expectedProvider: 'openai',
    model: null,
    expectedType: 'general',
    expectPriced: false,
  },
];

/**
 * Ingests the batch above through OTLP and resolves each case to the Opik span
 * it produced.
 *
 * The ids have to be read back: an OTLP write answers 200 with no body, and the
 * OTel ids are not the Opik ones. Reading them back is also what proves the
 * batch landed at all — a UI assertion over a batch that silently failed to
 * ingest would report an empty table as a mapping failure, or worse, pass.
 *
 * Teardown deletes the traces explicitly. Deleting a project does not cascade
 * to its traces, and `global-teardown`'s run-prefix sweep only knows about
 * experiments, datasets and projects — so without this the spans outlive the
 * run.
 */
export const test = baseTest.extend<OtelProviderSpansFixtures>({
  otelProviderSpans: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const startTime = new Date();
    const named = SEEDS.map((seed) => ({
      seed,
      name: `${testNamespace}-otel-${seed.suffix}`,
    }));

    const payload: OtelSpanSeed[] = named.map(({ seed, name }) => ({
      name,
      attributes: seed.attributes,
    }));
    await backendClient.ingestOtelSpans({
      projectName: project.name,
      spans: payload,
      startTime,
    });

    // OTLP ingestion is asynchronous, so poll until the whole batch is
    // readable. Gating on the full count rather than on "any span" keeps a
    // partially-ingested batch from being tested as though it were complete.
    const expectedNames = new Set(named.map((n) => n.name));
    let byName = new Map<string, { id: string; traceId: string }>();
    const deadline = Date.now() + 60_000;
    for (;;) {
      const { spans } = await backendClient.listSpans({ projectId: project.id });
      byName = new Map(
        spans
          .filter((s) => expectedNames.has(s.name))
          .map((s) => [s.name, { id: s.id, traceId: s.traceId }]),
      );
      if (byName.size === named.length) break;
      if (Date.now() > deadline) {
        throw new Error(
          `[otelProviderSpans] only ${byName.size}/${named.length} OTLP spans became readable ` +
            `in project ${project.name} within 60s`,
        );
      }
      await new Promise((resolve) => setTimeout(resolve, 1_000));
    }

    const all: OtelProviderSpanRef[] = named.map(({ seed, name }) => {
      const resolved = byName.get(name);
      if (!resolved) {
        throw new Error(`[otelProviderSpans] span "${name}" missing after the batch was complete`);
      }
      return {
        key: seed.key,
        name,
        id: resolved.id,
        traceId: resolved.traceId,
        expectedProvider: seed.expectedProvider,
        model: seed.model,
        expectedType: seed.expectedType,
        expectPriced: seed.expectPriced,
      };
    });

    await testInfo.attach('opik.otelProviderSpans', {
      body: JSON.stringify(all, null, 2),
      contentType: 'application/json',
    });

    await use({
      all,
      byKey(key: string): OtelProviderSpanRef {
        const match = all.find((span) => span.key === key);
        if (!match) {
          throw new Error(`[otelProviderSpans] no seeded case "${key}"`);
        }
        return match;
      },
      inputTokens: INPUT_TOKENS,
      outputTokens: OUTPUT_TOKENS,
    });

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces([...new Set(all.map((span) => span.traceId))]);
      } catch (err) {
        console.warn('[otelProviderSpans fixture] trace delete warning:', err);
      }
    }
  },
});

export { expect } from './evaluated-thread.fixture';
