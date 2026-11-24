export type CostLineName = "input" | "cache_read" | "cache_write" | "output";

export const COST_LINE_NAMES: CostLineName[] = [
  "input",
  "cache_read",
  "cache_write",
  "output",
];

export type UsageHint =
  "audio" | "reasoning" | "one_hour_cache_write" | "long_prompt";

export interface UsageBucket {
  name: CostLineName;
  tokens: number;
  source: string;
}

export interface UsageReading {
  buckets: UsageBucket[];
  hints: UsageHint[];
}

type Counts = Record<string, number>;

interface OriginalUsage {
  keys: Counts;
  outerKeys: Counts;
  nested: boolean;
}

interface UsageKeys {
  has: (name: string) => boolean;
  get: (name: string) => number;
  find: (name: string) => number | null;
  firstName: (...names: string[]) => string | undefined;
  first: (...names: string[]) => number;
  hasListItems: (listName: string) => boolean;
  sumOverListItems: (listName: string, field: string) => number;
  reportedTotal: number | null;
}

interface VariantInput {
  keys: UsageKeys;
  outerPromptTokens: number | null;
}

interface Buckets {
  input: number;
  cacheRead: number;
  cacheWrite: number;
  output: number;
  sources: Record<CostLineName, string>;
  hints: UsageHint[];
}

interface InputVariant {
  matches: (keys: UsageKeys) => boolean;
  read: (input: VariantInput) => Buckets;
}

type UsageFormatName = "openai" | "anthropic" | "gemini" | "bedrock_converse";

const ORIGINAL_USAGE_PREFIX = "original_usage.";
const NAMESPACES = ["gen_ai.usage.", "llm.token_count."];
const TOTAL_NAMES = ["total_tokens", "total"];
const CAMEL_CASE_BOUNDARY = /([a-z0-9])([A-Z])/g;
const SMALLEST_PROMPT_TIER = 32_000;

const LITELLM_NAME_BY_ALIAS: Record<string, string> = {
  google_ai: "gemini",
  google_vertexai: "vertex_ai-language-models",
  anthropic_vertexai: "vertex_ai-anthropic_models",
  "vertex-ai": "vertex_ai-language-models",
  moonshotai: "moonshot",
  "z-ai": "zai",
  together: "together_ai",
};

const FORMAT_BY_PROVIDER: Record<string, UsageFormatName> = {
  anthropic: "anthropic",
  "vertex_ai-anthropic_models": "anthropic",
  gemini: "gemini",
  "vertex_ai-language-models": "gemini",
  vertex_ai: "gemini",
  bedrock: "bedrock_converse",
  bedrock_converse: "bedrock_converse",
};

const FIVE_MINUTE_WRITES = [
  "cache_creation.ephemeral_5m_input_tokens",
  "cache_creation_token_details.ephemeral_5m_input_tokens",
  "prompt_tokens_details.cache_creation_token_details.ephemeral_5m_input_tokens",
  "cache_creation_ephemeral_5m_input_tokens",
  "cache_details.5m.input_tokens",
];

const ONE_HOUR_WRITES = [
  "cache_creation.ephemeral_1h_input_tokens",
  "cache_creation_token_details.ephemeral_1h_input_tokens",
  "prompt_tokens_details.cache_creation_token_details.ephemeral_1h_input_tokens",
  "cache_creation_ephemeral_1h_input_tokens",
  "cache_details.1h.input_tokens",
];

const toLiteLlmName = (name: string) => {
  const normalized = name.trim().toLowerCase();
  return LITELLM_NAME_BY_ALIAS[normalized] ?? normalized;
};

// The backend checks an API host against its whole price table. Only the providers with a usage format of their
// own change how usage reads, so only those are worth recognizing in a host here.
const providerOfHost = (name: string): string | undefined => {
  if (!name.includes(".")) return undefined;
  const labels = name.replace(/:\d+$/, "").replace(/\.$/, "").split(".");
  const vendor =
    labels.length === 2
      ? labels[0]
      : labels.length === 3 && labels[0] === "api"
        ? labels[1]
        : undefined;
  const provider = vendor ? toLiteLlmName(vendor) : undefined;
  return provider && FORMAT_BY_PROVIDER[provider] ? provider : undefined;
};

export const resolveUsageProvider = (
  provider?: string | null,
  model?: string | null,
): string | undefined => {
  if (provider?.trim()) {
    const name = toLiteLlmName(provider);
    return providerOfHost(name) ?? name;
  }
  const prefixEnd = model ? model.indexOf("/") : -1;
  if (model && prefixEnd > 0 && model.slice(0, prefixEnd).trim()) {
    return toLiteLlmName(model.slice(0, prefixEnd));
  }
  return undefined;
};

