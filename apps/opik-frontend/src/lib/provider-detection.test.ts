import { describe, it, expect } from "vitest";
import { detectProvider } from "./provider-detection";
import { PROVIDER_TYPE } from "@/types/providers";
import { Span, SPAN_TYPE } from "@/types/traces";

// Helper function to create minimal mock objects for testing
const createMockSpan = (overrides: Partial<Span> = {}): Span => ({
  id: "test-span-id",
  name: "test-span",
  type: SPAN_TYPE.llm,
  parent_span_id: "parent-id",
  trace_id: "trace-id",
  project_id: "project-id",
  input: {},
  output: {},
  start_time: "2024-01-01T00:00:00Z",
  end_time: "2024-01-01T00:01:00Z",
  duration: 60,
  created_at: "2024-01-01T00:00:00Z",
  last_updated_at: "2024-01-01T00:01:00Z",
  metadata: {},
  comments: [],
  tags: [],
  ...overrides,
});

describe("detectProvider", () => {
  describe("model name detection", () => {
    describe("OpenAI models", () => {
      it("should detect GPT models", () => {
        const span = createMockSpan({ model: "gpt-4" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_AI);
      });

      it("should detect O1 models", () => {
        const span = createMockSpan({ model: "o1-mini" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_AI);
      });
    });

    describe("Anthropic models", () => {
      it("should detect Claude models", () => {
        const span = createMockSpan({ model: "claude-3-sonnet" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.ANTHROPIC);
      });
    });

    describe("Google Gemini models", () => {
      it("should detect Gemini models with gemini- prefix", () => {
        const span = createMockSpan({ model: "gemini-1.5-pro" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
      });

      it("should detect Google Gemini models with google/ prefix", () => {
        const span = createMockSpan({ model: "google/gemini-2.0-flash-001" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
      });

      it("should detect Google Gemini models with google/gemini prefix", () => {
        const span = createMockSpan({ model: "google/gemini-2.5-pro" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
      });
    });

    describe("Vertex AI models", () => {
      it("should detect Vertex AI models with vertex_ai/ prefix", () => {
        const span = createMockSpan({
          model: "vertex_ai/gemini-2.5-flash-preview-04-17",
        });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.VERTEX_AI);
      });

      it("should detect Vertex AI models with vertex-ai in name", () => {
        const span = createMockSpan({ model: "vertex-ai-gemini-model" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.VERTEX_AI);
      });
    });

    describe("OpenRouter models", () => {
      it("should detect OpenRouter models", () => {
        const span = createMockSpan({
          model: "openrouter/anthropic/claude-3-haiku",
        });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_ROUTER);
      });

      it("should detect OpenAI models through OpenRouter", () => {
        const span = createMockSpan({ model: "openai/gpt-4" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_ROUTER);
      });
    });

    describe("edge cases and false positives", () => {
      it("should NOT classify Google Gemini models as Vertex AI", () => {
        const span = createMockSpan({ model: "google/gemini-2.0-flash-001" });
        expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
        expect(detectProvider(span)).not.toBe(PROVIDER_TYPE.VERTEX_AI);
      });

      it("should NOT classify models containing 'google' as Vertex AI", () => {
        const span = createMockSpan({ model: "some-google-model" });
        expect(detectProvider(span)).toBe(null);
      });

      it("should NOT classify models containing 'vertex' as Vertex AI unless specific pattern", () => {
        const span = createMockSpan({ model: "some-vertex-model" });
        expect(detectProvider(span)).toBe(null);
      });

      it("should return null for unknown models", () => {
        const span = createMockSpan({ model: "unknown-model" });
        expect(detectProvider(span)).toBe(null);
      });
    });
  });

  describe("explicit provider detection", () => {
    it("should use explicit provider field when available", () => {
      const span = createMockSpan({ provider: "openai", model: "gpt-4" });
      expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should use provider from metadata when available", () => {
      const span = createMockSpan({
        model: "gpt-4",
        metadata: { provider: "anthropic" },
      });
      expect(detectProvider(span)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });
  });

  describe("structure-based detection", () => {
    it("should detect OpenAI from output structure", () => {
      const span = createMockSpan({
        output: { choices: [{ message: { content: "Hello" } }] },
      });
      expect(detectProvider(span)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should detect Anthropic from output structure", () => {
      const span = createMockSpan({
        output: { content: "Hello" },
      });
      expect(detectProvider(span)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });

    it("should detect Gemini from input structure", () => {
      const span = createMockSpan({
        input: { contents: [{ parts: [{ text: "Hello" }] }] },
      });
      expect(detectProvider(span)).toBe(PROVIDER_TYPE.GEMINI);
    });
  });
});
