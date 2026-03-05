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

export const detectConfigValueType = (
  key: string,
  value: unknown,
): ConfigValueType => {
  const lowerKey = key.toLowerCase();

  if (PROMPT_KEYS.some((pk) => lowerKey.includes(pk))) {
    if (isString(value) || isMessagesArray(value)) {
      return "prompt";
    }
    if (isObject(value) && "messages" in (value as Record<string, unknown>)) {
      return "prompt";
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