const countsOf = (usage: unknown): Counts => {
  const counts: Counts = {};
  if (typeof usage !== "object" || usage === null) return counts;
  Object.entries(usage).forEach(([key, value]) => {
    if (typeof value === "number" && Number.isFinite(value)) {
      counts[key] = value;
    }
  });
  return counts;
};

const originalUsageOf = (counts: Counts): OriginalUsage => {
  const outerKeys: Counts = {};
  const keysByDepth: Record<number, Counts> = {};
  Object.entries(counts).forEach(([key, value]) => {
    let depth = 0;
    let nameStart = 0;
    while (key.startsWith(ORIGINAL_USAGE_PREFIX, nameStart)) {
      nameStart += ORIGINAL_USAGE_PREFIX.length;
      depth += 1;
    }
    const name = key.slice(nameStart);
    if (!name) return;
    if (depth === 0) {
      outerKeys[key] = value;
    } else {
      keysByDepth[depth] = { ...keysByDepth[depth], [name]: value };
    }
  });
  const depths = Object.keys(keysByDepth).map(Number);
  if (!depths.length) {
    return { keys: outerKeys, outerKeys: {}, nested: false };
  }
  // Usage converted and then logged as provider usage again nests deeper; only the deepest level is the provider's.
  return {
    keys: keysByDepth[Math.max(...depths)],
    outerKeys,
    nested: true,
  };
};

const withOuterKeys = (original: OriginalUsage): OriginalUsage => ({
  keys: { ...original.outerKeys, ...original.keys },
  outerKeys: {},
  nested: false,
});

const normalizeSegment = (segment: string) =>
  segment
    .replace(CAMEL_CASE_BOUNDARY, "$1_$2")
    .replace(/-/g, "_")
    .toLowerCase();

export const normalizeUsageKey = (key: string) => {
  const normalized = key.split(".").map(normalizeSegment).join(".");
  const namespace = NAMESPACES.find(
    (prefix) =>
      normalized.startsWith(prefix) && normalized.length > prefix.length,
  );
  return namespace ? normalized.slice(namespace.length) : normalized;
};

const listItemField = (name: string, listName: string) => {
  const prefix = `${listName}.`;
  if (!name.startsWith(prefix)) return undefined;
  const rest = name.slice(prefix.length);
  const dot = rest.indexOf(".");
  if (dot <= 0 || !/^\d+$/.test(rest.slice(0, dot))) return undefined;
  return rest.slice(dot + 1);
};

const usageKeysOf = (original: OriginalUsage): UsageKeys => {
  const counts = new Map<string, number>();
  // The backend keeps the keys sorted, so when two spellings normalize to one name the same one wins here.
  Object.keys(original.keys)
    .sort()
    .forEach((key) => {
      const name = normalizeUsageKey(key);
      if (!counts.has(name)) counts.set(name, original.keys[key]);
    });
  const has = (name: string) => counts.has(name);
  const get = (name: string) => counts.get(name) ?? 0;
  const firstName = (...names: string[]) => names.find(has);
  const total = TOTAL_NAMES.map((name) => counts.get(name)).find(
    (value) => value !== undefined,
  );
  return {
    has,
    get,
    find: (name) => counts.get(name) ?? null,
    firstName,
    first: (...names) => {
      const name = firstName(...names);
      return name ? get(name) : 0;
    },
    hasListItems: (listName) =>
      Array.from(counts.keys()).some(
        (name) => listItemField(name, listName) !== undefined,
      ),
    sumOverListItems: (listName, field) =>
      Array.from(counts.entries())
        .filter(([name]) => listItemField(name, listName) === field)
        .reduce((sum, [, value]) => sum + value, 0),
    reportedTotal: total ?? original.outerKeys.total_tokens ?? null,
  };
};

const cacheInsideInput = (
  variant: VariantInput,
  input: number,
  cacheTokens: number,
  outputTokens: number | null,
) => {
  if (cacheTokens === 0) return true;
  if (input < cacheTokens) return false;
  const total = variant.keys.reportedTotal;
  return (
    (total === null ||
      outputTokens === null ||
      total !== input + cacheTokens + outputTokens) &&
    (variant.outerPromptTokens === null ||
      variant.outerPromptTokens !== input + cacheTokens)
  );
};

const excludingCache = (
  variant: VariantInput,
  input: number,
  cacheTokens: number,
  outputTokens: number | null,
) =>
  cacheInsideInput(variant, input, cacheTokens, outputTokens)
    ? input - cacheTokens
    : input;

