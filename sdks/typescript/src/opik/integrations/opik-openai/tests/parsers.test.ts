import { parseCompletionOutput, parseUsage } from "../src/parsers";

describe("OpenAI Parsers", () => {
  describe("parseCompletionOutput", () => {
    describe("embedding responses", () => {
      it("should parse single embedding response", () => {
        const embeddingResponse = {
          data: [
            {
              embedding: [0.0023, -0.0093, 0.0045, 0.0012, -0.0078, 0.0034],
              index: 0,
              object: "embedding",
            },
          ],
          model: "text-embedding-3-small",
          object: "list",
          usage: { prompt_tokens: 8, total_tokens: 8 },
        };

        const result = parseCompletionOutput(embeddingResponse);

        expect(result).toBeDefined();
        expect(result).toHaveProperty("embeddings");
        expect(
          Array.isArray((result as { embeddings: unknown[] }).embeddings)
        ).toBe(true);
        expect(
          (result as { embeddings: { index: number; dimensions: number }[] })
            .embeddings[0]
        ).toEqual({
          embedding: [0.0023, -0.0093, 0.0045, 0.0012, -0.0078, "..."],
          index: 0,
          dimensions: 6,
        });
      });

      it("should parse batch embedding response with multiple embeddings", () => {
        const batchEmbeddingResponse = {
          data: [
            {
              embedding: [0.001, 0.002, 0.003],
              index: 0,
              object: "embedding",
            },
            {
              embedding: [0.004, 0.005, 0.006],
              index: 1,
              object: "embedding",
            },
          ],
          model: "text-embedding-3-small",
          object: "list",
        };

        const result = parseCompletionOutput(batchEmbeddingResponse);

        expect(result).toBeDefined();
        expect((result as { embeddings: unknown[] }).embeddings).toHaveLength(
          2
        );
        expect(
          (result as { embeddings: { index: number }[] }).embeddings[0].index
        ).toBe(0);
        expect(
          (result as { embeddings: { index: number }[] }).embeddings[1].index
        ).toBe(1);
      });

      it("should truncate large embedding vectors", () => {
        const largeEmbedding = Array.from(
          { length: 1536 },
          (_, i) => i * 0.001
        );
        const embeddingResponse = {
          data: [
            {
              embedding: largeEmbedding,
              index: 0,
            },
          ],
        };

        const result = parseCompletionOutput(embeddingResponse);

        expect(result).toBeDefined();
        const embeddings = (
          result as {
            embeddings: { embedding: unknown[]; dimensions: number }[];
          }
        ).embeddings;
        expect(embeddings[0].embedding).toHaveLength(6); // 5 values + "..."
        expect(embeddings[0].embedding[5]).toBe("...");
        expect(embeddings[0].dimensions).toBe(1536);
      });

      it("should not truncate small embedding vectors", () => {
        const smallEmbedding = [0.1, 0.2, 0.3];
        const embeddingResponse = {
          data: [
            {
              embedding: smallEmbedding,
              index: 0,
            },
          ],
        };

        const result = parseCompletionOutput(embeddingResponse);

        expect(result).toBeDefined();
        const embeddings = (
          result as {
            embeddings: { embedding: number[]; dimensions: number }[];
          }
        ).embeddings;
        expect(embeddings[0].embedding).toEqual([0.1, 0.2, 0.3]);
        expect(embeddings[0].dimensions).toBe(3);
      });
    });

    describe("chat completion responses (existing behavior)", () => {
      it("should parse choices format", () => {
        const chatResponse = {
          choices: [
            {
              message: {
                role: "assistant",
                content: "Hello, how can I help you?",
              },
            },
          ],
        };

        const result = parseCompletionOutput(chatResponse);

        expect(result).toBeDefined();
        expect(result).toHaveProperty("role", "assistant");
        expect(result).toHaveProperty("content", "Hello, how can I help you?");
      });

      it("should parse output_text format", () => {
        const responseApiResponse = {
          output_text: "This is the response text",
        };

        const result = parseCompletionOutput(responseApiResponse);

        expect(result).toBeDefined();
        expect(result).toEqual({ content: "This is the response text" });
      });
    });

    describe("edge cases", () => {
      it("should return undefined for unrecognized formats", () => {
        const unknownResponse = { foo: "bar" };
        const result = parseCompletionOutput(unknownResponse);
        expect(result).toBeUndefined();
      });

      it("should return undefined for empty data array", () => {
        const emptyDataResponse = { data: [] };
        const result = parseCompletionOutput(emptyDataResponse);
        expect(result).toBeUndefined();
      });

      it("should return undefined for data array without embedding field", () => {
        const noEmbeddingResponse = {
          data: [{ index: 0, text: "not an embedding" }],
        };
        const result = parseCompletionOutput(noEmbeddingResponse);
        expect(result).toBeUndefined();
      });
    });
  });

  describe("parseUsage", () => {
    describe("embedding usage (no completion_tokens)", () => {
      it("should parse embedding usage with prompt_tokens and total_tokens only", () => {
        const embeddingResponse = {
          usage: {
            prompt_tokens: 8,
            total_tokens: 8,
          },
        };

        const result = parseUsage(embeddingResponse);

        expect(result).toBeDefined();
        expect(result?.prompt_tokens).toBe(8);
        expect(result?.total_tokens).toBe(8);
        expect(result?.completion_tokens).toBe(0);
      });

      it("should include original_usage fields", () => {
        const embeddingResponse = {
          usage: {
            prompt_tokens: 10,
            total_tokens: 10,
          },
        };

        const result = parseUsage(embeddingResponse);

        expect(result).toBeDefined();
        expect(result?.["original_usage.prompt_tokens"]).toBe(10);
        expect(result?.["original_usage.total_tokens"]).toBe(10);
      });
    });

    describe("completion usage (existing behavior)", () => {
      it("should parse standard completion usage with all three token fields", () => {
        const completionResponse = {
          usage: {
            prompt_tokens: 10,
            completion_tokens: 20,
            total_tokens: 30,
          },
        };

        const result = parseUsage(completionResponse);

        expect(result).toBeDefined();
        expect(result?.prompt_tokens).toBe(10);
        expect(result?.completion_tokens).toBe(20);
        expect(result?.total_tokens).toBe(30);
      });

      it("should parse response API usage format", () => {
        const responseApiUsage = {
          usage: {
            input_tokens: 15,
            output_tokens: 25,
            total_tokens: 40,
          },
        };

        const result = parseUsage(responseApiUsage);

        expect(result).toBeDefined();
        expect(result?.prompt_tokens).toBe(25);
        expect(result?.completion_tokens).toBe(15);
        expect(result?.total_tokens).toBe(40);
      });
    });

    describe("edge cases", () => {
      it("should return undefined for missing usage", () => {
        const noUsageResponse = { model: "gpt-4" };
        const result = parseUsage(noUsageResponse);
        expect(result).toBeUndefined();
      });

      it("should return undefined for null usage", () => {
        const nullUsageResponse = { usage: null };
        const result = parseUsage(nullUsageResponse);
        expect(result).toBeUndefined();
      });

      it("should return undefined for empty object", () => {
        const result = parseUsage({});
        expect(result).toBeUndefined();
      });
    });
  });
});

