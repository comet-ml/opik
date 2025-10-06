import { describe, expect, it } from "vitest";
import {
  detectProvider,
  getProviderDisplayName,
  supportsPrettyView,
} from "./provider-detection";
import { supportsPrettyView as schemaSupportsPrettyView } from "./provider-schemas";
import { PROVIDER_TYPE } from "@/types/providers";
import { Trace, Span, SPAN_TYPE } from "@/types/traces";

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
        type: SPAN_TYPE.llm,
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

    it("should prioritize explicit provider over structure detection", () => {
      const span: Span = {
        id: "span-1",
        name: "test",
        input: { messages: [{ role: "user", content: "Hello" }] },
        output: { choices: [{ message: { content: "Hi there" } }] }, // OpenAI structure
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
        provider: PROVIDER_TYPE.ANTHROPIC, // Explicit Anthropic provider
      };

      // Should return Anthropic despite OpenAI-like structure
      expect(detectProvider(span)).toBe(PROVIDER_TYPE.ANTHROPIC);
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
        type: SPAN_TYPE.llm,
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
        type: SPAN_TYPE.llm,
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
        type: SPAN_TYPE.llm,
        model: "gemini-2.0-flash",
      };

      expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
    });

    it("should detect provider from OpenAI structure via output choices", () => {
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

    it("should detect Anthropic from output structure", () => {
      const trace: Trace = {
        id: "trace-1",
        name: "test",
        input: { messages: [{ role: "user", content: "Hello" }] },
        output: { content: "Hi there" }, // Anthropic-style output
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });

    it("should return null when input has messages but no distinctive output structure", () => {
      const trace: Trace = {
        id: "trace-1",
        name: "test",
        input: { messages: [{ role: "user", content: "Hello" }] },
        output: { some_other_field: "value" }, // Neither choices nor content
        start_time: "2024-01-01T00:00:00Z",
        end_time: "2024-01-01T00:01:00Z",
        duration: 60,
        created_at: "2024-01-01T00:00:00Z",
        last_updated_at: "2024-01-01T00:01:00Z",
        metadata: {},
        project_id: "project-1",
      };

      // Should return null since we can't distinguish between providers
      // based on input messages alone (both use "user" and "assistant")
      expect(detectProvider(trace)).toBeNull();
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
      expect(getProviderDisplayName(PROVIDER_TYPE.OPEN_ROUTER)).toBe(
        "OpenRouter",
      );
      expect(getProviderDisplayName(PROVIDER_TYPE.CUSTOM)).toBe("Custom");
    });
  });

  describe("supportsPrettyView", () => {
    it("should return true for all providers with formatters", () => {
      // All providers in providerFormatters should be supported
      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_AI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.ANTHROPIC)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.GEMINI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.VERTEX_AI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_ROUTER)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.CUSTOM)).toBe(true);
    });

    it("should be consistent with provider-schemas", () => {
      // This test ensures the delegation works correctly
      // and that we don't have hardcoded lists that can get out of sync

      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_AI)).toBe(
        schemaSupportsPrettyView(PROVIDER_TYPE.OPEN_AI),
      );
      expect(supportsPrettyView(PROVIDER_TYPE.ANTHROPIC)).toBe(
        schemaSupportsPrettyView(PROVIDER_TYPE.ANTHROPIC),
      );
    });
  });
});
