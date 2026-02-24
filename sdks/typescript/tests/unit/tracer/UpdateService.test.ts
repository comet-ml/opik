import { describe, it, expect } from "vitest";
import { UpdateService } from "@/tracer/UpdateService";
import { Prompt } from "@/prompt/Prompt";

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
  }) as unknown as Prompt;

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
});
