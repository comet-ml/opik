import { detectLLMMessages } from "./detectLLMMessages";
import { getFormat } from "./providers/registry";

export const resolveLLMMessageFormatHint = (
  provider?: string,
  traceProviders?: string[],
): string | undefined => {
  // Span providers may include integration formats such as "langchain".
  if (provider) return getFormat(provider)?.name;

  if (traceProviders?.length !== 1) return undefined;

  const [traceProvider] = traceProviders;
  return getFormat(traceProvider)?.name;
};

export const canShowLLMMessages = (
  inputData: unknown,
  outputData: unknown,
  formatHint?: string,
): boolean => {
  const input = detectLLMMessages(
    inputData,
    { fieldType: "input" },
    formatHint,
  );
  const output = detectLLMMessages(
    outputData,
    { fieldType: "output" },
    formatHint,
  );

  const hasValid = input.supported || output.supported;
  const hasInvalid =
    (!input.supported && !input.empty) || (!output.supported && !output.empty);

  return hasValid && !hasInvalid;
};
