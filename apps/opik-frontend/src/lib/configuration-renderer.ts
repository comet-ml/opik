import isArray from "lodash/isArray";
import isBoolean from "lodash/isBoolean";
import isNumber from "lodash/isNumber";
import isObject from "lodash/isObject";
import isString from "lodash/isString";

export type ConfigValueType =
  | "prompt"
  | "number"
  | "string"
  | "boolean"
  | "tools"
  | "json_object"
  | "unknown";

const PROMPT_KEYS = [
  "prompt",
  "system_prompt",
  "user_prompt",
  "messages",
  "template",
];

const TOOLS_KEYS = ["tools", "functions", "tool_choice"];

const isMessagesArray = (value: unknown): boolean => {
  if (!isArray(value)) return false;
  return value.every(
    (item) =>
      isObject(item) &&
      "role" in (item as Record<string, unknown>) &&
      "content" in (item as Record<string, unknown>),
  );
};

export const EXCLUDED_CONFIG_KEYS = ["prompt", "examples"];

const REDUNDANT_WHEN_STRUCTURED = [
  "system_prompt",
  "user_prompt",
  "user_message",
];

export const shouldSkipRedundantKey = (
  key: string,
  hasStructuredPrompt: boolean,
): boolean => hasStructuredPrompt && REDUNDANT_WHEN_STRUCTURED.includes(key);

export const makeSkipKey =
  (hasStructuredPrompt: boolean) =>
  (key: string): boolean =>
    EXCLUDED_CONFIG_KEYS.includes(key) ||
    shouldSkipRedundantKey(key, hasStructuredPrompt);

export type FlatConfigEntry = {
  key: string;
  value: unknown;
  type: ConfigValueType;
};

export const flattenConfig = (
  config: Record<string, unknown>,
  skipKey?: (key: string) => boolean,
): FlatConfigEntry[] => {
  const result: FlatConfigEntry[] = [];

  const collect = (obj: Record<string, unknown>, prefix: string) => {
    for (const [key, value] of Object.entries(obj)) {
      if (!prefix && skipKey?.(key)) continue;

      const path = prefix ? `${prefix}.${key}` : key;
      const type = detectConfigValueType(key, value);

      if (type === "json_object" && isObject(value) && !isArray(value)) {
        collect(value as Record<string, unknown>, path);
      } else {
        result.push({ key: path, value, type });
      }
    }
  };

  collect(config, "");
  return result;
};

export const detectConfigValueType = (
  key: string,
  value: unknown,
): ConfigValueType => {
  const lowerKey = key.toLowerCase();

  if (PROMPT_KEYS.some((pk) => lowerKey.includes(pk))) {
    if (isString(value) || isMessagesArray(value)) {
      return "prompt";
    }
    if (isObject(value) && !isArray(value)) {
      const obj = value as Record<string, unknown>;
      if ("messages" in obj) {
        return "prompt";
      }
      // NamedPrompts: object where all values are message arrays
      const vals = Object.values(obj);
      if (vals.length > 0 && vals.every((v) => isMessagesArray(v))) {
        return "prompt";
      }
    }
  }

  if (TOOLS_KEYS.some((tk) => lowerKey.includes(tk))) {
    if (isArray(value)) {
      return "tools";
    }
  }

  if (isBoolean(value)) return "boolean";
  if (isNumber(value)) return "number";
  if (isString(value)) return "string";
  if (isArray(value) || isObject(value)) return "json_object";

  return "unknown";
};
