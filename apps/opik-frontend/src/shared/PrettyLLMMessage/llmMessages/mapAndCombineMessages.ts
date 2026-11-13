import { detectLLMMessagePair } from "./detectLLMMessages";
import { getFormat, getAllFormats } from "./providers/registry";
import {
  LLMMessageDescriptor,
  LLMMapperResult,
  LLMMessageFormatDetectionResult,
  LLMMessageFormat,
  LLMMessagePairDetections,
} from "./types";
import { MessageUsage, numericUsage, getDisplayUsage } from "../usage";

type MapAndCombineMessagesConfig = {
  formatHint?: LLMMessageFormat;
  formatHintIsAuthoritative?: boolean;
  spanUsage?: MessageUsage;
  detections?: LLMMessagePairDetections;
};

export function mapAndCombineMessages(
  input: unknown,
  output: unknown,
  config: MapAndCombineMessagesConfig = {},
): LLMMapperResult {
  const { spanUsage } = config;
  const { prettifyConfig, inputDetection, outputDetection } =
    detectLLMMessagePair(input, output, config, config.detections);
  const { formatHint, formatHintIsAuthoritative } = prettifyConfig;
  const withSpanUsage = (result: LLMMapperResult): LLMMapperResult => {
    const spanDisplayUsage = Object.fromEntries(
      getDisplayUsage(spanUsage).map(({ key, value }) => [key, value]),
    );
    const usage = numericUsage({
      ...result.usage,
      ...numericUsage(spanUsage),
      ...spanDisplayUsage,
    });
    return {
      ...result,
      usage: Object.keys(usage).length > 0 ? usage : undefined,
    };
  };
  for (const format of getAllFormats()) {
    const result = format.pairMapper?.(
      { raw: input, detection: inputDetection },
      { raw: output, detection: outputDetection },
      prettifyConfig,
    );
    if (result) return withSpanUsage(result);
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
