import { BaseMetric, EvaluationScoreResult } from "opik";

class BasicSyncMetric extends BaseMetric {
  constructor() {
    super("basic_sync_metric", false);
  }

  score(input: string): EvaluationScoreResult {
    return {
      name: this.name,
      value: input.length > 0 ? 1.0 : 0.0,
      reason: input.length > 0 ? "Non-empty input" : "Empty input",
    };
  }
}

class LengthThresholdMetric extends BaseMetric {
  constructor(private readonly minLength: number) {
    super("length_threshold_metric", false);
  }

  score(input: string): EvaluationScoreResult {
    const isValid = input.length >= this.minLength;
    return {
      name: this.name,
      value: isValid ? 1.0 : 0.0,
      reason: isValid
        ? `Input length (${input.length}) meets threshold (${this.minLength})`
        : `Input too short (${input.length}/${this.minLength})`,
    };
  }
}

class CompositeMetric extends BaseMetric {
  constructor(private readonly metrics: BaseMetric[]) {
    super("composite_metric", false);
  }

  score(input: string): EvaluationScoreResult {
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
      expect(metric.score("test").value).toBe(1.0);
    });

    it("should return 0.0 for empty strings", () => {
      const metric = new BasicSyncMetric();
      expect(metric.score("").value).toBe(0.0);
    });
  });

  describe("LengthThresholdMetric", () => {
    it("should validate minimum length", () => {
      const metric = new LengthThresholdMetric(5);
      expect(metric.score("12345").value).toBe(1.0); // exact
      expect(metric.score("123456").value).toBe(1.0); // above
      expect(metric.score("1234").value).toBe(0.0); // below
    });
  });

  describe("CompositeMetric", () => {
    it("should calculate average score", () => {
      const metrics = [new BasicSyncMetric(), new LengthThresholdMetric(3)];
      const composite = new CompositeMetric(metrics);

      expect(composite.score("test").value).toBe(1.0);
    });
  });
});
