export const OPENINFERENCE_SPAN_KIND = "openinference.span.kind";

export type OpenInferenceFieldType = "input" | "output";

export type OpenInferenceToolCall = {
  id?: string;
  function?: {
    name?: string;
    arguments?: string;
  };
  reasoning_signature?: string;
};

export type OpenInferenceContent = {
  type?: string;
  text?: string;
  id?: string;
  signature?: string;
  data?: string;
  encrypted_content?: string;
  image?: { url?: string };
  audio?: { url?: string; mime_type?: string; transcript?: string };
  tool_call?: OpenInferenceToolCall;
};

export type OpenInferenceMessage = {
  role?: string;
  content?: unknown;
  contents?: OpenInferenceContent[];
  tool_calls?: OpenInferenceToolCall[];
  name?: string;
  tool_call_id?: string;
  function_call?: unknown;
};

export type ParsedOpenInferenceFields = {
  inputMessages: OpenInferenceMessage[];
  outputMessages: OpenInferenceMessage[];
  prompts: string[];
  choices: string[];
  tools: unknown[];
  finishReason?: string;
  functionCall?: unknown;
  inputFallback?: unknown;
  outputFallback?: unknown;
  hasOpenInferenceData: boolean;
};

type UnknownRecord = Record<string, unknown>;

const MESSAGE_ATTRIBUTE_RE =
  /^llm\.(input|output)_messages\.([^.]+)\.message\.(.+)$/;
const CONTENT_ATTRIBUTE_RE = /^contents\.([^.]+)\.(.+)$/;
const TOOL_CALL_ATTRIBUTE_RE = /^tool_calls\.([^.]+)\.tool_call\.(.+)$/;
const TOOL_ATTRIBUTE_RE =
  /^llm\.tools\.([^.]+)\.tool\.(name|description|json_schema)$/;
const PROMPT_ATTRIBUTE_RE = /^llm\.prompts\.([^.]+)\.prompt\.text$/;
const CHOICE_ATTRIBUTE_RE = /^llm\.choices\.([^.]+)\.completion\.text$/;

const DISPLAYABLE_LEGACY_PREFIXES = [
  "llm.input_messages.",
  "llm.output_messages.",
  "llm.prompts.",
  "llm.choices.",
  "llm.tools.",
];

const DISPLAYABLE_LEGACY_KEYS = new Set([
  OPENINFERENCE_SPAN_KIND,
  "llm.finish_reason",
  "llm.function_call",
]);

const isRecord = (value: unknown): value is UnknownRecord =>
  value !== null && typeof value === "object" && !Array.isArray(value);

const hasOwn = (value: UnknownRecord, key: string) =>
  Object.prototype.hasOwnProperty.call(value, key);

const parseIndex = (value: string): number | undefined => {
  if (!/^\d+$/.test(value)) return undefined;
  const index = Number(value);
  return Number.isSafeInteger(index) ? index : undefined;
};

const sortedValues = <T>(values: Map<number, T>): T[] =>
  [...values.entries()]
    .sort(([left], [right]) => left - right)
    .map(([, value]) => value);

const toStringValue = (value: unknown): string | undefined =>
  typeof value === "string" ? value : undefined;

const toArguments = (value: unknown): string | undefined => {
  if (typeof value === "string") return value;
  if (value === undefined) return undefined;
  try {
    return JSON.stringify(value);
  } catch {
    return undefined;
  }
};

const parseMaybeJson = (value: unknown): unknown => {
  if (typeof value !== "string") return value;
  try {
    return JSON.parse(value);
  } catch {
    return value;
  }
};

class ToolCallBuilder {
  private readonly value: OpenInferenceToolCall = {};

  accept(path: string, rawValue: unknown): boolean {
    if (path === "id") {
      const id = toStringValue(rawValue);
      if (id === undefined) return false;
      this.value.id = id;
      return true;
    }
    if (path === "reasoning_signature") {
      const signature = toStringValue(rawValue);
      if (signature === undefined) return false;
      this.value.reasoning_signature = signature;
      return true;
    }
    if (path === "function.name") {
      const name = toStringValue(rawValue);
      if (name === undefined) return false;
      this.value.function ??= {};
      this.value.function.name = name;
      return true;
    }
    if (path === "function.arguments") {
      const args = toArguments(rawValue);
      if (args === undefined) return false;
      this.value.function ??= {};
      this.value.function.arguments = args;
      return true;
    }
    return false;
  }

  build(): OpenInferenceToolCall | undefined {
    return Object.keys(this.value).length > 0 ? this.value : undefined;
  }
}

