import { describe, it, expect, vi, beforeEach } from "vitest";
import { BaseMetric, EvaluationScoreResult } from "opik";
import { z } from "zod";

const validationSchema = z.object({
  output: z.string(),
});
type Input = z.infer<typeof validationSchema>;

class BasicAsyncMetric extends BaseMetric {
  constructor(private readonly delayMs = 0) {
    super("basic_async_metric", false);
  }

  public validationSchema = validationSchema;

  async score(input: Input): Promise<EvaluationScoreResult> {
    await new Promise((resolve) => setTimeout(resolve, this.delayMs));
    return {
      name: this.name,
      value: input.output.length > 0 ? 1.0 : 0.0,
      reason: `Processed input of length ${input.output.length}`,
    };
  }
}

class ApiResponseValidator extends BaseMetric {
  constructor(
    private readonly validateFn: (response: unknown) => boolean,
    private readonly apiEndpoint: string
  ) {
    super("api_response_validator", false);
  }

  public validationSchema = validationSchema;

  async score(input: Input): Promise<EvaluationScoreResult> {
    try {
      const response = await fetch(this.apiEndpoint, {
        method: "POST",
        body: JSON.stringify({ input: input.output }),
      });
      const data = await response.json();
      const isValid = this.validateFn(data);

      return {
        name: this.name,
        value: isValid ? 1.0 : 0.0,
        reason: isValid ? "API validation passed" : "API validation failed",
      };
    } catch (error) {
      return {
        name: this.name,
        value: 0.0,
        reason: `API error: ${error instanceof Error ? error.message : String(error)}`,
      };
    }
  }
}

class ContentModerationMetric extends BaseMetric {
  private readonly bannedWords = ["spam", "scam", "fraud"];
  private readonly moderationApi = "https://api.moderatecontent.com/text/";

  constructor() {
    super("content_moderation_metric", false);
  }

  public validationSchema = validationSchema;

  async score(input: Input): Promise<EvaluationScoreResult> {
    try {
      const hasBannedWord = this.bannedWords.some((word) =>
        input.output.toLowerCase().includes(word)
      );

      if (hasBannedWord) {
        return {
          name: this.name,
          value: 0.0,
          reason: "Contains banned words",
        };
      }

      const response = await fetch(this.moderationApi, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ text: input }),
      });

      if (!response.ok) {
        throw new Error(`API error: ${response.status}`);
      }

      const result = await response.json();
      return {
        name: this.name,
        value: result.isApproved ? 1.0 : 0.0,
        reason: result.isApproved ? "Content approved" : "Content rejected",
      };
    } catch (error) {
      return {
        name: this.name,
        value: 0.0,
        reason: `Moderation error: ${error instanceof Error ? error.message : String(error)}`,
      };
    }
  }
}

class SentimentAnalysisMetric extends BaseMetric {
  constructor() {
    super("sentiment_analysis_metric", false);
  }

  public validationSchema = validationSchema;

  async score(input: Input): Promise<EvaluationScoreResult> {
    await new Promise((resolve) => setTimeout(resolve, 50)); // Simulate API delay

    const positiveWords = ["good", "great", "excellent", "happy"];
    const negativeWords = ["bad", "terrible", "awful", "sad"];

    const words = input.output.toLowerCase().split(/\s+/);
    let score = 0.5; // Neutral

    words.forEach((word) => {
      if (positiveWords.includes(word)) score += 0.1;
      if (negativeWords.includes(word)) score -= 0.1;
    });

    score = Math.max(0, Math.min(1, score));

    return {
      name: this.name,
      value: score,
      reason: `Sentiment score: ${score.toFixed(2)}`,
    };
  }
}

class CompositeAsyncMetric extends BaseMetric {
  constructor(private readonly metrics: BaseMetric[]) {
    super("composite_async_metric", false);
  }

  public validationSchema = validationSchema;

  async score(input: Input): Promise<EvaluationScoreResult> {
    const results = await Promise.all(
      this.metrics.map(async (metric) => {
        try {
          return await metric.score(input);
        } catch (error) {
          return {
            name: metric.name,
            value: 0.0,
            reason: `Error in ${metric.name}: ${error instanceof Error ? error.message : String(error)}`,
          };
        }
      })
    );

    const flatResults = results.flatMap((result) =>
      Array.isArray(result) ? result : [result]
    );

    const totalScore = flatResults.reduce((sum, r) => sum + r.value, 0);
    const avgScore =
      flatResults.length > 0 ? totalScore / flatResults.length : 0;

    return {
      name: this.name,
      value: avgScore,
      reason: `Average score from ${flatResults.length} metrics`,
    };
  }
}

describe("Async Custom Metrics", () => {
  describe("BasicAsyncMetric", () => {
    it("should process input asynchronously", async () => {
      const metric = new BasicAsyncMetric(10);
      const result = await metric.score({ output: "test" });
      expect(result.value).toBe(1.0);
      expect(result.reason).toContain("Processed input");
    });
  });

  describe("ApiResponseValidator", () => {
    beforeEach(() => {
      global.fetch = vi.fn();
    });

    it("should validate API responses", async () => {
      const mockFetch = vi.mocked(global.fetch);

      mockFetch.mockResolvedValueOnce({
        ok: true,
        json: async () => ({ valid: true, score: 0.9 }),
      } as Response);

      interface ApiResponse {
        valid: boolean;
        score: number;
      }

      const validator = new ApiResponseValidator((data: unknown) => {
        const response = data as ApiResponse;
        return response.valid && response.score > 0.8;
      }, "https://api.example.com/validate");

      const result = await validator.score({ output: "test input" });
      expect(result.value).toBe(1.0);
    });
  });

  describe("ContentModerationMetric", () => {
    it("should detect banned words", async () => {
      const metric = new ContentModerationMetric();
      const result = await metric.score({ output: "This is a spam message" });
      expect(result.value).toBe(0.0);
      expect(result.reason).toBe("Contains banned words");
    });
  });

  describe("SentimentAnalysisMetric", () => {
    it("should analyze sentiment", async () => {
      const metric = new SentimentAnalysisMetric();
      const positiveResult = await metric.score({
        output: "I am happy with this",
      });
      const negativeResult = await metric.score({ output: "This is bad" });

      expect(positiveResult.value).toBeGreaterThan(0.5);
      expect(negativeResult.value).toBeLessThan(0.5);
    });
  });

  describe("CompositeAsyncMetric", () => {
    it("should combine multiple metrics", async () => {
      const metrics = [new BasicAsyncMetric(), new SentimentAnalysisMetric()];

      const composite = new CompositeAsyncMetric(metrics);
      const result = await composite.score({ output: "This is a good test" });

      expect(result.value).toBeGreaterThan(0); // Should be between 0 and 1
      expect(result.reason).toContain("Average score");
    });
  });
});
