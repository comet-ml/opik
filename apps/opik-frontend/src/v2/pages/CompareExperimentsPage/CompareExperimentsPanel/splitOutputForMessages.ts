import omit from "lodash/omit";
import isEmpty from "lodash/isEmpty";
import {
  detectLLMMessages,
  mapAndCombineMessages,
} from "@/shared/PrettyLLMMessage/llmMessages";

export type OutputMessagesSplit = {
  rendersAsMessages: boolean;
  remainingOutput: Record<string, unknown>;
};

const NOT_MESSAGES: OutputMessagesSplit = {
  rendersAsMessages: false,
  remainingOutput: {},
};

// Only the keys each mapper actually renders are dropped, so anything else a
// task returns (e.g. a LangGraph state's final `output` beside its `messages`)
// still shows below the conversation.
const getRemainingOutput = (
  output: Record<string, unknown>,
  format: string | undefined,
): Record<string, unknown> => {
  if (format === "playground") return omit(output, "output");

  if (format === "openai") {
    return Array.isArray(output.choices)
      ? omit(output, ["choices", "usage"])
      : omit(output, ["text", "usage", "finish_reason"]);
  }

  if (format === "langchain") {
    if (Array.isArray(output.messages)) return omit(output, "messages");

    const remaining = omit(output, ["generations", "llm_output"]);
    const llmOutput = omit(
      (output.llm_output as Record<string, unknown>) ?? {},
      "token_usage",
    );
    return isEmpty(llmOutput)
      ? remaining
      : { ...remaining, llm_output: llmOutput };
  }

  return output;
};

export const splitOutputForMessages = (
  output: unknown,
): OutputMessagesSplit => {
  const detection = detectLLMMessages(output, { fieldType: "output" });

  // A detected shape can still map to no messages, e.g. `{ output: "" }`,
  // which would leave the panel with an empty messages view.
  if (
    !detection.supported ||
    !output ||
    typeof output !== "object" ||
    mapAndCombineMessages(undefined, output).messages.length === 0
  ) {
    return NOT_MESSAGES;
  }

  return {
    rendersAsMessages: true,
    remainingOutput: getRemainingOutput(
      output as Record<string, unknown>,
      detection.format,
    ),
  };
};
