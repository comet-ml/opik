import { detectLLMMessages } from "./detectLLMMessages";
import { getFormat } from "./providers/registry";
import {
  LLMMessageDescriptor,
  LLMMapperResult,
  LLMMessageFormatDetectionResult,
} from "./types";

export function mapAndCombineMessages(
  input: unknown,
  output: unknown,
): LLMMapperResult {
  const inputDetection = detectLLMMessages(input, { fieldType: "input" });
  const outputDetection = detectLLMMessages(output, { fieldType: "output" });

  const inputResult = mapForDetection(input, inputDetection, "input");
  const outputResult = mapForDetection(output, outputDetection, "output");

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
): LLMMapperResult | null {
  if (!detection.supported || !detection.format) return null;
  const format = getFormat(detection.format);
  if (!format) return null;
  return format.mapper(data, { fieldType });
}
