import { describe, it, expect } from "vitest";
import { detectProvider } from "./provider-detection";
import { PROVIDER_TYPE } from "@/types/providers";

describe("detectProvider", () => {
  describe("model name detection", () => {
    describe("OpenAI models", () => {
      it("should detect GPT models", () => {
        const trace = { model: "gpt-4" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_AI);
      });

      it("should detect O1 models", () => {
        const trace = { model: "o1-mini" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_AI);
      });
    });

    describe("Anthropic models", () => {
      it("should detect Claude models", () => {
        const trace = { model: "claude-3-sonnet" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.ANTHROPIC);
      });
    });

    describe("Google Gemini models", () => {
      it("should detect Gemini models with gemini- prefix", () => {
        const trace = { model: "gemini-1.5-pro" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.GEMINI);
      });

      it("should detect Google Gemini models with google/ prefix", () => {
        const trace = { model: "google/gemini-2.0-flash-001" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.GEMINI);
      });

      it("should detect Google Gemini models with google/gemini prefix", () => {
        const trace = { model: "google/gemini-2.5-pro" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.GEMINI);
      });
    });

    describe("Vertex AI models", () => {
      it("should detect Vertex AI models with vertex_ai/ prefix", () => {
        const trace = { model: "vertex_ai/gemini-2.5-flash-preview-04-17" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.VERTEX_AI);
      });

      it("should detect Vertex AI models with vertex-ai in name", () => {
        const trace = { model: "vertex-ai-gemini-model" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.VERTEX_AI);
      });
    });

    describe("OpenRouter models", () => {
      it("should detect OpenRouter models", () => {
        const trace = { model: "openrouter/anthropic/claude-3-haiku" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_ROUTER);
      });

      it("should detect OpenAI models through OpenRouter", () => {
        const trace = { model: "openai/gpt-4" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_ROUTER);
      });
    });

    describe("edge cases and false positives", () => {
      it("should NOT classify Google Gemini models as Vertex AI", () => {
        const trace = { model: "google/gemini-2.0-flash-001" };
        expect(detectProvider(trace)).toBe(PROVIDER_TYPE.GEMINI);
        expect(detectProvider(trace)).not.toBe(PROVIDER_TYPE.VERTEX_AI);
      });

      it("should NOT classify models containing 'google' as Vertex AI", () => {
        const trace = { model: "some-google-model" };
        expect(detectProvider(trace)).toBe(null);
      });

      it("should NOT classify models containing 'vertex' as Vertex AI unless specific pattern", () => {
        const trace = { model: "some-vertex-model" };
        expect(detectProvider(trace)).toBe(null);
      });

      it("should return null for unknown models", () => {
        const trace = { model: "unknown-model" };
        expect(detectProvider(trace)).toBe(null);
      });
    });
  });

  describe("explicit provider detection", () => {
    it("should use explicit provider field when available", () => {
      const trace = { provider: "openai", model: "gpt-4" };
      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should use provider from metadata when available", () => {
      const trace = {
        model: "gpt-4",
        metadata: { provider: "anthropic" },
      };
      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });
  });

  describe("structure-based detection", () => {
    it("should detect OpenAI from output structure", () => {
      const trace = {
        output: { choices: [{ message: { content: "Hello" } }] },
      };
      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.OPEN_AI);
    });

    it("should detect Anthropic from output structure", () => {
      const trace = {
        output: { content: "Hello" },
      };
      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.ANTHROPIC);
    });

    it("should detect Gemini from input structure", () => {
      const trace = {
        input: { contents: [{ parts: [{ text: "Hello" }] }] },
      };
      expect(detectProvider(trace)).toBe(PROVIDER_TYPE.GEMINI);
    });
  });
});
