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

const DECISION_MODEL = "~typesafe/jev-latest";

const userMessage = (content: string): LLMMessage => ({
  id: content,
  role: LLM_MESSAGE_ROLE.user,
  content,
});

const decisionModelDetails = (overrides: Record<string, unknown> = {}) => ({
  model: DECISION_MODEL,
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

describe("Decision model rule validation", () => {
  it("accepts a rule a decision model can run, on trace and span scope", () => {
    expect(
      LLMJudgeDetailsTraceFormSchema.safeParse(decisionModelDetails()).success,
    ).toBe(true);
    expect(
      LLMJudgeDetailsSpanFormSchema.safeParse(decisionModelDetails()).success,
    ).toBe(true);
  });

  it("rejects non-Boolean scores", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({
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

    expect(issueMessages(result)).toContain(
      "Decision models only support Boolean scores",
    );
  });

  it("rejects anything but a single user message", () => {
    const withSystemMessage = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({
        messages: [
          { id: "s", role: LLM_MESSAGE_ROLE.system, content: "Be strict" },
          userMessage("Answer: {{answer}}"),
        ],
        variables: { answer: "output.answer" },
      }),
    );

    expect(issueMessages(withSystemMessage)).toContain(
      "Decision models need exactly one user message",
    );
  });

  it("rejects an empty score list", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({ schema: [] }),
    );

    expect(issueMessages(result)).toContain(
      "Decision models need at least one score",
    );
  });

  it("rejects non-text content, including audio the capability check misses", () => {
    const audio = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({
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

    expect(issueMessages(audio)).toContain(
      "Decision models only accept text messages",
    );
  });

  it("accepts structured content made only of text parts", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({
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
      decisionModelDetails({
        messages: [userMessage("{{trace}}")],
        variables: { trace: "trace" },
      }),
    );
    const span = LLMJudgeDetailsSpanFormSchema.safeParse(
      decisionModelDetails({
        messages: [userMessage("{{span}}")],
        variables: { span: "span" },
      }),
    );

    expect(issueMessages(trace).join()).toContain(
      'Decision models don\'t support the "trace" variable',
    );
    expect(issueMessages(span).join()).toContain(
      'Decision models don\'t support the "span" variable',
    );
  });

  it("still allows the spans list on trace scope", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({
        messages: [userMessage("Spans: {{spans}}")],
        variables: { spans: "spans" },
      }),
    );

    expect(result.success).toBe(true);
  });

  it("rejects a decision model on thread scope", () => {
    const result = LLMJudgeDetailsThreadFormSchema.safeParse(
      decisionModelDetails({
        messages: [userMessage("{{context}}")],
        variables: {},
      }),
    );

    expect(issueMessages(result)).toContain(
      "Thread rules don't support decision models, choose a trace or span scope",
    );
  });

  it("applies no decision model rules to other models", () => {
    const result = LLMJudgeDetailsTraceFormSchema.safeParse(
      decisionModelDetails({
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

describe("Decision model rule on save", () => {
  it("sends the model name only and no budget", () => {
    const object = convertLLMJudgeDataToLLMJudgeObject(
      // eslint-disable-next-line @typescript-eslint/no-explicit-any
      decisionModelDetails() as any,
    );

    expect(object.model).toEqual({ name: DECISION_MODEL });
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
