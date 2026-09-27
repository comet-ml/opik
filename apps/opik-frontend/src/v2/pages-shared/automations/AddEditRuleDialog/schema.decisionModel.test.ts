import { describe, expect, it } from "vitest";

import {
  convertLLMJudgeDataToLLMJudgeObject,
  LLMJudgeDetailsSpanFormSchema,
  LLMJudgeDetailsThreadFormSchema,
  LLMJudgeDetailsTraceFormSchema,
} from "./schema";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMMessage,
} from "@/types/llm";

const JEV = "~typesafe/jev-latest";

const userMessage = (content: string): LLMMessage => ({
  id: content,
  role: LLM_MESSAGE_ROLE.user,
  content,
});

const jevDetails = (overrides: Record<string, unknown> = {}) => ({
  model: JEV,
  config: { temperature: 0.3, seed: 7, custom_parameters: { foo: "bar" } },
  template: LLM_JUDGE.custom,
  messages: [userMessage("Question: {{question}}\nAnswer: {{answer}}")],
  variables: { question: "input.question", answer: "output.answer" },
  schema: [
    {
      name: "answer_relevant",
      type: LLM_SCHEMA_TYPE.BOOLEAN,
      description: "Does the answer respond to the question?",
      unsaved: false,
    },
  ],
  maxCostUsd: 1.5,
  ...overrides,
});

const issueMessages = (result: { success: boolean; error?: unknown }) =>
  result.success
    ? []
    : (result.error as { issues: { message: string }[] }).issues.map(
        (issue) => issue.message,
      );

describe("Jev rule validation", () => {
  it("accepts a rule Jev can run, on trace and span scope", () => {
    expect(LLMJudgeDetailsTraceFormSchema.safeParse(jevDetails()).success).toBe(
      true,
    );
    expect(LLMJudgeDetailsSpanFormSchema.safeParse(jevDetails()).success).toBe(
      true,
    );
  });

  it("rejects non-Boolean scores", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        schema: [
          {
            name: "quality",
            type: LLM_SCHEMA_TYPE.INTEGER,
            description: "Rate it",
            unsaved: false,
          },
        ],
      }),
    );

    expect(issueMessages(result)).toContain("Jev only supports Boolean scores");
  });

  it("rejects anything but a single user message", () => {
    const withSystemMessage = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        messages: [
          { id: "s", role: LLM_MESSAGE_ROLE.system, content: "Be strict" },
          userMessage("Answer: {{answer}}"),
        ],
        variables: { answer: "output.answer" },
      }),
    );

    expect(issueMessages(withSystemMessage)).toContain(
      "Jev needs exactly one user message",
    );
  });

  it("rejects an empty score list", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({ schema: [] }),
    );

    expect(issueMessages(result)).toContain("Jev needs at least one score");
  });

  it("rejects non-text content, including audio the capability check misses", () => {
    const audio = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        messages: [
          {
            id: "a",
            role: LLM_MESSAGE_ROLE.user,
            content: [
              { type: "text", text: "Answer: {{answer}}" },
              {
                type: "audio_url",
                audio_url: { url: "https://example.com/a.mp3" },
              },
            ],
          },
        ],
        variables: { answer: "output.answer" },
      }),
    );

    expect(issueMessages(audio)).toContain("Jev only accepts text messages");
  });

  it("accepts structured content made only of text parts", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        messages: [
          {
            id: "t",
            role: LLM_MESSAGE_ROLE.user,
            content: [{ type: "text", text: "Answer: {{answer}}" }],
          },
        ],
        variables: { answer: "output.answer" },
      }),
    );

    expect(result.success).toBe(true);
  });

  it("rejects the structure variable of the scope", () => {
    const trace = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        messages: [userMessage("{{trace}}")],
        variables: { trace: "trace" },
      }),
    );
    const span = LLMJudgeDetailsSpanFormSchema.safeParse(
      jevDetails({
        messages: [userMessage("{{span}}")],
        variables: { span: "span" },
      }),
    );

    expect(issueMessages(trace).join()).toContain(
      'Jev doesn\'t support the "trace" variable',
    );
    expect(issueMessages(span).join()).toContain(
      'Jev doesn\'t support the "span" variable',
    );
  });

  it("still allows the spans list on trace scope", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        messages: [userMessage("Spans: {{spans}}")],
        variables: { spans: "spans" },
      }),
    );

    expect(result.success).toBe(true);
  });

  it("rejects Jev on thread scope", () => {
    const result = LLMJudgeDetailsThreadFormSchema.safeParse(
      jevDetails({
        messages: [userMessage("{{context}}")],
        variables: {},
      }),
    );

    expect(issueMessages(result)).toContain(
      "Thread rules don't support Jev, choose a trace or span scope",
    );
  });

  it("applies no Jev rules to other models", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      jevDetails({
        model: "gpt-4o",
        schema: [
          {
            name: "quality",
            type: LLM_SCHEMA_TYPE.INTEGER,
            description: "Rate it",
            unsaved: false,
          },
        ],
      }),
    );

    expect(result.success).toBe(true);
  });
});

describe("Jev rule on save", () => {
  it("sends the model name only and no budget", () => {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const object = convertLLMJudgeDataToLLMJudgeObject(jevDetails() as any);

    expect(object.model).toEqual({ name: JEV });
    expect(object.max_cost_usd).toBeNull();
    expect(object.schema).toHaveLength(1);
    expect(object.messages).toEqual([
      expect.objectContaining({
        role: "USER",
        content: "Question: {{question}}\nAnswer: {{answer}}",
      }),
    ]);
  });
});
