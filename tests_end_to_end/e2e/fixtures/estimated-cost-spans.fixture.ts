import { test as baseTest } from './alert.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface EstimatedCostSpanSeed {
  /** Also the span's name — see the fixture note on why they are the same. */
  name: string;
  model: string;
  provider: string;
}

export interface EstimatedCostSpansRef {
  traceId: string;
  traceName: string;
  /** Identical on every seeded span, so cost differences can only come from the model name. */
  usage: { prompt_tokens: number; completion_tokens: number; total_tokens: number };
  /**
   * A model the price table lists, and the same model written with a compact
   * `YYYYMMDD` date suffix. The two must price the same.
   */
  anthropic: { base: EstimatedCostSpanSeed; compactDated: EstimatedCostSpanSeed };
  /**
   * The same pairing for a second vendor — `control` and `compactDated` must
   * price the same — plus three 8-digit suffixes that are *not* dates and must
   * therefore leave the span unpriced rather than collapsing onto `control`.
   */
  openai: {
    control: EstimatedCostSpanSeed;
    compactDated: EstimatedCostSpanSeed;
    invalidSuffixes: EstimatedCostSpanSeed[];
  };
  /** Every seeded span, in the order the fixture wrote them. */
  all: EstimatedCostSpanSeed[];
}

export interface EstimatedCostSpansFixtures {
  estimatedCostSpans: EstimatedCostSpansRef;
}

/**
 * 100k in and 100k out: large enough that every per-1M-token price in the table
 * lands above `formatCost`'s $0.01 floor, so the UI renders a real amount
 * (`$0.6`) instead of the `<$0.01` bucket that cannot be compared between two
 * spans.
 */
const USAGE = { prompt_tokens: 100_000, completion_tokens: 100_000, total_tokens: 200_000 };

/**
 * Each span is named after the model it carries.
 *
 * Deliberate, not shorthand: the trace panel keys its tree nodes on the span
 * name (`data-testid="trace-tree-node-<name>"`) and the spans table keys its
 * rows on the span id, so naming a span for its model is the only way a UI
 * assertion can say "the compact-dated model's node" without counting rows. The
 * names are unique within the trace, which is what the tree-node locator needs;
 * they are not read outside it.
 */
const seed = (model: string, provider: string): EstimatedCostSpanSeed => ({
  name: model,
  model,
  provider,
});

const ANTHROPIC_BASE = seed('claude-haiku-4-5', 'anthropic');
const ANTHROPIC_COMPACT_DATED = seed('claude-4.5-haiku-20251001', 'anthropic');
const OPENAI_CONTROL = seed('gpt-5.2', 'openai');
const OPENAI_COMPACT_DATED = seed('gpt-5.2-20251217', 'openai');
/**
 * Three 8-digit suffixes no calendar can produce: a build number, month 13 and
 * day 32. Each one is a way the date-stripping fallback could be loosened into
 * billing an arbitrary suffix at `gpt-5.2`'s rate.
 */
const OPENAI_INVALID_SUFFIXES = [
  seed('gpt-5.2-12345678', 'openai'),
  seed('gpt-5.2-20251317', 'openai'),
  seed('gpt-5.2-20251232', 'openai'),
];

const ALL_SEEDS: EstimatedCostSpanSeed[] = [
  ANTHROPIC_BASE,
  ANTHROPIC_COMPACT_DATED,
  OPENAI_CONTROL,
  OPENAI_COMPACT_DATED,
  ...OPENAI_INVALID_SUFFIXES,
];

/**
 * One trace carrying seven LLM spans that differ *only* in their model name.
 *
 * No `total_cost` is seeded on any of them, which is the whole point: with a
 * client-supplied cost present the backend stores it verbatim and never reaches
 * the price table, so the server-side estimate is only observable on spans that
 * arrive without one.
 *
 * Nothing about the cost is asserted here — that is what the spec is for. What
 * the fixture does verify before handing over is that the model, the provider
 * and the usage all landed on every span, because a span that arrived without a
 * model would also come back unpriced, and the spec's negative assertions would
 * then pass for the wrong reason.
 *
 * Teardown deletes the trace explicitly. Deleting the project does not cascade
 * to its traces, and the run-prefix sweep in `global-teardown.ts` does not know
 * about spans.
 */
export const test = baseTest.extend<EstimatedCostSpansFixtures>({
  estimatedCostSpans: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const traceName = `${testNamespace}-cost-trace`;

    const created = await sdkClient.python.createNestedTrace({
      project_name: project.name,
      name: traceName,
      input: { question: 'seeded estimated cost' },
      output: { answer: 'seeded estimated cost' },
      spans: ALL_SEEDS.map((s) => ({
        name: s.name,
        type: 'llm' as const,
        model: s.model,
        provider: s.provider,
        usage: USAGE,
        // total_cost deliberately omitted — see the fixture note above.
      })),
    });

    if (created.span_count !== ALL_SEEDS.length) {
      throw new Error(
        `[estimatedCostSpans fixture] expected ${ALL_SEEDS.length} spans, bridge reported ${created.span_count}`,
      );
    }

    const readBack = await backendClient.listSpanCosts({
      projectId: project.id,
      traceId: created.id,
    });
    for (const s of ALL_SEEDS) {
      const found = readBack.find((r) => r.name === s.name);
      if (!found) {
        throw new Error(`[estimatedCostSpans fixture] span "${s.name}" is not readable back`);
      }
      if (found.model !== s.model || found.provider !== s.provider) {
        throw new Error(
          `[estimatedCostSpans fixture] span "${s.name}" landed as ` +
            `model=${found.model} provider=${found.provider}, expected ` +
            `model=${s.model} provider=${s.provider}`,
        );
      }
      for (const [key, expected] of Object.entries(USAGE)) {
        if (found.usage?.[key] !== expected) {
          throw new Error(
            `[estimatedCostSpans fixture] span "${s.name}" landed with ${key}=` +
              `${found.usage?.[key]}, expected ${expected}`,
          );
        }
      }
    }

    const ref: EstimatedCostSpansRef = {
      traceId: created.id,
      traceName,
      usage: USAGE,
      anthropic: { base: ANTHROPIC_BASE, compactDated: ANTHROPIC_COMPACT_DATED },
      openai: {
        control: OPENAI_CONTROL,
        compactDated: OPENAI_COMPACT_DATED,
        invalidSuffixes: OPENAI_INVALID_SUFFIXES,
      },
      all: ALL_SEEDS,
    };

    await testInfo.attach('opik.estimatedCostSpans', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces([created.id]);
      } catch (err) {
        console.warn(
          `[estimatedCostSpans fixture] delete warning for trace ${created.id}:`,
          err,
        );
      }
    }
  },
});

export { expect } from './alert.fixture';
