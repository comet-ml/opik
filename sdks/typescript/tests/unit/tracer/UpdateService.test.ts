import { describe, it, expect } from "vitest";
import { UpdateService } from "@/tracer/UpdateService";
import { Prompt } from "@/prompt/Prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";

const makePrompt = (
  name: string,
  template: string,
  id = "prompt-id",
  versionId = "version-id",
  commit = "abc12345"
) =>
  ({
    name,
    id,
    versionId,
    commit,
    prompt: template,
    templateStructure: "text",
  }) as unknown as Prompt;

const makeChatPrompt = (
  name: string,
  messages: { role: string; content: string }[],
  id = "chat-prompt-id",
  versionId = "chat-version-id",
  commit = "chat1234"
) =>
  ({
    name,
    id,
    versionId,
    commit,
    messages,
    templateStructure: "chat",
  }) as unknown as ChatPrompt;

describe("UpdateService", () => {
  describe("processTraceUpdate with prompts", () => {
    it("should serialize single prompt into metadata.opik_prompts", () => {
      const prompt = makePrompt("my-prompt", "Answer: {{q}}");
      const result = UpdateService.processTraceUpdate({ prompts: [prompt] });

      expect(result.metadata).toEqual({
        opik_prompts: [
          {
            name: "my-prompt",
            id: "prompt-id",
            template_structure: "text",
            version: { id: "version-id", commit: "abc12345", template: "Answer: {{q}}" },
          },
        ],
      });
    });

    it("should serialize multiple prompts preserving order", () => {
      const p1 = makePrompt("p1", "Template 1", "id-1", "vid-1", "commit1");
      const p2 = makePrompt("p2", "Template 2", "id-2", "vid-2", "commit2");
      const result = UpdateService.processTraceUpdate({ prompts: [p1, p2] });

      const prompts = (result.metadata as Record<string, unknown>).opik_prompts as unknown[];
      expect(prompts).toHaveLength(2);
      expect((prompts[0] as { name: string }).name).toBe("p1");
      expect((prompts[1] as { name: string }).name).toBe("p2");
    });

    it("should merge prompts with existing metadata", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processTraceUpdate(
        { prompts: [prompt] },
        { existingKey: "value" }
      );

      expect(result.metadata).toMatchObject({
        existingKey: "value",
        opik_prompts: expect.any(Array),
      });
    });

    it("should merge prompts with new metadata from update", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processTraceUpdate({
        prompts: [prompt],
        metadata: { newKey: "newValue" },
      });

      expect(result.metadata).toMatchObject({
        newKey: "newValue",
        opik_prompts: expect.any(Array),
      });
    });

    it("should merge prompts with both existing and new metadata", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processTraceUpdate(
        { prompts: [prompt], metadata: { newKey: "new" } },
        { existingKey: "existing" }
      );

      expect(result.metadata).toMatchObject({
        existingKey: "existing",
        newKey: "new",
        opik_prompts: expect.any(Array),
      });
    });

    it("should let new metadata override existing keys when prompts present", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processTraceUpdate(
        { prompts: [prompt], metadata: { key: "new" } },
        { key: "old" }
      );

      expect((result.metadata as Record<string, unknown>).key).toBe("new");
    });

    it("should omit prompts field from returned update", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processTraceUpdate({ prompts: [prompt] });

      expect(result).not.toHaveProperty("prompts");
    });

    it("should handle prompt with no id or commit", () => {
      const prompt = {
        name: "bare",
        id: undefined,
        versionId: undefined,
        commit: undefined,
        prompt: "template",
        templateStructure: "text",
      } as unknown as Prompt;

      const result = UpdateService.processTraceUpdate({ prompts: [prompt] });
      const opikPrompts = (result.metadata as Record<string, unknown>).opik_prompts as { name: string; version: { template: string } }[];

      expect(opikPrompts[0].name).toBe("bare");
      expect(opikPrompts[0].version.template).toBe("template");
    });
  });

  describe("processTraceUpdate", () => {
    it("should return updates unchanged when no metadata and no prompts", () => {
      const result = UpdateService.processTraceUpdate({
        output: "some output",
      });

      expect(result).toEqual({ output: "some output" });
    });

    it("should pass through new metadata when no existing metadata and no prompts", () => {
      const result = UpdateService.processTraceUpdate(
        { metadata: { source: "from_update" } },
        undefined
      );

      expect(result.metadata).toEqual({ source: "from_update" });
    });

    it("should merge new metadata with existing metadata when no prompts", () => {
      const result = UpdateService.processTraceUpdate(
        { metadata: { updated: "yes" } },
        { initial: "yes" }
      );

      expect(result.metadata).toEqual({
        initial: "yes",
        updated: "yes",
      });
    });

    it("should let new metadata override existing metadata keys when no prompts", () => {
      const result = UpdateService.processTraceUpdate(
        { metadata: { key: "new_value" } },
        { key: "old_value", other: "preserved" }
      );

      expect(result.metadata).toEqual({
        key: "new_value",
        other: "preserved",
      });
    });

    it("should preserve existing metadata when update has no metadata and no prompts", () => {
      const result = UpdateService.processTraceUpdate(
        { output: "result" },
        { initial: "yes" }
      );

      // When no metadata in update, existing metadata is not touched
      expect(result).toEqual({ output: "result" });
      expect(result.metadata).toBeUndefined();
    });

    it("should handle existing metadata as JSON string when merging without prompts", () => {
      const result = UpdateService.processTraceUpdate(
        { metadata: { updated: "yes" } },
        JSON.stringify({ initial: "yes" }) as unknown as Record<string, unknown>
      );

      expect(result.metadata).toEqual({
        initial: "yes",
        updated: "yes",
      });
    });
  });

  describe("processSpanUpdate with prompts", () => {
    it("should serialize single prompt into metadata.opik_prompts", () => {
      const prompt = makePrompt("my-prompt", "Answer: {{q}}");
      const result = UpdateService.processSpanUpdate({ prompts: [prompt] });

      expect(result.metadata).toEqual({
        opik_prompts: [
          {
            name: "my-prompt",
            id: "prompt-id",
            template_structure: "text",
            version: { id: "version-id", commit: "abc12345", template: "Answer: {{q}}" },
          },
        ],
      });
    });

    it("should serialize multiple prompts preserving order", () => {
      const p1 = makePrompt("p1", "Template 1", "id-1", "vid-1", "commit1");
      const p2 = makePrompt("p2", "Template 2", "id-2", "vid-2", "commit2");
      const result = UpdateService.processSpanUpdate({ prompts: [p1, p2] });

      const prompts = (result.metadata as Record<string, unknown>).opik_prompts as unknown[];
      expect(prompts).toHaveLength(2);
      expect((prompts[0] as { name: string }).name).toBe("p1");
      expect((prompts[1] as { name: string }).name).toBe("p2");
    });

    it("should merge prompts with existing metadata", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processSpanUpdate(
        { prompts: [prompt] },
        { existingKey: "value" }
      );

      expect(result.metadata).toMatchObject({
        existingKey: "value",
        opik_prompts: expect.any(Array),
      });
    });

    it("should merge prompts with new metadata from update", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processSpanUpdate({
        prompts: [prompt],
        metadata: { newKey: "newValue" },
      });

      expect(result.metadata).toMatchObject({
        newKey: "newValue",
        opik_prompts: expect.any(Array),
      });
    });

    it("should merge prompts with both existing and new metadata", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processSpanUpdate(
        { prompts: [prompt], metadata: { newKey: "new" } },
        { existingKey: "existing" }
      );

      expect(result.metadata).toMatchObject({
        existingKey: "existing",
        newKey: "new",
        opik_prompts: expect.any(Array),
      });
    });

    it("should let new metadata override existing keys when prompts present", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processSpanUpdate(
        { prompts: [prompt], metadata: { key: "new" } },
        { key: "old" }
      );

      expect((result.metadata as Record<string, unknown>).key).toBe("new");
    });

    it("should omit prompts field from returned update", () => {
      const prompt = makePrompt("p", "tpl");
      const result = UpdateService.processSpanUpdate({ prompts: [prompt] });

      expect(result).not.toHaveProperty("prompts");
    });
  });

  describe("processSpanUpdate", () => {
    it("should return updates unchanged when no metadata and no prompts", () => {
      const result = UpdateService.processSpanUpdate({
        output: "some output",
      });

      expect(result).toEqual({ output: "some output" });
    });

    it("should pass through new metadata when no existing metadata and no prompts", () => {
      const result = UpdateService.processSpanUpdate(
        { metadata: { source: "from_update" } },
        undefined
      );

      expect(result.metadata).toEqual({ source: "from_update" });
    });

    it("should merge new metadata with existing metadata when no prompts", () => {
      const result = UpdateService.processSpanUpdate(
        { metadata: { updated: "yes" } },
        { initial: "yes" }
      );

      expect(result.metadata).toEqual({
        initial: "yes",
        updated: "yes",
      });
    });

    it("should let new metadata override existing metadata keys when no prompts", () => {
      const result = UpdateService.processSpanUpdate(
        { metadata: { key: "new_value" } },
        { key: "old_value", other: "preserved" }
      );

      expect(result.metadata).toEqual({
        key: "new_value",
        other: "preserved",
      });
    });

    it("should preserve existing metadata when update has no metadata and no prompts", () => {
      const result = UpdateService.processSpanUpdate(
        { output: "result" },
        { initial: "yes" }
      );

      expect(result).toEqual({ output: "result" });
      expect(result.metadata).toBeUndefined();
    });

    it("should handle existing metadata as JSON string when merging without prompts", () => {
      const result = UpdateService.processSpanUpdate(
        { metadata: { updated: "yes" } },
        JSON.stringify({ initial: "yes" }) as unknown as Record<string, unknown>
      );

      expect(result.metadata).toEqual({
        initial: "yes",
        updated: "yes",
      });
    });
  });

  describe("processTraceUpdate with chat prompts", () => {
    it("should serialize chat prompt with messages and template_structure", () => {
      const chatPrompt = makeChatPrompt("assistant-prompt", [
        { role: "system", content: "You are helpful" },
        { role: "user", content: "Hello {{name}}" },
      ]);
      const result = UpdateService.processTraceUpdate({ prompts: [chatPrompt] });

      expect(result.metadata).toEqual({
        opik_prompts: [
          {
            name: "assistant-prompt",
            id: "chat-prompt-id",
            template_structure: "chat",
            version: {
              id: "chat-version-id",
              commit: "chat1234",
              template: [
                { role: "system", content: "You are helpful" },
                { role: "user", content: "Hello {{name}}" },
              ],
            },
          },
        ],
      });
    });

    it("should serialize chat prompt with no id or commit", () => {
      const chatPrompt = makeChatPrompt(
        "bare-chat",
        [{ role: "user", content: "Hi" }],
        undefined as unknown as string,
        undefined as unknown as string,
        undefined as unknown as string
      );

      const result = UpdateService.processTraceUpdate({ prompts: [chatPrompt] });
      const opikPrompts = (result.metadata as Record<string, unknown>).opik_prompts as { name: string; template_structure: string; version: { template: unknown } }[];

      expect(opikPrompts[0].name).toBe("bare-chat");
      expect(opikPrompts[0].template_structure).toBe("chat");
      expect(opikPrompts[0].version.template).toEqual([{ role: "user", content: "Hi" }]);
    });

    it("should serialize mixed text and chat prompts together", () => {
      const textPrompt = makePrompt("text-p", "Answer: {{q}}");
      const chatPrompt = makeChatPrompt("chat-p", [
        { role: "user", content: "Help with {{task}}" },
      ]);
      const result = UpdateService.processTraceUpdate({ prompts: [textPrompt, chatPrompt] });

      const opikPrompts = (result.metadata as Record<string, unknown>).opik_prompts as { name: string; template_structure: string }[];
      expect(opikPrompts).toHaveLength(2);
      expect(opikPrompts[0].name).toBe("text-p");
      expect(opikPrompts[0].template_structure).toBe("text");
      expect(opikPrompts[1].name).toBe("chat-p");
      expect(opikPrompts[1].template_structure).toBe("chat");
    });
  });

  describe("processSpanUpdate with chat prompts", () => {
    it("should serialize chat prompt into metadata.opik_prompts", () => {
      const chatPrompt = makeChatPrompt("span-chat", [
        { role: "system", content: "Be concise" },
      ]);
      const result = UpdateService.processSpanUpdate({ prompts: [chatPrompt] });

      expect(result.metadata).toEqual({
        opik_prompts: [
          {
            name: "span-chat",
            id: "chat-prompt-id",
            template_structure: "chat",
            version: {
              id: "chat-version-id",
              commit: "chat1234",
              template: [{ role: "system", content: "Be concise" }],
            },
          },
        ],
      });
    });

    it("should merge chat prompts with existing metadata", () => {
      const chatPrompt = makeChatPrompt("chat-p", [{ role: "user", content: "Hi" }]);
      const result = UpdateService.processSpanUpdate(
        { prompts: [chatPrompt] },
        { existingKey: "value" }
      );

      expect(result.metadata).toMatchObject({
        existingKey: "value",
        opik_prompts: expect.any(Array),
      });
    });
  });
});
