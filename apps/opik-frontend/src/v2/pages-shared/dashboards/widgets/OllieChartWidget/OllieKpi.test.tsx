import React from "react";
import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import OllieKpi, { formatMetric, parseKpiSpec, KpiSpec } from "./OllieKpi";

const kpi = (better: KpiSpec["better"]): KpiSpec => ({
  value: "value",
  period: "period",
  aggregate: "sum",
  format: "cost_total",
  better,
  window_days: 30,
});

describe("formatMetric", () => {
  it.each([
    [4210, "cost_total", "$4.21k"],
    [0.004, "cost_total", "<$0.01"],
    [0.0042, "cost_item", "$0.0042"],
    [0.123, "rate", "12.3%"],
    [0.7361, "score", "0.736"],
    [2310, "latency", "2.31 s"],
    [1_234_567, "count", "1.2M"],
  ] as const)("formats %s as %s → %s", (value, format, expected) => {
    expect(formatMetric(value, format)).toBe(expected);
  });
});

describe("OllieKpi", () => {
  it("reads a KPI spec and nothing else", () => {
    expect(parseKpiSpec({ kpi: kpi(null) })).not.toBeNull();
    expect(parseKpiSpec({ mark: "bar" })).toBeNull();
    expect(
      parseKpiSpec({ kpi: { value: "value", format: "dollars" } }),
    ).toBeNull();
  });

  it("shows the total and its change on the window before, coloured by what is better", () => {
    // Values arrive as strings from the widget query; they are still numbers.
    const rows = [
      { period: "d1", value: "200" },
      { period: "d2", value: "212.8" },
    ];

    render(
      <OllieKpi
        kpi={kpi("lower")}
        rows={rows}
        previousRows={[{ value: 450 }]}
        comparedWith="previous 30 days"
      />,
    );

    expect(screen.getByText("$413")).toBeTruthy();
    expect(screen.getByText(/▼ 8\.3%/).className).toContain("text-chart-green");
    expect(screen.getByText(/vs previous 30 days/)).toBeTruthy();
  });
});
