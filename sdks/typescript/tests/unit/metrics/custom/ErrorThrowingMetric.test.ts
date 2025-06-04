import { BaseMetric, EvaluationScoreResult } from "opik";

class ErrorThrowingMetric extends BaseMetric {
  constructor(private readonly shouldThrow: boolean) {
    super("error_metric", false);
  }

  async score(): Promise<EvaluationScoreResult> {
    if (this.shouldThrow) {
      throw new Error("Test error");
    }

    return { name: this.name, value: 1.0, reason: "OK" };
  }
}

it("should handle async errors", async () => {
  const metric = new ErrorThrowingMetric(true);
  await expect(metric.score()).rejects.toThrow("Test error");
});
