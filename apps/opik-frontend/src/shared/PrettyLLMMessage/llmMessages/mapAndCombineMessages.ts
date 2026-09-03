import { detectLLMMessages } from "./detectLLMMessages";
import { getFormat } from "./providers/registry";
import {
  LLMMessageDescriptor,
  LLMMapperResult,
  LLMMessageFormatDetectionResult,
  LLMMessageFormat,
} from "./types";
import { PrettyLLMMessageUsageProps } from "../types";

export function mapAndCombineMessages(
  input: unknown,
  output: unknown,
  formatHint?: LLMMessageFormat,
  spanUsage?: PrettyLLMMessageUsageProps["usage"],
): LLMMapperResult {
  const inputDetection = detectLLMMessages(
    input,
    { fieldType: "input" },
    formatHint,
  );
  const outputDetection = detectLLMMessages(
    output,
    { fieldType: "output" },
    formatHint,
  );

  // Historical OpenInference spans can have every flattened output attribute in input,
  // while output contains only a raw {value, mime_type} fallback. Once either side proves
  // the format, let its pair-aware combiner inspect both raw fields.
  if (
    inputDetection.format === "openinference" ||
    outputDetection.format === "openinference"
  ) {
    const format = getFormat("openinference");
    if (format?.combiner) {
      const mapped = format.combiner(
        {
          raw: input,
          mapped: format.mapper(input, {
            fieldType: "input",
            formatHint,
          }),
        },
        {
          raw: output,
          mapped: format.mapper(output, {
            fieldType: "output",
            formatHint,
          }),
        },
      );
      return { ...mapped, usage: spanUsage ?? mapped.usage };
    }
  }

  const inputResult = mapForDetection(
    input,
    inputDetection,
    "input",
    formatHint,
  );
  const outputResult = mapForDetection(
    output,
    outputDetection,
    "output",
    formatHint,
  );

  if (
    inputDetection.supported &&
    outputDetection.supported &&
    inputDetection.format === outputDetection.format &&
    inputDetection.format
  ) {
    const format = getFormat(inputDetection.format);
    if (format?.combiner && inputResult && outputResult) {
      return format.combiner(
        { raw: input, mapped: inputResult },
        { raw: output, mapped: outputResult },
      );
    }
  }

  const messages: LLMMessageDescriptor[] = [];
  if (inputResult) messages.push(...inputResult.messages);
  if (outputResult) messages.push(...outputResult.messages);
  return { messages, usage: outputResult?.usage };
}

function mapForDetection(
  data: unknown,
  detection: LLMMessageFormatDetectionResult,
  fieldType: "input" | "output",
  formatHint?: LLMMessageFormat,
): LLMMapperResult | null {
  if (!detection.supported || !detection.format) return null;
  const format = getFormat(detection.format);
  if (!format) return null;
  return format.mapper(data, { fieldType, formatHint });
}