interface CacheWrites {
  fiveMinutes: number;
  oneHour: number;
  source?: string;
}

const cacheWritesOf = (
  keys: UsageKeys,
  total: number,
  totalName?: string,
): CacheWrites => {
  const fiveMinuteName = keys.firstName(...FIVE_MINUTE_WRITES);
  const oneHourName = keys.firstName(...ONE_HOUR_WRITES);
  if (fiveMinuteName || oneHourName) {
    const oneHour = keys.first(...ONE_HOUR_WRITES);
    return {
      fiveMinutes: Math.max(keys.first(...FIVE_MINUTE_WRITES), total - oneHour),
      oneHour,
      source:
        totalName ?? [fiveMinuteName, oneHourName].filter(Boolean).join(" + "),
    };
  }
  return { fiveMinutes: total, oneHour: 0, source: totalName };
};

const minus = (from: string, parts: (string | false | undefined)[]) =>
  [from, ...parts.filter(Boolean)].join(" − ");

const buckets = (
  values: Omit<Buckets, "sources" | "hints">,
  sources: Partial<Record<CostLineName, string>>,
  hints: UsageHint[] = [],
): Buckets => ({
  ...values,
  sources: {
    input: sources.input ?? "",
    cache_read: sources.cache_read ?? "",
    cache_write: sources.cache_write ?? "",
    output: sources.output ?? "",
  },
  hints,
});

interface OpenAiCounts {
  reportedPrompt: number;
  promptName: string;
  cachedTokens: number;
  cachedName?: string;
  writes: CacheWrites;
  audioPromptTokens: number;
  completionTokens: number;
  completionName: string;
  reasoningTokens: number;
  audioCompletionTokens: number;
  textPromptTokens: number | null;
  textPromptName: string;
  textCompletionTokens: number | null;
  textCompletionName: string;
}

// Audio and reasoning tokens split out only at rates of their own, which the price explanation doesn't return, so
// they're read the way the backend reads them for a model without those rates.
const openAiBuckets = (variant: VariantInput, counts: OpenAiCounts) => {
  const writes = counts.writes.fiveMinutes + counts.writes.oneHour;
  const cacheTokens = counts.cachedTokens + writes;
  const inside = cacheInsideInput(
    variant,
    counts.reportedPrompt,
    cacheTokens,
    counts.completionTokens,
  );
  const promptTokens = inside
    ? counts.reportedPrompt
    : counts.reportedPrompt + cacheTokens;
  const input = Math.max(
    0,
    counts.textPromptTokens ?? promptTokens - counts.cachedTokens - writes,
  );
  const output = Math.max(
    0,
    counts.textCompletionTokens ?? counts.completionTokens,
  );
  const hints: UsageHint[] = [];
  if (counts.audioPromptTokens > 0 || counts.audioCompletionTokens > 0) {
    hints.push("audio");
  }
  if (counts.reasoningTokens > 0) hints.push("reasoning");
  if (counts.writes.oneHour > 0) hints.push("one_hour_cache_write");
  if (promptTokens > SMALLEST_PROMPT_TIER) hints.push("long_prompt");
  return buckets(
    { input, cacheRead: counts.cachedTokens, cacheWrite: writes, output },
    {
      input:
        counts.textPromptTokens !== null
          ? counts.textPromptName
          : inside
            ? minus(counts.promptName, [
                counts.cachedTokens > 0 && counts.cachedName,
                writes > 0 && counts.writes.source,
              ])
            : counts.promptName,
      cache_read: counts.cachedName,
      cache_write: counts.writes.source,
      output:
        counts.textCompletionTokens !== null
          ? counts.textCompletionName
          : counts.completionName,
    },
    hints,
  );
};

const OPENAI_CACHED = [
  "prompt_tokens_details.cached_tokens",
  "input_tokens_details.cached_tokens",
  "cache_read_input_tokens",
  "cache_read.input_tokens",
  "cached_tokens",
  "cache_read_tokens",
  "cached_content_token_count",
];

const OPENAI_CACHE_WRITES = [
  "prompt_tokens_details.cache_write_tokens",
  "prompt_tokens_details.cache_creation_tokens",
  "cache_creation_input_tokens",
  "cache_creation.input_tokens",
  "cache_write_tokens",
  "cache_creation_tokens",
];