class ContentBuilder {
  private readonly value: OpenInferenceContent = {};
  private readonly toolCall = new ToolCallBuilder();

  accept(path: string, rawValue: unknown): boolean {
    const scalarFields: Record<string, keyof OpenInferenceContent> = {
      "message_content.type": "type",
      "message_content.text": "text",
      "message_content.id": "id",
      "message_content.signature": "signature",
      "message_content.data": "data",
      "message_content.encrypted_content": "encrypted_content",
    };
    const scalarField = scalarFields[path];
    if (scalarField) {
      const value = toStringValue(rawValue);
      if (value === undefined) return false;
      Object.assign(this.value, { [scalarField]: value });
      return true;
    }

    if (path === "message_content.image.image.url") {
      const url = toStringValue(rawValue);
      if (url === undefined) return false;
      this.value.image = { url };
      return true;
    }

    const audioPrefix = "message_content.audio.audio.";
    if (path.startsWith(audioPrefix)) {
      const field = path.slice(audioPrefix.length);
      if (!(["url", "mime_type", "transcript"] as string[]).includes(field))
        return false;
      const value = toStringValue(rawValue);
      if (value === undefined) return false;
      this.value.audio ??= {};
      Object.assign(this.value.audio, { [field]: value });
      return true;
    }

    if (path.startsWith("tool_call.")) {
      return this.toolCall.accept(path.slice("tool_call.".length), rawValue);
    }
    return false;
  }

  build(): OpenInferenceContent | undefined {
    const toolCall = this.toolCall.build();
    if (toolCall) this.value.tool_call = toolCall;
    return Object.keys(this.value).length > 0 ? this.value : undefined;
  }
}

class MessageBuilder {
  private readonly value: OpenInferenceMessage = {};
  private readonly contents = new Map<number, ContentBuilder>();
  private readonly toolCalls = new Map<number, ToolCallBuilder>();
  private readonly functionCall: UnknownRecord = {};

  accept(path: string, rawValue: unknown): boolean {
    if (["role", "name", "tool_call_id"].includes(path)) {
      const value = toStringValue(rawValue);
      if (value === undefined) return false;
      Object.assign(this.value, { [path]: value });
      return true;
    }
    if (path === "content") {
      this.value.content = rawValue;
      return true;
    }
    if (path === "function_call_name") {
      const value = toStringValue(rawValue);
      if (value === undefined) return false;
      this.functionCall.name = value;
      return true;
    }
    if (path === "function_call_arguments_json") {
      this.functionCall.arguments = parseMaybeJson(rawValue);
      return true;
    }

    const contentMatch = path.match(CONTENT_ATTRIBUTE_RE);
    if (contentMatch) {
      const index = parseIndex(contentMatch[1]);
      if (index === undefined) return false;
      let content = this.contents.get(index);
      if (!content) {
        content = new ContentBuilder();
        this.contents.set(index, content);
      }
      return content.accept(contentMatch[2], rawValue);
    }

    const toolCallMatch = path.match(TOOL_CALL_ATTRIBUTE_RE);
    if (toolCallMatch) {
      const index = parseIndex(toolCallMatch[1]);
      if (index === undefined) return false;
      let toolCall = this.toolCalls.get(index);
      if (!toolCall) {
        toolCall = new ToolCallBuilder();
        this.toolCalls.set(index, toolCall);
      }
      return toolCall.accept(toolCallMatch[2], rawValue);
    }
    return false;
  }

  build(): OpenInferenceMessage | undefined {
    const contents = sortedValues(this.contents)
      .map((content) => content.build())
      .filter((content): content is OpenInferenceContent => Boolean(content));
    const toolCalls = sortedValues(this.toolCalls)
      .map((toolCall) => toolCall.build())
      .filter((toolCall): toolCall is OpenInferenceToolCall =>
        Boolean(toolCall),
      );
    if (contents.length > 0) this.value.contents = contents;
    if (toolCalls.length > 0) this.value.tool_calls = toolCalls;
    if (Object.keys(this.functionCall).length > 0) {
      this.value.function_call = this.functionCall;
    }
    return Object.keys(this.value).length > 0 ? this.value : undefined;
  }
}

type LegacyAccumulator = {
  inputMessages: Map<number, MessageBuilder>;
  outputMessages: Map<number, MessageBuilder>;
  prompts: Map<number, string>;
  choices: Map<number, string>;
  tools: Map<number, UnknownRecord>;
  finishReason?: string;
  functionCall?: unknown;
  found: boolean;
};

const createLegacyAccumulator = (): LegacyAccumulator => ({
  inputMessages: new Map(),
  outputMessages: new Map(),
  prompts: new Map(),
  choices: new Map(),
  tools: new Map(),
  found: false,
});

