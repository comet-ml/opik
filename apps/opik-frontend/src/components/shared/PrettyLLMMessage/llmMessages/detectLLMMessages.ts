import { LLMMessageFormatDetectionResult, LLMMessageFormat } from "./types";
import { getFormat, getAllFormats } from "./providers/registry";

/**
 * Detects if the provided data supports LLM messages pretty mode rendering.
 *
 * Detection strategy:
 * 1. If format hint is provided, try that format first
 * 2. Fall back to trying all registered formats
 *
 * @param data - The raw trace/span input or output data
 * @param prettifyConfig - Configuration indicating if this is input or output
 * @param formatHint - Optional format string hint from the span
 * @returns Detection result with supported flag and detected format
 */
export const detectLLMMessages = (
  data: unknown,
  prettifyConfig?: { fieldType?: "input" | "output" },
  formatHint?: string,
): LLMMessageFormatDetectionResult => {
  const isEmpty =
    data == null ||
    (typeof data === "object" && Object.keys(data as object).length === 0);

  if (isEmpty) {
    return { supported: false, empty: true };
  }

  // If format hint provided, try that first
  if (formatHint) {
    const format = getFormat(formatHint as LLMMessageFormat);
    if (format && format.detector(data, prettifyConfig)) {
      return {
        supported: true,
        format: format.name,
        confidence: "high",
      };
    }
  }

  // Auto-detect by trying all formats
  const formats = getAllFormats();
  for (const format of formats) {
    if (format.detector(data, prettifyConfig)) {
      return {
        supported: true,
        format: format.name,
        confidence: formatHint ? "low" : "medium",
      };
    }
  }

  return { supported: false };
};

export default detectLLMMessages;
