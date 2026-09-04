import {
  LLMMessageFormatDetectionResult,
  LLMMessageFormat,
  LLMMessagePrettifyConfig,
} from "./types";
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
  prettifyConfig?: LLMMessagePrettifyConfig,
  formatHint?: LLMMessageFormat,
): LLMMessageFormatDetectionResult => {
  const isEmpty =
    data == null ||
    (typeof data === "object" && Object.keys(data as object).length === 0);

  if (isEmpty) {
    return { supported: false, empty: true };
  }

  // If format hint provided, try that first
  if (formatHint) {
    const format = getFormat(formatHint);
    if (format && format.detector(data, { ...prettifyConfig, formatHint })) {
      const reliesOnAuthoritativeFallback =
        prettifyConfig?.formatHintIsAuthoritative === true &&
        !format.detector(data, {
          ...prettifyConfig,
          formatHint,
          formatHintIsAuthoritative: false,
        });

      // An exact OpenInference marker permits raw fallbacks. Prefer a provider mapper when
      // the field also has a provider-specific shape, since it can render richer messages.
      if (reliesOnAuthoritativeFallback) {
        const detectedFormat = getAllFormats().find(
          (candidate) =>
            candidate.name !== formatHint &&
            candidate.detector(data, {
              ...prettifyConfig,
              formatHint: undefined,
              formatHintIsAuthoritative: false,
            }),
        );
        if (detectedFormat) {
          return {
            supported: true,
            format: detectedFormat.name,
            confidence: "medium",
          };
        }
      }

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
    if (format.detector(data, { ...prettifyConfig, formatHint })) {
      return {
        supported: true,
        format: format.name,
        confidence: formatHint ? "low" : "medium",
      };
    }
  }

  return { supported: false };
};

export const canShowLLMMessages = (
  input: LLMMessageFormatDetectionResult,
  output: LLMMessageFormatDetectionResult,
  allowPartialFields: boolean = false,
): boolean => {
  const hasSupportedField = input.supported || output.supported;
  if (allowPartialFields) return hasSupportedField;

  const hasUnsupportedField =
    (!input.supported && !input.empty) || (!output.supported && !output.empty);
  return hasSupportedField && !hasUnsupportedField;
};

export default detectLLMMessages;
