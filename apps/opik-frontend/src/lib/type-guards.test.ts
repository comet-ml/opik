import { describe, expect, it } from "vitest";
import {
  isTrace,
  isSpan,
  isTraceOrSpan,
  hasTraceSpanStructure,
} from "./type-guards";
import { SPAN_TYPE } from "@/types/traces";

describe("type-guards", () => {
  describe("isTrace", () => {
    it("should return true for valid Trace objects", () => {
      const validTrace = {
        id: "trace-1",
        name: "Test Trace",
        input: { messages: [{ role: "user", content: "Hello" }] },
        output: { choices: [{ message: { content: "Hi" } }] },
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(isTrace(validTrace)).toBe(true);
    });

    it("should return false for invalid Trace objects", () => {
      expect(isTrace(null)).toBe(false);
      expect(isTrace(undefined)).toBe(false);
      expect(isTrace("string")).toBe(false);
      expect(isTrace(123)).toBe(false);
      expect(isTrace({})).toBe(false);
    });

    it("should return false for objects missing required fields", () => {
      const invalidTrace = {
        id: "trace-1",
        // missing name
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(isTrace(invalidTrace)).toBe(false);
    });

    it("should return false for objects with wrong field types", () => {
      const invalidTrace = {
        id: "trace-1",
        name: "Test Trace",
        input: "not an object", // should be object
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(isTrace(invalidTrace)).toBe(false);
    });

    it("should handle optional fields correctly", () => {
      const traceWithOptionals = {
        id: "trace-1",
        name: "Test Trace",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
        span_count: 5,
        llm_span_count: 2,
        thread_id: "thread-1",
        workspace_name: "workspace-1",
        feedback_scores: [],
        tags: ["tag1", "tag2"],
        usage: { tokens: 100 },
        total_estimated_cost: 0.01,
      };

      expect(isTrace(traceWithOptionals)).toBe(true);
    });
  });

  describe("isSpan", () => {
    it("should return true for valid Span objects", () => {
      const validSpan = {
        id: "span-1",
        name: "Test Span",
        input: { messages: [{ role: "user", content: "Hello" }] },
        output: { choices: [{ message: { content: "Hi" } }] },
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        parent_span_id: "parent-1",
        trace_id: "trace-1",
        project_id: "project-1",
        type: SPAN_TYPE.llm,
      };

      expect(isSpan(validSpan)).toBe(true);
    });

    it("should return false for invalid Span objects", () => {
      expect(isSpan(null)).toBe(false);
      expect(isSpan(undefined)).toBe(false);
      expect(isSpan("string")).toBe(false);
      expect(isSpan({})).toBe(false);
    });

    it("should return false for objects missing required Span fields", () => {
      const invalidSpan = {
        id: "span-1",
        name: "Test Span",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        // missing parent_span_id, trace_id, project_id, type
      };

      expect(isSpan(invalidSpan)).toBe(false);
    });

    it("should return false for invalid span type", () => {
      const invalidSpan = {
        id: "span-1",
        name: "Test Span",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        parent_span_id: "parent-1",
        trace_id: "trace-1",
        project_id: "project-1",
        type: "invalid-type", // invalid span type
      };

      expect(isSpan(invalidSpan)).toBe(false);
    });
  });

  describe("isTraceOrSpan", () => {
    it("should return true for valid Trace objects", () => {
      const validTrace = {
        id: "trace-1",
        name: "Test Trace",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(isTraceOrSpan(validTrace)).toBe(true);
    });

    it("should return true for valid Span objects", () => {
      const validSpan = {
        id: "span-1",
        name: "Test Span",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        parent_span_id: "parent-1",
        trace_id: "trace-1",
        project_id: "project-1",
        type: SPAN_TYPE.llm,
      };

      expect(isTraceOrSpan(validSpan)).toBe(true);
    });

    it("should return false for invalid objects", () => {
      expect(isTraceOrSpan(null)).toBe(false);
      expect(isTraceOrSpan(undefined)).toBe(false);
      expect(isTraceOrSpan("string")).toBe(false);
      expect(isTraceOrSpan({})).toBe(false);
    });
  });

  describe("hasTraceSpanStructure", () => {
    it("should return true for objects with basic trace/span structure", () => {
      const basicStructure = {
        id: "test-1",
        name: "Test",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(hasTraceSpanStructure(basicStructure)).toBe(true);
    });

    it("should return false for objects missing basic fields", () => {
      const incompleteStructure = {
        id: "test-1",
        name: "Test",
        // missing input, output, etc.
      };

      expect(hasTraceSpanStructure(incompleteStructure)).toBe(false);
    });

    it("should return false for non-objects", () => {
      expect(hasTraceSpanStructure(null)).toBe(false);
      expect(hasTraceSpanStructure(undefined)).toBe(false);
      expect(hasTraceSpanStructure("string")).toBe(false);
      expect(hasTraceSpanStructure(123)).toBe(false);
    });
  });
});
