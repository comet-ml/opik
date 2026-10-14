import { describe, it, expect, vi } from "vitest";
import { render, screen, fireEvent } from "@testing-library/react";

vi.mock("@/store/PluginsStore", () => ({
  default: () => null,
}));

import GuidanceChangedCallout from "./GuidanceChangedCallout";

describe("GuidanceChangedCallout", () => {
  it("offers to run a new diagnostic", () => {
    const onRun = vi.fn();
    render(<GuidanceChangedCallout onRun={onRun} />);

    fireEvent.click(
      screen.getByRole("button", { name: "Run a new diagnostic" }),
    );

    expect(onRun).toHaveBeenCalledTimes(1);
  });

  it("says Out of Ollie credits instead of the run link when credits ran out", () => {
    render(<GuidanceChangedCallout onRun={vi.fn()} outOfCredits />);

    expect(
      screen.getByRole("button", { name: "Out of Ollie credits" }),
    ).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: "Run a new diagnostic" }),
    ).not.toBeInTheDocument();
  });

  it("shows no action without permission to run", () => {
    render(<GuidanceChangedCallout />);

    expect(screen.queryByRole("button")).not.toBeInTheDocument();
  });
});