const acceptLegacyAttribute = (
  accumulator: LegacyAccumulator,
  key: string,
  value: unknown,
) => {
  const messageMatch = key.match(MESSAGE_ATTRIBUTE_RE);
  if (messageMatch) {
    accumulator.found = true;
    const index = parseIndex(messageMatch[2]);
    if (index === undefined) return;
    const messages =
      messageMatch[1] === "input"
        ? accumulator.inputMessages
        : accumulator.outputMessages;
    let message = messages.get(index);
    if (!message) {
      message = new MessageBuilder();
      messages.set(index, message);
    }
    message.accept(messageMatch[3], value);
    return;
  }

  const toolMatch = key.match(TOOL_ATTRIBUTE_RE);
  if (toolMatch) {
    accumulator.found = true;
    const index = parseIndex(toolMatch[1]);
    if (index === undefined) return;
    const tool = accumulator.tools.get(index) ?? {};
    tool[toolMatch[2]] =
      toolMatch[2] === "json_schema" ? parseMaybeJson(value) : value;
    accumulator.tools.set(index, tool);
    return;
  }

  const promptMatch = key.match(PROMPT_ATTRIBUTE_RE);
  if (promptMatch) {
    accumulator.found = true;
    const index = parseIndex(promptMatch[1]);
    const text = toStringValue(value);
    if (index !== undefined && text !== undefined)
      accumulator.prompts.set(index, text);
    return;
  }

  const choiceMatch = key.match(CHOICE_ATTRIBUTE_RE);
  if (choiceMatch) {
    accumulator.found = true;
    const index = parseIndex(choiceMatch[1]);
    const text = toStringValue(value);
    if (index !== undefined && text !== undefined)
      accumulator.choices.set(index, text);
    return;
  }

  if (key === "llm.finish_reason") {
    accumulator.found = true;
    accumulator.finishReason = toStringValue(value);
  } else if (key === "llm.function_call") {
    accumulator.found = true;
    accumulator.functionCall = parseMaybeJson(value);
  }
};

const collectLegacy = (accumulator: LegacyAccumulator, data: unknown): void => {
  if (!isRecord(data)) return;
  Object.entries(data).forEach(([key, value]) =>
    acceptLegacyAttribute(accumulator, key, value),
  );
};

const parseCanonicalToolCall = (
  value: unknown,
): OpenInferenceToolCall | undefined => {
  if (!isRecord(value)) return undefined;
  const toolCall: OpenInferenceToolCall = {};
  if (typeof value.id === "string") toolCall.id = value.id;
  if (typeof value.reasoning_signature === "string") {
    toolCall.reasoning_signature = value.reasoning_signature;
  }
  if (isRecord(value.function)) {
    const fn: NonNullable<OpenInferenceToolCall["function"]> = {};
    if (typeof value.function.name === "string") fn.name = value.function.name;
    const args = toArguments(value.function.arguments);
    if (args !== undefined) fn.arguments = args;
    if (Object.keys(fn).length > 0) toolCall.function = fn;
  }
  return Object.keys(toolCall).length > 0 ? toolCall : undefined;
};

const parseCanonicalContent = (
  value: unknown,
): OpenInferenceContent | undefined => {
  if (!isRecord(value)) return undefined;
  const content: OpenInferenceContent = {};
  (
    ["type", "text", "id", "signature", "data", "encrypted_content"] as const
  ).forEach((key) => {
    const fieldValue = value[key];
    if (typeof fieldValue === "string") content[key] = fieldValue;
  });
  if (isRecord(value.image) && typeof value.image.url === "string") {
    content.image = { url: value.image.url };
  }
  if (isRecord(value.audio)) {
    const audioValue = value.audio;
    const audio: NonNullable<OpenInferenceContent["audio"]> = {};
    (["url", "mime_type", "transcript"] as const).forEach((key) => {
      const fieldValue = audioValue[key];
      if (typeof fieldValue === "string") audio[key] = fieldValue;
    });
    if (Object.keys(audio).length > 0) content.audio = audio;
  }
  const toolCall = parseCanonicalToolCall(value.tool_call);
  if (toolCall) content.tool_call = toolCall;
  return Object.keys(content).length > 0 ? content : undefined;
};