const chatCompletions = (
  variant: VariantInput,
  cachedTokens: number,
  cachedName: string | undefined,
  writeTokens: number,
  writeName: string | undefined,
) => {
  const { keys } = variant;
  return openAiBuckets(variant, {
    reportedPrompt: keys.get("prompt_tokens"),
    promptName: "prompt_tokens",
    cachedTokens,
    cachedName,
    writes: cacheWritesOf(keys, writeTokens, writeName),
    audioPromptTokens: keys.get("prompt_tokens_details.audio_tokens"),
    completionTokens: keys.get("completion_tokens"),
    completionName: "completion_tokens",
    reasoningTokens: keys.first(
      "completion_tokens_details.reasoning_tokens",
      "reasoning.output_tokens",
      "reasoning_tokens",
    ),
    audioCompletionTokens: keys.get("completion_tokens_details.audio_tokens"),
    textPromptTokens: keys.find("prompt_tokens_details.text_tokens"),
    textPromptName: "prompt_tokens_details.text_tokens",
    textCompletionTokens: keys.find("completion_tokens_details.text_tokens"),
    textCompletionName: "completion_tokens_details.text_tokens",
  });
};

const OPENAI_VARIANTS: InputVariant[] = [
  {
    matches: (keys) =>
      keys.has("prompt_tokens") &&
      (keys.has("prompt_cache_hit_tokens") ||
        keys.has("prompt_cache_miss_tokens")),
    read: (variant) =>
      chatCompletions(
        variant,
        variant.keys.get("prompt_cache_hit_tokens"),
        "prompt_cache_hit_tokens",
        0,
        undefined,
      ),
  },
  {
    matches: (keys) =>
      keys.has("prompt_tokens") || keys.has("completion_tokens"),
    read: (variant) =>
      chatCompletions(
        variant,
        variant.keys.first(...OPENAI_CACHED),
        variant.keys.firstName(...OPENAI_CACHED),
        variant.keys.first(...OPENAI_CACHE_WRITES),
        variant.keys.firstName(...OPENAI_CACHE_WRITES),
      ),
  },
  {
    matches: (keys) => keys.has("input_tokens") || keys.has("output_tokens"),
    read: (variant) => {
      const { keys } = variant;
      const cachedNames = [
        "input_tokens_details.cached_tokens",
        "cache_read.input_tokens",
        "cache_read_input_tokens",
        "cached_tokens",
      ];
      const writeNames = [
        "cache_creation.input_tokens",
        "cache_creation_input_tokens",
      ];
      return openAiBuckets(variant, {
        reportedPrompt: keys.get("input_tokens"),
        promptName: "input_tokens",
        cachedTokens: keys.first(...cachedNames),
        cachedName: keys.firstName(...cachedNames),
        writes: cacheWritesOf(
          keys,
          keys.first(...writeNames),
          keys.firstName(...writeNames),
        ),
        audioPromptTokens: keys.get("input_tokens_details.audio_tokens"),
        completionTokens: keys.get("output_tokens"),
        completionName: "output_tokens",
        reasoningTokens: keys.first(
          "output_tokens_details.reasoning_tokens",
          "reasoning.output_tokens",
          "reasoning_tokens",
        ),
        audioCompletionTokens: keys.get("output_tokens_details.audio_tokens"),
        textPromptTokens: keys.find("input_tokens_details.text_tokens"),
        textPromptName: "input_tokens_details.text_tokens",
        textCompletionTokens: keys.find("output_tokens_details.text_tokens"),
        textCompletionName: "output_tokens_details.text_tokens",
      });
    },
  },
  {
    matches: (keys) => keys.has("prompt") || keys.has("completion"),
    read: (variant) => {
      const { keys } = variant;
      const writeTokens = keys.get("prompt_details.cache_write");
      return openAiBuckets(variant, {
        reportedPrompt: keys.get("prompt"),
        promptName: "prompt",
        cachedTokens: keys.get("prompt_details.cache_read"),
        cachedName: "prompt_details.cache_read",
        writes: {
          fiveMinutes: writeTokens,
          oneHour: 0,
          source: "prompt_details.cache_write",
        },
        audioPromptTokens: keys.get("prompt_details.audio"),
        completionTokens: keys.get("completion"),
        completionName: "completion",
        reasoningTokens: keys.get("completion_details.reasoning"),
        audioCompletionTokens: keys.get("completion_details.audio"),
        textPromptTokens: null,
        textPromptName: "",
        textCompletionTokens: null,
        textCompletionName: "",
      });
    },
  },
];

interface CacheOutsideCounts {
  input: number;
  inputSource: string;
  cacheRead: number;
  cacheReadSource?: string;
  writes: CacheWrites;
  output: number;
  outputSource: string;
}

