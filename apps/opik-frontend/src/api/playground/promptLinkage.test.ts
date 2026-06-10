import { describe, expect, it } from "vitest";
import {
  collectPromptVersionRefs,
  buildPromptLibraryMetadata,
} from "./promptLinkage";
import { PlaygroundPromptType } from "@/types/playground";
import { LLMMessage, LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROMPT_TEMPLATE_STRUCTURE } from "@/types/prompts";

const message = (overrides: Partial<LLMMessage> = {}): LLMMessage => ({
  id: "m1",
  role: LLM_MESSAGE_ROLE.user,
  content: "hello",
  ...overrides,
});

const prompt = (
  overrides: Partial<PlaygroundPromptType> = {},
): PlaygroundPromptType => ({
  name: "p",
  id: "prompt-state-id",
  messages: [],
  model: "",
  provider: "",
  configs: {},
  ...overrides,
});

describe("collectPromptVersionRefs", () => {
  it("collects the loaded CHAT prompt version", () => {
    const result = collectPromptVersionRefs(
      prompt({
        loadedChatPromptId: "P1",
        loadedChatPromptVersionId: "V1",
      }),
    );
    expect(result).toEqual([{ id: "V1", promptId: "P1" }]);
  });

  it("collects message-level TEXT prompt versions", () => {
    const result = collectPromptVersionRefs(
      prompt({
        messages: [message({ promptId: "P2", promptVersionId: "V2" })],
      }),
    );
    expect(result).toEqual([{ id: "V2", promptId: "P2" }]);
  });

  it("collects both CHAT and message-level versions, CHAT first", () => {
    const result = collectPromptVersionRefs(
      prompt({
        loadedChatPromptId: "P1",
        loadedChatPromptVersionId: "V1",
        messages: [message({ promptId: "P2", promptVersionId: "V2" })],
      }),
    );
    expect(result).toEqual([
      { id: "V1", promptId: "P1" },
      { id: "V2", promptId: "P2" },
    ]);
  });

  it("deduplicates repeated version ids", () => {
    const result = collectPromptVersionRefs(
      prompt({
        loadedChatPromptId: "P1",
        loadedChatPromptVersionId: "V1",
        messages: [message({ promptId: "P1", promptVersionId: "V1" })],
      }),
    );
    expect(result).toEqual([{ id: "V1", promptId: "P1" }]);
  });

  it("skips entries missing a version id or prompt id", () => {
    const result = collectPromptVersionRefs(
      prompt({
        loadedChatPromptId: "P1", // no version id
        messages: [message({ promptVersionId: "V2" })], // no prompt id
      }),
    );
    expect(result).toEqual([]);
  });

  it("returns an empty array for an ad-hoc prompt", () => {
    expect(collectPromptVersionRefs(prompt())).toEqual([]);
  });
});

describe("buildPromptLibraryMetadata", () => {
  it("parses a JSON template and passes through the modified flag", () => {
    const result = buildPromptLibraryMetadata(
      { name: "n", id: "P1", template_structure: "chat" },
      {
        id: "V1",
        template: '[{"role":"system","content":"hi"}]',
        commit: "abc1234",
      },
      true,
    );
    expect(result).toEqual({
      name: "n",
      id: "P1",
      template_structure: PROMPT_TEMPLATE_STRUCTURE.CHAT,
      modified: true,
      version: {
        id: "V1",
        template: [{ role: "system", content: "hi" }],
        commit: "abc1234",
      },
    });
  });

  it("defaults template_structure to TEXT and omits commit/metadata when absent", () => {
    const result = buildPromptLibraryMetadata(
      { name: "n", id: "P1" },
      { id: "V1", template: "plain text" },
      false,
    );
    expect(result).toEqual({
      name: "n",
      id: "P1",
      template_structure: PROMPT_TEMPLATE_STRUCTURE.TEXT,
      modified: false,
      version: {
        id: "V1",
        template: "plain text",
      },
    });
  });

  it("falls back to raw string when template is not valid JSON", () => {
    const result = buildPromptLibraryMetadata(
      { name: "n", id: "P1" },
      { id: "V1", template: "not { json" },
      false,
    );
    expect(result.version.template).toBe("not { json");
  });
});