const parseCanonicalMessage = (
  value: unknown,
): OpenInferenceMessage | undefined => {
  if (!isRecord(value)) return undefined;
  const message: OpenInferenceMessage = {};
  if (typeof value.role === "string") message.role = value.role;
  if (hasOwn(value, "content")) message.content = value.content;
  if (typeof value.name === "string") message.name = value.name;
  if (typeof value.tool_call_id === "string") {
    message.tool_call_id = value.tool_call_id;
  }
  if (Array.isArray(value.contents)) {
    const contents = value.contents
      .map(parseCanonicalContent)
      .filter((item): item is OpenInferenceContent => Boolean(item));
    if (contents.length > 0) message.contents = contents;
  }
  if (Array.isArray(value.tool_calls)) {
    const toolCalls = value.tool_calls
      .map(parseCanonicalToolCall)
      .filter((item): item is OpenInferenceToolCall => Boolean(item));
    if (toolCalls.length > 0) message.tool_calls = toolCalls;
  }
  if (hasOwn(value, "function_call")) {
    message.function_call = value.function_call;
  }
  return Object.keys(message).length > 0 ? message : undefined;
};

const parseCanonicalMessages = (data: unknown): OpenInferenceMessage[] => {
  if (!isRecord(data) || !Array.isArray(data.messages)) return [];
  return data.messages
    .map(parseCanonicalMessage)
    .filter((message): message is OpenInferenceMessage => Boolean(message));
};

const extractTexts = (data: unknown, key: "prompts" | "choices"): string[] => {
  if (!isRecord(data) || !Array.isArray(data[key])) return [];
  const items = data[key] as unknown[];
  const legacyKey = key === "prompts" ? "prompt.text" : "completion.text";
  return items
    .map((item) => {
      if (typeof item === "string") return item;
      if (!isRecord(item)) return undefined;
      return toStringValue(item.text) ?? toStringValue(item[legacyKey]);
    })
    .filter((item): item is string => item !== undefined);
};

const extractFallback = (data: unknown): unknown => {
  if (!isRecord(data)) return data;
  return hasOwn(data, "value") ? data.value : undefined;
};

const dedupe = <T>(values: T[]): T[] => {
  const fingerprints = new Set<string>();
  return values.filter((value) => {
    let fingerprint: string;
    try {
      fingerprint = JSON.stringify(value) ?? String(value);
    } catch {
      return true;
    }
    if (fingerprints.has(fingerprint)) return false;
    fingerprints.add(fingerprint);
    return true;
  });
};

export const hasLegacyOpenInferenceAttributes = (data: unknown): boolean => {
  if (!isRecord(data)) return false;
  return Object.keys(data).some(
    (key) =>
      DISPLAYABLE_LEGACY_KEYS.has(key) ||
      DISPLAYABLE_LEGACY_PREFIXES.some((prefix) => key.startsWith(prefix)),
  );
};

export const hasLegacyOpenInferenceOutputAttributes = (
  data: unknown,
): boolean => {
  if (!isRecord(data)) return false;
  return Object.keys(data).some(
    (key) =>
      key.startsWith("llm.output_messages.") ||
      key.startsWith("llm.choices.") ||
      key === "llm.finish_reason" ||
      key === "llm.function_call",
  );
};

const hasCanonicalDisplayData = (
  data: unknown,
  fieldType: OpenInferenceFieldType,
): boolean => {
  if (!isRecord(data)) return false;
  if (Array.isArray(data.messages) && data.messages.some(parseCanonicalMessage))
    return true;
  if (fieldType === "input") {
    return (
      extractTexts(data, "prompts").length > 0 ||
      (Array.isArray(data.tools) && data.tools.length > 0)
    );
  }
  return (
    extractTexts(data, "choices").length > 0 || hasOwn(data, "function_call")
  );
};

/**
 * A hint selects OpenInference ahead of generic OpenAI detection, but does not make a raw
 * {@code {value: ...}} object displayable on its own.
 */
export const isOpenInferenceField = (
  data: unknown,
  fieldType: OpenInferenceFieldType,
  hinted: boolean,
): boolean =>
  hasLegacyOpenInferenceAttributes(data) ||
  (hinted && hasCanonicalDisplayData(data, fieldType));

export const hasOpenInferenceHint = (
  metadata: unknown,
  input: unknown,
  output?: unknown,
): boolean => {
  const metadataMarker =
    isRecord(metadata) && hasOwn(metadata, OPENINFERENCE_SPAN_KIND);
  return (
    metadataMarker ||
    hasLegacyOpenInferenceAttributes(input) ||
    hasLegacyOpenInferenceAttributes(output)
  );
};

