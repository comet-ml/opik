import React from "react";
import { describe, it, expect, vi } from "vitest";
import { render, screen } from "@testing-library/react";

// The real CodeMirror editor doesn't mount cleanly under this test
// environment (unrelated to the behavior under test — a duplicate
// @codemirror/state instance breaks its internal instanceof checks). This
// test only asserts on the static helper copy around the editor, so stub it
// out rather than exercising the third-party editor internals.
vi.mock("@uiw/react-codemirror", () => ({
  default: () => <div data-testid="codemirror-stub" />,
}));

import CodeMetricConfigs from "./CodeMetricConfigs";

describe("CodeMetricConfigs — helper copy", () => {
  it("recommends kwargs.get(...) instead of raw **kwargs access", () => {
    render(<CodeMetricConfigs configs={{}} onChange={vi.fn()} />);

    // Regression guard (OPIK-7172): a required `kwargs["x"]` access used to be
    // rejected at build time, so the helper text must steer users toward the
    // safe `kwargs.get(...)` accessor instead of implying raw subscripting.
    expect(screen.getByText('kwargs.get("field_name")')).toBeInTheDocument();
  });

  it("still documents the BaseMetric/score contract", () => {
    render(<CodeMetricConfigs configs={{}} onChange={vi.fn()} />);

    expect(screen.getByText("BaseMetric")).toBeInTheDocument();
    expect(screen.getAllByText("score").length).toBeGreaterThan(0);
    expect(screen.getByText("output")).toBeInTheDocument();
  });
});
