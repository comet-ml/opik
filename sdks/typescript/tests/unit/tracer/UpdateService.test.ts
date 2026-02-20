import { describe, it, expect } from "vitest";
import { UpdateService } from "@/tracer/UpdateService";

describe("UpdateService", () => {
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
