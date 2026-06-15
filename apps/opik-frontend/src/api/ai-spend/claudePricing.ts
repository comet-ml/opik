// Hardcoded $/1M-token rates for Claude Code models (source: litellm
// model_prices_and_context_window.json, anthropic provider, 2026-06-11).
// FE-side pricing by design: the BE passes raw cache-tier token sums
// through; dollars are presentation. Refresh this table when Anthropic
// ships new models or pricing.
export interface ClaudeRates {
  input: number;
  output: number;
  cacheRead: number;
  cacheWrite: number;
}

const RATES: Record<string, ClaudeRates> = {
  "claude-3-7-sonnet-20250219": {
    input: 3,
    output: 15,
    cacheRead: 0.3,
    cacheWrite: 3.75,
  },
  "claude-3-haiku-20240307": {
    input: 0.25,
    output: 1.25,
    cacheRead: 0.03,
    cacheWrite: 0.3,
  },
  "claude-3-opus-20240229": {
    input: 15,
    output: 75,
    cacheRead: 1.5,
    cacheWrite: 18.75,
  },
  "claude-4-opus-20250514": {
    input: 15,
    output: 75,
    cacheRead: 1.5,
    cacheWrite: 18.75,
  },
  "claude-4-sonnet-20250514": {
    input: 3,
    output: 15,
    cacheRead: 0.3,
    cacheWrite: 3.75,
  },
  "claude-fable-5": { input: 10, output: 50, cacheRead: 1, cacheWrite: 12.5 },
  "claude-haiku-4-5": { input: 1, output: 5, cacheRead: 0.1, cacheWrite: 1.25 },
  "claude-haiku-4-5-20251001": {
    input: 1,
    output: 5,
    cacheRead: 0.1,
    cacheWrite: 1.25,
  },
  "claude-opus-4-1": {
    input: 15,
    output: 75,
    cacheRead: 1.5,
    cacheWrite: 18.75,
  },
  "claude-opus-4-1-20250805": {
    input: 15,
    output: 75,
    cacheRead: 1.5,
    cacheWrite: 18.75,
  },
  "claude-opus-4-20250514": {
    input: 15,
    output: 75,
    cacheRead: 1.5,
    cacheWrite: 18.75,
  },
  "claude-opus-4-5": { input: 5, output: 25, cacheRead: 0.5, cacheWrite: 6.25 },
  "claude-opus-4-5-20251101": {
    input: 5,
    output: 25,
    cacheRead: 0.5,
    cacheWrite: 6.25,
  },
  "claude-opus-4-6": { input: 5, output: 25, cacheRead: 0.5, cacheWrite: 6.25 },
  "claude-opus-4-6-20260205": {
    input: 5,
    output: 25,
    cacheRead: 0.5,
    cacheWrite: 6.25,
  },
  "claude-opus-4-7": { input: 5, output: 25, cacheRead: 0.5, cacheWrite: 6.25 },
  "claude-opus-4-7-20260416": {
    input: 5,
    output: 25,
    cacheRead: 0.5,
    cacheWrite: 6.25,
  },
  "claude-opus-4-8": { input: 5, output: 25, cacheRead: 0.5, cacheWrite: 6.25 },
  "claude-sonnet-4-20250514": {
    input: 3,
    output: 15,
    cacheRead: 0.3,
    cacheWrite: 3.75,
  },
  "claude-sonnet-4-5": {
    input: 3,
    output: 15,
    cacheRead: 0.3,
    cacheWrite: 3.75,
  },
  "claude-sonnet-4-5-20250929": {
    input: 3,
    output: 15,
    cacheRead: 0.3,
    cacheWrite: 3.75,
  },
  "claude-sonnet-4-6": {
    input: 3,
    output: 15,
    cacheRead: 0.3,
    cacheWrite: 3.75,
  },
};

// Resolve a rate for model ids with platform prefixes (us.anthropic.…,
// vertex_ai/…) or date suffixes the table doesn't list explicitly.
export const ratesForModel = (model?: string | null): ClaudeRates | null => {
  if (!model) return null;
  const bare = model
    .toLowerCase()
    .split("/")
    .pop()!
    .replace(/^.*anthropic\./, "");
  if (RATES[bare]) return RATES[bare];
  const dateless = bare.replace(/-\d{8}$/, "");
  if (RATES[dateless]) return RATES[dateless];
  const candidates = Object.keys(RATES).filter(
    (k) => bare.startsWith(k) || k.startsWith(dateless),
  );
  return candidates.length > 0 ? RATES[candidates[0]] : null;
};

export interface TierTokens {
  input_tokens?: number | null;
  cache_read_tokens?: number | null;
  cache_creation_tokens?: number | null;
  output_tokens?: number | null;
}

// Per-model cache-tier sums from cc.billing. The BE groups every tier-bearing
// figure by model so a workspace mixing models is priced exactly.
export interface ModelTiers extends TierTokens {
  model: string;
}

// Cost of one model's tier columns at that model's rates, in USD. Null when
// the model is unknown or no tier data is present (e.g. pre-billing traces).
const tierCost = (tiers: ModelTiers): number | null => {
  const rates = ratesForModel(tiers.model);
  if (!rates) return null;
  const {
    input_tokens,
    cache_read_tokens,
    cache_creation_tokens,
    output_tokens,
  } = tiers;
  if (
    input_tokens == null &&
    cache_read_tokens == null &&
    cache_creation_tokens == null &&
    output_tokens == null
  ) {
    return null;
  }
  return (
    ((input_tokens ?? 0) * rates.input +
      (cache_read_tokens ?? 0) * rates.cacheRead +
      (cache_creation_tokens ?? 0) * rates.cacheWrite +
      (output_tokens ?? 0) * rates.output) /
    1_000_000
  );
};

// Total USD across a per-model tier breakdown: each model priced at its own
// rate, then summed. Null when nothing could be priced (every model unknown
// or no tier data), so the UI hides the figure instead of showing $0.
export const tiersCost = (byModel?: ModelTiers[] | null): number | null => {
  if (!byModel || byModel.length === 0) return null;
  let total: number | null = null;
  for (const tiers of byModel) {
    const cost = tierCost(tiers);
    if (cost == null) continue;
    total = (total ?? 0) + cost;
  }
  return total;
};

const tierTotal = (tiers: ModelTiers): number =>
  (tiers.input_tokens ?? 0) +
  (tiers.cache_read_tokens ?? 0) +
  (tiers.cache_creation_tokens ?? 0) +
  (tiers.output_tokens ?? 0);

// Total tokens across a per-model tier breakdown, summed via tierTotal. Null
// when there's no per-model data so the UI shows N/A instead of 0.
export const tiersTokens = (byModel?: ModelTiers[] | null): number | null =>
  byModel && byModel.length > 0
    ? byModel.reduce((sum, tiers) => sum + tierTotal(tiers), 0)
    : null;

// The model carrying the most tokens, for a single-chip label. Null when the
// breakdown is empty.
export const dominantModel = (byModel?: ModelTiers[] | null): string | null => {
  if (!byModel || byModel.length === 0) return null;
  return byModel.reduce((best, tiers) =>
    tierTotal(tiers) > tierTotal(best) ? tiers : best,
  ).model;
};
