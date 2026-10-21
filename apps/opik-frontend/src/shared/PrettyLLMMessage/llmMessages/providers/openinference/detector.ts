import {
  hasLegacyOpenInferenceAttributes,
  isOpenInferenceField,
  resolveOpenInferenceHint,
} from "@/lib/openinference";
import { FormatDetector, FormatHintResolver } from "../../types";
import { detectOpenAIFormat } from "../openai/detector";

const hasOwnOpenInferenceStructure = (data: unknown): boolean => {
  if (hasLegacyOpenInferenceAttributes(data)) return true;
  if (!data || typeof data !== "object" || Array.isArray(data)) return false;
  const record = data as Record<string, unknown>;
  return (
    ["prompts", "tools", "function_call", "finish_reason"].some(
      (key) => key in record,
    ) ||
    (Array.isArray(record.messages) &&
      record.messages.some(
        (message) =>
          message &&
          typeof message === "object" &&
          ("contents" in message || "function_call" in message),
      ))
  );
};

export const resolveOpenInferenceFormatHint: FormatHintResolver = (
  input,
  output,
) => {
  const hint = resolveOpenInferenceHint(undefined, input, output);
  return hint.detected
    ? {
        formatHint: "openinference",
        formatHintIsAuthoritative: hint.authoritative,
      }
    : undefined;
};

export const detectOpenInferenceFormat: FormatDetector = (
  data,
  prettifyConfig,
) => {
  const fieldType = prettifyConfig?.fieldType;
  if (!fieldType) return false;

  // A sibling's hint must not take a native OpenAI payload away from its richer mapper.
  const hasMessageArray =
    Array.isArray(data) ||
    (data !== null &&
      typeof data === "object" &&
      "messages" in data &&
      Array.isArray(data.messages));
  if (
    hasMessageArray &&
    !hasOwnOpenInferenceStructure(data) &&
    detectOpenAIFormat(data, prettifyConfig)
  ) {
    return false;
  }

  return isOpenInferenceField(
    data,
    fieldType,
    prettifyConfig?.formatHint === "openinference",
    prettifyConfig?.formatHintIsAuthoritative,
  );
};
