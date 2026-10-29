import { parseUsage } from "../src/parsers";

describe("Gemini parsers", () => {
  describe("parseUsage", () => {
    it("maps the basic token counts", () => {
      const result = parseUsage({
        usageMetadata: {
          promptTokenCount: 10,
          candidatesTokenCount: 5,
          totalTokenCount: 15,
        },
      });

      expect(result?.prompt_tokens).toBe(10);
      expect(result?.completion_tokens).toBe(5);
      expect(result?.total_tokens).toBe(15);
    });

    it("counts thinking tokens as completion tokens", () => {
      const result = parseUsage({
        usageMetadata: {
          promptTokenCount: 10,
          candidatesTokenCount: 5,
          thoughtsTokenCount: 100,
          totalTokenCount: 115,
        },
      });

      expect(result?.prompt_tokens).toBe(10);
      expect(result?.completion_tokens).toBe(105);
      expect(result?.total_tokens).toBe(115);
      expect(result?.["original_usage.thoughtsTokenCount"]).toBe(100);
    });

    it("counts tool-use prompt tokens as prompt tokens", () => {
      const result = parseUsage({
        usage_metadata: {
          prompt_token_count: 10,
          tool_use_prompt_token_count: 40,
          candidates_token_count: 5,
          total_token_count: 55,
        },
      });

      expect(result?.prompt_tokens).toBe(50);
      expect(result?.completion_tokens).toBe(5);
      expect(result?.total_tokens).toBe(55);
      expect(result?.["original_usage.tool_use_prompt_token_count"]).toBe(40);
    });

    it("keeps a zero candidates count", () => {
      const result = parseUsage({
        usageMetadata: {
          promptTokenCount: 10,
          candidatesTokenCount: 0,
          totalTokenCount: 10,
        },
      });

      expect(result?.completion_tokens).toBe(0);
    });
  });
});
