import { describe, expect, it } from "vitest";

import {
  getDecisionModelReservedVariables,
  hasSingleUserMessage,
  isTextOnlyMessage,
  fromDecisionModelDetails,
  getDecisionModelTemplates,
  toDecisionModelDetails,
  toDecisionModelMessages,
  toDecisionModelSchema,
} from "./decisionModelRule";
import { EVALUATORS_RULE_SCOPE } from "@/types/automations";
import {
  LLM_JUDGE,
  LLM_MESSAGE_ROLE,
  LLM_SCHEMA_TYPE,
  LLMJudgeSchema,
  LLMMessage,
} from "@/types/llm";
import { LLM_PROMPT_CUSTOM_TRACE_TEMPLATE } from "@/constants/llm";
import { isDecisionModel } from "@/lib/modelCapabilities";

const score = (name: string, type: LLM_SCHEMA_TYPE): LLMJudgeSchema => ({
  name,
  type,
  description: `Is it ${name}?`,
  unsaved: false,
});

describe("isDecisionModel", () => {
  it("matches the Jev models only", () => {
    expect(isDecisionModel("~typesafe/jev-latest")).toBe(true);
    expect(isDecisionModel("typesafe/jev-1.13")).toBe(true);
    expect(isDecisionModel("typesafe/jev-latest")).toBe(false);
    expect(isDecisionModel("gpt-4o")).toBe(false);
    expect(isDecisionModel("")).toBe(false);
    expect(isDecisionModel(undefined)).toBe(false);
  });
});

describe("toDecisionModelSchema", () => {
  it("keeps Boolean scores and reports the removed ones", () => {
    const result = toDecisionModelSchema(
      [
        score("relevant", LLM_SCHEMA_TYPE.BOOLEAN),
        score("quality", LLM_SCHEMA_TYPE.INTEGER),
        score("tone", LLM_SCHEMA_TYPE.DOUBLE),
        score("correct", LLM_SCHEMA_TYPE.BOOLEAN),
      ],
      EVALUATORS_RULE_SCOPE.trace,
    );

    expect(result.schema.map((s) => s.name)).toEqual(["relevant", "correct"]);
    expect(result.removedScoreNames).toEqual(["quality", "tone"]);
  });

  it("falls back to the score of Jev's Custom template when no Boolean score is left", () => {
    const result = toDecisionModelSchema(
      [score("quality", LLM_SCHEMA_TYPE.INTEGER)],
      EVALUATORS_RULE_SCOPE.trace,
    );

    const [jevTemplate] = getDecisionModelTemplates(
      EVALUATORS_RULE_SCOPE.trace,
    );
    expect(result.schema).toEqual(jevTemplate.schema);
    expect(result.schema).not.toBe(jevTemplate.schema);
    expect(result.removedScoreNames).toEqual(["quality"]);
  });

  it("changes nothing when every score is Boolean", () => {
    const schema = [score("relevant", LLM_SCHEMA_TYPE.BOOLEAN)];

    const result = toDecisionModelSchema(schema, EVALUATORS_RULE_SCOPE.span);

    expect(result.schema).toEqual(schema);
    expect(result.removedScoreNames).toEqual([]);
  });
});

describe("getDecisionModelTemplates", () => {
  it("offers only Jev's Custom template, which holds data and no chat-judge instructions", () => {
    [EVALUATORS_RULE_SCOPE.trace, EVALUATORS_RULE_SCOPE.span].forEach(
      (scope) => {
        const templates = getDecisionModelTemplates(scope);

        expect(templates.map((t) => t.value)).toEqual([LLM_JUDGE.custom]);
        expect(templates[0].messages).toEqual([
          expect.objectContaining({
            role: LLM_MESSAGE_ROLE.user,
            content: "INPUT:\n{{input}}\n\nOUTPUT:\n{{output}}",
          }),
        ]);
        expect(templates[0].schema.map((score) => score.type)).toEqual([
          LLM_SCHEMA_TYPE.BOOLEAN,
        ]);
      },
    );
    expect(getDecisionModelTemplates(EVALUATORS_RULE_SCOPE.thread)).toEqual([]);
  });
});

