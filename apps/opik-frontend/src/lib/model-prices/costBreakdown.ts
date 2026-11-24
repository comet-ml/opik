import type { ModelPriceResolution, ModelPriceRow } from "./types";
import type { CatalogType } from "./catalog";
import { CATALOG_TYPE_LABELS, findBuiltIn } from "./catalog";
import type { BuiltInPrice } from "./litellm";
import type { CostLineName, UsageHint } from "./spanUsage";
import { COST_LINE_NAMES, readSpanUsage } from "./spanUsage";

export const COST_LINE_LABELS: Record<CostLineName, string> = {
  input: "Input",
  cache_read: "Cache read",
  cache_write: "Cache write",
  output: "Output",
};

export interface CostedSpan {
  model?: string | null;
  provider?: string | null;
  usage?: object | null;
  metadata?: unknown;
  total_estimated_cost?: number | null;
  total_estimated_cost_version?: string | null;
}

export type CostOrigin =
  | { kind: "organization"; listVersion: number }
  | { kind: "built_in" }
  | { kind: "load_failed" }
  | { kind: "unrecorded" }
  | { kind: "client" };

export interface CostLine {
  name: CostLineName;
  tokens: number;
  source: string;
  ratePerMillion: string;
  usesInputRate: boolean;
  cost: number;
}

export type FreshnessStatus = "current" | "changed" | "unrecorded";

export interface Freshness {
  status: FreshnessStatus;
  fromVersions: number[];
}

export interface SpanBreakdown {
  lines: CostLine[];
  linesTotal: number;
  billed: number;
  today: number;
  addsUp: boolean;
  freshness: Freshness;
  hints: UsageHint[];
}

export type PriceTypeTag =
  | { kind: "catalog"; type: CatalogType }
  | { kind: "alias"; target: string }
  | { kind: "none" };

export interface ModelCostGroup {
  key: string;
  model: string;
  provider: string;
  spans: CostedSpan[];
  inputTokens: number;
  outputTokens: number;
  cost: number;
}

export interface SpanCostGroups {
  groups: ModelCostGroup[];
  sentByApp: { spans: number; cost: number };
  total: number;
}

export interface GroupBreakdown {
  lines: CostLine[];
  billed: number;
  today: number;
  changedSpans: number;
  unrecordedSpans: number;
  unexplainedSpans: number;
  fromVersions: number[];
  hints: UsageHint[];
}

const TOKENS_PER_RATE_UNIT = 1_000_000;

export const costsMatch = (left: number, right: number) =>
  Math.abs(left - right) <=
  Math.max(1e-12, Math.max(Math.abs(left), Math.abs(right)) * 1e-6);

export const parseCostVersion = (version?: string | null): CostOrigin => {
  const text = (version ?? "").trim();
  if (!text) return { kind: "client" };
  const separator = text.indexOf(";");
  if (separator < 0) return { kind: "unrecorded" };
  const marker = text.slice(separator + 1);
  if (marker === "fb") return { kind: "load_failed" };
  if (marker.startsWith("o")) {
    const listVersion = Number(marker.slice(1));
    if (Number.isInteger(listVersion)) {
      return { kind: "organization", listVersion };
    }
  }
  if (marker.startsWith("b")) return { kind: "built_in" };
  return { kind: "unrecorded" };
};

const objectOf = (value: unknown): Record<string, unknown> | undefined =>
  typeof value === "object" && value !== null && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;

const metadataCostOf = (metadata: unknown): number | undefined => {
  const cost = objectOf(objectOf(metadata)?.cost);
  if (cost?.currency !== "USD") return undefined;
  const total = Number(cost.total_cost);
  return Number.isFinite(total) ? total : undefined;
};

export const billedCostOf = (span: CostedSpan) =>
  typeof span.total_estimated_cost === "number" &&
  Number.isFinite(span.total_estimated_cost)
    ? span.total_estimated_cost
    : 0;

// A plain version is also what every span estimated before Opik recorded its prices carries, so only a cost that
// matches metadata.cost counts as sent by the app.
export const spanCostOrigin = (span: CostedSpan): CostOrigin => {
  const origin = parseCostVersion(span.total_estimated_cost_version);
  if (origin.kind !== "unrecorded") return origin;
  const metadataCost = metadataCostOf(span.metadata);
  return metadataCost !== undefined &&
    metadataCost > 0 &&
    costsMatch(metadataCost, billedCostOf(span))
    ? { kind: "client" }
    : origin;
};

export const usageRequestOf = (usage: unknown): Record<string, number> => {
  const request: Record<string, number> = {};
  Object.entries(objectOf(usage) ?? {}).forEach(([key, value]) => {
    if (typeof value === "number" && Number.isInteger(value)) {
      request[key] = value;
    }
  });
  return request;
};

