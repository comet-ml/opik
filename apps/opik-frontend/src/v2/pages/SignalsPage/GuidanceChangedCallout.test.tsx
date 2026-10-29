import React from "react";
import { describe, it, expect, vi, beforeEach } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

const plugins = vi.hoisted(() => ({
  BillingLink: null as null | React.FC<{ label?: string }>,
}));

vi.mock("@/store/PluginsStore", () => ({
  default: (selector: (state: typeof plugins) => unknown) => selector(plugins),
}));

import GuidanceChangedCallout from "./GuidanceChangedCallout";

const TestBillingLink: React.FC<{ label?: string }> = ({ label }) => (
  <a href="#billing">{label}</a>
);

describe("GuidanceChangedCallout", () => {
  beforeEach(() => {
    plugins.BillingLink = null;
  });

  it("offers to run a new diagnostic", () => {
    const onRun = vi.fn();
    render(<GuidanceChangedCallout onRun={onRun} />);

    fireEvent.click(
      screen.getByRole("button", { name: "Run a new diagnostic" }),
    );

    expect(onRun).toHaveBeenCalledTimes(1);
  });

  it("says there aren't enough credits, with a billing link, instead of the run link", () => {
    plugins.BillingLink = TestBillingLink;
    render(<GuidanceChangedCallout onRun={vi.fn()} outOfCredits />);

    expect(
      screen.getByText(
        /There aren’t enough Ollie credits for a new diagnostic\./,
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "View billing" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Run a new diagnostic" }),
    ).not.toBeInTheDocument();
  });

  it("leaves out the billing link where billing isn't available", () => {
    render(<GuidanceChangedCallout outOfCredits />);

    expect(
      screen.getByText(
        /There aren’t enough Ollie credits for a new diagnostic\./,
      ),
    ).toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
  });

  it("shows no action without permission to run", () => {
    render(<GuidanceChangedCallout />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