const cacheOutsideBuckets = (counts: CacheOutsideCounts) => {
  const cacheWrite = counts.writes.fiveMinutes + counts.writes.oneHour;
  const hints: UsageHint[] = [];
  if (counts.writes.oneHour > 0) hints.push("one_hour_cache_write");
  if (counts.input + counts.cacheRead + cacheWrite > SMALLEST_PROMPT_TIER) {
    hints.push("long_prompt");
  }
  return buckets(
    {
      input: counts.input,
      cacheRead: counts.cacheRead,
      cacheWrite,
      output: counts.output,
    },
    {
      input: counts.inputSource,
      cache_read: counts.cacheReadSource,
      cache_write: counts.writes.source,
      output: counts.outputSource,
    },
    hints,
  );
};

const noWrites: CacheWrites = { fiveMinutes: 0, oneHour: 0 };

const totalsOnly = (variant: VariantInput) =>
  cacheOutsideBuckets({
    input: variant.keys.get("prompt_tokens"),
    inputSource: "prompt_tokens",
    cacheRead: 0,
    writes: noWrites,
    output: variant.keys.get("completion_tokens"),
    outputSource: "completion_tokens",
  });

const fromLiteLlmCacheOutside = (
  variant: VariantInput,
  cacheRead: number,
  cacheReadSource: string | undefined,
  writes: CacheWrites,
) => {
  const { keys } = variant;
  const promptTokens = keys.get("prompt_tokens");
  const detailed =
    keys.has("prompt_tokens_details.cached_tokens") ||
    keys.has("prompt_tokens_details.cache_creation_tokens");
  const cacheTokens = cacheRead + writes.fiveMinutes + writes.oneHour;
  const input = detailed
    ? excludingCache(
        variant,
        promptTokens,
        cacheTokens,
        keys.get("completion_tokens"),
      )
    : promptTokens;
  return cacheOutsideBuckets({
    input,
    inputSource:
      input === promptTokens || cacheTokens === 0
        ? "prompt_tokens"
        : minus("prompt_tokens", ["cache tokens"]),
    cacheRead,
    cacheReadSource,
    writes,
    output: keys.get("completion_tokens"),
    outputSource: "completion_tokens",
  });
};