describe("hasSingleUserMessage", () => {
  const message = (role: LLM_MESSAGE_ROLE) => ({
    id: role,
    role,
    content: "text",
  });

  it("requires exactly one message, from the user", () => {
    expect(hasSingleUserMessage([message(LLM_MESSAGE_ROLE.user)])).toBe(true);
    expect(hasSingleUserMessage([])).toBe(false);
    expect(hasSingleUserMessage([message(LLM_MESSAGE_ROLE.system)])).toBe(
      false,
    );
    expect(
      hasSingleUserMessage([
        message(LLM_MESSAGE_ROLE.system),
        message(LLM_MESSAGE_ROLE.user),
      ]),
    ).toBe(false);
  });
});

describe("getDecisionModelReservedVariables", () => {
  it("leaves out the structure variable of each scope", () => {
    expect(
      getDecisionModelReservedVariables(EVALUATORS_RULE_SCOPE.trace),
    ).toEqual({ spans: "spans" });
    expect(
      getDecisionModelReservedVariables(EVALUATORS_RULE_SCOPE.span),
    ).toEqual({});
  });
});

describe("isTextOnlyMessage", () => {
  const message = (content: unknown) =>
    ({ id: "m", role: LLM_MESSAGE_ROLE.user, content }) as Parameters<
      typeof isTextOnlyMessage
    >[0];

  it("accepts plain text and text-only parts", () => {
    expect(isTextOnlyMessage(message("hi"))).toBe(true);
    expect(isTextOnlyMessage(message([{ type: "text", text: "hi" }]))).toBe(
      true,
    );
  });

  it("rejects any image, video or audio part", () => {
    ["image_url", "video_url", "audio_url"].forEach((type) => {
      expect(
        isTextOnlyMessage(
          message([
            { type: "text", text: "hi" },
            { type, [type]: { url: "https://example.com/x" } },
          ]),
        ),
      ).toBe(false);
    });
  });
});

const textMessage = (
  id: string,
  role: LLM_MESSAGE_ROLE,
  content: string,
): LLMMessage => ({ id, role, content });

describe("toDecisionModelMessages", () => {
  it("leaves a single User message alone", () => {
    const messages = [textMessage("u", LLM_MESSAGE_ROLE.user, "Q: {{q}}")];

    expect(toDecisionModelMessages(messages)).toEqual({ messages });
  });

  it("turns a single message of another role into a User message", () => {
    const result = toDecisionModelMessages([
      textMessage("s", LLM_MESSAGE_ROLE.system, "Q: {{q}}"),
    ]);

    expect(result.messages).toEqual([
      textMessage("s", LLM_MESSAGE_ROLE.user, "Q: {{q}}"),
    ]);
    expect(result.note).toContain("the message role was changed to User");
  });

  it("merges several text messages into one User message, joined by a blank line", () => {
    const result = toDecisionModelMessages([
      textMessage("s", LLM_MESSAGE_ROLE.system, "Be strict"),
      {
        id: "u",
        role: LLM_MESSAGE_ROLE.user,
        content: [{ type: "text", text: "Q: {{q}}" }],
      } as LLMMessage,
    ]);

    expect(result.messages).toEqual([
      textMessage("s", LLM_MESSAGE_ROLE.user, "Be strict\n\nQ: {{q}}"),
    ]);
    expect(result.note).toContain("the 2 messages were merged into one");
  });

  it("leaves messages with media for validation to report", () => {
    const messages = [
      {
        id: "s",
        role: LLM_MESSAGE_ROLE.system,
        content: [
          { type: "text", text: "Describe" },
          {
            type: "image_url",
            image_url: { url: "https://example.com/i.png" },
          },
        ],
      } as LLMMessage,
    ];

    expect(toDecisionModelMessages(messages)).toEqual({ messages });
  });
});