export const parseOpenInferenceFields = (
  input: unknown,
  output: unknown,
): ParsedOpenInferenceFields => {
  const legacy = createLegacyAccumulator();
  collectLegacy(legacy, input);
  collectLegacy(legacy, output);

  const canonicalInputMessages = parseCanonicalMessages(input);
  const canonicalOutputMessages = parseCanonicalMessages(output);
  const legacyInputMessages = sortedValues(legacy.inputMessages)
    .map((message) => message.build())
    .filter((message): message is OpenInferenceMessage => Boolean(message));
  const legacyOutputMessages = sortedValues(legacy.outputMessages)
    .map((message) => message.build())
    .filter((message): message is OpenInferenceMessage => Boolean(message));

  const inputRecord = isRecord(input) ? input : undefined;
  const outputRecord = isRecord(output) ? output : undefined;
  const prompts = dedupe([
    ...extractTexts(input, "prompts"),
    ...sortedValues(legacy.prompts),
  ]);
  const choices = dedupe([
    ...extractTexts(output, "choices"),
    ...sortedValues(legacy.choices),
  ]);
  const canonicalTools =
    inputRecord && Array.isArray(inputRecord.tools) ? inputRecord.tools : [];
  const tools = dedupe([...canonicalTools, ...sortedValues(legacy.tools)]);
  const finishReason =
    (outputRecord && toStringValue(outputRecord.finish_reason)) ??
    legacy.finishReason;
  const functionCall =
    (outputRecord && outputRecord.function_call) ?? legacy.functionCall;

  return {
    inputMessages: dedupe([...canonicalInputMessages, ...legacyInputMessages]),
    outputMessages: dedupe([
      ...canonicalOutputMessages,
      ...legacyOutputMessages,
    ]),
    prompts,
    choices,
    tools,
    finishReason,
    functionCall,
    inputFallback: extractFallback(input),
    outputFallback: extractFallback(output),
    hasOpenInferenceData:
      legacy.found ||
      hasLegacyOpenInferenceAttributes(input) ||
      hasLegacyOpenInferenceAttributes(output) ||
      hasCanonicalDisplayData(input, "input") ||
      hasCanonicalDisplayData(output, "output"),
  };
};

const messageText = (
  message: OpenInferenceMessage | undefined,
): string | undefined => {
  if (!message) return undefined;
  if (typeof message.content === "string" && message.content.length > 0) {
    return message.content;
  }
  if (message.contents) {
    const visible = message.contents
      .map((content) => content.text ?? content.audio?.transcript)
      .filter((value): value is string => Boolean(value));
    if (visible.length > 0) return visible.join("\n\n");
  }
  return undefined;
};

const extractParsedPrettyText = (
  parsed: ParsedOpenInferenceFields,
  fieldType: OpenInferenceFieldType,
): string | undefined => {
  const messages =
    fieldType === "input" ? parsed.inputMessages : parsed.outputMessages;
  const preferredRoles =
    fieldType === "input"
      ? new Set(["user", "human"])
      : new Set(["assistant", "model", "ai", "agent"]);
  const preferred = [...messages]
    .reverse()
    .find(
      (message) =>
        message.role && preferredRoles.has(message.role.toLowerCase()),
    );
  const text = messageText(preferred ?? messages[messages.length - 1]);
  if (text) return text;

  const completions = fieldType === "input" ? parsed.prompts : parsed.choices;
  if (completions.length > 0) return completions[completions.length - 1];

  const fallback =
    fieldType === "input" ? parsed.inputFallback : parsed.outputFallback;
  return typeof fallback === "string" ? fallback : undefined;
};

/** Pure short-text extraction shared by table/annotation/export Pretty ✨ views. */
export const extractOpenInferencePrettyText = (
  data: unknown,
  fieldType: OpenInferenceFieldType,
): string | undefined => {
  const hasRoleBasedMessages =
    isRecord(data) &&
    Array.isArray(data.messages) &&
    data.messages.some(
      (message) => isRecord(message) && typeof message.role === "string",
    );
  const hasCompletionData =
    isRecord(data) &&
    (extractTexts(data, "prompts").length > 0 ||
      extractTexts(data, "choices").length > 0);
  if (
    !hasRoleBasedMessages &&
    !hasCompletionData &&
    !hasLegacyOpenInferenceAttributes(data)
  ) {
    return undefined;
  }

  return extractParsedPrettyText(
    parseOpenInferenceFields(
      fieldType === "input" ? data : undefined,
      fieldType === "output" ? data : undefined,
    ),
    fieldType,
  );
};

/** Recovers output attributes that older ingestion stored alongside the input raw value. */
export const extractLegacyOpenInferenceOutputText = (
  storedInput: unknown,
): string | undefined => {
  if (!hasLegacyOpenInferenceOutputAttributes(storedInput)) return undefined;
  return extractParsedPrettyText(
    parseOpenInferenceFields(storedInput, undefined),
    "output",
  );
};
