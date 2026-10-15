import { describe, expect, it } from "vitest";

import { LLM_MESSAGE_ROLE, LLMMessage } from "@/types/llm";
import {
  inlineVariableMappings,
  nameInlineVariables,
} from "@/v2/pages-shared/automations/RuleEditorPanel/inlineVariableMappings";

const message = (content: LLMMessage["content"]): LLMMessage => ({
  id: "m1",
  role: LLM_MESSAGE_ROLE.user,
  content,
});

describe("inline variable round trip", () => {
  it("keeps the saved name of a path and skips names already in use", () => {
    const { messages, variables } = nameInlineVariables(
      [message("{{input.question}} {{output.answer}} {{var_1}}")],
      {
        "input.question": "input.question",
        "output.answer": "output.answer",
        var_1: "metadata.id",
      },
      { question: "input.question", var_1: "metadata.id" },
    );

    expect(messages[0].content).toBe("{{question}} {{var_2}} {{var_1}}");
    expect(variables).toEqual({
      question: "input.question",
      var_2: "output.answer",
      var_1: "metadata.id",
    });
  });

  it("gives back the saved rule after a load and a save", () => {
    const saved = { var_1: "metadata.test.test", var_2: "output" };
    const loaded = inlineVariableMappings(
      [message("{{var_1}} and {{var_2}}")],
      saved,
    );
    const { messages, variables } = nameInlineVariables(
      loaded,
      { "metadata.test.test": "metadata.test.test", output: "output" },
      saved,
    );

    expect(messages[0].content).toBe("{{var_1}} and {{var_2}}");
    expect(variables).toEqual(saved);
  });
});