const ANTHROPIC_VARIANTS: InputVariant[] = [
  {
    matches: (keys) =>
      (keys.has("input_tokens") || keys.has("prompt_tokens")) &&
      (keys.has("cache_read.input_tokens") ||
        keys.has("cache_creation.input_tokens")),
    read: (variant) => {
      const { keys } = variant;
      const cacheRead = keys.get("cache_read.input_tokens");
      const cacheWrite = keys.get("cache_creation.input_tokens");
      const inputName = keys.firstName("input_tokens", "prompt_tokens") ?? "";
      const reportedInput = keys.first("input_tokens", "prompt_tokens");
      const output = keys.first("output_tokens", "completion_tokens");
      const input = excludingCache(
        variant,
        reportedInput,
        cacheRead + cacheWrite,
        output,
      );
      return cacheOutsideBuckets({
        input,
        inputSource:
          input === reportedInput
            ? inputName
            : minus(inputName, [
                cacheRead > 0 && "cache_read.input_tokens",
                cacheWrite > 0 && "cache_creation.input_tokens",
              ]),
        cacheRead,
        cacheReadSource: "cache_read.input_tokens",
        writes: {
          fiveMinutes: cacheWrite,
          oneHour: 0,
          source: "cache_creation.input_tokens",
        },
        output,
        outputSource:
          keys.firstName("output_tokens", "completion_tokens") ??
          "output_tokens",
      });
    },
  },
  {
    matches: (keys) =>
      keys.has("input_tokens") &&
      keys.has("input_tokens_details.cached_tokens"),
    read: (variant) => {
      const { keys } = variant;
      const cacheRead = keys.get("input_tokens_details.cached_tokens");
      const reportedInput = keys.get("input_tokens");
      const input = excludingCache(
        variant,
        reportedInput,
        cacheRead,
        keys.get("output_tokens"),
      );
      return cacheOutsideBuckets({
        input,
        inputSource:
          input === reportedInput
            ? "input_tokens"
            : minus("input_tokens", ["input_tokens_details.cached_tokens"]),
        cacheRead,
        cacheReadSource: "input_tokens_details.cached_tokens",
        writes: noWrites,
        output: keys.get("output_tokens"),
        outputSource: "output_tokens",
      });
    },
  },
  {
    matches: (keys) =>
      keys.has("input_tokens") || keys.hasListItems("iterations"),
    read: (variant) => {
      const { keys } = variant;
      if (keys.hasListItems("iterations")) {
        return cacheOutsideBuckets({
          input: keys.sumOverListItems("iterations", "input_tokens"),
          inputSource: "iterations.*.input_tokens",
          cacheRead: keys.sumOverListItems(
            "iterations",
            "cache_read_input_tokens",
          ),
          cacheReadSource: "iterations.*.cache_read_input_tokens",
          writes: {
            fiveMinutes: keys.sumOverListItems(
              "iterations",
              "cache_creation_input_tokens",
            ),
            oneHour: 0,
            source: "iterations.*.cache_creation_input_tokens",
          },
          output: keys.sumOverListItems("iterations", "output_tokens"),
          outputSource: "iterations.*.output_tokens",
        });
      }
      const writeNames = [
        "cache_creation_input_tokens",
        "cache_write_input_tokens",
      ];
      return cacheOutsideBuckets({
        input: keys.get("input_tokens"),
        inputSource: "input_tokens",
        cacheRead: keys.get("cache_read_input_tokens"),
        cacheReadSource: "cache_read_input_tokens",
        writes: cacheWritesOf(
          keys,
          keys.first(...writeNames),
          keys.firstName(...writeNames),
        ),
        output: keys.get("output_tokens"),
        outputSource: "output_tokens",
      });
    },
  },
  {
    matches: (keys) =>
      keys.has("prompt_tokens") &&
      [
        "cache_read_input_tokens",
        "prompt_tokens_details.cached_tokens",
        "cache_creation_input_tokens",
        "cache_read_tokens",
        "cache_write_tokens",
        "cache_creation_tokens",
      ].some(keys.has),
    read: (variant) => {
      const { keys } = variant;
      const readNames = [
        "cache_read_input_tokens",
        "prompt_tokens_details.cached_tokens",
        "cache_read_tokens",
      ];
      // A client that saw no cache reads may still send its zero next to another client's count of them.
      const cacheRead = Math.max(...readNames.map(keys.get));
      const writeNames = [
        "cache_creation_input_tokens",
        "cache_write_tokens",
        "cache_creation_tokens",
      ];
      return fromLiteLlmCacheOutside(
        variant,
        cacheRead,
        readNames.find(
          (name) => keys.get(name) === cacheRead && keys.has(name),
        ),
        cacheWritesOf(
          keys,
          keys.first(...writeNames),
          keys.firstName(...writeNames),
        ),
      );
    },
  },
  {
    matches: (keys) => keys.has("prompt"),
    read: (variant) => {
      const { keys } = variant;
      const cacheRead = keys.get("prompt_details.cache_read");
      const cacheWrite = keys.get("prompt_details.cache_write");
      const reportedInput = keys.get("prompt");
      const input = excludingCache(
        variant,
        reportedInput,
        cacheRead + cacheWrite,
        keys.get("completion"),
      );
      return cacheOutsideBuckets({
        input,
        inputSource:
          input === reportedInput
            ? "prompt"
            : minus("prompt", [
                cacheRead > 0 && "prompt_details.cache_read",
                cacheWrite > 0 && "prompt_details.cache_write",
              ]),
        cacheRead,
        cacheReadSource: "prompt_details.cache_read",
        writes: {
          fiveMinutes: cacheWrite,
          oneHour: 0,
          source: "prompt_details.cache_write",
        },
        output: keys.get("completion"),
        outputSource: "completion",
      });
    },
  },
  {
    matches: (keys) =>
      keys.has("prompt_tokens") || keys.has("completion_tokens"),
    read: totalsOnly,
  },
];

