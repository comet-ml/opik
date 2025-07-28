import { BaseMetric, EvaluationScoreResult } from "opik";
import { z } from "zod";

const validationSchema = z.object({
  output: z.string(),
});
type Input = z.infer<typeof validationSchema>;

class BasicSyncMetric extends BaseMetric {
  constructor() {
    super("basic_sync_metric", false);
  }

  public validationSchema = validationSchema;

  score(input: Input): EvaluationScoreResult {
    return {
      name: this.name,
      value: input.output.length > 0 ? 1.0 : 0.0,
      reason: input.output.length > 0 ? "Non-empty input" : "Empty input",
    };
  }
}

class LengthThresholdMetric extends BaseMetric {
  constructor(private readonly minLength: number) {
    super("length_threshold_metric", false);
  }

  public validationSchema = validationSchema;

  score(input: Input): EvaluationScoreResult {
    const isValid = input.output.length >= this.minLength;
    return {
      name: this.name,
      value: isValid ? 1.0 : 0.0,
      reason: isValid
        ? `Input length (${input.output.length}) meets threshold (${this.minLength})`
        : `Input too short (${input.output.length}/${this.minLength})`,
    };
  }
}

class CompositeMetric extends BaseMetric {
  constructor(private readonly metrics: BaseMetric[]) {
    super("composite_metric", false);
  }

  public validationSchema = validationSchema;

  async score(input: Input): Promise<EvaluationScoreResult> {
    const results = await Promise.all(
      this.metrics.map(async (metric) => {
        const result = metric.score(input);
        const resolvedResult =
          result instanceof Promise ? await result : result;
        return Array.isArray(resolvedResult)
          ? resolvedResult
          : [resolvedResult];
      })
    );

    const flattenedResults = results.flat();
    const totalScore = flattenedResults.reduce((sum, r) => sum + r.value, 0);
    const avgScore =
      flattenedResults.length > 0 ? totalScore / flattenedResults.length : 0;

    return {
      name: this.name,
      value: avgScore,
      reason: `Average score from ${flattenedResults.length} metrics`,
    };
  }
}

describe("Sync Custom Metrics", () => {
  describe("BasicSyncMetric", () => {
    it("should return 1.0 for non-empty strings", () => {
      const metric = new BasicSyncMetric();
      expect(metric.score({ output: "test" }).value).toBe(1.0);
    });

    it("should return 0.0 for empty strings", () => {
      const metric = new BasicSyncMetric();
      expect(metric.score({ output: "" }).value).toBe(0.0);
    });
  });

  describe("LengthThresholdMetric", () => {
    it("should validate minimum length", () => {
      const metric = new LengthThresholdMetric(5);
      expect(metric.score({ output: "12345" }).value).toBe(1.0);
      expect(metric.score({ output: "123456" }).value).toBe(1.0);
      expect(metric.score({ output: "1234" }).value).toBe(0.0);
    });
  });

  describe("CompositeMetric", () => {
    it("should calculate average score", async () => {
      const metrics = [new BasicSyncMetric(), new LengthThresholdMetric(3)];
      const composite = new CompositeMetric(metrics);

      expect((await composite.score({ output: "test" })).value).toBe(1.0);
    });
  });
});