describe("toDecisionModelDetails", () => {
  const scope = EVALUATORS_RULE_SCOPE.trace;
  const [jevTemplate] = getDecisionModelTemplates(scope);

  it("replaces an unedited built-in template with Jev's Custom template", () => {
    const result = toDecisionModelDetails(
      {
        template: LLM_JUDGE.custom,
        messages: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.messages,
        variables: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.variables,
        schema: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
      },
      scope,
    );

    expect(result.messages).toEqual(jevTemplate.messages);
    expect(result.variables).toEqual(jevTemplate.variables);
    expect(result.schema).toEqual(jevTemplate.schema);
    expect(result.template).toBe(LLM_JUDGE.custom);
    expect(result.notes).toEqual([
      expect.stringContaining("Loaded Jev's Custom template"),
    ]);
  });

  it("still treats a built-in template as unedited when only its role changed", () => {
    const result = toDecisionModelDetails(
      {
        template: LLM_JUDGE.custom,
        messages: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.messages.map((message) => ({
          ...message,
          role: LLM_MESSAGE_ROLE.system,
        })),
        variables: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.variables,
        schema: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
      },
      scope,
    );

    expect(result.messages).toEqual(jevTemplate.messages);
    expect(result.schema).toEqual(jevTemplate.schema);
    expect(result.notes).toEqual([
      expect.stringContaining("Loaded Jev's Custom template"),
    ]);
  });

  it("keeps an edited prompt, fixing only the role and the scores, and says what changed", () => {
    const result = toDecisionModelDetails(
      {
        template: LLM_JUDGE.custom,
        messages: [
          textMessage("s", LLM_MESSAGE_ROLE.system, "My prompt {{input}}"),
        ],
        variables: { input: "input" },
        schema: [
          score("relevant", LLM_SCHEMA_TYPE.BOOLEAN),
          score("quality", LLM_SCHEMA_TYPE.INTEGER),
        ],
      },
      scope,
    );

    expect(result.messages).toEqual([
      textMessage("s", LLM_MESSAGE_ROLE.user, "My prompt {{input}}"),
    ]);
    expect(result.variables).toEqual({ input: "input" });
    expect(result.schema.map((s) => s.name)).toEqual(["relevant"]);
    expect(result.notes).toEqual([
      "Jev only supports Boolean scores, so these scores were removed: quality.",
      expect.stringContaining("the message role was changed to User"),
    ]);
  });

  it("switches an edited built-in template to Custom", () => {
    const result = toDecisionModelDetails(
      {
        template: LLM_JUDGE.hallucination,
        messages: [
          textMessage("u", LLM_MESSAGE_ROLE.user, "Edited {{output}}"),
        ],
        variables: { output: "output" },
        schema: [score("relevant", LLM_SCHEMA_TYPE.BOOLEAN)],
      },
      scope,
    );

    expect(result.template).toBe(LLM_JUDGE.custom);
    expect(result.messages).toEqual([
      textMessage("u", LLM_MESSAGE_ROLE.user, "Edited {{output}}"),
    ]);
    expect(result.notes).toEqual([]);
  });

  it("changes nothing on a form already set up for Jev", () => {
    const details = {
      template: LLM_JUDGE.custom,
      messages: jevTemplate.messages,
      variables: jevTemplate.variables,
      schema: jevTemplate.schema,
    };

    const result = toDecisionModelDetails(details, scope);

    expect(result).toEqual({ ...details, notes: [] });
  });
});

describe("fromDecisionModelDetails", () => {
  const scope = EVALUATORS_RULE_SCOPE.trace;
  const [jevTemplate] = getDecisionModelTemplates(scope);

  it("restores the chat Custom template when Jev's is still unedited", () => {
    const result = fromDecisionModelDetails(
      {
        template: LLM_JUDGE.custom,
        messages: jevTemplate.messages,
        variables: jevTemplate.variables,
        schema: jevTemplate.schema,
      },
      scope,
    );

    expect(result).toEqual({
      template: LLM_JUDGE.custom,
      messages: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.messages,
      variables: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.variables,
      schema: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
    });
  });

  it("keeps a prompt edited in Jev mode", () => {
    const result = fromDecisionModelDetails(
      {
        template: LLM_JUDGE.custom,
        messages: [textMessage("u", LLM_MESSAGE_ROLE.user, "Mine {{output}}")],
        variables: { output: "output" },
        schema: jevTemplate.schema,
      },
      scope,
    );

    expect(result).toBeNull();
  });

  it("round-trips an unedited default template through Jev and back", () => {
    const chat = {
      template: LLM_JUDGE.custom,
      messages: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.messages,
      variables: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.variables,
      schema: LLM_PROMPT_CUSTOM_TRACE_TEMPLATE.schema,
    };

    const jev = toDecisionModelDetails(chat, scope);
    const back = fromDecisionModelDetails(jev, scope);

    expect(back).toEqual(chat);
  });
});