const BEDROCK_VARIANTS: InputVariant[] = [
  {
    matches: (keys) => keys.has("input_tokens") || keys.has("output_tokens"),
    read: (variant) => {
      const { keys } = variant;
      const writeNames = [
        "cache_write_input_tokens",
        "cache_creation_input_tokens",
      ];
      return cacheOutsideBuckets({
        input: keys.get("input_tokens"),
        inputSource: "input_tokens",
        cacheRead: keys.get("cache_read_input_tokens"),
        cacheReadSource: "cache_read_input_tokens",
        writes: cacheWritesOf(
          keys,
          keys.first(...writeNames),
          keys.firstName(...writeNames),
        ),
        output: keys.get("output_tokens"),
        outputSource: "output_tokens",
      });
    },
  },
  {
    matches: (keys) => keys.has("generation_token_count"),
    read: (variant) =>
      cacheOutsideBuckets({
        input: variant.keys.get("prompt_token_count"),
        inputSource: "prompt_token_count",
        cacheRead: 0,
        writes: noWrites,
        output: variant.keys.get("generation_token_count"),
        outputSource: "generation_token_count",
      }),
  },
  {
    matches: (keys) =>
      keys.has("prompt_tokens") &&
      (keys.has("cache_read_input_tokens") ||
        keys.has("cache_creation_input_tokens")),
    read: (variant) => {
      const { keys } = variant;
      return fromLiteLlmCacheOutside(
        variant,
        keys.get("cache_read_input_tokens"),
        "cache_read_input_tokens",
        cacheWritesOf(
          keys,
          keys.get("cache_creation_input_tokens"),
          "cache_creation_input_tokens",
        ),
      );
    },
  },
  {
    matches: (keys) =>
      keys.has("prompt_tokens") || keys.has("completion_tokens"),
    read: totalsOnly,
  },
];

interface GeminiCounts {
  promptTokenCount: number;
  promptSource: string;
  cachedContentTokenCount: number;
  cachedSource?: string;
  toolUsePromptTokenCount: number;
  candidatesTokenCount: number;
  candidatesSource: string;
  thoughtsTokenCount: number;
  thoughtsSource?: string;
}

// Thoughts bill at the output rate unless the model has a reasoning rate, which the price explanation doesn't return.
const geminiBuckets = (counts: GeminiCounts) => {
  const promptTokens = counts.promptTokenCount + counts.toolUsePromptTokenCount;
  const cached = Math.min(counts.cachedContentTokenCount, promptTokens);
  const hints: UsageHint[] = [];
  if (counts.thoughtsTokenCount > 0) hints.push("reasoning");
  if (promptTokens > SMALLEST_PROMPT_TIER) hints.push("long_prompt");
  const inputParts = [counts.promptSource];
  if (counts.toolUsePromptTokenCount > 0) {
    inputParts.push("+ tool_use_prompt_token_count");
  }
  if (cached > 0 && counts.cachedSource) {
    inputParts.push(`− ${counts.cachedSource}`);
  }
  return buckets(
    {
      input: promptTokens - cached,
      cacheRead: cached,
      cacheWrite: 0,
      output: counts.candidatesTokenCount + counts.thoughtsTokenCount,
    },
    {
      input: inputParts.join(" "),
      cache_read: counts.cachedSource,
      output:
        counts.thoughtsTokenCount > 0 && counts.thoughtsSource
          ? `${counts.candidatesSource} + ${counts.thoughtsSource}`
          : counts.candidatesSource,
    },
    hints,
  );
};

const geminiWithReasoning = (
  variant: VariantInput,
  reportedPrompt: number,
  promptSource: string,
  cachedTokens: number,
  cachedSource: string | undefined,
  completionTokens: number,
  completionSource: string,
  reasoningTokens: number,
  reasoningSource: string | undefined,
) => {
  const inside = cacheInsideInput(
    variant,
    reportedPrompt,
    cachedTokens,
    completionTokens,
  );
  const thoughts = Math.min(reasoningTokens, completionTokens);
  return geminiBuckets({
    promptTokenCount: inside ? reportedPrompt : reportedPrompt + cachedTokens,
    promptSource,
    cachedContentTokenCount: cachedTokens,
    cachedSource,
    toolUsePromptTokenCount: 0,
    candidatesTokenCount: completionTokens - thoughts,
    candidatesSource:
      thoughts > 0 ? minus(completionSource, ["reasoning"]) : completionSource,
    thoughtsTokenCount: thoughts,
    thoughtsSource: reasoningSource,
  });
};

