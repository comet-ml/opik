import { describe, expect, it } from "vitest";
import {
  formatProviderData,
  canFormatProviderData,
  getSupportedProviders,
  supportsPrettyView,
} from "./provider-schemas";
import { PROVIDER_TYPE } from "@/types/providers";

describe("provider-schemas", () => {
  describe("formatProviderData", () => {
    describe("OpenAI formatting", () => {
      it("should format OpenAI input correctly", () => {
        const input = {
          messages: [{ role: "user", content: "Hello, how are you?" }],
          model: "gpt-4",
          temperature: 0.7,
          max_tokens: 1000,
        };

        const result = formatProviderData(
          PROVIDER_TYPE.OPEN_AI,
          input,
          "input",
        );

        expect(result).toEqual({
          content: "Hello, how are you?",
          metadata: {
            model: "gpt-4",
            temperature: 0.7,
            max_tokens: 1000,
          },
        });
      });

      it("should format OpenAI output correctly", () => {
        const output = {
          choices: [
            {
              message: { content: "I'm doing well, thank you!" },
              finish_reason: "stop",
            },
          ],
          model: "gpt-4",
          usage: {
            prompt_tokens: 10,
            completion_tokens: 8,
            total_tokens: 18,
          },
        };

        const result = formatProviderData(
          PROVIDER_TYPE.OPEN_AI,
          output,
          "output",
        );

        expect(result).toEqual({
          content: "I'm doing well, thank you!",
          metadata: {
            model: "gpt-4",
            usage: {
              prompt_tokens: 10,
              completion_tokens: 8,
              total_tokens: 18,
            },
            finish_reason: "stop",
          },
        });
      });

      it("should handle multimodal content in input", () => {
        const input = {
          messages: [
            {
              role: "user",
              content: [
                { type: "text", text: "What's in this image?" },
                {
                  type: "image_url",
                  image_url: { url: "data:image/jpeg;base64,..." },
                },
              ],
            },
          ],
          model: "gpt-4-vision",
        };

        const result = formatProviderData(
          PROVIDER_TYPE.OPEN_AI,
          input,
          "input",
        );

        expect(result).toEqual({
          content: "What's in this image?",
          metadata: {
            model: "gpt-4-vision",
            temperature: undefined,
            max_tokens: undefined,
          },
        });
      });
    });

    describe("Anthropic formatting", () => {
      it("should format Anthropic input correctly", () => {
        const input = {
          messages: [{ role: "user", content: "Tell me a joke" }],
          model: "claude-3-sonnet",
          temperature: 0.8,
          max_tokens: 500,
        };

        const result = formatProviderData(
          PROVIDER_TYPE.ANTHROPIC,
          input,
          "input",
        );

        expect(result).toEqual({
          content: "Tell me a joke",
          metadata: {
            model: "claude-3-sonnet",
            temperature: 0.8,
            max_tokens: 500,
          },
        });
      });

      it("should format Anthropic output correctly", () => {
        const output = {
          content:
            "Why don't scientists trust atoms? Because they make up everything!",
          model: "claude-3-sonnet",
          usage: {
            input_tokens: 5,
            output_tokens: 15,
          },
          stop_reason: "end_turn",
        };

        const result = formatProviderData(
          PROVIDER_TYPE.ANTHROPIC,
          output,
          "output",
        );

        expect(result).toEqual({
          content:
            "Why don't scientists trust atoms? Because they make up everything!",
          metadata: {
            model: "claude-3-sonnet",
            usage: {
              prompt_tokens: 5,
              completion_tokens: 15,
              total_tokens: 20,
            },
            stop_reason: "end_turn",
          },
        });
      });
    });

    describe("Gemini formatting", () => {
      it("should format Gemini input correctly", () => {
        const input = {
          contents: [
            {
              parts: [{ text: "Explain quantum computing" }],
            },
          ],
          model: "gemini-2.0-flash",
          temperature: 0.6,
          maxOutputTokens: 2000,
        };

        const result = formatProviderData(PROVIDER_TYPE.GEMINI, input, "input");

        expect(result).toEqual({
          content: "Explain quantum computing",
          metadata: {
            model: "gemini-2.0-flash",
            temperature: 0.6,
            maxOutputTokens: 2000,
          },
        });
      });

      it("should format Gemini output correctly", () => {
        const output = {
          candidates: [
            {
              content: {
                parts: [
                  { text: "Quantum computing is a type of computation..." },
                ],
              },
              finishReason: "STOP",
            },
          ],
          model: "gemini-2.0-flash",
          usageMetadata: {
            promptTokenCount: 4,
            candidatesTokenCount: 20,
            totalTokenCount: 24,
          },
        };

        const result = formatProviderData(
          PROVIDER_TYPE.GEMINI,
          output,
          "output",
        );

        expect(result).toEqual({
          content: "Quantum computing is a type of computation...",
          metadata: {
            model: "gemini-2.0-flash",
            usage: {
              prompt_tokens: 4,
              completion_tokens: 20,
              total_tokens: 24,
            },
            finishReason: "STOP",
          },
        });
      });
    });

    it("should return null for invalid data", () => {
      const invalidData = { invalid: "structure" };

      expect(
        formatProviderData(PROVIDER_TYPE.OPEN_AI, invalidData, "input"),
      ).toBeNull();
      expect(
        formatProviderData(PROVIDER_TYPE.ANTHROPIC, invalidData, "output"),
      ).toBeNull();
      expect(
        formatProviderData(PROVIDER_TYPE.GEMINI, invalidData, "input"),
      ).toBeNull();
    });

    it("should return null for empty content", () => {
      const emptyInput = { messages: [{ role: "user", content: "" }] };

      expect(
        formatProviderData(PROVIDER_TYPE.OPEN_AI, emptyInput, "input"),
      ).toBeNull();
    });
  });

  describe("canFormatProviderData", () => {
    it("should return true for valid OpenAI data", () => {
      const validInput = {
        messages: [{ role: "user", content: "Hello" }],
      };

      expect(
        canFormatProviderData(PROVIDER_TYPE.OPEN_AI, validInput, "input"),
      ).toBe(true);
    });

    it("should return false for invalid data", () => {
      const invalidData = { invalid: "structure" };

      expect(
        canFormatProviderData(PROVIDER_TYPE.OPEN_AI, invalidData, "input"),
      ).toBe(false);
    });

    it("should return false for empty content", () => {
      const emptyInput = { messages: [{ role: "user", content: "" }] };

      expect(
        canFormatProviderData(PROVIDER_TYPE.OPEN_AI, emptyInput, "input"),
      ).toBe(false);
    });
  });

  describe("usage data normalization", () => {
    it("should normalize OpenAI usage data correctly", () => {
      const output = {
        choices: [{ message: { content: "Hello" } }],
        model: "gpt-4",
        usage: {
          prompt_tokens: 10,
          completion_tokens: 5,
          total_tokens: 15,
        },
      };

      const result = formatProviderData(
        PROVIDER_TYPE.OPEN_AI,
        output,
        "output",
      );

      expect(result?.metadata?.usage).toEqual({
        prompt_tokens: 10,
        completion_tokens: 5,
        total_tokens: 15,
      });
    });

    it("should normalize Anthropic usage data correctly", () => {
      const output = {
        content: "Hello",
        model: "claude-3-sonnet",
        usage: {
          input_tokens: 8,
          output_tokens: 3,
        },
      };

      const result = formatProviderData(
        PROVIDER_TYPE.ANTHROPIC,
        output,
        "output",
      );

      expect(result?.metadata?.usage).toEqual({
        prompt_tokens: 8,
        completion_tokens: 3,
        total_tokens: 11, // Should be calculated automatically
      });
    });

    it("should normalize Gemini usage data correctly", () => {
      const output = {
        candidates: [
          {
            content: { parts: [{ text: "Hello" }] },
            finishReason: "STOP",
          },
        ],
        model: "gemini-2.0-flash",
        usageMetadata: {
          promptTokenCount: 12,
          candidatesTokenCount: 4,
          totalTokenCount: 16,
        },
      };

      const result = formatProviderData(PROVIDER_TYPE.GEMINI, output, "output");

      expect(result?.metadata?.usage).toEqual({
        prompt_tokens: 12,
        completion_tokens: 4,
        total_tokens: 16,
      });
    });

    it("should handle missing usage data gracefully", () => {
      const output = {
        choices: [{ message: { content: "Hello" } }],
        model: "gpt-4",
        // No usage data
      };

      const result = formatProviderData(
        PROVIDER_TYPE.OPEN_AI,
        output,
        "output",
      );

      expect(result?.metadata?.usage).toBeUndefined();
    });

    it("should handle invalid usage data gracefully", () => {
      const output = {
        choices: [{ message: { content: "Hello" } }],
        model: "gpt-4",
        usage: "invalid usage data",
      };

      const result = formatProviderData(
        PROVIDER_TYPE.OPEN_AI,
        output,
        "output",
      );

      expect(result?.metadata?.usage).toBeUndefined();
    });

    it("should calculate total tokens when missing", () => {
      const output = {
        content: "Hello",
        model: "claude-3-sonnet",
        usage: {
          input_tokens: 6,
          output_tokens: 2,
          // No total_tokens provided
        },
      };

      const result = formatProviderData(
        PROVIDER_TYPE.ANTHROPIC,
        output,
        "output",
      );

      expect(result?.metadata?.usage).toEqual({
        prompt_tokens: 6,
        completion_tokens: 2,
        total_tokens: 8, // Should be calculated
      });
    });
  });

  describe("getSupportedProviders", () => {
    it("should return all providers that have formatters", () => {
      const supportedProviders = getSupportedProviders();

      expect(supportedProviders).toContain(PROVIDER_TYPE.OPEN_AI);
      expect(supportedProviders).toContain(PROVIDER_TYPE.ANTHROPIC);
      expect(supportedProviders).toContain(PROVIDER_TYPE.GEMINI);
      expect(supportedProviders).toContain(PROVIDER_TYPE.VERTEX_AI);
      expect(supportedProviders).toContain(PROVIDER_TYPE.OPEN_ROUTER);
      expect(supportedProviders).toContain(PROVIDER_TYPE.CUSTOM);
    });

    it("should return a consistent list", () => {
      const firstCall = getSupportedProviders();
      const secondCall = getSupportedProviders();

      expect(firstCall).toEqual(secondCall);
    });
  });

  describe("supportsPrettyView", () => {
    it("should return true for all providers with formatters", () => {
      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_AI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.ANTHROPIC)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.GEMINI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.VERTEX_AI)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.OPEN_ROUTER)).toBe(true);
      expect(supportsPrettyView(PROVIDER_TYPE.CUSTOM)).toBe(true);
    });

    it("should be consistent with getSupportedProviders", () => {
      const supportedProviders = getSupportedProviders();

      // All providers in the supported list should return true
      supportedProviders.forEach((provider) => {
        expect(supportsPrettyView(provider)).toBe(true);
      });
    });
  });
});
