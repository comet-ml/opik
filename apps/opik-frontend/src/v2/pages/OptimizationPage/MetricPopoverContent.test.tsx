import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";

import MetricPopoverContent from "./MetricPopoverContent";
import { METRIC_TYPE } from "@/types/optimizations";

describe("MetricPopoverContent", () => {
  it("does not render a redundant metric-name title (the pill already names it)", () => {
    render(
      <MetricPopoverContent
        metric={{
          type: METRIC_TYPE.G_EVAL,
          label: "Custom (G-Eval)",
          parameters: { task_introduction: "Judge the answer" },
        }}
      />,
    );
    expect(screen.queryByText("Custom (G-Eval)")).not.toBeInTheDocument();
  });

  it("renders G-Eval parameters with human labels", () => {
    render(
      <MetricPopoverContent
        metric={{
          type: METRIC_TYPE.G_EVAL,
          label: "Custom (G-Eval)",
          parameters: {
            task_introduction: "Judge the answer",
            evaluation_criteria: "Score between 0 and 1",
          },
        }}
      />,
    );
    expect(screen.getByText("Task introduction")).toBeInTheDocument();
    expect(screen.getByText("Judge the answer")).toBeInTheDocument();
    expect(screen.getByText("Evaluation criteria")).toBeInTheDocument();
    expect(screen.getByText("Score between 0 and 1")).toBeInTheDocument();
  });

  it("renders booleans as Yes/No", () => {
    render(
      <MetricPopoverContent
        metric={{
          type: METRIC_TYPE.EQUALS,
          label: "Equals",
          parameters: { reference_key: "answer", case_sensitive: false },
        }}
      />,
    );
    expect(screen.getByText("Reference key")).toBeInTheDocument();
    expect(screen.getByText("answer")).toBeInTheDocument();
    expect(screen.getByText("Case sensitive")).toBeInTheDocument();
    expect(screen.getByText("No")).toBeInTheDocument();
  });

  it("shows a placeholder when there are no parameters", () => {
    render(
      <MetricPopoverContent
        metric={{ type: METRIC_TYPE.G_EVAL, label: "Custom (G-Eval)" }}
      />,
    );
    expect(screen.getByText("No additional configuration")).toBeInTheDocument();
  });
});
