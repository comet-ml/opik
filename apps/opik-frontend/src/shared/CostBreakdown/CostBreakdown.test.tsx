import React from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen } from "@testing-library/react";
import { ModelPriceResolution } from "@/lib/model-prices";
import SpanCostBreakdown from "./SpanCostBreakdown";
import ThreadCostBreakdown from "./ThreadCostBreakdown";

const { resolve, threadSpans } = vi.hoisted(() => ({
  resolve: vi.fn(),
  threadSpans: vi.fn(),
}));

vi.mock("@/api/model-prices/useModelPriceResolve", () => ({
  default: (request: unknown) => resolve(request),
}));

vi.mock("@/api/model-prices/useBuiltInPrices", () => ({
  default: () => ({
    data: [{ model: "gpt-4.1-mini", provider: "openai" }],
  }),
}));

vi.mock("./useThreadCostSpans", () => ({
  default: () => threadSpans(),
}));

const OVERRIDE: ModelPriceResolution = {
  model: "gpt-4.1-mini",
  provider: "openai",
  cost: "0.0000114",
  cost_source: "organization",
  list_version: 6,
  match: {
    provider: "openai",
    name_form: "exact",
    row: {
      model: "gpt-4.1-mini",
      provider: "openai",
      kind: "fixed",
      input_price: "0.12",
      output_price: "0.6",
    },
  },
  lines: [
    { name: "input", rate_per_million: "0.12", source: "organization" },
    { name: "output", rate_per_million: "0.6", source: "organization" },
    { name: "cache_read", rate_per_million: "0", source: "none" },
    { name: "cache_write", rate_per_million: "0", source: "none" },
  ],
};

const span = {
  id: "span-1",
  model: "gpt-4.1-mini",
  provider: "openai",
  usage: { prompt_tokens: 90, completion_tokens: 1, total_tokens: 91 },
  total_estimated_cost: 0.0000114,
  total_estimated_cost_version: "1.1;o6",
};

describe("SpanCostBreakdown", () => {
  beforeEach(() => {
    resolve.mockReset();
  });

  it("shows the prices the span ran with and exact amounts", () => {
    resolve.mockReturnValue({ data: OVERRIDE, isPending: false });
    render(<SpanCostBreakdown span={span} />);

    expect(screen.getByText("$0.0000114")).toBeTruthy();
    expect(screen.getByText("Prices used when this span ran")).toBeTruthy();
    expect(screen.getByText("Override")).toBeTruthy();
    expect(screen.getByText("· Still current")).toBeTruthy();
    expect(screen.getByText("$0.0000108")).toBeTruthy();
    expect(screen.getByText("$0.0000006")).toBeTruthy();
    expect(screen.queryByText(/may not add up/)).toBeNull();

    fireEvent.click(screen.getByText("Calculation details"));

    expect(screen.getByText("prompt_tokens")).toBeTruthy();
    expect(screen.getByText("90 × $0.120 ÷ 1M = $0.0000108")).toBeTruthy();
    expect(resolve).toHaveBeenCalledWith({
      model: "gpt-4.1-mini",
      provider: "openai",
      usage: { prompt_tokens: 90, completion_tokens: 1, total_tokens: 91 },
    });
  });

  it("says when prices changed since the span ran", () => {
    resolve.mockReturnValue({ data: OVERRIDE, isPending: false });
    render(
      <SpanCostBreakdown
        span={{
          ...span,
          total_estimated_cost: 0.00002,
          total_estimated_cost_version: "1.1;o4",
        }}
      />,
    );

    expect(screen.getByText("Today's prices")).toBeTruthy();
    expect(
      screen.getByText(
        "Prices changed since this span ran (price list v4 → v6). The rates below are today's: this span would cost $0.0000114 now.",
      ),
    ).toBeTruthy();
  });

  it("flags lines that don't add up to the backend's cost", () => {
    resolve.mockReturnValue({
      data: { ...OVERRIDE, cost: "0.00002" },
      isPending: false,
    });
    render(
      <SpanCostBreakdown span={{ ...span, total_estimated_cost: 0.00002 }} />,
    );

    expect(screen.getByText(/may not add up exactly/)).toBeTruthy();
  });

  it("doesn't explain a cost the app sent", () => {
    resolve.mockReturnValue({ isPending: true });
    render(
      <SpanCostBreakdown
        span={{ ...span, total_estimated_cost_version: undefined }}
      />,
    );

    expect(
      screen.getByText("Cost sent by your app; Opik didn't estimate it."),
    ).toBeTruthy();
    expect(screen.queryByText("Calculation details")).toBeNull();
  });
});

describe("ThreadCostBreakdown", () => {
  it("groups the thread's spans by model and says when traces were left out", () => {
    resolve.mockReturnValue({ data: OVERRIDE, isPending: false });
    threadSpans.mockReturnValue({
      spans: [
        span,
        { ...span, id: "span-2" },
        {
          ...span,
          id: "span-3",
          model: "claude-haiku-4-5",
          provider: "anthropic",
          usage: { input_tokens: 10, output_tokens: 5 },
          total_estimated_cost: 0.00003,
        },
        {
          ...span,
          id: "span-4",
          total_estimated_cost: 0.001,
          total_estimated_cost_version: "",
        },
      ],
      isPending: false,
      isError: false,
      isTruncated: false,
      tracesShown: 20,
      tracesTotal: 57,
    });

    render(<ThreadCostBreakdown projectId="p" threadId="t" cost={0.0021} />);

    expect(screen.getByText("4 spans with a cost · 2 models")).toBeTruthy();
    expect(screen.getByText("claude-haiku-4-5")).toBeTruthy();
    expect(screen.getByText("Sent by your app")).toBeTruthy();
    expect(
      screen.getByText("Based on the first 20 of 57 traces with a cost."),
    ).toBeTruthy();

    fireEvent.click(screen.getByText("gpt-4.1-mini"));

    expect(screen.getByText("Prices used when these spans ran")).toBeTruthy();
    expect(screen.getByText("$0.0000216")).toBeTruthy();
  });
});
