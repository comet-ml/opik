import { describe, expect, it } from "vitest";
import { detectProvider, getProviderDisplayName, supportsPrettyView } from "./provider-detection";
import { PROVIDER_TYPE } from "@/types/providers";
import { Trace, Span } from "@/types/traces";

describe("provider-detection", () => {
  describe("detectProvider", () => {
    it("should detect OpenAI provider from explicit provider field", () => {
      const span: Span = {
        id: "span-1",
        name: "test",
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
        type: "llm" as any,
        provider: PROVIDER_TYPE.OPEN_AI,
      };

      expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should detect provider from metadata", () => {
      const trace: Trace = {
        id: "trace-1",
        name: "test",
        input: {},
        output: {},
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: { provider: PROVIDER_TYPE.ANTHROPIC },
        project_id: "project-1",
      };

      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });

    it("should detect provider from model name", () => {
      const span: Span = {
        id: "span-1",
        name: "test",
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
        type: "llm" as any,
        model: "gpt-4",
      };

      expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should detect Anthropic from model name", () => {
      const span: Span = {
        id: "span-1",
        name: "test",
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
        type: "llm" as any,
        model: "claude-3-sonnet",
      };

      expect(detectProvider(span)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });

    it("should detect Gemini from model name", () => {
      const span: Span = {
        id: "span-1",
        name: "test",
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
        type: "llm" as any,
        model: "gemini-2.0-flash",
      };

      expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
    });

    it("should detect provider from OpenAI structure", () => {
      const trace: Trace = {
        id: "trace-1",
        name: "test",
        input: { messages: [{ role: "user", content: "Hello" }] },
        output: { choices: [{ message: { content: "Hi there" } }] },
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should return null for unknown provider", () => {
      const trace: Trace = {
        id: "trace-1",
        name: "test",
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

      expect(detectProvider(trace)).toBeNull();
    });
  });

  describe("getProviderDisplayName", () => {
    it("should return correct display names", () => {
      expect(getProviderDisplayName(PROVIDER_TYPE.OPEN_AI)).toBe("OpenAI");
      expect(getProviderDisplayName(PROVIDER_TYPE.ANTHROPIC)).toBe("Anthropic");
      expect(getProviderDisplayName(PROVIDER_TYPE.GEMINI)).toBe("Gemini");
      expect(getProviderDisplayName(PROVIDER_TYPE.VERTEX_AI)).toBe("Vertex AI");
      expect(getProviderDisplayName(PROVIDER_TYPE.OPEN_ROUTER)).toBe("OpenRouter");
      expect(getProviderDisplayName(PROVIDER_TYPE.CUSTOM)).toBe("Custom");
    });
  });

  describe("supportsPrettyView", () => {
    it("should return true for supported providers", () => {
      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_AI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.ANTHROPIC)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.GEMINI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.VERTEX_AI)).toBe(true);
    });

    it("should return false for unsupported providers", () => {
      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_ROUTER)).toBe(false);
      expect(supportsPrettyView(PROVIDER_TYPE.CUSTOM)).toBe(false);
    });
  });
});
