import { describe, expect, it } from "vitest";

import {
  LLMJudgeDetailsThreadFormSchema,
  THREAD_CONTEXT_VARIABLE,
} from "./schema";
import { LLM_JUDGE, LLM_MESSAGE_ROLE, LLM_SCHEMA_TYPE } from "@/types/llm";

const buildThreadDetails = (contents: string[]) => ({
  model: "gpt-4o",
  config: { temperature: 0 },
  template: LLM_JUDGE.custom,
  messages: contents.map((content, index) => ({
    id: `message-${index}`,
    role: LLM_MESSAGE_ROLE.user,
    content,
  })),
  variables: {},
  schema: [
    {
      name: "Relevance",
      description: "",
      type: LLM_SCHEMA_TYPE.BOOLEAN,
      unsaved: false,
    },
  ],
});

const issuesFor = (contents: string[]) => {
  const result = LLMJudgeDetailsThreadFormSchema.safeParse(
    buildThreadDetails(contents),
  );
  return result.success ? [] : result.error.issues;
};

describe("LLMJudgeDetailsThreadFormSchema", () => {
  it("accepts a prompt with exactly one {{context}}", () => {
    expect(issuesFor([`Judge this:\n${THREAD_CONTEXT_VARIABLE}`])).toEqual([]);
  });

  it("anchors a missing {{context}} to the last message", () => {
    const issues = issuesFor(["You are a judge.", "Score the thread."]);

    expect(issues).toHaveLength(1);
    expect(issues[0].path).toEqual(["messages", 1, "content"]);
    expect(issues[0].message).toContain(THREAD_CONTEXT_VARIABLE);
  });

  it("flags each duplicate {{context}} on the message that carries it", () => {
    const issues = issuesFor([
      `System: ${THREAD_CONTEXT_VARIABLE}`,
      "Middle message without the variable.",
      `Again: ${THREAD_CONTEXT_VARIABLE}`,
    ]);

    expect(issues.map((issue) => issue.path)).toEqual([
      ["messages", 2, "content"],
    ]);
    expect(issues[0].message).toContain("Only one message");
  });

  it("flags unsupported variables on the message that uses them", () => {
    const issues = issuesFor([
      `Conversation: ${THREAD_CONTEXT_VARIABLE}`,
      "Expected: {{expected_output}}",
    ]);

    expect(issues).toHaveLength(1);
    expect(issues[0].path).toEqual(["messages", 1, "content"]);
    expect(issues[0].message).toContain("{{expected_output}}");
  });
});
