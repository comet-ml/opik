const MAX_DEPTH = 4;
const MAX_ARRAY_ITEMS = 20;
const MAX_OBJECT_KEYS = 50;

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null && !Array.isArray(value);

const sanitizeValue = (
  value: unknown,
  depth = 0,
  seen: WeakSet<object> = new WeakSet()
): unknown => {
  if (value === null || value === undefined) {
    return value;
  }

  if (typeof value === "string" || typeof value === "number" || typeof value === "boolean") {
    return value;
  }

  if (typeof value === "bigint") {
    return value.toString();
  }

  if (typeof value === "function") {
    const functionName = value.name || "anonymous";
    return `[Function: ${functionName}]`;
  }

  if (value instanceof Date) {
    return value.toISOString();
  }

  if (depth >= MAX_DEPTH) {
    return "[MaxDepth]";
  }

  if (Array.isArray(value)) {
    return value.slice(0, MAX_ARRAY_ITEMS).map((item) => sanitizeValue(item, depth + 1, seen));
  }

  if (typeof value === "object") {
    if (seen.has(value)) {
      return "[Circular]";
    }
    seen.add(value);

    const result: Record<string, unknown> = {};
    const entries = Object.entries(value).slice(0, MAX_OBJECT_KEYS);
    for (const [key, nestedValue] of entries) {
      result[key] = sanitizeValue(nestedValue, depth + 1, seen);
    }
    return result;
  }

  return String(value);
};

const getNumber = (source: Record<string, unknown>, keys: string[]): number | undefined => {
  for (const key of keys) {
    const value = source[key];
    if (typeof value === "number" && Number.isFinite(value)) {
      return value;
    }
  }
  return undefined;
};

const flattenNumericValues = (
  source: unknown,
  prefix: string,
  output: Record<string, number>
): void => {
  if (!isRecord(source)) {
    return;
  }

  for (const [key, value] of Object.entries(source)) {
    const nextPrefix = `${prefix}.${key}`;

    if (typeof value === "number" && Number.isFinite(value)) {
      output[nextPrefix] = value;
      continue;
    }

    if (isRecord(value)) {
      flattenNumericValues(value, nextPrefix, output);
    }
  }
};

const parseScopeAndConfig = (
  methodName: string,
  args: unknown[]
): {
  scope: Record<string, unknown> | undefined;
  config: Record<string, unknown> | undefined;
} => {
  const firstCandidate = args[1];
  const secondCandidate = args[2];

  if (methodName.startsWith("spawn")) {
    return {
      scope: isRecord(firstCandidate) ? (sanitizeValue(firstCandidate) as Record<string, unknown>) : undefined,
      config: isRecord(args[0]) ? (sanitizeValue(args[0]) as Record<string, unknown>) : undefined,
    };
  }

  if (!isRecord(firstCandidate)) {
    return {
      scope: undefined,
      config: isRecord(secondCandidate)
        ? (sanitizeValue(secondCandidate) as Record<string, unknown>)
        : undefined,
    };
  }

  const firstKeys = new Set(Object.keys(firstCandidate));
  const likelyConfigKeys = [
    "model",
    "premise",
    "system",
    "maxTokens",
    "reasoningEffort",
    "cacheTTL",
    "listener",
    "listenerIncludeUsage",
    "onUsage",
    "parentCallId",
  ];
  const looksLikeConfig = likelyConfigKeys.some((key) => firstKeys.has(key));

  if (looksLikeConfig && !isRecord(secondCandidate)) {
    return {
      scope: undefined,
      config: sanitizeValue(firstCandidate) as Record<string, unknown>,
    };
  }

  return {
    scope: sanitizeValue(firstCandidate) as Record<string, unknown>,
    config: isRecord(secondCandidate)
      ? (sanitizeValue(secondCandidate) as Record<string, unknown>)
      : undefined,
  };
};

export const parseInputArgs = (
  methodName: string,
  args: unknown[]
): Record<string, unknown> | undefined => {
  const prompt = args[0];
  const { scope, config } = parseScopeAndConfig(methodName, args);

  if (methodName.startsWith("spawn")) {
    const spawnInput: Record<string, unknown> = {};
    if (config) {
      spawnInput.config = config;
    }
    if (scope) {
      spawnInput.scope = scope;
    }
    return Object.keys(spawnInput).length > 0 ? spawnInput : undefined;
  }

  if (methodName === "agentic" || methodName === "agenticTransformation") {
    const agenticInput: Record<string, unknown> = {
      prompt: sanitizeValue(prompt),
    };
    if (scope) {
      agenticInput.scope = scope;
    }
    if (config) {
      agenticInput.config = config;
    }
    return agenticInput;
  }

  if (methodName === "call" || methodName === "callTransformation") {
    const callInput: Record<string, unknown> = {
      prompt: sanitizeValue(prompt),
    };
    if (scope) {
      callInput.scope = scope;
    }
    if (config) {
      callInput.config = config;
    }
    return callInput;
  }

  const fallbackInput: Record<string, unknown> = {};
  args.forEach((arg, index) => {
    fallbackInput[`arg${index}`] = sanitizeValue(arg);
  });

  return Object.keys(fallbackInput).length > 0 ? fallbackInput : undefined;
};

export const parseOutput = (result: unknown): Record<string, unknown> | undefined => {
  if (result === undefined) {
    return undefined;
  }
  if (isRecord(result)) {
    return sanitizeValue(result) as Record<string, unknown>;
  }

  return {
    output: sanitizeValue(result),
  };
};

export const parseUsage = (usageObject: unknown): Record<string, number> | undefined => {
  if (!isRecord(usageObject)) {
    return undefined;
  }

  const promptTokens = getNumber(usageObject, [
    "prompt_tokens",
    "input_tokens",
    "promptTokens",
    "inputTokens",
  ]);
  const completionTokens = getNumber(usageObject, [
    "completion_tokens",
    "output_tokens",
    "completionTokens",
    "outputTokens",
  ]);
  let totalTokens = getNumber(usageObject, ["total_tokens", "totalTokens"]);

  if (
    totalTokens === undefined &&
    promptTokens !== undefined &&
    completionTokens !== undefined
  ) {
    totalTokens = promptTokens + completionTokens;
  }

  const usage: Record<string, number> = {};
  if (promptTokens !== undefined) {
    usage.prompt_tokens = promptTokens;
  }
  if (completionTokens !== undefined) {
    usage.completion_tokens = completionTokens;
  }
  if (totalTokens !== undefined) {
    usage.total_tokens = totalTokens;
  }

  flattenNumericValues(usageObject, "original_usage", usage);

  return Object.keys(usage).length > 0 ? usage : undefined;
};

export const parseUsageFromAgent = (agent: unknown): Record<string, number> | undefined => {
  if (!isRecord(agent)) {
    return undefined;
  }
  const maybeLastUsage = agent.lastUsage;
  if (typeof maybeLastUsage !== "function") {
    return undefined;
  }

  try {
    return parseUsage(maybeLastUsage.call(agent));
  } catch {
    return undefined;
  }
};

