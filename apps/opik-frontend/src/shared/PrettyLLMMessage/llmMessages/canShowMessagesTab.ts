import { detectLLMMessages } from "./detectLLMMessages";

export const canShowMessagesTab = (
  input: unknown,
  output: unknown,
): boolean => {
  const inputDetection = detectLLMMessages(input, { fieldType: "input" });
  const outputDetection = detectLLMMessages(output, { fieldType: "output" });

  const hasValid = inputDetection.supported || outputDetection.supported;
  const hasInvalid =
    (!inputDetection.supported && !inputDetection.empty) ||
    (!outputDetection.supported && !outputDetection.empty);

  return hasValid && !hasInvalid;
};
