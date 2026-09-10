import {
  hasLegacyOpenInferenceOutputAttributes,
  isOpenInferenceField,
  resolveOpenInferenceHint,
} from "@/lib/openinference";
import { prettifyMessage } from "@/lib/traces";
import { detectLLMMessages } from "./detectLLMMessages";
import { getFormat } from "./providers/registry";
import {
  LLMMessageDescriptor,
  LLMMapperResult,
  LLMMessageFormatDetectionResult,
  LLMMessageFormat,
} from "./types";
import { MessageUsage, numericUsage } from "../usage";
import { mapOpenInferencePair } from "./providers/openinference/mapper";

type MapAndCombineMessagesConfig = {
  formatHint?: LLMMessageFormat;
  formatHintIsAuthoritative?: boolean;
  spanUsage?: MessageUsage;
  detections?: {
    input: LLMMessageFormatDetectionResult;
    output: LLMMessageFormatDetectionResult;
  };
};

export function mapAndCombineMessages(
  input: unknown,
  output: unknown,
  config: MapAndCombineMessagesConfig = {},
): LLMMapperResult {
  const pairHint = resolveOpenInferenceHint(undefined, input, output);
  const {
    formatHint = pairHint.detected ? "openinference" : undefined,
    spanUsage,
  } = config;
  const formatHintIsAuthoritative =
    config.formatHintIsAuthoritative ?? pairHint.authoritative;
  const withSpanUsage = (result: LLMMapperResult): LLMMapperResult => {
    const usage = numericUsage({ ...result.usage, ...numericUsage(spanUsage) });
    return {
      ...result,
      usage: Object.keys(usage).length > 0 ? usage : undefined,
    };
  };
  const inputDetection =
    config.detections?.input ??
    detectLLMMessages(input, {
      fieldType: "input",
      formatHintIsAuthoritative,
      formatHint,
    });
  const outputDetection =
    config.detections?.output ??
    detectLLMMessages(output, {
      fieldType: "output",
      formatHintIsAuthoritative,
      formatHint,
    });

  // Historical OpenInference spans can have every flattened output attribute in input,
  // while output contains only a raw {value, mime_type} fallback. Once either side proves
  // the format, let its pair-aware combiner inspect both raw fields.
  const hasNonOpenInferenceFormat = [inputDetection, outputDetection].some(
    (detection) =>
      detection.supported &&
      detection.format !== undefined &&
      detection.format !== "openinference",
  );
  if (
    !hasNonOpenInferenceFormat &&
    (inputDetection.format === "openinference" ||
      outputDetection.format === "openinference")
  ) {
    let currentOutput = output;
    // Use the same current-answer precedence as previews before recovering output
    // from historical input. Old {value, mime_type} envelopes remain fallbacks.
    if (
      formatHintIsAuthoritative !== false &&
      hasLegacyOpenInferenceOutputAttributes(input) &&
      output != null &&
      (typeof output === "object" ||
        typeof output === "string" ||
        typeof output === "number" ||
        typeof output === "boolean") &&
      !(typeof output === "object" && "value" in output) &&
      !isOpenInferenceField(output, "output", true, false)
    ) {
      const pretty = prettifyMessage(output, { type: "output" });
      if (
        pretty.prettified &&
        typeof pretty.message === "string" &&
        pretty.message.trim()
      ) {
        currentOutput = {
          messages: [{ role: "assistant", content: pretty.message }],
        };
      }
    }
    return withSpanUsage(
      mapOpenInferencePair(
        input,
        currentOutput,
        inputDetection.supported,
        outputDetection.supported,
      ),
    );
  }

  const inputResult = mapForDetection(
    input,
    inputDetection,
    "input",
    formatHint,
    formatHintIsAuthoritative,
  );
  const outputResult = mapForDetection(
    output,
    outputDetection,
    "output",
    formatHint,
    formatHintIsAuthoritative,
  );

  if (
    inputDetection.supported &&
    outputDetection.supported &&
    inputDetection.format === outputDetection.format &&
    inputDetection.format
  ) {
    const format = getFormat(inputDetection.format);
    if (format?.combiner && inputResult && outputResult) {
      return withSpanUsage(
        format.combiner(
          { raw: input, mapped: inputResult },
          { raw: output, mapped: outputResult },
        ),
      );
    }
  }

  const messages: LLMMessageDescriptor[] = [];
  if (inputResult) messages.push(...inputResult.messages);
  if (outputResult) messages.push(...outputResult.messages);
  return withSpanUsage({ messages, usage: outputResult?.usage });
}

function mapForDetection(
  data: unknown,
  detection: LLMMessageFormatDetectionResult,
  fieldType: "input" | "output",
  formatHint?: LLMMessageFormat,
  formatHintIsAuthoritative?: boolean,
): LLMMapperResult | null {
  if (!detection.supported || !detection.format) return null;
  const format = getFormat(detection.format);
  if (!format) return null;
  return format.mapper(data, {
    fieldType,
    formatHint,
    formatHintIsAuthoritative,
  });
}
