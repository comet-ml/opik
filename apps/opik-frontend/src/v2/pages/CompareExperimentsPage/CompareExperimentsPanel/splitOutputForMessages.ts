import omit from "lodash/omit";
import { detectLLMMessages } from "@/shared/PrettyLLMMessage/llmMessages";

export type OutputMessagesSplit = {
  rendersAsMessages: boolean;
  remainingOutput: Record<string, unknown>;
};

const NOT_MESSAGES: OutputMessagesSplit = {
  rendersAsMessages: false,
  remainingOutput: {},
};

// The playground `{ output }` and OpenAI custom `{ text }` shapes are matched on
// a single key, so any sibling keys a task returns would never reach the
// messages view. Full provider responses (`choices`, LangChain) are shown whole.
const getKeysShownAsMessages = (
  output: Record<string, unknown>,
  format: string | undefined,
): string[] | null => {
  if (format === "playground") return ["output"];
  if (format === "openai" && !Array.isArray(output.choices)) {
    return ["text", "usage", "finish_reason"];
  }
  return null;
};

export const splitOutputForMessages = (
  output: unknown,
): OutputMessagesSplit => {
  const detection = detectLLMMessages(output, { fieldType: "output" });

  if (!detection.supported || !output || typeof output !== "object") {
    return NOT_MESSAGES;
  }

  const record = output as Record<string, unknown>;
  const keysShownAsMessages = getKeysShownAsMessages(record, detection.format);

  return {
    rendersAsMessages: true,
    remainingOutput: keysShownAsMessages
      ? omit(record, keysShownAsMessages)
      : {},
  };
};