// Usage with no tokens makes the backend report cost_source "none", which would hide the prices of the whole group.
export const usageForPrices = (spans: CostedSpan[]): Record<string, number> => {
  const usages = spans.map((span) => usageRequestOf(span.usage));
  return (
    usages.find((usage) => Object.values(usage).some((count) => count > 0)) ??
    usages[0] ??
    {}
  );
};

export const spanModelOf = (span: CostedSpan): string => {
  const model = span.model?.trim();
  if (model) return model;
  const fromMetadata = objectOf(span.metadata)?.model;
  return typeof fromMetadata === "string" ? fromMetadata.trim() : "";
};

const ratesOf = (resolution: Pick<ModelPriceResolution, "lines">) => {
  const rates: Record<string, string> = {};
  resolution.lines.forEach((line) => {
    rates[line.name] = line.rate_per_million;
  });
  return rates;
};

const isPositive = (rate?: string) => Number(rate) > 0;

const linesOf = (
  tokens: Record<CostLineName, number>,
  sources: Record<CostLineName, string>,
  resolution: Pick<ModelPriceResolution, "lines">,
): CostLine[] => {
  const rates = ratesOf(resolution);
  const inputRate = rates.input ?? "0";
  return COST_LINE_NAMES.map((name) => {
    // A provider that publishes no cache rate bills its cached tokens as plain input.
    const usesInputRate = name.startsWith("cache_") && !isPositive(rates[name]);
    const ratePerMillion = usesInputRate ? inputRate : (rates[name] ?? "0");
    return {
      name,
      tokens: tokens[name],
      source: sources[name],
      ratePerMillion,
      usesInputRate,
      cost: (tokens[name] * Number(ratePerMillion)) / TOKENS_PER_RATE_UNIT,
    };
  });
};

const readSpan = (span: CostedSpan) => {
  const reading = readSpanUsage(span.usage, span.provider, spanModelOf(span));
  const tokens = {} as Record<CostLineName, number>;
  const sources = {} as Record<CostLineName, string>;
  reading.buckets.forEach((bucket) => {
    tokens[bucket.name] = bucket.tokens;
    sources[bucket.name] = bucket.source;
  });
  return { tokens, sources, hints: reading.hints };
};

export const visibleLines = (lines: CostLine[]) => {
  const used = lines.filter((line) => line.tokens !== 0);
  return used.length
    ? used
    : lines.filter((line) => line.name === "input" || line.name === "output");
};

const sumOf = (values: number[]) =>
  values.reduce((sum, value) => sum + value, 0);

const versionMatches = (
  origin: CostOrigin,
  resolution: Pick<ModelPriceResolution, "cost_source" | "list_version">,
) => {
  if (origin.kind === "organization") {
    return (
      resolution.cost_source === "organization" &&
      resolution.list_version === origin.listVersion
    );
  }
  return (
    (origin.kind === "built_in" || origin.kind === "load_failed") &&
    resolution.cost_source === "built_in"
  );
};

// The list version moves whenever any row of the organization changes, so the cost decides whether this model's
// price did; the versions only say between which lists it changed.
export const freshnessOf = (
  origin: CostOrigin,
  billed: number,
  today: number,
): Freshness => {
  if (costsMatch(billed, today)) return { status: "current", fromVersions: [] };
  if (origin.kind === "unrecorded" || origin.kind === "client") {
    return { status: "unrecorded", fromVersions: [] };
  }
  return {
    status: "changed",
    fromVersions: origin.kind === "organization" ? [origin.listVersion] : [],
  };
};

export const priceSpan = (
  span: CostedSpan,
  resolution: ModelPriceResolution,
): SpanBreakdown => {
  const { tokens, sources, hints } = readSpan(span);
  const lines = linesOf(tokens, sources, resolution);
  const linesTotal = sumOf(lines.map((line) => line.cost));
  const today = Number(resolution.cost) || 0;
  const billed = billedCostOf(span);
  return {
    lines,
    linesTotal,
    billed,
    today,
    addsUp: costsMatch(linesTotal, today),
    freshness: freshnessOf(spanCostOrigin(span), billed, today),
    hints,
  };
};

export const needsBuiltInPrices = (resolution: ModelPriceResolution) =>
  resolution.match?.row?.kind === "fixed";

export const priceTypeOf = (
  resolution: ModelPriceResolution,
  builtIns?: BuiltInPrice[],
): PriceTypeTag | undefined => {
  const match = resolution.match;
  if (!match) return { kind: "none" };
  const row: ModelPriceRow | null | undefined = match.row;
  if (!row) return { kind: "catalog", type: "default" };
  if (row.kind === "alias") {
    return { kind: "alias", target: row.alias_of?.model?.trim() ?? "" };
  }
  if (!builtIns) return undefined;
  return {
    kind: "catalog",
    type: findBuiltIn(builtIns, row.model, row.provider ?? "")
      ? "override"
      : "custom",
  };
};