const GEMINI_VARIANTS: InputVariant[] = [
  {
    matches: (keys) =>
      keys.has("prompt_token_count") || keys.has("candidates_token_count"),
    read: (variant) => {
      const { keys } = variant;
      const cached = keys.get("cached_content_token_count");
      const toolUse = keys.get("tool_use_prompt_token_count");
      const thoughts = keys.get("thoughts_token_count");
      const reportedPrompt = keys.has("prompt_token_count")
        ? keys.get("prompt_token_count")
        : Math.max(0, keys.get("prompt_tokens") - toolUse);
      const candidates = keys.has("candidates_token_count")
        ? keys.get("candidates_token_count")
        : Math.max(0, keys.get("completion_tokens") - thoughts);
      const inside = cacheInsideInput(
        variant,
        reportedPrompt + toolUse,
        cached,
        null,
      );
      return geminiBuckets({
        promptTokenCount: inside ? reportedPrompt : reportedPrompt + cached,
        promptSource: keys.has("prompt_token_count")
          ? "prompt_token_count"
          : "prompt_tokens",
        cachedContentTokenCount: cached,
        cachedSource: "cached_content_token_count",
        toolUsePromptTokenCount: toolUse,
        candidatesTokenCount: candidates,
        candidatesSource: keys.has("candidates_token_count")
          ? "candidates_token_count"
          : "completion_tokens",
        thoughtsTokenCount: thoughts,
        thoughtsSource: "thoughts_token_count",
      });
    },
  },
  {
    matches: (keys) =>
      keys.has("prompt_tokens") || keys.has("completion_tokens"),
    read: (variant) => {
      const { keys } = variant;
      const cachedNames = [
        "cache_read_input_tokens",
        "prompt_tokens_details.cached_tokens",
        "cache_read.input_tokens",
      ];
      const reasoningNames = [
        "completion_tokens_details.reasoning_tokens",
        "reasoning_tokens",
        "reasoning.output_tokens",
      ];
      return geminiWithReasoning(
        variant,
        keys.get("prompt_tokens"),
        "prompt_tokens",
        keys.first(...cachedNames),
        keys.firstName(...cachedNames),
        keys.get("completion_tokens"),
        "completion_tokens",
        keys.first(...reasoningNames),
        keys.firstName(...reasoningNames),
      );
    },
  },
  {
    matches: (keys) => keys.has("input_tokens") || keys.has("output_tokens"),
    read: (variant) => {
      const { keys } = variant;
      const cachedNames = [
        "input_tokens_details.cached_tokens",
        "cache_read.input_tokens",
        "cache_read_input_tokens",
      ];
      const reasoningNames = [
        "output_tokens_details.reasoning_tokens",
        "reasoning.output_tokens",
      ];
      return geminiWithReasoning(
        variant,
        keys.get("input_tokens"),
        "input_tokens",
        keys.first(...cachedNames),
        keys.firstName(...cachedNames),
        keys.get("output_tokens"),
        "output_tokens",
        keys.first(...reasoningNames),
        keys.firstName(...reasoningNames),
      );
    },
  },
];

const VARIANTS_BY_FORMAT: Record<UsageFormatName, InputVariant[]> = {
  openai: OPENAI_VARIANTS,
  anthropic: ANTHROPIC_VARIANTS,
  gemini: GEMINI_VARIANTS,
  bedrock_converse: BEDROCK_VARIANTS,
};

const readFormat = (format: UsageFormatName, original: OriginalUsage) => {
  const providerKeys = usageKeysOf(original);
  const variant = VARIANTS_BY_FORMAT[format].find((candidate) =>
    candidate.matches(providerKeys),
  );
  if (!variant) return undefined;
  return variant.read({
    keys: original.nested ? usageKeysOf(withOuterKeys(original)) : providerKeys,
    outerPromptTokens: original.outerKeys.prompt_tokens ?? null,
  });
};

const fromClientTotals = (original: OriginalUsage) => {
  const clientKeys = original.nested ? original.outerKeys : original.keys;
  return cacheOutsideBuckets({
    input: clientKeys.prompt_tokens ?? 0,
    inputSource: "prompt_tokens",
    cacheRead: 0,
    writes: noWrites,
    output: clientKeys.completion_tokens ?? 0,
    outputSource: "completion_tokens",
  });
};

const toReading = (read: Buckets): UsageReading => {
  const tokens: Record<CostLineName, number> = {
    input: read.input,
    cache_read: read.cacheRead,
    cache_write: read.cacheWrite,
    output: read.output,
  };
  return {
    buckets: COST_LINE_NAMES.map((name) => ({
      name,
      tokens: tokens[name],
      source: read.sources[name],
    })),
    hints: read.hints,
  };
};

export const readSpanUsage = (
  usage: unknown,
  provider?: string | null,
  model?: string | null,
): UsageReading => {
  const original = originalUsageOf(countsOf(usage));
  const resolvedProvider = resolveUsageProvider(provider, model);
  if (!resolvedProvider || !Object.keys(original.keys).length) {
    return toReading(fromClientTotals(original));
  }
  const format = FORMAT_BY_PROVIDER[resolvedProvider] ?? "openai";
  // The SDK can log only a few provider details next to its own totals; the backend then reads both as OpenAI usage.
  const read =
    readFormat(format, original) ??
    (original.nested
      ? readFormat("openai", withOuterKeys(original))
      : undefined);
  return toReading(read ?? fromClientTotals(original));
};
