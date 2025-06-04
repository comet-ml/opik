import { BaseMetric, EvaluationScoreResult } from "opik";

interface OneOutputMetricArgs {
  output: string;
}

class BasicSyncMetric extends BaseMetric<OneOutputMetricArgs> {
  constructor() {
    super("basic_sync_metric", false);
  }

  score(input: OneOutputMetricArgs): EvaluationScoreResult {
    return {
      name: this.name,
      value: input.output.length > 0 ? 1.0 : 0.0,
      reason: input.output.length > 0 ? "Non-empty input" : "Empty input",
    };
  }
}

class LengthThresholdMetric extends BaseMetric<OneOutputMetricArgs> {
  constructor(private readonly minLength: number) {
    super("length_threshold_metric", false);
  }

  score(input: OneOutputMetricArgs): EvaluationScoreResult {
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

class CompositeMetric extends BaseMetric<OneOutputMetricArgs> {
  constructor(private readonly metrics: BaseMetric<OneOutputMetricArgs>[]) {
    super("composite_metric", false);
  }

  score(input: OneOutputMetricArgs): EvaluationScoreResult {
    const results = this.metrics.map((metric) => metric.score(input));
    const avgScore =
      results.reduce((sum, r) => sum + r.value, 0) / results.length;

    return {
      name: this.name,
      value: avgScore,
      reason: `Average score from ${results.length} metrics`,
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
    it("should calculate average score", () => {
      const metrics = [new BasicSyncMetric(), new LengthThresholdMetric(3)];
      const composite = new CompositeMetric(metrics);

      expect(composite.score({ output: "test" }).value).toBe(1.0);
    });
  });
});