export const describePriceType = (tag: PriceTypeTag) => {
  if (tag.kind === "none") return "No price";
  if (tag.kind === "alias") return `Same as ${tag.target}`;
  return CATALOG_TYPE_LABELS[tag.type];
};

const HINT_LABELS: Record<UsageHint, string> = {
  audio: "audio tokens",
  reasoning: "reasoning tokens",
  one_hour_cache_write: "1-hour cache writes",
  long_prompt: "prompts above a size tier",
};

const joinWords = (words: string[]) =>
  words.length > 1
    ? `${words.slice(0, -1).join(", ")} or ${words[words.length - 1]}`
    : words.join("");

export const describeMismatch = (hints: UsageHint[]) => {
  const reasons = Array.from(new Set(hints)).map((hint) => HINT_LABELS[hint]);
  return reasons.length
    ? `The breakdown may not add up exactly: this model may bill ${joinWords(
        reasons,
      )} at rates not shown here.`
    : "The breakdown may not add up exactly: this model may bill some tokens at rates not shown here.";
};

const listVersionsText = (versions: number[]) =>
  versions.length === 1
    ? `price list v${versions[0]}`
    : `price lists v${versions.join(", v")}`;

export const describeVersionChange = (
  fromVersions: number[],
  resolution: Pick<ModelPriceResolution, "cost_source" | "list_version">,
) => {
  const from = Array.from(new Set(fromVersions)).sort(
    (left, right) => left - right,
  );
  const fromText = from.length ? listVersionsText(from) : "built-in prices";
  if (resolution.cost_source === "none") return `${fromText} → no price`;
  if (resolution.cost_source === "built_in") {
    return from.length ? `${fromText} → built-in prices` : "";
  }
  if (from.length === 1 && from[0] === resolution.list_version) return "";
  return from.length
    ? `${fromText} → v${resolution.list_version}`
    : `${fromText} → price list v${resolution.list_version}`;
};

export const groupSpanCosts = (spans: CostedSpan[]): SpanCostGroups => {
  const byKey = new Map<string, ModelCostGroup>();
  const sentByApp = { spans: 0, cost: 0 };
  let total = 0;
  spans.forEach((span) => {
    const cost = billedCostOf(span);
    if (cost <= 0) return;
    total += cost;
    if (spanCostOrigin(span).kind === "client") {
      sentByApp.spans += 1;
      sentByApp.cost += cost;
      return;
    }
    const model = spanModelOf(span);
    const provider = span.provider?.trim() ?? "";
    const key = `${model}|${provider}`;
    const group = byKey.get(key) ?? {
      key,
      model,
      provider,
      spans: [],
      inputTokens: 0,
      outputTokens: 0,
      cost: 0,
    };
    const { tokens } = readSpan(span);
    group.spans.push(span);
    group.inputTokens += tokens.input + tokens.cache_read + tokens.cache_write;
    group.outputTokens += tokens.output;
    group.cost += cost;
    byKey.set(key, group);
  });
  return {
    groups: Array.from(byKey.values()).sort(
      (left, right) => right.cost - left.cost,
    ),
    sentByApp,
    total,
  };
};

export const priceGroup = (
  group: ModelCostGroup,
  resolution: ModelPriceResolution,
): GroupBreakdown => {
  const tokens = {} as Record<CostLineName, number>;
  const sources = {} as Record<CostLineName, string>;
  COST_LINE_NAMES.forEach((name) => {
    tokens[name] = 0;
    sources[name] = "";
  });
  const hints: UsageHint[] = [];
  const fromVersions: number[] = [];
  let changedSpans = 0;
  let unrecordedSpans = 0;
  let unexplainedSpans = 0;
  let today = 0;
  group.spans.forEach((span) => {
    const read = readSpan(span);
    const spanLines = linesOf(read.tokens, read.sources, resolution);
    const spanToday = sumOf(spanLines.map((line) => line.cost));
    today += spanToday;
    COST_LINE_NAMES.forEach((name) => {
      tokens[name] += read.tokens[name];
      if (!sources[name] && read.tokens[name])
        sources[name] = read.sources[name];
    });
    hints.push(...read.hints);
    if (costsMatch(billedCostOf(span), spanToday)) return;
    const origin = spanCostOrigin(span);
    if (origin.kind === "unrecorded" || origin.kind === "client") {
      unrecordedSpans += 1;
      return;
    }
    if (versionMatches(origin, resolution)) {
      unexplainedSpans += 1;
      return;
    }
    changedSpans += 1;
    if (origin.kind === "organization") fromVersions.push(origin.listVersion);
  });
  return {
    lines: linesOf(tokens, sources, resolution),
    billed: group.cost,
    today,
    changedSpans,
    unrecordedSpans,
    unexplainedSpans,
    fromVersions,
    hints,
  };
};
